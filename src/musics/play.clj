(ns musics.play
  "mu's play words as Clojure functions, done for their effect: stopping,
   the voices, hushing, pausing, echoing, the synthesizer and the GUI --
   registered into mu's play namespace by musics.mu.library. Starting
   music (play, >, play-next, >>, …) reads the stack's shape -- a
   context under the music, a mark, a voice on top -- and stays a stack
   word in musics.mu.kernel."
  (:require [clojure.string :as str]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]))

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
  (m/resume!))

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
  (m/connect :fluidsynth))

(defn gervill!
  "Play through Gervill, Java's own synthesizer, with FluidR3_GM"
  []
  (mt/cancel-staged!)
  (m/connect :gervill))

(defn soundfonts!
  "The .sf2 files soundfont takes by number"
  []
  (doseq [[i f] (map-indexed vector (m/soundfonts))] (println (str i "> " f))))

(defn soundfont!
  "Gervill with that soundfont, or the n-th of soundfonts; what plays goes on"
  [file-or-n]
  (when-not (or (string? file-or-n) (integer? file-or-n))
    (fail "needs a file in quotes or a number from soundfonts, got" (p/show file-or-n)))
  (println (m/soundfont file-or-n)))

(defn gui!
  "Open musics' GUI beside mu: transport, the parts and their contexts as
   sliders, the Wall, Record MIDI; closing it leaves mu running and gui
   opens it again"
  []
  (m/gui)
  ;; JavaFX ends when its last window closes, unless told not to: then
  ;; gui couldn't open it again. By reflection, so loading mu needs no
  ;; JavaFX class.
  (clojure.lang.Reflector/invokeStaticMethod "javafx.application.Platform" "setImplicitExit" (object-array [false])))
