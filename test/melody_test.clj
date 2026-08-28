(ns ^:algo melody-test
  "Tests for melodic algorithms. Run: lein test melody-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.melodic.melody :as a]))

(deftest constraint-melody-test
  (let [melody (a/constraint-melody a/c-major 8 [a/no-repeat-constraint])]
    (is (= 8 (count melody)))
    (is (every? #(contains? (set a/c-major) %) melody))))
