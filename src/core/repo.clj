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
     it will never see half of a batch applied and half not."
  (:import (clojure.lang Counted ILookup MapEntry Seqable)))

;; ---------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------

;Monotonically increasing transaction counter. Every commit mints
;         exactly one new tx, shared by every node changed in that commit.
(defonce tx-counter (atom 0))

;id -> sorted-map of tx -> node.
; The *only* place committed, visible state lives.
(defonce registry (atom {}))

;sid -> {id -> node}.
;          Working sets for in-progress, not-yet-visible edits. A sid groups
;          an arbitrary number of `stage!` calls into one eventual commit.
(defonce staging (atom {}))

;Monotonically increasing staging-id counter, mirroring tx-counter --
;         sids are short and ordered (:sid1, :sid2, ...), same convention as
;         flat-core-builder's auto-ids, rather than an opaque gensym.
(defonce sid-counter (atom 0))

;; ---------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------

(defn as-of
  "The value of `id` as of `tx` (inclusive), or nil if it didn't exist yet."
  [id tx]
  (when-let [versions (get @registry id)]
    (when-let [e (first (rsubseq versions <= tx))]
      (val e))))

(defn latest-tx
  "The most recently committed tx."
  []
  @tx-counter)

(defn current
  "The value of `id` as of the latest committed tx."
  [id]
  (as-of id @tx-counter))

(defn history
  "All [tx node] pairs ever committed for `id`, oldest first."
  [id]
  (seq (get @registry id)))

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
    (seq (keep (fn [id] (when-let [v (as-of id tx)]
                          (MapEntry. id v)))
               (keys @registry))))

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
  (let [tx (swap! tx-counter inc)]
    (swap! registry update id
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
  (let [sid (keyword (str "sid" (swap! sid-counter inc)))]
    (swap! staging assoc sid {})
    sid))

(defn stage!
  "Record a pending write of `node` under `id`, inside staging area `sid`.
   Overwrites any earlier staged value for the same id in this sid.
   Invisible to `as-of`/`current` until commit-staged! runs."
  [sid id node]
  (swap! staging update sid assoc id node)
  nil)

(defn stage-many!
  "Record a pending write for every [id node] pair in `edits`, inside
   staging area `sid` -- one swap! instead of one per id. Same effect as
   calling stage! in a loop; the caller doesn't drive the loop itself."
  [sid edits]
  (swap! staging update sid merge edits)
  nil)

(defn staged-edits
  "The pending {id -> node} map for `sid`, or nil if unknown."
  [sid]
  (get @staging sid))

(defn abort-staged!
  "Discard all pending edits under `sid` without ever making them visible."
  [sid]
  (swap! staging dissoc sid)
  nil)

(defn commit-staged!
  "Fold every edit staged under `sid` into the registry as one atomic
   transaction: mints a single new tx and stamps every staged node with
   it in one swap!, then clears the staging area. Returns the new tx.
   No-op (returns nil) if `sid` has no staged edits."
  [sid]
  (when-let [edits (get @staging sid)]
    (when (seq edits)
      (let [tx (swap! tx-counter inc)]
        (swap! registry
               (fn [reg]
                 (reduce-kv
                   (fn [reg id node]
                     (update reg id
                             (fn [versions]
                               (assoc (or versions (sorted-map)) tx node))))
                   reg
                   edits)))
        (swap! staging dissoc sid)
        tx))))

;; ---------------------------------------------------------------------
;; Playback read pointer
;; ---------------------------------------------------------------------

;The tx live playback reads through. Deliberately decoupled from
;         committing -- commit-staged!/commit-node! never move this on their
;         own. Call play-tx!/play-latest! to explicitly repoint playback once
;         a batch of edits is ready to go live; takes effect at the next node
;         the reading traversal visits (no phrase/bar-boundary awareness yet).
(defonce play-tx (atom 0))

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
   fresh store (e.g. a REPL session reset), not for ordinary edits."
  []
  (clojure.core/reset! tx-counter 0)
  (clojure.core/reset! sid-counter 0)
  (clojure.core/reset! registry {})
  (clojure.core/reset! staging {})
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
  (let [tx (swap! tx-counter inc)]
    (clojure.core/reset! registry
                          (into {} (map (fn [[id node]] [id (sorted-map tx node)])) id->node))
    tx))