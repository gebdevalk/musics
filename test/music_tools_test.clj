(ns music-tools-test
  "Tests for gcd, lcm, fractions. Run: lein test music-tools-test"
  (:require [clojure.test :refer [deftest is testing]]
            [common.tools.music-tools :as t]))

(deftest gcd-test
  (is (= 6 (t/gcd 48 18)))
  (is (= 1 (t/gcd 17 13)))
  (is (= 12 (t/gcd 24 36)))
  (is (= 5 (t/gcd 5 0))))

(deftest lcm-test
  (is (= 12 (t/lcm 4 6)))
  (is (= 0 (t/lcm 0 5)))
  (is (= 35 (t/lcm 5 7))))

(deftest lcm-multiple-test
  (is (= 60 (t/lcm-multiple 3 4 5)))
  (is (= 12 (t/lcm-multiple 4 6)))
  (is (= 3 (t/lcm-multiple 3))))

(deftest coprime-test
  (is (t/coprime? 17 13))
  (is (not (t/coprime? 12 8)))
  (is (t/coprime? 1 100)))

(deftest modular-inverse-test
  (is (= 3 (t/modular-inverse 3 4)))
  (is (= 4 (t/modular-inverse 2 7))))

(deftest fraction-test
  (is (= 1/4 (t/fraction-from-string "4")))
  (is (= 3/8 (t/fraction-from-string "4.")))
  (is (= 7/16 (t/fraction-from-string "4..")))
  (is (= 3/8 (t/fraction-from-string "3/8"))))
