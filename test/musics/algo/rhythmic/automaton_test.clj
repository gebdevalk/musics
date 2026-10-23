(ns ^:algo musics.algo.rhythmic.automaton-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.automaton :as ca]))

(deftest rule-90-draws-sierpinski
  (is (= [[0 0 0 1 0 0 0]
          [0 0 1 0 1 0 0]
          [0 1 0 0 0 1 0]
          [1 0 1 0 1 0 1]]
         (map vec (partition 7 (ca/automaton 90 7 4 :single))))))

(deftest a-random-start-gives-width-times-steps-pulses
  (let [ps (ca/automaton 30 16 8 :random)]
    (is (= 128 (count ps)))
    (is (every? #{0 1} ps))))
