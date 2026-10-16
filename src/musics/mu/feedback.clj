(ns musics.mu.feedback
  "mu's feedback words (doc/md/feedback.md): rate what was heard -- like,
   meh, dislike, or Alt+l Alt+k Alt+j at the prompt (musics.mu binds the keys) --
   audition a line of sounds one by one, and read the ratings back
   (taste, liked, disliked), in namespace rate. The ratings themselves
   are musics.feedback's."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.feedback :as fb]
            [musics.midi.gervill :as gervill]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn sound?
  "A note, chord or drum -- what can be rated as a sound."
  [x]
  (boolean (and (mt/leaf? x) (#{:LEAF :DRUM} (:type x)))))

(defn- say [line] (@e/print-above line))

(defn- part-name [kind n]
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

(defonce ^:private ask-agent (agent nil :error-mode :continue))

(defn- ask-fn [_top]
  (when (and (nil? @audition) (sound? @mt/last-played))
    (send-off ask-agent
              (fn [_] (say (str "rate " (p/show @mt/last-played) "?  Alt+j dislike · Alt+k meh · Alt+l like")) nil))))

;; -- words --------------------------------------------------------------------

(word! "rate" "like" "( -- )  rate what is heard (the audition's item, else the sound last played) 1; Alt+l"
       (fn [s] (rate-current! 1) s))
(word! "rate" "meh" "( -- )  rate it 0, neutral; Alt+k"
       (fn [s] (rate-current! 0) s))
(word! "rate" "dislike" "( -- )  rate it -1; Alt+j"
       (fn [s] (rate-current! -1) s))
(word! "rate" "again" "( -- )  play it again; Alt+K"
       (fn [s] (again!) s))
(word! "rate" "next" "( -- )  the audition's next item; Alt+L"
       (fn [s] (next!) s))
(word! "rate" "prev" "( -- )  the audition's previous item; Alt+J"
       (fn [s] (back!) s))

(word! "rate" "audition" "( line -- )  play its sounds one by one, in the background, to rate each; stop (Alt+.) stops"
       (fn [s]
         (need s 1 "audition")
         (let [items (filterv sound? (mt/elements (peek s)))]
           (when (empty? items)
             (e/mu-error "audition: needs a line of notes, chords or drums, got" (p/show (peek s))))
           (start-audition! items)
           (pop s))))

(word! "rate" "step" "( -- )  auditions wait for a rating or next, or move on by themselves again"
       (fn [s]
         (println (if (swap! auto? not) "auditions move on by themselves" "auditions wait for a rating or next"))
         s))

(word! "rate" "gap" "( ms -- )  the silence after each sound of an audition before it moves on by itself (150 by default)"
       (fn [s]
         (need s 1 "gap")
         (let [ms (peek s)]
           (when-not (and (number? ms) (<= 0 ms)) (e/mu-error "gap: takes milliseconds, 0 or more"))
           (reset! gap-ms (long ms))
           (pop s))))

(word! "rate" "ask" "( -- )  ask for a rating when a sound played ends, or stop asking"
       (fn [s]
         (if @engine/finished
           (do (reset! engine/finished nil) (println "ask off"))
           (do (reset! engine/finished ask-fn) (println "ask on")))
         s))

(defn combinations
  "Every k-element combination of xs, in order."
  [k xs]
  (cond (zero? k) [[]]
        (empty? xs) []
        :else (concat (map #(cons (first xs) %) (combinations (dec k) (rest xs)))
                      (combinations k (rest xs)))))

(word! "music" "combos" "( leaf numbers k -- line )  a copy of the leaf per k of the numbers layered: a drum's keys, a note's instruments"
       (fn [s]
         (need s 3 "combos")
         (let [k (peek s) ns (peek (pop s)) x (peek (pop (pop s)))]
           (when-not (sound? x)
             (e/mu-error "combos: needs a note, chord or drum under the numbers, got" (p/show x)))
           (let [nums (vec (distinct (mt/elements ns)))]
             (when-not (every? #(and (integer? %) (<= 0 % 127)) nums)
               (e/mu-error "combos: needs whole numbers 0-127 (drum keys or instruments), as '[40 41 71 73]"))
             (when-not (and (integer? k) (<= 1 k (count nums)))
               (e/mu-error "combos: needs a count 1 to" (count nums) "on top, got" k))
             (conj (pop (pop (pop s)))
                   (mt/line (for [c (combinations k nums)
                                  :let [v (if (= 1 k) (first c) (vec c))]]
                              (if (= :DRUM (:type x)) (assoc x :program v) (assoc-in x [:overrides :instrument] v)))))))))

(word! "rate" "taste" "( -- )  the ratings: per sound, then per instrument or drum, best first (mean, count)"
       (fn [s]
         (let [rs (fb/ratings)]
           (if (empty? rs)
             (println "nothing rated yet")
             (do (println "sounds")
                 (doseq [l (fb/table (map (fn [[w v]] [(describe w) v]) (fb/by-sound rs)))] (println " " l))
                 (println "instruments and drums")
                 (doseq [l (fb/table (map (fn [[part v]] [(fb/part-label part part-name) v]) (fb/by-part rs)))]
                   (println " " l)))))
         s))

(defn- sounds-line [word pred]
  (let [ws (fb/sounds-where pred)]
    (when (empty? ws) (e/mu-error (str word ":") "no sound rated so far"))
    (mt/read-text (str "[" (str/join " " ws) "]"))))

(word! "rate" "liked" "( -- line )  the sounds rated above zero on the whole, best first"
       (fn [s] (conj s (sounds-line "liked" pos?))))
(word! "rate" "disliked" "( -- line )  the sounds rated below zero on the whole, worst last"
       (fn [s] (conj s (sounds-line "disliked" neg?))))
