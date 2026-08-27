(ns ^:domain sonification-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.sonification :as sf]))

(def sample-data [1.0 3.0 2.0 5.0 1.0 4.0 2.0 6.0])

(deftest data-to-rhythm-median-threshold
  (is (= [0 1 0 1 0 1 0 1] (sf/data-to-rhythm sample-data "median"))))

(deftest data-to-rhythm-mean-threshold
  (is (= [0 0 0 1 0 1 0 1] (sf/data-to-rhythm sample-data "mean"))))

(deftest data-to-rhythm-adaptive-threshold
  (is (= [0 1 0 1 0 1 0 1] (sf/data-to-rhythm sample-data "adaptive"))))

(deftest data-to-rhythm-with-smoothing
  (is (= [0 0 1 0 1 0 1 1] (sf/data-to-rhythm sample-data "median" 3))))

(deftest data-to-rhythm-empty-input
  (is (= [] (sf/data-to-rhythm [] "median"))))

(deftest stock-market-rhythm-marks-trend-strength
  (is (= [0 0 0 0 0 -1 2 -1 1 2]
         (sf/stock-market-rhythm [100 101 99 102 105 98 110 95 103 108]))))

(deftest stock-market-rhythm-too-short-is-all-zero
  (is (= [0 0 0] (sf/stock-market-rhythm [1 2 3] 5))))

(deftest text-to-rhythm-syllables-mode
  (is (= [1 0 1 1 1 1 0] (sf/text-to-rhythm "Hello world this is rhythm." "syllables"))))

(deftest text-to-rhythm-words-mode
  (is (= [1 1 1 1 1 0] (sf/text-to-rhythm "Hello world this is rhythm." "words"))))

(deftest text-to-rhythm-stress-mode
  (is (= [1 0 1 1 1 1 0] (sf/text-to-rhythm "Hello world this is rhythm." "stress"))))

(deftest text-to-rhythm-no-terminal-punctuation-has-no-trailing-rest
  (is (= [1 1] (sf/text-to-rhythm "Hi go" "words"))))
