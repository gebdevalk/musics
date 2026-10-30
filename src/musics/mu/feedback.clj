(ns musics.mu.feedback
  "What mu's rate words (musics.rate) and the prompt's Alt keys share
   (doc/md/feedback.md): rating what is heard -- the audition's item,
   else the sound last played -- and auditioning a line of sounds one by
   one. The ratings themselves are musics.feedback's."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.engine :as engine]
            [musics.feedback :as fb]
            [musics.midi.gervill :as gervill]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.print :as p])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn sound?
  "A note, chord or drum -- what can be rated as a sound."
  [x]
  (boolean (and (mt/leaf? x) (#{:LEAF :DRUM} (:type x)))))

(defn- say [line] (@e/print-above line))

(defn part-name
  "The name of an instrument program or a drum key, by kind."
  [kind n]
  (if (= kind :drum)
    (get-in data/drums [n :name])
    (some-> (data/program->name n) name)))

(defn describe
  "A sound's text, with the names of its instruments or drums."
  [text]
  (let [{:keys [kind keys instruments]} (fb/sound-parts text)
        parts (if (= kind :drum) (map #(vector :drum %) keys) (map #(vector :instrument %) instruments))]
    (str text "  " (str/join " + " (keep (fn [[k n]] (part-name k n)) parts)))))

;; -- audition ---------------------------------------------------------------

(defonce ^{:doc "nil, or the audition going on: {:items [{:value x :show text :play f} ...] :i n :queue q}."}
  audition (atom nil))

(defonce ^{:doc "true: an audition moves on after each item by itself; false: it waits."}
  auto? (atom true))

(defonce ^{:doc "The silence (ms) after an item before an auto audition moves on; the word gap sets it."}
  gap-ms (atom 150))

(defn- command!
  "Tell the audition going on to go :next, :back, :again or :stop."
  [cmd]
  (when-let [{:keys [^LinkedBlockingQueue queue]} @audition]
    (.put queue cmd)
    true))

(defn- await-command
  "The next command, or :next once the item has been silent gap-ms in
   auto mode."
  [^LinkedBlockingQueue q]
  (loop [quiet-since nil]
    (or (.poll q 20 TimeUnit/MILLISECONDS)
        (let [now (System/nanoTime)
              qs  (when-not (engine/busy?) (or quiet-since now))]
          (if (and @auto? qs (> (- now qs) (* @gap-ms 1000000)))
            :next
            (recur qs))))))

(def keys-hint "Alt+j dislike · Alt+k meh · Alt+l like · Alt+K again")

(defn- run-audition [items q]
  (try
    (say keys-hint)
    (loop [i 0]
      (if (>= i (count items))
        (say "audition: done")
        (let [{:keys [show play]} (items i)]
          (swap! audition assoc :i i)
          (say show)
          (play)
          (case (await-command q)
            :next  (recur (inc i))
            :back  (recur (max 0 (dec i)))
            :again (recur i)
            :stop  nil))))
    (catch Throwable t (say (str "audition stopped: " (.getMessage t))))
    (finally (reset! audition nil))))

(defn stop-audition! [] (command! :stop))

(swap! mt/stoppers assoc ::audition stop-audition!)

(defn- start! [items]
  (stop-audition!)
  (let [q (LinkedBlockingQueue.)]
    (reset! audition {:items items :i 0 :queue q})
    (doto (Thread. ^Runnable #(run-audition items q) "mu-audition")
      (.setDaemon true)
      (.start))))

(defn start-audition!
  "Audition sounds one by one, in the background."
  [sounds]
  (let [n (count sounds)]
    (start! (vec (map-indexed (fn [i x] {:value x :show (str (inc i) "/" n "  " (describe (p/show x))) :play #(mt/play! x nil)})
                              sounds)))))

(defn- presets
  "[[program name] ...]: the instruments of the .sf2 at path a program
   change reaches -- bank 0, the drum kits left out."
  [path]
  (->> (gervill/instruments path)
       (remove :percussion?)
       (filter #(zero? (:bank %)))
       (map (juxt :program (comp str/trim :name)))
       (into (sorted-map))))

(defn- named?
  "Does text name the instrument, in its own name or its General MIDI
   program's (frenchHorn, horn), in any case?"
  [text program name]
  (let [squash #(str/replace (str/lower-case %) #"[-_ ]" "")
        t      (squash text)]
    (or (str/includes? (squash name) t)
        (some-> (data/program->name program) clojure.core/name squash (str/includes? t)))))

(defn start-soundfont-trial!
  "Play x through Gervill with every instrument of each soundfont of
   fonts, [[n path] ...], or those text names (named?), in turn, in the
   background, as an audition: ^{ !i:p } over x, a soundfont loaded when
   its first instrument is reached, each shown by n, the number soundfont
   takes it by. nil when no instrument is left to play."
  [x fonts text]
  (let [loaded (atom nil)
        load!  (fn [path] (when (not= path @loaded) (engine/soundfont! path) (reset! loaded path)))
        found  (vec (for [[n path] fonts
                          [program name] (presets path)
                          :when (named? text program name)]
                      [n path program name]))
        total  (count found)]
    (when (seq found)
      (start! (vec (map-indexed
                    (fn [i [n path program name]]
                      {:value x
                       :show  (str (inc i) "/" total "  " n "> " (.getName (java.io.File. ^String path))
                                   "  !i:" program " " name)
                       :play  #(do (load! path) (mt/play! x (mt/read-text (str "^{ !i:" program " }"))))})
                    found)))
      total)))

;; -- rating -----------------------------------------------------------------

(defn current
  "The trial a rating is for: the audition's item, else what was last
   played when it is a sound -- {:value x :where :audition|:repl}, or nil."
  []
  (if-let [{:keys [items i]} @audition]
    {:value (:value (items i)) :where :audition}
    (let [x @mt/last-played]
      (when (sound? x) {:value x :where :repl}))))

(defn- synth-info []
  (cond @engine/synth    {:synth :gervill :soundfont (gervill/soundbank-name @engine/synth)}
        @engine/receiver {:synth :fluidsynth :soundfont nil}
        :else       {:synth nil :soundfont nil}))

(def ^:private score-words {1 "like" 0 "meh" -1 "dislike"})

(defn rate-current!
  "Rate the current trial with score and say so above the prompt; in an
   audition, move on to the next item. The record, or nil when there is
   nothing to rate."
  [score]
  (if-let [{:keys [value where]} (current)]
    (let [rec (fb/rate! (assoc (synth-info) :what (p/show value) :where where) score)]
      (say (str (score-words score) ": " (describe (:what rec))))
      (when (= where :audition) (command! :next))
      rec)
    (say "nothing to rate -- play a note, chord or drum, or audition a line")))

(defn again!
  "Play the current trial again."
  []
  (cond (command! :again) nil
        (current)         (mt/play! (:value (current)) nil)
        :else             (say "nothing to play again")))

(defn next! [] (or (command! :next) (say "no audition going on")))
(defn back! [] (or (command! :back) (say "no audition going on")))

;; -- ask: a prompt to rate when what was played ends -------------------------

;; -- words --------------------------------------------------------------------

(defn combinations
  "Every k-element combination of xs, in order."
  [k xs]
  (cond (zero? k) [[]]
        (empty? xs) []
        :else (concat (map #(cons (first xs) %) (combinations (dec k) (rest xs)))
                      (combinations k (rest xs)))))

