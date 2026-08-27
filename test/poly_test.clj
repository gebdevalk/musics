(ns ^:domain poly-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.poly :as poly]))

(deftest polyrhythm-places-each-layer-evenly
  (is (= [[1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0]
          [1 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0]]
         (poly/polyrhythm [[3 8] [2 8]] 24))))

(deftest polymeter-marks-strong-and-medium-beats
  (is (= [[2 0 0 2 0 0 2 0 0 2 0 0]
          [2 1 1 1 2 1 1 1 2 1 1 1]]
         (poly/polymeter [[3 4] [4 4]] 12))))

(deftest metric-modulation-converts-tempo-by-ratio
  (is (= [0.0 0.375]
         (poly/metric-modulation 120 3/2 [1 0 1 0]))))

(deftest nested-tuplets-expands-one-level
  (is (= [1 0 0 0 0 1 0 0]
         (poly/nested-tuplets [1 0 1] 3/2 1))))

(deftest nested-tuplets-recurses-for-depth-two
  (is (= [1 0 0 0 0 0 0 0 0 0 0]
         (poly/nested-tuplets [1 0] 3/2 2))))
