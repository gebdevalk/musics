(ns ^:algo phase-sieve-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.phase-sieve :as ps]))

(deftest clapping-music-phases-rotates-progressively
  (let [pattern [1 1 1 0 1 1 0 1 0 1 1 0]
        phases  (ps/clapping-music-phases pattern 3)]
    (is (= [[1 1 1 0 1 1 0 1 0 1 1 0]
            [1 1 0 1 1 0 1 0 1 1 0 1]
            [1 0 1 1 0 1 0 1 1 0 1 1]]
           phases))))

(deftest clapping-music-duet-returns-static-and-shifted
  (let [pattern [1 1 1 0 1 1 0 1 0 1 1 0]]
    (is (= [pattern [0 1 1 0 1 0 1 1 0 1 1 1]]
           (ps/clapping-music-duet pattern 3)))))

(deftest xenakis-sieve-unions-multiple-residue-classes
  (is (= [1 1 1 1 1 0 1 1 0 1 1 0]
         (ps/xenakis-sieve [3 4] [[0 1] [2]] 12))))

(deftest sieve-from-intervals-places-beats-at-cumulative-sums
  (is (= [1 0 1 0 1 0 1 0 1 0]
         (ps/sieve-from-intervals [2 3] 10))))
