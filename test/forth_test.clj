(ns ^:forth forth-test
  "Coverage for input.forth -- the hosted Forth interpreter -- both its
   own core language (arithmetic, colon definitions, control structures,
   locals, CREATE/DOES>, strings) and its musics.ebnf integration (bare
   {...}/<<...>>/'{...}/[...]/^{...}/@[...]/@{...} text, no S\" wrapper
   needed, and the { collision with gforth's own locals-block syntax).
   None of this had any test coverage before -- everything here was
   previously only checked by hand at a REPL."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [input.forth :as f]
            [musics :as m]
            [core.repo :as repo]
            [core.async-engine :as engine]
            [input.reader.flat-core-builder :as flat]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- run [s]
  "Run s against a fresh ctx, return the final stack (bottom..top)."
  (let [ctx (f/make-ctx)]
    (f/run-string ctx s)
    @(:stack ctx)))

(defn- run-out [s]
  "Run s against a fresh ctx, return [stack printed-output]."
  (let [ctx (f/make-ctx)
        out (with-out-str (f/run-string ctx s))]
    [@(:stack ctx) out]))

;; core.repo/musics.clj's session is a defonce'd singleton shared by
;; every namespace in this one JVM run (see musics-test's own identical
;; fixture) -- every word tested below this point drives that exact same
;; real store via musics.clj, not a throwaway per-test one, so it needs
;; the same reset-between-tests discipline musics_test.clj already uses,
;; or one test's :verse could collide with another's. The bare-musics-
;; text tests above this point (M., tokenize-*, bare-musics-*, ...) never
;; touch core.repo at all (see the file header docstring and CLAUDE.md's
;; "standalone, one-off walk" note), so they're unaffected either way.
(defn- reset-musics-fixture [f]
  (repo/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! m/session {:auto-ids {} :var-map {}})
  (reset! m/receiver nil)
  (f))

(use-fixtures :each reset-musics-fixture)

(defn- parse-commit!
  "Test helper, mirrors musics_test.clj's own parse! -- run text through
   the real PARSE/>SID/COMMIT! word sequence against a scratch ctx and
   return the committed tx, discarding the scratch ctx's own stack."
  [text]
  (let [ctx (f/make-ctx)]
    (f/run-string ctx (str "S\" " text "\" PARSE >SID COMMIT!"))
    (peek @(:stack ctx))))

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
  (is (= [[:musics "{verse: C4 c4}"]] (f/tokenize "{verse: C4 c4}")))
  (let [[v] (run "{verse: C4 c4}")
        pitches (map (comp first :pitches) (:children (get (m/pending (:sid v)) :verse)))]
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
  (doseq [text ["{verse: c4 d4}" "<<{a: c4} {b: d4}>>" "'{grp: c4 d4}"
                "[c 4 3/2]" "^{ctx: !mf}" "@[algo [c4]]" "@{algo {c4}}"]]
    (testing text
      (is (= [[:musics text]] (f/tokenize text))))))

(deftest bare-musics-text-stages-into-the-real-repo-same-as-parse
  ;; Bare {...} calls m/parse directly now (unified with S" ..." PARSE,
  ;; not a separate standalone/session-less walk) -- same {:sid :ids}
  ;; shape, real staged content visible via pending, real COMMIT!-able.
  (let [[v] (run "{verse: c4 d4 e4}")]
    (is (map? v))
    (is (keyword? (:sid v)))
    (is (= [:verse] (:ids v)))
    (is (= 3 (count (:children (get (m/pending (:sid v)) :verse))))
        "visible pre-commit via pending, same as any other staged parse"))
  (is (nil? (m/find :verse2)) "not committed yet")
  (run "{verse2: c4} >SID COMMIT!")
  (is (some? (m/find :verse2)) "bare musics text really did commit through COMMIT!"))

(deftest bare-musics-coexists-with-ordinary-forth-on-one-line
  ;; arithmetic, then a musics chunk pushed and dropped, then more
  ;; arithmetic -- all on one line, mutually unaffected
  (is (= [5 12] (run "2 3 + {verse: c4} DROP 4 3 *"))))

(deftest atomic-algo-and-repeat-work-bare-inside-forth
  (let [[v] (run "{ct: \\repeat unfold 3 { @[ colorTalea [C4 D4 E4] [/4 /8] ] } }")
        staged (m/pending (:sid v))
        iter (first (:children (get staged :ct)))]
    (is (= :REPEAT (:type iter)))
    (is (= 3 (:count (:params iter))))
    (is (= 6 (count (:children (:source iter))))
        "one period, lcm(3,2)=6 -- the algo itself never sees the repeat count")))

(deftest string-literal-inside-musics-text-does-not-confuse-bracket-scanning
  (is (= [[:musics "[42 \"text with } and ] inside\" 3/4]"]]
         (f/tokenize "[42 \"text with } and ] inside\" 3/4]"))))

(deftest m-dot-prints-a-pushed-musics-value
  (let [[stack out] (run-out "{verse: c4 d4} M.")]
    (is (= [] stack) "M. pops the value it prints")
    (is (re-find #":verse" out))
    (is (re-find #"2 leaves" out))))

;; ============================================================
;; { collision -- Forth locals vs musics Sequence
;; ============================================================

(deftest locals-brace-only-recognized-immediately-after-a-colon-name
  (testing "right after : NAME -- Forth locals, not musics"
    (is (= [":" "SQUARE" "{" "X" "}"] (f/tokenize ": SQUARE { x }"))
        "tokenized as separate words, not swallowed into one :musics chunk
         -- x reads as X since word lookup is case-insensitive"))
  (testing "anywhere else -- musics, not Forth locals"
    (is (= [[:musics "{verse: c4 d4}"]] (f/tokenize "{verse: c4 d4}")))
    (is (= ["DUP" [:musics "{verse: c4 d4}"] "SWAP"]
           (f/tokenize "DUP {verse: c4 d4} SWAP")))))

(deftest locals-position-workaround-with-empty-braces
  ;; If musics text is wanted as the very first thing after : NAME, an
  ;; empty locals block frees the position back up -- the documented
  ;; escape hatch for the one reserved spot.
  (is (= [":" "FOO" "{" "}" [:musics "{verse: c4}"] ";"]
         (f/tokenize ": FOO { } {verse: c4} ;"))))

;; ============================================================
;; musics.clj bridge -- every public musics.clj fn wired as a Forth word
;; ============================================================
;; See input.forth's own "musics.clj bridge" comment block (right above
;; musics-prims) for the full argument-marshaling convention this
;; exercises: ->kw on id/sid/key/phase/action-id args, tx always
;; required (LATEST-TX supplies the default), and PARSE/S!/PARSE-FILE's
;; {:sid :ids} result pushed as one map plus >SID/>IDS accessors.

;; ── The primary workflow: stage, commit, inspect real content ──

(deftest parse-commit-leaves-real-pipeline
  (testing "S\" ...\" PARSE DUP >SID COMMIT! stages then commits real text;
            LEAVES reads back the real, committed leaves -- count and
            pitches both checked, not just \"didn't throw\""
    (let [ctx (f/make-ctx)]
      (f/run-string ctx "S\" {verse: !mf c4 d4 e4}\" PARSE DUP >SID COMMIT! DROP")
      (is (nil? (m/find :sid1)) "sanity: :sid1 was never a repo id")
      (is (some? (m/find :verse)) "COMMIT! actually made :verse visible")
      (f/run-string ctx "S\" verse\" LATEST-TX LEAVES")
      (let [leaves (peek @(:stack ctx))]
        (is (= 3 (count leaves)))
        (is (= [[60] [62] [64]] (mapv :pitches leaves))
            "c4 d4 e4 -> MIDI 60/62/64, in written order")))))

(deftest bare-musics-inside-a-loop-body-re-parses-every-iteration
  ;; Real bug, confirmed directly (2026-08-12): a bare {...} chunk inside
  ;; a compiled body (DO/BEGIN/IF, or a colon-definition) used to be
  ;; baked as a :lit op -- m/parse called ONCE, at compile time, with
  ;; every iteration just re-pushing that same already-staged value.
  ;; `10 0 DO {verse: c4} PLAY! LOOP` called m/parse exactly once despite
  ;; 10 iterations. Fixed via a dedicated :parse-musics op that defers
  ;; the call to run-body's own dispatch, so it reruns -- and re-stages,
  ;; under a fresh sid -- every time this op is actually reached.
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
  (reset! m/receiver :fake-connected-for-this-test)
  (try
    (run "5 0 DO {loopy: c4} PLAY! LOOP")
    (is (= 5 (count (m/history :loopy)))
        "5 loop iterations, 5 real commits -- not 1 stale one replayed 5x")
    (is (= 5 (count (into #{} (map first (m/history :loopy)))))
        "5 genuinely distinct tx numbers, not the same tx counted 5 times")
    (finally
      (engine/stop!)
      (reset! m/receiver nil))))

(deftest sid-and-ids-accessors
  (testing ">SID / >IDS pull the two fields out of PARSE's {:sid :ids}
            result -- the documented multi-value-return convention"
    (let [[sid ids] (run "S\" {a: c4} {b: d4}\" PARSE DUP >SID SWAP >IDS")]
      (is (keyword? sid) "a real staging id, e.g. :sid1")
      (is (= [:a :b] ids)))))

(deftest pending-shows-staged-content-and-abort-discards-it
  (testing "PENDING ( sid -- map ) sees staged content pre-commit;
            ABORT! ( sid -- ) discards it, never becomes visible"
    (let [ctx (f/make-ctx)]
      (f/run-string ctx "S\" {oops: c4}\" PARSE DUP >SID")
      (f/run-string ctx "DUP PENDING")
      (let [pending (peek @(:stack ctx))]
        (is (map? pending))
        (is (contains? pending :oops)))
      (f/run-string ctx "DROP ABORT!")
      (is (nil? (m/find :oops)) "aborted sid's edits never became visible"))))

;; ── Staged-sid group, and COMMIT!/C! both wired ──

(deftest commit-and-c-bang-are-both-wired-and-return-the-new-tx
  (let [tx1 (first (run "S\" {p1: c4}\" PARSE >SID COMMIT!"))
        tx2 (first (run "S\" {p2: c4}\" PARSE >SID C!"))]
    (is (integer? tx1))
    (is (integer? tx2))
    (is (some? (m/find :p1)))
    (is (some? (m/find :p2)))
    (is (not= tx1 tx2) "each commit mints its own tx")))

;; ── Repo-id group: id (+ tx) argument words ──

(deftest children-leaves-sq-group-reads-real-committed-content
  (parse-commit! "{verse: c4 d4}")
  (let [tx (m/latest-tx)]
    (is (= 2 (count (first (run (str "S\" verse\" " tx " CHILDREN"))))))
    (is (= 2 (count (first (run (str "S\" verse\" " tx " LEAVES"))))))
    (let [[sq-result] (run (str "S\" verse\" " tx " SQ"))]
      (is (= 2 (count sq-result)))
      (is (= :verse (:id (meta sq-result))) "sq tags its result with the source id"))))

(deftest ctx-value-samples-a-real-committed-context
  (parse-commit! "{verse: !mf c4}")
  (let [tx (m/latest-tx)
        [v] (run (str "S\" verse\" S\" volume\" 0.0 " tx " CTX-VALUE"))]
    (is (number? v) "!mf set a real, readable volume envelope value")))

(deftest describe-returns-data-print-structure-prints-it
  (parse-commit! "{verse: c4 d4}")
  (let [tx (m/latest-tx)
        [described] (run (str "S\" verse\" " tx " DESCRIBE"))]
    (is (map? described))
    (is (= :verse (:id described)))
    (is (= 2 (:leaf-count described))))
  (let [tx (m/latest-tx)
        [stack out] (run-out (str "S\" verse\" " tx " PRINT-STRUCTURE"))]
    (is (= [] stack) "PRINT-STRUCTURE prints, doesn't push a value")
    (is (re-find #":verse" out))))

(deftest inspect-and-inspect-all-both-print
  (parse-commit! "{verse: c4 d4}")
  (let [tx (m/latest-tx)
        [stack out] (run-out (str "S\" verse\" " tx " INSPECT"))]
    (is (= [] stack))
    (is (re-find #"verse" out)))
  (let [[stack out] (run-out "INSPECT-ALL")]
    (is (= [] stack))
    (is (re-find #"node" out) "the session node-count overview, a genuinely
                                different 0-arg form, not just (inspect :ROOT tx)")))

(deftest locate-navigates-a-real-path
  (testing "LOCATE ( id-str path tx -- {:part ... :ctx-chain ... :path ...} ) --
            path is a raw selector vector, no Forth literal syntax exists for
            it yet, so it's seeded directly the same way S\" ... already
            bypasses needing a general string-literal builder"
    (parse-commit! "{verse: c4 d4}")
    (let [ctx (f/make-ctx)]
      (f/push! ctx "verse")
      (f/push! ctx [1])
      (f/push! ctx (m/latest-tx))
      (f/run-string ctx "LOCATE")
      (is (= [62] (:pitches (:part (peek @(:stack ctx)))))
          "root's 2nd child (index 1) is d4"))))

(deftest history-and-as-of-see-real-tx-history
  (parse-commit! "{verse: c4}")
  (let [tx1 (m/latest-tx)]
    (parse-commit! "{verse: c4 d4 e4}")
    (let [tx2 (m/latest-tx)
          [hist] (run "S\" verse\" HISTORY")
          [v1]   (run (str "S\" verse\" " tx1 " AS-OF"))
          [v2]   (run (str "S\" verse\" " tx2 " AS-OF"))]
      (is (= 2 (count hist)) "two commits touched :verse")
      (is (= 1 (count (:children v1))))
      (is (= 3 (count (:children v2)))))))

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
  (parse-commit! "{verse: c4}")
  (let [[action-id] (run "S\" verse\" S\" exit\" S\" latest\" SCHEDULE-TX!")]
    (is (some? action-id) "schedule-tx! returns the generated action-id")
    (is (= action-id (m/scheduled :verse :exit)))))

;; ── Persistence ──

(deftest write-and-load-round-trip
  (parse-commit! "{verse: c4 d4}")
  (let [path (str (System/getProperty "java.io.tmpdir") "/forth-test-" (gensym) ".edn")]
    (try
      (run (str "S\" " path "\" " (m/latest-tx) " WRITE"))
      (is (.exists (io/file path)))
      (repo/reset-all!)
      (is (nil? (m/find :verse)) "reset-all! really did wipe it")
      (run (str "S\" " path "\" LOAD"))
      (is (some? (m/find :verse)) "LOAD restored it from disk")
      (finally (io/delete-file path true)))))

;; ── No-stack-arg, side-effecting words ──

(deftest reset-word-clears-everything
  (parse-commit! "{verse: c4}")
  (is (some? (m/find :verse)))
  (run "RESET")
  (is (nil? (m/find :verse)) "RESET wiped committed history back to a fresh :ROOT"))

(deftest help-and-algos-print-without-throwing
  (let [[_ out] (run-out "HELP")]
    (is (re-find #"parse" out)))
  (let [[_ out] (run-out "S\" parse\" HELP?")]
    (is (seq out)))
  (let [[_ out] (run-out "ALGOS")]
    (is (re-find #"colorTalea" out) "the one default-registered algo"))
  (let [[_ out] (run-out "S\" colorTalea\" ALGOS?")]
    (is (re-find #"talea" out))))

;; ── MIDI/playback group -- nil-fs engine, no real hardware touched ──
;; Mirrors async_engine_test.clj's own pattern for testing the engine
;; without opening a real MIDI device: (engine/engine nil repo/play-tx
;; :ROOT) plus set-engine!, and marking musics.clj's own `receiver` atom
;; non-nil so `play`'s own auto-connect guard (`(when (nil? @receiver)
;; (connect))`) never tries to open real hardware.

(deftest display-word-is-pure-and-needs-no-engine
  (parse-commit! "{tune: c4 d4}")
  ;; DISPLAY reads through core.repo/play-tx (see musics.clj/display),
  ;; same pointer live playback reads through -- committing alone never
  ;; moves it (see CLAUDE.md's "Session, the versioned repo, and
  ;; playback"), so it has to be pointed at this commit explicitly
  ;; first, same as a real REPL session would.
  (m/play-latest!)
  (let [[steps] (run "S\" tune\" DISPLAY")]
    (is (= [[60] [62]] (mapv :pitches steps)))))

(deftest play-word-runs-through-a-nil-fs-engine-without-throwing
  (parse-commit! "{tune: c4 d4}")
  (m/play-latest!)
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
  (reset! m/receiver :fake-connected-for-this-test)
  (try
    (is (= [] (run "S\" tune\" PLAY"))
        "PLAY doesn't push a value -- proving it ran without throwing is
         the point here, real audio can't be asserted on in a test")
    (finally
      (engine/stop!)
      (reset! m/receiver nil))))

(deftest play-bang-stages-commits-and-plays-in-one-step
  (testing "quoted text: S\" ...\" PLAY! -- not yet parsed when PLAY! runs"
    (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (is (nil? (m/find :bang1)) "sanity: not committed before PLAY!")
      (is (= [] (run "S\" {bang1: c4 d4}\" PLAY!")))
      (is (some? (m/find :bang1)) "PLAY! really staged AND committed it")
      (finally
        (engine/stop!)
        (reset! m/receiver nil))))
  (testing "bare musics: {...} PLAY! -- already staged {:sid :ids} by the
            time PLAY! runs (see the unified pathway), not raw text"
    (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
    (reset! m/receiver :fake-connected-for-this-test)
    (try
      (is (nil? (m/find :bang2)))
      (is (= [] (run "{bang2: e4 f4} PLAY!")))
      (is (some? (m/find :bang2)))
      (finally
        (engine/stop!)
        (reset! m/receiver nil)))))

(deftest play-bang-only-consumes-one-staged-chunk-not-several
  ;; Documented gotcha, not a hypothetical: two separate bare chunks are
  ;; two separate tokens, each independently parsed (own sid) the moment
  ;; it's tokenized -- PLAY! only ever pops the top one. The correct way
  ;; to stage/commit/play several parts together is ONE string with
  ;; several { } blocks in it, the same multi-part support musics.clj/
  ;; parse itself already documents.
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
  (reset! m/receiver :fake-connected-for-this-test)
  (try
    (run "{lost: c4} {kept: d4} PLAY!")
    (is (nil? (m/find :lost)) "staged but never committed -- PLAY! never saw it")
    (is (some? (m/find :kept)) "the one PLAY! actually popped")
    (finally
      (engine/stop!)
      (reset! m/receiver nil)))
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
  (reset! m/receiver :fake-connected-for-this-test)
  (try
    (run "S\" {both1: c4} {both2: d4}\" PLAY!")
    (is (some? (m/find :both1)) "one string, one sid -- both committed")
    (is (some? (m/find :both2)))
    (finally
      (engine/stop!)
      (reset! m/receiver nil))))

;; ── REPL-parity words ──

(deftest music-eval-stages-text-the-same-as-parse
  (testing "MUSIC-EVAL treats a string arg as musics text (calls s! under
            the hood), same {:sid :ids} shape as PARSE"
    (let [[result] (run "S\" {verse: c4}\" MUSIC-EVAL")]
      (is (= [:verse] (:ids result))))))

(deftest register-algo-bang-is-wired-for-parity-and-calls-straight-through
  (testing "REGISTER-ALGO! can't build a usable algo fn from bare Forth
            text (an @[ ] algo's own positional Data/Primitive calling
            convention isn't something this Forth can construct) -- but
            the word itself is real and calls musics.clj unchanged, so
            seeding a genuine Clojure fn onto the stack from outside
            (exactly the parity limitation documented in input.forth)
            still works end to end."
    (let [ctx (f/make-ctx)]
      (f/push! ctx "forth-test-algo")
      (f/push! ctx (fn [pitches durs] (map vector pitches durs)))
      (f/run-string ctx "REGISTER-ALGO!")
      (let [[_ out] (run-out "S\" forth-test-algo\" ALGOS?")]
        (is (not (re-find #"Unknown algo" out))
            "REGISTER-ALGO! really parked the seeded fn under that name"))
      (m/unregister-algo! "forth-test-algo")
      (let [[_ out] (run-out "S\" forth-test-algo\" ALGOS?")]
        (is (re-find #"Unknown algo" out) "UNREGISTER-ALGO! forgot it again")))))

;; ── State-atom reads ──

(deftest session-and-receiver-words-read-the-real-atoms
  (parse-commit! "{verse: c4}")
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
  ;; The actual point of repl! over a standalone -main process: staged
  ;; from Clojure, visible inside the nested Forth loop, same as the
  ;; live two-process session this was verified against first.
  (parse-commit! "{verse: c4 d4}")
  (let [out (feed-lines ["S\" verse\" LATEST-TX CHILDREN ." "BYE"]
                         f/repl!)]
    (is (re-find #"Forth REPL" out) "repl!'s own banner printed")
    (is (re-find #"Back to the Clojure REPL" out) "repl!'s own farewell printed")
    (is (re-find #":pitches \[60\]" out)
        ":verse (staged by the OUTER call, not this nested loop) is visible")))

(deftest repl-bang-never-calls-system-exit
  ;; Nothing to assert directly on System/exit not firing (the test JVM
  ;; would be dead if it had) -- this test passing at all, with the rest
  ;; of the suite still running after it, IS the assertion.
  (feed-lines ["BYE"] f/repl!)
  (is true "reached this line -- the JVM is still here"))
