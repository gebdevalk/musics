(ns vars-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [input.reader.parser.vars :as vars]))

(use-fixtures :each (fn [f] (vars/clear-vars!) (f)))

;; ============================================================
;; Single-line definitions (unaffected by the multi-line fix)
;; ============================================================

(deftest single-line-definition
  (testing "verse = c4 d4 e4 -- definition line removed, value registered"
    (let [cleaned (first (vars/extract-vars "verse = c4 d4 e4\n{v: \\verse}"))]
      (is (= "{v: \\verse}" cleaned))
      (is (= "c4 d4 e4" (vars/get-var "verse"))))))

;; ============================================================
;; Multi-line definitions -- every composite bracket the grammar
;; defines, not just a bare [ ] (the bug: count-brackets/the trigger
;; only recognized [ ], so { }/<< >>/( )/'[ ]/@'[ ]/@[ ]/^{ } all
;; silently broke the moment a value spanned more than one line)
;; ============================================================

(deftest multiline-sequence-brackets
  (testing "{ } (Sequence) -- previously the reported bug: only the
            opening brace was captured, and the body leaked out as bare
            top-level text with a dangling unmatched }"
    (vars/extract-vars "motif = {\nc4 d4 e4\n}\n{v: \\motif}")
    (is (= "{\nc4 d4 e4\n}" (vars/get-var "motif")))))

(deftest multiline-bare-brackets-still-work
  (testing "Bare [ ] (the only form that worked before this fix) still
            works, unchanged"
    (vars/extract-vars "motif = [c4\nd4]\n{v: \\motif}")
    (is (= "[c4\nd4]" (vars/get-var "motif")))))

(deftest multiline-parallel-brackets
  (testing "<< >> (Parallel)"
    (vars/extract-vars "parts = <<\n{c4 d4}\n{e4 f4}\n>>\n{v: \\parts}")
    (is (= "<<\n{c4 d4}\n{e4 f4}\n>>" (vars/get-var "parts")))))

(deftest multiline-unit-brackets
  (testing "( ) (Unit)"
    (vars/extract-vars "u = (\nc4 d4\n)\n{v: \\u}")
    (is (= "(\nc4 d4\n)" (vars/get-var "u")))))

(deftest multiline-context-brackets
  (testing "^{ } (Context)"
    (vars/extract-vars "ctx = ^{\nmc: !mf\n}\n{v: \\ctx}")
    (is (= "^{\nmc: !mf\n}" (vars/get-var "ctx")))))

(deftest multiline-data-brackets
  (testing "'[ ] (Data) -- starts with ' not [, so the old first-char-only
            check never recognized it as multi-line at all"
    (vars/extract-vars "dat = '[c\n4 3/2]\n{v: \\dat}")
    (is (= "'[c\n4 3/2]" (vars/get-var "dat")))))

(deftest multiline-atomic-algo-nested-data
  (testing "@'[ ] (AtomicAlgo) containing a nested '[ ] (Data) -- two
            levels of the SAME [ ] character pair, correctly stack-
            tracked so it closes at the second ], not the first"
    (vars/extract-vars "algo = @'[a\n'[1 2]]\n{v: \\algo}")
    (is (= "@'[a\n'[1 2]]" (vars/get-var "algo")))))

(deftest multiline-mixed-nested-brackets
  (testing "A Sequence containing both a slur mark's bare ( ) and a
            nested << >> Parallel -- different bracket characters mixed
            together must still balance correctly"
    (vars/extract-vars "melody = {\nc4( <<d4 e4>> f4)\n}\n{v: \\melody}")
    (is (= "{\nc4( <<d4 e4>> f4)\n}" (vars/get-var "melody")))))

;; ============================================================
;; Mismatched bracket types must not falsely balance
;; ============================================================

(deftest mismatched-bracket-type-does-not-falsely-close
  (testing "A stray ] where a } was expected must not be treated as
            closing the definition early -- a flat net counter (old
            design, generalized naively) would have let +1/-1 cancel out
            across different bracket types; the real per-type stack
            keeps the definition open until its OWN opener's matching
            closer actually appears"
    (let [cleaned (first (vars/extract-vars
                            "bad = {\nc4]\nd4\n}\nreal = c4\n{v: \\real}"))]
      (is (= "{\nc4]\nd4\n}" (vars/get-var "bad"))
          "the whole malformed body was captured as one value, not cut
           short at the stray ]")
      (is (= "c4" (vars/get-var "real"))
          "a later, genuinely single-line definition still parses fine")
      (is (= "{v: \\real}" cleaned)
          "both definitions' lines are gone; only the real content line remains"))))
