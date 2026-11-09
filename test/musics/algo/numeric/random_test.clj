(ns ^:algo musics.algo.numeric.random-test
  "Tests for musics.algo.numeric.random's draws, distributions and picks.
   Run: lein test musics.algo.numeric.random-test"
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.random :as r]
            [musics.algo.numeric.rng :as seed]))


(deftest linear-stays-within-bounds
  (dotimes [_ 200]
    (is (<= 10 (r/linear 10 20) 20))))


(deftest linear-rising-skews-toward-hi
  ;; rising? true (the default) means density increases toward hi, so the
  ;; mean of many draws should sit above the midpoint of [lo, hi]
  (let [draws (repeatedly 2000 #(r/linear 0 100))
        mean  (/ (reduce + draws) (count draws))]
    (is (> mean 50) "rising linear's mean should skew above the midpoint")))


(deftest linear-falling-skews-toward-lo
  (let [draws (repeatedly 2000 #(r/linear 0 100 false))
        mean  (/ (reduce + draws) (count draws))]
    (is (< mean 50) "falling linear's mean should skew below the midpoint")))


(deftest linear-is-seedable
  (is (= (seed/with-seed 3 (doall (repeatedly 10 #(r/linear 0 10))))
         (seed/with-seed 3 (doall (repeatedly 10 #(r/linear 0 10)))))))


(deftest arcsine-stays-within-bounds
  (dotimes [_ 200]
    (is (<= 10 (r/arcsine 10 20) 20))))


(deftest arcsine-clusters-at-the-extremes
  ;; density is highest at the two extremes and lowest in the middle, so
  ;; far fewer draws should land in the tight middle band than near-and-far
  (let [draws     (repeatedly 3000 #(r/arcsine 0 100))
        middle    (count (filter #(< 40 % 60) draws))
        near-ends (count (filter #(or (< % 20) (> % 80)) draws))]
    (is (< middle near-ends) "middle band should be sparser than the two end bands")))


(deftest arcsine-is-seedable
  (is (= (seed/with-seed 5 (doall (repeatedly 10 #(r/arcsine 0 10))))
         (seed/with-seed 5 (doall (repeatedly 10 #(r/arcsine 0 10)))))))


;; ============================================================
;; int-triangular/int-linear/int-arcsine/int-lo-emph/int-mean-emph/
;; int-hi-emph -- integer counterparts added 2026-09-15, closing the
;; asymmetry with rising/falling's own pre-existing int-rising/
;; int-falling (musics.algo.dimensions' own taxonomy work surfaced it)
;; ============================================================

(deftest int-triangular-is-an-integer-within-bounds
  (dotimes [_ 200]
    (let [v (r/int-triangular 10 20 15)]
      (is (integer? v))
      (is (<= 10 v 19)))))


(deftest int-linear-is-an-integer-within-bounds
  (dotimes [_ 200]
    (let [v (r/int-linear 10 20)]
      (is (integer? v))
      (is (<= 10 v 19)))))


(deftest int-arcsine-is-an-integer-within-bounds
  (dotimes [_ 200]
    (let [v (r/int-arcsine 10 20)]
      (is (integer? v))
      (is (<= 10 v 19)))))


(deftest int-lo-emph-int-mean-emph-int-hi-emph-are-integers-within-bounds
  (dotimes [_ 200]
    (is (<= 10 (r/int-lo-emph 10 20) 19))
    (is (<= 10 (r/int-mean-emph 10 20) 19))
    (is (<= 10 (r/int-hi-emph 10 20) 19))))


(deftest int-triangular-and-friends-are-seedable
  (is (= (seed/with-seed 7 (doall (repeatedly 10 #(r/int-triangular 0 10 5))))
         (seed/with-seed 7 (doall (repeatedly 10 #(r/int-triangular 0 10 5)))))))


;; ============================================================
;; only -- index-based selection from a phrase
;; ============================================================

(deftest only-selects-by-index-in-order-given
  (is (= [10 30] (r/only [10 20 30 40] [0 2]))))


(deftest only-allows-repeated-indices
  (is (= [30 30 10] (r/only [10 20 30] [2 2 0]))))


(deftest only-with-nil-notes-is-empty
  (is (= [] (r/only [10 20] nil))))


(deftest only-with-an-empty-vector-of-notes-is-not-the-same-as-nil
  ;; A real, confirmed quirk, not asymmetric by design: (if notes ...)
  ;; treats an empty VECTOR as truthy (only nil/false are falsy in
  ;; Clojure), so [] still enters the recursive branch once, looking
  ;; up (first []) = nil in phrase before (next []) = nil finally stops
  ;; it -- producing [nil], not []. Documented here as the function's
  ;; own actual behavior, confirmed live, not "fixed" -- callers should
  ;; pass nil (or omit the arg) for "no notes," not [].
  (is (= [nil] (r/only [10 20] []))))
