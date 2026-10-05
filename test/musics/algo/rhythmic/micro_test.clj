(ns ^:algo musics.algo.rhythmic.micro-test
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

(deftest pocket-lays-notes-back-by-their-place-in-the-beat
  (let [n  (fn [dur] {:type :LEAF :duration dur :pitches [60]})
        ls (micro/pocket [(n 1/16) (n 1/8) {:type :REST :duration 1/16} (n 1/4)] 0.05)]
    (is (= [0.05 0.06 nil 0.05]
           (map #(some-> % :overrides :micro (* 1000) Math/round (/ 1000.0)) ls))
        "on the beat, a sixteenth in, a rest, the next beat")))
