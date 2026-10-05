(ns ^:algo musics.algo.bridge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.algo.bridge :as b]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.domain :as d]
            [musics.events :as ev]
            [musics.input.grammar-parser]
            [musics.input.reader.leaf-parser]))

(defn- rest-of [x] (when (d/rest? x) [:rest (:duration x)]))
(defn- durs [xs] (map #(or (rest-of %) %) xs))

(deftest pulses-and-strokes-become-durations
  (is (= [[:rest 1/4] 1/4 1/8 1/8] (durs (b/pulses->durations [0 0 1 0 1 1] 1/8))) "0s lengthen; leading 0s one rest")
  (is (= [3/16 1/8] (durs (b/pulses->durations [2 0 0 1 0] 1/16))) "any nonzero is an onset")
  (is (= [[:rest 1/16] 1/8 1/16] (durs (b/strokes->durations ["-" "Ta" "-" "Ka"] 1/16))))
  (is (= [3/16 3/16 1/8 3/16 3/16] (take 5 (b/pulses->durations (cycle [1 0 0 1 0 0 1 0]) 1/16))) "lazy: a cycled source works"))

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

(deftest every-bridge-says-how-it-works
  (doseq [[k e] (t/algos) :when (= "bridge" (:category e)) :when (= "musics.algo.bridge" (namespace (:full e)))]
    (is (#{:value :shape :whole} (:works e)) (str k))))

(deftest leaves-blend-the-materials
  (let [parts (t/run (lib/zip [(d/rest* nil nil 1/4) 1/8 1/8 1/4] [60 [62 65] nil]) {})]
    (testing "a Rest in the durations uses no pitch; a collection is a chord; nil a rest"
      (is (= [[:REST 1/4 nil] [:LEAF 1/8 [60]] [:LEAF 1/8 [62 65]] [:REST 1/4 nil]]
             (map (juxt :type :duration :pitches) parts))))
    (testing "blend steps skip rests"
      (is (= [nil 70 50 nil] (map (comp :volume :overrides) (t/run (lib/+volume parts [70 50 90]) {})))))
    (testing "articulation sets length and adds its dynamic"
      (is (= [[nil nil] [0.55 10] [nil -20] [nil nil]]
             (map (juxt :articulation :dynamic) (t/run (lib/+articulation parts [:marcato :ghost]) {})))))
    (testing "instrument: a program, a General MIDI name (0-based), or a drum"
      (is (= [nil 40] (map (comp :instrument :overrides) (t/run (lib/+instrument parts [40]) {}))) "ends at the first note with no value left")
      (let [[_ a b] (t/run (lib/+instrument parts ["violin" "kick"]) {})]
        (is (= 40 (get-in a [:overrides :instrument])))
        (is (= [:DRUM 36 1/8] ((juxt :type :program :duration) b))))
      (is (= :DRUM (:type (second (t/run (lib/+instrument parts [38 38]) {:drum? true}))))))))

(deftest a-leaf-plays-its-own-volume-and-program
  (let [[e] (filter #(= :note (:kind %))
                    (ev/events {} [(assoc (d/leaf nil nil 1/4 [60]) :overrides {:volume 80 :instrument 40})]))]
    (is (= [40 102] ((juxt :program :velocity) e)) "volume 80 -> velocity 102, over the context's")))

(deftest a-tree-from-bridges-to-leaves
  (let [tr  (lib/+volume (lib/zip (lib/pulses->durations lib/euclid) (lib/degrees->pitches (lib/cycle> [0 2 4 7])))
                         (lib/cycle> (lib/weights->volumes lib/indisp)))
        out (t/run tr {:k 3 :n 8})]
    (is (= [3/16 3/16 1/8] (map :duration out)))
    (is (= [[60] [64] [67]] (map :pitches out)))
    (is (every? number? (map (comp :volume :overrides) out)))))

(deftest override-sets-any-played-key-per-note
  (let [parts (t/run (lib/zip [1/8 1/8 1/8] [60 62 64]) {})]
    (is (= [{:panning -1.0} {:panning 0.0} {:panning 1.0}]
           (map :overrides (t/run (lib/+override parts [-1.0 0.0 1.0]) {:key :panning}))))
    (is (= [{:transposition 12}] (map :overrides (take 1 (t/run (lib/+override parts [12]) {:key :transposition}))))))) 

(deftest generated-leaves-read-back-the-same-from-text
  (let [parts (t/run (lib/+instrument (lib/+articulation (lib/+volume (lib/zip [1/4 1/8 1/8] [60 [62 65] 67]) [90 50 70])
                                                         [:accent nil :staccato])
                                      [40 "violin" "kick"]) {})
        text  (str "[" (str/join " " (map musics.input.reader.leaf-parser/part->mus parts)) "]")
        {:keys [tree root-id]} (musics.input.grammar-parser/parse-domain-string text)
        back  (:children (get tree (first (:children (get tree root-id)))))
        same  (fn [xs] (map (juxt :type :pitches :program :duration :articulation :dynamic :overrides) xs))]
    (is (= (same parts) (same back)) text)))
