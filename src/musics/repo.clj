(ns musics.repo
  "An id-addressed store for immutable, committed musical material.

   Design:
   - Every node lives under an id in `registry`, a flat {id -> node}
     map -- no history retained. Once a commit lands, only its CURRENT
     value is kept; an earlier value under the same id is simply gone
     the moment a newer one replaces it (see doc/md/decisions.md for why:
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
     musics.repo/play-tx): once every commit already keeps playback
     current automatically, a pointer that can only ever equal 'whatever
     `registry` itself holds' isn't decoupling anything -- see
     `registry` below, the thin accessor that replaced it.

   registry itself lives in musics.registries (a leaf namespace collecting
   this project's mutable global state, so it has one home instead of
   being scattered) -- this ns requires it and reads/writes
   musics.registries/*repo-registry* exactly where it used to read/write
   its own local atom."
  (:require [clojure.string :as str]
            [musics.registries :as reg]))

;; ---------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------

(defn current
  "The current value of `id`, or nil if it doesn't exist (yet, or ever)."
  [id]
  (get @reg/*repo-registry* id))

(defn registry
  "The live {id -> node} registry atom itself -- what a brand-new
   voice's snapshot is taken from (musics.engine/engine's :repo). A thin FUNCTION, not a bare var alias,
   specifically so it still re-resolves *repo-registry*'s CURRENT
   dynamic binding at the moment it's called -- a bare `(def registry
   reg/*repo-registry*)` would instead freeze onto whatever the ROOT
   binding was at namespace-load time, silently ignoring a test's own
   `binding` (see musics.registries' own ns docstring for the fuller
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
   alongside, a direct (musics.registries/reset-all!) call, which also
   wipes musics.wall/core.conductor/core.assist's own state; that wider
   reset is musics.mu.store/reset-all!'s job, not this fn's."
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

(defn lookup
  "The node a handle names: a keyword or string id (looked up), a map
   (a node already), nil for nil."
  [x]
  (cond
    (nil? x)     nil
    (keyword? x) (current x)
    (string? x)  (current (keyword x))
    (map? x)     x
    :else        (throw (ex-info (str "Cannot resolve: " (pr-str x)) {:arg x}))))

(defn ids
  "Every stored id but :ROOT, sorted."
  []
  (sort (remove #{:ROOT} (keys @(registry)))))

(defn root-children
  "The ids of :ROOT's own children, in the order they were read; an
   inline child without an id shows as nil."
  []
  (mapv (fn [child] (if (keyword? child) child (:id child)))
        (:children (current :ROOT))))

(defn children
  "A container's children (any handle lookup takes), ids looked up."
  [x]
  (let [view @(registry)
        c    (lookup x)]
    (when (and (map? c) (contains? c :children))
      (mapv (fn [child] (if (keyword? child) (get view child) child))
            (:children c)))))

(defn usages
  "Every id whose content references id directly as one of its children
   -- what else changes when id is stored again."
  [id]
  (into #{}
        (keep (fn [[candidate-id node]]
                (when (and (map? node) (some #{id} (:children node)))
                  candidate-id)))
        @(registry)))

;; ---------------------------------------------------------------------
;; Housekeeping: references, forget, rename
;; ---------------------------------------------------------------------

(defn- rename-text
  "Text that names id -- a reference written :old, an inline context
   defined ^{ old: …, a recipe @( old: … -- with new instead; else as it
   is."
  [text old new]
  (let [o (name old) n (name new)]
    (cond
      (= text (str ":" o))                  (str ":" n)
      (str/starts-with? text (str "^{ " o ": ")) (str "^{ " n ": " (subs text (+ 4 (count o) 1)))
      (str/starts-with? text (str "@( " o ": ")) (str "@( " n ": " (subs text (+ 4 (count o) 1)))
      :else text)))

(defn- rewrite
  "x with every reference to old made one to new: an id among a
   container's :children (nested ones too: a repeat's body and endings),
   an override marker's :ref and :raw, a node's own :id, a recipe's
   :text. A context's atoms are left as they are."
  [x old new]
  (cond
    (map? x)
    (reduce-kv (fn [m k v]
                 (assoc m k (cond
                              (= k :context)                         v
                              (and (= k :children) (sequential? v))  (mapv #(if (= % old) new (rewrite % old new)) v)
                              (and (#{:ref :id} k) (= v old))        new
                              (and (#{:raw :text} k) (string? v))    (rename-text v old new)
                              :else                                  (rewrite v old new))))
               x x)
    (vector? x) (mapv #(rewrite % old new) x)
    (seq? x)    (doall (map #(rewrite % old new) x))
    :else       x))

(defn referrers
  "Every id but :ROOT whose content refers to id anywhere in it -- a
   child, a repeat's body, an override marker -- not only directly as one
   of its children, which is what usages finds."
  [id]
  (into #{}
        (keep (fn [[k node]]
                (when (and (not= k :ROOT) (not= k id) (map? node)
                           (not= node (rewrite node id ::none)))
                  k)))
        @(registry)))

(defn forget!
  "Take id out of the store, and out of :ROOT's children. Refused while
   another stored id refers to it (forget those first, or store them
   again without it); :ROOT itself can't go. A voice already playing it
   plays on, from its own snapshot."
  [id]
  (when (= id :ROOT) (throw (ex-info ":ROOT is the store's root and stays" {:id id})))
  (when-not (current id) (throw (ex-info (str id " is not stored") {:id id})))
  (when-let [used (seq (sort (referrers id)))]
    (throw (ex-info (str id " is still used by " (str/join " " used)) {:id id :used used})))
  (swap! reg/*repo-registry*
         (fn [r] (-> (dissoc r id)
                     (update :ROOT (fn [root] (update root :children (fn [cs] (vec (remove #{id} cs)))))))))
  nil)

(defn rename!
  "Rename old to new everywhere in one swap: the node, :ROOT's children,
   every container that holds it, every override marker and inline
   context that names it, a recipe's own text. Refused when old is not
   stored, new is, or new is no name (a letter, then letters, digits and
   _). A voice playing it keeps its old name."
  [old new]
  (when (= old :ROOT) (throw (ex-info ":ROOT is the store's root and keeps its name" {:id old})))
  (when-not (current old) (throw (ex-info (str old " is not stored") {:id old})))
  (when-not (and (keyword? new) (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" (name new)))
    (throw (ex-info (str "needs a new name of letters, digits and _ starting with a letter, got " (pr-str new)) {:id new})))
  (when (current new) (throw (ex-info (str new " is stored already") {:id new})))
  (swap! reg/*repo-registry*
         (fn [r] (reduce-kv (fn [m k node] (assoc m (if (= k old) new k) (rewrite node old new))) {} r)))
  nil)

