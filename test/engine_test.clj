(ns engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.music-parser :as p]
            [output.midi.engine :as engine]))

(defn notes-from [text]
  (engine/collect-notes (:score (p/parse text))))

(deftest collect-single-note
  (let [ns (notes-from "c4")]
    (is (= 1 (count ns)))
    (is (= [60] (:pitches (first ns))))))

(deftest collect-multiple-notes
  (let [ns (notes-from "c4 d4 e4")]
    (is (= 3 (count ns)))
    (is (= [60] (:pitches (nth ns 0))))
    (is (= [62] (:pitches (nth ns 1))))
    (is (= [64] (:pitches (nth ns 2))))))

(deftest collect-rest-skips
  (let [ns (notes-from "c4 r4 e4")]
    (is (= 2 (count ns)) "rest produces no note")))

(deftest collect-chord
  (let [ns (notes-from "<c e g>2")]
    (is (= 1 (count ns)))
    (is (= [60 64 67] (:pitches (first ns))))))

(deftest collect-duration-nonzero
  (let [ns (notes-from "c4")]
    (is (pos? (:duration-notated (first ns))))))

;; Composite walking
(deftest walk-seq
  (let [ns (notes-from "[c4 d4 e4]")]
    (is (= 3 (count ns)))))

(deftest walk-nested-seq
  (let [ns (notes-from "[[c4 d4] [e4 f4]]")]
    (is (= 4 (count ns)))))

(deftest walk-par
  (let [ns (notes-from "<<c4 d4 e4>>")]
    (is (= 3 (count ns)))))

(deftest walk-list
  (let [ns (notes-from "(c4 d4)")]
    (is (= 2 (count ns)))))

(deftest walk-list-inside-seq
  (let [ns (notes-from "[ (c4 d4) e4 ]")]
    (is (= 3 (count ns)))))

;; Articulation through pipeline
(deftest pipeline-staccato
  (let [ns (notes-from "c4-.")
        n  (first ns)]
    (is (< 0.49 (:duration-notated n) 0.51))
    (is (< 0.19 (:duration-played n) 0.21))))

(deftest pipeline-marcato
  (let [ns (notes-from "c4-^")
        n  (first ns)]
    (is (= 76 (:velocity n)))))

;; Edge cases
(deftest all-rests-empty
  (let [ns (notes-from "r4 r4 r4")]
    (is (= 0 (count ns)))))

;; Velocity
(deftest velocity-nonzero
  (let [ns (notes-from "c4")]
    (is (> (:velocity (first ns)) 50))))

(deftest velocity-in-range
  (let [ns (notes-from "c4")]
    (is (<= 0 (:velocity (first ns)) 127))))

;; Channels
(deftest channel-default-zero
  (let [ns (notes-from "c4")]
    (is (= 0 (:channel (first ns))))))

(deftest channel-param
  (let [ns (engine/collect-notes (:score (p/parse "c4")) :channel 3)]
    (is (= 3 (:channel (first ns))))))

(deftest pipeline-cresc
  (let [ns (notes-from "!cresc c4 d4 e4")]
    (is (= 3 (count ns)))
    (is (> (:velocity (first ns)) 80) "cresc raises volume above default")))

(deftest pipeline-dim
  (let [ns (notes-from "!dim c4 d4 e4")]
    (is (= 3 (count ns)))
    (is (< (:velocity (first ns)) 110) "dim lowers volume below default")))
