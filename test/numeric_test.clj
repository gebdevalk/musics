(ns ^:algo numeric-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.numeric :as num]))

(deftest gcd-computes-the-greatest-common-divisor
  (is (= 6 (num/gcd 12 18)))
  (is (= 1 (num/gcd 7 13)))
  (is (= 5 (num/gcd 5 0))))

(deftest lcm-computes-the-least-common-multiple
  (is (= 12 (num/lcm 4 6)))
  (is (= 35 (num/lcm 5 7))))

(deftest lcm-multiple-folds-lcm-over-every-number
  (is (= 12 (num/lcm-multiple [2 3 4])))
  (is (= 1 (num/lcm-multiple [1])))
  (is (= 60 (num/lcm-multiple [4 5 6]))))
