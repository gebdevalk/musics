(ns ^:repl musics-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [musics :as m]
            [core.repo :as repo]
            [core.async-engine :as engine]
            [input.reader.flat-core-builder :as flat]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [common.music-elements :as el]
            [algo.random.core :as seed]
            [algo.random.core :as chance]))

(defn reset-state-fixture [f]
  ;; core.repo's registry/staging/play-tx are defonce'd (shared across the
  ;; whole test namespace), so a leftover commit from a previous test would
  ;; otherwise leak into the next test's (find)/(children)/etc. A session
  ;; is never nil in real use either (see musics.clj's own _bootstrap) --
  ;; match that here too, rather than resetting to a state real code never
  ;; sees: a real :ROOT, committed, with playback pointed at it.
  (repo/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! m/session {:auto-ids {}})
  (f))

(use-fixtures :each reset-state-fixture)

(defn parse!
  "Test helper: parse and immediately commit, returning the ids the parse
   introduced or changed -- (parse ...) itself only stages now (see its
   docstring), so tests that don't care about the staging step use this
   to get the old immediate-visibility behavior."
  [text]
  (let [{:keys [sid ids]} (m/parse text)]
    (m/commit! sid)
    ids))

(defn- quietly
  "Run f with *out* redirected to a throwaway sink and return its value.
   musics/display intentionally pprints its own result for REPL
   inspection (see its own docstring) -- exactly right at a REPL, pure
   noise in a test run that only cares about the return value; this
   keeps display's own design intact while keeping `lein test` quiet."
  [f]
  (binding [*out* (java.io.StringWriter.)]
    (f)))

;; ============================================================
;; Parse
;; ============================================================

(deftest parse-returns-new-ids
  (let [new-ids (parse! "[verse: c4 d4]")]
    (is (= [:verse] new-ids) "parse returns the newly-added top-level ids")
    (is (d/container? (m/find :verse)) "id resolves to a container in the session")))

(deftest parse-is-staged-until-commit
  (let [{:keys [sid ids]} (m/parse "[verse: c4 d4]")]
    (is (= [:verse] ids))
    (is (nil? (m/find :verse)) "not visible before commit!")
    (is (map? (m/pending sid)) "staged edits are inspectable before commit")
    (m/commit! sid)
    (is (d/container? (m/find :verse)) "visible after commit!")))

(deftest aborted-parse-never-becomes-visible
  (let [{:keys [sid]} (m/parse "[verse: c4 d4]")]
    (m/abort! sid)
    (is (nil? (m/find :verse)))
    (is (nil? (m/pending sid)) "aborted sid no longer has staged edits")))

(deftest parse-registers-ids
  (parse! "[verse: c4 d4]")
  (parse! "[chorus: g4 a4 b4]")
  (let [all-ids (set (m/ids))]
    (is (all-ids :verse) "verse registered")
    (is (all-ids :chorus) "chorus registered")))

(deftest parse-error-returns-nil
  (binding [*out* (java.io.StringWriter.)]
    (is (nil? (m/parse "[c4 d4")) "unclosed bracket returns nil")))

(deftest root-children-accumulates-every-top-level-parse
  (parse! "[verse: c4 d4]")
  (parse! "[chorus: g4 a4]")
  (parse! "[song: :verse :chorus]")
  (is (= [:verse :chorus :song] (m/root-children))
      "every top-level parse this session has seen, in call order -- not just the latest"))

(deftest parse-ids-is-a-vector-of-just-this-calls-own-top-level-ids
  ;; :ids is what play-file! actually uses now -- computed directly from
  ;; this walk's own freshly-built :ROOT :children (already the
  ;; corrected, deduplicated list a redefinition leaves in place -- see
  ;; the flat-core-builder regression test above), not by filtering
  ;; root-children (a separate, session-wide, cross-call view) after the
  ;; fact. A plain vector, in the order they were written -- :ROOT's own
  ;; :children is already deduplicated on a redefinition (pop-container),
  ;; so there's no realistic duplicate-id case a vector needs to guard
  ;; against the way a set-shaped return once did.
  (testing "order is preserved for a multi-block parse"
    (let [{:keys [ids]} (m/parse "[a: c4] [b: d4] [c: e4]")]
      (is (= [:a :b :c] ids) "seq order is exactly written order")))
  (testing "only a direct :ROOT child counts -- nested content that also
            changed as part of the same parse doesn't leak in"
    (let [{:keys [ids]} (m/parse "[outer: c4 [inner: d4]]")]
      (is (= [:outer] ids)
          ":inner did change (and got staged/committed same as always),
           but it's not a direct :ROOT child, so it's not part of ids")))
  (testing "re-parsing an existing top-level id doesn't duplicate it either"
    (parse! "[verse: c4]")
    (let [{:keys [ids]} (m/parse "[verse: d4]")]
      (is (= [:verse] ids)))))

(deftest re-parsing-the-same-top-level-id-does-not-duplicate-it-in-root-children
  ;; Regression coverage: flat-core-builder's pop-container used to conj
  ;; a newly-registered container's id onto its parent's :children
  ;; unconditionally. Harmless for a genuinely new id, but :ROOT's own
  ;; :children carries forward across parse calls (initial-state seeds
  ;; the stack from the session's existing :ROOT) -- so re-parsing (or
  ;; re-committing) an unchanged top-level [verse: ...] a second time
  ;; appended a *second* :verse, a third time a third, etc. Found via
  ;; repeatedly (play-file! "some.mus") on an unedited file: play-file!'s
  ;; own filtering doesn't dedupe either, so the file's content played
  ;; back to back once per accumulated duplicate.
  (testing "the same top-level id, re-parsed across separate calls"
    (parse! "[verse: c4 d4]")
    (parse! "[verse: e4 f4]")
    (parse! "[verse: g4 a4]")
    (is (= [:verse] (m/root-children))
        "still one entry, not one per re-parse"))
  (testing "the same top-level id declared twice within ONE parse call"
    (parse! "[chorus: c4] [chorus: d4]")
    (is (= [:verse :chorus] (m/root-children))
        "one entry here too, appended once to the existing list from above"))
  (testing "a genuinely repeated REFERENCE (not a redefinition) is a different
            thing entirely and must NOT be deduped -- [song: :verse :chorus
            :verse] deliberately plays :verse twice"
    (parse! "[song: :verse :chorus :verse]")
    (is (= [:verse :chorus :verse] (:children (m/find :song))))))

(deftest locate-navigates-the-session-with-no-repo-argument
  (parse! "[verse: c4 d4]")
  (let [{:keys [part]} (m/locate :verse [1])]
    (is (d/leaf? part))
    (is (= [62] (:pitches part)))))

(deftest cross-parse-references-resolve
  ;; This is the regression test for the bug that motivated the session
  ;; refactor: separately-parsed parts referenced from a later parse used
  ;; to silently vanish, since each parse built its own isolated repo.
  ;; (children ...) auto-resolves keyword children against the latest
  ;; committed tx by default now, so no explicit tree/view is needed.
  (parse! "[verse: c4 d4]")
  (parse! "[chorus: g4 a4]")
  (parse! "[song: :verse :chorus]")
  (let [song-children (m/children :song)]
    (is (= 2 (count song-children)) "song has two children")
    (is (every? d/container? song-children)
        "both children resolve to real containers, not dangling keywords")
    (is (= :verse (:id (first song-children))))
    (is (= :chorus (:id (second song-children))))))

(deftest pristine-parse-has-a-two-context-chain
  ;; A session's :ROOT always carries the one true root context (built at
  ;; session-start by flat/empty-session). Locating a leaf in a freshly-
  ;; parsed, unnamed top-level sequence should see exactly ROOT's context
  ;; and the sequence's own context -- not a third, separately-constructed
  ;; root context stacked on top.
  (parse! "[a b c]")
  (let [loc (r/locate (repo/view (repo/latest-tx)) :ROOT [0 0])]
    (is (= 2 (count (:ctx-chain loc))))))

;; ============================================================
;; play-tx: committing is decoupled from what's playing
;; ============================================================

(deftest commit-does-not-move-play-tx
  (let [before @repo/play-tx]
    (parse! "[verse: c4 d4]")
    (is (not= before (repo/latest-tx)) "commit! did mint a new tx")
    (is (= before @repo/play-tx) "but play-tx was left exactly where it was")))

(deftest play-tx-bang-repoints-playback-explicitly
  (parse! "[verse: c4 d4]")
  (let [tx1 @repo/play-tx]
    (parse! "[verse: e4 f4]")                                 ;; redefine :verse, mints a new tx
    (is (= tx1 @repo/play-tx) "still pointing at the pre-redefinition tx")
    (m/play-tx! (m/latest-tx))
    (is (= (m/latest-tx) @repo/play-tx) "explicit play-tx! moved it")))

(deftest play-latest-bang-follows-latest-commit
  (parse! "[verse: c4 d4]")
  (m/play-latest!)
  (is (= (m/latest-tx) @repo/play-tx)))

;; ============================================================
;; play! / p! -- play-file!'s own stage+commit+play recipe, starting
;; from text instead of a file path (mirrors input.forth's PLAY! word)
;; ============================================================

(defn- with-fake-receiver
  "Runs f with a real (nil-fs, so no actual MIDI I/O) engine wired up
   and m/receiver set to something truthy -- exactly enough for play/
   play!/p! to skip their own (connect) auto-call (which would
   otherwise try to open real MIDI hardware in a test run) without
   needing a real Receiver. Restores m/receiver afterward regardless."
  [f]
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT))
  (reset! m/receiver :fake)
  (try (f) (finally (reset! m/receiver nil))))

(deftest play-bang-stages-commits-and-plays-in-one-step
  (with-fake-receiver
    #(do
       (is (keyword? (m/play! "[verse: c4 d4]"))
           "same track-id return play/play-file! have")
       (is (d/container? (m/find :verse)) "committed and visible -- not just staged"))))

