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
;; :base, assoc'd on, is the repo the literal was read against: what the
;; literal itself created or redefined is what differs from it.

(defn material? [x] (instance? Material x))

(defrecord Quotation [body source])

(defrecord Variable [name cell])

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
                         (assoc (->Material id tree) :base repo)))
                     nodes (:ids r))
       :auto-ids (:auto-ids r)})))

(defn node
  "A Material's own node."
  [m]
  (get (:repo m) (:id m)))

(defn- refs
  "The ids a node refers to: keyword children, and those of inline
   children, an iterator's source and its alternative, recursively."
  [n]
  (letfn [(of [x] (cond (keyword? x) [x]
                        (map? x)     (concat (mapcat of (:children x))
                                             (of (:source x))
                                             (of (get-in x [:params :alternative])))
                        :else        []))]
    (of (dissoc n :id))))

(defn reachable
  "Every id reachable from id in repo, id included."
  [repo id]
  (loop [todo [id] seen #{}]
    (if-let [k (first todo)]
      (if (seen k)
        (recur (rest todo) seen)
        (recur (concat (rest todo) (refs (get repo k))) (conj seen k)))
      seen)))

(defn own-nodes
  "The nodes a Material's literal created or redefined: reachable from
   its id and different from the repo it was read against."
  [m]
  (let [repo (:repo m) base (:base m {})]
    (into {} (for [k (reachable repo (:id m))
                   :let [n (get repo k)]
                   :when (and n (not= k :ROOT) (not (identical? n (get base k))))]
               [k n]))))

