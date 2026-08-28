(ns ^:parsing abc-import-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [input.abc-import :as abc]
            [input.grammar-parser :as gp]))

(defn- parses? [mus-text]
  (not (insta/failure? (gp/parse-string mus-text))))

;; ============================================================
;; key-signature -- all 15 real major key signatures, both directions of
;; every ambiguous (enharmonic) slot
;; ============================================================

(deftest key-signature-covers-all-15-major-keys-with-correct-counts
  ;; A real bug lived here: an earlier hand-typed major-key-signatures
  ;; table had three transcription errors (Gb missing its own C, Db
  ;; carrying an extra one, Cb missing its own F) that spot-checking only
  ;; C/D/G/F would never have caught -- this asserts the EXACT sharp/flat
  ;; count for all 15 keys, not just a couple of common ones.
  (doseq [[letter acc expected-sharps expected-flats]
          [["C" nil 0 0] ["G" nil 1 0] ["D" nil 2 0] ["A" nil 3 0] ["E" nil 4 0]
           ["B" nil 5 0] ["F" "#" 6 0] ["C" "#" 7 0]
           ["F" nil 0 1] ["B" "b" 0 2] ["E" "b" 0 3] ["A" "b" 0 4]
           ["D" "b" 0 5] ["G" "b" 0 6] ["C" "b" 0 7]]]
    (let [sig (abc/key-signature letter acc :major)]
      (is (= expected-sharps (count (filter #(= "#" (val %)) sig)))
          (str letter acc " should have " expected-sharps " sharps"))
      (is (= expected-flats (count (filter #(= "b" (val %)) sig)))
          (str letter acc " should have " expected-flats " flats")))))

(deftest key-signature-modal-shift-matches-relative-major
  ;; D dorian shares C major's own (empty) signature; A mixolydian shares
  ;; D major's own (2-sharp) signature -- confirms mode-degree-offset's
  ;; own shift direction, not just that SOME table lookup happens.
  (is (= (abc/key-signature "C" nil :major) (abc/key-signature "D" nil :dorian)))
  (is (= (abc/key-signature "D" nil :major) (abc/key-signature "A" nil :mixolydian)))
  (is (= (abc/key-signature "A" nil :minor) (abc/key-signature "C" nil :major))
      "A minor (aeolian) shares C major's own signature"))

(deftest key-signature-ambiguous-slot-picks-flats-only-when-tonic-written-flat
  (is (= "#" (get (abc/key-signature "F" "#" :major) "F"))
      "F# major (written sharp) -> sharp direction")
  (is (= "b" (get (abc/key-signature "G" "b" :major) "G"))
      "Gb major (written flat) -> flat direction, same pitch class as F#"))

;; ============================================================
;; duration->mus -- clean 1/n and dotted matches, times-wrapper fallback
;; ============================================================

(deftest duration-matches-plain-and-dotted-values
  (is (= ["4" nil] (abc/duration->mus 1/4)))
  (is (= ["8" nil] (abc/duration->mus 1/8)))
  (is (= ["4." nil] (abc/duration->mus 3/8)) "dotted quarter")
  (is (= ["8.." nil] (abc/duration->mus 7/32)) "double-dotted eighth"))

(deftest duration-falls-back-to-times-wrapper-for-an-irregular-ratio
  (let [[dur wrapper] (abc/duration->mus 5/16)]
    (is (nil? dur))
    (is (= "5/16" wrapper))))

;; ============================================================
;; Full pipeline: real ABC -> musics text -> grammar-parses, with
;; assertions on the ACTUAL converted text, not just "it parses"
;; ============================================================

(def simple-tune
  "X:1
T:Test Tune
M:4/4
L:1/8
Q:1/4=120
K:D
DFA2 d2 FA | d4 z4 |]")

(deftest simple-tune-converts-correctly-and-parses
  (let [mus (abc/abc-text->mus-text simple-tune)]
    (is (parses? mus))
    (is (str/includes? mus "!Meter:4/4"))
    (is (str/includes? mus "!tempo:4=120"))
    (is (str/includes? mus "!key:D.major"))
    ;; D major: F/C sharped by the key, D/A untouched -- confirms the
    ;; converter applies the KEY's own implied accidentals, not just
    ;; passing bare letters through.
    (is (str/includes? mus "D38"))
    (is (str/includes? mus "F#38"))
    (is (str/includes? mus "A34"))
    (is (str/includes? mus "D44") "lowercase d -> D4, one octave above uppercase D3")))

(def two-tune-book
  "X:1
T:First
M:3/4
L:1/8
K:G
G2 A2 B2 |]

X:2
T:Second
M:6/8
K:Amin
A3 B3 |]")

(deftest tunebook-with-two-tunes-converts-and-both-parse
  (let [mus (abc/abc-text->mus-text two-tune-book)]
    (is (parses? mus))
    (is (str/includes? mus "tune1_first"))
    (is (str/includes? mus "tune2_second")
        "both tunes present under distinct ids, not just the first")
    (is (str/includes? mus "!key:G.major"))
    (is (str/includes? mus "!key:A.minor"))))

(def tuplet-and-chord-tune
  "X:1
T:Tuplet and chord
M:4/4
L:1/8
K:C
[CEG]4 (3CDE F2 | C2-C2 z4 |]")

(deftest tuplet-and-chord-converts-correctly-and-parses
  (let [mus (abc/abc-text->mus-text tuplet-and-chord-tune)]
    (is (parses? mus))
    (is (str/includes? mus "<C3 E3 G3>2") "chord: all 3 pitches, not just the first")
    (is (str/includes? mus "(times 2/3 [C38 D38 E38])") "eighth-note triplet")
    (is (str/includes? mus "C34~ C34") "tie glued onto the FIRST note of the pair")))

(def flat-key-with-override-tune
  "X:1
T:Flat Key Test
M:3/4
L:1/8
K:Eb
E2 G2 B2 | c2 B2 A2 | ^A2 =B2 G2 | E6 |]")

(deftest flat-key-implied-accidentals-and-explicit-overrides
  (let [mus (abc/abc-text->mus-text flat-key-with-override-tune)]
    (is (parses? mus))
    ;; Eb major flats B/E/A -- confirms the IMPLIED accidental actually
    ;; reaches bare, un-marked notes, not just ones written with a sign.
    (is (str/includes? mus "Eb34") "bare E gets Eb major's own implied flat")
    (is (str/includes? mus "Bb34") "bare B likewise")
    (is (str/includes? mus "G34") "G is NOT in Eb major's signature -- stays natural")
    (is (str/includes? mus "C44") "C likewise stays natural (lowercase c, octave 4)")
    ;; explicit accidentals override the key regardless of direction
    (is (str/includes? mus "A#34") "explicit ^A -- sharp, not the key's own implied flat")
    (is (str/includes? mus "Bn34") "explicit =B -- natural, cancelling the key's own flat")
    (is (str/includes? mus "Eb32.") "E6 at L:1/8 = 3/4 = dotted half")))

(def slur-tune
  "X:1
T:Slur Test
M:4/4
L:1/8
K:C
(GA) c2 z4 |]")

(deftest slur-open-glues-onto-the-note-it-precedes-in-source-order
  ;; The real ordering trap: ABC's '(' precedes the note it opens on in
  ;; the SOURCE stream, but musics-DSL's own SlurMark is a trailing
  ;; suffix on that same note's text -- confirmed live this doesn't just
  ;; silently glue onto whatever was emitted last (which would put '('
  ;; nowhere, since nothing precedes it here) or throw.
  (let [mus (abc/abc-text->mus-text slur-tune)]
    (is (parses? mus))
    (is (str/includes? mus "G38(") "opening slur mark trails G's own duration digit")
    (is (str/includes? mus "A38)") "closing slur mark trails A's own duration digit")))

;; ============================================================
;; ABC's own example files under abc/ -- the ones a real user would
;; actually load, not just inline test strings
;; ============================================================

(deftest example-abc-files-all-convert-and-parse
  (doseq [f ["abc/01_simple_reel.abc" "abc/02_key_and_accidentals.abc"
             "abc/03_chords_ties_tuplet.abc" "abc/04_tunebook.abc"]]
    (testing f
      (is (parses? (abc/abc-text->mus-text (slurp f)))))))
