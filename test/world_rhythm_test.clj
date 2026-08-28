(ns ^:algo world-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.world :as w]))

(deftest theka-pattern-teental-matches-the-hand-transcribed-bols
  (is (= [1 1 1 1 1 1 1 1 1 0 0 1 1 1 1 1] (w/theka-pattern "teental"))))

(deftest theka-pattern-jhaptal-matches-the-hand-transcribed-bols
  (is (= [1 0 1 1 0 1 0 1 1 0] (w/theka-pattern "jhaptal"))))

(deftest theka-pattern-falls-back-to-accenting-each-vibhags-first-matra
  (is (= [1 0 0 1 0 1 0] (w/theka-pattern "rupak"))))

(deftest theka-pattern-non-tabla-alternates
  (is (= [1 0 1 0 1 0 1 0 1 0 1 0 1 0 1 0] (w/theka-pattern "teental" "mridangam"))))

(deftest tala-pattern-marks-sam-with-the-strongest-accent
  (let [first-three (take 3 (w/tala-pattern "teental"))]
    (is (= [3 0 0] (mapv :accent first-three)))
    (is (= [0.0 1.0 2.0] (mapv :time first-three)))))

(deftest konnakol-pattern-cycles-syllables-across-on-beats
  (is (= ["Ta" "-" "Ka" "-" "Di" "Mi" "-" "Tom"] (w/konnakol-pattern))))

(deftest bell-pattern-known-names
  (is (= [1 0 1 0 1 0 1 0 1 0 1 0] (w/bell-pattern [12 8] "standard")))
  (is (= [1 0 0 1 0 0 1 0 0 0 1 0 0 1 0 0] (w/bell-pattern [16 8] "clave"))))

(deftest bell-pattern-unknown-name-generates-from-the-meter
  (is (= [1 0 0 0 1 0 0 0] (w/bell-pattern [8 8] "nonexistent"))))

(deftest cross-rhythm-3-2-marks-triple-and-duple-layers
  (is (= [[1 0 0 0 1 0 0 0 1 0 0 0]
          [1 0 0 0 0 0 1 0 0 0 0 0]]
         (w/cross-rhythm-3-2 12))))

(deftest african-polyrhythm-first-four-layers-use-the-classic-ratios
  (let [layers (w/african-polyrhythm 4 12)]
    (is (= 4 (count layers)))
    (is (every? #(= 12 (count %)) layers))
    (is (every? #(= 1 %) (map #(nth % 0) layers)) "every layer's own first primary beat lands on 0")))

(deftest djembe-basic-is-fully-deterministic
  (is (= [{:time 0.0 :stroke "B" :accent 1} {:time 0.5 :stroke "T" :accent 0}
          {:time 1.0 :stroke "S" :accent 1} {:time 1.5 :stroke "T" :accent 0}
          {:time 2.0 :stroke "B" :accent 1} {:time 2.5 :stroke "T" :accent 0}
          {:time 3.0 :stroke "S" :accent 1} {:time 3.5 :stroke "T" :accent 0}]
         (w/djembe-pattern "basic" 8))))

(deftest djembe-accompaniment-alternates-bass-and-tone
  (is (= ["B" "T" "B" "T" "B" "T"] (mapv :stroke (w/djembe-pattern "accompaniment" 6)))))

(deftest djembe-solo-follows-the-four-beat-accent-cycle
  (is (= [2 0 1 0 2 0] (mapv :accent (w/djembe-pattern "solo" 6)))))
