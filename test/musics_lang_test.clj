(ns ^:lang musics-lang-test
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
            [clojure.set]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [test-support :refer [with-fresh-session]]
            [musics.lang :as l]
            [musics.lang.runtime :as rt]
            [musics.core :as m]
            [algo.common.scaling :as scaling]
            [algo.common.rotate :as rotate]
            [algo.common.numeric :as numeric]
            [algo.common.trig :as trig]
            [algo.common.isorhythm :as isorhythm]
            [algo.common.zfilter :as zfilter]
            [algo.indisp.indispensability :as indisp]
            [algo.melodic.slonimsky :as slonimsky]
            [algo.metric.metric :as metric]
            [algo.random :as rnd]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.rhythmic.necklace :as necklace]
            [algo.algoline :as algoline]))

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
    (doseq [v vs] (rt/push! ctx v))
    (l/run-string ctx s)
    @(:stack ctx)))

;; core.repo/musics.core's session is a defonce'd singleton shared by
;; every namespace in this one JVM run -- same reset-between-tests
;; discipline musics_test.clj already uses.
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
    (is (rt/quotation? (first stack)))))

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

(deftest question-mark-is-the-ternary-if-picking-values-not-quotations
  (is (= ["less"] (run "3 4 < \"less\" \"not less\" ?"))
      "real Factor's own worked example (kernel-docs.factor's own HELP: ?)")
  (is (= ["not less"] (run "4 3 < \"less\" \"not less\" ?"))))

(deftest dip-and-keep
  (is (= [10 2] (run "1 2 ( 10 * ) dip")) "( x quot -- x ): quot runs with x removed, then x is restored on top")
  (is (= [6 5] (run "5 ( 1 + ) keep")) "( x quot -- x ): quot runs on x, original x pushed back after"))

(deftest bi-and-tri-apply-each-quotation-to-the-same-original-value
  (is (= [6 10] (run "5 ( 1 + ) ( 2 * ) bi")))
  (is (= [6 10 25] (run "5 ( 1 + ) ( 2 * ) ( dup * ) tri"))))

(deftest curry-and-compose-accept-a-word-reference-not-just-a-literal-quotation
  ;; run-callable (call/if/dip/bi/tri/...) already treats a Quotation and
  ;; a Wordref identically -- quot-steps (curry/compose's own shared
  ;; helper) used to be narrower, throwing on a bare \ name. Real
  ;; Factor's own curry/compose accept either.
  (is (= [5 5] (run "5 \\ dup curry call"))
      "curry: obj=5, quot=\\ dup -- curried pushes 5 then runs dup")
  (is (= [5 6] (run "5 \\ dup ( 1 + ) compose call"))
      "compose: quot1=\\ dup (a word reference), quot2=a literal quotation")
  (is (= [6 6] (run "5 ( 1 + ) \\ dup compose call"))
      "compose: quot1=a literal quotation, quot2=\\ dup (a word reference)"))

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
;; throwaway walk
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
  ;; make-ctx's own scratchpad vocab already uses "musics" (play/repo-
  ;; navigation words) alongside "parse"/"algo" -- everything's
  ;; reachable with no explicit USING: needed, matching input.forth's
  ;; own current ergonomics. (parse itself now lives in "parse" -- see
  ;; parse-vocab-words-are-in-scope-by-default below.)
  (let [stack (run "IN: musics words")]
    (is (contains? (set (first stack)) "play")
        "play/repo-navigation words are still in musics -- only parse and the wall bridge moved out")))

(deftest parse-vocab-holds-text-to-repo-words-separately-from-musics
  ;; parse/parse-notation/s!/try-parse/parse-file/>ids live in their OWN
  ;; vocabulary now, not folded into "musics" alongside play/repo-
  ;; navigation words.
  (let [parse-words (set (first (run "IN: parse words")))
        musics-words (set (first (run "IN: musics words")))]
    (is (contains? parse-words "parse"))
    (is (contains? parse-words "parse-notation"))
    (is (contains? parse-words ">ids"))
    (is (not (contains? musics-words "parse"))
        "moved out of musics, not merely duplicated into parse")))

(deftest parse-vocab-words-are-in-scope-by-default
  ;; make-ctx's own scratchpad vocab USEs "parse" too, same as "musics"/
  ;; "algo" -- these stay reachable with no explicit USING:
  ;; needed, matching input.forth's own current ergonomics.
  (let [stack (run "\"[verse: c4 d4]\" parse")]
    (is (= [:verse] (:ids (first stack))))))

