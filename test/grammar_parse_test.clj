(ns grammar-parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.grammar-parser :as gp]
            [instaparse.core :as insta]))

(deftest note-parses-not-bareword
  (testing "Note c4 parses as Note, not BareWord"
    (let [result (gp/parse-string "c4")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Note")
            (str "Expected :Note in tree, got: " tree-str))
        (is (not (clojure.string/includes? tree-str ":BareWord"))
            (str "Did not expect :BareWord in tree, got: " tree-str)))))

  (testing "Rest r4 parses as Rest"
    (let [result (gp/parse-string "r4")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Rest")
            (str "Expected :Rest in tree, got: " tree-str)))))

  (testing "Chord <c e g>2 parses as Chord"
    (let [result (gp/parse-string "<c e g>2")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Chord")
            (str "Expected :Chord in tree, got: " tree-str))))))

(deftest drum-parses
  (testing "Drum x8\\kick parses as Drum"
    (let [result (gp/parse-string "x8\\kick")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Drum")
            (str "Expected :Drum in tree, got: " tree-str))))))

;; Bracket scheme: [ ] Sequence, { } Parallel, '[ ] Data,
;; @'[ ] AtomicAlgo, @[ ] ElementAlgo, ^[ ] Context.
(deftest composites-parse
  (testing "Sequence"
    (is (not (insta/failure? (gp/parse-string "[c4 d4 e4]")))))
  (testing "Named sequence (Id with trailing colon)"
    (is (not (insta/failure? (gp/parse-string "[verse: c4 d4]")))))
  (testing "Parallel"
    (is (not (insta/failure? (gp/parse-string "{[c4 d4] [e4 f4]}")))))
  (testing "Parallel rejects bare notes"
    (is (insta/failure? (gp/parse-string "{c4 e4 g4}"))))
  (testing "Data"
    (is (not (insta/failure? (gp/parse-string "'[c 4 3/2]")))))
  (testing "AtomicAlgo"
    (is (not (insta/failure? (gp/parse-string "@'[algo '[c 4 2.. c#'] '[1 2.3 3/4]]")))))
  (testing "ElementAlgo"
    (is (not (insta/failure? (gp/parse-string "@[algo [c4 d2..] [c#' r4]]"))))))


(deftest instructions-parse
  (testing "Bang constant"
    (is (not (insta/failure? (gp/parse-string "!mf")))))
  (testing "Assignment int"
    (is (not (insta/failure? (gp/parse-string "!art:80")))))
  (testing "Assignment keyword"
    (is (not (insta/failure? (gp/parse-string "!vol:mf")))))
  (testing "Key assignment"
    (is (not (insta/failure? (gp/parse-string "!key:C.major")))))
  (testing "Ramp up"
    (is (not (insta/failure? (gp/parse-string "!vol:<")))))
  (testing "Ramp smooth down"
    (is (not (insta/failure? (gp/parse-string "!vol:s>"))))))

;; ── Failure helpers ──────────────────────────────────────────

(defn- get-failure [input]
  (insta/get-failure (gp/parse-string input)))

