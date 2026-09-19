(ns ^:forth forth-test
  "Coverage for input.forth -- the hosted Forth interpreter -- both its
   own core language (arithmetic, colon definitions, control structures,
   locals, CREATE/DOES>, strings) and its musics.ebnf integration (bare
   [...]/{...}/^{...}/'[...] text, no S\" wrapper needed, and the {
   collision with gforth's own locals-block syntax).
   None of this had any test coverage before -- everything here was
   previously only checked by hand at a REPL."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [test-support :refer [with-fresh-session]]
            [input.forth :as f]
            [musics.core :as m]
            [core.repo :as repo]
            [core.adviser :as adviser]
            [core.async-engine :as engine]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- run
  "Run s against a fresh ctx, return the final stack (bottom..top)."
  [s]
  (let [ctx (f/make-ctx)]
    (f/run-string ctx s)
    @(:stack ctx)))

(defn- run-out
  "Run s against a fresh ctx, return [stack printed-output]."
  [s]
  (let [ctx (f/make-ctx)
        out (with-out-str (f/run-string ctx s))]
    [@(:stack ctx) out]))

;; core.repo/musics.core's session is a defonce'd singleton shared by
;; every namespace in this one JVM run (see musics-test's own identical
;; fixture) -- every word tested below this point drives that exact same
;; real store via musics.core, not a throwaway per-test one, so it needs
;; the same reset-between-tests discipline musics_test.clj already uses,
;; or one test's :verse could collide with another's. The bare-musics-
;; text tests above this point (M., tokenize-*, bare-musics-*, ...) never
;; touch core.repo at all (see the file header docstring and CLAUDE.md's
;; "standalone, one-off walk" note), so they're unaffected either way.
(defn- reset-musics-fixture [f]
  ;; with-fresh-session wraps (f) itself -- the whole test body runs
  ;; inside its binding's dynamic extent, genuinely isolated from
  ;; whatever any OTHER test namespace left in the shared repo/wall/
  ;; conductor/adviser atoms, not just from this file's own previous
  ;; test. m/session and m/receiver are plain musics.core defonce atoms,
  ;; not core.registries ^:dynamic vars, so they still need their own
  ;; explicit reset! here.
  (with-fresh-session
    (reset! m/session {:auto-ids {} :var-map {}})
    (reset! m/receiver nil)
    (f)))

(use-fixtures :each reset-musics-fixture)

(defn- parse-commit!
  "Test helper, mirrors musics_test.clj's own parse! -- run text through
   the real PARSE word (commits immediately) against a scratch ctx,
   discarding the scratch ctx's own stack."
  [text]
  (let [ctx (f/make-ctx)]
    (f/run-string ctx (str "S\" " text "\" PARSE DROP"))))

;; ============================================================
;; Core arithmetic / stack words
;; ============================================================

(deftest arithmetic-and-stack-shuffling
  (is (= [7] (run "3 4 +")))
  (is (= [12] (run "3 4 *")))
  (is (= [4] (run "7 3 -")))
  (is (= [1] (run "7 3 MOD")))
  (is (= [5 5] (run "5 DUP")))
  (is (= [5] (run "5 DUP DROP")))
  (is (= [4 3] (run "3 4 SWAP")))
  (is (= [3 4 3] (run "3 4 OVER")))
  (is (= [3 4 3] (run "3 3 4 ROT")) "( a b c -- b c a )"))

(deftest comparisons-use-forth-truth-values
  (is (= [-1] (run "3 4 <")) "true is -1, not 1")
  (is (= [0] (run "4 3 <")))
  (is (= [-1] (run "4 3 >")))
  (is (= [-1] (run "5 5 =")))
  (is (= [-1] (run "0 0="))))

;; ============================================================
;; Colon definitions
;; ============================================================

(deftest colon-definition-basic
  (is (= [9] (run ": SQUARE DUP * ;\n3 SQUARE"))))

(deftest colon-definition-self-recursion
  (is (= [120] (run ": FACT DUP 1 > IF DUP 1 - FACT * THEN ;\n5 FACT"))))

;; ============================================================
;; Word lookup is case-insensitive
;; ============================================================

(deftest words-are-case-insensitive-at-definition-and-call-sites
  (is (= [8] (run "3 4 dup + swap drop")) "lowercase primitives")
  (is (= [25] (run ": Square dup * ;\n5 SQUARE")) "mixed-case def, uppercase call")
  (is (= [25] (run ": SQUARE DUP * ;\n5 square")) "uppercase def, lowercase call")
  (is (= [1] (run "3 4 < if 1 else 0 then")) "lowercase control-structure keywords")
  (is (= ["hello"] (run "s\" hello\"")) "lowercase S\" opener"))

(deftest locals-names-are-case-insensitive-too
  (is (= [7] (run ": add2 { A b } a b + ;\n3 4 ADD2"))
      "defined with A/b, referenced as a/b -- same bound locals"))

(deftest musics-text-case-is-never-touched-by-word-case-folding
  ;; C4 (absolute) and c4 (relative) are different pitches -- case-
  ;; folding musics text the way Forth words are folded would be a real
  ;; correctness bug, not just a style nit.
  (is (= [[:musics "[verse: C4 c4]"]] (f/tokenize "[verse: C4 c4]")))
  (let [_ (run "[verse: C4 c4]")
        pitches (map (comp first :pitches) (m/children :verse))]
    (is (= [60 60] pitches) "C4 is absolute middle C; the following bare c
                              resolves relative to it -- both land on 60,
                              proving the literal C/c distinction survived")))

;; ============================================================
;; Control structures
;; ============================================================

(deftest if-else-then
  (is (= [1] (run "3 4 < IF 1 ELSE 0 THEN")))
  (is (= [0] (run "4 3 < IF 1 ELSE 0 THEN"))))

(deftest do-loop-sums-via-i
  (is (= [10] (run ": SUM 0 5 0 DO I + LOOP ;\nSUM")) "0+1+2+3+4 = 10"))

(deftest ms-word-pauses-for-real-time
  (let [start (System/currentTimeMillis)]
    (run "60 MS")
    (is (>= (- (System/currentTimeMillis) start) 60))))

(deftest begin-until-counts-down
  (is (= [0] (run "5 BEGIN DUP 0 > WHILE 1 - REPEAT"))))

;; ============================================================
;; Locals -- { a b c } and { a b c -- comment }
;; ============================================================

(deftest locals-bind-from-the-stack-leftmost-deepest
  (is (= [1] (run ": SUB2 { a b } a b - ;\n5 4 SUB2"))
      "leftmost = deepest per the header docstring: a=5 b=4"))

(deftest locals-dash-dash-comment-is-not-bound
  (is (= [7] (run ": ADD2 { a b -- sum } a b + ;\n3 4 ADD2"))
      "only a/b bound; \"sum\" after -- is a comment, never a local"))

;; ============================================================
;; CREATE/DOES>, VARIABLE/@/!
;; ============================================================

(deftest variable-store-and-fetch
  (is (= [42] (run "VARIABLE X 42 X ! X @"))))

(deftest create-does-defines-a-constant
  ;; This CREATE/DOES> is single-cell (each , overwrites the one cell --
  ;; no array/HERE-offset memory model), so the classic CONSTANT idiom is
  ;; what it actually supports: CREATE names a word, , stores into its
  ;; one cell, DOES> installs what that word does when later invoked.
  (is (= [42] (run ": CONSTANT CREATE , DOES> @ ;\n42 CONSTANT FOO\nFOO"))))

;; ============================================================
;; Strings -- S" .../." ...
;; ============================================================

(deftest s-quote-pushes-a-string
  (is (= ["hi"] (run "S\" hi\""))))

(deftest print-quote-prints-without-touching-the-stack
  (let [[stack out] (run-out "42 .\" hello \" .")]
    (is (= [] stack) "42 was consumed by the trailing .")
    (is (= "hello 42 " out))))

;; ============================================================
;; musics text -- bare, no S" wrapper
;; ============================================================

(deftest tokenize-recognizes-every-musics-lead-bracket
  (doseq [text ["[verse: c4 d4]" "{ [a: c4] [b: d4] }"
                "'[c 4 3/2]" "^{ctx: !mf}"]]
    (testing text
      (is (= [[:musics text]] (f/tokenize text))))))

(deftest ordinary-forth-comment-unaffected
  (is (= [] (f/tokenize "( ordinary comment )")))
  (is (= [] (f/tokenize "( times two would still just be a comment )"))
      "( is unambiguously always a Forth comment now, at Forth's own top
       level -- no musics Composite is ( -prefixed anymore (Parallel
       moved to bare { }), so there's no longer even a residual
       ambiguity left to accept as a tradeoff here"))

(deftest nested-command-inside-bare-scope-scans-correctly
  ;; \transpose/\reverse's own Scope shares Forth's own ) character as
  ;; its closer, so a NESTED Command's own ) directly inside one --
  ;; Scope's own grammar allows any Element directly, no wrapping [...]
  ;; required -- must not be mistaken for the OUTER Scope's own closer,
  ;; ending the scan too early. Wrapped in [ ] since a bare \transpose
  ;; isn't itself a recognized musics-openers entry (\ is Forth's own
  ;; line-comment marker at the bare top level, and \transpose is never
  ;; a valid TopElement on its own anyway -- only repeat is)."
  (let [text "[\\transpose c d ( \\reverse ( c4 d4 ) e4 )]"
        tokens (f/tokenize text)]
    (is (= [[:musics text]] tokens)
        "the WHOLE thing is one musics chunk, not truncated after reverse's own close")))


(deftest bare-musics-text-commits-into-the-real-repo-same-as-parse
  ;; Bare [...] calls m/parse directly now (unified with S" ..." PARSE,
  ;; not a separate standalone/session-less walk) -- same {:ids ids}
  ;; shape, commits immediately, same as any other parse.
  (let [[v] (run "[verse: c4 d4 e4]")]
    (is (map? v))
    (is (= [:verse] (:ids v)))
    (is (= 3 (count (m/children :verse)))
        "visible immediately, no separate commit step"))
  (is (nil? (m/find :verse2)) "nothing named :verse2 yet")
  (run "[verse2: c4]")
  (is (some? (m/find :verse2)) "bare musics text really did commit"))

(deftest bare-musics-coexists-with-ordinary-forth-on-one-line
  ;; arithmetic, then a musics chunk pushed and dropped, then more
  ;; arithmetic -- all on one line, mutually unaffected
  (is (= [5 12] (run "2 3 + [verse: c4] DROP 4 3 *"))))

(deftest repeat-works-bare-inside-forth
  (let [_ (run "[ct: \\repeat unfold 3 [c4 d4 e4]]")
        iter (first (m/children :ct))]
    (is (= :REPEAT (:type iter)))
    (is (= 3 (:count (:params iter))))
    (is (= 3 (count (:children (:source iter))))
        "the repeat's own source is the 3-note body, unfolded 3 times")))

(deftest string-literal-inside-musics-text-does-not-confuse-bracket-scanning
  (is (= [[:musics "'[42 \"text with } and ] inside\" 3/4]"]]
         (f/tokenize "'[42 \"text with } and ] inside\" 3/4]"))))

(deftest m-dot-prints-a-pushed-musics-value
  (let [[stack out] (run-out "[verse: c4 d4] M.")]
    (is (= [] stack) "M. pops the value it prints")
    (is (re-find #":verse" out))
    (is (re-find #"2 children" out))))

;; ============================================================
;; { collision -- Forth locals vs musics' own use of { (Parallel)
;; ============================================================

(deftest locals-brace-only-recognized-immediately-after-a-colon-name
  (testing "right after : NAME -- Forth locals, not musics"
    (is (= [":" "SQUARE" "{" "X" "}"] (f/tokenize ": SQUARE { x }"))
        "tokenized as separate words, not swallowed into one :musics chunk
         -- x reads as X since word lookup is case-insensitive"))
  (testing "anywhere else -- musics, not Forth locals"
    (is (= [[:musics "{ctx: !mf}"]] (f/tokenize "{ctx: !mf}")))
    (is (= ["DUP" [:musics "{ctx: !mf}"] "SWAP"]
           (f/tokenize "DUP {ctx: !mf} SWAP")))))

(deftest locals-position-workaround-with-empty-braces
  ;; If musics text is wanted as the very first thing after : NAME, an
  ;; empty locals block frees the position back up -- the documented
  ;; escape hatch for the one reserved spot.
  (is (= [":" "FOO" "{" "}" [:musics "{ctx: !mf}"] ";"]
         (f/tokenize ": FOO { } {ctx: !mf} ;"))))

;; ============================================================
;; musics.core bridge -- every public musics.core fn wired as a Forth word
;; ============================================================
;; See input.forth's own "musics.core bridge" comment block (right above
;; musics-prims) for the full argument-marshaling convention this
;; exercises: ->kw on id/key/phase/action-id args, and PARSE/S!/
;; PARSE-FILE's {:ids ids} result pushed as one map plus the >IDS
;; accessor.

;; ── The primary workflow: parse (commits immediately), inspect real content ──

(deftest parse-commits-leaves-real-pipeline
  (testing "S\" ...\" PARSE commits real text immediately; LEAVES reads
            back the real, committed leaves -- count and pitches both
            checked, not just \"didn't throw\""
    (let [ctx (f/make-ctx)]
      (f/run-string ctx "S\" [verse: !mf c4 d4 e4]\" PARSE DROP")
      (is (some? (m/find :verse)) "PARSE actually made :verse visible")
      (f/run-string ctx "S\" verse\" LEAVES")
      (let [leaves (peek @(:stack ctx))]
        (is (= 3 (count leaves)))
        (is (= [[60] [62] [64]] (mapv :pitches leaves))
            "c4 d4 e4 -> MIDI 60/62/64, in written order")))))

(deftest bare-musics-inside-a-loop-body-re-parses-every-iteration
  ;; Real bug, confirmed directly (2026-08-12): a bare {...} chunk inside
  ;; a compiled body (DO/BEGIN/IF, or a colon-definition) used to be
  ;; baked as a :lit op -- m/parse called ONCE, at compile time, with
  ;; every iteration just re-pushing that same already-committed value.
  ;; `10 0 DO [verse: c4] PLAY! LOOP` called m/parse exactly once despite
  ;; 10 iterations. Fixed via a dedicated :parse-musics op that defers
  ;; the call to run-body's own dispatch, so it reruns -- and re-commits
  ;; -- every time this op is actually reached.
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (adviser/wipe!)
      (run "5 0 DO [loopy: c4] PLAY! LOOP")
      (is (= 5 (count (filter #(= :parse (:action %)) (adviser/recent-activity))))
          "5 loop iterations, 5 real parse/commit calls -- not 1 stale one replayed 5x")
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

(deftest ids-accessor
  (testing ">IDS pulls :ids out of PARSE's {:ids ids} result"
    (let [[ids] (run "S\" [a: c4] [b: d4]\" PARSE >IDS")]
      (is (= [:a :b] ids)))))

;; ── Repo-id group: id argument words ──

(deftest children-leaves-sq-group-reads-real-committed-content
  (parse-commit! "[verse: c4 d4]")
  (is (= 2 (count (first (run "S\" verse\" CHILDREN")))))
  (is (= 2 (count (first (run "S\" verse\" LEAVES")))))
  (let [[sq-result] (run "S\" verse\" SQ")]
    (is (= 2 (count sq-result)))
    (is (= :verse (:id (meta sq-result))) "sq tags its result with the source id")))

(deftest ctx-value-samples-a-real-committed-context
  (parse-commit! "[verse: !mf c4]")
  (let [[v] (run "S\" verse\" S\" volume\" 0.0 CTX-VALUE")]
    (is (number? v) "!mf set a real, readable volume envelope value")))

(deftest describe-returns-data-print-structure-prints-it
  (parse-commit! "[verse: c4 d4]")
  (let [[described] (run "S\" verse\" DESCRIBE")]
    (is (map? described))
    (is (= :verse (:id described)))
    (is (= 2 (:leaf-count described))))
  (let [[stack out] (run-out "S\" verse\" PRINT-STRUCTURE")]
    (is (= [] stack) "PRINT-STRUCTURE prints, doesn't push a value")
    (is (re-find #":verse" out))))

(deftest inspect-and-inspect-all-both-print
  (parse-commit! "[verse: c4 d4]")
  (let [[stack out] (run-out "S\" verse\" INSPECT")]
    (is (= [] stack))
    (is (re-find #"verse" out)))
  (let [[stack out] (run-out "INSPECT-ALL")]
    (is (= [] stack))
    (is (re-find #"node" out) "the session node-count overview, a genuinely
                                different 0-arg form, not just (inspect :ROOT)")))

(deftest locate-navigates-a-real-path
  (testing "LOCATE ( id-str path -- {:part ... :ctx-chain ... :path ...} ) --
            path is a raw selector vector, no Forth literal syntax exists for
            it yet, so it's seeded directly the same way S\" ... already
            bypasses needing a general string-literal builder"
    (parse-commit! "[verse: c4 d4]")
    (let [ctx (f/make-ctx)]
      (f/push! ctx "verse")
      (f/push! ctx [1])
      (f/run-string ctx "LOCATE")
      (is (= [62] (:pitches (:part (peek @(:stack ctx)))))
          "root's 2nd child (index 1) is d4"))))

;; ── Registry words: action registry + schedule table + schedule-tx! ──

(deftest register-action-and-trigger-bridge-a-real-forth-word
  (testing "REGISTER-ACTION!'s token->fn bridge -- ' NAME pushes an
            execution token, REGISTER-ACTION! wraps it into a real
            Clojure fn, TRIGGER! runs it through the SAME interpreter's
            own stack and returns what it left on top"
    (let [ctx (f/make-ctx)]
      (f/run-string ctx ": DOUBLE-IT 2 * ;")
      (f/run-string ctx "S\" forth-test-action\" ' DOUBLE-IT REGISTER-ACTION!")
      (f/run-string ctx "S\" forth-test-action\" 21 TRIGGER!")
      (is (= 42 (peek @(:stack ctx))))
      (f/run-string ctx "S\" forth-test-action\" UNREGISTER-ACTION!")
      (f/run-string ctx "S\" forth-test-action\" 21 TRIGGER!")
      (is (nil? (peek @(:stack ctx))) "unregistered -- trigger! is a no-op now"))))

(deftest schedule-and-scheduled-table
  (let [[action-id] (run "S\" verse\" S\" exit\" S\" my-action\" SCHEDULE! S\" verse\" S\" exit\" SCHEDULED?")]
    (is (= :my-action action-id))
    (run "S\" verse\" S\" exit\" UNSCHEDULE!")
    (is (nil? (m/scheduled :verse :exit)))))

(deftest schedule-tx-bang-registers-a-real-cut-over-action
  (parse-commit! "[verse: c4]")
  (let [[action-id] (run "S\" verse\" S\" exit\" SCHEDULE-TX!")]
    (is (some? action-id) "schedule-tx! returns the generated action-id")
    (is (= action-id (m/scheduled-repeating :verse :exit))
        "schedule-tx! arms the non-consuming repeating table, not the one-shot schedule table")))

;; ── Persistence ──

(deftest write-and-load-round-trip
  (parse-commit! "[verse: c4 d4]")
  (let [path (str (System/getProperty "java.io.tmpdir") "/forth-test-" (gensym) ".edn")]
    (try
      (run (str "S\" " path "\" WRITE"))
      (is (.exists (io/file path)))
      (repo/reset-all!)
      (is (nil? (m/find :verse)) "reset-all! really did wipe it")
      (run (str "S\" " path "\" LOAD"))
      (is (some? (m/find :verse)) "LOAD restored it from disk")
      (finally (io/delete-file path true)))))

;; ── No-stack-arg, side-effecting words ──

(deftest reset-word-clears-everything
  (parse-commit! "[verse: c4]")
  (is (some? (m/find :verse)))
  (run "RESET")
  (is (nil? (m/find :verse)) "RESET wiped committed history back to a fresh :ROOT"))

(deftest help-prints-without-throwing
  (let [[_ out] (run-out "HELP")]
    (is (re-find #"parse" out)))
  (let [[_ out] (run-out "S\" parse\" HELP?")]
    (is (seq out))))

;; ── MIDI/playback group -- nil-fs engine, no real hardware touched ──
;; Mirrors async_engine_test.clj's own pattern for testing the engine
;; without opening a real MIDI device: (engine/engine nil (repo/registry)
;; :ROOT) bound via `binding` (test-local isolation -- set-engine!'s own
;; alter-var-root is for real cross-REPL-call persistence, not test
;; scoping), and marking musics.core's own `receiver` atom non-nil so
;; `play`'s own auto-connect guard (`(when (nil? @receiver) (connect))`)
;; never tries to open real hardware.

(deftest display-word-is-pure-and-needs-no-engine
  (parse-commit! "[tune: c4 d4]")
  (let [[steps] (run "S\" tune\" DISPLAY")]
    (is (= [[60] [62]] (mapv :pitches steps)))))

(deftest play-word-runs-through-a-nil-fs-engine-without-throwing
  (parse-commit! "[tune: c4 d4]")
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (is (= [] (run "S\" tune\" PLAY"))
          "PLAY doesn't push a value -- proving it ran without throwing is
           the point here, real audio can't be asserted on in a test")
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

(deftest play-bang-commits-and-plays-in-one-step
  (testing "quoted text: S\" ...\" PLAY! -- not yet parsed when PLAY! runs"
    (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
      (reset! m/receiver :fake-connected-for-this-test)
      (try
        (is (nil? (m/find :bang1)) "sanity: not committed before PLAY!")
        (is (= [] (run "S\" [bang1: c4 d4]\" PLAY!")))
        (is (some? (m/find :bang1)) "PLAY! really committed it")
        (finally
          (engine/stop!)
          (reset! m/receiver nil)))))
  (testing "bare musics: [...] PLAY! -- already committed {:ids ids} by the
            time PLAY! runs (see the unified pathway), not raw text"
    (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
      (reset! m/receiver :fake-connected-for-this-test)
      (try
        (is (nil? (m/find :bang2)))
        (is (= [] (run "[bang2: e4 f4] PLAY!")))
        (is (some? (m/find :bang2)))
        (finally
          (engine/stop!)
          (reset! m/receiver nil))))))

(deftest play-bang-only-plays-one-chunk-not-several
  ;; Documented gotcha, not a hypothetical: two separate bare chunks are
  ;; two separate tokens, each independently parsed (and committed --
  ;; parse commits immediately) the moment it's tokenized -- PLAY! only
  ;; ever pops the top one to PLAY it. Both still commit; only playback
  ;; is narrowed to the top chunk. The correct way to commit and play
  ;; several parts together is ONE string with several { } blocks in it,
  ;; the same multi-part support musics.core/parse itself already
  ;; documents.
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (run "[lost: c4] [kept: d4] PLAY!")
      (is (some? (m/find :lost)) "committed at parse time regardless -- just never played")
      (is (some? (m/find :kept)) "the one PLAY! actually popped and played")
      (finally
        (engine/stop!)
        (reset! m/receiver nil))))
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (run "S\" [both1: c4] [both2: d4]\" PLAY!")
      (is (some? (m/find :both1)) "one string, one sid -- both committed")
      (is (some? (m/find :both2)))
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

(deftest p-bang-commits-and-plays-a-quoted-string
  ;; P! is musics.core/p!'s own Forth word -- unlike PLAY! above, it
  ;; only ever pops a STRING (p!/play! call m/parse themselves, which
  ;; expects text, not an already-committed map), so only S" ..." works
  ;; here, not a bare {...} chunk (see the comment above P!'s own
  ;; def-prim in forth.clj).
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (is (nil? (m/find :pbang)) "sanity: not committed before P!")
      (is (= [] (run "S\" [pbang: c4 d4]\" P!")))
      (is (some? (m/find :pbang)) "P! really committed it")
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

(deftest p-bang-on-a-parse-failure-does-not-throw
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (binding [*out* (java.io.StringWriter.)]
        (is (= [] (run "S\" {unclosed\" P!"))
            "P! doesn't throw on a parse failure -- same nil-safe shape p! has"))
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

;; ── Generative transforms: times/transpose/invert/scale/reverse/
;; shuffle/thread/active-key/tonal-* ──

(defn- pitches-of [parts]
  (map (comp first :pitches) parts))

;; Every transform below is pure now (see musics.core/times' own comment
;; on the input-phase/read-eval-play split): SQ is called explicitly to
;; get material onto the stack FIRST, and none of these words pop a
;; tx-like arg of their own -- only SQ (and ACTIVE-KEY, for tonal-*'s
;; ks) ever reach into the repo.

(deftest times-through-forth-repeats-the-whole-phrase
  (parse-commit! "[verse: c4 d4]")
  (let [[result] (run "2 S\" verse\" SQ TIMES")]
    (is (= [60 62 60 62] (pitches-of result))
        "2 full passes, not 2 raw elements")))

(deftest transpose-through-forth-shifts-every-pitch
  (parse-commit! "[verse: c4 d4 e4]")
  (let [[result] (run "7 S\" verse\" SQ TRANSPOSE")]
    (is (= [67 69 71] (pitches-of result)))))

(deftest chaining-times-then-transpose-needs-outer-args-pushed-first
  ;; Real gotcha, confirmed live, not just reasoned through: got the
  ;; push order wrong once myself before this test existed. TRANSPOSE's
  ;; own semitones (7, the LAST word to run) has to be pushed BEFORE
  ;; TIMES's own n (2) and material -- otherwise 7 ends up on top of
  ;; TIMES's own result and gets popped by TRANSPOSE as if IT were the
  ;; material, not the actual seq.
  (parse-commit! "[verse: c4 d4]")
  (let [[result] (run "7 2 S\" verse\" SQ TIMES TRANSPOSE")]
    (is (= [67 69 67 69] (pitches-of result))
        "2 full passes of c4/d4, then all four shifted up 7 semitones")))

(deftest invert-through-forth-mirrors-around-an-explicit-axis
  (parse-commit! "[verse: c4 d4 e4 f4]")
  (let [[result] (run "60 S\" verse\" SQ INVERT")]
    (is (= [60 58 56 55] (pitches-of result))
        "new = 2*60 - old for each pitch")))

(deftest invert-mean-through-forth-mirrors-each-part-around-its-own-mean
  (parse-commit! "[verse: c4 d4 e4]")
  (let [[result] (run "S\" verse\" SQ INVERT-MEAN")]
    (is (= [60 62 64] (pitches-of result))
        "single-pitch leaves -- each one's own mean IS itself, unchanged")))

(deftest scale-through-forth-multiplies-every-duration
  (parse-commit! "[verse: c4 d4]")
  (let [[result] (run "2 S\" verse\" SQ SCALE")]
    (is (= [1/2 1/2] (map :duration result)))))

(deftest reverse-through-forth-flips-order-only
  (parse-commit! "[verse: c4 d4 e4]")
  (let [[result] (run "S\" verse\" SQ REVERSE")]
    (is (= [64 62 60] (pitches-of result)))))

(deftest shuffle-through-forth-keeps-every-part-just-reorders
  (parse-commit! "[verse: c4 d4 e4 f4 g4 a4 b4]")
  (let [[result] (run "S\" verse\" SQ SHUFFLE")]
    (is (= (set (pitches-of result)) #{60 62 64 65 67 69 71})
        "same multiset of pitches, just reordered")))

(deftest active-key-through-forth-reads-the-active-key
  ;; ACTIVE-KEY stays input-phase -- pops a bare id, same as SQ.
  (parse-commit! "[tune: !key:D.major c4]")
  (let [[ks] (run "S\" tune\" ACTIVE-KEY")]
    (is (= "D" (:display (:signature ks))))))

(deftest tonal-transpose-through-forth-follows-diatonic-steps
  ;; Same D-major math verified directly against musics.core earlier:
  ;; c4/d4/e4 up 1 diatonic step -> 62/64/66, not a fixed semitone shift.
  ;; ks (ACTIVE-KEY) and material (SQ) are each fetched from the repo
  ;; separately, ks pushed first -- mirrors musics.core's own
  ;; (tonal-transpose (active-key :tune) 1 (sq :tune)) exactly.
  (parse-commit! "[tune: !key:D.major !accidentals:explicit c4 d4 e4]")
  (let [[result] (run (str "S\" tune\" ACTIVE-KEY "
                           "1 "
                           "S\" tune\" SQ TONAL-TRANSPOSE"))]
    (is (= [nil nil 62 64 66] (pitches-of result))
        "leading !key: marker has no pitches, then the diatonic shift")))

(deftest snap-to-scale-through-forth-quantizes-off-scale-pitches
  (parse-commit! "[tune: !key:D.major !accidentals:explicit c4 d4 e4]")
  (let [[result] (run (str "S\" tune\" ACTIVE-KEY "
                           "S\" tune\" SQ SNAP-TO-SCALE"))]
    (is (= [nil nil 61 62 64] (pitches-of result)))))

(deftest thread-through-forth-applies-an-execution-token
  ;; NOOP's empty body leaves the pushed seq arg untouched -- token->fn
  ;; pushes it, runs NOOP (a no-op), pops the same value back --
  ;; confirms THREAD's own f-as-execution-token wiring actually calls
  ;; through, not just that it doesn't throw. Same ctx across both
  ;; run-string calls -- unlike run (which builds a fresh ctx per call,
  ;; see its own docstring), NOOP has to still exist in the dictionary
  ;; when THREAD looks it up.
  (parse-commit! "[verse: c4 d4]")
  (let [ctx (f/make-ctx)]
    (f/run-string ctx ": NOOP ;")
    (f/run-string ctx "' NOOP S\" verse\" SQ THREAD")
    (is (= [60 62] (pitches-of (peek @(:stack ctx)))))))

;; ── REPL-parity words ──

(deftest music-eval-commits-text-the-same-as-parse
  (testing "MUSIC-EVAL treats a string arg as musics text (calls s! under
            the hood), same {:ids ids} shape as PARSE"
    (let [[result] (run "S\" [verse: c4]\" MUSIC-EVAL")]
      (is (= [:verse] (:ids result))))))

;; ── State-atom reads ──

(deftest session-and-receiver-words-read-the-real-atoms
  (parse-commit! "[verse: c4]")
  (let [[s] (run "SESSION")]
    (is (map? s))
    (is (contains? s :auto-ids)))
  (is (= [nil] (run "RECEIVER")) "no MIDI connected in this test run"))

;; ── BYE / run-repl-loop / repl! ──
;; (mu!)'s from-Clojure-to-Forth counterpart -- verified live in a real
;; two-process session first (standalone -main and a nested repl! from
;; inside a running lein repl, confirmed core.repo state genuinely
;; shared both directions), these tests lock the same mechanics in.

(defn- feed-lines
  "Run f with *in* bound to a BufferedReader that yields lines one at a
   time (same shape a real terminal's read-line calls see), and *out*
   captured -- for exercising run-repl-loop/repl!, both of which read
   via plain read-line/print rather than any injectable stream arg."
  [lines f]
  (let [w (java.io.StringWriter.)]
    (binding [*in*  (java.io.BufferedReader. (java.io.StringReader. (str/join "\n" lines)))
              *out* w]
      (f))
    (str w)))

(deftest bye-unwinds-the-loop-without-printing-an-error
  (let [out (feed-lines ["2 3 + ." "BYE"]
                         #(f/run-repl-loop (f/make-ctx) "> "))]
    (is (re-find #"5" out) "the arithmetic before BYE still ran")
    (is (not (re-find #"Error" out))
        "BYE's forth-exit signal is a plain unwind, not a caught error")))

(deftest a-real-error-is-still-caught-and-the-loop-continues
  (let [out (feed-lines ["NOPE-NOT-A-WORD" "2 3 + ." "BYE"]
                         #(f/run-repl-loop (f/make-ctx) "> "))]
    (is (re-find #"Error: Unknown word: NOPE-NOT-A-WORD" out)
        "an ordinary error still prints, doesn't get mistaken for BYE")
    (is (re-find #"5" out)
        "the loop kept going after the error -- one bad line doesn't end the session")))

(deftest eof-with-no-input-ends-the-loop-cleanly
  (is (feed-lines [] #(f/run-repl-loop (f/make-ctx) "> "))
      "returns normally on immediate EOF, same as Ctrl-D at a real prompt"))

(deftest repl-bang-shares-core-repo-with-the-calling-clojure-session
  ;; The actual point of repl! over a standalone -main process: committed
  ;; from Clojure, visible inside the nested Forth loop, same as the
  ;; live two-process session this was verified against first.
  (parse-commit! "[verse: c4 d4]")
  (let [out (feed-lines ["S\" verse\" CHILDREN ." "BYE"]
                         f/repl!)]
    (is (re-find #"Forth REPL" out) "repl!'s own banner printed")
    (is (re-find #"Back to the Clojure REPL" out) "repl!'s own farewell printed")
    (is (re-find #":pitches \[60\]" out)
        ":verse (committed by the OUTER call, not this nested loop) is visible")))

(deftest repl-bang-never-calls-system-exit
  ;; Nothing to assert directly on System/exit not firing (the test JVM
  ;; would be dead if it had) -- this test passing at all, with the rest
  ;; of the suite still running after it, IS the assertion.
  (feed-lines ["BYE"] f/repl!)
  (is true "reached this line -- the JVM is still here"))
