(ns ^:algo physical-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.physical :as phys]))

(defn- round3 [t] (/ (Math/round (* t 1000.0)) 1000.0))

(deftest pendulum-rhythm-marks-only-downward-crossings
  (is (= [0.51 2.51] (mapv round3 (phys/pendulum-rhythm 0.3 1.0 9.8 3.0)))))

(deftest bouncing-ball-rhythm-decays-toward-a-stop
  (let [timings (phys/bouncing-ball-rhythm 2.0 0.7 9.8 5.0)]
    (is (= [0.0 0.639 1.086 1.399 1.618 1.772 1.879 1.954 2.007 2.044 2.069 2.087]
           (mapv round3 timings)))
    (is (apply <= timings) "bounces happen in increasing time order")))

(deftest logistic-map-rhythm-matches-the-reference-sequence
  (is (= [1 0 0 1 0 1 0 0 1 0 0 1 0 1 0 1 0 1 1 0]
         (phys/logistic-map-rhythm 3.9 0.5 20))))

(deftest bird-song-rhythm-follows-the-species-phrase-structure
  (is (= [0.0 0.15 0.3 0.75 0.9 1.05 1.2 1.65 1.8 1.95]
         (mapv round3 (phys/bird-song-rhythm "sparrow" 2.0)))))

(deftest bird-song-rhythm-falls-back-to-sparrow-for-unknown-species
  (is (= (phys/bird-song-rhythm "sparrow" 1.0)
         (phys/bird-song-rhythm "unknown-species" 1.0))))

(deftest heartbeat-rhythm-stays-near-the-base-interval
  ;; base interval at 80bpm is 0.75s; +-10% variability plus a small
  ;; respiratory wobble should never push a single gap far outside that
  (let [timings (phys/heartbeat-rhythm 80 0.1 5.0)
        gaps (map - (rest timings) timings)]
    (is (seq timings))
    (is (every? #(< 0.5 % 1.0) gaps))))

(deftest rainfall-rhythm-stays-within-duration-and-sorted
  (let [timings (phys/rainfall-rhythm 0.5 5.0)]
    (is (every? #(<= 0 % 5.0) timings))
    (is (apply <= timings))))

(deftest rainfall-rhythm-high-intensity-can-add-a-burst
  ;; not deterministic whether the burst timings survive the final sort
  ;; distinctly, but the function must still run and stay in bounds
  (let [timings (phys/rainfall-rhythm 0.95 5.0)]
    (is (every? #(<= 0 % 5.0) timings))
    (is (apply <= timings))))