(defn- expects?
  "True if any reason entry expects the given string (works for
   string literals and regex patterns via str)."
  [failure s]
  (some #(= s (str (:expecting %))) (:reason failure)))

(defn- expects-end? [failure]
  (some #(= :end-of-string (:expecting %)) (:reason failure)))

;; ── Failure tests ───────────────────────────────────────────

(deftest parse-failures
  ;; Notes
  (testing "Bare accidental without pitch"
    (let [f (get-failure "#4")]
      (is (= 1 (:column f)) "fails at column 1 — # can't start any element")))

  (testing "Double pitch letters without whitespace"
    (let [f (get-failure "cc4")]
      (is (= 2 (:column f)) "fails at second c")
      (is (expects-end? f) "expected end-of-string after first note")))

  ;; Chords
  (testing "Unclosed chord"
    (let [f (get-failure "<c e g")]
      (is (= 7 (:column f)))
      (is (expects? f ">") "expected closing >")))

  (testing "Empty chord"
    (let [f (get-failure "<>")]
      (is (= 2 (:column f)))
      (is (expects? f "[A-Ga-gp]") "expected pitch letter")))

  ;; Drums
  (testing "Drum with bare word but no backslash"
    (let [f (get-failure "x kick")]
      (is (= 3 (:column f)) "fails at 'kick' — not a valid element")))

  ;; Brackets
  (testing "Unclosed sequence"
    (let [f (get-failure "[c4 d4")]
      (is (= 7 (:column f)))
      (is (expects? f "]") "expected closing ]")))

  (testing "Unopened sequence"
    (let [f (get-failure "c4 d4]")]
      (is (= 6 (:column f)))
      (is (expects-end? f) "expected end-of-string, not ]")))

  (testing "Unclosed parallel"
    (let [f (get-failure "{[c4 d4]")]
      (is (= 9 (:column f)))
      (is (expects? f "}") "expected closing }")))

  (testing "Mismatched brackets"
    (let [f (get-failure "[c4 d4}")]
      (is (= 7 (:column f)))
      (is (expects? f "]") "expected ] not }")))

  ;; Instructions (compact syntax — no internal whitespace)
  (testing "Bang with space before name"
    (let [f (get-failure "!  mf")]
      (is (= 2 (:column f)))
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !")))

  (testing "Bare bang without name"
    (let [f (get-failure "!")]
      (is (= 2 (:column f)))
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !")))

  (testing "Assignment without value"
    (let [f (get-failure "!art:")]
      (is (= 6 (:column f)) "fails after colon — no value provided"))))

;; ── Multi-line failure tests ────────────────────────────────

(deftest multi-line-errors
  (testing "Invalid token on line 2"
    (let [f (get-failure "c4 d4\n$bad\nf4 g4")]
      (is (= 2 (:line f)) "error on line 2")
      (is (= 1 (:column f)) "at column 1 — $ can't start any element")))

  (testing "Unclosed sequence spanning lines"
    (let [f (get-failure "[c4 d4\n e4 f4")]
      (is (= 2 (:line f)) "error at end of line 2")
      (is (= 7 (:column f)))
      (is (expects? f "]") "expected closing ]")))

  (testing "Bare bang on line 3"
    (let [f (get-failure "c4 d4\ne4 f4\n!")]
      (is (= 3 (:line f)) "error on line 3")
      (is (= 2 (:column f)) "after the !")
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !"))))

;; ── Note pitch variants ─────────────────────────────────────

(deftest note-pitch-variants
  (testing "Sharp accidental"
    (let [result (gp/parse-string "c#4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))))

  (testing "Flat accidental"
    (let [result (gp/parse-string "eb4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))))

  (testing "Double sharp"
    (let [result (gp/parse-string "c##4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))))

  (testing "Double flat"
    (let [result (gp/parse-string "cbb4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))))

  (testing "Natural"
    (let [result (gp/parse-string "cn4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))))

  (testing "Octave absolute notation"
    (let [result (gp/parse-string "c4/4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":OctaveAbs"))))

  (testing "Octave ticks up"
    (let [result (gp/parse-string "c''4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Octave ticks down"
    (let [result (gp/parse-string "c,,4")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Full pitch: accidental + octave + duration"
    (let [result (gp/parse-string "f#''8")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Accidental"))
      (is (clojure.string/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Note without duration"
    (let [result (gp/parse-string "c")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Note")))))

;; ── Note duration variants ──────────────────────────────────

(deftest note-duration-variants
  (testing "Dotted duration"
    (let [result (gp/parse-string "c4.")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":DurationNum"))))

  (testing "Double-dotted duration"
    (let [result (gp/parse-string "c8..")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":DurationNum"))))

  (testing "Whole note"
    (is (not (insta/failure? (gp/parse-string "c1")))))

  (testing "Sixteenth note"
    (is (not (insta/failure? (gp/parse-string "c16")))))

  (testing "Longa duration"
    (let [result (gp/parse-string "c\\longa")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":DurationSpecial"))))

  (testing "Breve duration"
    (let [result (gp/parse-string "c\\breve")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":DurationSpecial")))))

;; ── Note suffixes ───────────────────────────────────────────

(deftest note-suffixes
  (testing "Staccato shorthand"
    (let [result (gp/parse-string "c4-.")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Articulation"))))

  (testing "Accent shorthand"
    (let [result (gp/parse-string "c4->")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Articulation"))))

  (testing "Named articulation staccato"
    (let [result (gp/parse-string "c4\\staccato")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":ArticulationName"))))

  (testing "Named articulation tenuto"
    (let [result (gp/parse-string "c4\\tenuto")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":ArticulationName"))))

  (testing "Tie"
    (let [result (gp/parse-string "c4~")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Tie"))))

  (testing "Modifier"
    (let [result (gp/parse-string "c4\\vibrato:3")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Modifier"))))

  (testing "Ornament trill"
    (let [result (gp/parse-string "c4\\trill")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Ornament"))))

  (testing "Ornament mordent"
    (let [result (gp/parse-string "c4\\mordent")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Ornament"))))

  (testing "Ornament fermata"
    (let [result (gp/parse-string "c4\\fermata")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Ornament"))))

  (testing "Articulation + ornament + tie combined"
    (let [result (gp/parse-string "c4-.\\trill~")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Articulation"))
      (is (clojure.string/includes? (pr-str result) ":Ornament"))
      (is (clojure.string/includes? (pr-str result) ":Tie")))))

;; ── Commands ────────────────────────────────────────────────

(deftest commands-parse
  (testing "Transpose"
    (is (not (insta/failure? (gp/parse-string "\\transpose c d [c4 d4 e4]")))))

  (testing "Times"
    (is (not (insta/failure? (gp/parse-string "\\times 2/3 [c4 d4 e4]")))))

  (testing "Tuplet"
    (is (not (insta/failure? (gp/parse-string "\\tuplet 3/2 [c4 d4 e4]")))))

  (testing "Repeat volta"
    (is (not (insta/failure? (gp/parse-string "\\repeat volta 2 [c4 d4 e4]")))))

  (testing "Repeat unfold"
    (is (not (insta/failure? (gp/parse-string "\\repeat unfold 4 [c4 d4]")))))

  (testing "Repeat with alternative"
    (is (not (insta/failure? (gp/parse-string "\\repeat volta 2 [c4 d4] \\alternative [e4 f4]")))))

  (testing "Tremolo on note"
    (is (not (insta/failure? (gp/parse-string "c4:32")))))

  (testing "Tremolo on chord"
    (is (not (insta/failure? (gp/parse-string "<c e>4:32")))))

  (testing "Measured tremolo"
    (is (not (insta/failure? (gp/parse-string "\\repeat tremolo 4 [c16 d16]")))))

  (testing "Grace note"
    (is (not (insta/failure? (gp/parse-string "\\grace c8")))))

  (testing "Acciaccatura"
    (is (not (insta/failure? (gp/parse-string "\\acciaccatura c8")))))

  (testing "Appoggiatura"
    (is (not (insta/failure? (gp/parse-string "\\appoggiatura c8")))))

  (testing "Slashed grace"
    (is (not (insta/failure? (gp/parse-string "\\slashedGrace c8")))))

  (testing "After grace"
    (is (not (insta/failure? (gp/parse-string "\\afterGrace c4 d8"))))))

;; Form navigation (\segno, \coda, \fine, \dacapo, etc.) was removed from
;; the grammar entirely as part of the flat-model rewrite -- there is no
;; FormSign/FormJump rule anymore, so there is nothing left to test here.

;; ── References & slurs ──────────────────────────────────────

(deftest references-and-slurs
  (testing "Reference"
    (let [result (gp/parse-string ":verse")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":Reference"))))

  (testing "Slur start"
    (let [result (gp/parse-string "!(")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":SlurStart"))))

  (testing "Slur end"
    (let [result (gp/parse-string "!)")]
      (is (not (insta/failure? result)))
      (is (clojure.string/includes? (pr-str result) ":SlurEnd"))))

  (testing "Slurs around notes in sequence"
    (is (not (insta/failure? (gp/parse-string "[!( c4 d4 e4 !)]"))))))

;; ── Data types in containers ────────────────────────────────

(deftest data-types-in-containers
  (testing "Integers"
    (is (not (insta/failure? (gp/parse-string "'[1 2 3]")))))

  (testing "Floats"
    (is (not (insta/failure? (gp/parse-string "'[1.5 2.0 3.14]")))))

  (testing "Ratios"
    (is (not (insta/failure? (gp/parse-string "'[3/4 7/8]")))))

  (testing "Strings"
    (is (not (insta/failure? (gp/parse-string "'[\"hello\" \"world\"]")))))

  (testing "Keywords"
    (is (insta/failure? (gp/parse-string "'[:piano :forte]"))))

  (testing "Mixed data types"
    (is (insta/failure? (gp/parse-string "'[42 3.14 :name \"text\" 3/4]"))))

  (testing "Empty data"
    (is (not (insta/failure? (gp/parse-string "'[]")))))

  (testing "Empty list"
    (is (insta/failure? (gp/parse-string "()"))))

  (testing "Struct value in assignment"
    (is (not (insta/failure? (gp/parse-string "!env:(1 2 3)"))))))

;; ── Nested structures ───────────────────────────────────────

(deftest nested-structures
  (testing "Sequences inside parallel"
    (is (not (insta/failure? (gp/parse-string "{[c4 d4 e4] [f4 g4 a4]}")))))

  (testing "Nested sequences"
    (is (not (insta/failure? (gp/parse-string "[c4 [d4 e4] f4]")))))

  (testing "Instruction inside sequence"
    (is (not (insta/failure? (gp/parse-string "[!mf c4 d4 e4]")))))

  (testing "Reference inside sequence"
    (is (not (insta/failure? (gp/parse-string "[c4 :bridge d4]"))))))

;; ── Command failures ────────────────────────────────────────

(deftest command-failures
  (testing "Transpose missing second pitch"
    (is (insta/failure? (gp/parse-string "\\transpose c [c4 d4]"))))

  (testing "Tuplet missing ratio"
    (is (insta/failure? (gp/parse-string "\\tuplet [c4 d4 e4]"))))

  (testing "Repeat missing count"
    (is (insta/failure? (gp/parse-string "\\repeat volta [c4 d4]"))))

  (testing "Unknown backslash command"
    (is (insta/failure? (gp/parse-string "\\bogus [c4 d4]")))))
