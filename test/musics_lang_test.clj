(ns ^:forth musics-lang-test
  "Coverage for musics.lang -- the Factor-style kernel replacing
   input.forth's own language core (see musics.lang's own ns docstring
   for the full design). Covers: quotations as values (not auto-run),
   the combinator model (if/when/unless/bi/tri/dip/keep/each/map/
   filter/reduce -- no compiled branch ops), :/:: definitions (including
   that a plain : word's ( ... ) does NOT bind anything -- a real
   negative case, not just the happy path, verified directly against
   real Factor's own locals-vocabulary docs), early binding (redefining
   a word does not retroactively change an already-compiled caller --
   the opposite of input.forth's own dictionary, a deliberate semantic
   change, not an oversight), vocabularies (in:/use:/using:, shadowing,
   the kernel-vocab fallback), and the #: ... ; musics-text bridge
   staging into the real core.repo (not a throwaway walk)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [test-support :refer [with-fresh-session]]
            [musics.lang :as l]
            [musics.core :as m]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- run
  "Run s against a fresh ctx, return the final stack (bottom..top)."
  [s]
  (let [ctx (l/make-ctx)]
    (l/run-string ctx s)
    @(:stack ctx)))

(defn- run-with
  "Run s against a fresh ctx that already has vs pushed (bottom..top,
   left to right) -- for combinators that need a real Clojure seq on the
   stack (each/map/filter/reduce), since literal vector syntax is an
   explicit non-goal of this first pass (see musics.lang's own ns
   docstring)."
  [vs s]
  (let [ctx (l/make-ctx)]
    (doseq [v vs] (l/push! ctx v))
    (l/run-string ctx s)
    @(:stack ctx)))

;; core.repo/musics.core's session is a defonce'd singleton shared by
;; every namespace in this one JVM run -- same reset-between-tests
;; discipline forth_test.clj/musics_test.clj already use.
(defn- reset-musics-fixture [f]
  (with-fresh-session
    (reset! m/session {:auto-ids {} :var-map {}})
    (reset! m/receiver nil)
    (f)))

(use-fixtures :each reset-musics-fixture)

;; ============================================================
;; Core arithmetic / stack words -- native Clojure numeric tower, real
;; true/false (not a -1/0 flag convention)
;; ============================================================

(deftest arithmetic-and-stack-shuffling
  (is (= [7] (run "3 4 +")))
  (is (= [12] (run "3 4 *")))
  (is (= [4] (run "7 3 -")))
  (is (= [1] (run "7 3 mod")))
  (is (= [1/3] (run "1 3 /")) "true exact ratio, Clojure's own numeric tower")
  (is (= [5 5] (run "5 dup")))
  (is (= [5] (run "5 dup drop")))
  (is (= [4 3] (run "3 4 swap")))
  (is (= [3 4 3] (run "3 4 over")))
  (is (= [3 4 3] (run "3 3 4 rot")) "( a b c -- b c a )"))

(deftest comparisons-push-real-booleans
  (is (= [true] (run "3 4 <")))
  (is (= [false] (run "4 3 <")))
  (is (= [true] (run "5 5 =")))
  (is (= [false] (run "5 4 =")))
  (is (= [true] (run "true"))))

;; ============================================================
;; Quotations: real, first-class stack values -- never auto-run
;; ============================================================

(deftest a-quotation-is-pushed-as-a-value-not-executed
  (let [stack (run "( 1 + )")]
    (is (= 1 (count stack)))
    (is (l/quotation? (first stack)))))

(deftest call-runs-a-quotation-against-the-current-stack
  (is (= [6] (run "5 ( 1 + ) call")))
  (is (= [7] (run "3 4 \\ + execute")) "\\ name execute -- wordref, the other callable shape"))

;; ============================================================
;; Display: `.`/`.s` print a value back as its own re-readable source --
;; real Factor's own printing philosophy, not a generic placeholder.
;; ============================================================

(defn- printed [s]
  (let [ctx (l/make-ctx)]
    (str/trim (with-out-str (l/run-string ctx s)))))

(deftest a-quotation-prints-its-own-real-source-not-a-placeholder
  (is (= "( 1 + )" (printed "( 1 + ) .")))
  (is (= "( )" (printed "( ) .")) "an empty quotation")
  (is (= "( 1 2 3 dup + )" (printed "( 1 2 3 dup + ) .")))
  (is (= "( ( 1 + ) call )" (printed "( ( 1 + ) call ) ."))
      "a nested quotation's own ( ) come through verbatim, not re-derived"))

(deftest a-string-prints-quoted-a-wordref-prints-its-own-syntax
  (is (= "\"hello\"" (printed "\"hello\" .")))
  (is (= "\\ dup" (printed "\\ dup ."))))

(deftest dot-s-prints-every-stack-value-in-its-own-re-readable-form
  (is (= "1 \"two\" ( 3 + ) true" (printed "1 \"two\" ( 3 + ) true .s"))))

;; ============================================================
;; Combinators -- ordinary words consuming quotations, no special
;; compile-time branch/loop syntax at all
;; ============================================================

(deftest if-when-unless
  (is (= [1] (run "true ( 1 ) ( 2 ) if")))
  (is (= [2] (run "false ( 1 ) ( 2 ) if")))
  (is (= [1] (run "true ( 1 ) when")))
  (is (= [] (run "false ( 1 ) when")))
  (is (= [1] (run "false ( 1 ) unless")))
  (is (= [] (run "true ( 1 ) unless"))))

(deftest dip-and-keep
  (is (= [10 2] (run "1 2 ( 10 * ) dip")) "( x quot -- x ): quot runs with x removed, then x is restored on top")
  (is (= [6 5] (run "5 ( 1 + ) keep")) "( x quot -- x ): quot runs on x, original x pushed back after"))

