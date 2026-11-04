(ns ^:algo musics.algo.rhythmic.process-test
  "Tests for musics.algo.rhythmic.process."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.process :as p]
            [musics.algo.numeric.rng :as seed]))

(deftest random-rhythm-at-full-density-fires-every-beat
  (is (= [0.0 0.25 0.5 0.75] (p/random-rhythm 0.25 4 1.0))))


(deftest random-rhythm-at-zero-density-fires-no-beats
  (is (= [] (p/random-rhythm 0.25 4 0.0))))


(deftest poisson-events-stay-within-duration
  (doseq [t (p/poisson-events 4 8)]
    (is (< 0.0 t 8.0))))


(deftest poisson-events-are-strictly-increasing
  (let [events (p/poisson-events 4 8)]
    (is (apply < events) "onset times must be strictly increasing")))


(deftest poisson-events-average-count-tracks-rate
  ;; expected count = rate * duration; check it's in the right ballpark
  ;; across many draws rather than asserting an exact count
  (let [counts (repeatedly 200 #(count (p/poisson-events 4 8)))
        mean   (/ (reduce + counts) (count counts))]
    (is (< 20 mean 44) (str "mean event count " mean " far from expected 32"))))


(deftest poisson-events-at-zero-duration-is-empty
  (is (= [] (p/poisson-events 4 0))))


(deftest poisson-events-is-seedable
  (is (= (seed/with-seed 9 (p/poisson-events 4 8))
         (seed/with-seed 9 (p/poisson-events 4 8)))))
