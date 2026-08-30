(ns core.registries
  "The mutable global state this project's foundational namespaces --
   core.repo, core.wall, core.conductor -- actually hold, collected in
   one file so 'what mutable state does this whole system have' has one
   answer instead of being scattered across three namespaces (and, in
   practice, easy to lose track of). This file only declares WHERE each
   piece of state lives and how to reset it -- what each one MEANS and
   the invariants its owning namespace enforces are documented there,
   not duplicated here.

   Deliberately a LEAF namespace: requires nothing else in this project,
   so core.repo/core.wall/core.conductor (each already documented, in
   its own ns docstring, as depending on nothing above it) can require
   this without inverting that layering. Putting this state directly in
   musics.clj/session instead was considered and rejected for exactly
   that reason: musics.clj sits at the TOP of the dependency graph,
   requiring all three of them -- none of them can require it back
   without creating a cycle.

   Every var here is ^:dynamic specifically so a test can (binding
   [core.registries/*wall-registry* (atom {}) ...] ...) a completely
   fresh, isolated instance of any one of them -- or all of them at
   once -- for just its own extent, auto-restored afterward even if the
   test throws. This is optional, not a replacement for the existing
   pattern: (reset! core.registries/*wall-registry* {}) still works
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
   everything' on its own -- see musics.clj/reset, which calls both this
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

(defonce ^{:doc "sid -> {id -> node}. Working sets for in-progress,
not-yet-visible edits. See core.repo/begin-staged-tx!/stage!/
commit-staged!."}
  ^:dynamic *repo-staging* (atom {}))

(defonce ^{:doc "Monotonically increasing transaction counter -- every
commit mints exactly one new tx. See core.repo/commit-node!/commit-staged!."}
  ^:dynamic *repo-tx-counter* (atom 0))

(defonce ^{:doc "Monotonically increasing staging-id counter, mirroring
*repo-tx-counter* -- sids are short and ordered (:sid1, :sid2, ...). See
core.repo/begin-staged-tx!."}
  ^:dynamic *repo-sid-counter* (atom 0))

;; ---------------------------------------------------------------------
;; core.wall's registry
;; ---------------------------------------------------------------------

(defonce ^{:doc "name -> {:fn f :doc doc :kind kind}. See core.wall's own
ns docstring."}
  ^:dynamic *wall-registry* (atom {}))

(defonce ^{:doc "name -> {:fn f :doc doc}, a SEPARATE store from
*wall-registry* above -- a preset is always already-resolved (never a
factory needing further args), built by configure-preset! applying a
wall-registry factory to concrete args and parking the RESULT here
under its own name, leaving the factory's own wall-registry entry
untouched. See core.wall/configure-preset!'s own docstring for why
this is a second store rather than reusing wall-registry the way
configure-wall! reuses it for a single name (that would only ever let
one name hold one configuration at a time; a preset menu needs several
configurations of the SAME factory to coexist under different names)."}
  ^:dynamic *preset-registry* (atom {}))

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

(defn reset-all!
  "Reset every var this namespace declares back to its initial empty
   value: core.repo's registry/staging/tx-counter/sid-counter,
   core.wall's wall-registry/preset-registry, core.conductor's action-registry/schedule/
   repeating. Does NOT reset core.repo/play-tx (see this ns's own
   docstring for why) -- pair with (core.repo/reset-all!) for that;
   musics.clj/reset calls both."
  []
  (clojure.core/reset! *repo-registry* {})
  (clojure.core/reset! *repo-staging* {})
  (clojure.core/reset! *repo-tx-counter* 0)
  (clojure.core/reset! *repo-sid-counter* 0)
  (clojure.core/reset! *wall-registry* {})
  (clojure.core/reset! *preset-registry* {})
  (clojure.core/reset! *conductor-action-registry* {})
  (clojure.core/reset! *conductor-schedule* {})
  (clojure.core/reset! *conductor-repeating* {})
  nil)
