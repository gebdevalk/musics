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
   are children, the rest params. A multi-arity fn names the arity it
   wraps with :arity (its number of fixed args).

   A param spec has a :type (:int :double :ratio :keyword :vector :bool
   :any), a :default, and for a number a :min and :max -- ##-Inf/##Inf
   for an open end. A :default of ##NaN means required: no sensible
   default, it must be set before a tree runs. :choices restricts a
   :keyword. register! throws when a spec lacks what its type needs.

   Types, for :in/:out: :grid (0/1) :weights :pitches :durations :pairs
   :notes :index :any, and :same (an :out that is its first child's).

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

(defn algo-spec
  "Introspect `v` (a var carrying :algo metadata) into a registry entry."
  [v]
  (let [m    (meta v)
        full (symbol (str (ns-name (:ns m))) (str (:name m)))
        a    (or (:algo m) (throw (ex-info (str "algo.tree: " full " has no :algo metadata") {:full full})))
        n-in (count (:in a))
        as   (args (arglist full (:arglists m) (:arity a)))
        [kids ps] (split-at n-in as)
        params (mapv (fn [{:keys [name] :as arg}]
                       (check-spec! full (merge (dissoc arg :kind) (get-in a [:params name]) {:kind (:kind arg)})))
                     ps)]
    (when (< (count kids) n-in)
      (throw (ex-info (str "algo.tree: " full " declares " n-in " children but has " (count kids) " args") {:full full})))
    {:short  (or (:short a) (keyword (:name m)))
     :full   full
     :var    v
     :doc    (some-> (:doc m) str/split-lines first str/trim)
     :in     (vec (:in a))
     :out    (:out a :any)
     :params params}))

(defn call
  "Apply `entry`'s fn to its children's data and param values, in order."
  [{:keys [var params]} children-data values]
  (let [pairs (map vector params values)
        pos   (for [[p v] pairs :when (= :pos (:kind p))] v)
        kw    (for [[p v] pairs :when (= :kw (:kind p)) x [(:name p) v]] x)]
    (apply @var (concat children-data pos kw))))

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

(defn algos
  "Every registered algo: short -> {:full :doc :in :out :params}."
  []
  (into (sorted-map) (map (fn [[k e]] [k (select-keys e [:full :doc :in :out :params])])) @by-short))
