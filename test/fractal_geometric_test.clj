(ns ^:algo fractal-geometric-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.fractal-geometric :as fg]))

(deftest cantor-set-rhythm-removes-middle-thirds-recursively
  (is (= [1 0 1 0 0 0 1 0 1 0 0 0 0 0 0 0 0 0 1 0 1 0 0 0 1 0 1]
         (fg/cantor-set-rhythm 3 27))))

(deftest dragon-curve-rhythm-folds-and-flips
  (is (= [1 1 0 1 1 0 0 1 1 1 0 0 1 0 0 1 1 1 0 1 1 0 0 0 1 1 0 0 1 0 0]
         (fg/dragon-curve-rhythm 4))))

(deftest l-system-rhythm-expands-then-reads-off-abc
  (is (= [1 0 1 1 0]
         (fg/l-system-rhythm "A" {\A "AB" \B "A"} 3))))

(deftest polygon-rotation-rhythm-marks-vertex-alignments
  (is (= [1 0 0 0 0 0 0 0]
         (fg/polygon-rotation-rhythm 5 8 0.0))))

(deftest circle-of-fifths-rhythm-front-loads-the-active-notes
  (is (= (vec (concat (repeat 12 1) (repeat 12 0)))
         (fg/circle-of-fifths-rhythm 12 24))))

(deftest golden-ratio-rhythm-spreads-beats-with-shrinking-steps
  (is (= [1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0]
         (fg/golden-ratio-rhythm 21))))
