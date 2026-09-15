(ns ^:algo transient-ops-test
  "Tests for algo.common.transient-ops. Run: lein test transient-ops-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.common.transient-ops :as t]
            [core.domain.flat-domain :as d]))

(deftest times-scales-bare-numbers
  (is (= [1/2 1/4 1] (vec (t/times 2 [1/4 1/8 1/2])))))

(deftest times-scales-part-durations
  (is (= [{:duration 1/2} {:duration 1/4}]
         (vec (t/times 2 [{:duration 1/4} {:duration 1/8}])))))

(deftest times-passes-through-durationless-items
  (is (= [:assignment] (vec (t/times 2 [:assignment])))))

(deftest tuplet-is-the-reciprocal-of-times
  ;; 3 notes in the time of 2 -- each duration divided by 3/2
  (is (= [1/6 1/6 1/6] (vec (t/tuplet 3/2 [1/4 1/4 1/4])))))

(deftest tuplet-matches-flat-domain-to-tuplet
  (let [part {:duration 1/4}]
    (is (= (:duration ((d/to-tuplet 3/2) part))
           (:duration (first (t/tuplet 3/2 [part])))))))

(deftest transpose-shifts-pitches
  (is (= [{:pitches [67 71 74] :duration 1/4}]
         (vec (t/transpose 7 [{:pitches [60 64 67] :duration 1/4}])))))

(deftest transpose-passes-through-unpitched-items
  (is (= [{:duration 1/4}] (vec (t/transpose 7 [{:duration 1/4}])))))
