(ns ^:domain constraint-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.constraint :as ct]))

(deftest isorhythm-strength-cycles-talea-and-color-independently
  (is (= [1 0 1 2 1 0 1 2 1 0 1 2]
         (ct/isorhythm-strength [1 0 1 1] [0 1] 3))))

(deftest constraint-satisfaction-rhythm-is-fully-deterministic
  ;; the reference this ports draws a random initial pattern that
  ;; provably never affects the result -- confirmed live against the
  ;; actual Python source across 30 different seeds, always the same
  ;; answer -- so this port (no randomness at all) must match exactly
  (let [at-least-2-ones (fn [p] (>= (reduce + p) 2))
        no-two-consecutive (fn [p] (every? (fn [i] (not (and (= 1 (nth p i)) (= 1 (nth p (inc i))))))
                                            (range (dec (count p)))))]
    (is (= [0 0 0 1 0 1]
           (ct/constraint-satisfaction-rhythm [at-least-2-ones no-two-consecutive] 6)))))

(deftest constraint-satisfaction-rhythm-returns-nil-when-impossible
  (is (nil? (ct/constraint-satisfaction-rhythm [(fn [p] (> (reduce + p) (count p)))] 4))))

(deftest all-interval-rhythm-starts-with-a-beat-and-has-the-right-length
  (let [pattern (ct/all-interval-rhythm 12)]
    (is (= 12 (count pattern)))
    (is (= 1 (first pattern)))
    (is (every? #{0 1} pattern))))