(deftest hash-colon-still-reaches-parse-notation-after-the-parse-vocab-split
  ;; #: ... ; expands to a literal "parse-notation" token (see
  ;; tokenize's own header comment) -- it MUST stay reachable from
  ;; scratchpad by bare lookup, not a special-cased dispatch, so moving
  ;; it into "parse" can't silently break this bridge.
  (let [stack (run "#: [verse: c4 d4] ;")]
    (is (= [:verse] (:ids (first stack))))))

(deftest algo-vocab-holds-core-wall-words-separately-from-musics
  ;; core.wall's own bridge (register-factory!/build!/build-algo!/algos/
  ;; assign-algo!/...) lives in its OWN vocabulary now, not folded into
  ;; "musics" alongside play/repo-navigation words.
  (let [algo-words (set (first (run "IN: algo words")))
        musics-words (set (first (run "IN: musics words")))]
    (is (contains? algo-words "build!"))
    (is (contains? algo-words "register-factory!"))
    (is (contains? algo-words "algo-assignments"))
    (is (not (contains? musics-words "build!"))
        "moved out of musics, not merely duplicated into algo")))

(deftest algo-vocab-words-are-in-scope-by-default
  ;; make-ctx's own scratchpad vocab USEs "algo" too, same as
  ;; "musics" -- these stay reachable with no explicit USING: needed.
  (let [stack (run "factories")]
    (is (= [{}] stack) "no factories registered yet in a fresh ctx")))

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
  (is (= ["parse"] (run "\\ parse where"))
      "the musics.core bridge's own parse vocabulary, reachable by default"))

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
    (rt/push! ctx 1)
    (rt/push! ctx 2)
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
  (is (= [[2 3]] (run "[1 2 3] rest")))
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

;; ============================================================
;; CLOSE:/OPEN: -- a closed vocabulary can't be written to (: / :: /
;; FORGET:), but reading it (USE:/QUALIFIED:/a plain lookup) is
;; completely unaffected
;; ============================================================

(deftest every-built-in-vocab-starts-closed
  (is (every? #(= [true] (run (str "\"" % "\" closed?")))
              ["kernel" "musics" "parse" "algo" "algo-common" "algo-indisp"
               "algo-melodic" "algo-metric" "algo-random" "algo-rhythmic"
               "algo-algoline" "algo-toolkit"])
      "every built-in bridge vocab, including algo-common once its own native bootstrap has run"))

(deftest scratchpad-and-a-user-created-vocab-start-open
  (is (= [false] (run "\"scratchpad\" closed?")))
  (is (= [false] (run "IN: my-fresh-vocab : x 1 ; IN: scratchpad \"my-fresh-vocab\" closed?"))))

(deftest defining-into-a-closed-vocab-throws
  (is (thrown-with-msg? Exception #"is closed"
        (run "IN: kernel : dup 99 ;"))
      "would otherwise silently redefine the language's own dup")
  (is (thrown-with-msg? Exception #"is closed"
        (run "IN: musics : play 99 ;"))))

(deftest forgetting-from-a-closed-vocab-throws
  (is (thrown-with-msg? Exception #"is closed"
        (run "IN: musics FORGET: play"))))

(deftest reading-from-a-closed-vocab-is-completely-unaffected
  (is (= [true] (run "IN: fresh-scope USE: algo-indisp \"algo-indisp\" closed?"))
      "closed the whole time")
  (is (= [(indisp/indispensability [2 2])] (run "IN: fresh-scope USE: algo-indisp [2 2] indispensability"))
      "USE:ing a closed vocab still works -- closed only blocks WRITES into it"))

(deftest open-reverses-close-and-writes-succeed-again
  (is (= [1] (run "IN: my-lib : x 1 ; CLOSE: my-lib IN: scratchpad IN: my-lib x")
      ) "closing doesn't remove what's already there")
  (is (thrown-with-msg? Exception #"is closed"
        (run "IN: my-lib : x 1 ; CLOSE: my-lib : y 2 ;")))
  (is (= [2] (run "IN: my-lib : x 1 ; CLOSE: my-lib OPEN: my-lib : y 2 ; y"))
      "OPEN: reverses CLOSE: -- writes succeed again"))

