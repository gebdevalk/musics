(ns ^:algo musics.algo.bridge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.algo.bridge :as b]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.domain :as d]
            [musics.events :as ev]
            [musics.mu.value :as v]
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

(deftest every-bridge-says-how-it-works
  (doseq [[k e] (t/algos) :when (= "bridge" (:category e)) :when (= "musics.algo.bridge" (namespace (:full e)))]
    (is (#{:value :shape :whole} (:works e)) (str k))))

(defn- onto
  "parts (leaves) with the stream xs written on them as part k, by zip."
  [parts k xs & [params]]
  (t/run (lib/zip (lib/as-part :leaf parts) (lib/as-part k xs)) (or params {})))

(deftest leaves-blend-the-materials
  (let [parts (t/run (lib/zip [(d/rest* nil nil 1/4) 1/8 1/8 1/4] [nil 60 [62 65] nil]) {})]
    (testing "a collection is a chord; nil a rest"
      (is (= [[:REST 1/4 nil] [:LEAF 1/8 [60]] [:LEAF 1/8 [62 65]] [:REST 1/4 nil]]
             (map (juxt :type :duration :pitches) parts))))
    (testing "a Rest in the durations uses its pitch unwritten; ends with the shorter"
      (is (= [[:REST nil] [:LEAF [62]] [:LEAF [64]]]
             (map (juxt :type :pitches) (t/run (lib/zip [(d/rest* nil nil 1/8) 1/8 1/8 1/8] [60 62 64]) {})))))
    (testing "zip onto notes uses a rest's value unwritten"
      (is (= [nil 50 90 nil] (map (comp :volume :overrides) (onto parts :volume [70 50 90 10])))))
    (testing "articulation sets length and adds its dynamic"
      (is (= [[nil nil] [0.55 10] [nil -20] [nil nil]]
             (map (juxt :articulation :dynamic) (onto parts :articulation [nil :marcato :ghost nil])))))
    (testing "instrument: a program, a General MIDI name (0-based) or a short name; a drum by :drum"
      (is (= [nil 40] (map (comp :instrument :overrides) (onto parts :instrument [nil 40]))) "ends where the values do")
      (is (= [40 122 nil] (map (comp :instrument :overrides) (rest (onto parts :instrument [nil "violin" "sea" nil])))) "a name, a short name; nil writes nothing")
      (let [[_ a b] (onto parts :drum [nil "kick" 38])]
        (is (= [:DRUM 36 1/8] ((juxt :type :program :duration) a)) ":drum makes a note a drum")
        (is (= [:DRUM 38] ((juxt :type :program) b))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a drum has no instrument"
                            (doall (onto (onto parts :drum [36 36]) :instrument [40 40])))))))

(deftest zip-joins-any-parts
  (testing "several children fold from the left, and print as written"
    (let [tr (lib/zip [1/4 1/8] [60 62] (lib/as-part :volume [90 70]))]
      (is (= '(zip [1/4 1/8] [60 62] (as-part :volume [90 70])) (t/show tr)))
      (is (= [[[60] 90] [[62] 70]] (map (juxt :pitches (comp :volume :overrides)) (t/run tr {}))))))
  (testing "without durations and pitches, a bundle of the parts, typed by them"
    (let [b (lib/zip (lib/as-part :pitch [60 62 64]) (lib/as-part :volume [90 70]))]
      (is (= [:bundle :pitch :volume] (t/out-type b)))
      (is (= {:type :BUNDLE :parts {:pitch [60 62 64] :volume [90 70]}} (t/run b {})))
      (is (= :leaf (t/out-type (lib/zip [1/4 1/4] b))) "joined again, with durations: notes")
      (is (= [[[60] 90] [[62] 70]] (map (juxt :pitches (comp :volume :overrides)) (t/run (lib/zip [1/4 1/4] b) {}))))))
  (testing "onto notes a part replaces their own"
    (is (= [[1/8 [67]] [1/8 [69]]]
           (map (juxt :duration :pitches) (t/run (lib/zip (lib/zip [1/4 1/4] [60 62]) (lib/as-part :duration [1/8 1/8]) (lib/as-part :pitch [67 69])) {})))))
  (testing "refused when built"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name the part a literal gives" (lib/zip (lib/zip [1/4] [60]) [90])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two streams for one part" (lib/zip (lib/as-part :pitch [60]) (lib/as-part :pitch [62]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a part of a note" (lib/zip [1/4] (lib/pick lib/indisp))) "nothing turns an index into a part")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two streams of notes" (lib/zip (lib/zip [1/4] [60]) (lib/zip [1/4] [62]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no part of a note" (lib/as-part :bogus [1])))))

(deftest a-leaf-plays-its-own-volume-and-program
  (let [[e] (filter #(= :note (:kind %))
                    (ev/events {} [(assoc (d/leaf nil nil 1/4 [60]) :overrides {:volume 80 :instrument 40})]))]
    (is (= [40 102] ((juxt :program :velocity) e)) "volume 80 -> velocity 102, over the context's")))

(deftest a-tree-from-bridges-to-leaves
  (let [tr  (lib/zip (lib/pulses->durations lib/euclid) (lib/degrees->pitches (lib/cycle> [0 2 4 7]))
                     (lib/cycle> (lib/weights->volumes lib/indisp)))
        out (t/run tr {:k 3 :n 8})]
    (is (= [3/16 3/16 1/8] (map :duration out)))
    (is (= [[60] [64] [67]] (map :pitches out)))
    (is (every? number? (map (comp :volume :overrides) out)))))

(deftest override-sets-any-played-key-per-note
  (let [parts (t/run (lib/zip [1/8 1/8 1/8] [60 62 64]) {})]
    (is (= [{:panning -1.0} {:panning 0.0} {:panning 1.0}]
           (map :overrides (onto parts :panning [-1.0 0.0 1.0]))))
    (is (= [{:transposition 12}] (map :overrides (take 1 (onto parts :transposition [12]))))))) 

(deftest generated-leaves-read-back-the-same-from-text
  (let [parts (t/run (lib/zip [1/4 1/8 1/8] [60 [62 65] 67]
                              (lib/as-part :volume [90 50 70]) (lib/as-part :articulation [:accent nil :staccato])
                              (lib/as-part :instrument [40 "violin" nil]) (lib/as-part :drum [nil nil "kick"]))
                     {})
        text  (str "[" (str/join " " (map musics.input.reader.leaf-parser/part->mus parts)) "]")
        {:keys [tree root-id]} (v/read-domain text)
        back  (:children (get tree (first (:children (get tree root-id)))))
        same  (fn [xs] (map (juxt :type :pitches :program :duration :articulation :dynamic :overrides) xs))]
    (is (= (same parts) (same back)) text)))

(deftest numbers-become-instruments
  (is (= [0 64 127] (b/numbers->instruments [0.0 0.5 1.0] 0.0 1.0 [])) "all of General MIDI by default")
  (is (= [40 73 122] (b/numbers->instruments [0.0 0.5 1.0] 0.0 1.0 [40 73 "seashore"])) "a palette, names too")
  (is (= [73] (b/points->instruments [[0 0.9]] 1 0.0 1.0 [40 73]))))
