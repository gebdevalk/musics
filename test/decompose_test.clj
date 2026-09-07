(ns ^:algo decompose-test
  "Tests for algo.rhythmic.decompose/split-decompose. Run: lein test decompose-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.rhythmic.decompose :as d]))

(deftest split-decompose-whole-note-long-short
  (is (= [2/3 2/9 1/9] (d/split-decompose 1 3 2/3))))

(deftest split-decompose-whole-note-short-long
  (is (= [1/3 2/9 4/9] (d/split-decompose 1 3 1/3))))

(deftest split-decompose-sums-back-exactly
  (doseq [[duration depth ratio] [[1 3 2/3] [1 3 1/3] [1 4 1/2] [3/4 5 2/3] [1 1 2/3]]]
    (is (= duration (reduce + (d/split-decompose duration depth ratio)))
        (str "duration=" duration " depth=" depth " ratio=" ratio))))

(deftest split-decompose-depth-le-1-returns-unsplit
  (is (= [1] (d/split-decompose 1 0 1/2)))
  (is (= [1] (d/split-decompose 1 1 1/2))))

(deftest split-decompose-on-a-leaf-returns-copies-with-new-durations
  (is (= [{:pitches [60] :duration 2/3}
          {:pitches [60] :duration 2/9}
          {:pitches [60] :duration 1/9}]
         (d/split-decompose {:pitches [60] :duration 1} 3 2/3))))

(deftest split-decompose-on-a-leaf-preserves-other-fields
  (is (every? #(= [60] (:pitches %))
              (d/split-decompose {:pitches [60] :articulation :staccato :duration 1} 2 1/3))))

(deftest split-decompose-on-a-leaf-sums-back-exactly
  (is (= 1 (reduce + (map :duration (d/split-decompose {:pitches [60] :duration 1} 4 2/3))))))
