(ns algo.tree
  "Simple algorithm composition: a tcxt threaded through ordinary
   Clojure application.

   A tcxt is a flat map of params plus one reserved key, :data, holding
   the latest result in the chain. Every algo, called
   with its children, returns a NODE, a wrapper (tcxt -> tcxt). A tree of
   nodes is the whole composition:

     (A1 (b2) (A2 (b2) (A3 (b3))))

   Children run left to right, threading the tcxt; params ride along
   untouched, :data is the only key that moves. A raw algo fn is
   (fn [ds p1 p2 ...] result): ds is the vector of its children's :data,
   in order ([] for a leaf), p1..pn the params it reads off the tcxt.

   A child can also be:
     - an uncalled algo -- b2 means (b2);
     - a keyword -- :melody, whose data is that param's value;
     - any other non-fn value -- [60 62 64], a literal, its own data.

   Params: `defalgos` reads each fn's own arg vector at macro time (an
   anonymous fn carries no :arglists). A param keeps its bare key (:lo)
   unless another algo in the SAME defalgos form reads a param of that
   name too; then each colliding one becomes :<prefix>.<name>, prefix
   the algo id's first letter, extended letter by letter until it's
   unique among them -- b2/c1 give :b.lo/:c.lo, b2/b3 give :b2.lo/:b3.lo.
   Metadata on the arg symbol refines a param:
     ^:shared adherence                  -- stays bare despite a collision
     ^{:default 1/8} dur                 -- used when the tcxt lacks it
     ^{:min 0 :max 1 :doc \"...\"} density -- for a GUI, carried as data
   A missing param with no default throws, naming the node and the key.

   A tree is data as well as a fn: (show tree) gives back its
   expression, (params tree) the specs of every key it reads,
   (missing tree tcxt) what a tcxt lacks, (trace tree tcxt) every
   node's :data. (with {:lo 3} (b2)) runs a subtree against overridden
   params -- how two instances of one algo get different values.

   Laziness is a property of the value at :data, chosen per algo.
   Params are immutable through the walk, so a lazy value closed over
   them is safe to realize later. The one rule: an algo with side
   effects must force before returning.")

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(declare show)

(def ^:dynamic *trace*
  "Bound to an atom by `trace`; each node conj's its own result."
  nil)

(defn algo?
  "An algo not yet called with its children."
  [x]
  (boolean (::algo (meta x))))

(defn node?
  "A called algo (or a `with`/param/literal node) -- a tcxt -> tcxt wrapper."
  [x]
  (boolean (::kind (meta x))))

(defn- param-node [k]
  (with-meta (fn [t] (assoc t :data (get t k)))
    {::kind :param ::key k :type ::node}))

(defn- literal-node [v]
  (with-meta (fn [t] (assoc t :data v))
    {::kind :literal ::value v :type ::node}))

(defn- ->node
  "Coerce one child argument to a node (see the ns docstring)."
  [parent c]
  (cond
    (node? c)    c
    (algo? c)  (c)
    (keyword? c) (param-node c)
    (fn? c)      (throw (ex-info (str "algo.tree: " parent " got a plain fn as a child -- "
                                      "only algos, nodes, keywords and literal values")
                                 {:parent parent :child c}))
    :else        (literal-node c)))

(defn as-node
  "`x` as a node: a node as-is, an uncalled algo called with no
   children, a keyword as a param read, anything else as a literal."
  [x]
  (->node 'as-node x))

(defn- param-value [id t {:keys [key] :as spec}]
  (cond
    (contains? t key)         (get t key)
    (contains? spec :default) (:default spec)
    :else (throw (ex-info (str "algo.tree: (" id " ...) needs " key
                               " -- not in the params, and it has no default")
                          {:algo id :missing key}))))

(defn- record! [n data]
  (when *trace* (swap! *trace* conj {:node (show n) :data data}))
  data)

(defn make-algo
  "Lift a raw algo fn into an algo named `id` (a symbol). `specs` are
   the params, in order after the children-data vector: a keyword, or
   {:key k :default v :min .. :max .. :doc ..}.

   Returns (fn [& children] node)."
  [id f specs]
  (let [specs (mapv #(if (keyword? %) {:key %} %) specs)]
    (with-meta
      (fn algo [& children]
        (let [children (mapv #(->node id %) children)
              self     (promise)
              n        (with-meta
                         (fn [t]
                           (let [ts (reduce (fn [acc c] (conj acc (c (peek acc)))) [t] children)
                                 t' (peek ts)
                                 ds (mapv :data (rest ts))
                                 vs (mapv #(param-value id t' %) specs)
                                 v  (try (apply f ds vs)
                                         (catch Exception e
                                           (throw (ex-info (str "algo.tree: " (pr-str (show @self))
                                                                " threw: " (.getMessage e))
                                                           {:node (show @self)} e))))]
                             (assoc t' :data (record! @self v))))
                         {::kind :algo ::id id ::params specs ::children children :type ::node})]
          (deliver self n)
          n))
      {::algo true ::id id ::params specs :type ::algo})))

(defn with
  "A node running `child` against the tcxt merged with `overrides`; the
   outer params come back unchanged, only :data moves out."
  [overrides child]
  (let [child (->node 'with child)
        self  (promise)
        n     (with-meta
                (fn [t]
                  (let [t' (child (merge t overrides))]
                    (assoc t :data (record! @self (:data t')))))
                {::kind :with ::overrides overrides ::children [child] :type ::node})]
    (deliver self n)
    n))

;; ---------------------------------------------------------------------------
;; Trees as data
;; ---------------------------------------------------------------------------

(defn show
  "The expression a node (or algo) was built from."
  [x]
  (let [m (meta x)]
    (case (::kind m)
      :algo    (apply list (::id m) (map show (::children m)))
      :with    (list 'with (::overrides m) (show (first (::children m))))
      :param   (::key m)
      :literal (::value m)
      (if (algo? x) (::id m) x))))

(defn- walk-params
  "[spec ...] every key `n` reads from the tcxt it's given, minus keys a
   `with` above it already supplies (`covered`), first appearance first."
  [n covered]
  (let [m (meta n)]
    (case (::kind m)
      :algo    (concat (mapcat #(walk-params % covered) (::children m))
                       (->> (::params m)
                            (remove (comp covered :key))
                            (map #(assoc % :algo (::id m)))))
      :with    (walk-params (first (::children m)) (into covered (keys (::overrides m))))
      :param   (when-not (covered (::key m)) [{:key (::key m) :algo :param}])
      nil)))

(defn params
  "The param specs `tree` reads from the params map it's run with --
   one per key, first appearance first, each tagged with the :algo that
   reads it (the first one, if several do)."
  [tree]
  (let [tree (->node 'params tree)]
    (->> (walk-params tree #{})
         (reduce (fn [[seen out] {:keys [key] :as s}]
                   (if (seen key) [seen out] [(conj seen key) (conj out s)]))
                 [#{} []])
         second)))

(defn missing
  "The keys `tree` needs that `tcxt` lacks and have no default."
  [tree tcxt]
  (->> (params tree)
       (remove #(or (contains? tcxt (:key %)) (contains? % :default)))
       (mapv :key)))

(defn run
  "Run `tree` against `tcxt` (a params map), returning just its :data.
   Checks every key up front: a missing one throws, listing all of them."
  [tree tcxt]
  (let [tree (->node 'run tree)]
    (when-let [ks (seq (missing tree tcxt))]
      (throw (ex-info (str "algo.tree: " (pr-str (show tree)) " needs "
                           (apply str (interpose " " ks)) " -- not in the params")
                      {:missing (vec ks) :tree (show tree)})))
    (:data (tree tcxt))))

(defn- preview
  "A lazy seq shown as its first `limit` items, then '... when there are
   more, so printing a trace never walks an infinite source. Any
   uncounted seq, not just an unrealized one: a chunked seq reports
   realized? once its first chunk is, however long its tail."
  [d limit]
  (if (and (seq? d) (not (counted? d)))
    (let [head (vec (take (inc limit) d))]
      (if (> (count head) limit) (conj (pop head) '...) head))
    d))

(defn trace
  "Run `tree` against `tcxt` and return every node's result, in the
   order computed (children before parents, the root last):
   [{:node expr :data value} ...]. An unrealized lazy :data shows only
   its first `limit` (default 16) items."
  ([tree tcxt] (trace tree tcxt 16))
  ([tree tcxt limit]
   (let [log (atom [])]
     (binding [*trace* log] (run tree tcxt))
     (mapv #(update % :data preview limit) @log))))

;; A node prints as the expression it was built from, an algo as its id
;; and param keys -- clojure.core/type honors a :type metadata key, which
;; is what these dispatch on, so a tree evaluated at the REPL reads back
;; as what was typed rather than #object[...].
(defmethod print-method ::node [n ^java.io.Writer w]
  (.write w (str "#node " (pr-str (show n)))))

(defmethod print-method ::algo [x ^java.io.Writer w]
  (.write w (str "#algo " (pr-str (cons (::id (meta x)) (map :key (::params (meta x))))))))

;; ---------------------------------------------------------------------------
;; Definition: one form produces the vars and the read-only registry
;; ---------------------------------------------------------------------------

(defn- spec-params
  "A defalgos spec's params (the fn's first arg, the children vector, is
   never a param) -- each {:name .. :meta ..} -- and the expression
   producing the raw fn. Spec shapes: a literal (fn [ds p ...] ...) (a
   single arity), or [f p ...] naming the params explicitly for an
   existing fn."
  [id spec]
  (let [[params f]
        (cond
          (and (seq? spec) ('#{fn clojure.core/fn} (first spec)))
          (let [argv (first (drop-while (complement vector?) (rest spec)))]
            (when-not argv
              (throw (ex-info (str "defalgos " id ": fn needs a single arg vector") {:id id})))
            [(rest argv) spec])

          (vector? spec)
          [(rest spec) (first spec)]

          :else
          (throw (ex-info (str "defalgos " id ": spec must be (fn [ds params...] ...) or [f params...]")
                          {:id id :spec spec})))]
    (doseq [p params]
      (when-not (symbol? p)
        (throw (ex-info (str "defalgos " id ": param " (pr-str p) " must be a plain symbol")
                        {:id id :param p}))))
    [(mapv (fn [p] {:name (name p) :meta (dissoc (meta p) :tag :line :column)}) params) f]))

(defn- unique-prefix
  "Shortest leading slice of `id` (at least one letter) no other name in
   `others` shares at that same length."
  [id others]
  (let [cut (fn [s n] (subs s 0 (min n (count s))))]
    (or (first (for [n (range 1 (inc (count id)))
                     :let [p (cut id n)]
                     :when (not-any? #(= p (cut % n)) others)]
                 p))
        id)))

(defn param-keys
  "{id-name [param-name ...]} -> {id-name [key ...]}, applying the
   collision rule in this ns's docstring. `shared` is a set of
   [id-name param-name] pairs that keep their bare key regardless."
  ([id->params] (param-keys id->params #{}))
  ([id->params shared]
   (let [owners (reduce-kv (fn [m id ps] (reduce #(update %1 %2 (fnil conj #{}) id) m ps))
                           {} id->params)]
     (into {}
           (for [[id ps] id->params]
             [id (mapv (fn [p]
                         (let [owns   (owners p)
                               others (disj (set (remove #(shared [% p]) owns)) id)]
                           (if (or (shared [id p]) (= 1 (count owns)))
                             (keyword p)
                             (keyword (str (unique-prefix id others) "." p)))))
                       ps)])))))

(defn- warn-cross-form-collisions!
  "A bare key this form defines that an algo from an EARLIER defalgos
   form in this ns also reads -- the two will silently share it."
  [entries ks]
  (when-let [v (get (ns-interns *ns*) 'algos)]
    (when (bound? v)
      (let [ids     (set (map (comp keyword name :id) entries))
            earlier (for [[k m] @v :when (not (ids k)) spec (::params (meta m))]
                      [(:key spec) k])
            by-key  (group-by first earlier)]
        (doseq [{:keys [id]} entries
                k (ks (name id))
                :when (by-key k)]
          (binding [*out* *err*]
            (println "defalgos: WARNING" id "reads" k "which"
                     (mapv second (by-key k)) "(an earlier defalgos form) also reads -- they share it")))))))

(defmacro defalgos
  "Define several algos at once.

   (defalgos
     b2 (fn [_ lo hi] (range lo hi))
     A1 (fn [seqs] (apply map vector seqs))
     eu [rhythm/euclidean-rhythm k n])   ; existing fn, params named

   An [f p ...] spec's f is called as (f p ...), without the children
   vector -- for lifting a plain fn that has no children at all.

   Emits (def b2 ...) etc., and (def algos {:b2 b2 ...}) -- a plain
   map, built at load time. A second defalgos form in the same ns merges
   its own entries into that ns's existing `algos` rather than
   replacing it (and warns when it reads a bare key an earlier form's
   algo also reads)."
  [& specs]
  (let [entries (for [[id spec] (partition 2 specs)]
                  (let [[ps f] (spec-params id spec)]
                    {:id id :ps ps
                     :f (if (vector? spec) `(fn [~'_ & args#] (apply ~f args#)) f)}))
        shared  (set (for [{:keys [id ps]} entries, p ps :when (:shared (:meta p))]
                       [(name id) (:name p)]))
        ks      (param-keys (into {} (map (fn [{:keys [id ps]}] [(name id) (mapv :name ps)])) entries)
                            shared)]
    (warn-cross-form-collisions! entries ks)
    `(do
       ~@(for [{:keys [id f ps]} entries]
           `(def ~id (make-algo '~id ~f
                                  ~(mapv (fn [k p] (merge (dissoc (:meta p) :shared) {:key k}))
                                         (ks (name id)) ps))))
       (def ~'algos
         (merge (let [v# (get (ns-interns '~(ns-name *ns*)) '~'algos)]
                  (when (and v# (bound? v#)) @v#))
                ~(into {} (for [{:keys [id]} entries] [(keyword id) id])))))))
