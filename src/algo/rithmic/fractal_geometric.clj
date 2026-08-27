;; fractal_geometric.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 17-18 -- rhythms from fractals (Cantor set, dragon curve, L-systems)
;; and geometry (polygon rotation, circle of fifths, golden ratio).
;; Fully deterministic, no randomness.

(ns algo.rithmic.fractal-geometric
  (:require [clojure.string :as str]))

(defn cantor-set-rhythm
  "The Cantor set as a rhythm: start with every position on (1), then
   recursively remove the middle third of each remaining run,
   iterations times."
  ([] (cantor-set-rhythm 3 27))
  ([iterations length]
   (let [pattern (atom (vec (repeat length 1)))]
     (letfn [(remove-middle [start end level]
               (when (and (< level iterations) (>= (- end start) 3))
                 (let [third (quot (- end start) 3)]
                   (doseq [i (range (+ start third) (+ start (* 2 third)))]
                     (when (< i length) (swap! pattern assoc i 0)))
                   (remove-middle start (+ start third) (inc level))
                   (remove-middle (+ start (* 2 third)) end (inc level)))))]
       (remove-middle 0 length 0)
       @pattern))))

(defn dragon-curve-rhythm
  "The dragon-curve folding sequence as a binary rhythm: start with a
   single beat, then each iteration appends a beat (the fold) followed
   by the pattern-so-far reversed and bit-flipped."
  ([] (dragon-curve-rhythm 4))
  ([iterations]
   (loop [pattern [1] i 0]
     (if (= i iterations)
       pattern
       (let [flipped-reversed (mapv #(- 1 %) (rseq pattern))]
         (recur (vec (concat pattern [1] flipped-reversed)) (inc i)))))))

(defn l-system-rhythm
  "Fractal rhythm from an L-system: axiom, rewritten iterations times
   via rules (char -> replacement string, identity for any char with no
   rule), then read off as a pattern: A=1 (beat), B=0 (rest), C=2
   (strong beat), anything else defaults to 0."
  ([axiom rules] (l-system-rhythm axiom rules 3))
  ([axiom rules iterations]
   (let [expanded (nth (iterate (fn [s] (apply str (map #(get rules % (str %)) s))) axiom) iterations)]
     (mapv (fn [ch] (case ch \A 1 \B 0 \C 2 0)) expanded))))

(defn polygon-rotation-rhythm
  "A beat wherever a rotating point comes close (within 0.1 of a full
   revolution's worth) to landing exactly on one of an n-sided polygon's
   own vertices, sampled at rotations evenly-spaced steps around one
   full turn starting at offset (a fraction of a turn)."
  ([] (polygon-rotation-rhythm 5 8 0.0))
  ([sides rotations offset]
   (mapv (fn [step]
           (let [angle (* (+ (/ (double step) rotations) offset) 2 Math/PI)
                 vertex-angle (/ (* angle sides) (* 2 Math/PI))
                 distance (Math/abs (- vertex-angle (Math/round vertex-angle)))]
             (if (< distance 0.1) 1 0)))
         (range rotations))))

(defn circle-of-fifths-rhythm
  "A beat every (pattern-length / notes) steps, at a position stepping
   round the circle of fifths (+7 semitones mod notes) each time --
   notes defaults to 12 (the chromatic circle), so the classic circle
   of fifths (C G D A E B F# C# G# D# A# F)."
  ([] (circle-of-fifths-rhythm 12 24))
  ([notes pattern-length]
   (let [step (quot pattern-length notes)]
     (loop [pattern (vec (repeat pattern-length 0)) current 0 i 0]
       (if (= i pattern-length)
         pattern
         (if (zero? (mod i step))
           (recur (assoc pattern (mod current pattern-length) 1) (mod (+ current 7) notes) (inc i))
           (recur pattern current (inc i))))))))

(defn golden-ratio-rhythm
  "A beat at each position reached by repeatedly stepping forward by
   phi * (length / (beat-count + 1)) -- an irrational, ever-shrinking
   step that spreads beats increasingly evenly (a low-discrepancy
   sequence), continuing until the position runs past length."
  ([] (golden-ratio-rhythm 34 1.61803398875))
  ([length] (golden-ratio-rhythm length 1.61803398875))
  ([length phi]
   (loop [pattern (vec (repeat length 0)) position 0.0 beat-count 0]
     (if (>= (int position) length)
       pattern
       (let [idx (int position)
             pattern' (if (< idx length) (assoc pattern idx 1) pattern)
             beat-count' (if (< idx length) (inc beat-count) beat-count)]
         (recur pattern' (+ position (* phi (/ length (inc beat-count')))) beat-count'))))))

(comment
  (cantor-set-rhythm 3 27)
  (dragon-curve-rhythm 4)
  (l-system-rhythm "A" {\A "AB" \B "A"} 3)
  (polygon-rotation-rhythm 5 8 0.0)
  (circle-of-fifths-rhythm 12 24)
  (golden-ratio-rhythm 21)
  )
