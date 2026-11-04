(ns ^:algo musics.algo.numeric.lsystem-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.lsystem :as ls]))

(deftest expand-rewrites-every-character-in-parallel
  (is (= "A" (ls/expand "A" {"A" "AB" "B" "A"} 0)) "no generations: the axiom")
  (is (= "AB" (ls/expand "A" {"A" "AB" "B" "A"} 1)))
  (is (= "ABAAB" (ls/expand "A" {"A" "AB" "B" "A"} 3)) "Fibonacci word")
  (is (= "ABAABABA" (ls/expand "A" {"A" "AB" "B" "A"} 4))))

(deftest a-character-without-a-rule-stays
  (is (= "AxB" (ls/expand "AxB" {} 3)))
  (is (= "CC" (ls/expand "A" {"A" "CC"} 5)) "C has no rule, so it stays"))
