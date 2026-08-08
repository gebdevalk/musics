(ns ^:domain rand-test
  "Tests for algo.random.rand's stateful/composite generators.
   Run: lein test rand-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.rand :as r]))

(deftest random-rhythm-at-full-density-fires-every-beat
  (is (= [0.0 0.25 0.5 0.75] (r/random-rhythm 0.25 4 1.0))))

(deftest random-rhythm-at-zero-density-fires-no-beats
  (is (= [] (r/random-rhythm 0.25 4 0.0))))
