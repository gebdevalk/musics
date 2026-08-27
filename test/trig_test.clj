(ns ^:domain trig-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.trig :as trig]))

(deftest cosr-matches-known-values
  (is (= [12.0 10.0 8.0 10.0 12.0]
         (mapv #(trig/cosr % 2 10 8) [0 2 4 6 8]))))

(deftest sinr-matches-known-values
  (is (= [10.0 12.0 10.0 8.0 10.0]
         (mapv #(trig/sinr % 2 10 8) [0 2 4 6 8]))))

(deftest tanr-is-centered-at-zero-crossings
  ;; idx 0/4/8 land on multiples of period/2, where tan itself is 0,
  ;; so tanr should return exactly center regardless of amp
  (is (= [10.0 10.0 10.0]
         (mapv #(trig/tanr % 2 10 8) [0 4 8]))))

(deftest cosr-completes-one-cycle-per-period
  (is (= (trig/cosr 0 3 5 4) (trig/cosr 4 3 5 4))))
