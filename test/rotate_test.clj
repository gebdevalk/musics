(ns ^:algo rotate-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.rotate :refer [rotate]]))

(deftest rotate-shifts-left-by-i-positions
  (is (= [2 3 4 1] (rotate [1 2 3 4] 1)))
  (is (= [3 4 1 2] (rotate [1 2 3 4] 2)))
  (is (= [1 2 3 4] (rotate [1 2 3 4] 0))))

(deftest rotate-wraps-an-oversized-or-negative-i-via-mod
  (is (= [2 3 4 1] (rotate [1 2 3 4] 5)) "5 mod 4 = 1, same as rotating by 1")
  (is (= [4 1 2 3] (rotate [1 2 3 4] -1)) "wraps to rotating by 3"))
