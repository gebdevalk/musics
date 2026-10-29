(ns ^:repl musics.session-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [musics.test-support :refer [with-fresh-session]]
            [musics.events :as ev] [musics.mu.store :as store] [musics.persist :as persist] [musics.registries :as reg]
            [musics.repo :as repo]
            [musics.engine :as engine]
            [musics.compose :as compose]
            [musics.wall :as wall]
            [musics.domain :as d]
            [musics.domain.resolve :as r]
            [musics.domain.ornaments :as orn]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]))

;; pitch shift and duration stretch are musics.algo.tree algos now (musics.algo.tree.lib)
(defn- shift [n material] (t/run (lib/transpose :m) {:m material :semitones n}))
(defn- stretch [f material] (t/run (lib/stretch> :m) {:m material :factor f}))
(defn- times [n material] (take (* n (count material)) (cycle material)))

(defn reset-state-fixture [f]
  ;; with-fresh-session wraps (f) itself -- the whole test body runs
  ;; inside its binding's dynamic extent, genuinely isolated from
  ;; whatever any OTHER test namespace left in the shared repo/wall/
  ;; conductor/activity-log atoms (previously: musics.repo's registry/
  ;; play-tx are defonce'd/shared across the whole JVM, so a leftover
  ;; commit from a DIFFERENT test namespace could leak in, not just from
  ;; this file's own previous test). Still seeds a real :ROOT, committed,
  ;; with playback pointed at it -- a session is never nil in real use
  ;; either (see musics.mu.store's _bootstrap). musics.registries' `session`
  ;; atom (:auto-ids) is a plain defonce, not a musics.registries
  ;; ^:dynamic var, so it still needs its own explicit reset! here.
  (with-fresh-session
    (reset! reg/session {:auto-ids {}})
    (f)))

(use-fixtures :each reset-state-fixture)

(defn parse!
  "Test helper: parse (commits immediately, see its own docstring) and
   return the ids it introduced or changed."
  [text]
  (:ids (store/read! text)))

(defn- quietly
  "Run f with *out* redirected to a throwaway sink and return its value.
   musics.compose/display-timed intentionally pprints its own result for REPL
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
    (is (d/container? (repo/lookup :verse)) "id resolves to a container in the session")))

(deftest parse-commits-immediately
  (let [{:keys [ids]} (store/read! "[verse: c4 d4]")]
    (is (= [:verse] ids))
    (is (d/container? (repo/lookup :verse)) "visible immediately, no separate commit step")))

(deftest parse-registers-ids
  (parse! "[verse: c4 d4]")
  (parse! "[chorus: g4 a4 b4]")
  (let [all-ids (set (repo/ids))]
    (is (all-ids :verse) "verse registered")
    (is (all-ids :chorus) "chorus registered")))

(deftest parse-error-returns-nil
  (binding [*out* (java.io.StringWriter.)]
    (is (nil? (store/read! "[c4 d4")) "unclosed bracket returns nil")))

(deftest root-children-accumulates-every-top-level-parse
  (parse! "[verse: c4 d4]")
  (parse! "[chorus: g4 a4]")
  (parse! "[song: :verse :chorus]")
  (is (= [:verse :chorus :song] (repo/root-children))
      "every top-level parse this session has seen, in call order -- not just the latest"))

(deftest parse-ids-is-a-vector-of-just-this-calls-own-top-level-ids
  ;; :ids is what play-file! actually uses now -- computed directly from
  ;; this walk's own freshly-built :ROOT :children (already the
  ;; corrected, deduplicated list a redefinition leaves in place -- see
  ;; the builder regression test above), not by filtering
  ;; root-children (a separate, session-wide, cross-call view) after the
  ;; fact. A plain vector, in the order they were written -- :ROOT's own
  ;; :children is already deduplicated on a redefinition (pop-container),
  ;; so there's no realistic duplicate-id case a vector needs to guard
  ;; against the way a set-shaped return once did.
  (testing "order is preserved for a multi-block parse"
    (let [{:keys [ids]} (store/read! "[a: c4] [b: d4] [c: e4]")]
      (is (= [:a :b :c] ids) "seq order is exactly written order")))
  (testing "only a direct :ROOT child counts -- nested content that also
            changed as part of the same parse doesn't leak in"
    (let [{:keys [ids]} (store/read! "[outer: c4 [inner: d4]]")]
      (is (= [:outer] ids)
          ":inner did change (and got staged/committed same as always),
           but it's not a direct :ROOT child, so it's not part of ids")))
  (testing "re-parsing an existing top-level id doesn't duplicate it either"
    (parse! "[verse: c4]")
    (let [{:keys [ids]} (store/read! "[verse: d4]")]
      (is (= [:verse] ids)))))

(deftest re-parsing-the-same-top-level-id-does-not-duplicate-it-in-root-children
  ;; Regression coverage: builder's pop-container used to conj
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
    (is (= [:verse] (repo/root-children))
        "still one entry, not one per re-parse"))
  (testing "the same top-level id declared twice within ONE parse call"
    (parse! "[chorus: c4] [chorus: d4]")
    (is (= [:verse :chorus] (repo/root-children))
        "one entry here too, appended once to the existing list from above"))
  (testing "a genuinely repeated REFERENCE (not a redefinition) is a different
            thing entirely and must NOT be deduped -- [song: :verse :chorus
            :verse] deliberately plays :verse twice"
    (parse! "[song: :verse :chorus :verse]")
    (is (= [:verse :chorus :verse] (:children (repo/lookup :song))))))

(deftest locate-navigates-the-session-with-no-repo-argument
  (parse! "[verse: c4 d4]")
  (let [{:keys [part]} (r/locate @(repo/registry) :verse [1])]
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
  (let [song-children (repo/children :song)]
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
  (let [loc (r/locate @(repo/registry) :ROOT [0 0])]
    (is (= 2 (count (:ctx-chain loc))))))

;; ============================================================
;; musics.repo: committing is always immediately visible, no separate
;; "play-tx" pointer to advance -- only an ALREADY-RUNNING voice's own
;; :view is insulated from a later commit (see musics.engine's own
;; docstring)
;; ============================================================

(deftest commit-is-visible-immediately
  (parse! "[verse: c4 d4]")
  (is (= 2 (count (repo/children :verse)))))

(deftest redefining-something-replaces-it-immediately
  (parse! "[verse: c4 d4]")
  (parse! "[verse: e4 f4]")                                 ;; redefine :verse
  (is (= [64 65] (map (comp first :pitches) (repo/children :verse)))
      "the old value is simply gone, no history to fall back to"))

;; ============================================================
;; usages / parse's shared-id redefinition warning
;; ============================================================

(deftest usages-finds-every-direct-referrer-of-a-shared-id
  (parse! "[motif: c4 d4] [verseA: :motif e4] [verseB: :motif f4]")
  (is (= #{:verseA :verseB :ROOT} (repo/usages :motif))
      "both real referrers found, plus :ROOT (every top-level id's own
       registering parent) -- see parse's own :ROOT exclusion below")
  (is (= #{:ROOT} (repo/usages :verseA))
      ":verseA is itself only referenced by :ROOT -- nothing else points at it"))

(deftest usages-is-empty-for-an-id-nothing-references
  (parse! "[lonely: c4]")
  (is (= #{:ROOT} (repo/usages :lonely))
      "only :ROOT (its own registering parent) -- no OTHER container shares it"))

(deftest parse-warns-when-a-redefinition-has-spillover
  (parse! "[motif: c4 d4] [verseA: :motif e4] [verseB: :motif f4]")
  (let [printed (with-out-str (store/read! "[motif: g4 a4]"))]
    (is (re-find #"Redefining :motif also affects" printed))
    (is (re-find #":verseA" printed))
    (is (re-find #":verseB" printed))
    (is (not (re-find #":ROOT" printed))
        ":ROOT is deliberately excluded -- it references every top-level id
         by construction, so including it would fire on every ordinary
         redefinition and say nothing the composer doesn't already know")))

(deftest parse-does-not-warn-when-nothing-is-shared
  (let [printed (with-out-str (store/read! "[solo: c4 d4]"))]
    (is (= "" printed) "an ordinary, unshared top-level redefinition prints nothing")))

(deftest parse-does-not-warn-when-the-whole-batch-covers-the-spillover
  ;; Redefining :motif AND every one of its own real referrers (:verseA,
  ;; :verseB) together, in the SAME parse call, means nothing outside
  ;; this commit is affected by surprise -- the composer's own call
  ;; already accounts for all of it.
  (parse! "[motif: c4 d4] [verseA: :motif e4] [verseB: :motif f4]")
  (let [printed (with-out-str
                  (store/read! "[motif: g4 a4] [verseA: :motif b4] [verseB: :motif b4]"))]
    (is (= "" printed)
        "every referrer was part of the same batch, so there's no surprise spillover")))

;; ============================================================
;; play! / p! -- play-file!'s own stage+commit+play recipe, starting
;; from text instead of a file path
;; ============================================================

(defn- with-fake-receiver
  "Runs f with a real (nil-fs, so no actual MIDI I/O) engine wired up
   and engine/receiver set to something truthy -- exactly enough for play/
   play!/p! to skip their own (connect) auto-call (which would
   otherwise try to open real MIDI hardware in a test run) without
   needing a real Receiver. Restores engine/receiver afterward regardless."
  [f]
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (reset! engine/receiver :fake)
    (try (f) (finally (reset! engine/receiver nil)))))

;; ============================================================
;; Playback offset rebasing (musics.domain.context/ctx-shift) -- a
;; container's own envelope is built at parse time with local, zero-
;; based time (walker's (duration state) resets per
;; container); a Ramp (or any multi-point envelope) that isn't the
;; first thing played in its voice used to resolve straight to its
;; endpoint instead of interpolating, since async-engine's build-chain
;; prepended that context onto the ctx-chain unrebased. async_engine_test
;; already covers this with hand-built domain objects; these two cover
;; it through the real, end-to-end (store/read! ...) path (commits
;; immediately), for the two shapes real usage actually takes.
;; ============================================================

(deftest ramp-inside-a-nested-sequence-of-one-originally-parsed-piece
  (testing "a single piece, parsed and committed as one call -- not
            multiple top-level parts concatenated together -- whose
            Ramp sits inside a NESTED Sequence (not the piece's own
            top level) still rebases against ITS OWN local start, not
            wherever structural-time has already reached by the time
            playback enters it"
    (parse! "[piece: C4/4 D4/4 [inner: !vol:30 !vol<2:80 E4/4 F4/4 G4/4 A4/4] ]")
    (is (= [64 64 38 46 54 62]
           (mapv :velocity (compose/display-timed (repo/registry) :piece)))
        "C4/D4 at root's own default volume (50), then inner's ramp
         interpolating from its own local 30 toward 80 -- not
         [50 50 55 68 80 80], which is what inner's envelope would read
         back at outer's-duration-plus-its-own-local-time instead --
         velocities rescaled via musics.common.context-keys/volume->midi from those
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
    (is (= [64 64 38 46 54 62]
           (mapv :velocity (compose/display-timed (repo/registry) :song))))))

;; ============================================================
;; Inspection always reflects whatever's currently committed -- there's
;; no history to pin against, so a redefinition is visible immediately.
;; ============================================================

(deftest inspection-fns-see-a-redefinition-immediately
  (parse! "[verse: c4 d4]")
  (parse! "[verse: e4 f4]")                                 ;; redefine :verse
  (is (= [64] (:pitches (first (repo/children :verse))))
      "sees the redefinition (e4), the old value is simply gone")
  (is (= #{:verse} (set (repo/ids)))))

;; ============================================================
;; Find
;; ============================================================

(deftest find-by-keyword
  (parse! "[verse: c4 d4]")
  (let [c (repo/lookup :verse)]
    (is (d/container? c))
    (is (= :verse (:id c)))))

(deftest find-by-string
  (parse! "[verse: c4 d4]")
  (is (d/container? (repo/lookup "verse"))))

(deftest find-nonexistent-returns-nil
  (is (nil? (repo/lookup :bogus)) "bogus keyword returns nil"))

;; ============================================================
;; Children / Leaves
;; ============================================================

(deftest children-of-named-part
  (parse! "[verse: c4 d4]")
  (let [ch (repo/children :verse)]
    (is (= 2 (count ch)) "two children")
    (is (every? d/leaf? ch) "both are leaves")))

;; ============================================================
;; sq -- real Clojure seqs over container children
;; ============================================================

(deftest sq-tags-a-sequential-container-as-not-parallel
  (parse! "[verse: c4 d4]")
  (let [s (compose/sq :verse)]
    (is (= {:parallel? false :id :verse :node (repo/lookup :verse)} (meta s)))
    (is (= (repo/children :verse) s))))

(deftest sq-tags-a-parallel-container-as-parallel
  (parse! "{ par1: [a: c4] [b: d4] }")
  (let [s (compose/sq :par1)]
    (is (= {:parallel? true :id :par1 :node (repo/lookup :par1)} (meta s)))
    (is (= 2 (count s)))))

(deftest sq-of-nonexistent-returns-nil
  (is (nil? (compose/sq :bogus))))

;; ============================================================
;; stale? -- has an already-extracted sq result's own source been
;; re-committed since it was captured
;; ============================================================

(deftest sq-result-composes-with-ordinary-clojure-seq-functions
  (parse! "[verse: c4 d4 e4]")
  (is (= 5 (count (take 5 (cycle (compose/sq :verse))))))
  (is (every? d/leaf? (map identity (compose/sq :verse)))))

;; ============================================================
;; times -- n full passes of a container's material, playable directly
;; ============================================================

;; times/transpose/invert/scale/reverse/shuffle/thread/tonal-* below all
;; take material -- an already-built seq, from (sq id) or another of
;; these fns' own output -- never a bare id. sq/active-key are the only
;; input-phase fns (real musics.repo interaction); once you have material,
;; every combinator below is a pure seq->seq fn. See the
;; comment above times for the fuller reasoning.

;; ============================================================
;; transpose / invert -- REPL-level siblings of the grammar's own
;; chromatic transforms, mapped over material
;; ============================================================

(deftest transpose-shifts-every-pitch-by-semitones
  (parse! "[verse: c4 d4 e4]")
  (is (= [67 69 71] (map (comp first :pitches) (shift 7 (compose/sq :verse))))))

(deftest transpose-passes-non-pitched-children-through-unchanged
  (parse! "[verse: !mf c4 d4]")
  (is (= [nil 62 64] (map (comp first :pitches) (shift 2 (compose/sq :verse))))
      "the leading !mf instruction marker has no :pitches -- untouched"))

(deftest transpose-composes-with-times
  (parse! "[verse: c4 d4]")
  (is (= [62 64 62 64] (map (comp first :pitches) (shift 2 (times 2 (compose/sq :verse)))))
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
  (let [[direct]    (quietly #(compose/display-timed (repo/registry) :verse))
        [extracted] (quietly #(compose/display-timed (repo/registry) (times 2 (compose/sq :verse))))]
    (is (= 32 (:program direct) (:program extracted))
        "instrument survives being extracted via sq and repeated via times")
    (is (= 76 (:velocity direct) (:velocity extracted))
        "!mf's volume (60 on the 0-100 scale, 76 once rescaled via
         musics.common.context-keys/volume->midi) survives too, not ROOT's raw
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
  (let [normal    (mapv :velocity (quietly #(compose/display-timed (repo/registry) :verse)))
        extracted (mapv :velocity (quietly #(compose/display-timed (repo/registry) (times 2 (compose/sq :verse)))))]
    (is (= [38 54 70 86] normal)
        "raw 0-100-scale ramp values 30/43/55/68 rescaled via
         musics.common.context-keys/volume->midi")
    (is (= (into normal normal) extracted)
        "two full repeats, each independently re-interpolating from 30 --
         not flattened to the same velocity 8 times over, and not
         continuing to climb monotonically across the repeat boundary
         either")))

;; ============================================================
;; stretch -- \times/\tuplet's own duration-multiplier, as a tree algo
;; ============================================================

(deftest stretch-multiplies-every-duration
  (parse! "[verse: c4 d4]")
  (is (= [1/2 1/2] (map :duration (stretch 2 (compose/sq :verse))))))

(deftest stretch-passes-non-duration-children-through-unchanged
  (parse! "[verse: !mf c4]")
  (is (= [nil 1/2] (map :duration (stretch 2 (compose/sq :verse))))
      "the leading !mf instruction marker has no :duration -- untouched"))

(deftest stretch-also-works-directly-on-bare-numbers
  ;; not just musical parts -- a plain seq of numbers (a talea) too
  (is (= [1/2 1/4 1] (stretch 2 [1/4 1/8 1/2]))))

;; ============================================================
;; reverse -- order only, shadows clojure.core/reverse in musics
;; ============================================================

;; ============================================================
;; shuffle / thread -- random reordering, and a generic seq->seq door
;; ============================================================

;; ============================================================
;; active-key / tonal-* -- scale-relative transforms. active-key is
;; input-phase (bare id, reads !key: from musics.repo); the tonal-*
;; fns themselves are pure, always taking an explicit ks
;; ============================================================

;; ============================================================
;; Context query -- ctx (display) vs. ctx-value (sampling)
;; ============================================================

(deftest ctx-value-samples-by-canonical-key-or-any-alias
  ;; !tempo:120 is written under the canonical :Tempo (see
  ;; musics.common.context-keys' :Tempo registration, :aliases [:T :tempo]).
  ;; ctx-value must canonicalize its own key argument the same way a
  ;; write already does, or every alias except the canonical spelling
  ;; would silently read back nil.
  (parse! "[verse: !tempo:120 c4]")
  (is (= 120 (r/ctx-value :verse :Tempo 0.0)) "canonical spelling")
  (is (= 120 (r/ctx-value :verse :tempo 0.0)) "lowercase alias")
  (is (= 120 (r/ctx-value :verse :T 0.0)) "single-letter alias"))

(deftest ctx-value-reads-whatever-is-currently-committed
  (parse! "[verse: !tempo:120 c4]")
  (is (= 120 (r/ctx-value :verse :tempo 0.0))))

(deftest ctx-shows-ancestor-chain-nearest-first-root-excluded
  (parse! "[verse: !tempo:120 [inner: !vol:80 c4]]")
  (let [inner-id (first (filter keyword? (:children (repo/lookup :verse))))
        out      (with-out-str (r/ctx inner-id))]
    (is (re-find #":inner" out) "inner's own authored value shows")
    (is (re-find #":verse" out) "ancestor's authored value shows too")
    (is (not (re-find #":ROOT" out)) ":ROOT itself is excluded")
    ;; nearest-first: :inner's own line comes before :verse's
    (is (< (.indexOf out ":inner") (.indexOf out ":verse")))))

(deftest ctx-on-unregistered-id-prints-not-found
  (is (re-find #"(?i)not found" (with-out-str (r/ctx :nope)))))

(deftest ctx-value-finds-a-value-set-on-an-intermediate-ancestor
  ;; Regression coverage: ctx-value used to build only a 2-element
  ;; [part's own context, :ROOT's context] chain, skipping anything
  ;; authored on an ancestor in between -- a value set on :outer (not
  ;; :inner's own context, not :ROOT) was invisible from :inner.
  (parse! "[outer: !key:D.major [inner: c4]]")
  (let [ks (r/ctx-value :inner :key 0.0)]
    (is (= "D" (:display (:signature ks))) "found on :outer, not just own/:ROOT")))

(deftest ctx-value-still-samples-roots-default-with-nothing-set
  (parse! "[plain: c4]")
  (let [ks (r/ctx-value :plain :key 0.0)]
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
  (let [leaf     (first (filter d/leaf? (repo/children :inner)))
        expanded (orn/expand leaf (r/full-ctx-chain @(repo/registry) leaf))]
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
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (is (nil? (repo/lookup :verse)) "session really was cleared before load")
      (with-out-str (persist/load (.getPath tmp)))
      (is (d/container? (repo/lookup :verse)) "verse resolves again after load")
      (is (= 2 (count (repo/children :verse))) "verse's children survived the round-trip")
      (finally (io/delete-file tmp true)))))

(deftest write-load-round-trips-algo-params
  (parse! "@^{ k:5 style:rock iv:0,2,4 key:\"D.major\" }")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (with-out-str (persist/load (.getPath tmp)))
      (let [settings (some #(when (= :TCONTEXT (:type %)) %) (vals @(repo/registry)))]
        (is (= [["k" 5] ["style" :rock] ["iv" [0 2 4]] ["key" "D.major"]] (:params settings)))
        (is (= "k:5 style:rock iv:0,2,4 key:\"D.major\"" (:text settings)) "written back as written"))
      (finally (io/delete-file tmp true)))))

(deftest write-load-round-trips-a-meter-record
  ;; musics.repo commit-node!/write always includes :ROOT, and :ROOT's own
  ;; context now carries a real Meter record as its default -- regression
  ;; coverage for the write/load break that caused (fixed in persist.clj's
  ;; freeze/thaw: Meter/Key records can't survive a bare pr-str/edn/read-
  ;; string round-trip without explicit tagging).
  (parse! "[verse: !Meter:\"7/8(2+2+3)\" c4]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (with-out-str (persist/load (.getPath tmp)))
      (let [meter (r/ctx-value :verse :Meter 0.0)]
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
        s1-repo  @(repo/registry)]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (with-out-str (persist/load (.getPath tmp)))
      (let [new-ids    (parse! "[g4 a4]")                   ;; would also want :s1 if reset
            leaf-shape (fn [container]
                         ;; Leaf/Context both embed atoms (reference-
                         ;; identity, never = across a round-trip even
                         ;; with equal content) -- compare pitches/duration
                         ;; instead of whole records.
                         (mapv (juxt :duration :pitches) (:children container)))]
        (is (not= :s1 (first new-ids)) "auto-id counter continued past what was loaded")
        (is (= (leaf-shape (get s1-repo :s1))
               (leaf-shape (get @(repo/registry) :s1)))
            "the loaded :s1 was not overwritten by the new parse"))
      (finally (io/delete-file tmp true)))))

(deftest write-load-keeps-the-key-a-leaf-was-written-in
  (parse! "[mot: c f]")
  (parse! "[inG: !key:G.major :mot]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (with-out-str (persist/load (.getPath tmp)))
      (is (= [[60] [66]] (map :pitches (filter #(= :note (:kind %)) (ev/events (repo/registry) :inG)))))
      (finally (io/delete-file tmp true)))))

(deftest write-load-keeps-a-reused-container-free-of-its-old-parent
  ;; a container's context and its leaves' baked copies share one atom
  ;; (resolve's chain-links tells a walked ancestor by it); a round trip
  ;; must keep that, or :inner plays at :piece's tempo inside :reuse
  (parse! "[piece: !tempo:60 [inner: c4 d]]")
  (parse! "[reuse: !tempo:240 :inner]")
  (let [tmp   (java.io.File/createTempFile "musics-session" ".edn")
        times #(map (fn [e] (/ (Math/round (* 1000 (:t e))) 1000.0))
                    (filter (fn [e] (= :note (:kind e))) (ev/events (repo/registry) :reuse)))]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (reset! reg/session {:auto-ids {}})
      (with-out-str (persist/load (.getPath tmp)))
      (is (= [0.0 0.25] (times)))
      (finally (io/delete-file tmp true)))))

;; ============================================================
;; persist-session / restore-session -- like write/load, but also
;; round-trips a voice's algo-assignment (review.txt point 11: write/
;; load silently dropped this, so a reloaded piece could sound nothing
;; like what was saved while listening to it)
;; ============================================================

(deftest persist-session-round-trips-a-bare-name-algo-assignment
  (with-fake-receiver
    #(do
       (parse! "[verse: c4 d4]")
       (wall/build-algo! ::persist-bare (fn [nodes _ctx _voice] (reverse nodes)))
       (engine/play-connected :verse :algo ::persist-bare)
       (is (= ::persist-bare (:algo (engine/voice-at [:TA])))
           "sanity: the assignment is really there before we persist it")
       (is (= {[:TA] ::persist-bare} (engine/live-algos))
           "and that's exactly what persist-session itself reads to build its
            snapshot -- engine/live-algos, not the (empty here) prep table")
       (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
         (try
           (with-out-str (persist/persist-session (.getPath tmp)))
           (repo/reset-all!)
           (reset! reg/session {:auto-ids {}})
           (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
             ;; build-algo! is code, always the user's own job to redo --
             ;; matches restore-session's own documented contract.
             (wall/build-algo! ::persist-bare (fn [nodes _ctx _voice] (reverse nodes)))
             (with-out-str (persist/restore-session (.getPath tmp)))
             (is (= ::persist-bare (get (engine/algo-assignments) [:TA]))
                 "the composer-typed Name survives the round-trip -- write/load
                  alone would have silently dropped this entirely"))
           (finally (io/delete-file tmp true)))))))

(deftest persist-session-round-trips-a-registered-algo-assignment
  ;; The stored assignment is just a bare keyword; re-registering the
  ;; name on restore (the user's own job) repopulates *algo-registry*.
  (with-fake-receiver
    (fn []
       (let [build-persist-factory!
             #(wall/build-algo! ::persist-built
                (fn [nodes _ctx _voice] (map (fn [x] (assoc x :marked 5)) nodes)))]
         (parse! "[verse: c4 d4]")
         (build-persist-factory!)
         (engine/play-connected :verse :algo ::persist-built)
         (is (= {[:TA] ::persist-built} (engine/live-algos))
             "sanity: persist-session's own data source has it before we persist")
         (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
           (try
             (with-out-str (persist/persist-session (.getPath tmp)))
             (repo/reset-all!)
             (reset! reg/session {:auto-ids {}})
             (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
               (build-persist-factory!)
               (with-out-str (persist/restore-session (.getPath tmp)))
               (is (= ::persist-built (get (engine/algo-assignments) [:TA]))
                   "just a bare Name, same as any other assignment -- nothing
                    args-shaped left to round-trip")
               (let [resolved (wall/algo (get @(:algo-prepared engine/*engine*) [:TA]))]
                 (is (= [{:marked 5}] (resolved [{}] [] nil))
                     "restored assignment resolves to a REAL, correctly-built
                      wall fn, not just a name that happens to print back correctly")))
             (finally (io/delete-file tmp true))))))))

(deftest persist-session-with-no-engine-yet-persists-an-empty-table
  ;; No (connect)/play call has happened at all -- *engine* genuinely
  ;; nil, the real state a brand-new session starts in. persist-session
  ;; must not throw, and restore-session must come back with nothing to
  ;; replay (rather than, say, NPE-ing on a nil :voices/:algo-prepared atom).
  (parse! "[verse: c4 d4]")
  (let [prior-engine engine/*engine*]
    (try
      (alter-var-root #'engine/*engine* (constantly nil))
      (is (= {} (engine/live-algos))
          "sanity: persist-session's own data source is empty with eng nil, not a throw")
      (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
        (try
          (with-out-str (persist/persist-session (.getPath tmp)))
          (repo/reset-all!)
          (reset! reg/session {:auto-ids {}})
          (with-out-str (persist/restore-session (.getPath tmp)))
          (is (d/container? (repo/lookup :verse))
              "the repo half still round-trips fine with no engine involved")
          (is (= {} (engine/algo-assignments))
              "nothing to restore -- restore-session didn't even need to
               create an engine of its own for an empty table")
          (finally (io/delete-file tmp true))))
      (finally (alter-var-root #'engine/*engine* (constantly prior-engine))))))

(deftest restore-session-creates-an-engine-when-none-exists-if-needed
  (with-fake-receiver
    #(do
       (parse! "[verse: c4 d4]")
       (wall/build-algo! ::persist-needs-engine (fn [nodes _ctx _voice] nodes))
       (engine/play-connected :verse :algo ::persist-needs-engine)
       (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
         (try
           (with-out-str (persist/persist-session (.getPath tmp)))
           (repo/reset-all!)
           (reset! reg/session {:auto-ids {}})
           (let [prior-engine engine/*engine*]
             (try
               (alter-var-root #'engine/*engine* (constantly nil))
               (wall/build-algo! ::persist-needs-engine (fn [nodes _ctx _voice] nodes))
               (with-out-str (persist/restore-session (.getPath tmp)))
               (is (some? engine/*engine*)
                   "restore-session minted its own engine to have somewhere
                    to replay a non-empty algo-assignments table")
               (is (= ::persist-needs-engine (get (engine/algo-assignments) [:TA])))
               (finally (alter-var-root #'engine/*engine* (constantly prior-engine)))))
           (finally (io/delete-file tmp true)))))))

(deftest restore-session-warns-instead-of-throwing-for-a-not-yet-registered-name
  (with-fake-receiver
    #(do
       (parse! "[verse: c4 d4]")
       (wall/build-algo! ::persist-forgotten (fn [nodes _ctx _voice] nodes))
       (engine/play-connected :verse :algo ::persist-forgotten)
       (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
         (try
           (with-out-str (persist/persist-session (.getPath tmp)))
           (repo/reset-all!)
           (reset! reg/session {:auto-ids {}})
           (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
             ;; musics.wall's registry is a process-wide global untouched by
             ;; repo/reset-all! -- unregister explicitly to genuinely
             ;; simulate "not yet re-registered in this fresh process".
             (wall/unregister-algo! ::persist-forgotten)
             ;; :algo-prepared stores just the bare Name -- always,
             ;; whether or not it currently resolves.
             (with-out-str (persist/restore-session (.getPath tmp)))
             (is (= ::persist-forgotten
                    (get @(:algo-prepared engine/*engine*) [:TA]))
                 "restore-session still stores the composer-typed Name as-is,
                  not silently clearing the path back to unassigned")
             (is (nil? (wall/algo (get @(:algo-prepared engine/*engine*) [:TA])))
                 "that Name resolves to nothing yet, so it plays unchanged"))
           (finally (io/delete-file tmp true)))))))

;; ============================================================
;; play-ly-file / play-abc-file -- convert + stage/commit/play in one
;; step, entirely in memory (never write a sibling .mus file -- see
;; ly-to-mus/abc-to-mus for that)
;; ============================================================

