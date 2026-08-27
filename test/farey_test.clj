(ns ^:domain farey-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.farey :as farey]))

(deftest farey-matches-an-exact-fraction
  (is (= 3/4 (farey/farey 0.75 4))))

(deftest farey-matches-a-repeating-fraction
  (is (= 1/3 (farey/farey (/ 1.0 3) 10))))

(deftest farey-respects-the-denominator-bound
  ;; a tight bound can't reach the exact fraction, only the closest one
  ;; reachable within N
  (let [approx (farey/farey (/ 1.0 3) 2)]
    (is (<= (denominator approx) 2))))

(deftest farey-returns-a-reduced-ratio
  (is (= 1/2 (farey/farey 0.5 10))))
