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
