(ns ^:domain indispensability-test
  "Tests for Barlow indispensability. Run: lein test indispensability-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.indisp.indispensability :as a]))

(deftest psi-test
  (is (= 12 (a/psi 0 [2 3 2])))
  (is (= [1 1/4 3/4 1/2] (a/psi-fractions [2 2]))))