(deftest closed-and-open-have-a-throwing-kernel-stub-same-as-every-other-parsing-word
  ;; CLOSE:/OPEN: are only meaningful as top-level parsing words
  ;; (interpret-token!'s own special-casing) -- reached any other way
  ;; (compiled into a body, called via \ execute) they fall through to
  ;; kernel-vocab's own stub entry, same as IN:/USE:/FORGET:/HELP:/...
  ;; already do.
  (is (thrown-with-msg? Exception #"CLOSE: is a parsing word, only valid at the top level"
        (run "\\ CLOSE: execute")))
  (is (thrown-with-msg? Exception #"OPEN: is a parsing word, only valid at the top level"
        (run "\\ OPEN: execute"))))

;; ============================================================
;; VARIABLE:/@/! -- real Clojure Vars, ported naming from input.forth's
;; own classic-Forth VARIABLE/@/!; CONSTANT: -- real Factor's own
;; syntax, pure sugar over a plain colon word
;; ============================================================

(deftest variable-starts-nil-fetch-and-store-round-trip
  (is (= [nil] (run "VARIABLE: x x @")) "a fresh variable's own Var starts at nil")
  (is (= [42] (run "VARIABLE: x 42 x ! x @")))
  (is (= [7] (run "VARIABLE: x 42 x ! 7 x ! x @")) "! is a permanent overwrite, not additive"))

(deftest variable-word-pushes-the-var-itself-not-its-value
  (is (instance? clojure.lang.Var (first (run "VARIABLE: x x")))))

(deftest at-sign-works-on-any-ideref-not-just-a-variable-made-var
  (is (= [5] (run-with [(atom 5)] "@")) "deref is already generic over any IDeref -- an atom works too"))

(deftest constant-always-pushes-the-same-literal-and-never-consumes-it
  (is (= [99] (run "CONSTANT: bar 99 bar")))
  (is (= [99 99] (run "CONSTANT: bar 99 bar bar")) "calling it twice -- still 99, doesn't consume anything")
  (is (= ["hi"] (run "CONSTANT: greeting \"hi\" greeting")))
  (is (= [[1 2 3]] (run "CONSTANT: nums [1 2 3] nums"))))

(deftest constant-is-early-bound-same-as-any-other-colon-word
  (is (= [1 2] (run "CONSTANT: c 1 : uses-c c ; CONSTANT: c 2 uses-c c"))
      "uses-c compiled against c's OWN value at THAT moment (1); redefining c afterward only affects new callers"))

(deftest variable-and-constant-respect-closed-vocabs
  (is (thrown-with-msg? Exception #"is closed" (run "IN: kernel VARIABLE: x")))
  (is (thrown-with-msg? Exception #"is closed" (run "IN: musics CONSTANT: y 1"))))

(deftest variable-and-constant-have-throwing-kernel-stubs-same-as-other-parsing-words
  (is (thrown-with-msg? Exception #"VARIABLE: is a parsing word, only valid at the top level"
        (run "\\ VARIABLE: execute")))
  (is (thrown-with-msg? Exception #"CONSTANT: is a parsing word, only valid at the top level"
        (run "\\ CONSTANT: execute"))))

;; ============================================================
;; save-vocabs!/load-vocabs! -- a ctx's own user-defined words/vocabs
;; round-trip through a real file as real, re-executable source text
;; ============================================================

(defn- with-temp-file [f]
  (let [tmp (java.io.File/createTempFile "musics-lang-vocabs" ".mlv")]
    (try (f (.getPath tmp))
         (finally (io/delete-file tmp true)))))

(deftest save-vocabs-round-trips-a-user-word-a-container-vocab-and-closed-state
  (with-temp-file
    (fn [path]
      (let [ctx (l/make-ctx)]
        (l/run-string ctx (str "IN: my-helpers "
                                ":: double ( x -- y ) x 2 * ; "
                                "IN: my-container USE: my-helpers USE: algo-indisp CLOSE: my-container "
                                "IN: scratchpad USE: my-container"))
        (l/run-string ctx (str "\"" path "\" save-vocabs!"))
        (let [ctx2 (l/make-ctx)]
          (l/run-string ctx2 (str "\"" path "\" load-vocabs!"))
          (l/run-string ctx2 "21 double")
          (is (= [42] @(:stack ctx2)) "my-helpers' own word survived, reachable via the reloaded USE: chain")
          (l/run-string ctx2 "\"my-container\" closed?")
          (is (= true (last @(:stack ctx2)))
              "CLOSE: state on the user vocab survived too")
          (is (= "scratchpad" @(:current-vocab ctx2))
              "current-vocab restored to what it was at save time"))))))

(deftest save-vocabs-does-not-include-built-in-bridge-words
  (with-temp-file
    (fn [path]
      (let [ctx (l/make-ctx)]
        (l/run-string ctx (str "\"" path "\" save-vocabs!"))
        (let [saved (slurp path)]
          (is (not (str/includes? saved ": dup"))
              "kernel's own dup is a :primitive entry -- nothing to reconstruct, nothing user-defined either")
          (is (not (str/includes? saved "indispensability"))
              "algo-indisp's own bridge words are :primitive too -- not colon-defined, not persisted"))))))

(deftest load-vocabs-into-a-ctx-that-already-has-state-still-works-for-a-still-open-vocab
  (with-temp-file
    (fn [path]
      (let [ctx (l/make-ctx)]
        (l/run-string ctx "IN: lib-a :: greet ( -- n ) 42 ; IN: scratchpad USE: lib-a")
        (l/run-string ctx (str "\"" path "\" save-vocabs!"))
        (l/run-string ctx "IN: lib-a :: farewell ( -- n ) 7 ; IN: scratchpad")
        (l/run-string ctx (str "\"" path "\" load-vocabs!"))
        (l/run-string ctx "greet farewell")
        (is (= [42 7] @(:stack ctx))
            "reloading the earlier snapshot into the SAME still-open ctx just replays greet again -- farewell, defined after the snapshot was taken, is untouched")))))

(deftest save-vocabs-round-trips-a-variables-current-value-and-a-constant
  (with-temp-file
    (fn [path]
      (let [ctx (l/make-ctx)]
        (l/run-string ctx "VARIABLE: hits 3 hits ! CONSTANT: pitch-count 12")
        (l/run-string ctx (str "\"" path "\" save-vocabs!"))
        (let [ctx2 (l/make-ctx)]
          (l/run-string ctx2 (str "\"" path "\" load-vocabs!"))
          (l/run-string ctx2 "hits @ pitch-count")
          (is (= [3 12] @(:stack ctx2))
              "the variable's own CURRENT value (3, not the nil it started at) and the constant both survived"))))))

;; ============================================================
;; The `algo` vocab's own composition words (chain-algo!/retune!)
;; and their supporting introspection primitives (registered/registered?/
;; algo-fn/apply-algo) -- proving these are actually reachable and wired
;; correctly from musics.lang text, not just at the core.wall/musics.core
;; level (see wall_preset_test.clj for the deeper, more exhaustive
;; coverage of the underlying mechanism itself).
;; ============================================================

(deftest chain-algo!-composes-two-musics-lang-defined-algos-in-sequence
  ;; Each stage is a real musics.lang colon word (a wall fn's own shape,
  ;; ( nodes ctx voice -- nodes' )), built via build-algo! -- proving the
  ;; whole path (colon-word -> Wordref -> callable->fn -> build-algo! ->
  ;; chain-algo! -> algo-fn/apply-algo) round-trips through real
  ;; musics.lang text, no Clojure-level shortcut.
  (let [stack (run (str ":: tag-a ( nodes ctx voice -- nodes' ) nodes ( :a 1 assoc ) map ; "
                         ":: tag-b ( nodes ctx voice -- nodes' ) nodes ( :b 2 assoc ) map ; "
                         ":algo-a \\ tag-a build-algo! drop "
                         ":algo-b \\ tag-b build-algo! drop "
                         ":chained [ :algo-a :algo-b ] chain-algo! drop "
                         ":chained algo-fn [ ] false [ { } ] apply-algo"))]
    (is (= [{:a 1 :b 2}] (last stack))
        "tag-a's own output ({:a 1}) fed into tag-b, not run independently")))

;; ============================================================
;; The algo/ -> musics.lang bridge: one vocab per algo/ subdirectory
;; (algo-common/algo-indisp/algo-melodic/algo-metric/algo-random/
;; algo-rhythmic/algo-algoline/algo-toolkit), all USE:'d by scratchpad
;; by default alongside musics/parse/algo. Existence + a sample
;; of real words per vocab here; golden-value regression checks below
;; for the native-rewritten utilities and a representative spread of
;; bridged functions, each checked against calling the real Clojure fn
;; directly with matching args -- not just an eyeball read.
;; ============================================================

(defn- vocab-words [vocab-name]
  (set (first (run (str "IN: " vocab-name " words")))))

(deftest algo-common-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"farey" "gate" "color-talea" "clamp" "rotate" "sawr" "scale-duration" "invert-around"}
                            (vocab-words "algo-common"))))

(deftest algo-indisp-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"indispensability" "tilt-probabilities" "density-grid"} (vocab-words "algo-indisp"))))

(deftest algo-melodic-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"generate" "markov-generate" "infrapolate" "c-major"} (vocab-words "algo-melodic"))))

