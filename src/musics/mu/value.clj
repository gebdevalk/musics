(ns musics.mu.value
  "mu's musical values: what a literal becomes on the stack, without
   entering the repo.

   All literals of one input are walked together, in order, against the
   current repo, so within an input a duration carries over and a
   relative pitch follows the note before it, as across a .mus file's
   top-level elements; each input starts fresh. A bare leaf becomes the
   leaf itself; any other literal a Material: its id and a repo holding
   it with everything it refers to. Nothing reaches musics.repo here --
   storing is R! (doc/md/mu-repl.md)."
  (:require [musics.domain.context :as c]
            [musics.input.reader.walker :as walker]))

(defrecord Material [id repo])
;; :base, assoc'd on, is the repo the literal was read against: what the
;; literal itself created or redefined is what differs from it.

(defn material? [x] (instance? Material x))

(defrecord Quotation [body source])


(defrecord Mark [count])  ; | … ||||: a gathering mark, and >>'s boundary
(def mark (->Mark 1))
(defn mark? [x] (instance? Mark x))

(defn quotation?
  "A quotation '( … ), or a Clojure fn standing in for one (from
   musics.mu.words): it takes the top value and leaves its result."
  [x]
  (or (instance? Quotation x) (fn? x)))

(def ^:private leaf-tags #{:Note :Chord :Rest :Drum :MultiRest})
(def ^:private composite-tags #{:Sequence :Parallel :Context :Data :repeat :RepeatBars :tuplet})

(defn literal?
  "Is this parse node a musical literal (a leaf or a composite)?"
  [node]
  (and (vector? node) (contains? (into leaf-tags composite-tags) (first node))))

(defn leaf? [x] (and (map? x) (contains? #{:LEAF :REST :DRUM} (:type x))))

(defn leaf-node? [node] (and (vector? node) (contains? leaf-tags (first node))))

(declare reachable)

(defn- var-map
  "The bindings that hold music as the walker's variables ({name
   {:children :context}}, what \\name splices into a literal), and the
   nodes they need in the repo. A sequence splices its children and
   replays its context, as a text variable does; a bare leaf is itself;
   any other material (a parallel, …) is one reference."
  [bindings]
  (reduce-kv
   (fn [[vm nodes] k x]
     (cond
       (leaf? x)
       [(assoc vm k {:children [x] :context (c/context)}) nodes]
       (instance? Material x)
       (let [n    (get (:repo x) (:id x))
             own  (select-keys (:repo x) (disj (reachable (:repo x) (:id x)) :ROOT))]
         [(assoc vm k (if (= :SEQ (:type n))
                        {:children (:children n) :context (:context n)}
                        {:children [(:id x)] :context (c/context)}))
          (merge nodes own)])
       :else [vm nodes]))
   [{} {}] bindings))

(defn read-literals
  "Walk the literal nodes of one input (text) together against repo and
   auto-ids, with bindings ({name value}, mu's := ) as variables. Returns
   {:values [v …] :ids [id …] :auto-ids …}, one value and the id it was
   read under per node: a leaf map for a bare leaf (its id the one-leaf
   line parse would wrap it in), a Material for a composite. The auto-ids advance, so a later
   input never reuses an id."
  ([nodes text repo auto-ids] (read-literals nodes text repo auto-ids {}))
  ([nodes text repo auto-ids bindings]
   (if (empty? nodes)
     {:values [] :ids [] :auto-ids auto-ids}
     ;; :base stays the repo without the bound nodes: what a bound name
     ;; brings in is the literal's own, stored with it by R!
     (let [[vm bound] (var-map bindings)
           r    (walker/walk-each nodes text {:repo (merge repo bound) :auto-ids auto-ids :var-map vm})
           tree (:tree r)]
       {:values (mapv (fn [node id]
                        (cond
                          (leaf-node? node) (first (:children (get tree id)))
                          ;; a repeat is read inside a line of its own;
                          ;; its value is the repeat, held inline there
                          (#{:repeat :RepeatBars} (first node))
                          (let [it (first (:children (get tree id)))]
                            (assoc (->Material (:id it) (assoc tree (:id it) it)) :base repo))
                          :else (assoc (->Material id tree) :base repo)))
                      nodes (:ids r))
        :ids      (:ids r)
        :auto-ids (:auto-ids r)}))))

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

