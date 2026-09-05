(ns ^:algo pitch-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.pitch :as pitch]))

(deftest build-scale-computes-pitch-classes-from-a-root-and-intervals
  (is (= [0 2 4 5 7 9 11] (pitch/build-scale 0 [0 2 4 5 7 9 11]))
      "C major"))

(deftest build-scale-wraps-past-11-back-into-0-11
  (is (= [9 11 0 2 4 5 7] (pitch/build-scale 9 [0 2 3 5 7 8 10]))
      "A minor -- 9+2=11 stays put, 9+3=12 wraps to 0, etc."))

(deftest build-scale-supports-a-pentatonic-subset
  (is (= [0 2 4 7 9] (pitch/build-scale 0 [0 2 4 7 9]))))
