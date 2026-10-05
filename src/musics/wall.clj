(ns musics.wall
  "The registry of per-voice playback algorithms (\"wall\" algos): name ->
   {:fn f :doc doc ...}. A voice carries only a name (its :algo); the
   engine looks the name up FRESH on every node it plays, so
   re-registering a name is the whole hot-swap mechanism -- every voice
   following it changes on its next note.

   A wall fn is seq-in/seq-out: (nodes ctx-chain voice) -> nodes'. The
   engine calls it once with a container's whole list of children, then
   again with each resulting note singleton-wrapped, so a fn with side
   effects (or one that transforms) must pass through what it already
   produced -- musics.algo.tree.live tags its output for exactly this.

   What fills this registry is musics.algo.tree/live! (a name bound to a tree
   and a tctx); build-algo! stores any hand-written wall fn directly. The
   registry atom lives in musics.registries, ^:dynamic so a test can bind
   a fresh one."
  (:require [musics.registries :as reg]))

(defn identity-algo
  "The default, no-op wall fn -- (nodes ctx-chain voice) -> nodes."
  [nodes _ctx-chain _voice]
  nodes)

(defn build-algo!
  "Store wall fn f under name (overwriting it: the hot-swap). doc
   (optional) is shown by (algos)."
  ([name f] (build-algo! name f nil))
  ([name f doc]
   (swap! reg/*algo-registry* assoc name {:fn f :doc doc})
   name))

(defn unregister-algo!
  "Forget name; voices following it play unchanged from their next note."
  [name]
  (swap! reg/*algo-registry* dissoc name)
  nil)

(defn algo
  "The wall fn registered under name, or nil. Read fresh by the engine
   on every node -- never cached on a voice."
  [name]
  (:fn (get @reg/*algo-registry* name)))

(defn algos
  "With no arg: {name -> doc} for every registered algo. With name: its doc."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*algo-registry*))
  ([name] (:doc (get @reg/*algo-registry* name))))

(defn registered
  "With no arg: the whole {name -> entry} registry. With name: its entry
   (a name bound by musics.algo.tree/live! adds :tree, :tctx and :cursors)."
  ([] @reg/*algo-registry*)
  ([name] (get @reg/*algo-registry* name)))

(defn apply-algo
  "Run nodes through slot-fn, or return them unchanged if slot-fn is nil."
  [slot-fn ctx-chain voice nodes]
  ((or slot-fn identity-algo) nodes ctx-chain voice))
