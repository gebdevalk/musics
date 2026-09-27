(ns algo.mapper
  "Simple algorithm composition: a tcxt threaded through ordinary
   Clojure application.

   A tcxt is a flat map of params plus one reserved key, :data, holding
   the latest result in the chain. Every algo is a MAPPER: a fn that
   takes child wrappers and returns a wrapper (tcxt -> tcxt).

   Composition is plain application:

     (A1 (b2) (A2 (b2) (A3 (b3))))

   Each mapper call returns a fresh wrapper. Children run left to right,
   threading the tcxt; params ride along untouched, :data is the only
   key that moves. A raw algo fn is (fn [ds p1 p2 ...] result): ds is the
   vector of its children's :data, in order ([] for a leaf), p1..pn name
   the params it reads off the tcxt.

   Param keys: `defalgos` reads each fn's own arg vector at macro time
   (an anonymous fn carries no :arglists, so reflecting at run time
   finds nothing). A param keeps its bare key (:lo) unless another algo
   in the SAME defalgos form reads a param of that name too; then each
   colliding one becomes :<prefix>.<name>, prefix the algo id's first
   letter, extended letter by letter until it's unique among the algos
   sharing that name -- b2/c1 both reading lo give :b.lo/:c.lo, b2/b3
   give :b2.lo/:b3.lo. Collisions across separate defalgos forms can't
   be seen. Each mapper's resolved keys are on its own metadata,
   (:params (meta b2)), for a GUI to list what's editable.

   Laziness is a property of the value at :data, chosen per algo.
   Params are immutable through the walk, so a lazy value closed over
   them is safe to realize later. The one rule: an algo with side
   effects must force before returning.")

;; ---------------------------------------------------------------------------
;; Mapper construction
;; ---------------------------------------------------------------------------

(defn make-mapper
  "Lift a raw algo fn into a mapper. `ks` are the tcxt keys whose values
   become f's params, in order, after the children-data vector.

   Returns (fn [& children] wrapper), each child itself a wrapper."
  [f ks]
  (with-meta
    (fn mapper [& children]
      (fn [t]
        (let [ts (vec (reductions (fn [acc c] (c acc)) t children))
              t' (peek ts)
              ds (mapv :data (rest ts))]
          (assoc t' :data (apply f ds (map #(get t' %) ks))))))
    {:params ks}))

(defn run
  "Run `tree` against `params`, returning just its :data."
  [tree params]
  (:data (tree params)))

;; ---------------------------------------------------------------------------
;; Definition: one form produces the vars and the read-only registry
;; ---------------------------------------------------------------------------

(defn- spec-params
  "A defalgos spec's param symbols (the fn's first arg, the children
   vector, is never a param) and the expression producing the raw fn.
   Spec shapes: a literal (fn [ds p ...] ...) (a single arity), or
   [f p ...] naming the params explicitly for an existing fn."
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
    [(mapv name params) f]))

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
   collision rule in this ns's docstring."
  [id->params]
  (let [owners (reduce-kv (fn [m id ps] (reduce #(update %1 %2 (fnil conj #{}) id) m ps))
                          {} id->params)]
    (into {}
          (for [[id ps] id->params]
            [id (mapv (fn [p]
                        (let [others (disj (owners p) id)]
                          (if (empty? others)
                            (keyword p)
                            (keyword (str (unique-prefix id others) "." p)))))
                      ps)]))))

(defmacro defalgos
  "Define several algos at once.

   (defalgos
     b2 (fn [_ lo hi] (range lo hi))
     A1 (fn [seqs] (apply map vector seqs))
     eu [rhythm/euclidean-rhythm k n])   ; existing fn, params named

   An [f p ...] spec's f is called as (f p ...), without the children
   vector -- for lifting a plain fn that has no children at all.

   Emits (def b2 ...) etc., and (def mappers {:b2 b2 ...}) -- a plain
   map, built at load time. A second defalgos form in the same ns merges
   its own entries into that ns's existing `mappers` rather than
   replacing it."
  [& specs]
  (let [entries (for [[id spec] (partition 2 specs)]
                  (let [[ps f] (spec-params id spec)]
                    {:id id :ps ps
                     :f (if (vector? spec) `(fn [~'_ & args#] (apply ~f args#)) f)}))
        ks      (param-keys (into {} (map (juxt (comp name :id) :ps)) entries))]
    `(do
       ~@(for [{:keys [id f]} entries]
           `(def ~id (make-mapper ~f ~(ks (name id)))))
       (def ~'mappers
         (merge (let [v# (get (ns-interns '~(ns-name *ns*)) '~'mappers)]
                  (when (and v# (bound? v#)) @v#))
                ~(into {} (for [{:keys [id]} entries] [(keyword id) id])))))))
