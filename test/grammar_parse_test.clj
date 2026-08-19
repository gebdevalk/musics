(ns ^:parsing grammar-parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [input.grammar-parser :as gp]
            [instaparse.core :as insta]))

(defn- fixture
  "Load a DSL fixture from test/resources/musics -- keeps nested-quote-heavy
   input (embedded StringLits) out of Clojure string literals."
  [name]
  (slurp (str "test/resources/musics/" name)))

;; Bare Leaf/Reference/VarRef content is wrapped in { } throughout this
;; whole file from here on -- none of Leaf/Reference/VarRef (the other
;; three Part alternatives, alongside Composite) is a valid TopElement
;; any more (see musics.ebnf's own TopElement comment: all three can
;; write directly into whatever context is on top of the builder stack,
;; which is :ROOT itself at Program's own bare top level -- :ROOT is
;; meant to be a read-only, guaranteed-value endpoint).
(deftest note-parses-not-bareword
  (testing "Note c4 parses as Note, not BareWord"
    (let [result (gp/parse-string "{c4}")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (str/includes? tree-str ":Note")
            (str "Expected :Note in tree, got: " tree-str))
        (is (not (str/includes? tree-str ":BareWord"))
            (str "Did not expect :BareWord in tree, got: " tree-str)))))

  (testing "Rest r4 parses as Rest"
    (let [result (gp/parse-string "{r4}")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (str/includes? tree-str ":Rest")
            (str "Expected :Rest in tree, got: " tree-str)))))

  (testing "Chord <c e g>2 parses as Chord"
    (let [result (gp/parse-string "{<c e g>2}")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (str/includes? tree-str ":Chord")
            (str "Expected :Chord in tree, got: " tree-str))))))

(deftest drum-parses
  (testing "Drum x8\\kick parses as Drum"
    (let [result (gp/parse-string "{x8\\kick}")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (str/includes? tree-str ":Drum")
            (str "Expected :Drum in tree, got: " tree-str))))))

;; Bracket scheme: { } Sequence, << >> Parallel, '{ } Unit, [ ] Data,
;; @[ ] AtomicAlgo, @{ } ElementAlgo, ^{ } Context (Scope removed -- times/tuplet/transpose/VarDef reuse { } directly now)
;; (\times/\tuplet/\transpose's body, a VarDef's value -- never itself a
;; registered container, always spliced/stashed into something else).
(deftest composites-parse
  (testing "Sequence"
    (is (not (insta/failure? (gp/parse-string "{c4 d4 e4}")))))
  (testing "Named sequence (Id with trailing colon)"
    (is (not (insta/failure? (gp/parse-string "{verse: c4 d4}")))))
  (testing "Parallel"
    (is (not (insta/failure? (gp/parse-string "<<{c4 d4} {e4 f4}>>")))))
  (testing "Parallel rejects bare notes"
    (is (insta/failure? (gp/parse-string "<<c4 e4 g4>>"))))
  (testing "Unit inside a Sequence"
    (is (not (insta/failure? (gp/parse-string "{'{c4 d4} e4}")))))
  (testing "Named unit (Id with trailing colon)"
    (is (not (insta/failure? (gp/parse-string "{'{grp: c4 d4} e4}")))))
  (testing "Unit rejected inside a Parallel -- no sequential order to preserve there"
    (is (insta/failure? (gp/parse-string "<<'{c4 d4}'{e4 f4}>>"))))
  (testing "Data"
    (is (not (insta/failure? (gp/parse-string "[c 4 3/2]")))))
  (testing "AtomicAlgo"
    (is (not (insta/failure? (gp/parse-string "@[algo [c 4 2.. c#'] [1 2.3 3/4]]")))))
  (testing "ElementAlgo"
    (is (not (insta/failure? (gp/parse-string "@{algo {c4 d2..} {c#' r4}}"))))))


