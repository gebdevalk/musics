(ns ^:algo logistic-test
  "Tests for the logistic map. Run: lein test logistic-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.logistic :as lg]))

(deftest logistic-map-is-deterministic-given-seed
  (lg/factor! 3.0)
  (lg/seed! 0.5)
  (let [v1 (lg/value)]
    (lg/seed! 0.5)
    (is (= v1 (lg/value)))))

(deftest logistic-map-matches-formula
  (lg/factor! 3.2)
  (lg/seed! 0.4)
  (is (= (* 3.2 0.4 (- 1 0.4)) (lg/value))))
