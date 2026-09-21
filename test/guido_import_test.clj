(ns ^:parsing guido-import-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [input.guido-import :as gi]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]))

(defn- parses? [mus-text]
  (not (insta/failure? (gp/parse-string mus-text))))

(defn- leaves
  "Every leaf reachable from mus-text's own root, at any depth --
   domain-level verification (:pitches/:duration), not just string-
   content matching -- see abc_import_test.clj's own leaves for the
   identical rationale (a real octave/duration-digit collision bug once
   slipped through undetected past string-only assertions, in this
   exact converter's own note->pitch-text)."
  [mus-text]
  (let [{:keys [tree root-id]} (gp/parse-domain-string mus-text)]
    (letfn [(walk [node]
              (cond
                (d/leaf? node)      [node]
                (d/container? node) (mapcat walk (d/children tree node))
                :else               []))]
      (vec (walk (get tree root-id))))))

;; ============================================================
;; duration->mus -- clean 1/n and dotted matches, *Ratio fallback
;; ============================================================

(deftest duration-matches-plain-and-dotted-values
  (is (= "4" (gi/duration->mus 1/4)))
  (is (= "2" (gi/duration->mus 1/2)))
  (is (= "4." (gi/duration->mus 3/8)) "dotted quarter")
  (is (= "8.." (gi/duration->mus 7/32)) "double-dotted eighth"))

(deftest duration-falls-back-to-a-ratio-suffix-for-an-irregular-ratio
  (is (= "1*5/16" (gi/duration->mus 5/16))))

;; ============================================================
;; Full pipeline: real GUIDO -> musics text -> grammar-parses, with
;; assertions on the ACTUAL converted text, not just "it parses"
;; ============================================================

(deftest basic-notes-rest-and-bars-convert-correctly-and-parse
  ;; Octave/duration both sticky per-sequence: 'a'/'b' inherit g1's own
  ;; octave 1 and the rest's own 1/2 falls back to the sticky duration
  ;; only where elided -- confirms elision resolves against this
  ;; converter's own explicitly-threaded state, not GUIDO's grammar.
  (let [mus (gi/guido-text->mus-text "[ g1/4 a b/8 _/2 | c2/4 ]")]
    (is (parses? mus))
    (is (str/includes? mus "G4/4"))
    (is (str/includes? mus "A4/4") "bare 'a' inherits octave 1 and duration 1/4")
    (is (str/includes? mus "B4/8"))
    (is (str/includes? mus "r2") "GUIDO's own '_' rest -> this DSL's own 'r'")
    (is (str/includes? mus "|"))
    (is (str/includes? mus "C5/4") "GUIDO octave 2 -> this DSL's own octave 5")))

(deftest key-tag-integer-form-resolves-to-the-correct-circle-of-fifths-tonic
  ;; A real bug lived here: an initial wrong reuse of abc_import.clj's
  ;; own sharp-add-order/flat-add-order table gave \\key<-3> as Ab major
  ;; (4 flats) instead of the correct Eb major (3 flats) -- this checks
  ;; the full signed range, not just one spot-checked value.
  (doseq [[n expected] [[0 "!key:C.major"] [1 "!key:G.major"] [3 "!key:A.major"]
                        [7 "!key:C#.major"] [-1 "!key:F.major"] [-3 "!key:Eb.major"]
                        [-7 "!key:Cb.major"]]]
    (let [mus (gi/guido-text->mus-text (str "[ \\key<" n "> c1/4 ]"))]
      (is (parses? mus))
      (is (str/includes? mus expected) (str "\\key<" n ">")))))

(deftest key-tag-tonality-string-form-picks-major-or-minor-by-letter-case
  (let [mus-major (gi/guido-text->mus-text "[ \\key<\"D\"> c1/4 ]")
        mus-minor (gi/guido-text->mus-text "[ \\key<\"d\"> c1/4 ]")]
    (is (parses? mus-major))
    (is (parses? mus-minor))
    (is (str/includes? mus-major "!key:D.major") "uppercase tonic letter -> major")
    (is (str/includes? mus-minor "!key:D.minor") "lowercase tonic letter -> minor")))

(deftest key-tag-tonality-string-form-with-an-accidental
  (let [mus (gi/guido-text->mus-text "[ \\key<\"F#\"> c1/4 ]")]
    (is (parses? mus))
    (is (str/includes? mus "!key:F#.major"))))

(deftest meter-tag-handles-common-and-cut-time-aliases
  (is (str/includes? (gi/guido-text->mus-text "[ \\meter<\"C\"> c1/4 ]") "!Meter:4/4"))
  (is (str/includes? (gi/guido-text->mus-text "[ \\meter<\"C/\"> c1/4 ]") "!Meter:2/2"))
  (is (str/includes? (gi/guido-text->mus-text "[ \\meter<\"7/8\"> c1/4 ]") "!Meter:7/8")))

(deftest tempo-tag-extracts-an-embedded-bpm-marker
  (let [mus (gi/guido-text->mus-text "[ \\tempo<\"[1/4]=120\"> c1/4 ]")]
    (is (parses? mus))
    (is (str/includes? mus "!tempo:120"))))

(deftest tie-range-tag-glues-the-tie-mark-onto-the-last-note-only
  ;; GUIDO's own tie is a RANGE tag wrapping the notes it spans, unlike
  ;; this DSL's own trailing '~' suffix -- confirms the conversion picks
  ;; the LAST note of the range, not the first, to carry the mark.
  (let [mus (gi/guido-text->mus-text "[ \\tie(c1/4 c) ]")]
    (is (parses? mus))
    (is (str/includes? mus "C4/4 C4/4~") "tie mark trails the SECOND (last) note")))

(deftest slur-range-tag-glues-open-close-marks-onto-first-and-last-notes
  (let [mus (gi/guido-text->mus-text "[ \\slur(g1/8 a) ]")]
    (is (parses? mus))
    (is (str/includes? mus "G4/8(") "opening slur mark trails the FIRST note")
    (is (str/includes? mus "A4/8)") "closing slur mark trails the LAST note")))

(deftest brace-with-comma-separated-bare-notes-converts-to-a-chord
  (let [mus (gi/guido-text->mus-text "[ {c1/4,e1,g1} ]")]
    (is (parses? mus))
    (is (str/includes? mus "<C4/ E4/ G4/>4") "all 3 pitches, single shared duration")))

(deftest brace-with-bracketed-items-converts-to-parallel-voices
  (let [mus (gi/guido-text->mus-text "[ { [c1/4 d1] [e1/4 f1] } ]")]
    (is (parses? mus))
    (is (str/includes? mus "{ [ C4/4 D4/4 ] [ E4/4 F4/4 ] }"))))

(deftest chord-duration-is-taken-from-the-first-tone-even-when-a-later-tone-overrides
  ;; Regression for a real bug found while writing these tests: emit-
  ;; brace's own reduce originally kept overwriting its accumulated
  ;; duration on every tone, so a later tone's own explicit override
  ;; silently became the WHOLE chord's printed duration instead of the
  ;; first tone's, contradicting this converter's own documented
  ;; behavior ('duration taken from the FIRST tone's own', emit-brace's
  ;; docstring) -- confirmed live before the fix: {c1/4,e1/8} printed
  ;; '<C4/ E4/>8', not '<C4/ E4/>4'.
  (let [mus (gi/guido-text->mus-text "[ {c1/4,e1/8} ]")]
    (is (parses? mus))
    (is (str/includes? mus "<C4/ E4/>4")
        "the chord's own printed duration stays the FIRST tone's 1/4, not the second tone's own 1/8 override")))

(deftest comments-are-skipped-entirely
  (let [mus (gi/guido-text->mus-text "[ % a line comment\n c1/4 (* a block\ncomment *) d1/4 ]")]
    (is (parses? mus))
    (is (str/includes? mus "C4/4"))
    (is (str/includes? mus "D4/4"))))

(deftest domain-level-octave-digit-trailing-slash-is-load-bearing
  ;; Same class of bug already found once in abc_import.clj's own
  ;; note->pitch-text: without the trailing '/' after an octave digit,
  ;; an octave immediately followed by another digit (the note's own
  ;; Duration) silently misparses as NO octave (defaulting to octave 4)
  ;; plus a wrong, mashed-together Duration -- no parse error at all.
  ;; String-content assertions alone wouldn't catch a regression here
  ;; (a dropped '/' still produces text that LOOKS plausible), so this
  ;; checks actual :pitches/:duration.
  (let [mus (gi/guido-text->mus-text "[ c3/8 ]")
        [leaf] (leaves mus)]
    (is (= [84] (:pitches leaf)) "GUIDO octave 3 -> this DSL's own octave 6, not the octave-4 default")
    (is (= 1/8 (:duration leaf)) "not 1/38")))

(deftest domain-level-ratio-duration-and-chord-pitches
  (let [mus (gi/guido-text->mus-text "[ c1*1/6 d1*1/6 e1*1/6 | {c1/4,e1,g1} ]")
        ls  (leaves mus)]
    (testing "*n/d duration form: a literal fraction of a whole note, not a
              scale factor on a preceding base digit -- GUIDO's own
              convention, distinct from this DSL's own *Ratio suffix
              (which duration->mus uses on the OUTPUT side instead)"
      (is (every? #(= 1/6 (:duration %)) (take 3 ls))))
    (testing "chord: C4/E4/G4 (GUIDO octave 1 -> this DSL's own octave 4)"
      (is (= [60 64 67] (:pitches (nth ls 3)))))))

;; ============================================================
;; GUIDO's own example files under guido/ -- the ones a real user would
;; actually load, not just inline test strings
;; ============================================================

(deftest example-guido-files-all-convert-and-parse
  (doseq [f ["guido/01_o_sanctissima.gmn" "guido/02_key_and_accidentals.gmn"
             "guido/03_chords_ties_tuplet.gmn" "guido/04_two_voices.gmn"]]
    (testing f
      (is (parses? (gi/guido-text->mus-text (slurp f)))))))
