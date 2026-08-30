(ns core.wall
  "Registry of pluggable playback transforms (\"wall\" algorithms) -- a
   parked toolbox: name -> {:fn f :doc doc}, nothing more.

   A wall fn is always seq-in/seq-out: (nodes ctx-chain voice) -> nodes'.
   It's called identically regardless of granularity -- core.async-
   engine's container branch calls it with a full sibling list (every
   child of a :SEQ/:PAR at once); its leaf/rest/drum branch calls it with
   a singleton wrapping one already-ornament-expanded node. An algo never
   declares which one it 'acts on' -- it just always receives a seq, and
   a fn that only cares about one granularity naturally no-ops (or maps
   trivially) on the other.

   A fn that EXPANDS -- returns more nodes than it was given, e.g. a
   doubling/echo transform -- is safe to write with no special care of
   its own: for one authored note inside a container, it's called at
   most twice, not once per note the way a naive mental model might
   assume -- once on the container's own sibling list (the batch
   containing that note, alongside whatever else is in it), and once
   more per node THAT call produced, singleton-wrapped, when each is
   individually dispatched to actually sound (so a fn that doubles one
   note into two gets invoked a third time overall: the batch call, plus
   one singleton call per one of the two notes it just produced -- three
   calls, four notes actually played, for one note originally written).
   That second wave's OWN output is always played directly from there,
   never fed back through this registry a third time, by construction
   in core.async-engine (play-leaves, not play-seq, handles it) -- so an
   expanding fn can never trigger runaway, ever-doubling growth just by
   being itself; nothing here or in the engine ever re-offers your own
   already-produced output back to you as fresh input past that second
   wave. What this DOES mean for a fn with side effects (a counter, a
   PRNG draw, logging): expect those to fire at both granularities, and
   more than once for a single authored note if you expand -- 'once per
   composer-written note' is not this fn's actual calling contract.

   This registry only holds implementations, nothing about who runs
   them. Which fn actually applies to a given voice is a separate,
   per-engine concern -- core.async-engine's own :algo-assignments map,
   path -> concrete fn (default identity), set explicitly via
   assign-algo! (or implicitly by musics.clj/play's own optional :algo
   tag -- a [Form :algo Name] anywhere in the tree, or a trailing :algo
   Name on the call itself -- which mints a short track id and assigns
   it in one call) -- see that namespace's own docstring, and why there's
   deliberately no pool/claim/release machinery the way MIDI channels
   need: calling the same fn for several simultaneous voices costs
   nothing and creates no conflict, unlike sharing real MIDI channel
   state.

   Two ways a registered name can carry parameters, both built on the
   one registry above -- nothing about wall-registry's own shape
   changes for either:

   1. Inline, at the point of use: a play-arg tag's own Name can be
      [registered-name arg1 arg2 ...] instead of a bare name -- see
      core.async-engine's own play-arg-mini-language comment and
      assign-algo!. For this to work, name must be registered as a
      FACTORY -- (fn [arg1 arg2 ...] -> wall-fn) -- rather than a plain
      3-arg wall fn; which shape a given register-wall! call uses is
      the registerer's own choice -- nothing here detects it
      automatically, but register-wall! now takes an OPTIONAL kind
      (:fn or :factory) so a registerer who declares it gets a much
      more specific failure message than a bare arity exception when
      the two get mixed up later (see apply-factory below, and
      core.async-engine/resolve-algo-name for the bare-name direction of
      the same mistake) -- declaring it is never required, an
      undeclared registration behaves exactly as it always has.
      apply-factory (below) is the shared resolution step both this and
      configure-wall! run through.

   2. Install once, configure later, from a fixed known location:
      register-wall! a factory under a stable name ahead of time (that
      IS 'install' -- no separate mechanism needed for it), then
      configure-wall! that same name with concrete args whenever you
      actually want it fed -- independent of any play call, any number
      of times. configure-wall! re-registers the RESOLVED wall fn back
      under the same name, in this same wall-registry -- deliberately
      one store, not a second cache atom holding 'the current
      configuration' separately from 'the original recipe'. The
      tradeoff this buys simplicity at: after configure-wall! runs
      once, the name holds a concrete fn, not the factory anymore --
      reconfiguring the SAME name a second time needs the factory
      re-registered first. A name used this way (configure-wall!)
      shouldn't also be used inline (#1) at the same time for a
      different parameter set -- register the factory under two
      distinct names if both usages are wanted at once.

   The registry atom itself (name -> {:fn f :doc doc :kind kind}) lives
   in core.registries now, as core.registries/*wall-registry* -- see
   that ns's own docstring for why (collecting this project's mutable
   global state in one place, and making it ^:dynamic so a test can
   give itself a fresh, isolated registry via `binding` instead of
   manually resetting the shared one). Every function below reads/
   writes it exactly as if it were still a local atom; nothing about
   this ns's own public API changed."
  (:require [core.registries :as reg]
            [core.repo :as repo]
            [core.domain.flat-domain :as d]))

(defn identity-wall
  "The default, no-op wall fn -- (nodes ctx-chain voice) -> nodes,
   unchanged. NOT clojure.core/identity: a wall fn's contract is always
   3-arg (nodes ctx-chain voice), so a genuine 1-arg identity would
   arity-error the moment an unconfigured slot's default is actually
   invoked -- confirmed live, not hypothetical."
  [nodes _ctx-chain _voice]
  nodes)

(defn register-wall!
  "Park f under name, usable thereafter as a voice's assigned algorithm
   (see core.async-engine/assign-algo!, or musics.clj/play's own
   optional :algo tag). f is
   always called as (f nodes ctx-chain voice) -> nodes', nodes always a
   seq (a container's full sibling list, or a singleton wrapping one
   leaf/rest/drum), UNLESS f is instead a FACTORY -- (fn [arg1 arg2 ...]
   -> wall-fn) -- see this ns's own header comment on the two shapes,
   INCLUDING what it means for f's own return value to have a different
   count than nodes (1-to-N expansion, e.g. doubling/echo) -- safe to
   write with no special care on f's own part, f is called at most twice
   per authored note either way, never a third time on its own prior
   output.
   doc (a plain string, optional) is shown by (walls)/(walls name).
   kind (also optional, one of :fn/:factory) is a self-declaration of
   which of the two shapes f actually is -- entirely opt-in, defaults to
   nil (undeclared, matching every registration before this arg
   existed) -- see apply-factory/core.async-engine's own resolve-algo-
   name for what declaring it actually buys: a specific 'you used a
   factory where a plain fn was expected (or vice versa)' message
   instead of a bare arity exception or, worse, silently invoking a
   factory AS a wall fn with the wrong arguments entirely. Nothing here
   verifies kind actually matches f's real shape -- a wrong declaration
   just produces a wrong (if still clearer-sounding) message, same
   'trust the registerer' policy this whole registry already has for f
   itself."
  ([name f] (register-wall! name f nil nil))
  ([name f doc] (register-wall! name f doc nil))
  ([name f doc kind]
   (swap! reg/*wall-registry* assoc name {:fn f :doc doc :kind kind})
   name))

(defn unregister-wall!
  "Forget name's parked wall fn -- any voice already assigned it (via
   assign-algo!, or play/play-add's own :algo tag) keeps
   whatever fn it already resolved to (resolved once, at assignment
   time, not on every read); only a later registration lookup under
   this name is affected."
  [name]
  (swap! reg/*wall-registry* dissoc name)
  nil)

(defn wall-fn
  "The registered fn for name, or nil if nothing's registered under it."
  [name]
  (:fn (get @reg/*wall-registry* name)))

(defn wall-kind
  "name's declared :kind (:fn, :factory, or nil if either unregistered
   or registered without ever declaring one -- the two are
   indistinguishable here on purpose, since every caller of this fn only
   ever branches on = :factory or = :fn specifically and treats anything
   else, nil included, as 'proceed as before this existed')."
  [name]
  (:kind (get @reg/*wall-registry* name)))

(defn walls
  "With no arg: {name -> doc} for every registered wall fn. With name:
   just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*wall-registry*))
  ([name] (:doc (get @reg/*wall-registry* name))))

(defn registered
  "The raw {name -> {:fn f :doc doc :kind kind}} registry map, for a
   caller that genuinely needs every entry at once (core.async-engine/
   algo-assignments' own reverse fn->name lookup, the one place outside
   this ns that needs this) -- rather than reaching directly into
   core.registries/*wall-registry* and duplicating this ns's own
   knowledge of what an entry's shape is."
  []
  @reg/*wall-registry*)

(defn apply-wall
  "Run nodes (always a seq) through slot-fn, or return nodes unchanged
   if slot-fn is nil (an unconfigured slot -- the default). ctx-chain
   and voice are passed through untouched, for a wall fn that wants to
   condition its own transform on either."
  [slot-fn ctx-chain voice nodes]
  ((or slot-fn identity-wall) nodes ctx-chain voice))

(defn apply-factory
  "Look up name's registered factory and apply args to it, returning the
   resolved wall fn -- or nil, after printing a plain console warning,
   if name isn't registered, applying args throws, or the result isn't
   itself a fn (most commonly: name was registered as a plain 3-arg
   wall fn, not a factory, and got called with args it never expected).
   That last case gets a MUCH more specific warning when the registerer
   declared :kind :fn at register-wall! time (see that fn's own
   docstring) -- 'this is a plain wall fn, not a factory' rather than
   whatever arity exception happened to come back from calling a 3-arg
   fn with N args, which could easily be non-obvious or, worse, not
   throw at all if N happened to be 3 (see resolve-algo-name in
   core.async-engine for what happens then: silently NOT this fn's
   problem, since that's the bare-name direction of the same mistake,
   caught there instead).
   The one shared resolution step behind both core.async-engine's own
   inline [name arg...] tag support and configure-wall! below -- keeps
   the 'no fn, print why, let the caller fall back to identity' policy
   in exactly one place rather than duplicated at each call site."
  [name args]
  (if-let [entry (get @reg/*wall-registry* name)]
    (if (= :fn (:kind entry))
      (do (println "core.wall:" name "is registered as a plain wall fn, not a factory --"
                    "call it bare (no args), not [" name (pr-str args) "] -- falling back to identity")
          nil)
      (try
        (let [resolved (apply (:fn entry) args)]
          (if (fn? resolved)
            resolved
            (do (println "core.wall:" name "did not resolve to a usable algorithm -- falling back to identity")
                nil)))
        (catch Exception e
          (println "core.wall:" name "threw applying args" (pr-str args) "--" (.getMessage e) "-- falling back to identity")
          nil)))
    (do (println "core.wall: no algorithm registered as" name "-- falling back to identity")
        nil)))

(defn configure-wall!
  "Feed location's currently-registered factory args, and re-register
   the resolved wall fn back under that same name -- 'install once
   (register-wall! a factory under a stable name, ahead of time),
   configure later (this call, any time, any number of times,
   independent of any play call)'. location's own existing doc (if any)
   is preserved across the reconfigure, not blanked. Returns location.

   Deliberately one store, the same wall-registry apply-wall/wall-fn
   already read -- not a second cache atom separating 'the original
   factory' from 'the current configuration'. The real tradeoff that
   buys: after this runs once, location holds a concrete wall fn, not
   the factory anymore -- reconfiguring it AGAIN needs the factory
   re-registered under location first, this can't just re-derive from
   its own last output. Uses apply-factory for the actual resolution --
   an unregistered location, a factory that throws, or a factory that
   doesn't resolve to a fn all print the same console warning
   apply-factory already does and leave location's own registration
   untouched (no partial/broken overwrite).

   Re-registers with :kind :fn explicitly, not whatever kind (if any)
   location was originally declared under -- once this runs, location
   really does hold a plain, already-resolved wall fn, not a factory
   anymore, exactly what the paragraph above already says in prose; a
   later bare (play ... :algo location) reference must NOT be rejected
   as 'that's a factory, not a plain algorithm' (resolve-algo-name/
   validate-algo-name! in core.async-engine, see wall-kind) just
   because location happened to start out declared :factory."
  [location & args]
  (when-let [resolved (apply-factory location args)]
    (register-wall! location resolved (walls location) :fn))
  location)

;; ============================================================
;; Presets: a SEPARATE store from wall-registry above -- see
;; core.registries/*preset-registry*'s own docstring for why. A preset
;; is always already-resolved (never a factory needing further args),
;; parked under its own name by configure-preset! below, which reads
;; (never overwrites) a wall-registry factory's own entry -- so any
;; number of independent presets can coexist off one factory, unlike
;; configure-wall! above, which can only ever hold ONE current
;; configuration per name because factory and resolved-config share a
;; single slot there.
;; ============================================================

(defn register-preset!
  "Park an already-resolved fn f under name in *preset-registry* -- the
   direct, low-level counterpart to register-wall! above, for a caller
   that already has a concrete wall fn in hand and just wants to give
   it a switchable name of its own. configure-preset! (below) is the
   usual way to get here -- resolve a wall-registry factory against
   concrete args, then register the RESULT -- this fn is what it calls
   internally, kept public for the same reason register-wall! is:
   sometimes you already have the fn and don't need a factory step at
   all. doc (optional) is shown by (presets)/(presets name)."
  ([name f] (register-preset! name f nil))
  ([name f doc]
   (swap! reg/*preset-registry* assoc name {:fn f :doc doc})
   name))

(defn unregister-preset!
  "Forget name's parked preset -- any voice already assigned it (via
   assign-algo!, or play/play-add's own :algo tag) keeps whatever fn it
   already resolved to, resolved once at assignment time same as
   everywhere else in this ns; only a LATER reference to name is
   affected."
  [name]
  (swap! reg/*preset-registry* dissoc name)
  nil)

(defn preset-fn
  "The registered fn for name in *preset-registry*, or nil if nothing's
   registered under it."
  [name]
  (:fn (get @reg/*preset-registry* name)))

(defn presets
  "With no arg: {name -> doc} for every registered preset. With name:
   just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*preset-registry*))
  ([name] (:doc (get @reg/*preset-registry* name))))

(defn- resolve-config-form
  "Resolve one configure-preset! arg against repo-view, the SAME play-
   arg-mini-language shapes play itself accepts for a Form -- bare
   keyword = repo reference, [Form+]/#{Form+} = resolve every item,
   preserving whichever collection type was actually written -- but
   deliberately NOT play's own machinery: no context-ref peeling, no
   :algo tags, no seq-vs-par distinction (this fn's caller doesn't care
   -- config data is never itself 'played'), and resolving a keyword is
   never coerced into a sequence the way sq coerces a container for
   playback. A :DATA container's own d/children IS the value returned
   -- a talea authored as '[ /4 /8 /8 /4 ] resolves straight to
   [1/4 1/8 1/8 1/4], not to anything voice- or Leaf-shaped.

   A bare keyword resolves against repo-view ONLY IF it actually names
   something there -- an id that doesn't resolve falls through to the
   literal branch below and is returned AS the keyword itself, not an
   error. This is the one deliberate departure from play's own Form
   grammar (there, an unresolvable keyword is always a hard,
   validate-ids!-caught error): a factory's own args are routinely
   plain keyword FLAGS (:major, :up, a dynamic mark) that were never
   meant to be repo references at all, and configure-preset! has no
   equivalent pre-flight pass to make a hard failure safe/early the way
   play's validate-ids! does -- silently trying the repo first and
   falling back to the literal is far less surprising here than
   erroring on every ordinary flag argument.

   A resolved container's own children are run back through this same
   fn, recursively -- a :DATA container's children are always already-
   terminal values per its own grammar (DataElement never includes a
   nested Reference), so this is a no-op for the primary case; a
   :SEQ/:PAR container's children CAN still be further keyword ids, so
   this still does the right thing for those without a second, separate
   code path."
  [repo-view form]
  (cond
    (vector? form) (mapv (partial resolve-config-form repo-view) form)
    (set? form)    (into #{} (map (partial resolve-config-form repo-view)) form)
    (keyword? form)
    (if-let [node (get repo-view form)]
      (resolve-config-form repo-view (if (d/container? node) (d/children repo-view node) node))
      form)
    :else form))

(defn configure-preset!
  "Build ONE named preset -- an already-resolved wall fn, parked in
   *preset-registry* under preset-name -- by applying factory-name's
   own currently-registered FACTORY (looked up in *wall-registry*,
   resolved via apply-factory -- same failure handling as everywhere
   else that fn is used: an unregistered factory-name, a throwing
   factory, or a non-fn result all print a console warning and leave
   preset-name's own PRIOR registration untouched, no partial
   overwrite) to args.

   args go through resolve-config-form first -- everything play's own
   Form mini-language can express (a bare keyword repo reference,
   [Form+]/#{Form+} groups, nested) is accepted here too, resolved
   against the latest committed repo ONCE, right now, same 'resolved
   once, not re-read later' invariant assign-algo!/configure-wall!
   already have -- but the result never has to be a sequence the way a
   play argument does: a bare keyword pointing at a :DATA container
   resolves straight to that container's own raw values (a talea, a
   color), a literal value (a number, a ratio, a plain already-Clojure
   collection with nothing keyword-shaped inside it) passes through
   completely unchanged. This is what lets a factory's args be fed
   EITHER inline literals (as before this fn existed) OR real,
   committed, versioned Material -- a composer's own choice per call,
   not a fork in the mechanism.

   THIS is the reason presets need a store separate from wall-registry:
   factory-name's own entry there is only ever READ, never overwritten
   -- calling configure-preset! any number of times, under any number
   of DIFFERENT preset-name values, off the SAME factory-name, produces
   that many independent, coexisting presets (::bright/::dark off one
   color-talea factory, say), each switchable on its own, by name, via
   assign-algo!/play's own :algo tag -- exactly the gap
   core.wall/configure-wall! itself can't close (see its own docstring:
   reconfiguring a name there needs the factory re-registered under
   THAT SAME name first, since factory and resolved-config share one
   slot there).

   Returns preset-name."
  [preset-name factory-name & args]
  (let [repo-view     (repo/view (repo/latest-tx))
        resolved-args (mapv (partial resolve-config-form repo-view) args)]
    (when-let [resolved (apply-factory factory-name resolved-args)]
      (register-preset! preset-name resolved (walls factory-name)))
    preset-name))
