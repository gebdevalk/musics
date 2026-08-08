(ns ^:domain distributions-test
  "Tests for continuous-distribution random variate generators.
   Run: lein test distributions-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.distributions :as d]))

(deftest rand-uniform-stays-in-range
  (dotimes [_ 200]
    (is (<= 2.0 (d/rand-uniform 2.0 5.0) 5.0))))

(deftest rand-weibull-is-nonnegative
  (dotimes [_ 200]
    (is (>= (d/rand-weibull 2.0 1.0) 0.0))))

(deftest rand-beta-stays-in-unit-interval
  (dotimes [_ 200]
    (is (<= 0.0 (d/rand-beta 2.0 5.0) 1.0))))

(deftest rand-gamma-is-positive-both-branches
  (dotimes [_ 200]
    (is (pos? (d/rand-gamma 0.5 1.0))) ;; shape < 1 -- recursive branch
    (is (pos? (d/rand-gamma 2.0 1.0))))) ;; shape >= 1 -- Marsaglia-Tsang branch
