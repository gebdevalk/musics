(ns core.repo
  "An id-addressed store for immutable, committed musical material.

   Design:
   - Every node lives under an id in `registry`, a flat {id -> node}
     map -- no history retained. Once a commit lands, only its CURRENT
     value is kept; an earlier value under the same id is simply gone
     the moment a newer one replaces it (see doc/decisions.md for why:
     nothing in this project ever reads a past state anymore, only
     current, so retaining one was pure cost with no reader left to
     serve -- if you want to keep an old version yourself, `(def v0
     (sq :id))` captures a frozen, detached snapshot of it, same as it
     always has).
   - A commit is always immediate, never staged: `commit-node!` (one
     id) and `commit-many!` (several ids at once) each apply directly,
     in one swap! -- there is no intermediate staged, not-yet-visible
     state to open, hold, or abort. If a commit turns out wrong, the fix
     is a NEW commit, not a rollback of this one.
   - `commit-many!` still applies every id in its own batch in ONE
     swap!, so nothing ever sees half a batch applied and half not --
     that atomicity guarantee is the one thing the old staging design's
     two-phase dance was ACTUALLY protecting; a single immediate swap!
     gives it for free, without needing a separate staging area to get
     there.
   - There is no separate playback pointer anymore either (the old
     core.repo/play-tx): once every commit already keeps playback
     current automatically, a pointer that can only ever equal 'whatever
     `registry` itself holds' isn't decoupling anything -- see
     `registry` below, the thin accessor that replaced it.

   registry itself lives in core.registries (a leaf namespace collecting
   this project's mutable global state, so it has one home instead of
   being scattered) -- this ns requires it and reads/writes
   core.registries/*repo-registry* exactly where it used to read/write
   its own local atom."
  (:require [core.registries :as reg]))

;; ---------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------

(defn current
  "The current value of `id`, or nil if it doesn't exist (yet, or ever)."
  [id]
  (get @reg/*repo-registry* id))

(defn registry
  "The live {id -> node} registry atom itself -- what a brand-new
   voice's own :view is captured from (see core.async-engine/fresh-view,
   engine's own :repo argument). A thin FUNCTION, not a bare var alias,
   specifically so it still re-resolves *repo-registry*'s CURRENT
   dynamic binding at the moment it's called -- a bare `(def registry
   reg/*repo-registry*)` would instead freeze onto whatever the ROOT
   binding was at namespace-load time, silently ignoring a test's own
   `binding` (see core.registries' own ns docstring for the fuller
   reasoning, and why this replaced the old play-tx pointer atom rather
   than keeping a second atom in sync with this one)."
  []
  reg/*repo-registry*)

;; ---------------------------------------------------------------------
;; Direct commit (single-node, immediate)
;; ---------------------------------------------------------------------

(defn commit-node!
  "Commit `node` under `id` immediately -- a plain assoc into the live
   registry, replacing whatever `id` held before, if anything. Use for
   a single-id write; for several ids that need to become visible
   together, atomically, use commit-many! below instead."
  [id node]
  (swap! reg/*repo-registry* assoc id node)
  nil)

;; ---------------------------------------------------------------------
;; Diffing (pure -- no atoms touched, independently testable)
;; ---------------------------------------------------------------------

(defn changed-ids
  "The ids in `new-repo` whose node differs from `old-repo`'s (new ids
   included -- get returns nil for those, which never = a real node).
   Pure map comparison; doesn't care where either map came from or
   whether either is committed or a scratch build in progress."
  [old-repo new-repo]
  (into #{}
        (keep (fn [[id node]] (when (not= node (get old-repo id)) id)))
        new-repo))

;; ---------------------------------------------------------------------
;; Direct commit (multi-node, immediate, atomic)
;; ---------------------------------------------------------------------

(defn commit-many!
  "Commit every [id node] pair in `edits` immediately, as ONE atomic
   swap! -- no staging, no separate open/close step. Nothing ever sees
   half of this batch applied and half not. No-op if `edits` is empty."
  [edits]
  (when (seq edits)
    (swap! reg/*repo-registry* merge edits)
    nil))

;; ---------------------------------------------------------------------
;; Whole-store reset / bulk seed
;; ---------------------------------------------------------------------

(defn reset-all!
  "Discard all committed material. For starting a genuinely fresh store
   (e.g. a REPL session reset), not for ordinary edits. Covers ONLY this
   ns's own state (the repo registry) -- redundant with, but harmless
   alongside, a direct (core.registries/reset-all!) call, which also
   wipes core.wall/core.conductor/core.adviser's own state; that wider
   reset is musics.core/reset's job, not this fn's (see its own
   docstring for why both calls are made there)."
  []
  (clojure.core/reset! reg/*repo-registry* {})
  nil)

(defn seed!
  "Bulk-load `id->node` as a single, brand-new baseline, discarding any
   prior committed material first. For establishing content from a
   source that didn't go through an ordinary commit itself (e.g.
   loading a saved session)."
  [id->node]
  (reset-all!)
  (clojure.core/reset! reg/*repo-registry* id->node)
  nil)
