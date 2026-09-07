(ns ^:algo trig-test
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

(deftest trianglr-matches-known-values
  (is (= [10.0 12.0 10.0 8.0 10.0]
         (mapv #(trig/trianglr % 2 10 8) [0 2 4 6 8]))))

(deftest trianglr-completes-one-cycle-per-period
  ;; arcsin(sin(...))'s own floating-point error can be larger than
  ;; cos/sin's alone -- confirmed live (period=4/amp=3 landed 0 and 4
  ;; a full 1e-15 apart, not exactly equal) -- so this checks within a
  ;; tolerance, not exact equality, unlike cosr's own version above
  (is (< (Math/abs (- (trig/trianglr 0 3 5 4) (trig/trianglr 4 3 5 4))) 1e-9)))

(deftest squarr-plateaus-at-amp-extremes-off-the-zero-crossings
  (is (= [12.0 12.0 8.0 8.0]
         (mapv #(trig/squarr % 2 10 8) [1 3 5 7]))))

(deftest squarr-at-idx-zero-lands-exactly-on-center
  ;; sign(sin(0.0)) is exactly 0.0, unlike the OTHER zero-crossings
  ;; (idx=4/8), which are at the mercy of floating-point noise instead
  (is (= 10.0 (trig/squarr 0 2 10 8))))

(deftest sawr-ramps-linearly-away-from-center-at-idx-zero
  (is (= [10.0 11.0 9.0 10.0]
         (mapv #(trig/sawr % 2 10 8) [0 2 6 8]))))

(deftest sawr-completes-one-cycle-per-period
  (is (= (trig/sawr 1 3 5 4) (trig/sawr 5 3 5 4))))
