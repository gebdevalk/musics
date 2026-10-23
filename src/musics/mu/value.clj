(ns musics.mu.value
  "mu's musical values: what a literal becomes on the stack, without
   entering the repo.

   The literals of one input are walked together, in order, against the
   current repo (up to each :=, after which the input reads on afresh),
   so a duration carries over and a relative pitch follows the note
   before it; each input starts fresh. A bare leaf becomes the
   leaf itself; any other literal a Material: its id and a repo holding
   it with everything it refers to. Nothing reaches musics.repo here --
   storing is R! (doc/md/mu-repl.md)."
  (:require [clojure.string :as str]
            [musics.domain.context :as c]
            [musics.input.reader.walker :as walker]
            [musics.mu.grammar :as g]))

(defrecord Material [id repo])
;; :base, assoc'd on, is the repo the literal was read against: what the
;; literal itself created or redefined is what differs from it.

(defn material? [x] (instance? Material x))

(defrecord Quotation [body source])


(defrecord Mark [count])  ; | … ||||: a gathering mark, and >>'s boundary
(def mark (->Mark 1))
(defn mark? [x] (instance? Mark x))

(defn quotation?
  "A quotation '( … ), or a Clojure fn standing in for one: it takes the
   top value and leaves its result."
  [x]
  (or (instance? Quotation x) (fn? x)))

(def ^:private leaf-tags #{:Note :Chord :ChordSymbol :Rest :Drum :MultiRest})
(def ^:private literal-tags
  (into leaf-tags #{:Sequence :Parallel :Context :Data :repeat :RepeatBars :tuplet :shuffle :rotate :choose :alternate}))

(defn literal?
  "Is this parse node a musical literal (a leaf or a composite)?"
  [node]
  (and (vector? node) (contains? literal-tags (first node))))

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

(defn- param-aliases
  "The bindings of a ?name to another ?name ('?dp '?duration-pulse :=)
   as {\"dp\" \"duration-pulse\"}: an algo param written by its short
   name is read as the long one."
  [bindings]
  (into {} (for [[k x] bindings
                 :when (and (str/starts-with? k "?") (symbol? x) (str/starts-with? (str x) "?"))]
             [(subs k 1) (subs (str x) 1)])))

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
           r    (walker/walk-each nodes text {:repo (merge repo bound) :auto-ids auto-ids :var-map vm
                                              :param-aliases (param-aliases bindings)})
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
   children, an iterator's source and its alternative, an override
   mark's context, recursively."
  [n]
  (letfn [(of [x] (cond (keyword? x) [x]
                        (map? x)     (concat (mapcat of (:children x))
                                             (of (:source x))
                                             (of (get-in x [:params :alternative]))
                                             (when (= :override (:key x)) (of (:ref x))))
                        :else        []))]
    (of (dissoc n :id))))

(defn reachable
  "Every id reachable from id in repo, id included."
  [repo id]
  (loop [todo [id] seen #{}]
    (if-let [k (peek todo)]
      (if (seen k)
        (recur (pop todo) seen)
        (recur (into (pop todo) (refs (get repo k))) (conj seen k)))
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


(defn read-domain
  "Walk text made only of literals ([c d] {…} ^{…} …) to the domain,
   nothing stored: the walker's {:tree id->node :ids [id …] :auto-ids …},
   one id per literal. Throws when text can't be read or holds anything
   but literals."
  ([text] (read-domain text nil))
  ([text session]
   (let [tree (g/parse text)]
     (when (g/failure? tree)
       (throw (ex-info (str "can't read this:\n" (with-out-str (print tree))) {:failure tree})))
     (when-let [x (first (remove literal? (rest tree)))]
       (throw (ex-info (str "only literals here, not " (pr-str x)) {:node x})))
     (walker/walk-each (rest tree) text session))))
