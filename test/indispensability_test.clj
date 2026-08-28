(ns ^:algo indispensability-test
  "Tests for Barlow indispensability. Run: lein test indispensability-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.indisp.indispensability :as a]))

(defn- permutation-of-0-to-n-1? [coll]
  (= (set coll) (set (range (count coll)))))

(deftest indispensability-base-cases
  ;; The four verified reference tables, fed back through the general
  ;; multi-level machinery via a single-element subdivisions vector --
  ;; must reproduce exactly, not just "a valid permutation."
  (is (= [1 0]             (a/indispensability [2])))
  (is (= [2 0 1]           (a/indispensability [3])))
  (is (= [4 0 1 3 2]       (a/indispensability [5])))
  (is (= [6 0 1 3 5 2 4]   (a/indispensability [7]))))

(deftest indispensability-downbeat-is-always-max
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [5 3] [7 3]]]
    (let [ranks (a/indispensability subdivisions)]
      (is (= (dec (count ranks)) (first ranks))
          (str "downbeat should be max for " subdivisions)))))

(deftest indispensability-is-always-a-permutation
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [3 2 2]
                        [5 3] [7 3] [2 2 2 2 3]]]
    (is (permutation-of-0-to-n-1? (a/indispensability subdivisions))
        (str "should be a permutation of 0..N-1 for " subdivisions))))

(deftest indispensability-two-two-three-matches-confirmed-reference
  (is (= [11 0 4 8 2 6 10 1 5 9 3 7] (a/indispensability [2 2 3]))))

(deftest indispensability-unsupported-factor-throws
  (is (thrown? clojure.lang.ExceptionInfo (a/indispensability [11]))))

(deftest beat-probabilities-sums-to-one
  (let [probs (a/beat-probabilities (a/indispensability [2 2]) 0.5)]
    (is (= 4 (count probs)))
    (is (< (Math/abs (- 1.0 (reduce + probs))) 1e-9))))

(deftest beat-probabilities-favors-higher-ranks-as-adherence-rises
  ;; downbeat is position 0 (rank 3, the max for [2 2]) -- its share of
  ;; the probability mass should grow as adherence rises
  (let [downbeat-share #(first (a/beat-probabilities (a/indispensability [2 2]) %))]
    (is (< (downbeat-share 0.1) (downbeat-share 5.0)))))
