(ns ^:algo musics.algo.numeric.walk-test
  "Tests for musics.algo.numeric.walk."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.walk :as w]
            [musics.algo.numeric.rng :as seed]))


(deftest smooth-noise-is-a-pure-function-of-t
  (let [curve (w/smooth-noise 8)]
    (is (= (curve 3.5) (curve 3.5)) "same t, same value, called twice on the same curve")))


(deftest smooth-noise-stays-within-lo-hi
  (let [curve (w/smooth-noise 8 20 80)]
    (doseq [t (range 0 7 0.1)]
      (is (<= 20 (curve t) 80) (str "t=" t " out of range")))))


(deftest smooth-noise-clamps-outside-its-domain
  (let [curve (w/smooth-noise 5)]
    (is (= (curve 0) (curve -3)) "t below 0 clamps to t=0")
    (is (= (curve 4) (curve 100)) "t past the last lattice point clamps to the end")))


(deftest smooth-noise-with-one-lattice-point-is-constant
  (let [curve (w/smooth-noise 1)]
    (is (= (curve 0) (curve 0.5) (curve 99)))))


(deftest smooth-noise-is-seedable
  (is (= (seed/with-seed 7 (mapv (w/smooth-noise 6) (range 0 5 0.5)))
         (seed/with-seed 7 (mapv (w/smooth-noise 6) (range 0 5 0.5))))))


(deftest smooth-noise-has-no-seam-at-lattice-boundaries
  ;; approaching a lattice point from just below should land very close
  ;; to the exact value at that point -- a real discontinuity here would
  ;; mean the interpolation picked the wrong segment at the boundary
  (let [curve (w/smooth-noise 5 0 1)]
    (doseq [i [1 2 3]]
      (is (< (Math/abs (- (curve i) (curve (- i 1e-6)))) 1e-4)
          (str "seam detected approaching lattice point " i)))))


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
        gen  (w/cyclic coll)]
    (dotimes [_ (* 50 (count coll))]
      (let [item (gen)]
        (is (some? item) "never nil, even right at a reshuffle boundary")
        (is (contains? (set coll) item))))))


(deftest cyclic-random-every-pass-is-a-permutation-of-coll
  (let [coll (vec (range 5))
        gen  (w/cyclic coll)
        xs   (repeatedly (* 10 (count coll)) gen)]
    (is (every? #(= (set coll) (set %)) (partition (count coll) xs)))))


;; ============================================================
;; random-walk -- unbiased bounded random walk
;; ============================================================

(deftest random-walk-single-step-stays-within-step-bound
  (let [w (w/walk 50 5)]
    (is (<= 45 (w) 55))))


(deftest random-walk-clipping-keeps-every-step-in-range
  (let [w (w/walk 50 5 :clip-lo 48 :clip-hi 52)]
    (is (every? #(<= 48 % 52) (repeatedly 100 w)))))


(deftest random-walk-two-instances-have-independent-state
  (let [w1 (w/walk 10 1)
        w2 (w/walk 10 1)]
    (dotimes [_ 20] (w1))
    (is (<= 9 (w2) 11) "w2 is unaffected by w1's own 20 steps -- fresh, independent atom")))


(deftest random-walk-reflects-off-its-bounds-instead-of-sticking
  (let [xs (repeatedly 500 (w/walk 50 5 :clip-lo 48 :clip-hi 52 :boundary :reflect))]
    (is (every? #(<= 48 % 52) xs))
    (is (not-any? #{48 52 48.0 52.0} xs) "clamping would park on an edge; reflecting doesn't")))


;; ============================================================
;; tendency -- Koenig's tendency mask
;; ============================================================

(deftest tendency-draws-between-moving-bounds
  (let [xs (w/tendency 101 0 50 100 50)]
    (is (= 101 (count xs)))
    (is (every? true? (map-indexed (fn [i x] (<= (* i 0.5) x (- 100 (* i 0.5)))) xs)))
    (is (== 50 (last xs)) "the bounds close on 50")))


;; ============================================================
;; pink-noise -- Voss's 1/f dice
;; ============================================================

(deftest pink-noise-stays-in-range-and-is-correlated
  (let [xs  (vec (repeatedly 2000 (w/pink 5 60 72)))
        avg (fn [v] (/ (reduce + v) (count v)))
        a   (pop xs) b (subvec xs 1)
        ma  (avg a) mb (avg b)
        ss  (fn [v m] (reduce + (map #(let [d (- % m)] (* d d)) v)))
        r1  (/ (reduce + (map #(* (- %1 ma) (- %2 mb)) a b)) (Math/sqrt (* (ss a ma) (ss b mb))))]
    (is (every? #(and (<= 60 %) (< % 72)) xs))
    (is (< 0.4 r1) "neighbours are alike: 1/f, not white noise (whose lag-1 correlation is ~0)")))


(deftest pink-noise-with-one-die-is-white
  (let [xs (repeatedly 200 (w/pink 1 0 1))]
    (is (< 150 (count (distinct xs))) "the single die is rolled again every call")))


;; ============================================================
;; smooth-walk -- moves toward a target each call, with inertia
;; ============================================================

(deftest smooth-walk-inertia-0-snaps-to-target
  ;; Regression test: the step-toward-target multiplier used to be
  ;; `inertia` directly, inverting the documented meaning -- inertia=0
  ;; used to IGNORE the target entirely, exactly backwards -- confirmed
  ;; live before fixing (2026-09-03).
  (let [w (w/glide 0.0 0 0.0)]
    (is (= 10.0 (w 10)))))


(deftest smooth-walk-inertia-1-ignores-target
  (let [w (w/glide 0.0 1 0.0)]
    (is (= 0.0 (w 10)))))


(deftest smooth-walk-inertia-0.5-moves-halfway
  (let [w (w/glide 0.0 0.5 0.0)]
    (is (= 5.0 (w 10)))))


(deftest smooth-walk-state-persists-across-calls
  (let [w (w/glide 0.0 0 0.0)]
    (w 10)
    (is (= 20.0 (w 20)) "already at 10 (inertia 0 snapped last call), snaps again to the new target")))


;; ============================================================
;; generative-patch -- a worked-example event generator combining
;; several of the above
;; ============================================================

(deftest generative-patch-sometimes-fires-sometimes-doesnt
  (let [gen     (w/generative-patch)
        results (repeatedly 30 gen)]
    (is (some nil? results) "the rhythm-trigger gate must sometimes suppress an event")
    (is (some map? results) "and sometimes let one through")))


(deftest generative-patch-events-have-the-expected-shape
  (let [gen     (w/generative-patch)
        events  (remove nil? (repeatedly 30 gen))]
    (is (seq events) "at least one event fired across 30 tries")
    (is (every? #(= #{:pitch :velocity :duration :bend} (set (keys %))) events))
    (is (every? #(<= 60 (:pitch %) 71) events))
    (is (every? #(<= 30 (:velocity %) 127) events))))
