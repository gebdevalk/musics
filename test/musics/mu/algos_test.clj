(ns ^:repl musics.mu.algos-test
  "The algos as mu words: by what they deliver, a line taken apart into
   their inputs, a ^{ @… } on top as their settings."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu]
            [musics.mu.algos :as algos]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.algo.random.core :as rc]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (:ns saved)))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- top [input]
  (swap! e/state assoc :stack [])
  (let [r (e/eval-input input)]
    (if (:error r) (:error r) (str/join " " (map p/show (:ok r))))))

(deftest every-shelf-is-a-namespace
  (is (< 150 (algos/register-all!)))
  (is (every? #(get-in @e/state [:ns (namespace %) (name %)])
              [:pitch/lorenz :pitch/walk-degrees :duration/euclid :duration/emi :volume/walk
               :articulation/tala :pitch/scale :leaf/pocket :parallel/drums :instrument/walk :volume/euclid]))
  (is (nil? (get-in @e/state [:ns "workshop"])) "raw material stays out"))

(deftest instruments-and-accents-as-words
  (is (re-matches #"'\[(\d+ ?)+\]" (top "^{ @instrument-palette:(40 73 seashore) @len:8 } instrument/walk")) "a stream of programs")
  (is (every? #{"40" "73" "122"} (re-seq #"\d+" (top "^{ @instrument-palette:(40 73 seashore) @len:8 } instrument/walk"))) "from the palette")
  (is (= "'[80.0 50.666666666666664 66.66666666666666]" (top "volume/euclid")) "a rhythm's accents, metric"))

(deftest a-source-with-its-defaults-or-a-context
  (is (= "'[3/16 3/16 1/8]" (top "duration/euclid")))
  (is (= "'[1/8 1/8 1/8 1/8]" (top "^{ @k:4 @n:4 @duration-pulse:1/8 } duration/euclid")) "a context on top is its settings")
  (is (re-find #"duration/euclid: :k 99 should be at most 32" (top "^{ @k:99 } duration/euclid"))))

(deftest a-line-taken-apart-into-an-algos-input
  (is (= "'[1/8 1/8 1/4 3/8 1/8]" (top "[c8 d e4 f4. g8] durations")))
  (is (= "1/2" (top "[c8 d4 e8] dup durations ^{ @index:1 } duration/necklace swap pitches zip durations 0 '( add ) reduce"))
      "a rotation keeps the length")
  (let [out (top "[c8 d4 e8] dup durations ^{ @index:1 } duration/necklace swap pitches zip")]
    (is (not= "[C4/8 D4/4 E4/8]" out) "the rhythm varied")
    (is (= ["C4" "D4" "E4"] (re-seq #"[A-G]4" out)) "the pitches kept, in order")))

(deftest a-repeat-stays-around-the-result
  (let [once (top "[c8 d e4 f8] durations ^{ @index:1 } duration/necklace")]
    (is (= (str once " [~]") (top "[c8 d e4 f8] [~] durations ^{ @index:1 } duration/necklace"))
        "the algo runs on what the loop repeats; the loop goes around its result")
    (is (= (str once " 2 times") (top "[c8 d e4 f8] 2 times durations ^{ @index:1 } duration/necklace"))))
  (is (str/starts-with? (top "[c d e] [~] leaf/pocket") "repeat ~ [") "a line in a loop gives a loop")
  (is (str/ends-with? (top "[c d e] [~] [e f g] [~] pitch/inter") "] [~]") "two loops by the same count")
  (is (re-find #"repeat differently" (top "[c d e] [~] [e f g] pitch/inter")))
  (is (re-find #"loop inside a line" (top "[a repeat ~ [c d]] duration/emi"))))

(deftest data-and-repeat-kinds-reach-an-algo
  (is (= 16 (count (re-seq #"\d+" (top "'[60 62 64] pitch/constrained")))) "data as its values")
  (is (str/ends-with? (top "'[60 62 64] [~] pitch/constrained") "] [~]") "a loop of data, as the reader prints it")
  (is (str/starts-with? (top "[repeat tremolo 4 [c16 d]] first leaf/pocket") "repeat tremolo 4 [") "a tremolo stays one")
  (is (str/starts-with? (top "[: c d :] leaf/pocket") "[: ") "repeat signs stay"))

(deftest a-param-follows-the-music
  (is (= "'[62]" (top "^{ !key:D.major @start:0.0 @step-bound:0.0 @len:1 } pitch/walk-degrees"))
      "the key of the context on top: D major's tonic")
  (is (= "'[64]" (top "^{ !key:D.major @pitch-key:E.minor @start:0.0 @step-bound:0.0 @len:1 } pitch/walk-degrees"))
      "an @ setting comes first")
  (is (= "'[60]" (top "^{ @start:0.0 @step-bound:0.0 @len:1 } pitch/walk-degrees")) "else :ROOT's C major"))

(deftest products
  (rc/seed! 3)
  (is (str/starts-with? (top "parallel/drums") "{") "parts as { }")
  (is (str/starts-with? (top "[c d e] leaf/pocket") "[") "leaves as a line"))

(deftest every-built-in-word-says-what-it-does
  (let [all (:ns @e/state)]
    (is (every? all ["kernel" "tool" "music" "pitch" "duration"]))
    (doseq [[ns m] all [w {:keys [effect source]}] m :when (not source)]
      (is (re-find #"\)\s\s\S" (str effect)) (str ns "/" w " has no description: " effect)))))