(deftest p-bang-is-a-short-name-for-play-bang
  (with-fake-receiver
    #(do
       (m/p! "[chorus: g4 a4]")
       (is (d/container? (m/find :chorus))
           "p! stages/commits/plays the same way play! does"))))

(deftest play-bang-on-a-parse-failure-does-not-throw
  ;; ids is nil on a parse failure, so this ends in a bare (play) call --
  ;; still returns a real track id (play always does, see its own
  ;; docstring), just with no material of its own to play. Not a
  ;; reliable failure signal on its own -- parse itself already printed
  ;; the error, same failure shape parse/play-file! already have.
  (with-fake-receiver
    #(binding [*out* (java.io.StringWriter.)]
       (is (keyword? (m/play! "[unclosed"))))))

;; ============================================================
;; Playback offset rebasing (core.domain.context/ctx-shift) -- a
;; container's own envelope is built at parse time with local, zero-
;; based time (flat-tree-walker's (duration state) resets per
;; container); a Ramp (or any multi-point envelope) that isn't the
;; first thing played in its voice used to resolve straight to its
;; endpoint instead of interpolating, since async-engine's build-chain
;; prepended that context onto the ctx-chain unrebased. async_engine_test
;; already covers this with hand-built domain objects; these two cover
;; it through the real, end-to-end (m/parse ...)/(m/commit! ...) path,
;; for the two shapes real usage actually takes.
;; ============================================================

