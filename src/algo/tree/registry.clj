(ns algo.tree.registry
  "Every algorithm a tree can use, found by introspection.

   An algorithm is an ordinary defn carrying an :algo attr-map:

     (defn tilt-probabilities
       \"Softmax over ranks...\"
       {:algo {:short  :tilt
               :in     [:weights]          ; leading args are children, by type
               :out    :weights
               :params {:adherence {:type :double :min -1.0 :max 1.0 :default 0.5
                                    :doc \"how strongly rank predicts sounding\"}}}}
       [psi-vals adherence] ...)

   register! reads the var's :arglists (param names and order; keyword
   args' :or defaults), :doc and :algo map. The first (count :in) args
   are children, the rest params -- or name the child args with
   :children when they aren't the leading ones. A multi-arity fn names
   the arity it wraps with :arity (its number of fixed args).

   Two keys turn a fn that yields ONE value into a sequence source,
   without a wrapper:
     :repeat :len           call it :len times -> a vector (a sampler)
     :pull {:via :value     call it once for a generator (under :via in
            :args [:target]} the map it returns, or the result itself),
                            then pull :len values, each call given :args
   The count param (:len, or :pull's :count) gets a default spec; it
   and :args are params like any other, set in the tctx.

   A param spec has a :type (:int :double :ratio :keyword :string
   :vector :map :fn :bool :any), a :default, and for a number a :min and
   :max -- ##-Inf/##Inf for an open end. A :default of ##NaN means
   required: no sensible default, it must be set before a tree runs.
   :choices restricts a :keyword or :string. register! throws when a
   spec lacks what its type needs.

   Types, for :in/:out: :grid (0/1) :weights :pitches :durations :pairs
   :notes :index :numbers :onsets (times) :points (vectors per step)
   :layers (parallel patterns) :strokes :any, and :same (an :out that is
   its first child's).

   Lookups take either name: the short keyword or the full symbol."
  (:require [clojure.string :as str]))

(defonce ^:private by-short (atom {}))
(defonce ^:private by-full (atom {}))

(def ^:private numeric? #{:int :double :ratio})

(defn nan?
  "True for ##NaN, the \"required, not set yet\" default."
  [v]
  (and (double? v) (Double/isNaN v)))

(defn- arglist
  "The arg vector to wrap: the only one, or the one with `arity` fixed args."
  [full arglists arity]
  (let [fixed (fn [al] (count (take-while #(not= '& %) al)))]
    (cond
      arity (or (first (filter #(and (= arity (fixed %)) (not (some #{'&} %))) arglists))
                (first (filter #(= arity (fixed %)) arglists))
                (throw (ex-info (str "algo.tree: " full " has no arity " arity) {:full full})))
      (= 1 (count arglists)) (first arglists)
      :else (throw (ex-info (str "algo.tree: " full " has several arities -- name one with :arity")
                            {:full full :arglists arglists})))))

(defn- args
  "An arg vector -> [{:name :kind :default?}]: fixed args :pos, keyword
   args (& {:keys [..] :or {..}}) :kw with their :or default."
  [al]
  (let [[fixed [_ rest-arg]] (split-with #(not= '& %) al)]
    (concat (map (fn [a] {:name (keyword (name a)) :kind :pos}) fixed)
            (when (map? rest-arg)
              (for [k (:keys rest-arg)]
                (cond-> {:name (keyword (name k)) :kind :kw}
                  (contains? (:or rest-arg) k) (assoc :default (get (:or rest-arg) k))))))))

(defn- check-spec!
  "Throw unless a param spec carries what its type needs."
  [full {:keys [name type min max] :as spec}]
  (let [miss (fn [what] (throw (ex-info (str "algo.tree: " full " param " name " needs " what) {:full full :param name})))]
    (when-not type (miss ":type"))
    (when-not (contains? spec :default) (miss ":default (##NaN if it has none)"))
    (when (numeric? type)
      (when-not (number? min) (miss ":min (##-Inf for none)"))
      (when-not (number? max) (miss ":max (##Inf for none)")))
    spec))

(def ^:private count-spec
  {:type :int :min 1 :max 1024 :default 16 :doc "how many values"})

(defn- extra-params
  "Params the fn doesn't take itself: the :repeat/:pull count, :pull's :args."
  [a]
  (let [cnt (or (:repeat a) (when (:pull a) (get-in a [:pull :count] :len)))]
    (for [nm (distinct (remove nil? (cons cnt (get-in a [:pull :args]))))]
      (merge {:name nm :kind :extra} (when (= nm cnt) count-spec)))))

(defn- category-of
  "The category of an algo without its own :category: the namespace
   segment after algo. (algo.rhythmic.world -> \"rhythmic\", algo.random
   -> \"random\"), or \"yours\" for one defined outside algo/."
  [full]
  (let [[top sub] (str/split (namespace full) #"\.")]
    (if (= "algo" top) (or sub "other") "yours")))

(defn algo-spec
  "Introspect `v` (a var carrying :algo metadata) into a registry entry."
  [v]
  (let [m    (meta v)
        full (symbol (str (ns-name (:ns m))) (str (:name m)))
        a    (or (:algo m) (throw (ex-info (str "algo.tree: " full " has no :algo metadata") {:full full})))
        n-in (count (:in a))
        as   (args (arglist full (:arglists m) (:arity a)))
        kids (or (:children a) (map :name (take n-in as)))
        kid? (set kids)
        spec (fn [{:keys [name] :as arg}]
               (check-spec! full (merge (dissoc arg :kind) (get-in a [:params name]) {:kind (:kind arg)})))]
    (when (or (not= n-in (count kids)) (not-every? (set (map :name as)) kids))
      (throw (ex-info (str "algo.tree: " full " declares " n-in " children but its args give " (vec kids)) {:full full})))
    {:short    (or (:short a) (keyword (:name m)))
     :full     full
     :category (or (:category a) (category-of full))
     :var      v
     :doc      (some-> (:doc m) str/split-lines first str/trim)
     :in       (vec (:in a))
     :out      (:out a :any)
     :children (vec kids)
     :args     (mapv #(cond-> (select-keys % [:name :kind]) (kid? (:name %)) (assoc :kind :child)) as)
     :params   (mapv spec (concat (remove (comp kid? :name) as) (extra-params a)))
     :repeat   (:repeat a)
     :pull     (when-let [p (:pull a)] (merge {:count :len} p))}))

(defn call
  "Apply `entry`'s fn to its children's data and param values."
  [{:keys [var params args children repeat pull]} children-data values]
  (let [v    (zipmap (map :name params) values)
        kids (zipmap children children-data)
        arg  (fn [{:keys [name kind]}] (if (= :child kind) (kids name) (v name)))
        pos  (map arg (filter #(#{:child :pos} (:kind %)) args))
        kw   (for [a args :when (= :kw (:kind a)) x [(:name a) (arg a)]] x)
        f    #(apply @var (concat pos kw))]
    (cond
      repeat (vec (repeatedly (v repeat) f))
      pull   (let [g  (cond-> (f) (:via pull) (get (:via pull)))
                   xs (map v (:args pull))]
               (vec (repeatedly (v (:count pull)) #(apply g xs))))
      :else  (f))))

(defn register!
  "Introspect var `v` and register it under its short and full names.
   Returns the entry."
  [v]
  (let [{:keys [short full] :as e} (algo-spec v)]
    (when-let [other (get @by-short short)]
      (when (not= full (:full other))
        (throw (ex-info (str "algo.tree: short name " short " is already " (:full other)) {:short short}))))
    (swap! by-short assoc short e)
    (swap! by-full assoc full short)
    e))

(defn algo
  "The registry entry for a short keyword or a full symbol, or nil."
  [nm]
  (if (keyword? nm)
    (get @by-short nm)
    (some->> (get @by-full (symbol nm)) (get @by-short))))

(defn full-name
  "Short keyword -> full symbol."
  [short]
  (:full (algo short)))

(defn short-name
  "Full symbol -> short keyword."
  [full]
  (get @by-full (symbol full)))

(defn state
  "The registry's current value -- a new one after every register!, so
   a caller can cache what it derives with identical? against it."
  []
  @by-short)

(defn algos
  "Every registered algo: short -> {:full :doc :in :out :params}."
  []
  (into (sorted-map) (map (fn [[k e]] [k (select-keys e [:full :category :doc :in :out :params])])) @by-short))
