(ns musics.registries
  "The mutable global state this project's foundational namespaces --
   musics.repo, musics.wall, musics.conductor -- actually hold, collected in
   one file so 'what mutable state does this whole system have' has one
   answer instead of being scattered across three namespaces (and, in
   practice, easy to lose track of). This file only declares WHERE each
   piece of state lives and how to reset it -- what each one MEANS and
   the invariants its owning namespace enforces are documented there,
   not duplicated here.

   THE INVENTORY -- every var this file declares, one line each (full
   detail is on each var's own docstring below):

     Var                          Owner            What it holds
     ----------------------------  ---------------  --------------------------------------------------
     *repo-registry*               musics.repo        id -> node -- the only place committed material lives
     *algo-registry*               musics.wall        name -> hot-swappable wall fn (what a voice's own :algo resolves against)
     *conductor-action-registry*   musics.conductor   id -> f, a parked toolbox of reusable actions
     *conductor-schedule*          musics.conductor   [id phase] -> action-id, one-shot (consumed on trigger)
     *conductor-repeating*         musics.conductor   [id phase] -> action-id, NOT consumed on trigger
     *activity-log*                musics.assist      bounded log of the actions taken (its history facts)

   That's 6, not more -- two other tables sometimes get lumped in with
   this list (e.g. in an earlier self-audit) but genuinely aren't the
   same kind of thing: `musics.engine`'s `:algo-prepared` (path ->
   name, consulted only at voice-mint time) lives on each ENGINE
   INSTANCE, not as a var here, by the same 'instance, not global'
   discipline `:voices`/`:channel-claims`/etc. already follow -- see
   that ns's own docstring.

   Deliberately a LEAF namespace: requires nothing else in this project,
   so musics.repo/core.wall/core.conductor (each already documented, in
   its own ns docstring, as depending on nothing above it) can require
   this without inverting that layering. Putting this state directly in
   musics.core/session instead was considered and rejected for exactly
   that reason: musics.core sits at the TOP of the dependency graph,
   requiring all three of them -- none of them can require it back
   without creating a cycle.

   Every var here is ^:dynamic specifically so a test can (binding
   [musics.registries/*algo-registry* (atom {}) ...] ...) a completely
   fresh, isolated instance of any one of them -- or all of them at
   once -- for just its own extent, auto-restored afterward even if the
   test throws. This is optional, not a replacement for the existing
   pattern: (reset! musics.registries/*algo-registry* {}) still works
   exactly like resetting any other atom, so existing manual-reset test
   fixtures keep working unchanged, just pointed at the new location.
   defonce still protects the root binding across a REPL reload, same
   guarantee every var here had before this file existed.

   *repo-registry* used to have a sibling here, musics.repo/play-tx -- a
   SEPARATE pointer atom tracking which tx playback should read through,
   decoupled from the registry itself so playback could be pinned to an
   arbitrary, possibly-non-latest commit. Removed entirely (not just
   moved) once that decoupling stopped being possible to exploit: once
   committing always kept play-tx at the latest commit automatically
   (an earlier step in the same redesign), a SEPARATE atom that could
   only ever equal 'whatever *repo-registry* itself currently holds' was
   proven, structurally, to never do any work a caller couldn't get by
   reading *repo-registry* directly -- so there was nothing left for a
   second atom to decouple. musics.engine/engine's own :repo
   argument (what a brand-new voice's snapshot is taken from) is
   handed musics.repo/registry's return value now -- a thin accessor
   function, not a bare var alias, specifically so it still re-resolves
   *repo-registry*'s CURRENT dynamic binding at the moment it's called
   (a bare `(def registry reg/*repo-registry*)` would instead freeze
   onto the ROOT binding at namespace-load time, silently ignoring any
   later test `binding` -- confirmed as the actual reason a function was
   needed here, not assumed). See doc/decisions.md for the fuller
   history."
  )

;; ---------------------------------------------------------------------
;; musics.repo's own bookkeeping
;; ---------------------------------------------------------------------

(defonce ^{:doc "id -> node. The *only* place committed, visible
material lives -- a flat map, no history retained (see musics.repo's own
ns docstring for why: nothing ever reads a past state anymore, only
current, so there's nothing to index by)."}
  ^:dynamic *repo-registry* (atom {}))

;; ---------------------------------------------------------------------
;; musics.wall's registry
;; ---------------------------------------------------------------------

(defonce ^{:doc "name -> {:fn f :doc doc ...}, f a wall fn. What a
voice's own :algo name resolves against, read FRESH by musics.wall/algo
on every node a voice visits -- so re-registering a name is the whole
hot-swap: every voice following it changes on its next note. See
musics.wall's ns docstring."}
  ^:dynamic *algo-registry* (atom {}))

;; ---------------------------------------------------------------------
;; musics.conductor's three tables
;; ---------------------------------------------------------------------

(defonce ^{:doc "id -> f, a parked toolbox of reusable actions. See
musics.conductor/register-action!/trigger!."}
  ^:dynamic *conductor-action-registry* (atom {}))

(defonce ^{:doc "[id phase] -> action-id, one-shot (consumed on trigger).
See musics.conductor/schedule!/signal!."}
  ^:dynamic *conductor-schedule* (atom {}))

(defonce ^{:doc "[id phase] -> action-id, NOT consumed on trigger. See
musics.conductor/schedule-repeating!/signal!."}
  ^:dynamic *conductor-repeating* (atom {}))

;; ---------------------------------------------------------------------
;; musics.assist's history
;; ---------------------------------------------------------------------

(defonce ^{:doc "The actions taken, oldest first, at most 30: [{:action kw
:when ms} ...]. Written by log!, from each action musics.assist's table
names; read by musics.assist as history facts."}
  ^:dynamic *activity-log* (atom []))

(defn log!
  "Record that `action` (a key of musics.assist's action table) was taken."
  [action]
  (swap! *activity-log* #(vec (take-last 30 (conj % {:action action :when (System/currentTimeMillis)}))))
  nil)

(defn reset-all!
  "Reset every var this namespace declares back to its initial empty
   value: musics.repo's registry, musics.wall's algo-registry, musics.conductor's
   action-registry/schedule/repeating, musics.assist's activity log."
  []
  (clojure.core/reset! *repo-registry* {})
  (clojure.core/reset! *algo-registry* {})
  (clojure.core/reset! *conductor-action-registry* {})
  (clojure.core/reset! *conductor-schedule* {})
  (clojure.core/reset! *conductor-repeating* {})
  (clojure.core/reset! *activity-log* [])
  nil)
