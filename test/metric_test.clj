(ns ^:algo metric-test
  "Tests for metric/numeric pulse generators. Run: lein test metric-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.metric.metric :as a]))

(deftest modular-test
  (is (= [1 0 0 0 1 0 0] (a/modular-rhythm 4 3 7 0))))

(deftest continued-fraction-test
  ;; Regression test: the zero-padding used to be concatenated BEFORE
  ;; the real values via an unbounded (repeat 0), so this always
  ;; returned all-zeros regardless of input -- confirmed live before
  ;; fixing (2026-09-03).
  (let [r (a/continued-fraction-rhythm 1.5 8)]
    (is (= 8 (count r)))
    (is (some pos? r) "must produce real, non-trivial pulses, not all zeros"))
  (is (= [1 0 0 0 0 0 0 0] (a/continued-fraction-rhythm 1.5 8)))
  (is (= [1 1 1 1 0 0 0 0 1 0] (a/continued-fraction-rhythm 3.7 10)))
  (is (= [1 0 0] (a/continued-fraction-rhythm 1.5 3))
      "shorter length -- still real values, correctly truncated"))
