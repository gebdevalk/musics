(ns musics.mu.value
  "mu's musical values: what a literal becomes on the stack, without
   entering the repo.

   All literals of one input are walked together, in order, against the
   current repo, so within an input a duration carries over and a
   relative pitch follows the note before it, as across a .mus file's
   top-level elements; each input starts fresh. A bare leaf becomes the
   leaf itself; any other literal a Material: its id and a repo holding
   it with everything it refers to. Nothing reaches musics.repo here --
   storing is !! (doc/mu-repl.md)."
  (:require [musics.input.reader.walker :as walker]))

(defrecord Material [id repo])

(defn material? [x] (instance? Material x))

(defrecord Quotation [body source])

(defn quotation? [x] (instance? Quotation x))

(def ^:private leaf-tags #{:Note :Chord :Rest :Drum :MultiRest})
(def ^:private composite-tags #{:Sequence :Parallel :Context :Data})

(defn literal?
  "Is this parse node a musical literal (a leaf or a composite)?"
  [node]
  (and (vector? node) (contains? (into leaf-tags composite-tags) (first node))))

(defn leaf-node? [node] (and (vector? node) (contains? leaf-tags (first node))))

(defn read-literals
  "Walk the literal nodes of one input (text) together against repo and
   auto-ids. Returns {:values [v …] :auto-ids …}, one value per node: a
   leaf map for a bare leaf, a Material for a composite. The auto-ids
   advance, so a later input never reuses an id."
  [nodes text repo auto-ids]
  (if (empty? nodes)
    {:values [] :auto-ids auto-ids}
    (let [r    (walker/walk-each nodes text {:repo repo :auto-ids auto-ids :var-map {}})
          tree (:tree r)]
      {:values (mapv (fn [node id]
                       (if (leaf-node? node)
                         (first (:children (get tree id)))
                         (->Material id tree)))
                     nodes (:ids r))
       :auto-ids (:auto-ids r)})))

(defn node
  "A Material's own node."
  [m]
  (get (:repo m) (:id m)))
