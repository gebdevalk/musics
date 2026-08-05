(ns ^:parsing lilypond-import-test
  "Regression coverage for a real bug: peel-suffix/convert-note-chunk used
   to glue each trailing note-suffix onto whatever token had been emitted
   *last*, and to convert Dynamic/Hairpin/SlurMark into a separate `!name`
   Instruction token placed *after* the note. Both together meant a) a tie
   written before another suffix in the LilyPond source (or a suffix
   written after a dynamic) could come out in an order our own grammar
   doesn't accept back, and b) even when it did parse, the converted
   dynamic/hairpin took effect starting at the *next* note instead of the
   note it was glued to in the source -- since a standalone Instruction's
   context point lands wherever the walker's structural clock reads when
   it's walked, not at the onset of whatever note it was textually next
   to. See CLAUDE.md's \"Comments and variables\"-adjacent design notes and
   peel-suffix's own docstring for the full story."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [input.lilypond-import :as li]
            [input.grammar-parser :as gp]
            [core.domain.context :as c]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- parses? [mus-text]
  (not (insta/failure? (gp/parse-string mus-text))))

(defn- root-sequence
  "Parse+walk mus-text (already run through ly-text->mus-text, so it opens
   with the !accidentals:explicit assignment that always emits first) and
   return the top-level Sequence container -- the second top-level token,
   right after that assignment."
  [mus-text]
  (let [{:keys [tree root-id]} (gp/parse-domain-string mus-text)
        root (get tree root-id)
        [_ seq-id] (:children root)]
    (get tree seq-id)))

(def ^:private root-ctx (c/context-root {"Tempo" 120 "volume" 0.8 "timbre" 42}))

;; ── convert-note-chunk: suffixes reassemble correctly ────────

(deftest tie-and-dynamic-order-are-interchangeable
  (testing "d2.\\p~ (dynamic then tie) and d2.~\\p (tie then dynamic) mean the
            same thing in LilyPond -- both must convert to the identical,
            grammar-valid glued Note text, not a separate !p Instruction"
    (is (= ["D3/2.\\p~"] (li/convert-note-chunk "d2.\\p~" false)))
    (is (= ["D3/2.\\p~"] (li/convert-note-chunk "d2.~\\p" false)))))

(deftest suffixes-reassemble-in-grammar-order-regardless-of-source-order
  (testing "Tie always ends up last and Articulation always right after
            Duration, even when LilyPond's source wrote them in a
            different order"
    (is (= ["D3/4-.~"] (li/convert-note-chunk "d4~-." false))
        "tie-then-articulation in source -> articulation-then-tie in output")
    (is (= ["C3/4->~"] (li/convert-note-chunk "c4->~" false))
        "articulation-then-tie in source stays that way")
    (is (= ["C3/4\\trill~"] (li/convert-note-chunk "c4~\\trill" false))
        "tie-then-ornament in source -> ornament-then-tie in output")
    (is (= ["C3/4\\trill~"] (li/convert-note-chunk "c4\\trill~" false))
        "ornament-then-tie in source stays that way")))

(deftest dynamic-and-hairpin-glue-directly-onto-the-note
  (testing "Dynamic/Hairpin suffixes our grammar's DynamicMark/Hairpin
            rules cover glue straight onto the note -- literally the same
            text LilyPond itself uses -- instead of becoming a separate
            !f/!vol< Instruction"
    (is (= ["C3/4-.\\f"] (li/convert-note-chunk "c4\\f-." false)))
    (is (= ["C3/4\\f\\trill"] (li/convert-note-chunk "c4\\f\\trill" false)))
    (is (= ["C3/4\\<"] (li/convert-note-chunk "c4\\<" false)))))

(deftest slur-marks-glue-directly-onto-the-note
  (testing "( / ) glue onto the note as our grammar's own SlurMark suffix
            (identical text to LilyPond's), not a separate !( / !) token"
    (is (= ["C3/4("] (li/convert-note-chunk "c4(" false)))
    (is (= ["E3/4)"] (li/convert-note-chunk "e4)" false)))))

(deftest extended-dynamic-emits-before-the-note-not-after
  (testing "sf/sfz/etc. aren't in our grammar's DynamicMark word list, so
            they still need a standalone !sf Instruction token -- but it
            must be emitted *before* the note text, not after, so its
            context point lands on this note's own onset rather than the
            following note's (see the namespace docstring)"
    (is (= ["!sf" "C3/4"] (li/convert-note-chunk "c4\\sf" false)))
    (is (= ["!sf" "C3/4~"] (li/convert-note-chunk "c4\\sf~" false)))))

(deftest second-articulation-is-dropped-not-misplaced
  (testing "Our grammar's Articulation slot is singular; LilyPond allows
            stacking (c4-.->). Keep the first, silently drop the rest --
            same as any other unrecognized suffix -- rather than emitting
            invalid text"
    (is (= ["C3/4-."] (li/convert-note-chunk "c4-.->" false)))))

;; ── All of the above round-trip through our own grammar ──────

(deftest converted-note-chunks-always-reparse
  (testing "Every note-chunk conversion above produces text our own grammar
            accepts back -- the original failure mode was silent: broken
            text that failed to reparse with no test ever catching it"
    (doseq [ly ["d2.\\p~" "d2.~\\p" "d4~-." "c4->~" "c4~\\trill" "c4\\trill~"
                "c4\\f-." "c4\\f\\trill" "c4\\<" "c4(" "e4)" "c4-.->"
                "c4\\sf" "c4\\sf~" "cis,8.~"]]
      (let [chunk (li/convert-note-chunk ly false)
            mus   (str "{ " (str/join " " chunk) " }")]
        (is (parses? mus) (str ly " -> " (pr-str chunk) " -> " mus))))))

;; ── Full ly-text->mus-text pipeline: onset timing ─────────────

(deftest ly-import-dynamic-lands-on-its-own-note-not-the-next-one
  (testing "d2.\\p~ and d2.~\\p (dynamic/tie in either order) must both
            convert to a piece where the dynamic is already in effect at
            THIS note's own onset (t=0.0), not only from the tied note
            that follows (t=0.75) -- this is the actual timing bug, not
            just a reparse failure: a converted dynamic used to depend on
            the walker's structural clock at the point it was walked,
            which had already advanced past this note by the time a
            trailing !p token was processed"
    (doseq [ly ["{ d2.\\p~ d2. }" "{ d2.~\\p d2. }"]]
      (let [mus   (li/ly-text->mus-text ly)
            seq-c (root-sequence mus)
            ctx   (:context seq-c)]
        (is (= 40 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
            (str ly " -> " (pr-str mus) ": p (40) must already be in effect at t=0.0"))
        (is (= 40 (c/ctx-value-chain [ctx root-ctx] :volume 0.75))
            (str ly " -> " (pr-str mus) ": still p at the tied note"))))))

(deftest ly-import-plain-glued-dynamic-also-lands-on-its-own-note
  (testing "The same onset-timing fix applies with no tie involved at all --
            c4\\f in isolation must set the volume from c4's own onset"
    (let [mus   (li/ly-text->mus-text "{ c4\\f d4 }")
          seq-c (root-sequence mus)
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "f (70) in effect from c4's own onset, not d4's"))))
