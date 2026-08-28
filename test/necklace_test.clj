(ns ^:algo necklace-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.necklace :as nk]))

(deftest rhythm-necklace-collects-unique-rotations
  (is (= [[1 0 1 0 0] [0 1 0 0 1] [1 0 0 1 0] [0 0 1 0 1] [0 1 0 1 0]]
         (nk/rhythm-necklace [1 0 1 0 0]))))

(deftest rhythm-bracelet-adds-reversals
  (is (= [[1 0 1 0 0] [0 0 1 0 1] [0 1 0 0 1] [1 0 0 1 0] [0 1 0 1 0]]
         (nk/rhythm-bracelet [1 0 1 0 0]))))

(deftest all-binary-necklaces-with-k-ones
  (is (= [[1 1 0 0] [1 0 1 0]]
         (nk/all-binary-necklaces 4 2))))

(deftest all-binary-necklaces-without-k-covers-every-class
  (is (= 6 (count (nk/all-binary-necklaces 4)))))

(deftest rhythmic-tiling-detects-overlap
  (let [[combined tiling?] (nk/rhythmic-tiling [1 0 0] [0 1 0] 9)]
    (is (= [1 1 0 1 1 0 1 1 0] combined))
    (is (false? tiling?))))

(deftest rhythmic-tiling-succeeds-when-patterns-are-complementary
  ;; two length-2 patterns, [1 0] and [0 1], tile length 4 perfectly:
  ;; [1 0 1 0] + [0 1 0 1] = [1 1 1 1], no overlap
  (let [[combined tiling?] (nk/rhythmic-tiling [1 0] [0 1] 4)]
    (is (= [1 1 1 1] combined))
    (is (true? tiling?))))

(deftest vuza-canon-is-empty-for-this-simplified-search
  ;; the reference's own docstring admits this simplified search rarely
  ;; (here: never, for n up to 12) finds a genuine tiling
  (is (= [] (nk/vuza-canon 9))))
