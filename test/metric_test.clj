(ns ^:domain metric-test
  "Tests for metric/numeric pulse generators. Run: lein test metric-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.metric.metric :as a]))

(deftest modular-test
  (is (= [1 0 0 0 1 0 0] (a/modular-rhythm 4 3 7 0))))
