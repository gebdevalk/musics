(ns ^:algo musics.algo.bridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.algo.bridge :as b]
            [musics.domain :as d]
            [musics.input.reader.leaf-parser]))

(defn- rest-of [x] (when (d/rest? x) [:rest (:duration x)]))
(defn- durs [xs] (map #(or (rest-of %) %) xs))

;; a window of counts, one for each bar of weights
(deftest counts-thin-each-bar-to-its-count
  (let [w [11 0 4 8 2 6 10 1]]
    (is (= [1 0 0 0 0 0 0 0, 1 0 0 1 0 0 1 0, 1 1 1 1 1 1 1 1]
           (b/counts->pulses w [1 3 8])) "the n strongest, 8 keeps every pulse")
    (is (= [0 0 0 0 0 0 0 0, 1 0 0 0 0 0 0 0] (b/counts->pulses w [0 1.4])) "rounded")
    (is (= (repeat 8 1) (b/counts->pulses w [12])) "held to the bar")
    (is (= [] (b/counts->pulses w [])))))

(deftest pulses-and-strokes-become-durations
  (is (= [[:rest 1/4] 1/4 1/8 1/8] (durs (b/pulses->durations [0 0 1 0 1 1] 1/8))) "0s lengthen; leading 0s one rest")
  (is (= [3/16 1/8] (durs (b/pulses->durations [2 0 0 1 0] 1/16))) "any nonzero is an onset")
  (is (= [[:rest 1/16] 1/8 1/16] (durs (b/strokes->durations ["-" "Ta" "-" "Ka"] 1/16))))
  (is (= [3/16 3/16 1/8 3/16 3/16] (take 5 (b/pulses->durations (cycle [1 0 0 1 0 0 1 0]) 1/16))) "lazy: a cycled source works"))

(deftest pulses-become-steps-a-note-or-a-rest-each
  (is (= [1/8 [:rest 1/8] 1/8 1/8 [:rest 1/8]] (durs (b/pulses->events [1 0 1 1 0] 1/8))) "a 1 a note, a 0 a rest, all one pulse long")
  (is (= [[:rest 1/16] [:rest 1/16] 1/16] (durs (b/pulses->events [0 0 2] 1/16))) "any nonzero is an onset; leading 0s are rests, not one")
  (is (= 5 (count (b/pulses->events [1 0 0 1 0] 1/16))) "one duration for every pulse")
  (is (= [1/16 [:rest 1/16] [:rest 1/16] 1/16] (durs (take 4 (b/pulses->events (cycle [1 0 0]) 1/16)))) "lazy: a cycled source works"))

(deftest onsets-numbers-and-points-become-durations
  (is (= [1/8 1/16 3/16] (b/onsets->durations [0.0 0.5 0.75 1.5] 1/4 1/32)))
  (testing "each number's place in from-lo..from-hi, clamped"
    (is (= [1/16 7/32 11/32 1/2 1/2] (b/numbers->durations [0 1 2 3 9] 0 3 1/16 1/2 1/32))))
  (is (= [1/16 1/2] (b/points->durations [[0 9] [1 9]] 0 0 1 1/16 1/2 1/32)))
  (is (= 1/8 (b/number->duration 0.5 0 1 0 1/4 1/32)) "the value bridge")
  (is (= 5 (count (take 5 (b/numbers->durations (repeatedly rand) 0 1 1/16 1/2 1/32)))) "lazy: an endless stream works"))

(deftest numbers-and-points-become-pitches
  (is (= [60 64 67 72 59] (b/degrees->pitches [0 2 4 7 -1] "C.major" 4)) "0 tonic, 7 an octave up, -1 below")
  (is (= [57 59 60] (b/degrees->pitches [0 1 2] "A.minor" 3)))
  (is (= [62 64 66] (b/degrees->pitches [0 1 2] "D.major" 4)) "the key's own accidentals")
  (is (= 72 (b/degree->pitch 7 "C.major" 4)) "the value bridge")
  (is (thrown-with-msg? Exception #"not a key" (b/degrees->pitches [0] "H.major" 4)))
  (is (= [60 66 72 72] (b/numbers->pitches [0 5 10 99] 0 10 60 72)) "clamped at the top")
  (is (= [60 72 83] (b/points->pitches [[0 1] [0.5 1] [1 1]] 0 0 1 "C.major" 4 2))))

(deftest weights-become-pulses-volumes-and-articulations
  (is (= [1 0 0 1 0 1 1 0] (b/weights->pulses [11 0 4 8 2 6 10 1] 0.5)))
  (is (= [80.0 40.0 60.0] (b/weights->volumes [2 0 1] 40.0 80.0)))
  (is (= [:ghost :ghost nil nil :accent :accent :marcato :marcato]
         (b/weights->articulations [0 1 2 3 4 5 6 7] [:ghost nil :accent :marcato]))))

(deftest pulses-become-accents
  (let [tresillo [1 0 0 1 0 0 1 0]]
    (is (= [80 51 69] (map #(Math/round (double %)) (b/pulses->volumes tresillo :metric 1/8 "4/4" 40.0 80.0)))
        "metric: the downbeat loudest, beat 4 (pulse 6) above the off-beat at pulse 3")
    (is (= [:marcato nil :accent] (b/pulses->articulations tresillo :metric 1/8 "4/4" [:ghost nil :accent :marcato])))
    (is (= [61 61 53] (map #(Math/round (double %)) (b/pulses->volumes tresillo :agogic 1/8 "4/4" 40.0 80.0)))
        "agogic: by length -- three eighths, three, then two (log of the length against a bar's)")
    (is (= [80.0 80.0] (take 2 (b/pulses->volumes (cycle [1 0 0 0 0 0 0 0]) :metric 1/8 "4/4" 40.0 80.0)))
        "lazy: an endless stream, a downbeat each bar")
    (is (= 4 (count (b/pulses->volumes [0 0 1 0 1 1] :metric 1/16 "4/4" 40.0 80.0))) "one per note pulses->durations makes")
    (is (nil? (first (b/pulses->volumes [0 0 1 0 1 1] :metric 1/16 "4/4" 40.0 80.0))) "nil for the rest before the first onset")
    (is (nil? (first (b/pulses->articulations [0 1 1] :metric 1/16 "4/4" [:ghost nil :accent :marcato]))))))
