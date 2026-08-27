(ns ^:domain rand-test
  "Tests for algo.rnd's stateful/composite generators.
   Run: lein test rand-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.rnd :as r]
            [algo.random.core :as seed]))

(deftest random-rhythm-at-full-density-fires-every-beat
  (is (= [0.0 0.25 0.5 0.75] (r/random-rhythm 0.25 4 1.0))))

(deftest random-rhythm-at-zero-density-fires-no-beats
  (is (= [] (r/random-rhythm 0.25 4 0.0))))

(deftest smooth-noise-is-a-pure-function-of-t
  (let [curve (r/smooth-noise 8)]
    (is (= (curve 3.5) (curve 3.5)) "same t, same value, called twice on the same curve")))

(deftest smooth-noise-stays-within-lo-hi
  (let [curve (r/smooth-noise 8 20 80)]
    (doseq [t (range 0 7 0.1)]
      (is (<= 20 (curve t) 80) (str "t=" t " out of range")))))

(deftest smooth-noise-clamps-outside-its-domain
  (let [curve (r/smooth-noise 5)]
    (is (= (curve 0) (curve -3)) "t below 0 clamps to t=0")
    (is (= (curve 4) (curve 100)) "t past the last lattice point clamps to the end")))

(deftest smooth-noise-with-one-lattice-point-is-constant
  (let [curve (r/smooth-noise 1)]
    (is (= (curve 0) (curve 0.5) (curve 99)))))

(deftest smooth-noise-is-seedable
  (is (= (seed/with-seed 7 (mapv (r/smooth-noise 6) (range 0 5 0.5)))
         (seed/with-seed 7 (mapv (r/smooth-noise 6) (range 0 5 0.5))))))

(deftest smooth-noise-has-no-seam-at-lattice-boundaries
  ;; approaching a lattice point from just below should land very close
  ;; to the exact value at that point -- a real discontinuity here would
  ;; mean the interpolation picked the wrong segment at the boundary
  (let [curve (r/smooth-noise 5 0 1)]
    (doseq [i [1 2 3]]
      (is (< (Math/abs (- (curve i) (curve (- i 1e-6)))) 1e-4)
          (str "seam detected approaching lattice point " i)))))

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

(deftest poisson-events-stay-within-duration
  (doseq [t (r/poisson-events 4 8)]
    (is (< 0.0 t 8.0))))

(deftest poisson-events-are-strictly-increasing
  (let [events (r/poisson-events 4 8)]
    (is (apply < events) "onset times must be strictly increasing")))

(deftest poisson-events-average-count-tracks-rate
  ;; expected count = rate * duration; check it's in the right ballpark
  ;; across many draws rather than asserting an exact count
  (let [counts (repeatedly 200 #(count (r/poisson-events 4 8)))
        mean   (/ (reduce + counts) (count counts))]
    (is (< 20 mean 44) (str "mean event count " mean " far from expected 32"))))

(deftest poisson-events-at-zero-duration-is-empty
  (is (= [] (r/poisson-events 4 0))))

(deftest poisson-events-is-seedable
  (is (= (seed/with-seed 9 (r/poisson-events 4 8))
         (seed/with-seed 9 (r/poisson-events 4 8)))))
