(ns ^:algo rand-test
  "Tests for algo.random's stateful/composite generators.
   Run: lein test rand-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random :as r]
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

;; ============================================================
;; int-triangular/int-linear/int-arcsine/int-lo-emph/int-mean-emph/
;; int-hi-emph -- integer counterparts added 2026-09-15, closing the
;; asymmetry with rising/falling's own pre-existing int-rising/
;; int-falling (algo.dimensions' own taxonomy work surfaced it)
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

;; ============================================================
;; cyclic-random -- reshuffle-on-exhaustion item cycler
;; ============================================================

(deftest cyclic-random-never-returns-nil-across-many-exhaustion-boundaries
  ;; Regression test for a real, confirmed bug (fixed 2026-09-12):
  ;; cyclic-random used to destructure {:keys [pool idx]} BEFORE checking/
  ;; performing an exhaustion-triggered reset, then used those STALE
  ;; (pre-reset) locals for the actual item lookup -- at exactly the
  ;; exhaustion boundary (idx == (count old-pool)), (get old-pool idx) is
  ;; out of bounds and silently returns nil instead of throwing or
  ;; returning a real item. Driving the cycler through many full passes
  ;; (not just one) is what actually exercises that boundary repeatedly.
  (let [coll (range 60 72)
        gen  (r/cyclic-random coll)]
    (dotimes [_ (* 50 (count coll))]
      (let [item (gen)]
        (is (some? item) "never nil, even right at a reshuffle boundary")
        (is (contains? (set coll) item))))))

(deftest cyclic-random-every-pass-is-a-permutation-of-coll
  (let [coll (vec (range 5))
        gen  (r/cyclic-random coll)
        xs   (repeatedly (* 10 (count coll)) gen)]
    (is (every? #(= (set coll) (set %)) (partition (count coll) xs)))))

;; ============================================================
;; random-walk -- unbiased bounded random walk
;; ============================================================

(deftest random-walk-single-step-stays-within-step-bound
  (let [w (r/random-walk 50 5)]
    (is (<= 45 (w) 55))))

(deftest random-walk-clipping-keeps-every-step-in-range
  (let [w (r/random-walk 50 5 :clip-lo 48 :clip-hi 52)]
    (is (every? #(<= 48 % 52) (repeatedly 100 w)))))

(deftest random-walk-two-instances-have-independent-state
  (let [w1 (r/random-walk 10 1)
        w2 (r/random-walk 10 1)]
    (dotimes [_ 20] (w1))
    (is (<= 9 (w2) 11) "w2 is unaffected by w1's own 20 steps -- fresh, independent atom")))

;; ============================================================
;; smooth-walk -- moves toward a target each call, with inertia
;; ============================================================

(deftest smooth-walk-inertia-0-snaps-to-target
  ;; Regression test: the step-toward-target multiplier used to be
  ;; `inertia` directly, inverting the documented meaning -- inertia=0
  ;; used to IGNORE the target entirely, exactly backwards -- confirmed
  ;; live before fixing (2026-09-03).
  (let [w (r/smooth-walk 0.0 0 0.0)]
    (is (= 10.0 (w 10)))))

(deftest smooth-walk-inertia-1-ignores-target
  (let [w (r/smooth-walk 0.0 1 0.0)]
    (is (= 0.0 (w 10)))))

(deftest smooth-walk-inertia-0.5-moves-halfway
  (let [w (r/smooth-walk 0.0 0.5 0.0)]
    (is (= 5.0 (w 10)))))

(deftest smooth-walk-state-persists-across-calls
  (let [w (r/smooth-walk 0.0 0 0.0)]
    (w 10)
    (is (= 20.0 (w 20)) "already at 10 (inertia 0 snapped last call), snaps again to the new target")))

;; ============================================================
;; generative-patch -- a worked-example event generator combining
;; several of the above
;; ============================================================

(deftest generative-patch-sometimes-fires-sometimes-doesnt
  (let [gen     (r/generative-patch)
        results (repeatedly 30 gen)]
    (is (some nil? results) "the rhythm-trigger gate must sometimes suppress an event")
    (is (some map? results) "and sometimes let one through")))

(deftest generative-patch-events-have-the-expected-shape
  (let [gen     (r/generative-patch)
        events  (remove nil? (repeatedly 30 gen))]
    (is (seq events) "at least one event fired across 30 tries")
    (is (every? #(= #{:pitch :velocity :duration :bend} (set (keys %))) events))
    (is (every? #(<= 60 (:pitch %) 71) events))
    (is (every? #(<= 30 (:velocity %) 127) events))))
