(ns ^:algo musics.algo.numeric.markov-test
  "Tests for musics.algo.numeric.markov."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.markov :as m]))


(deftest markov-chain-walks-its-number-states
  (let [step (m/markov-chain {0 {4 2 3 1} 4 {0 1 5 1} 3 {0 1} 5 {4 1}} 0)]
    (is (every? #{0 3 4 5} (repeatedly 20 step)))))
