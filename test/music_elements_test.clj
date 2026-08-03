(ns ^:domain music-elements-test
  "Tests for Tempo, Meter, Pitch, Key, Chords, Circle of Fifths.
   Run: lein test music-elements-test"
  (:require [clojure.test :refer [deftest is testing]]
            [common.music-elements :as el]))

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

;; ============================================================
;; Indispensability (Clarence Barlow)
;; ============================================================

(defn- permutation-of-0-to-n-1? [coll]
  (= (set coll) (set (range (count coll)))))

(deftest indispensability-base-cases
  ;; The four verified reference tables, fed back through the general
  ;; multi-level machinery via a single-element subdivisions vector --
  ;; must reproduce exactly, not just "a valid permutation."
  (is (= [1 0]             (el/indispensability [2])))
  (is (= [2 0 1]           (el/indispensability [3])))
  (is (= [4 0 1 3 2]       (el/indispensability [5])))
  (is (= [6 0 1 3 5 2 4]   (el/indispensability [7]))))

(deftest indispensability-downbeat-is-always-max
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [5 3] [7 3]]]
    (let [ranks (el/indispensability subdivisions)]
      (is (= (dec (count ranks)) (first ranks))
          (str "downbeat should be max for " subdivisions)))))

(deftest indispensability-is-always-a-permutation
  (doseq [subdivisions [[2] [3] [5] [7] [2 2] [2 3] [3 2] [2 2 3] [3 2 2]
                        [5 3] [7 3] [2 2 2 2 3]]]
    (is (permutation-of-0-to-n-1? (el/indispensability subdivisions))
        (str "should be a permutation of 0..N-1 for " subdivisions))))

(deftest indispensability-two-two-three-matches-confirmed-reference
  (is (= [11 0 4 8 2 6 10 1 5 9 3 7] (el/indispensability [2 2 3]))))

(deftest indispensability-unsupported-factor-throws
  (is (thrown? clojure.lang.ExceptionInfo (el/indispensability [11]))))

(deftest meter-indispensability-uses-default-subdivisions-when-none-given
  (let [m (el/make-meter 12 8)]
    (is (= (el/indispensability [2 2 3]) (el/meter-indispensability m)))))

(deftest meter-indispensability-honors-explicit-subdivisions
  (let [m (el/make-meter 7 8 [2 2 3])]
    (is (= (el/indispensability [2 2 3]) (el/meter-indispensability m)))))

(deftest pitch-names
  (is (= 60 (el/name->pitch (el/pitch->name 60))))
  (is (= 69 (el/name->pitch "a4")))
  (is (= "c4" (el/pitch->name 60 true)))
  (is (= 61 (el/name->pitch "c#4"))))

(deftest key-construction
  (let [k (el/key :C :major)]
    (is (= [0 2 4 5 7 9 11] (el/key-pitches k)))
    (is (= "C.major" (el/key->str k))))
  ;; A minor (A B C D E F G), D dorian (D E F G A B C) -- rooted at the
  ;; given tonic itself, not shifted by a "which degree of C major is
  ;; this mode" offset (confirmed as a real, pre-existing bug: key used
  ;; to add that offset to the tonic before walking scale-steps, so
  ;; (key :A :minor) silently built F# minor and (key :D :dorian) built
  ;; E dorian -- these two assertions used to encode that bug directly).
  (let [k (el/key :A :minor)]
    (is (= [9 11 12 14 16 17 19] (el/key-pitches k))))
  (let [k (el/key :D :dorian)]
    (is (= [2 4 5 7 9 11 12] (el/key-pitches k))))
  (let [k (el/key :C :major)]
    (is (= [48 50 52 53 55 57 59] (el/key-absolute k 4)))))

(deftest key-letter-offset
  (testing "D major implies sharps on F and C, naturals elsewhere"
    (let [k (el/key :D :major)]
      (is (= 1 (el/key-letter-offset k \f)))
      (is (= 1 (el/key-letter-offset k \c)))
      (is (every? zero? (map (partial el/key-letter-offset k) [\d \e \g \a \b])))))
  (testing "D minor implies a flat on B only"
    (let [k (el/key :D :minor)]
      (is (= -1 (el/key-letter-offset k \b)))
      (is (every? zero? (map (partial el/key-letter-offset k) [\c \d \e \f \g \a])))))
  (testing "F major implies a flat on B only"
    (let [k (el/key :F :major)]
      (is (= -1 (el/key-letter-offset k \b)))))
  (testing "C major implies nothing"
    (let [k (el/key :C :major)]
      (is (every? zero? (map (partial el/key-letter-offset k) [\c \d \e \f \g \a \b])))))
  (testing "a non-7-note scale implies nothing, regardless of tonic"
    (let [k (el/key :D :pentatonic-major)]
      (is (every? zero? (map (partial el/key-letter-offset k) [\c \d \e \f \g \a \b]))))))

(deftest key-pitch-name
  (testing "a diatonic pitch is spelled with the key's own implied letter+accidental"
    (let [k (el/key :D :major)]
      (is (= "f#4" (el/key-pitch-name k 66)))
      (is (= "c#5" (el/key-pitch-name k 73)))
      (is (= "d4"  (el/key-pitch-name k 62)))))
  (testing "a chromatic pitch outside the scale falls back to sharps/flats by signature sign"
    (let [d-major (el/key :D :major)
          f-major (el/key :F :major)]
      (is (= "c4"  (el/key-pitch-name d-major 60)) "natural C isn't in D major's scale")
      (is (= "eb4" (el/key-pitch-name f-major 63)) "F major's signature prefers flats"))))

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
