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
   change, not an oversight), vocabularies (IN:/USE:/USING:, shadowing,
   the kernel-vocab fallback), and the #: ... ; musics-text bridge
   staging into the real core.repo (not a throwaway walk)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.edn :as edn]
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
;; Vocabularies -- IN:/USE:/USING:, current-vocab-first lookup order,
;; kernel as the always-in-scope fallback
;; ============================================================

(deftest in-use-using-bring-a-vocabularys-words-into-scope
  (is (= [42] (run "IN: mylib : greet 42 ; IN: scratchpad USING: mylib ; greet"))))

(deftest a-word-defined-in-the-current-vocab-shadows-a-used-one
  (is (= [2] (run (str "IN: a : x 1 ; "
                        "IN: c USING: a ; : x 2 ; " ;; c's own x shadows a's
                        "x")))
      "a name defined directly in the CURRENT vocab always wins over the
       same name reached via USING:, unambiguous regardless of use-set
       iteration order")
  (is (= [2] (run "IN: b2 : x 2 ; IN: c2 USING: b2 ; x"))
      "an unshadowed name reached via USING: resolves normally"))

(deftest kernel-words-stay-reachable-from-any-vocabulary
  (is (= [7] (run "IN: fresh-vocab 3 4 +"))
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
  ;; play/etc. are reachable with no explicit USING: needed, matching
  ;; input.forth's own current ergonomics.
  (let [stack (run "\"[verse: c4 d4]\" parse")]
    (is (= [:verse] (:ids (first stack))))))

;; ============================================================
;; Literal Clojure data -- vectors/maps/sets read directly via
;; clojure.edn, real values, not built via constructor words
;; ============================================================

(deftest vector-map-set-literals-read-as-real-clojure-values
  (is (= [[1 2 3]] (run "[1 2 3]")))
  (is (= [{:a 1 :b 2}] (run "{:a 1 :b 2}")))
  (is (= [#{1 2 3}] (run "#{1 2 3}")))
  (is (= [[1 [2 3] {:a #{4 5}}]] (run "[1 [2 3] {:a #{4 5}}]"))
      "nesting needs no special handling -- one balanced-bracket scan,
       then edn/read-string parses everything inside it in one shot"))

(deftest literal-values-work-directly-with-sequence-combinators
  (is (= [[2 4 6]] (run "[1 2 3] ( 2 * ) map"))))

(deftest literal-values-print-in-their-own-native-clojure-syntax
  (is (= "[1 2 3]" (printed "[1 2 3] .")))
  (is (= #{1 2 3} (edn/read-string (printed "#{1 2 3} .")))
      "set element order isn't guaranteed -- checking the printed text
       reads back to the same set, not an exact string")
  (is (= "{:a 1}" (printed "{:a 1} ."))))

;; ============================================================
;; Mode flags -- :parsing?/:compiling? are real interpreter state, not
;; just a naming convention; interpreting is the derived state when
;; both are false, not a stored third flag
;; ============================================================

(deftest interpreting-is-the-default-derived-state
  (is (= [true] (run "interpreting?")))
  (is (= [false] (run "compiling?")))
  (is (= [false] (run "parsing?"))))

(deftest flags-reset-to-interpreting-after-compiling-or-parsing
  (is (= [false] (run ": foo 1 2 + ; compiling?"))
      "compiling? is genuinely true only DURING compile-definition!'s
       own body-compile -- back to false the instant it returns")
  (is (= [{:ids [:verse]} false] (run "#: [verse: c4 d4] ; parsing?"))
      "the #: ... ; result stays on the stack (parsing? doesn't consume
       it) -- parsing? itself is genuinely true only DURING parse-
       notation's own call into the real musics grammar, false again by
       the time this next word runs"))

;; ============================================================
;; Vocabularies, fully implemented: FROM:/EXCLUDE:/RENAME:/QUALIFIED:/
;; QUALIFIED-WITH:/FORGET:, verified against real Factor's own
;; core/syntax/syntax-docs.factor for exact syntax and precedence
;; ============================================================

(deftest from-colon-takes-precedence-over-an-ambiguous-using
  (is (= [2]
         (run (str "IN: liba : search 1 ; "
                    "IN: libb : search 2 ; "
                    "IN: user USING: liba libb ; "
                    "FROM: libb => search ; "
                    "search")))
      "both liba and libb define search -- FROM: explicitly resolves
       the ambiguity in libb's own favor, confirmed real Factor
       behavior (core/syntax/syntax-docs.factor's own FROM: example)"))

(deftest exclude-colon-imports-everything-but-the-named-words
  (is (= [3] (run (str "IN: mathish : bin> 1 ; : hex> 2 ; : plain 3 ; "
                        "IN: user2 EXCLUDE: mathish => bin> hex> ; "
                        "plain"))))
  (is (thrown? Exception
               (run (str "IN: mathish2 : bin> 1 ; "
                          "IN: user3 EXCLUDE: mathish2 => bin> ; "
                          "bin>")))
      "the excluded word itself stays unreachable"))

(deftest rename-colon-imports-one-word-under-a-new-name
  (is (= [42] (run (str "IN: mathlib : + 42 ; " ;; shadow + locally
                          "IN: user4 RENAME: + mathlib => weird-plus "
                          "weird-plus")))))

(deftest qualified-colon-and-qualified-with-give-prefix-colon-word-access
  (is (= [99] (run (str "IN: geom : area 99 ; "
                          "IN: user5 QUALIFIED: geom "
                          "geom:area"))))
  (is (= [77] (run (str "IN: geom2 : area 77 ; "
                          "IN: user6 QUALIFIED-WITH: geom2 g "
                          "g:area")))))

(deftest forget-colon-removes-a-word-from-the-current-vocab
  (is (thrown? Exception (run "IN: scratch2 : temp 5 ; FORGET: temp temp"))))

(deftest vocab-introspection-words
  (is (= [["a" "b"]] (run "IN: myvoc : a 1 ; : b 2 ; words")))
  (is (= ["myvoc2"] (run "IN: myvoc2 vocab")))
  (is (contains? (set (first (run "vocabs"))) "kernel")
      "kernel is always a real, listed vocabulary"))

;; ============================================================
;; Code inspection: see/where/stack-effect -- real Factor's own words,
;; verified against a local real-Factor source checkout (basis/see/
;; see-docs.factor, core/definitions/definitions-docs.factor, core/
;; effects/effects-docs.factor). All three take a \ word reference.
;; ============================================================

(deftest see-reconstructs-a-plain-colon-words-real-source
  (is (= ": square ( x -- y ) dup * ;"
         (printed (str ": square ( x -- y ) dup * ; " "\\ square see")))))

(deftest see-reconstructs-a-double-colon-words-real-source
  (is (= ":: quad ( a b c -- x y ) a b c * + a b - ;"
         (printed (str ":: quad ( a b c -- x y ) a b c * + a b - ; "
                        "\\ quad see")))))

(deftest see-handles-a-word-with-no-declared-effect
  (is (= ": bare dup * ;" (printed (str ": bare dup * ; " "\\ bare see")))))

(deftest see-on-a-primitive-uses-the-honest-primitive-syntax
  (is (= "dup -- duplicates the top of the stack\nPRIMITIVE: dup ( x -- x x )"
         (printed "\\ dup see"))
      "not a fake : body -- primitives have no musics-lang source to
       show, just the honest PRIMITIVE: form, with its own now-real doc
       string leading it"))

(deftest stack-effect-reads-back-a-words-declared-effect-or-false
  (is (= ["( x -- y )"] (run (str ": square ( x -- y ) dup * ; " "\\ square stack-effect"))))
  (is (= ["( x -- x x )"] (run "\\ dup stack-effect")))
  (is (= [false] (run (str ": bare dup * ; " "\\ bare stack-effect")))
      "no declared effect -- false, real Factor's own f-for-unknown convention"))

(deftest where-reports-the-defining-vocabulary
  (is (= ["mylib3"] (run (str "IN: mylib3 : foo 1 ; "
                                "IN: scratchpad USING: mylib3 ; "
                                "\\ foo where"))))
  (is (= ["kernel"] (run "\\ dup where")))
  (is (= ["musics"] (run "\\ parse where"))
      "the musics.core bridge vocabulary, reachable by default"))

;; ============================================================
;; HELP:/word-doc -- concise, one-line documentation for a word, and
;; every primitive (both kernel and the musics.core bridge) now has one
;; ============================================================

(deftest help-colon-attaches-a-doc-string-a-user-defined-word-can-read-back
  (is (= ["squares a number"]
         (run (str ": square ( x -- y ) dup * ; "
                    "HELP: square \"squares a number\" "
                    "\\ square word-doc")))))

(deftest word-doc-is-false-when-no-help-was-ever-given
  (is (= [false] (run (str ": bare 1 ; " "\\ bare word-doc")))))

(deftest help-colon-can-amend-a-primitives-own-doc-too
  (is (= ["my own override"]
         (run (str "HELP: dup \"my own override\" " "\\ dup word-doc")))))

(deftest help-colon-on-an-unknown-word-throws
  (is (thrown? Exception (run "HELP: nonexistent \"x\""))))

(deftest see-shows-the-doc-line-above-the-reconstructed-definition
  (is (= "square -- squares a number\n: square ( x -- y ) dup * ;"
         (printed (str ": square ( x -- y ) dup * ; "
                        "HELP: square \"squares a number\" "
                        "\\ square see")))))

(deftest every-primitive-in-both-real-vocabularies-has-a-doc-string
  (let [ctx (l/make-ctx)
        vocabs @(:vocabularies ctx)]
    (doseq [[vname vmap] (select-keys vocabs ["kernel" "musics"])]
      (testing vname
        (doseq [[wname entry] vmap]
          (is (some? (:doc entry)) (str wname " has no :doc")))))))

;; ============================================================
;; The REPL prompt -- vocab<stack-depth>, real Factor's own listener
;; prompt shape, recomputed fresh every line
;; ============================================================

(deftest prompt-text-shows-the-current-vocab-and-live-stack-depth
  (let [ctx (l/make-ctx)]
    (is (= "scratchpad<0>" (l/prompt-text ctx)))
    (l/push! ctx 1)
    (l/push! ctx 2)
    (is (= "scratchpad<2>" (l/prompt-text ctx)))
    (l/run-string ctx "IN: mylib7")
    (is (= "mylib7<2>" (l/prompt-text ctx)))))

;; ============================================================
;; Numbers: mod/rem/floor/neg/abs/gcd behave exactly as Clojure's own
;; built-ins do -- a deliberate departure from real Factor's own
;; (opposite-sign) mod convention
;; ============================================================

(deftest mod-and-rem-use-clojures-own-sign-conventions
  (is (= [1] (run "-7 2 mod")) "Clojure's own mod: sign of the DIVISOR")
  (is (= [-1] (run "-7 2 rem")) "Clojure's own rem: sign of the DIVIDEND"))

(deftest slash-mod-gives-a-mutually-consistent-quotient-and-remainder
  (is (= [-3 -1] (run "-7 2 /mod"))
      "quot/rem pair -- q*y + r = x always, -3*2 + -1 = -7"))

(deftest neg-abs-gcd-floor
  (is (= [5] (run "-5 neg")))
  (is (= [5] (run "-5 abs")))
  (is (= [6] (run "54 24 gcd")))
  (is (= [3] (run "7 2 / floor"))))

;; ============================================================
;; Literal keyword syntax, and the new sequence/assoc accessors
;; ============================================================

(deftest bare-colon-words-read-as-real-keyword-literals
  (is (= [:a] (run ":a")))
  (is (= [:some-thing] (run ":some-thing")))
  (is (= [1] (run "{ :a 1 } :a get")) "a keyword works as a real map key"))

(deftest double-colon-and-bind-local-are-not-mistaken-for-keywords
  (is (= [14 -1] (run ":: quad ( a b c -- x y ) a b c * + a b - ; 2 3 4 quad"))
      ":: still compiles a real definition, not a stray :: keyword")
  (is (= [40] (run ":: add-doubled ( a b -- c ) a 2 * :> a2 a2 b + ; 10 20 add-doubled"))
      ":> still binds a local, not a stray :> keyword"))

(deftest sequence-and-assoc-accessors
  (is (= [20] (run-with [[10 20 30]] "1 nth")))
  (is (= [1] (run "{:a 1} :a get")))
  (is (= [{:a 1 :b 2}] (run "{:a 1} :b 2 assoc")))
  (is (= [[1 2 3]] (run "[1 2] 3 conj")))
  (is (= [1] (run "[1 2 3] first")))
  (is (= [3] (run "[1 2 3] count"))))

;; ============================================================
;; Vocabulary parsing words are UPPERCASE ONLY -- real Factor's own
;; convention (ordinary words lowercase, parsing words UPPERCASE-with-
;; colon), a deliberate departure from this kernel's earlier
;; all-lowercase-everything choice for exactly this one word family
;; ============================================================

(deftest vocabulary-words-are-recognized-only-in-uppercase
  (is (= [42] (run "IN: libX : greet 42 ; IN: scratchpad USE: libX greet")))
  (is (thrown? Exception (run "in: libY")) "lowercase in: is just an unknown word now"))
