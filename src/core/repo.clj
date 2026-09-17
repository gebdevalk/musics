(ns core.repo
  "A versioned, id-addressed node store for immutable musical material.

   Design:
   - Every node lives under an id in `registry`, as a sorted-map of
     tx -> node, so history is queryable and `as-of` lookups are
     O(log n) via subseq rather than a linear scan.
   - A commit is always immediate, never staged: `commit-node!` (one
     id) and `commit-many!` (several ids at once) each mint a new tx
     and apply it directly, in one swap! -- there is no intermediate
     staged, not-yet-visible state to open, hold, or abort. If a
     commit turns out wrong, the fix is a NEW commit, not a rollback of
     this one (see doc/decisions.md for why the earlier two-phase
     stage!/commit-staged! design -- which DID have that intermediate
     state -- was dropped).
   - `commit-many!` still applies every id in its own batch under ONE
     shared tx, in ONE swap!, so a read pinned to that tx is guaranteed
     a mutually consistent view of the whole batch -- it will never see
     half of it applied and half not. That atomicity guarantee is the
     one thing the old staging design's two-phase dance was ACTUALLY
     protecting; a single immediate swap! gives it for free, without
     needing a separate staging area to get there.

   registry/tx-counter themselves now live in
   core.registries (a leaf namespace collecting this project's mutable
   global state, so it has one home instead of being scattered) --
   this ns requires it and reads/writes core.registries/*repo-registry*
   etc. exactly where it used to read/write its own local atoms; only
   play-tx (below) stayed here, since it's referenced by value
   throughout the codebase rather than only through this ns's own
   functions -- see core.registries' own docstring for the full
   reasoning."
  (:require [core.registries :as reg])
  (:import (clojure.lang Counted ILookup MapEntry Seqable)))

;; ---------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------

(defn- as-of-in
  "Like as-of, but against an already-deref'd registry snapshot --
   for a caller (RepoView's own seq, below) that needs many id lookups
   against ONE consistent snapshot without re-deref'ing
   core.registries/*repo-registry* once per id."
  [registry id tx]
  (when-let [versions (get registry id)]
    (when-let [e (first (rsubseq versions <= tx))]
      (val e))))

(defn as-of
  "The value of `id` as of `tx` (inclusive), or nil if it didn't exist yet."
  [id tx]
  (as-of-in @reg/*repo-registry* id tx))

(defn latest-tx
  "The most recently committed tx."
  []
  @reg/*repo-tx-counter*)

(defn current
  "The value of `id` as of the latest committed tx."
  [id]
  (as-of id @reg/*repo-tx-counter*))

(defn history
  "All [tx node] pairs ever committed for `id`, oldest first."
  [id]
  (seq (get @reg/*repo-registry* id)))

;; ---------------------------------------------------------------------
;; Read-only, tx-pinned map view
;; ---------------------------------------------------------------------

(deftype RepoView [tx]
  ILookup
  (valAt [_ id] (as-of id tx))
  (valAt [_ id not-found]
    (let [v (as-of id tx)] (if (nil? v) not-found v)))

  Seqable
  (seq [_]
    ;; One deref of *repo-registry* for the whole walk, not one for the
    ;; key list PLUS one more per id via as-of -- as-of-in reuses this
    ;; same snapshot for every id instead. Still O(every id ever
    ;; registered in this process), not just what's visible as of tx --
    ;; a real, unavoidable-without-a-separate-index cost for a long
    ;; session, but a REPL inspection helper (musics.core/ids, the only
    ;; real caller) doesn't need that index badly enough to justify
    ;; building and maintaining one; see review.txt point 15.
    (let [registry @reg/*repo-registry*]
      (seq (keep (fn [id] (when-let [v (as-of-in registry id tx)]
                            (MapEntry. id v)))
                 (keys registry)))))

  Counted
  (count [this] (count (seq this))))

(defn view
  "A read-only, map-like {id -> node} view of the store as of `tx`:
   get/keys/seq/count all work normally (backed by as-of, nothing pre-
   materialized). The read-only counterpart to a plain repo map, for
   anything that only needs to look things up -- inspection, live
   playback -- rather than build one up (flat-core-builder still needs a
   genuine mutable-via-assoc map while parsing, see musics.core/parse)."
  [tx]
  (->RepoView tx))

;; ---------------------------------------------------------------------
;; Playback read pointer
;; ---------------------------------------------------------------------

;The tx live playback reads through. ALWAYS kept at the latest commit --
;         commit-node!/commit-many!/seed! all advance it themselves, as
;         their own last step, so a fresh (play ...) call always starts
;         current with zero extra action needed (see doc/decisions.md
;         for why the earlier "committing never moves this, call
;         play-tx!/play-latest! yourself" design -- which let you pin
;         playback to an arbitrary, possibly-stale tx -- was dropped).
;         play-latest! still exists and is still safe to call -- it's
;         just always a no-op now, since play-tx already IS latest the
;         instant anything commits.
;
;         Only an ALREADY-RUNNING voice is ever insulated from this: its
;         own :view was captured once, at birth/fork, and only moves via
;         an explicit schedule-tx! redirect -- play-tx auto-advancing
;         only affects what a BRAND NEW (play ...) call starts at, never
;         anything already playing. See core.async-engine's own ns
;         docstring for the fuller reasoning.
;
;         ^:dynamic (not moved into core.registries -- see that ns's own
;         docstring for why) so a test can (binding [play-tx (atom N)] ...)
;         a private instance the same way core.registries' own vars allow,
;         without changing this var's name or any of its ~60 by-value call
;         sites throughout the codebase (core.async-engine/engine's own
;         :repo argument is normally handed this atom directly).
(defonce ^:dynamic play-tx (atom 0))

(defn play-latest!
  "Point live playback at whatever is currently the latest committed tx
   -- a no-op in practice now (see play-tx's own docstring: committing
   already keeps it there automatically), kept as an explicit, safe-to-
   call checkpoint rather than removed outright."
  []
  (clojure.core/reset! play-tx (latest-tx))
  nil)

;; ---------------------------------------------------------------------
;; Direct commit (single-node, immediate)
;; ---------------------------------------------------------------------

(defn commit-node!
  "Commit `node` under `id` immediately, minting a new tx, and advances
   play-tx to it -- committing always keeps playback pointed at current
   now, see play-tx's own docstring above. Use for a single-id write;
   for several ids that need to become visible together, atomically, use
   commit-many! below instead."
  [id node]
  (let [tx (swap! reg/*repo-tx-counter* inc)]
    (swap! reg/*repo-registry* update id
           (fn [versions] (assoc (or versions (sorted-map)) tx node)))
    (clojure.core/reset! play-tx tx)
    tx))

;; ---------------------------------------------------------------------
;; Diffing (pure -- no atoms touched, independently testable)
;; ---------------------------------------------------------------------

(defn changed-ids
  "The ids in `new-repo` whose node differs from `old-repo`'s (new ids
   included -- get returns nil for those, which never = a real node).
   Pure map comparison; doesn't care where either map came from or
   whether either is staged, committed, or a scratch build in progress."
  [old-repo new-repo]
  (into #{}
        (keep (fn [[id node]] (when (not= node (get old-repo id)) id)))
        new-repo))

;; ---------------------------------------------------------------------
;; Direct commit (multi-node, immediate, atomic)
;; ---------------------------------------------------------------------

(defn commit-many!
  "Commit every [id node] pair in `edits` immediately, as ONE atomic
   transaction: mints a single new tx and applies every edit under it
   in one swap! -- no staging, no separate open/close step. A read
   pinned to the returned tx sees every id in this batch consistently,
   never half applied. Advances play-tx to it too, same as commit-node!
   -- see play-tx's own docstring. Returns the new tx, or nil if `edits`
   is empty."
  [edits]
  (when (seq edits)
    (let [tx (swap! reg/*repo-tx-counter* inc)]
      (swap! reg/*repo-registry*
             (fn [reg]
               (reduce-kv
                 (fn [reg id node]
                   (update reg id
                           (fn [versions]
                             (assoc (or versions (sorted-map)) tx node))))
                 reg
                 edits)))
      (clojure.core/reset! play-tx tx)
      tx)))

;; ---------------------------------------------------------------------
;; Whole-store reset / bulk seed
;; ---------------------------------------------------------------------

(defn reset-all!
  "Discard all committed history and restart the tx counter (and the
   playback pointer) at 0. For starting a genuinely fresh store (e.g. a
   REPL session reset), not for ordinary edits. Covers this ns's own
   state (registry/tx-counter, via core.registries -- redundant with,
   but harmless alongside, a direct (core.registries/reset-all!) call)
   plus play-tx, which only this ns can reset -- see core.registries'
   own docstring for why."
  []
  (clojure.core/reset! reg/*repo-tx-counter* 0)
  (clojure.core/reset! reg/*repo-registry* {})
  (clojure.core/reset! play-tx 0)
  nil)

(defn seed!
  "Bulk-load `id->node` as a single, brand-new baseline commit, discarding
   any prior history first, and advances play-tx to it -- same 'commit
   always keeps playback current' guarantee commit-node!/commit-many!
   have (see play-tx's own docstring). For establishing history from a
   source that didn't go through an ordinary commit itself (e.g. loading
   a saved session), so a later commit-node!/commit-many! against this
   baseline has real history to build on instead of silently overwriting
   it."
  [id->node]
  (reset-all!)
  (let [tx (swap! reg/*repo-tx-counter* inc)]
    (clojure.core/reset! reg/*repo-registry*
                          (into {} (map (fn [[id node]] [id (sorted-map tx node)])) id->node))
    (clojure.core/reset! play-tx tx)
    tx))
