(ns ^:algo decompose-test
  "Tests for algo.rhythmic.decompose/ternary-decompose. Run: lein test decompose-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.rhythmic.decompose :as d]))

(deftest ternary-decompose-matches-worked-example
  (is (= [1/9 2/9 1/9 2/9] (d/ternary-decompose 2/3 2))))

(deftest ternary-decompose-sums-back-exactly
  (doseq [[duration depth] [[1/3 1] [4/9 2] [2/3 3] [1 1] [5/3 2] [7/9 2]]]
    (is (= duration (reduce + (d/ternary-decompose duration depth)))
        (str "duration=" duration " depth=" depth))))

(deftest ternary-decompose-depth-one-returns-the-base-terms-unsplit
  (is (= [1/3] (d/ternary-decompose 1/3 1))))

(deftest ternary-decompose-uses-digit-two-not-just-distinct-powers
  ;; 2/3 needs TWO copies of the SAME power (1/3) -- a ternary digit,
  ;; unlike a binary one, can be 2 -- confirmed via the base pass alone
  ;; (depth=1 leaves base terms unsplit, see the test above)
  (is (= [1/3 1/3] (d/ternary-decompose 2/3 1))))

(deftest ternary-decompose-throws-instead-of-hanging-on-non-3-adic-durations
  ;; 3/4's reduced denominator (4) is not a power of 3 -- no finite
  ;; base-3 expansion exists, so this must throw, not hang
  (is (thrown? clojure.lang.ExceptionInfo (d/ternary-decompose 3/4 2))))