(deftest ramp-inside-a-nested-sequence-of-one-originally-parsed-piece
  (testing "a single piece, parsed and committed as one call -- not
            multiple top-level parts concatenated together -- whose
            Ramp sits inside a NESTED Sequence (not the piece's own
            top level) still rebases against ITS OWN local start, not
            wherever structural-time has already reached by the time
            playback enters it"
    (parse! "[piece: C4/4 D4/4 [inner: !vol:30 !vol<2:80 E4/4 F4/4 G4/4 A4/4] ]")
    (m/play-latest!)
    (is (= [64 64 38 46 54 62]
           (mapv :velocity (engine/display repo/play-tx :piece)))
        "C4/D4 at root's own default volume (50), then inner's ramp
         interpolating from its own local 30 toward 80 -- not
         [50 50 55 68 80 80], which is what inner's envelope would read
         back at outer's-duration-plus-its-own-local-time instead --
         velocities rescaled via common.defaults/volume->midi from those
         raw 0-100-scale volumes")))

(deftest ramp-in-a-part-aggregated-by-reference-into-a-new-composite
  (testing "two parts parsed and committed SEPARATELY (verse, chorus --
            each gets its own envelope built independently, as if
            authored/tested in isolation), then aggregated into a third,
            new piece purely by id reference (song: :verse :chorus, the
            same shape CLAUDE.md's own [song: :verse :chorus :verse]
            example uses) -- chorus's Ramp must still rebase correctly
            once it's reached only via that reference, exactly as if it
            had been written inline"
    (parse! "[verse: C4/4 D4/4]")
    (parse! "[chorus: !vol:30 !vol<2:80 E4/4 F4/4 G4/4 A4/4]")
    (parse! "[song: :verse :chorus]")
    (m/play-latest!)
    (is (= [64 64 38 46 54 62]
           (mapv :velocity (engine/display repo/play-tx :song))))))

;; ============================================================
;; Inspection defaults to latest committed tx, with an explicit tx
;; argument for looking at any point in history
;; ============================================================

(deftest inspection-fns-accept-an-explicit-tx
  (parse! "[verse: c4 d4]")
  (let [tx1 (m/latest-tx)]
    (parse! "[verse: e4 f4]")                                 ;; redefine :verse
    (is (= [60] (:pitches (first (m/children :verse tx1))))
        "as of tx1, verse still has its original first note (c4)")
    (is (= [64] (:pitches (first (m/children :verse))))
        "no tx arg defaults to the latest commit, seeing the redefinition (e4)")
    (is (= #{:verse} (set (m/ids tx1))) "(ids tx) also respects the pin")))

;; ============================================================
;; Find
;; ============================================================

(deftest find-by-keyword
  (parse! "[verse: c4 d4]")
  (let [c (m/find :verse)]
    (is (d/container? c))
    (is (= :verse (:id c)))))

(deftest find-by-string
  (parse! "[verse: c4 d4]")
  (is (d/container? (m/find "verse"))))

(deftest find-nonexistent-returns-nil
  (is (nil? (m/find :bogus)) "bogus keyword returns nil"))

;; ============================================================
;; Children / Leaves
;; ============================================================

(deftest children-of-named-part
  (parse! "[verse: c4 d4]")
  (let [ch (m/children :verse)]
    (is (= 2 (count ch)) "two children")
    (is (every? d/leaf? ch) "both are leaves")))

(deftest leaves-of-named-part
  (parse! "[verse: c4 d4]")
  (let [ls (m/leaves :verse)]
    (is (= 2 (count ls)) "two leaves")
    (is (every? d/leaf? ls) "both are leaves")))

;; ============================================================
;; sq -- real Clojure seqs over container children
;; ============================================================

(deftest sq-tags-a-sequential-container-as-not-parallel
  (parse! "[verse: c4 d4]")
  (let [s (m/sq :verse)]
    (is (= {:parallel? false :id :verse} (meta s)))
    (is (= (m/children :verse) s))))

(deftest sq-tags-a-parallel-container-as-parallel
  (parse! "#{par1: [a: c4] [b: d4]}")
  (let [s (m/sq :par1)]
    (is (= {:parallel? true :id :par1} (meta s)))
    (is (= 2 (count s)))))

(deftest sq-of-nonexistent-returns-nil
  (is (nil? (m/sq :bogus))))

(deftest sq-result-composes-with-ordinary-clojure-seq-functions
  (parse! "[verse: c4 d4 e4]")
  (is (= 5 (count (take 5 (cycle (m/sq :verse))))))
  (is (every? d/leaf? (map identity (m/sq :verse)))))

;; ============================================================
;; times -- n full passes of a container's material, playable directly
;; ============================================================

;; times/transpose/invert/scale/reverse/shuffle/thread/tonal-* below all
;; take material -- an already-built seq, from (sq id) or another of
;; these fns' own output -- never a bare id, never a tx. sq/active-key
;; are the only input-phase fns (real core.repo/tx interaction); once
;; you have material, every combinator below is a pure seq->seq fn. See
;; musics.clj's own comment above times for the fuller reasoning.

(deftest times-repeats-the-whole-material-not-just-n-elements
  ;; The exact gotcha times exists to avoid: :verse has 5 children (a
  ;; leading !mf marker plus 4 notes) -- (take 4 (cycle (sq :verse)))
  ;; stops mid-phrase (marker + first 3 notes), never one full repeat.
  (parse! "[verse: !mf c4 d4 e4 f4]")
  (let [t (m/times 4 (m/sq :verse))]
    (is (= 20 (count t)) "4 full passes of all 5 children, not 4 elements")
    (is (= (vec (repeat 4 (m/sq :verse))) (partition 5 t))
        "each 5-child pass is identical, in order")))

;; ============================================================
;; transpose / invert -- REPL-level siblings of the grammar's own
;; chromatic transforms, mapped over material
;; ============================================================

(deftest transpose-shifts-every-pitch-by-semitones
  (parse! "[verse: c4 d4 e4]")
  (is (= [67 69 71] (map (comp first :pitches) (m/transpose 7 (m/sq :verse))))))

(deftest transpose-passes-non-pitched-children-through-unchanged
  (parse! "[verse: !mf c4 d4]")
  (is (= [nil 62 64] (map (comp first :pitches) (m/transpose 2 (m/sq :verse))))
      "the leading !mf instruction marker has no :pitches -- untouched"))

(deftest transpose-composes-with-times
  (parse! "[verse: c4 d4]")
  (is (= [62 64 62 64] (map (comp first :pitches) (m/transpose 2 (m/times 2 (m/sq :verse)))))
      "times' own output is material too, so it composes straight in"))

;; ============================================================
;; Leaf-level baked :ctx-chain -- sq/times'd material resolves against
;; its own source container's context, not whatever minimal ctx-chain
;; the play call it's fed into happens to build. See CLAUDE.md's
;; "Context has no parent pointer" bullet for the full mechanism.
;; ============================================================

(deftest times-of-sq-resolves-against-the-source-containers-own-context
  ;; The exact bug reported live: !i:32/!mf set on :verse are :verse's
  ;; OWN context, never :ROOT's -- sq returns bare children with no
  ;; memory of that, so playing them standalone used to silently fall
  ;; back to ROOT's generic defaults (instrument 0/piano, volume 50)
  ;; instead of :verse's own values.
  (parse! "[verse: !i:32 !mf c4]")
  (m/play-latest!)
  (let [[direct]    (quietly #(m/display :verse))
        [extracted] (quietly #(m/display (m/times 2 (m/sq :verse))))]
    (is (= 32 (:program direct) (:program extracted))
        "instrument survives being extracted via sq and repeated via times")
    (is (= 76 (:velocity direct) (:velocity extracted))
        "!mf's volume (60 on the 0-100 scale, 76 once rescaled via
         common.defaults/volume->midi) survives too, not ROOT's raw
         default")))

(deftest times-of-sq-preserves-a-ramps-relative-timing-per-repeat
  ;; The wrong-turn this design took and recovered from, locked in: an
  ;; earlier fix shifted every baked ancestor context uniformly by
  ;; "now", which flattened a ramp spanning several leaves to its start
  ;; value on every one of them instead of interpolating. The correct
  ;; fix re-bases each ancestor by (structural-time - its own relative
  ;; offset), so each repeat re-interpolates fresh from the ramp's own
  ;; start, exactly matching how it plays un-extracted.
  (parse! "[verse: !vol:30<l c4 d4 e4 f4 !vol:80]")
  (m/play-latest!)
  (let [normal    (mapv :velocity (quietly #(m/display :verse)))
        extracted (mapv :velocity (quietly #(m/display (m/times 2 (m/sq :verse)))))]
    (is (= [38 54 70 86] normal)
        "raw 0-100-scale ramp values 30/43/55/68 rescaled via
         common.defaults/volume->midi")
    (is (= (into normal normal) extracted)
        "two full repeats, each independently re-interpolating from 30 --
         not flattened to the same velocity 8 times over, and not
         continuing to climb monotonically across the repeat boundary
         either")))

(deftest invert-with-no-axis-mirrors-each-part-around-its-own-mean
  ;; A single-pitch leaf's own mean IS its only pitch -- unchanged.
  (parse! "[verse: c4 d4 e4]")
  (is (= [60 62 64] (map (comp first :pitches) (m/invert (m/sq :verse))))))

(deftest invert-around-a-fixed-axis
  (parse! "[verse: c4 d4 e4 f4]")
  (is (= [60 58 56 55] (map (comp first :pitches) (m/invert 60 (m/sq :verse))))
      "new = 2*60 - old for each pitch"))

;; ============================================================
;; scale -- \times/\tuplet's own duration-multiplier, REPL-side
;; ============================================================

(deftest scale-multiplies-every-duration
  (parse! "[verse: c4 d4]")
  (is (= [1/2 1/2] (map :duration (m/scale 2 (m/sq :verse))))))

(deftest scale-passes-non-duration-children-through-unchanged
  (parse! "[verse: !mf c4]")
  (is (= [nil 1/2] (map :duration (m/scale 2 (m/sq :verse))))
      "the leading !mf instruction marker has no :duration -- untouched"))

(deftest scale-also-works-directly-on-bare-numbers
  ;; The generic half of scale-value's contract -- not just musical
  ;; parts, so it composes with a plain Clojure seq of numbers too.
  (is (= [1/2 1/4 1] (m/scale 2 [1/4 1/8 1/2]))))

;; ============================================================
;; reverse -- order only, shadows clojure.core/reverse in musics
;; ============================================================

(deftest reverse-flips-material-order-only
  (parse! "[verse: c4 d4 e4]")
  (is (= [64 62 60] (map (comp first :pitches) (m/reverse (m/sq :verse))))
      "pitches/durations within each note untouched, just the order"))

;; ============================================================
;; shuffle / thread -- random reordering, and a generic seq->seq door
;; ============================================================

(deftest shuffle-reorders-but-keeps-every-part
  (parse! "[verse: c4 d4 e4 f4 g4 a4 b4]")
  (let [before (set (map (comp first :pitches) (m/sq :verse)))
        after  (m/shuffle (m/sq :verse))]
    (is (= before (set (map (comp first :pitches) after)))
        "same multiset of parts, just reordered")))

(deftest shuffle-is-reproducible-under-with-seed
  (parse! "[verse: c4 d4 e4 f4 g4 a4 b4]")
  (is (= (seed/with-seed 42 (vec (map (comp first :pitches) (m/shuffle (m/sq :verse)))))
         (seed/with-seed 42 (vec (map (comp first :pitches) (m/shuffle (m/sq :verse))))))
      "same seed -> same permutation, every time (algo.random.core's own contract)"))

(deftest thread-applies-an-arbitrary-seq-fn-to-material
  (parse! "[verse: c4 d4 e4]")
  (is (= [64 62 60] (map (comp first :pitches) (m/thread clojure.core/reverse (m/sq :verse))))
      "any seq-in/seq-out fn works, not just the dedicated wrappers"))

(deftest thread-composes-with-a-real-algo-random-chance-fn
  (parse! "[verse: c4 d4 e4]")
  (is (= 2 (count (m/thread (partial chance/choose-n 2) (m/sq :verse))))
      "algo.random.core/choose-n is exactly the kind of fn thread exists for"))

;; ============================================================
;; active-key / tonal-* -- scale-relative transforms. active-key is
;; input-phase (bare id + tx, reads !key: from core.repo); the tonal-*
;; fns themselves are pure, always taking an explicit ks
;; ============================================================

(deftest active-key-defaults-to-c-major-with-no-key-set
  (parse! "[verse: c4]")
  (is (= "C" (:display (:signature (m/active-key :verse))))))

(deftest active-key-picks-up-an-explicit-key-assignment
  (parse! "[tune: !key:D.major c4]")
  (is (= "D" (:display (:signature (m/active-key :tune))))))

;; !accidentals:explicit throughout below -- bare pitch letters (c4/d4/
;; e4) otherwise resolve against the active key's own implied accidental
;; (D major implies C#/F#), which would color the INPUT pitches under
;; test and defeat the point of asserting on the transform's own output.
;; Confirmed concretely: c4 under bare !key:D.major parsed as [61], not
;; [60], the first time this was written without :explicit.

(deftest tonal-transpose-follows-diatonic-steps-of-the-given-key
  ;; D major's own scale-pcs: C#,D,E,F#,G,A,B -- moving each pitch up
  ;; one scale degree follows that pattern, not a fixed semitone count.
  ;; Two leading nils: !key:/!accidentals: are both non-pitched children.
  (parse! "[tune: !key:D.major !accidentals:explicit c4 d4 e4]")
  (is (= [nil nil 62 64 66]
         (map (comp first :pitches) (m/tonal-transpose (m/active-key :tune) 1 (m/sq :tune))))))

(deftest tonal-transpose-result-depends-on-which-ks-is-passed
  ;; e4 up 1 diatonic step lands differently in D major (F#, 66) vs an
  ;; explicitly-passed C major (F, 65) -- ks is always explicit now, so
  ;; this is just "different ks, different result", not an override.
  (parse! "[tune: !key:D.major !accidentals:explicit c4 d4 e4]")
  (let [material (m/sq :tune)]
    (is (= [nil nil 62 64 66]
           (map (comp first :pitches) (m/tonal-transpose (m/active-key :tune) 1 material)))
        "tune's own active D major")
    (is (= [nil nil 62 64 65]
           (map (comp first :pitches) (m/tonal-transpose (el/key :C :major) 1 material)))
        "an explicitly different C major")))

(deftest snap-to-scale-quantizes-off-scale-pitches
  (parse! "[tune: !key:D.major !accidentals:explicit c4 d4 e4]")
  (is (= [nil nil 61 62 64]
         (map (comp first :pitches) (m/snap-to-scale (m/active-key :tune) (m/sq :tune))))))

;; ============================================================
;; Context query -- ctx (display) vs. ctx-value (sampling)
;; ============================================================

(deftest ctx-value-samples-by-canonical-key-or-any-alias
  ;; !tempo:120 is written under the canonical :Tempo (see
  ;; common.defaults' :Tempo registration, :aliases [:T :tempo]).
  ;; ctx-value must canonicalize its own key argument the same way a
  ;; write already does, or every alias except the canonical spelling
  ;; would silently read back nil.
  (parse! "[verse: !tempo:120 c4]")
  (is (= 120 (m/ctx-value :verse :Tempo 0.0)) "canonical spelling")
  (is (= 120 (m/ctx-value :verse :tempo 0.0)) "lowercase alias")
  (is (= 120 (m/ctx-value :verse :T 0.0)) "single-letter alias"))

(deftest ctx-value-defaults-to-latest-tx
  (parse! "[verse: !tempo:120 c4]")
  (is (= 120 (m/ctx-value :verse :tempo 0.0))))

(deftest ctx-shows-ancestor-chain-nearest-first-root-excluded
  (parse! "[verse: !tempo:120 [inner: !vol:80 c4]]")
  (let [inner-id (first (filter keyword? (:children (m/find :verse))))
        out      (with-out-str (m/ctx inner-id))]
    (is (re-find #":inner" out) "inner's own authored value shows")
    (is (re-find #":verse" out) "ancestor's authored value shows too")
    (is (not (re-find #":ROOT" out)) ":ROOT itself is excluded")
    ;; nearest-first: :inner's own line comes before :verse's
    (is (< (.indexOf out ":inner") (.indexOf out ":verse")))))

(deftest ctx-on-unregistered-id-prints-not-found
  (is (re-find #"(?i)not found" (with-out-str (m/ctx :nope)))))

(deftest ctx-value-finds-a-value-set-on-an-intermediate-ancestor
  ;; Regression coverage: ctx-value used to build only a 2-element
  ;; [part's own context, :ROOT's context] chain, skipping anything
  ;; authored on an ancestor in between -- a value set on :outer (not
  ;; :inner's own context, not :ROOT) was invisible from :inner.
  (parse! "[outer: !key:D.major [inner: c4]]")
  (let [ks (m/ctx-value :inner :key 0.0)]
    (is (= "D" (:display (:signature ks))) "found on :outer, not just own/:ROOT")))

(deftest ctx-value-still-samples-roots-default-with-nothing-set
  (parse! "[plain: c4]")
  (let [ks (m/ctx-value :plain :key 0.0)]
    (is (= "C" (:display (:signature ks))))))

;; ============================================================
;; Expand -- ornaments sample the leaf's complete ancestor chain
;; ============================================================

(deftest expand-samples-key-from-an-intermediate-ancestor
  ;; Same gap as ctx-value, on the ornament path: orn/expand alone can
  ;; only see [leaf's own context, root-ctx] (a bare Leaf carries no
  ;; path back to its ancestors) -- musics/expand has to build the real
  ;; chain itself (full-ctx-chain) and hand it over.
  (parse! "[outer: !key:D.major [inner: c4\\trill]]")
  (let [leaf     (first (m/leaves :inner))
        expanded (m/expand leaf)]
    (is (< 1 (count expanded)) "trill actually expanded into sub-notes")
    (is (not (apply = (map :pitches expanded)))
        "a real trill alternates -- D major's key was found on :outer")))

;; ============================================================
;; Persistence
;; ============================================================

(deftest write-load-round-trips-session
  (parse! "[verse: c4 d4]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (m/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (is (nil? (m/find :verse)) "session really was cleared before load")
      (with-out-str (m/load (.getPath tmp)))
      (is (d/container? (m/find :verse)) "verse resolves again after load")
      (is (= 2 (count (m/children :verse))) "verse's children survived the round-trip")
      (finally (io/delete-file tmp true)))))

(deftest write-load-round-trips-a-meter-record
  ;; core.repo commit-node!/write always includes :ROOT, and :ROOT's own
  ;; context now carries a real Meter record as its default -- regression
  ;; coverage for the write/load break that caused (fixed in persist.clj's
  ;; freeze/thaw: Meter/Key records can't survive a bare pr-str/edn/read-
  ;; string round-trip without explicit tagging).
  (parse! "[verse: !Meter:\"7/8(2+2+3)\" c4]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (m/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (with-out-str (m/load (.getPath tmp)))
      (let [meter (m/ctx-value :verse :Meter 0.0)]
        (is (= 7 (:num meter)))
        (is (= 8 (:den meter)))
        (is (= [2 2 3] (:subdivisions meter))))
      (finally (io/delete-file tmp true)))))

(deftest load-then-parse-does-not-collide-ids
  ;; Bare (unnamed) sequences mint auto-ids like :s1 -- the real
  ;; collision risk this session refactor was meant to fix. Confirm the
  ;; counter keeps counting up across a load instead of restarting at 0
  ;; and clobbering what was loaded.
  (parse! "[c4 d4]")                                        ;; mints :s1
  (let [tmp      (java.io.File/createTempFile "musics-session" ".edn")
        s1-repo  (into {} (repo/view (repo/latest-tx)))]
    (try
      (with-out-str (m/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! m/session {:auto-ids {}})
      (with-out-str (m/load (.getPath tmp)))
      (let [new-ids    (parse! "[g4 a4]")                   ;; would also want :s1 if reset
            leaf-shape (fn [container]
                         ;; Leaf/Context both embed atoms (reference-
                         ;; identity, never = across a round-trip even
                         ;; with equal content) -- compare pitches/duration
                         ;; instead of whole records.
                         (mapv (juxt :duration :pitches) (:children container)))]
        (is (not= :s1 (first new-ids)) "auto-id counter continued past what was loaded")
        (is (= (leaf-shape (get s1-repo :s1))
               (leaf-shape (get (into {} (repo/view (repo/latest-tx))) :s1)))
            "the loaded :s1 was not overwritten by the new parse"))
      (finally (io/delete-file tmp true)))))