(deftest algo-metric-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"binary-decomposition-rhythm" "modular-rhythm"} (vocab-words "algo-metric"))))

(deftest algo-random-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"int-range" "choose" "markov" "henon-attractor" "rnd-double"} (vocab-words "algo-random")))
  (is (not (contains? (vocab-words "algo-random") "shuffle"))
      "skipped -- the same algorithm already reachable as plain `shuffle` in `musics`"))

(deftest algo-rhythmic-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"euclidean-rhythm" "rhythm-necklace" "genetic-rhythm" "tala-pattern"} (vocab-words "algo-rhythmic"))))

(deftest algo-algoline-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"algoline" "step" "run" "attach!"} (vocab-words "algo-algoline")))
  (is (not (clojure.set/subset? #{"*attached*" "*controls*" "*step-registry*"} (vocab-words "algo-algoline")))
      "the three raw dynamic-var atoms are skipped -- not callable functions"))

(deftest algo-toolkit-vocab-exists-and-holds-representative-words
  (is (clojure.set/subset? #{"cycle-shuffle" "weighted-pulse-choice" "shuffled-euclidean" "weighted-shuffle-lo"}
                            (vocab-words "algo-toolkit")))
  (is (not (contains? (vocab-words "algo-toolkit") "color-talea"))
      "skipped -- a standalone-port duplicate of algo-common's own"))

(defn- transitively-used-vocabs
  "Every vocab reachable from start, walking :vocab-uses transitively --
   the test-side mirror of musics.lang's own use-vocab-lookup, so this
   file's own collision check covers exactly what a real lookup would
   actually see, not just one hop."
  [ctx start]
  (let [all-uses @(:vocab-uses ctx)]
    (loop [frontier [start] seen #{}]
      (if (empty? frontier)
        (disj seen start)
        (let [v (peek frontier) frontier (pop frontier)]
          (if (contains? seen v)
            (recur frontier seen)
            (recur (into frontier (get all-uses v)) (conj seen v))))))))

(deftest no-word-collides-across-any-two-default-used-vocabs
  ;; scratchpad USEs `algo`, which itself USEs the 8 algo-*
  ;; vocabs -- transitively, that's 11 vocabs total (musics/parse/
  ;; algo + the 8 algo-* ones) scratchpad sees by default.
  ;; Ambiguous "first-used-wins" ordering over a plain Clojure set would
  ;; make bare word resolution non-deterministic if any two of them
  ;; defined the same name. Every genuine collision found while building
  ;; this bridge was resolved by renaming (algo.common.reshape/invert ->
  ;; invert-around, algo.common.transient-ops/times -> scale-duration)
  ;; or skipping (transient-ops/transpose, algo.random/shuffle,
  ;; toolkit's own standalone-port duplicates) rather than left to
  ;; chance.
  (let [ctx (l/make-ctx)
        uses (transitively-used-vocabs ctx "scratchpad")
        per-vocab (into {} (map (fn [v] [v (set (keys (get @(:vocabularies ctx) v)))])) uses)
        total (reduce + (map count (vals per-vocab)))
        unique (count (apply clojure.set/union (vals per-vocab)))]
    (is (= 11 (count uses)) "musics/parse/algo + the 8 algo-* vocabs, transitively")
    (is (= total unique) "every word name across every default-used vocab is unique")))

;; ============================================================
;; USE:/USING: is transitive -- a vocab USEd by a vocab you USE is
;; visible too, any number of hops deep, cycle-safely
;; ============================================================

(deftest a-word-two-use-hops-away-is-reachable-without-a-direct-use
  ;; `scratchpad` USEs `algo`; `algo` USEs `algo-indisp` --
  ;; scratchpad's own :vocab-uses never mentions algo-indisp directly
  ;; (see make-ctx), so this only works if the walk is transitive.
  (is (= [(indisp/indispensability [2 2 3])] (run "[2 2 3] indispensability"))
      "a real algo-indisp word, resolved from scratchpad with no direct USE: edge to it"))

(deftest a-mutual-use-cycle-resolves-instead-of-hanging
  ;; A USEs B, B USEs A -- a naive transitive walk would recurse
  ;; forever; use-vocab-lookup's own visited-set has to catch this.
  (is (= [1] (run (str "IN: cycle-a : only-in-a 1 ; USE: cycle-b "
                        "IN: cycle-b : only-in-b 2 ; USE: cycle-a "
                        "IN: cycle-a only-in-a"))))
  (is (= [2] (run (str "IN: cycle-a : only-in-a 1 ; USE: cycle-b "
                        "IN: cycle-b : only-in-b 2 ; USE: cycle-a "
                        "IN: cycle-a only-in-b")))
      "only-in-a's own vocab USEs cycle-b transitively, reaching only-in-b despite the cycle back to cycle-a"))

(deftest a-vocab-nobody-uses-stays-invisible-from-scratchpad
  (is (thrown? Exception
        (run "IN: some-standalone-vocab : hidden 1 ; IN: scratchpad hidden"))
      "defined in a vocab nothing USEs -- not reachable unqualified from scratchpad, only via IN: itself or QUALIFIED:"))

;; -- native words -- golden-value checks against the real Clojure fns
;; they were translated from --------------------------------------

(deftest native-clamp-matches-the-real-clojure-fn
  (is (= (scaling/clamp 0 10 15) (first (run "0 10 15 clamp"))) "over hi")
  (is (= (scaling/clamp 0 10 -3) (first (run "0 10 -3 clamp"))) "under lo"))

(deftest native-clamp-optional-matches-the-real-clojure-fn
  (is (= (scaling/clamp-optional 5 nil 15) (first (run "5 nil 15 clamp-optional"))) "lo bound only")
  (is (= (scaling/clamp-optional nil 5 3) (first (run "nil 5 3 clamp-optional"))) "hi bound only")
  (is (= (scaling/clamp-optional nil nil 7) (first (run "nil nil 7 clamp-optional"))) "neither bound")
  (is (= (scaling/clamp-optional 0 10 15) (first (run "0 10 15 clamp-optional"))) "both bounds"))

(deftest native-closest-to-matches-the-real-clojure-fn
  (is (= (scaling/closest-to 4.7 4 6) (first (run "4.7 4 6 closest-to")))))

(deftest native-round-to-matches-the-real-clojure-fn
  (is (= (scaling/round-to 4.7 1) (first (run "4.7 1 round-to"))))
  (is (= (scaling/round-to 4.7 2) (first (run "4.7 2 round-to")))))

(deftest native-scale-range-matches-the-real-clojure-fn
  (is (= (scaling/scale-range 5 0 10 50 150) (first (run "5 0 10 50 150 scale-range")))))

(deftest native-rotate-matches-the-real-clojure-fn
  (is (= (rotate/rotate [1 2 3 4] 1) (first (run "[1 2 3 4] 1 rotate"))))
  (is (= (rotate/rotate [1 2 3 4] -1) (first (run "[1 2 3 4] -1 rotate")))))

(deftest native-lcm-and-lcm-multiple-match-the-real-clojure-fns
  (is (= (numeric/lcm 4 6) (first (run "4 6 lcm"))))
  (is (= (numeric/lcm-multiple [2 3 4]) (first (run "[2 3 4] lcm-multiple")))))

(deftest native-trig-waves-match-the-real-clojure-fns
  (doseq [idx [0 2 4 6 8]]
    (is (= (trig/cosr idx 2 10 8) (first (run (str idx " 2 10 8 cosr")))) (str "cosr " idx))
    (is (= (trig/sinr idx 2 10 8) (first (run (str idx " 2 10 8 sinr")))) (str "sinr " idx))
    (is (= (trig/trianglr idx 2 10 8) (first (run (str idx " 2 10 8 trianglr")))) (str "trianglr " idx)))
  (doseq [idx [1 3 5 7]]
    (is (= (trig/squarr idx 2 10 8) (first (run (str idx " 2 10 8 squarr")))) (str "squarr " idx)))
  (doseq [idx [0 2 6 8]]
    (is (= (trig/sawr idx 2 10 8) (first (run (str idx " 2 10 8 sawr")))) (str "sawr " idx)))
  (doseq [idx [0 8]] ;; idx=4 sits on tan's own asymptote-adjacent zero-crossing, skipped same as the source's own docstring warns
    (is (= (trig/tanr idx 2 10 8) (first (run (str idx " 2 10 8 tanr")))) (str "tanr " idx))))

;; -- bridged words -- golden-value checks against the real Clojure
;; fns, spread across the vocabs -------------------------------------

(deftest bridged-color-talea-matches-the-real-clojure-fn
  (is (= (isorhythm/color-talea [60 64 67] [1 1 2] 1) (first (run "[60 64 67] [1 1 2] 1 color-talea")))))

(deftest bridged-z-filter-matches-the-real-clojure-fn
  (is (= (vec (zfilter/z-filter [1] [1 -0.5] [1 2 3 4]))
         (first (run "[1] [1 -0.5] [1 2 3 4] z-filter")))))

(deftest bridged-indispensability-matches-the-real-clojure-fn
  (is (= (indisp/indispensability [2 2 3]) (first (run "[2 2 3] indispensability")))))

(deftest bridged-infrapolate-matches-the-real-clojure-fn
  (is (= (slonimsky/infrapolate [60 62 64] [0]) (first (run "[60 62 64] [0] infrapolate")))))

(deftest bridged-modular-rhythm-matches-the-real-clojure-fn
  (is (= (metric/modular-rhythm 4 1 8 0) (first (run "4 1 8 0 modular-rhythm")))))

(deftest bridged-only-matches-the-real-clojure-fn
  (is (= (rnd/only [1 2 3 4 5] [1 3]) (first (run "[1 2 3 4 5] [1 3] only")))))

(deftest bridged-euclidean-rhythm-matches-the-real-clojure-fn
  (is (= (rhythm/euclidean-rhythm 3 8 :rotation 0) (first (run "3 8 0 euclidean-rhythm")))))

(deftest bridged-rhythm-necklace-matches-the-real-clojure-fn
  (is (= (necklace/rhythm-necklace [1 0 0 1 0 0]) (first (run "[1 0 0 1 0 0] rhythm-necklace")))))

(deftest bridged-algoline-run-matches-the-real-clojure-fn
  ;; step's own f may return a BARE new value (state unchanged) --
  ;; callable->fn already leaves exactly one value on the stack after
  ;; running a wordref, which is exactly that shape, no extra
  ;; state-vector bookkeeping needed on the musics.lang side.
  (let [an-algoline (algoline/algoline (algoline/step (fn [v _s] (inc v))) (algoline/step (fn [v _s] (* v 2))))]
    (is (= (algoline/run an-algoline 5 {})
           (first (run (str ":: bumped ( v s -- v' ) v 1 + ; "
                             ":: doubled ( v s -- v' ) v 2 * ; "
                             "[ ] \\ bumped step conj \\ doubled step conj algoline "
                             "5 { } run")))))))

(deftest retune!-and-registered-round-trip-through-musics-lang-words
  (m/register-factory! :test-stamp (fn [name {:keys [a b]}]
                                       (m/build-algo! name (fn [nodes _ctx _voice]
                                                              (map #(assoc % :stamp [a b]) nodes)))))
  (let [stack (run (str "IN: algo "
                         ":test-algo :test-stamp { :a 1 :b 2 } build! drop "
                         ":test-algo :a 99 retune! drop "
                         ":test-algo registered? "
                         ":test-algo algo-fn [ ] false [ { } ] apply-algo"))]
    (is (= {:factory-name :test-stamp :params {:a 99 :b 2}}
           (select-keys (first stack) [:factory-name :params]))
        "retune! changed only :a, keeping :b and :factory-name as build! left them")
    (is (= [{:stamp [99 2]}] (second stack))
        "the rebuilt algo -- reached fresh via algo-fn -- reflects the retune")))
