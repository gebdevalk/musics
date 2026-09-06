(ns ^:algo rhythm-test
  "Tests for rhythm-pattern generators. Run: lein test rhythm-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.rhythmic.rhythm :as a]))

(deftest euclidean-test
  (is (= [1 1 1 0 0 0 0 0] (a/euclidean-rhythm 3 8)))
  (is (= [1 1 1 1 0 0 0 0] (a/euclidean-rhythm 4 8)))
  (is (= [1 0 0 0 0] (a/euclidean-rhythm 1 5)))
  (is (= [0 0 0 0] (a/euclidean-rhythm 0 4))))

(deftest fibonacci-test
  (is (= [1 1 1 1 0 1 0 0 1 0 0 0 0] (a/fibonacci-rhythm 13))))

(deftest prime-test
  (let [r (a/prime-rhythm 10)]
    (is (= 10 (count r)))
    (is (= 1 (nth r 2)))
    (is (= 1 (nth r 3)))
    (is (= 1 (nth r 5)))
    (is (= 1 (nth r 7)))))

(deftest lindenmayer-test
  ;; Regression test: the zero-padding used to be concatenated BEFORE
  ;; the real values, so this always returned all-zeros regardless of
  ;; input -- confirmed live before fixing (2026-09-03).
  (let [r (a/lindenmayer-rhythm "A" {"A" "AB" "B" "A"} 3 10)]
    (is (= 10 (count r)))
    (is (some pos? r) "must produce real, non-trivial pulses, not all zeros"))
  (is (= [1 0 1 1 0 0 0 0 0 0] (a/lindenmayer-rhythm "A" {"A" "AB" "B" "A"} 3 10)))
  (is (= [1 0 1 1 0] (a/lindenmayer-rhythm "A" {"A" "AB" "B" "A"} 3 5))
      "shorter length -- still real values, correctly truncated")
  (is (= [1 0 0 0 0 0] (a/lindenmayer-rhythm "A" {} 0 6))
      "no rules/iterations -- axiom itself expands to just \"A\", padded with zeros"))

(deftest markov-rhythm-test
  (let [r (a/markov-rhythm 30 {"0" {"0" 0.5 "1" 0.5} "1" {"0" 0.3 "1" 0.7}})]
    (is (= 30 (count r)))
    (is (every? #{0 1} r)))
  ;; Regression: markov-rhythm used to walk its own transition-matrix
  ;; via a hand-rolled cumulative-sum loop that assumed a state's own
  ;; outgoing probabilities summed to exactly 1.0 -- an under-summing
  ;; table threw a NullPointerException walking off the end of the
  ;; list (confirmed live before this fix). Now built on algo.random/
  ;; markov, which normalizes by the total first, so this must not
  ;; throw even when the given weights don't sum to 1.
  (is (= 30 (count (a/markov-rhythm 30 {"0" {"0" 0.3}}
                                    :initial-state "0" :states {"0" 0 "1" 1})))
      "under-summing transition weights must not throw"))
