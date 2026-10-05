(ns ^:algo micro-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.micro :as micro]))

(deftest swing-quantization-delays-upbeats-only
  (is (= [0.0 1.0 2.0 3.0]
         (micro/swing-quantization [1 0 1 0 1 0 1 0] 0.67))))

(deftest swing-quantization-straight-is-identity-on-the-grid
  (is (= [0.0 0.5 1.0 1.5]
         (micro/swing-quantization [1 1 1 1] 0.5))))

(deftest swing-quantization-upbeats-land-at-the-ratio-of-their-pair
  ;; step 0.5: a pair of steps spans 1.0, the upbeat sits swing-ratio into it
  (let [ts (micro/swing-quantization [1 1 1 1] 0.75)]
    (is (= [0.0 0.75 1.0 1.75] ts))
    (is (apply < ts))))

(deftest humanize-rhythm-stays-close-to-original-and-sorted
  (let [humanized (micro/humanize-rhythm [0.0 0.25 0.5 0.75] 0.01 0.1)]
    (is (= 4 (count humanized)))
    (is (apply <= (map :time humanized)))
    (is (every? #(<= 0.1 (:velocity %) 1.0) humanized))
    (is (every? (fn [{:keys [time original-time]}] (< (Math/abs (- time original-time)) 0.02))
                humanized))))

(deftest pocket-groove-delays-later-beats-in-the-bar-more
  (let [groove (micro/pocket-groove [1 0 0 1 0 0 0 0] 0.05 nil)
        by-beat (into {} (map (juxt :beat-position :time) groove))]
    (is (< (get by-beat 0) (get by-beat 3)) "beat 3 should be pushed later than beat 0")))
