(ns ^:algo indispensability-test
  "Tests for Barlow indispensability. Run: lein test indispensability-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.indisp.indispensability :as a]))

(defn- permutation-of-0-to-n-1? [coll]
  (= (set coll) (set (range (count coll)))))

(deftest indispensability-base-cases
  ;; The four verified reference tables, fed back through the general
  ;; multi-level machinery via a single-element subdivisions vector --
  ;; must reproduce exactly, not just "a valid permutation."
  (is (= [1 0]             (a/indispensability [2])))
  (is (= [2 0 1]           (a/indispensability [3])))
  (is (= [4 0 1 3 2]       (a/indispensability [5])))
  (is (= [6 0 1 3 5 2 4]   (a/indispensability [7]))))

(deftest indispensability-downbeat-is-always-max
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [5 3] [7 3]]]
    (let [ranks (a/indispensability subdivisions)]
      (is (= (dec (count ranks)) (first ranks))
          (str "downbeat should be max for " subdivisions)))))

(deftest indispensability-is-always-a-permutation
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [3 2 2]
                        [5 3] [7 3] [2 2 2 2 3]]]
    (is (permutation-of-0-to-n-1? (a/indispensability subdivisions))
        (str "should be a permutation of 0..N-1 for " subdivisions))))

(deftest indispensability-two-two-three-matches-confirmed-reference
  (is (= [11 0 4 8 2 6 10 1 5 9 3 7] (a/indispensability [2 2 3]))))

(deftest indispensability-unsupported-factor-throws
  (is (thrown? clojure.lang.ExceptionInfo (a/indispensability [11]))))

(deftest beat-probabilities-sums-to-one
  (let [probs (a/beat-probabilities (a/indispensability [2 2]) 0.5)]
    (is (= 4 (count probs)))
    (is (< (Math/abs (- 1.0 (reduce + probs))) 1e-9))))

(deftest beat-probabilities-favors-higher-ranks-as-adherence-rises
  ;; downbeat is position 0 (rank 3, the max for [2 2]) -- its share of
  ;; the probability mass should grow as adherence rises
  (let [downbeat-share #(first (a/beat-probabilities (a/indispensability [2 2]) %))]
    (is (< (downbeat-share 0.1) (downbeat-share 5.0)))))

(deftest beat-probabilities-is-scale-invariant-in-its-weights
  ;; the same adherence must mean the same thing regardless of how big
  ;; the raw weights are -- a 12-pulse meter's ranks 0..11 shouldn't
  ;; tilt any harder than a rescaled [0 1 2 3] at the same adherence
  (is (= (a/beat-probabilities [0 1 2 3] 2.0)
         (a/beat-probabilities [0 10 20 30] 2.0))))

(deftest density-grid-keeps-the-top-ranked-positions
  ;; (indispensability [2 2]) => [3 0 2 1] -- the two highest ranks (3,
  ;; 2) sit at positions 0 and 2
  (is (= [1 0 1 0] (a/density-grid (a/indispensability [2 2]) 0.5))))

(deftest density-grid-at-the-extremes
  (let [ranks (a/indispensability [2 2 3])]
    (is (= (vec (repeat (count ranks) 1)) (a/density-grid ranks 1.0)))
    (is (= (vec (repeat (count ranks) 0)) (a/density-grid ranks 0.0)))))

(deftest density-grid-downbeat-always-survives-any-positive-density
  ;; downbeat holds the max rank -- any density that keeps at least one
  ;; pulse must keep it
  (doseq [subdivisions [[2] [3] [2 2] [2 2 3] [5 3]]]
    (let [ranks (a/indispensability subdivisions)]
      (is (= 1 (first (a/density-grid ranks (/ 1.0 (count ranks)))))))))

(deftest density-grid-is-deterministic
  (let [ranks (a/indispensability [2 2 3])]
    (is (= (a/density-grid ranks 0.5) (a/density-grid ranks 0.5)))))

;; ── beat-probabilities: zero-avoidance ──────────────────────────

(deftest beat-probabilities-never-collapses-at-adherence-zero
  ;; tied raw weights would all reduce to exp(0)=1 without a tie-break
  (let [probs (a/beat-probabilities [3 3 3 3] 0.0)]
    (is (apply distinct? probs))
    (is (< (Math/abs (- 1.0 (reduce + probs))) 1e-9))))

(deftest beat-probabilities-tie-break-is-strictly-increasing-in-position
  (let [probs (a/beat-probabilities [7 7 7 7 7] 0.0)]
    (is (apply < probs))))

(deftest beat-probabilities-tie-break-is-musically-negligible
  ;; the tie-break must never be large enough to out-rank real
  ;; adherence-driven differences
  (let [probs (a/beat-probabilities (a/indispensability [2 2]) 1.0)]
    (is (= 0 (apply max-key #(nth probs %) (range (count probs)))))))

;; ── power-law-probabilities ──────────────────────────────────────

(deftest power-law-probabilities-sums-to-one
  (doseq [adherence [-1.0 -0.3 0.0 0.3 1.0]]
    (let [probs (a/power-law-probabilities (a/indispensability [2 2 3]) adherence)]
      (is (< (Math/abs (- 1.0 (reduce + probs))) 1e-9)
          (str "adherence " adherence)))))

(deftest power-law-probabilities-at-zero-matches-raw-rank-proportions
  ;; (indispensability [2 2]) => [3 0 2 1], sum 6 -- k=1 at adherence=0
  ;; reduces to plain rank/sum, no reshaping at all
  (let [probs (a/power-law-probabilities (a/indispensability [2 2]) 0.0)]
    (is (every? true? (map #(< (Math/abs (- %1 %2)) 1e-9)
                            probs [0.5 0.0 (/ 2.0 6) (/ 1.0 6)])))))

(deftest power-law-probabilities-positive-adherence-favors-downbeat
  (let [downbeat-share #(first (a/power-law-probabilities (a/indispensability [2 2]) %))]
    (is (< (downbeat-share 0.1) (downbeat-share 1.0)))))

(deftest power-law-probabilities-negative-adherence-inverts-order
  ;; at adherence -1, the LEAST indispensable position (index 1, rank 0
  ;; for [2 2]) should hold the MOST probability mass instead
  (let [probs (a/power-law-probabilities (a/indispensability [2 2]) -1.0)]
    (is (= 1 (apply max-key #(nth probs %) (range (count probs)))))))

(deftest power-law-probabilities-zero-rank-position-gets-exactly-zero
  ;; adherence >= 0: the least-indispensable pulse's own normalized
  ;; weight is exactly 0, so 0^k stays exactly 0, unlike softmax
  (is (= 0.0 (nth (a/power-law-probabilities (a/indispensability [2 2]) 0.5) 1))))

(deftest power-law-probabilities-downbeat-gets-exactly-zero-at-full-negative-adherence
  (is (= 0.0 (first (a/power-law-probabilities (a/indispensability [2 2]) -1.0)))))
