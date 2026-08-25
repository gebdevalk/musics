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
   state.")

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
