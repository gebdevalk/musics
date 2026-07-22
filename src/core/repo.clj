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
     it will never see half of a batch applied and half not.")

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

;; ---------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------

(defn as-of
  "The value of `id` as of `tx` (inclusive), or nil if it didn't exist yet."
  [id tx]
  (when-let [versions (get @registry id)]
    (val (first (rsubseq versions <= tx)))))

(defn current
  "The value of `id` as of the latest committed tx."
  [id]
  (as-of id @tx-counter))

(defn history
  "All [tx node] pairs ever committed for `id`, oldest first."
  [id]
  (seq (get @registry id)))

(defn snapshot
  "A plain map {id -> value} of every id's state as of `tx`.
   Useful for resolving/rendering a whole phrase against one pinned tx."
  ([] (snapshot @tx-counter))
  ([tx]
   (into {}
         (keep (fn [[id _versions]]
                 (when-let [v (as-of id tx)]
                   [id v])))
         @registry)))

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
;; Staged transactions
;; ---------------------------------------------------------------------

(defn begin-staged-tx!
  "Open a new staging area and return its sid. Nothing staged under
   this sid is visible to readers until `commit-staged!` is called on it."
  []
  (let [sid (gensym "stx")]
    (swap! staging assoc sid {})
    sid))

(defn stage!
  "Record a pending write of `node` under `id`, inside staging area `sid`.
   Overwrites any earlier staged value for the same id in this sid.
   Invisible to `as-of`/`current` until commit-staged! runs."
  [sid id node]
  (swap! staging update sid assoc id node)
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
;; Scheduling future commits against musical position
;; ---------------------------------------------------------------------

;bar-or-position -> sid.
;          A small table letting a scheduler resolve 'we reached bar 8' into
;          'commit-staged! on the sid that was prepared for bar 8', without
;          ever having to guess a future tx number in advance.
(defonce scheduled-commits (atom {}))

(defn schedule-at!
  "Associate a prepared (but not yet committed) sid with a musical
   position, e.g. a bar number. Call `run-scheduled!` once playback
   reaches that position."
  [position sid]
  (swap! scheduled-commits assoc position sid)
  nil)

(defn run-scheduled!
  "If a staged sid was scheduled for `position`, commit it now and
   return the new tx. Returns nil if nothing was scheduled there."
  [position]
  (when-let [sid (get @scheduled-commits position)]
    (swap! scheduled-commits dissoc position)
    (commit-staged! sid)))

;; ---------------------------------------------------------------------
;; Whole-store reset / bulk seed
;; ---------------------------------------------------------------------

(defn reset-all!
  "Discard all committed history, staged edits, and scheduled commits, and
   restart the tx counter at 0. For starting a genuinely fresh store (e.g.
   a REPL session reset), not for ordinary edits."
  []
  (clojure.core/reset! tx-counter 0)
  (clojure.core/reset! registry {})
  (clojure.core/reset! staging {})
  (clojure.core/reset! scheduled-commits {})
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