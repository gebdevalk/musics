(ns ^:domain distributions-test
  "Tests for continuous-distribution random variate generators.
   Run: lein test distributions-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random :as d]))

(deftest uniform-stays-in-range
  (dotimes [_ 200]
    (is (<= 2.0 (d/uniform 2.0 5.0) 5.0))))

(deftest weibull-is-nonnegative
  (dotimes [_ 200]
    (is (>= (d/weibull 2.0 1.0) 0.0))))

(deftest beta-stays-in-unit-interval
  (dotimes [_ 200]
    (is (<= 0.0 (d/beta 2.0 5.0) 1.0))))

(deftest normal-rejects-non-positive-stdev
  (is (thrown? AssertionError (d/normal 0 0))))

(deftest gamma-is-positive-both-branches
  (dotimes [_ 200]
    (is (pos? (d/gamma 0.5 1.0))) ;; shape < 1 -- recursive branch
    (is (pos? (d/gamma 2.0 1.0))))) ;; shape >= 1 -- Marsaglia-Tsang branch
