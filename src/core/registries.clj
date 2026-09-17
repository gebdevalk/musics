(ns core.registries
  "The mutable global state this project's foundational namespaces --
   core.repo, core.wall, core.conductor -- actually hold, collected in
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
     *repo-registry*               core.repo        id -> tx -> node -- the only place committed material lives
     *repo-tx-counter*             core.repo        next commit's tx number
     *algo-factory-registry*       core.wall        name -> permanent factory recipe
     *algo-registry*               core.wall        name -> built, hot-swappable wall fn (what a voice's own :algo actually resolves against)
     *distribution-registry*       core.wall        name -> (lo hi)->value sampler, for factories that take a distribution by name
     *criteria-registry*           core.wall        name -> select-fn, for gate-algo's named criteria
     *conductor-action-registry*   core.conductor   id -> f, a parked toolbox of reusable actions
     *conductor-schedule*          core.conductor   [id phase] -> action-id, one-shot (consumed on trigger)
     *conductor-repeating*         core.conductor   [id phase] -> action-id, NOT consumed on trigger
     *adviser-log*                 core.adviser     bounded recent-activity log for what-next

   That's 10, not more -- two other tables sometimes get lumped in with
   this list (e.g. in an earlier self-audit) but genuinely aren't the
   same kind of thing: `core.async-engine`'s `:algo-prepared` (path ->
   name, consulted only at voice-mint time) lives on each ENGINE
   INSTANCE, not as a var here, by the same 'instance, not global'
   discipline `:voices`/`:channel-claims`/etc. already follow -- see
   that ns's own docstring. And the wall-preset duality
   (`*preset-registry*`/`configure-preset!`) this table might remind
   you of no longer exists at all -- superseded by `build!`'s own
   hot-swap-by-name design (2026-09-11); only historical mentions of it
   remain, in `core.wall`'s own docstring and `doc/decisions.md`.

   Deliberately a LEAF namespace: requires nothing else in this project,
   so core.repo/core.wall/core.conductor (each already documented, in
   its own ns docstring, as depending on nothing above it) can require
   this without inverting that layering. Putting this state directly in
   musics.core/session instead was considered and rejected for exactly
   that reason: musics.core sits at the TOP of the dependency graph,
   requiring all three of them -- none of them can require it back
   without creating a cycle.

   Every var here is ^:dynamic specifically so a test can (binding
   [core.registries/*algo-registry* (atom {}) ...] ...) a completely
   fresh, isolated instance of any one of them -- or all of them at
   once -- for just its own extent, auto-restored afterward even if the
   test throws. This is optional, not a replacement for the existing
   pattern: (reset! core.registries/*algo-registry* {}) still works
   exactly like resetting any other atom, so existing manual-reset test
   fixtures keep working unchanged, just pointed at the new location.
   defonce still protects the root binding across a REPL reload, same
   guarantee every var here had before this file existed.

   core.repo/play-tx is deliberately NOT here despite being the same
   general shape (an atom, module-level, mutable). Unlike every var
   below, it's referenced BY VALUE throughout the codebase --
   core.async-engine/engine's own :repo argument is normally handed
   this atom directly (not read through an accessor fn), and it's
   discussed at length, by that exact name, in CLAUDE.md and several
   other namespaces' own docstrings. Moving it here would mean either a
   stale documentation trail across the whole project or a purely
   mechanical rename at every one of its ~60 call sites, for a var whose
   external contract doesn't actually change either way -- staying in
   core.repo.clj, just upgraded to ^:dynamic in place, keeps that
   documentation accurate while still gaining the same testing benefit.
   One consequence: reset-all! below is NOT a complete 'reset
   everything' on its own -- see musics.core/reset, which calls both this
   and core.repo/reset-all! (which separately covers play-tx, plus
   redundantly the four repo vars this file also resets -- harmless,
   not worth avoiding at the cost of a dependency cycle back into
   core.repo)."
  )

;; ---------------------------------------------------------------------
;; core.repo's own bookkeeping (play-tx excepted -- see ns docstring)
;; ---------------------------------------------------------------------

(defonce ^{:doc "id -> sorted-map of tx -> node. The *only* place committed,
visible material lives. See core.repo's own ns docstring for the full
versioning design."}
  ^:dynamic *repo-registry* (atom {}))

(defonce ^{:doc "Monotonically increasing transaction counter -- every
commit mints exactly one new tx. See core.repo/commit-node!/commit-many!."}
  ^:dynamic *repo-tx-counter* (atom 0))

;; ---------------------------------------------------------------------
;; core.wall's registry
;; ---------------------------------------------------------------------

(defonce ^{:doc "name -> {:fn f :doc doc}. f is ALWAYS a factory,
(fn [name params] -> name), params ALWAYS a plain map (2026-09-11
redesign -- one uniform shape for every factory, not a positional arg
list that differs per factory) -- see core.wall's own ns docstring.
Entries here are meant to be PERMANENT: nothing in core.wall ever
overwrites an existing entry the way the old (pre-2026-09-09)
single-registry design let configure-algo! do -- a factory, once
registered, stays available to build as many independently-named,
independently-hot-swappable cooked algos off of as wanted. See
*algo-registry* below for where those cooked results actually land."}
  ^:dynamic *algo-factory-registry* (atom {}))

(defonce ^{:doc "name -> {:fn f :doc doc :factory-name :params}, f an
already-resolved wall fn -- a SEPARATE store from
*algo-factory-registry* above, one name per built algo. Every entry
here was built by calling some factory in *algo-factory-registry* with
(name params) -- that factory's own call stores its result here, under
name, via core.wall/build-algo! (see that fn's own docstring, and
core.wall's ns docstring for the full pipeline); core.wall/build! then
additionally stamps :factory-name/:params (the resolved params, the
recipe) onto that same entry -- a factory called directly, bypassing
build!, stores only :fn/:doc, no recipe. This is what a voice/track
actually points at (core.async-engine's own voice map holds just this
plain name in its own immutable :algo field, never a resolved fn) and
what core.wall/algo reads FRESH on every single node a voice visits --
so
hot-swapping an algo is exactly 'call some factory with this SAME name
again,' overwriting this entry in place; every voice currently pointing
at name picks it up on its very next node, no per-voice action needed.
Named *algo-registry* (not *preset-registry*) as of 2026-09-09 -- it's
no longer a secondary, optional store beside a frozen-copy default;
this and only this is what 'a voice's assigned algo' now means."}
  ^:dynamic *algo-registry* (atom {}))

(defonce ^{:doc "name -> {:fn f :doc doc}, a SEPARATE store from
*algo-factory-registry*/*algo-registry* above -- a distribution is a
plain (lo hi) -> value sampler (e.g. algo.random/lo-emph), never a
wall-fn (nodes ctx voice) -> nodes' itself. Exists so a composite
wall-fn factory (e.g. algo.common.reshape/weighted-shuffle-algo) can
accept a distribution BY NAME as one of its own args and resolve it
against this registry -- a second, independent axis of 'reference
something named, not just a literal value' alongside that one. See
core.wall's own docstring for the accessors (register-distribution!/
distribution-fn/distributions)."}
  ^:dynamic *distribution-registry* (atom {}))

(defonce ^{:doc "name -> {:fn f :doc doc}, another store alongside
*algo-factory-registry*/*algo-registry*/*distribution-registry* above
-- a criterion factory is (fn [args...] -> select-fn), select-fn being
(part raw-prev) -> boolean. Exists so algo.common.gate/gate-algo can
accept a criterion BY NAME (e.g. [:lo 67]) the same way weighted-
shuffle-algo accepts a distribution by name -- another independent axis
of 'reference something named, not just a literal value.' See
core.wall's own docstring for the accessors (register-criterion!/
criterion-fn/criteria)."}
  ^:dynamic *criteria-registry* (atom {}))

;; ---------------------------------------------------------------------
;; core.conductor's three tables
;; ---------------------------------------------------------------------

(defonce ^{:doc "id -> f, a parked toolbox of reusable actions. See
core.conductor/register-action!/trigger!."}
  ^:dynamic *conductor-action-registry* (atom {}))

(defonce ^{:doc "[id phase] -> action-id, one-shot (consumed on trigger).
See core.conductor/schedule!/signal!."}
  ^:dynamic *conductor-schedule* (atom {}))

(defonce ^{:doc "[id phase] -> action-id, NOT consumed on trigger. See
core.conductor/schedule-repeating!/signal!."}
  ^:dynamic *conductor-repeating* (atom {}))

;; ---------------------------------------------------------------------
;; core.adviser's own state
;; ---------------------------------------------------------------------

(defonce ^{:doc "Bounded recent-activity log for core.adviser/what-next --
[{:action kw :detail m :when ms} ...], newest last, capped at
core.adviser's own log-limit. Appended to from musics.core's thin
wrappers (the one seam every REPL-facing verb already funnels through),
never from anywhere lower-level. See core.adviser's own ns docstring.
Deliberately the only piece of core.adviser's own state -- an intent is
always an explicit, one-off argument to what-next/musics.core's advise,
never persisted, so there's no separate 'declared intent' var here."}
  ^:dynamic *adviser-log* (atom []))

(defn reset-all!
  "Reset every var this namespace declares back to its initial empty
   value: core.repo's registry/tx-counter, core.wall's algo-factory-
   registry/algo-registry/distribution-registry/criteria-registry,
   core.conductor's action-registry/schedule/repeating, core.adviser's
   log. Does NOT reset core.repo/play-tx (see this ns's own docstring
   for why) -- pair with (core.repo/reset-all!) for that; musics.core/
   reset calls both."
  []
  (clojure.core/reset! *repo-registry* {})
  (clojure.core/reset! *repo-tx-counter* 0)
  (clojure.core/reset! *algo-factory-registry* {})
  (clojure.core/reset! *algo-registry* {})
  (clojure.core/reset! *distribution-registry* {})
  (clojure.core/reset! *criteria-registry* {})
  (clojure.core/reset! *conductor-action-registry* {})
  (clojure.core/reset! *conductor-schedule* {})
  (clojure.core/reset! *conductor-repeating* {})
  (clojure.core/reset! *adviser-log* [])
  nil)
