(ns core.repo
  "A versioned, id-addressed node store for immutable musical material.

   Design:
   - Every node lives under an id in `registry`, as a sorted-map of
     tx -> node, so history is queryable and `as-of` lookups are
     O(log n) via subseq rather than a linear scan.
   - Mutations are never applied directly to the registry. They are
     first accumulated in a `staging` area under a staging-id (sid),
     invisible to all readers, and only become visible atomically
     when `commit-staged!` mints a single new tx and folds every
     staged node in under it.
   - A read pinned to a tx (e.g. by the playback thread at the start
     of a phrase) is therefore guaranteed a mutually consistent view:
     it will never see half of a batch applied and half not.

   registry/staging/tx-counter/sid-counter themselves now live in
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
    ;; session, but a REPL inspection helper (musics.clj/ids, the only
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
   genuine mutable-via-assoc map while parsing, see musics.clj/parse)."
  [tx]
  (->RepoView tx))

;; ---------------------------------------------------------------------
;; Direct commit (single-node, immediate)
;; ---------------------------------------------------------------------

(defn commit-node!
  "Commit `node` under `id` immediately, minting a new tx.
   Use for simple one-off writes; for grouped/batched or future-scheduled
   writes, use the staging API below instead."
  [id node]
  (let [tx (swap! reg/*repo-tx-counter* inc)]
    (swap! reg/*repo-registry* update id
           (fn [versions] (assoc (or versions (sorted-map)) tx node)))
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
;; Staged transactions
;; ---------------------------------------------------------------------

(defn begin-staged-tx!
  "Open a new staging area and return its sid. Nothing staged under
   this sid is visible to readers until `commit-staged!` is called on it."
  []
  (let [sid (keyword (str "sid" (swap! reg/*repo-sid-counter* inc)))]
    (swap! reg/*repo-staging* assoc sid {})
    sid))

(defn stage!
  "Record a pending write of `node` under `id`, inside staging area `sid`.
   Overwrites any earlier staged value for the same id in this sid.
   Invisible to `as-of`/`current` until commit-staged! runs."
  [sid id node]
  (swap! reg/*repo-staging* update sid assoc id node)
  nil)

(defn stage-many!
  "Record a pending write for every [id node] pair in `edits`, inside
   staging area `sid` -- one swap! instead of one per id. Same effect as
   calling stage! in a loop; the caller doesn't drive the loop itself."
  [sid edits]
  (swap! reg/*repo-staging* update sid merge edits)
  nil)

(defn staged-edits
  "The pending {id -> node} map for `sid`, or nil if unknown."
  [sid]
  (get @reg/*repo-staging* sid))

(defn abort-staged!
  "Discard all pending edits under `sid` without ever making them visible."
  [sid]
  (swap! reg/*repo-staging* dissoc sid)
  nil)

(defn commit-staged!
  "Fold every edit staged under `sid` into the registry as one atomic
   transaction: mints a single new tx and stamps every staged node with
   it in one swap!, then clears the staging area. Returns the new tx.
   No-op (returns nil) if `sid` has no staged edits."
  [sid]
  (when-let [edits (get @reg/*repo-staging* sid)]
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
        (swap! reg/*repo-staging* dissoc sid)
        tx))))

;; ---------------------------------------------------------------------
;; Playback read pointer
;; ---------------------------------------------------------------------

;The tx live playback reads through. Deliberately decoupled from
;         committing -- commit-staged!/commit-node! never move this on their
;         own. Call play-tx!/play-latest! to explicitly repoint playback once
;         a batch of edits is ready to go live; takes effect at the next node
;         the reading traversal visits (no phrase/bar-boundary awareness yet).
;
;         ^:dynamic (not moved into core.registries -- see that ns's own
;         docstring for why) so a test can (binding [play-tx (atom N)] ...)
;         a private instance the same way core.registries' own vars allow,
;         without changing this var's name or any of its ~60 by-value call
;         sites throughout the codebase (core.async-engine/engine's own
;         :repo argument is normally handed this atom directly).
(defonce ^:dynamic play-tx (atom 0))

(defn play-tx!
  "Point live playback at `tx` explicitly."
  [tx]
  (reset! play-tx tx)
  nil)

(defn play-latest!
  "Point live playback at whatever is currently the latest committed tx."
  []
  (play-tx! (latest-tx)))

;; ---------------------------------------------------------------------
;; Whole-store reset / bulk seed
;; ---------------------------------------------------------------------

(defn reset-all!
  "Discard all committed history and staged edits, and restart the tx
   counter (and the playback pointer) at 0. For starting a genuinely
   fresh store (e.g. a REPL session reset), not for ordinary edits.
   Covers this ns's own state (registry/staging/tx-counter/sid-counter,
   via core.registries -- redundant with, but harmless alongside, a
   direct (core.registries/reset-all!) call) plus play-tx, which only
   this ns can reset -- see core.registries' own docstring for why."
  []
  (clojure.core/reset! reg/*repo-tx-counter* 0)
  (clojure.core/reset! reg/*repo-sid-counter* 0)
  (clojure.core/reset! reg/*repo-registry* {})
  (clojure.core/reset! reg/*repo-staging* {})
  (clojure.core/reset! play-tx 0)
  nil)

(defn seed!
  "Bulk-load `id->node` as a single, brand-new baseline commit, discarding
   any prior history first. For establishing history from a source that
   didn't go through the staged API itself (e.g. loading a saved session),
   so a later commit-staged! against this baseline has real history to
   build on instead of silently overwriting it."
  [id->node]
  (reset-all!)
  (let [tx (swap! reg/*repo-tx-counter* inc)]
    (clojure.core/reset! reg/*repo-registry*
                          (into {} (map (fn [[id node]] [id (sorted-map tx node)])) id->node))
    tx))
