(ns ^:algo pulse-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.pulse :as p]
            [core.domain.flat-domain :as d]))

(deftest grid->pulses-collapses-consecutive-equal-value-runs
  (let [pulses (vec (p/grid->pulses [1 0 0 1 0 0 1 0]))]
    (is (= [[1 1] [2 0] [1 1] [2 0] [1 1] [1 0]]
           (mapv (juxt :duration :value) pulses)))
    (is (every? d/pulse? pulses))))

(deftest grid->pulses-durations-feed-color-talea-style-talea-directly
  (is (= [1 2 1 2 1 1] (mapv :duration (p/grid->pulses [1 0 0 1 0 0 1 0])))))

(deftest grid->pulses-treats-every-value-symmetrically-no-onset-vs-rest-special-casing
  (is (= [[2 2] [1 0] [3 1] [1 0]]
         (mapv (juxt :duration :value) (p/grid->pulses [2 2 0 1 1 1 0])))
      "a weighted grid's own strengths (0/1/2/...) survive as :value, not
       collapsed into a plain onset/rest distinction"))

(deftest grid->pulses-on-a-uniform-grid-returns-one-pulse
  (let [pulses (vec (p/grid->pulses [1 1 1 1]))]
    (is (= 1 (count pulses)))
    (is (= 4 (:duration (first pulses))))))

(deftest grid->pulses-on-an-empty-grid-returns-nothing
  (is (empty? (p/grid->pulses []))))