(deftest instructions-parse
  ;; Wrapped in { } throughout -- a bare Instruction is no longer valid
  ;; directly at Program's own top level (see musics.ebnf's own
  ;; TopElement comment): it would write straight into :ROOT's context,
  ;; which is meant to be a read-only, guaranteed-value endpoint.
  (testing "Bang constant"
    (is (not (insta/failure? (gp/parse-string "{!mf}")))))
  (testing "Assignment int"
    (is (not (insta/failure? (gp/parse-string "{!art:80}")))))
  (testing "Assignment keyword"
    (is (not (insta/failure? (gp/parse-string "{!vol:mf}")))))
  (testing "Key assignment"
    (is (not (insta/failure? (gp/parse-string "{!key:C.major}")))))
  (testing "Ramp up"
    (is (not (insta/failure? (gp/parse-string "{!vol<}")))))
  (testing "Ramp smooth down"
    (is (not (insta/failure? (gp/parse-string "{!vol>s}"))))))

;; ── Failure helpers ──────────────────────────────────────────

(defn- get-failure [input]
  (insta/get-failure (gp/parse-string input)))

(defn- expects?
  "True if any reason entry expects the given string (works for
   string literals and regex patterns via str)."
  [failure s]
  (some #(= s (str (:expecting %))) (:reason failure)))

;; ── Failure tests ───────────────────────────────────────────

(deftest parse-failures
  ;; Notes
  (testing "Bare accidental without pitch"
    (let [f (get-failure "#4")]
      (is (= 1 (:column f)) "fails at column 1 — # can't start any element")))

  (testing "Double pitch letters without whitespace"
    ;; "cc4" no longer fails right at the second c (column 2) -- VarDef
    ;; is now also a valid Element alternative, and "cc4" greedily
    ;; matches VarName in full (letters + trailing digits are both
    ;; allowed there) before failing to find the '=' a definition needs,
    ;; at the end of the string. Still correctly rejected, just later.
    (let [f (get-failure "cc4")]
      (is (= 4 (:column f)) "fails after all of cc4, looking for =")
      (is (expects? f "=") "expected = (a VarDef attempt), not end-of-string")))

  ;; Chords -- wrapped in { } (Leaf, which Chord is one of, is no longer
  ;; a valid TopElement on its own -- see musics.ebnf's own TopElement
  ;; comment); columns below are all +1 versus the bare/unwrapped text,
  ;; for the leading {.
  (testing "Unclosed chord"
    (let [f (get-failure "{<c e g}")]
      (is (= 8 (:column f)))
      (is (expects? f ">") "expected closing >")))

  (testing "Empty chord"
    (let [f (get-failure "{<>}")]
      (is (= 3 (:column f)))
      ;; Pitch now splits into two letter-case alternatives (see
      ;; musics.ebnf's PitchLetterAbs/PitchLetterRel), so a pitch-letter
      ;; failure reports both charsets separately rather than one
      ;; combined "[A-Ga-gp]".
      (is (or (expects? f "[A-G]") (expects? f "[a-gp]")) "expected pitch letter")))

  ;; Drums
  (testing "Drum with bare word but no backslash"
    ;; Wrapped in { } -- also closes off the VarDef-dead-end detour this
    ;; test used to document (the "cc4" test above still shows it, for
    ;; content that's genuinely at Program's own top level): VarDef is
    ;; only ever reachable there, never as an ordinary Element inside a
    ;; Sequence, so "kick" here is never even attempted as a variable
    ;; name -- it just fails immediately as unexpected content, looking
    ;; for the sequence's own closing }.
    (let [f (get-failure "{x kick}")]
      (is (= 4 (:column f)) "fails right after 'x ', looking for }")
      (is (expects? f "}") "expected closing } -- kick is unexpected content, not a VarDef attempt")))

  ;; Brackets
  (testing "Unclosed sequence"
    (let [f (get-failure "{c4 d4")]
      (is (= 7 (:column f)))
      (is (expects? f "}") "expected closing }")))

  (testing "Unopened sequence"
    ;; A complete, valid {c4 d4} followed by a stray extra } -- wrapping
    ;; the whole thing (c4/d4 are no longer valid bare TopElements
    ;; either) actually sharpens this test versus the old bare form: the
    ;; failure is now genuinely about the unexpected trailing } specifically
    ;; (:end-of-string was expected, nothing else), not blurred together
    ;; with a VarDef-dead-end attempt on c4 itself the way the unwrapped
    ;; text used to be.
    (let [f (get-failure "{c4 d4}}")]
      (is (= 8 (:column f)) "fails right at the stray, trailing }")
      (is (expects? f ":end-of-string") "nothing valid can follow a complete top-level Sequence")))

  (testing "Unclosed parallel"
    (let [f (get-failure "<<{c4 d4}")]
      (is (= 10 (:column f)))
      (is (expects? f ">>") "expected closing >>")))

  (testing "Mismatched brackets"
    (let [f (get-failure "{c4 d4>>")]
      (is (= 7 (:column f)))
      (is (expects? f "}") "expected } not >>")))

  ;; Instructions (compact syntax — no internal whitespace) -- wrapped in
  ;; { }, since a bare Instruction is no longer valid at Program's own
  ;; top level at all (see musics.ebnf's own TopElement comment); columns
  ;; below are all +1 versus the bare/unwrapped text, for the leading {.
  (testing "Bang with space before name"
    (let [f (get-failure "{!  mf}")]
      (is (= 3 (:column f)))
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !")))

  (testing "Bare bang without name"
    (let [f (get-failure "{!}")]
      (is (= 3 (:column f)))
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !")))

  (testing "Assignment without value"
    (let [f (get-failure "{!art:}")]
      (is (= 7 (:column f)) "fails after colon — no value provided"))))

;; ── Multi-line failure tests ────────────────────────────────

(deftest multi-line-errors
  (testing "Invalid token on line 2"
    ;; Wrapped in { } -- bare c4 d4/f4 g4 are no longer valid TopElements
    ;; on their own (see musics.ebnf's own TopElement comment) -- but the
    ;; failure itself is still right at $ on line 2, column 1, unchanged.
    (let [f (get-failure "{c4 d4\n$bad\nf4 g4}")]
      (is (= 2 (:line f)) "error on line 2")
      (is (= 1 (:column f)) "at column 1 — $ can't start any element")))

  (testing "Unclosed sequence spanning lines"
    ;; Already wrapped from the start -- c4/d4/e4/f4 here are ordinary
    ;; Elements inside an (unclosed) Sequence, not bare TopElements, so
    ;; this one was never affected by the TopElement restriction at all.
    (let [f (get-failure "{c4 d4\n e4 f4")]
      (is (= 2 (:line f)) "error at end of line 2")
      (is (= 7 (:column f)))
      (is (expects? f "}") "expected closing }")))

  (testing "Bare bang on line 3"
    ;; Every line wrapped in its own { } now -- bare c4 d4/e4 f4 are no
    ;; longer valid TopElements either, not just the trailing bang (see
    ;; musics.ebnf's own TopElement comment) -- the failure itself is
    ;; still right after the ! on line 3, same column as before.
    (let [f (get-failure "{c4 d4}\n{e4 f4}\n{!}")]
      (is (= 3 (:line f)) "error on line 3")
      (is (= 3 (:column f)) "after the !, +1 versus unwrapped for the leading {")
      (is (expects? f "[a-zA-Z][a-zA-Z0-9_]*") "expected Name after !"))))

;; ── Note pitch variants ─────────────────────────────────────

(deftest note-pitch-variants
  (testing "Sharp accidental"
    (let [result (gp/parse-string "{c#4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))))

  (testing "Flat accidental"
    (let [result (gp/parse-string "{eb4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))))

  (testing "Double sharp"
    (let [result (gp/parse-string "{c##4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))))

  (testing "Double flat"
    (let [result (gp/parse-string "{cbb4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))))

  (testing "Natural"
    (let [result (gp/parse-string "{cn4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))))

  (testing "Dutch sharp (is) and double sharp (isis)"
    (is (not (insta/failure? (gp/parse-string "{cis4}"))))
    (is (not (insta/failure? (gp/parse-string "{cisis4}")))))

  (testing "Dutch flat (es) and double flat (eses)"
    (is (not (insta/failure? (gp/parse-string "{ces4}"))))
    (is (not (insta/failure? (gp/parse-string "{ceses4}")))))

  (testing "Dutch vowel-elided flat (as/es -> s) and double flat (ases/eses -> ses)"
    (is (not (insta/failure? (gp/parse-string "{as4}"))))
    (is (not (insta/failure? (gp/parse-string "{es4}"))))
    (is (not (insta/failure? (gp/parse-string "{ases4}"))))
    (is (not (insta/failure? (gp/parse-string "{eses4}")))))

  (testing "Octave absolute notation -- only reachable after an uppercase (absolute) letter"
    (let [result (gp/parse-string "{C4/4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":OctaveAbs"))))

  (testing "Octave absolute notation, slash omitted when no duration follows"
    (let [result (gp/parse-string "{C4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":OctaveAbs"))))

  (testing "a lowercase letter never takes a digit-based octave -- the digit is always a duration"
    (let [result (gp/parse-string "{c4/4}")]
      (is (insta/failure? result))))

  (testing "Octave ticks up"
    (let [result (gp/parse-string "{c''4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Octave ticks down"
    (let [result (gp/parse-string "{c,,4}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Full pitch: accidental + octave + duration"
    (let [result (gp/parse-string "{f#''8}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Accidental"))
      (is (str/includes? (pr-str result) ":OctaveTicks"))))

  (testing "Note without duration"
    (let [result (gp/parse-string "{c}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Note")))))

;; ── Note duration variants ──────────────────────────────────

(deftest note-duration-variants
  (testing "Dotted duration"
    (let [result (gp/parse-string "{c4.}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":DurationNum"))))

  (testing "Double-dotted duration"
    (let [result (gp/parse-string "{c8..}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":DurationNum"))))

  (testing "Whole note"
    (is (not (insta/failure? (gp/parse-string "{c1}")))))

  (testing "Sixteenth note"
    (is (not (insta/failure? (gp/parse-string "{c16}")))))

  (testing "Longa duration"
    (let [result (gp/parse-string "{c\\longa}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":DurationSpecial"))))

  (testing "Breve duration"
    (let [result (gp/parse-string "{c\\breve}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":DurationSpecial")))))

;; ── Note suffixes ───────────────────────────────────────────

(deftest note-suffixes
  (testing "Staccato shorthand"
    (let [result (gp/parse-string "{c4-.}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Articulation"))))

  (testing "Accent shorthand"
    (let [result (gp/parse-string "{c4->}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Articulation"))))

  (testing "Named articulation staccato"
    (let [result (gp/parse-string "{c4\\staccato}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":ArticulationName"))))

  (testing "Named articulation tenuto"
    (let [result (gp/parse-string "{c4\\tenuto}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":ArticulationName"))))

  (testing "Tie"
    (let [result (gp/parse-string "{c4~}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Tie"))))

  (testing "Modifier"
    (let [result (gp/parse-string "{c4\\vibrato:3}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Modifier"))))

  (testing "Ornament trill"
    (let [result (gp/parse-string "{c4\\trill}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Ornament"))))

  (testing "Ornament mordent"
    (let [result (gp/parse-string "{c4\\mordent}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Ornament"))))

  (testing "Ornament fermata"
    (let [result (gp/parse-string "{c4\\fermata}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Ornament"))))

  (testing "Articulation + ornament + tie combined"
    (let [result (gp/parse-string "{c4-.\\trill~}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Articulation"))
      (is (str/includes? (pr-str result) ":Ornament"))
      (is (str/includes? (pr-str result) ":Tie"))))

  (testing "Dynamic mark glued onto a note"
    (let [result (gp/parse-string "{c4\\f}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Dynamic"))
      (is (str/includes? (pr-str result) ":DynamicMark"))))

  (testing "Dynamic mark glued onto a chord"
    (let [result (gp/parse-string "{<c e g>4\\mf}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Dynamic"))))

  (testing "Hairpin crescendo glued onto a note"
    (let [result (gp/parse-string "{c4\\<}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Hairpin"))))

  (testing "Hairpin decrescendo glued onto a note"
    (let [result (gp/parse-string "{c4\\>}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Hairpin"))))

  (testing "Hairpin chained after a dynamic mark"
    (let [result (gp/parse-string "{c4\\mf\\<}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Dynamic"))
      (is (str/includes? (pr-str result) ":Hairpin")))))

;; ── Commands ────────────────────────────────────────────────

(deftest commands-parse
  ;; transpose/times/tuplet/grace (and its 4 synonyms below) are all
  ;; wrapped in { } -- transient commands splice their children into
  ;; whatever's enclosing them and replay any instruction written inside
  ;; onto that same enclosing context, so none of them can sit bare at
  ;; Program's own top level any more (see musics.ebnf's own TopElement
  ;; comment); repeat/tremolo stay unwrapped below, since those persist
  ;; as real, retained containers and were never affected.
  (testing "Transpose"
    (is (not (insta/failure? (gp/parse-string "{\\transpose c d {c4 d4 e4}}")))))

  (testing "Times"
    (is (not (insta/failure? (gp/parse-string "{\\times 2/3 {c4 d4 e4}}")))))

  (testing "Tuplet"
    (is (not (insta/failure? (gp/parse-string "{\\tuplet 3/2 {c4 d4 e4}}")))))

  (testing "Repeat volta"
    (is (not (insta/failure? (gp/parse-string "\\repeat volta 2 {c4 d4 e4}")))))

  (testing "Repeat unfold"
    (is (not (insta/failure? (gp/parse-string "\\repeat unfold 4 {c4 d4}")))))

  (testing "Repeat with alternative"
    (is (not (insta/failure? (gp/parse-string "\\repeat volta 2 {c4 d4} \\alternative {e4 f4}")))))

  (testing "Tremolo on note"
    (is (not (insta/failure? (gp/parse-string "{c4:32}")))))

  (testing "Tremolo on chord"
    (is (not (insta/failure? (gp/parse-string "{<c e>4:32}")))))

  (testing "Measured tremolo"
    (is (not (insta/failure? (gp/parse-string "\\repeat tremolo 4 {c16 d16}")))))

  (testing "Grace note"
    (is (not (insta/failure? (gp/parse-string "{\\grace c8 d4}")))))

  (testing "Acciaccatura"
    (is (not (insta/failure? (gp/parse-string "{\\acciaccatura c8 d4}")))))

  (testing "Appoggiatura"
    (is (not (insta/failure? (gp/parse-string "{\\appoggiatura c8 d4}")))))

  (testing "Slashed grace"
    (is (not (insta/failure? (gp/parse-string "{\\slashedGrace c8 d4}")))))

  (testing "After grace"
    (is (not (insta/failure? (gp/parse-string "{\\afterGrace c4 d8}"))))))

;; Form navigation (\segno, \coda, \fine, \dacapo, etc.) was removed from
;; the grammar entirely as part of the flat-model rewrite -- there is no
;; FormSign/FormJump rule anymore, so there is nothing left to test here.

;; ── References & slurs ──────────────────────────────────────

(deftest references-and-slurs
  (testing "Reference"
    (let [result (gp/parse-string "{:verse}")]
      (is (not (insta/failure? result)))
      (is (str/includes? (pr-str result) ":Reference"))))

  (testing "Slurs around notes in sequence (note-glued, LilyPond-style --
            !( / !) were removed, see musics.ebnf's own Instruction comment)"
    (is (not (insta/failure? (gp/parse-string "{c4( d4 e4)}"))))))

;; ── Data types in containers ────────────────────────────────

(deftest data-types-in-containers
  (testing "Integers"
    (is (not (insta/failure? (gp/parse-string "[1 2 3]")))))

  (testing "Floats"
    (is (not (insta/failure? (gp/parse-string "[1.5 2.0 3.14]")))))

  (testing "Ratios"
    (is (not (insta/failure? (gp/parse-string "[3/4 7/8]")))))

  (testing "Bare durations (a talea authored as pure data)"
    (is (not (insta/failure? (gp/parse-string "[/4 /8 /8. /16]")))))

  (testing "Strings"
    (is (not (insta/failure? (gp/parse-string (fixture "data-strings.mus"))))))

  (testing "Keywords"
    (is (insta/failure? (gp/parse-string "[:piano :forte]"))))

  (testing "Mixed data types"
    (is (insta/failure? (gp/parse-string (fixture "data-mixed-with-string.mus")))))

  (testing "Empty data"
    (is (not (insta/failure? (gp/parse-string "[]")))))

  (testing "Empty unit"
    (is (insta/failure? (gp/parse-string "'{}"))))

  (testing "Struct value in assignment"
    (is (not (insta/failure? (gp/parse-string "{!env:(1 2 3)}"))))))

;; ── Nested structures ───────────────────────────────────────

(deftest nested-structures
  (testing "Sequences inside parallel"
    (is (not (insta/failure? (gp/parse-string "<<{c4 d4 e4} {f4 g4 a4}>>")))))

  (testing "Nested sequences"
    (is (not (insta/failure? (gp/parse-string "{c4 {d4 e4} f4}")))))

  (testing "Instruction inside sequence"
    (is (not (insta/failure? (gp/parse-string "{!mf c4 d4 e4}")))))

  (testing "Reference inside sequence"
    (is (not (insta/failure? (gp/parse-string "{c4 :bridge d4}"))))))

;; ── Command failures ────────────────────────────────────────

(deftest command-failures
  (testing "Transpose missing second pitch"
    (is (insta/failure? (gp/parse-string "\\transpose c {c4 d4}"))))

  (testing "Tuplet missing ratio"
    (is (insta/failure? (gp/parse-string "\\tuplet {c4 d4 e4}"))))

  (testing "Repeat missing count"
    (is (insta/failure? (gp/parse-string "\\repeat volta {c4 d4}"))))

  (testing "An unrecognized backslash word is grammar-valid now -- a
            VarRef, not a failure. Whether \"bogus\" is actually defined
            is a walk-time question, not a grammar one (see
            command-walk-test/undefined-var-ref-is-a-walk-error).
            Wrapped in { } -- a bare VarRef is no longer a valid
            TopElement on its own either (see musics.ebnf's own
            TopElement comment)."
    (is (not (insta/failure? (gp/parse-string "{\\bogus c4 d4}"))))))

;; ── Comments and variables are grammar-native, not text-level
;;    pre-processing (see musics.ebnf's Comment/VarDef/VarRef and
;;    flat-tree-walker's walk-var-def/walk-var-ref) ─────────────

(deftest comments-are-discarded-by-the-walker-not-stripped-from-text
  (testing "% line comments and %{ ... %} blocks are real, tagged grammar
            nodes now (Comment) -- nothing is ever removed from the text
            before instaparse sees it, so a later parse error's position
            is always relative to what was actually written. The walker
            discards Comment nodes, same as it already discards bare ws
            artifacts, leaving no trace in the domain model."
    (let [{:keys [tree]} (gp/parse-domain-string "{v: c4 % a comment\nd4}")]
      (is (= 2 (count (:children (get tree :v))))))
    (let [{:keys [tree]} (gp/parse-domain-string
                           "{v: c4 %{ a block\ncomment %} d4}")]
      (is (= 2 (count (:children (get tree :v))))))))

(deftest commented-out-pseudo-var-def-is-never-registered
  (testing "A %-commented-out line that LOOKS like a variable definition
            is just inert text to the grammar -- the whole line is
            matched as one Comment token, never considered as a VarDef
            attempt at all (there's no separate text-scanning pass left
            to fool with an unbalanced brace) -- and a real definition
            afterward still works fine."
    (let [text "{v: c4}\n% broken = {oops\nreal = {c4 d4}\n{w: \\real}"
          {:keys [tree]} (gp/parse-domain-string text)]
      (is (= 2 (count (:children (get tree :w))))
          "the real definition's two notes were spliced in"))))

(deftest var-def-only-valid-at-programs-own-top-level
  (testing "A VarDef nested inside a Sequence/Parallel/Unit is a parse
            failure -- reachable only through Program's own top-level
            element list (TopElement), never through Element/ParElement.
            Same restriction LilyPond itself has (defined before the
            music, not inside it)."
    (is (insta/failure? (gp/parse-string "{v: motif = {c4 d4}}"))
        "nested inside a Sequence")
    (is (insta/failure? (gp/parse-string "<<motif = {c4 d4} {a: c4}>>"))
        "nested inside a Parallel")
    (is (insta/failure? (gp/parse-string "'{motif = {c4 d4}}"))
        "nested inside a Unit")
    (is (not (insta/failure? (gp/parse-string "motif = {c4 d4}\n{v: c4}")))
        "directly at the top level still works")))

(deftest var-ref-still-valid-everywhere-unlike-var-def
  (testing "Only VarDef is restricted to the top level -- VarRef
            (referencing an already-defined variable) still works
            nested inside a Sequence, Parallel, or Unit, same as before"
    (is (not (insta/failure?
               (gp/parse-string "motif = {c4 d4}\n{v: \\motif}"))))
    (is (not (insta/failure?
               (gp/parse-string "motif = {c4 d4}\n<<{a: \\motif} {b: e4}>>"))))
    (is (not (insta/failure?
               (gp/parse-string "motif = {c4 d4}\n{v: '{\\motif} e4}"))))))

(deftest nested-typo-no-longer-derailed-by-a-vardef-attempt
  (testing "The regression this restriction actually fixes: before it,
            {verse: cc4 d4} failed at column 13 with a useless 'Expected
            one of: =, comment...' because VarDef being reachable inside
            a Sequence let instaparse's furthest-failure tracking follow
            a dead-end 'maybe this is a variable definition' attempt
            right past the real mistake. Now it fails right at the
            actual double-pitch-letter typo, with relevant reasons."
    (let [f (get-failure "{verse: cc4 d4}")]
      (is (= 10 (:column f)) "right at the second c, not column 13")
      (is (expects? f "}") "a relevant reason -- } validly follows c alone")
      (is (not (expects? f "="))
          "no more dead-end VarDef noise for a typo inside a Sequence"))))

(deftest signed-context-values-accept-both-explicit-signs
  (testing "Value/Target's SignedInt/SignedFloat (musics.ebnf) are the
            only numeric literals with an explicit sign at all -- '-'
            for genuinely negative context values (panning left,
            downward transposition, ...), '+' purely for symmetry when
            an author wants to write it out (e.g. alternating panning:
            !pan:-1.0 / !pan:+1.0). Integer/parseInt and Double/
            parseDouble already handle either sign natively, so this is
            a grammar-only change -- no walker code needed for either."
    (is (not (insta/failure? (gp/parse-string "{!pan:-1.0 c4}"))) "explicit -")
    (is (not (insta/failure? (gp/parse-string "{!pan:+1.0 c4}"))) "explicit +")
    (is (not (insta/failure? (gp/parse-string "{!vol:-5 c4}"))) "SignedInt, -")
    (is (not (insta/failure? (gp/parse-string "{!vol:+5 c4}"))) "SignedInt, +")
    (is (not (insta/failure? (gp/parse-string "{!pan:1.0 c4}"))) "no sign at all still works")))