(deftest bi-and-tri-apply-each-quotation-to-the-same-original-value
  (is (= [6 10] (run "5 ( 1 + ) ( 2 * ) bi")))
  (is (= [6 10 25] (run "5 ( 1 + ) ( 2 * ) ( dup * ) tri"))))

(deftest sequence-combinators-operate-on-a-real-clojure-seq
  (is (= [[2 4 6 8]] (run-with [[1 2 3 4]] "( 2 * ) map")))
  (is (= [10] (run-with [[1 2 3 4]] "0 ( + ) reduce")))
  (is (= [[3 4]] (run-with [[1 2 3 4]] "( 2 > ) filter")))
  (testing "each -- side-effecting, pushes one value per element in order"
    (is (= [10 20 30] (run-with [[1 2 3]] "( 10 * ) each")))))

;; ============================================================
;; :/:: definitions -- a plain : word's stack-effect comment never
;; binds; :: does, real Factor's own locals-vocabulary convention
;; (verified against a local real-Factor source checkout's own
;; core/locals/locals-docs.factor, not guessed)
;; ============================================================

(deftest plain-colon-stack-effect-is-documentation-only
  (is (= [3] (run ": foo ( a b -- c ) drop ; 3 4 foo"))
      "( a b -- c ) named nothing real -- foo's own body is just `drop`,
       which pops whatever's actually on the stack (4) regardless of
       what the comment calls it, leaving [3]")
  (is (thrown? Exception (run ": bad ( a b -- c ) a b + ;"))
      "a/b are NOT bound under plain : -- referencing them as bare names
       fails to compile (unknown word), same as any other undefined
       name would"))

(deftest double-colon-binds-named-locals-from-the-stack-effect
  (is (= [14 -1] (run ":: quad ( a b c -- x y ) a b c * + a b - ; 2 3 4 quad"))))

(deftest bind-adds-a-new-local-mid-body
  (is (= [40] (run ":: add-doubled ( a b -- c ) a 2 * :> a2 a2 b + ; 10 20 add-doubled"))
      "a2 = a*2 = 20; a2 + b = 20 + 20 = 40"))

(deftest a-quotation-closes-over-its-enclosing-words-own-locals
  (is (= [15] (run ":: adder ( n -- q ) ( n + ) ; 5 10 adder call"))))

;; ============================================================
;; Early binding -- the deliberate semantic change from input.forth's
;; own late-bound dictionary: redefining a word never retroactively
;; changes an already-compiled caller
;; ============================================================

(deftest redefining-a-word-does-not-change-an-already-compiled-caller
  (is (= [11 11 100]
         (run (str ": base 1 ; "
                    ": caller base 10 + ; "
                    "caller "               ;; 1 + 10 = 11
                    ": base 100 ; "         ;; redefine base
                    "caller "               ;; still 11 -- caller's own
                                             ;; compiled step baked in
                                             ;; base's OLD entry
                    "base")))               ;; a bare, freshly-compiled
                                             ;; call to base DOES see 100
      "caller stays 11 both times; only a fresh reference to base itself sees the redefinition"))

(deftest naive-self-recursion-does-not-compile
  ;; Real Factor's own limitation too (recursion needs an explicit
  ;; combinator, not implicit self-name-calling) -- a direct consequence
  ;; of early binding: the name being defined isn't in any vocabulary
  ;; yet while its own body is still being compiled.
  (is (thrown? Exception (run ": rec dup 0 > ( 1 - rec ) ( ) if ;"))))

;; ============================================================
;; Vocabularies -- in:/use:/using:, current-vocab-first lookup order,
;; kernel as the always-in-scope fallback
;; ============================================================

(deftest in-use-using-bring-a-vocabularys-words-into-scope
  (is (= [42] (run "in: mylib : greet 42 ; in: scratchpad using: mylib ; greet"))))

(deftest a-word-defined-in-the-current-vocab-shadows-a-used-one
  (is (= [2] (run (str "in: a : x 1 ; "
                        "in: c using: a ; : x 2 ; " ;; c's own x shadows a's
                        "x")))
      "a name defined directly in the CURRENT vocab always wins over the
       same name reached via using:, unambiguous regardless of use-set
       iteration order")
  (is (= [2] (run "in: b2 : x 2 ; in: c2 using: b2 ; x"))
      "an unshadowed name reached via using: resolves normally"))

(deftest kernel-words-stay-reachable-from-any-vocabulary
  (is (= [7] (run "in: fresh-vocab 3 4 +"))
      "+ is a kernel word -- reachable even from a vocab that uses nothing"))

;; ============================================================
;; The #: ... ; musics-text bridge -- real core.repo staging, not a
;; throwaway walk (mirrors forth_test.clj's own "unified with real
;; staging" coverage for input.forth's bare-bracket recognition)
;; ============================================================

(deftest hash-colon-stages-real-musics-text-into-core-repo
  (let [stack (run "#: [verse: c4 d4] ;")
        {:keys [ids]} (first stack)]
    (is (= [:verse] ids))
    (is (some? (m/find :verse)) "really committed -- findable via musics.core directly")))

(deftest hash-colon-tolerates-nested-brackets-strings-and-comments
  (let [stack (run "#: [verse: !instrument:\"Piano; still one string\" c4 %{ a ; inside a comment %} d4] ;")
        {:keys [ids]} (first stack)]
    (is (= [:verse] ids))))

(deftest musics-vocab-words-are-in-scope-by-default
  ;; make-ctx's own scratchpad vocab already uses: "musics" -- parse/
  ;; play/etc. are reachable with no explicit using: needed, matching
  ;; input.forth's own current ergonomics.
  (let [stack (run "\"[verse: c4 d4]\" parse")]
    (is (= [:verse] (:ids (first stack))))))
