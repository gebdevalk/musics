(ns music-elements-test
  "Tests for Tempo, Meter, Pitch, Key, Chords, Circle of Fifths.
   Run: lein test music-elements-test"
  (:require [clojure.test :refer [deftest is]]
            [common.elements.music-elements :as el]))

(deftest tempo-construction
  (let [t (el/tempo 4 120)]
    (is (= 1/4 (:duration t)))
    (is (= 120 (:bpm t)))))

(deftest tempo-ms
  (let [t (el/tempo 4 60)]
    (is (= 1000 (el/duration-ms t 1/4)))
    (is (= 2000 (el/duration-ms t 1/2)))))

(deftest tempo-str
  (let [t (el/tempo 4 120)]
    (is (= "4=120" (el/tempo->str t)))
    (is (= 120 (:bpm (el/parse-tempo-str "4=120"))))))

(deftest tempo-ops
  (is (= 120 (:bpm (el/tempo* (el/tempo 4 60) 2))))
  (is (= 80 (:bpm (el/tempo+ (el/tempo 4 60) 20))))
  (is (= 100 (:bpm (el/tempo- (el/tempo 4 120) 20))))
  (is (= 20 (:bpm (el/tempo-diff (el/tempo 4 120) (el/tempo 4 100))))))

(deftest meter
  (let [m24 (el/make-meter 2 4)
        m68 (el/make-meter 6 8)
        m34 (el/make-meter 3 4)
        m44 (el/make-meter 4 4)
        m78 (el/make-meter 7 8 [2 2 3])]
    (is (el/duple? m24))
    (is (el/simple? m24))
    (is (el/duple? m68))
    (is (el/compound? m68))
    (is (el/triple? m34))
    (is (el/quadruple? m44))
    (is (el/additive? m78))
    (is (= "4/4" (el/meter->str m44)))
    (is (= "7/8(2+2+3)" (el/meter->str m78)))))

(deftest parse-meter-str-divisible
  (let [m (el/parse-meter-str "7/8")]
    (is (= 7 (:num m)))
    (is (= 8 (:den m)))
    (is (nil? (:subdivisions m)))))

(deftest parse-meter-str-additive
  (let [m (el/parse-meter-str "7/8(2+2+3)")]
    (is (= 7 (:num m)))
    (is (= 8 (:den m)))
    (is (= [2 2 3] (:subdivisions m)))))

(deftest parse-meter-str-round-trips-through-meter->str
  (doseq [s ["4/4" "3/4" "6/8" "7/8(2+2+3)" "5/8(3+2)"]]
    (is (= s (el/meter->str (el/parse-meter-str s))))))

(deftest parse-meter-str-rejects-mismatched-subdivisions
  (is (thrown? clojure.lang.ExceptionInfo (el/parse-meter-str "7/8(2+2+2)"))))

(deftest parse-meter-str-rejects-garbage
  (is (thrown? clojure.lang.ExceptionInfo (el/parse-meter-str "not-a-meter"))))

(deftest default-subdivisions-simple-meters
  (is (= [2] (el/default-subdivisions 2 4)))
  (is (= [3] (el/default-subdivisions 3 4)))
  (is (= [2 2] (el/default-subdivisions 4 4)))
  (is (= [5] (el/default-subdivisions 5 4)))
  (is (= [7] (el/default-subdivisions 7 4))))

(deftest default-subdivisions-compound-meters
  (is (= [2 3] (el/default-subdivisions 6 8)))
  (is (= [3 3] (el/default-subdivisions 9 8)))
  (is (= [2 2 3] (el/default-subdivisions 12 8)))
  (is (= [5 3] (el/default-subdivisions 15 8))))

(deftest default-subdivisions-irregular-meters-stay-flat
  ;; 5/8 and 7/8 are simple (not compound: num isn't divisible by 3), so
  ;; the default is their flat prime beat count, not a guessed grouping --
  ;; see default-subdivisions' own docstring for why.
  (is (= [5] (el/default-subdivisions 5 8)))
  (is (= [7] (el/default-subdivisions 7 8))))

(deftest pitch-names
  (is (= 60 (el/name->pitch (el/pitch->name 60))))
  (is (= 69 (el/name->pitch "a4")))
  (is (= "c4" (el/pitch->name 60 true)))
  (is (= 61 (el/name->pitch "c#4"))))

(deftest key-construction
  (let [k (el/key :C :major)]
    (is (= [0 2 4 5 7 9 11] (el/key-pitches k)))
    (is (= "C.major" (el/key->str k))))
  (let [k (el/key :A :minor)]
    (is (= [6 8 9 11 13 14 16] (el/key-pitches k))))
  (let [k (el/key :D :dorian)]
    (is (= [4 6 7 9 11 13 14] (el/key-pitches k))))
  (let [k (el/key :C :major)]
    (is (= [48 50 52 53 55 57 59] (el/key-absolute k 4)))))

(deftest parse-key
  (is (= "C.major" (el/key->str (el/parse-key "C.major"))))
  (is (= "Eb.minor" (el/key->str (el/parse-key "Eb.minor"))))
  (is (nil? (try (el/parse-key "H.major") (catch Exception _ nil)))))

(deftest chord-pitches
  (is (= [0 4 7] (el/chord-pitches :major 0)))
  (is (= [2 5 9] (el/chord-pitches :minor 2)))
  (is (= [7 11 2 5] (el/chord-pitches :dominant-7 7))))

(deftest parse-chord
  (is (= ["C" :major] (el/parse-chord-symbol "C")))
  (is (= ["C" :minor] (el/parse-chord-symbol "Cm")))
  (is (= ["C" :minor-7] (el/parse-chord-symbol "Cm7")))
  (is (= ["F#" :major-7] (el/parse-chord-symbol "F#maj7")))
  (is (= ["D" :half-diminished] (el/parse-chord-symbol "Dm7b5")))
  (is (= ["G" :sus4] (el/parse-chord-symbol "Gsus4"))))

(deftest circle-of-fifths
  (is (= :G (el/modulate :C 1)))
  (is (= :D (el/modulate :G 1)))
  (is (= :F (el/modulate :C -1)))
  (is (= :Bb (el/modulate :F -1)))
  (is (= :G (el/fifths-up :C)))
  (is (= :D (el/fifths-up :C 2)))
  (is (= :F (el/fifths-down :C)))
  (is (= 1 (el/cof-distance :C :G)))
  (is (= 0 (el/cof-distance :C :C)))
  (is (= 6 (el/cof-distance :C :F#))))
