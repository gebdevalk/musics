(ns musics.mu.feedback
  "What mu's rate words (musics.rate) and the prompt's Alt keys share
   (doc/md/feedback.md): rating what is heard -- the audition's item,
   else the sound last played -- and auditioning a line of sounds one by
   one. The ratings themselves are musics.feedback's."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.core :as m]
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

(defonce ^{:doc "nil, or the audition going on: {:items [sound ...] :i n :queue q}."}
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
        (do (swap! audition assoc :i i)
            (say (str (inc i) "/" (count items) "  " (describe (p/show (items i)))))
            (mt/play! (items i) nil)
            (case (await-command q)
              :next  (recur (inc i))
              :back  (recur (max 0 (dec i)))
              :again (recur i)
              :stop  nil))))
    (catch Throwable t (say (str "audition stopped: " (.getMessage t))))
    (finally (reset! audition nil))))

(defn stop-audition! [] (command! :stop))

(swap! mt/stoppers assoc ::audition stop-audition!)

(defn start-audition!
  "Audition items (sounds) one by one, in the background."
  [items]
  (stop-audition!)
  (let [q (LinkedBlockingQueue.)]
    (reset! audition {:items (vec items) :i 0 :queue q})
    (doto (Thread. ^Runnable #(run-audition (vec items) q) "mu-audition")
      (.setDaemon true)
      (.start))))

;; -- rating -----------------------------------------------------------------

(defn current
  "The trial a rating is for: the audition's item, else what was last
   played when it is a sound -- {:value x :where :audition|:repl}, or nil."
  []
  (if-let [{:keys [items i]} @audition]
    {:value (items i) :where :audition}
    (let [x @mt/last-played]
      (when (sound? x) {:value x :where :repl}))))

(defn- synth-info []
  (cond @m/synth    {:synth :gervill :soundfont (gervill/soundbank-name @m/synth)}
        @m/receiver {:synth :fluidsynth :soundfont nil}
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

