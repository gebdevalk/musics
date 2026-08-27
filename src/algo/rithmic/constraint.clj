;; constraint.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py section 16
;; -- constraint-driven rhythm generation: all-interval rhythms,
;; isorhythmic beat-strength patterns, and backtracking constraint
;; satisfaction.

(ns algo.rithmic.constraint
  (:require [algo.random :as rand]))

(defn all-interval-rhythm
  "A binary pattern of the given length where the intervals between
   consecutive beats are (as much as possible) all different: starts
   with a beat at 0, then repeatedly jumps by a not-yet-used interval
   (falling back to any interval that still fits once every interval
   has been tried) until reaching the far end. A simplified approach,
   not a true all-interval series."
  ([] (all-interval-rhythm 12))
  ([length]
   (loop [pattern (assoc (vec (repeat length 0)) 0 1)
          used-intervals #{}
          current-pos 0]
     (if (>= current-pos (dec length))
       pattern
       (let [candidates (filter #(and (not (used-intervals %)) (< (+ current-pos %) length))
                                 (range 1 (inc (quot length 2))))
             candidates (if (seq candidates)
                          candidates
                          (filter #(< (+ current-pos %) length) (range 1 (inc (quot length 2)))))]
         (if (empty? candidates)
           pattern
           (let [interval (rand/choose (vec candidates))
                 pos' (+ current-pos interval)]
             (recur (if (< pos' length) (assoc pattern pos' 1) pattern)
                    (conj used-intervals interval)
                    pos'))))))))

(defn isorhythm-strength
  "Isorhythmic beat-strength pattern: talea (a binary rhythm pattern)
   repeated repetitions times, each of its own 1s replaced by (color
   entry) + 1 -- so the result cycles talea's own ON/OFF shape against
   color's own strength values independently, the classic isorhythmic
   talea/color pairing but expressed as beat strength (1 or 2) rather
   than pitch."
  ([talea color] (isorhythm-strength talea color 3))
  ([talea color repetitions]
   (let [talea (vec talea) color (vec color) tn (count talea) cn (count color)]
     (vec (mapcat (fn [rep]
                     (map (fn [i]
                            (if (= 1 (nth talea i))
                              (inc (nth color (mod (+ (* rep tn) i) cn)))
                              0))
                          (range tn)))
                   (range repetitions))))))

(defn constraint-satisfaction-rhythm
  "A binary pattern of the given length satisfying every predicate in
   constraints (each a fn from a full-length pattern to a boolean), via
   exhaustive depth-first backtracking (try 0, then 1, at each position
   in turn). Returns nil if no satisfying pattern exists.

   The reference this ports also draws a random initial pattern and
   retries up to max-attempts times -- confirmed live, neither has any
   effect on the result: the backtracking search overwrites every
   position unconditionally before it's ever read, so the outcome is
   fully determined by the constraints alone. This port skips both."
  [constraints length]
  (letfn [(satisfies-all? [pattern] (every? #(% pattern) constraints))
          (backtrack [pattern pos]
            (if (= pos length)
              (when (satisfies-all? pattern) pattern)
              (or (backtrack (assoc pattern pos 0) (inc pos))
                  (backtrack (assoc pattern pos 1) (inc pos)))))]
    (backtrack (vec (repeat length 0)) 0)))

(comment
  (isorhythm-strength [1 0 1 1] [0 1] 3)
  (constraint-satisfaction-rhythm [#(>= (reduce + %) 2)] 6)
  )
