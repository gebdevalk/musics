(ns ^:algo scaling-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.scaling :as scaling]))

(deftest clamp-keeps-a-value-inside-the-bounds
  (is (= 10 (scaling/clamp 0 10 15)))
  (is (= 0 (scaling/clamp 0 10 -3)))
  (is (= 5 (scaling/clamp 0 10 5))))

(deftest closest-to-picks-the-nearer-bound
  (is (= 4 (scaling/closest-to 4.7 4 6)))
  (is (= 2.0 (scaling/closest-to 3.9 2.0 6.0))))

(deftest round-to-rounds-to-nearest-multiple
  (is (= 5.0 (scaling/round-to 4.7 1)))
  (is (= 4.0 (scaling/round-to 4.7 2)))
  (is (= 6 (scaling/round-to 5 2))))

(deftest scale-range-remaps-linearly
  (is (= 100 (scaling/scale-range 5 0 10 50 150)))
  (is (= 50 (scaling/scale-range 0 0 10 50 150)))
  (is (= 150 (scaling/scale-range 10 0 10 50 150))))
