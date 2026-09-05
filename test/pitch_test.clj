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

(deftest from-key-derives-a-scale-from-the-central-key-table
  (is (= [0 2 4 5 7 9 11] (pitch/from-key :C :major)))
  (is (= [0 2 4 7 9] (pitch/from-key :C :pentatonic-major))))

(deftest from-key-wraps-a-non-c-tonics-scale-back-into-0-11
  (is (= [9 11 0 2 4 5 7] (pitch/from-key :A :minor))
      "common.music-elements/key's own :pitches for A minor are
       [9 11 12 14 16 17 19], deliberately unwrapped -- from-key wraps
       each back into 0-11 via mod"))
