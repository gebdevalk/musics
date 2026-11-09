(ns ^:algo musics.algo.rhythmic.pattern-test
  "Tests for musics.algo.rhythmic.pattern."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.pattern :as a]))

;; ============================================================
;; pattern-test
;; ============================================================

(deftest euclidean-test
  ;; Regression test: the previous bucket-merging loop's own stopping
  ;; condition -- "do ALL remaining buckets already share the minimum
  ;; length" -- was trivially true on its very first check (every
  ;; bucket starts as a length-1 vector), so the loop always exited
  ;; before a single merge happened -- silently degenerating to k 1s
  ;; then (n-k) 0s for EVERY input, never actually distributing beats
  ;; evenly. This test file's own hardcoded expectations had the buggy
  ;; output baked straight in ([1 1 1 0 0 0 0 0]), so it confirmed the
  ;; bug rather than catching it -- confirmed live before fixing
  ;; (2026-09-14), against the textbook-known tresillo/cinquillo
  ;; patterns, not just internal self-consistency.
  (is (= [1 0 0 1 0 0 1 0] (a/euclid 3 8)) "the tresillo")
  (is (= [1 0 1 1 0 1 1 0] (a/euclid 5 8)) "the cinquillo")
  (is (= [1 0 1 0 1 0 1 0] (a/euclid 4 8)))
  (is (= [1 0 0 0 0] (a/euclid 1 5)))
  (is (= [0 0 0 0] (a/euclid 0 4))))

(deftest fibonacci-test
  (is (= [1 1 1 1 0 1 0 0 1 0 0 0 0] (a/fibonacci 13))))

(deftest prime-test
  (let [r (a/primes 10)]
    (is (= 10 (count r)))
    (is (= 1 (nth r 2)))
    (is (= 1 (nth r 3)))
    (is (= 1 (nth r 5)))
    (is (= 1 (nth r 7)))))

(deftest lindenmayer-test
  ;; Regression test: the zero-padding used to be concatenated BEFORE
  ;; the real values, so this always returned all-zeros regardless of
  ;; input -- confirmed live before fixing (2026-09-03).
  (let [r (a/lsys-rhythm "A" {"A" "AB" "B" "A"} 3 10)]
    (is (= 10 (count r)))
    (is (some pos? r) "must produce real, non-trivial pulses, not all zeros"))
  (is (= [1 0 1 1 0 0 0 0 0 0] (a/lsys-rhythm "A" {"A" "AB" "B" "A"} 3 10)))
  (is (= [1 0 1 1 0] (a/lsys-rhythm "A" {"A" "AB" "B" "A"} 3 5))
      "shorter length -- still real values, correctly truncated")
  (is (= [1 0 0 0 0 0] (a/lsys-rhythm "A" {} 0 6))
      "no rules/iterations -- axiom itself expands to just \"A\", padded with zeros"))

;; ============================================================
;; metric-test
;; ============================================================

(deftest modular-test
  (is (= [1 0 0 0 1 0 0] (a/modular 4 3 7 0))))

(deftest continued-fraction-test
  ;; Regression test: the zero-padding used to be concatenated BEFORE
  ;; the real values via an unbounded (repeat 0), so this always
  ;; returned all-zeros regardless of input -- confirmed live before
  ;; fixing (2026-09-03).
  (let [r (a/cfrac 1.5 8)]
    (is (= 8 (count r)))
    (is (some pos? r) "must produce real, non-trivial pulses, not all zeros"))
  (is (= [1 0 0 0 0 0 0 0] (a/cfrac 1.5 8)))
  (is (= [1 1 1 1 0 0 0 0 1 0] (a/cfrac 3.7 10)))
  (is (= [1 0 0] (a/cfrac 1.5 3))
      "shorter length -- still real values, correctly truncated"))

;; ============================================================
;; fractal-geometric-test
;; ============================================================

(deftest cantor-set-rhythm-removes-middle-thirds-recursively
  (is (= [1 0 1 0 0 0 1 0 1 0 0 0 0 0 0 0 0 0 1 0 1 0 0 0 1 0 1]
         (a/cantor 3 27))))

(deftest dragon-curve-rhythm-folds-and-flips
  (is (= [1 1 0 1 1 0 0 1 1 1 0 0 1 0 0 1 1 1 0 1 1 0 0 0 1 1 0 0 1 0 0]
         (a/dragon 4))))

(deftest polygon-rotation-rhythm-marks-vertex-alignments
  (is (= [1 0 0 0 0 0 0 0]
         (a/polygon 5 8 0.0))))

(deftest circle-of-fifths-rhythm-front-loads-the-active-notes
  (is (= (vec (concat (repeat 12 1) (repeat 12 0)))
         (a/fifths-rhythm 12 24))))

(deftest golden-ratio-rhythm-spreads-beats-with-shrinking-steps
  (is (= [1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0]
         (a/golden 21))))
