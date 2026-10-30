(ns musics.play
  "mu's play words as Clojure functions, done for their effect: stopping,
   the voices, hushing, pausing, echoing, the synthesizer and the GUI --
   registered into mu's play namespace by musics.mu.library. Starting
   music (play, >, play-next, >>, …) reads the stack's shape -- a
   context under the music, a mark, a voice on top -- and stays a stack
   word in musics.mu.kernel."
  (:require [clojure.string :as str]
            [musics.algo.tree :as t]
            [musics.engine :as engine]
            [musics.mu.eval :as e]
            [musics.midi.gervill :as gervill]
            [musics.mu.feedback :as feedback]
            [musics.mu.live :as live]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- voice-id
  "The id of the voice a voice's id or index names, which must play."
  [word voice]
  (when (and (number? voice) (not (integer? voice)))
    (fail "takes a voice's id or index (:bass, 1); to compare numbers write lt"))
  (let [id (mt/voice-id word voice)]
    (when-not (keyword? id) (fail "takes a voice's id or index, :bass, :TA or 1 (the lowest) -- voices lists them"))
    (when-not (engine/voice-at id) (fail (pr-str id) "isn't playing -- voices lists what plays"))
    id))

(defn stop!
  "Stop everything that plays (Alt+.), or with a voice's id or index on
   top only that voice: :bass stop, 1 stop"
  {:mu {:optional {:voice (some-fn keyword? integer?)}}}
  ([] (mt/stop-all!))
  ([voice] (mt/stop-voice! (mt/voice-id "stop" voice))))

(defn voices!
  "What plays: each voice's index (1 the lowest, 0 -1 … the drums), id,
   material, and whether it is hushed or soloed"
  {:mu {:names ["<<"]}}
  []
  (let [vs (mt/live-voices) {:keys [hushed solo]} (engine/hushed)]
    (if (empty? vs)
      (println "nothing plays")
      (let [[pitched drums] (mt/ranked)
            n    (count pitched)
            rows (concat (reverse (map-indexed (fn [i id] [(inc i) id]) pitched)) ; the highest on top, as in a score
                         (map-indexed (fn [i id] [(- i) id]) drums))
            wide #(apply max (map (comp count str %) rows))
            row  (str "%" (wide first) "d %-" (if (pos? n) 7 5) "s %-" (wide (comp pr-str second)) "s  %s%s")]
        (doseq [[i id] rows
                :let [{:keys [x]} (get vs id)
                      shown (p/show x)
                      label (cond (<= i 0) "drums" (= i 1) "lowest" (= i n) "highest" :else "")
                      mark  (cond (= solo [id]) "  solo" (contains? hushed [id]) "  hushed" (and solo (not= solo [id])) "  (not soloed)" :else "")]]
          (println (format row i label (pr-str id)
                           (if (> (count shown) 60) (str (subs shown 0 59) "…") shown) mark)))))))

(defn playing
  "What a voice plays, to change and play again: :bass playing 5 + >>,
   1 < (the lowest)"
  {:mu {:names ["<"]}}
  [voice]
  (mt/playing (voice-id "playing" voice)))

(defn hush!
  "Silence a voice, keeping its place; again and it sounds"
  [voice]
  (engine/hush! (voice-id "hush" voice)))

(defn solo!
  "Only this voice sounds, the others keeping their place; again and all sound"
  [voice]
  (engine/solo! (voice-id "solo" voice)))

(defn pause!
  "Hold what plays, or go on if it is held; Alt+,"
  []
  (let [said (mt/toggle-pause!)]
    (when (= "nothing plays" said) (println said))))

(defn resume!
  "Go on after pause"
  []
  (engine/resume!))

(defn echo!
  "Print each note as it sounds (its instrument or drum), or stop printing"
  []
  (if @engine/echo
    (do (reset! engine/echo nil) (println "echo off"))
    (do (reset! engine/echo (fn [ev] (send-off mt/echo-agent (fn [_] (@e/print-above (mt/echo-line ev)) nil))))
        (println "echo on"))))

(defn fluidsynth!
  "Play through Fluidsynth (qsynth, via VirMIDI)"
  []
  (mt/cancel-staged!)
  (engine/connect! :fluidsynth))

(defn gervill!
  "Play through Gervill, Java's own synthesizer, with FluidR3_GM"
  []
  (mt/cancel-staged!)
  (engine/connect! :gervill))

(defn soundfonts!
  "The .sf2 files soundfont takes by number"
  []
  (doseq [[i f] (map-indexed vector (engine/soundfonts))] (println (str i "> " f))))

(defn soundfont!
  "Gervill with that soundfont, or the n-th of soundfonts; what plays goes on"
  [file-or-n]
  (when-not (or (string? file-or-n) (integer? file-or-n))
    (fail "needs a file in quotes or a number from soundfonts, got" (p/show file-or-n)))
  (println (engine/soundfont! file-or-n)))

(defn try-soundfonts!
  "Play the music on top through every instrument of each of soundfonts
   in turn, or with a text on top of those whose file has it in its name
   (x \"strings\" try-soundfonts), in the background, as an audition: Alt+L the next,
   Alt+J back, Alt+K again, a rating records the soundfont, stop (Alt+.)
   stops; n soundfont keeps one"
  {:mu {:optional {:named string?}}}
  ([x] (try-soundfonts! x ""))
  ([x named]
   (when-not (or (v/material? x) (mt/leaf? x) (mt/endless? x))
     (fail "needs music to play, got" (p/show x)))
   (let [all   (engine/soundfonts)
         fonts (filterv (fn [[_ path]] (str/includes? (str/lower-case path) (str/lower-case named)))
                        (map-indexed vector all))]
     (cond (empty? all)   (fail "no .sf2 file in" (str/join " or " gervill/soundfont-dirs))
           (empty? fonts) (fail "no soundfont has" (pr-str named) "in its name -- soundfonts lists them"))
     (mt/cancel-staged!)
     (feedback/start-soundfont-trial! x fonts))))

(defn- keep-javafx!
  "JavaFX ends when its last window closes, unless told not to: then a
   window couldn't open again. By reflection, so loading mu needs no
   JavaFX class."
  []
  (clojure.lang.Reflector/invokeStaticMethod "javafx.application.Platform" "setImplicitExit" (object-array [false])))

(defn gui!
  "Open musics' GUI beside mu: transport, the parts and their contexts as
   sliders, the Wall, Record MIDI; closing it leaves mu running and gui
   opens it again. With a live voice on top (:riff gui), a window of its
   settings, a control each; with a tree (compose), its settings and a
   preview"
  {:mu {:optional {:x (some-fn keyword? integer? t/node?)}}}
  ([]
   ((requiring-resolve 'musics.gui.core/launch!) :dark)
   (keep-javafx!))
  ([x]
   (if (t/node? x) (t/gui x) (t/gui (live/tctx-of "gui" x)))
   (keep-javafx!)))

;; -- live voices: a play word given a recipe (musics.mu.live) ----------------

(defn set-settings!
  "Change a live voice's settings, heard on its next note: :riff @^{ k:5 } set"
  {:mu {:names ["set"]}}
  [voice settings]
  (when-not (= :TCONTEXT (mt/kind settings))
    (fail "takes a voice and settings, :riff @^{ k:5 } set -- got" (p/show settings)))
  (let [m (live/settings-of settings)]
    (when (empty? m) (fail "the settings are empty -- write @^{ k:5 }"))
    (t/setp! (live/tctx-of "set" voice) m)))

(defn unset!
  "Leave a live voice's setting to its recipe's head and the defaults again: :riff 'k unset"
  [voice nm]
  (when-not (symbol? nm) (fail "takes a setting's name, 'k -- got" (p/show nm)))
  (t/unsetp! (live/tctx-of "unset" voice) (str nm)))

(defn settings!
  "A live voice's settings: each param its recipe reads, its value, range,
   default and what it does"
  [voice]
  (t/describe (live/tctx-of "settings" voice)))

(defn retree!
  "A new recipe for a live voice, its settings and position kept: '( … ) :riff retree"
  [recipe voice]
  (when-not (live/recipe? recipe) (fail "takes a recipe '( … ) and a live voice -- got" (p/show recipe)))
  (live/retree! "retree" recipe voice))

(defn compose
  "The composer window: build a tree by drag and drop; Finalize leaves it
   on the stack, a recipe for a play word (compose :riff >); with a tree,
   edit it"
  {:mu {:optional {:tree t/node?}}}
  ([] (compose nil))
  ([tree]
   (keep-javafx!)
   (or (first (if tree (t/build-tree tree) (t/build-tree)))
       (fail "the composer was closed without a tree"))))
