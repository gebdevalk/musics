(ns ^:algo musics.algo.rhythmic.sieve-test
  "Tests for musics.algo.rhythmic.sieve."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.sieve :as ps]))

;; ============================================================
;; phase-sieve-test
;; ============================================================

(deftest clapping-music-phases-rotates-progressively
  (let [pattern [1 1 1 0 1 1 0 1 0 1 1 0]
        phases  (ps/clapping-music-phases pattern 3)]
    (is (= [[1 1 1 0 1 1 0 1 0 1 1 0]
            [1 1 0 1 1 0 1 0 1 1 0 1]
            [1 0 1 1 0 1 0 1 1 0 1 1]]
           phases))))

(deftest clapping-music-duet-returns-static-and-shifted
  (let [pattern [1 1 1 0 1 1 0 1 0 1 1 0]]
    (is (= [pattern [0 1 1 0 1 0 1 1 0 1 1 1]]
           (ps/clapping-music-duet pattern 3)))))

(deftest xenakis-sieve-unions-multiple-residue-classes
  (is (= [1 1 1 1 1 0 1 1 0 1 1 0]
         (ps/xenakis-sieve [3 4] [[0 1] [2]] 12))))

(deftest sieve-from-intervals-places-beats-at-cumulative-sums
  (is (= [1 0 1 0 1 0 1 0 1 0]
         (ps/sieve-from-intervals [2 3] 10))))

;; ============================================================
;; automaton-test
;; ============================================================

(deftest rule-90-draws-sierpinski
  (is (= [[0 0 0 1 0 0 0]
          [0 0 1 0 1 0 0]
          [0 1 0 0 0 1 0]
          [1 0 1 0 1 0 1]]
         (map vec (partition 7 (ps/automaton 90 7 4 :single))))))

(deftest a-random-start-gives-width-times-steps-pulses
  (let [ps (ps/automaton 30 16 8 :random)]
    (is (= 128 (count ps)))
    (is (every? #{0 1} ps))))

;; ============================================================
;; necklace-test
;; ============================================================

(deftest rhythm-necklace-collects-unique-rotations
  (is (= [[1 0 1 0 0] [0 1 0 0 1] [1 0 0 1 0] [0 0 1 0 1] [0 1 0 1 0]]
         (ps/rhythm-necklace [1 0 1 0 0]))))

(deftest rhythm-bracelet-adds-reversals
  (is (= [[1 0 1 0 0] [0 0 1 0 1] [0 1 0 0 1] [1 0 0 1 0] [0 1 0 1 0]]
         (ps/rhythm-bracelet [1 0 1 0 0]))))

(deftest all-binary-necklaces-with-k-ones
  (is (= [[1 1 0 0] [1 0 1 0]]
         (ps/all-binary-necklaces 4 2))))

(deftest all-binary-necklaces-without-k-covers-every-class
  (is (= 6 (count (ps/all-binary-necklaces 4)))))

(deftest rhythmic-tiling-detects-overlap
  (let [[combined tiling?] (ps/rhythmic-tiling [1 0 0] [0 1 0] 9)]
    (is (= [1 1 0 1 1 0 1 1 0] combined))
    (is (false? tiling?))))

(deftest rhythmic-tiling-succeeds-when-patterns-are-complementary
  ;; two length-2 patterns, [1 0] and [0 1], tile length 4 perfectly:
  ;; [1 0 1 0] + [0 1 0 1] = [1 1 1 1], no overlap
  (let [[combined tiling?] (ps/rhythmic-tiling [1 0] [0 1] 4)]
    (is (= [1 1 1 1] combined))
    (is (true? tiling?))))

(deftest vuza-canon-is-empty-for-this-simplified-search
  ;; the reference's own docstring admits this simplified search rarely
  ;; (here: never, for n up to 12) finds a genuine tiling
  (is (= [] (ps/vuza-canon 9))))

(deftest tiled-is-the-tilings-grid
  (is (= (first (ps/rhythmic-tiling [1 0] [0 1] 4)) (ps/tiled [1 0] [0 1] 4))))
