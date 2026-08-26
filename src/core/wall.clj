(ns core.wall
  "Registry of pluggable playback transforms (\"wall\" algorithms) -- a
   parked toolbox, same shape input.algo-registry already uses for
   AtomicAlgo/ElementAlgo: name -> {:fn f :doc doc}, nothing more.

   A wall fn is always seq-in/seq-out: (nodes ctx-chain voice) -> nodes'.
   It's called identically regardless of granularity -- core.async-
   engine's container branch calls it with a full sibling list (every
   child of a :SEQ/:PAR at once); its leaf/rest/drum branch calls it with
   a singleton wrapping one already-ornament-expanded node. An algo never
   declares which one it 'acts on' -- it just always receives a seq, and
   a fn that only cares about one granularity naturally no-ops (or maps
   trivially) on the other.

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
      the registerer's own choice, nothing here detects it
      automatically. apply-factory (below) is the shared resolution
      step both this and configure-wall! run through.

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
      distinct names if both usages are wanted at once.")

(defonce ^{:doc "name -> {:fn f :doc doc}."} wall-registry
  (atom {}))

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
   leaf/rest/drum). doc (a plain string, optional) is shown by
   (walls)/(walls name)."
  ([name f] (register-wall! name f nil))
  ([name f doc]
   (swap! wall-registry assoc name {:fn f :doc doc})
   name))

(defn unregister-wall!
  "Forget name's parked wall fn -- any voice already assigned it (via
   assign-algo!, or play/play-add's own :algo tag) keeps
   whatever fn it already resolved to (resolved once, at assignment
   time, not on every read); only a later registration lookup under
   this name is affected."
  [name]
  (swap! wall-registry dissoc name)
  nil)

(defn wall-fn
  "The registered fn for name, or nil if nothing's registered under it."
  [name]
  (:fn (get @wall-registry name)))

(defn walls
  "With no arg: {name -> doc} for every registered wall fn. With name:
   just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @wall-registry))
  ([name] (:doc (get @wall-registry name))))

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
   The one shared resolution step behind both core.async-engine's own
   inline [name arg...] tag support and configure-wall! below -- keeps
   the 'no fn, print why, let the caller fall back to identity' policy
   in exactly one place rather than duplicated at each call site."
  [name args]
  (if-let [factory (wall-fn name)]
    (try
      (let [resolved (apply factory args)]
        (if (fn? resolved)
          resolved
          (do (println "core.wall:" name "did not resolve to a usable algorithm -- falling back to identity")
              nil)))
      (catch Exception e
        (println "core.wall:" name "threw applying args" (pr-str args) "--" (.getMessage e) "-- falling back to identity")
        nil))
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
   untouched (no partial/broken overwrite)."
  [location & args]
  (when-let [resolved (apply-factory location args)]
    (register-wall! location resolved (walls location)))
  location)
