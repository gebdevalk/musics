(ns musics.algo.tree
  "Algorithm composition: a TREE of registered algorithms, and a TCTX
   holding the settings it runs with -- two separate things.

   The tree is an immutable value: what is computed. Build it by calling
   an algo's constructor with its children:

     (def riff (zip (pulses->durations euclid) (cycle> scale)))   ; musics.algo.tree.lib

   A child may be a node, a bare constructor (`scale` = `(scale)`), a
   keyword (reads that param at run time) or a literal value. The child
   count and types are checked right here, against the registry
   (musics.algo.tree.registry): `(zip (tilt ...) ...)` fails at once, naming
   both. `(euclid :as :bass)` names an instance, so its params get their
   own keys (:bass/k).

   The tctx is an atom of settings, derived from a tree but not holding
   it -- the model a GUI watches:

     (def tctx (t/tctx riff))       ; {:params {:k 3 ...} :specs {:k {...} ...}}
     (t/setp! tctx :k 5)            ; = (swap! tctx assoc-in [:params :k] 5)
     (t/run riff tctx)              ; or (t/run riff {:k 5}), a plain map

   Every value is checked against its spec by the atom's validator, so a
   plain swap! can't store one out of range. A ##NaN value means unset: a
   required param. One tree can run against several tctxs; (fit! tctx
   tree) prepares a tctx for another tree, keeping its values.

   Keys: a param keeps its bare name (:k) unless two different algos in
   the tree read that name with different specs -- then each becomes
   :<short>.<name> (:euclid.n). A named instance's are :<as>/<name>.

   Laziness is a property of each algo's value; params are fixed for
   one run, so a lazy value closed over them is safe to realize later.
   An algo with side effects must force before returning."
  (:require [musics.algo.tree.registry :as reg]
            [clojure.string :as str]
            [musics.registries :as registries]))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(defrecord Node [kind entry children as value key])

(declare show)

(defn node? [x] (instance? Node x))

(defn algo?
  "A constructor, not yet called with its children."
  [x]
  (boolean (::entry (meta x))))

(defn- ->node [parent c]
  (cond
    (node? c)    c
    (algo? c)    (c)
    (keyword? c) (map->Node {:kind :param :key c})
    (fn? c)      (throw (ex-info (str "musics.algo.tree: " parent " got a plain fn as a child -- "
                                      "use a constructor, a node, a keyword or a literal")
                                 {:parent parent}))
    :else        (map->Node {:kind :literal :value c})))

(defn as-node
  "`x` as a node (see the ns docstring for what a child may be)."
  [x]
  (->node 'as-node x))

(defn out-type
  "What a node produces: its algo's :out (:same = its first child's),
   or :any for a param read or a literal."
  [n]
  (if (= :algo (:kind n))
    (let [o (get-in n [:entry :out])]
      (if (= :same o) (out-type (first (:children n))) o))
    :any))

(defn fits?
  "Whether a node producing `got` fits a slot wanting `want`: equal, or
   either is :any."
  [want got]
  (or (= :any want) (= :any got) (= want got)))

(defn- check! [{:keys [short in]} children]
  (when (not= (count in) (count children))
    (throw (ex-info (str "musics.algo.tree: " (name short) " takes " (count in) " child"
                         (when (not= 1 (count in)) "ren") ", got " (count children))
                    {:algo short})))
  (doseq [[i want c] (map vector (range) in children)
          :let [got (out-type c)]
          :when (not (fits? want got))]
    (throw (ex-info (str "musics.algo.tree: " (name short) ": child " (inc i) " should be " want
                         ", " (pr-str (show c)) " gives " got)
                    {:algo short :child (show c) :want want :got got}))))

(defn constructor
  "The node constructor for registry `entry`: called with its children,
   and optionally a trailing :as name, it returns a checked node."
  [{:keys [short] :as entry}]
  (with-meta
    (fn [& args]
      (let [[args as] (if (= :as (last (butlast args))) [(drop-last 2 args) (last args)] [args nil])
            children  (mapv #(->node (name short) %) args)]
        (check! entry children)
        (map->Node {:kind :algo :entry entry :children children :as as})))
    {::entry entry :type ::algo}))

(defn show
  "The expression a node (or constructor) stands for."
  [x]
  (cond
    (node? x) (case (:kind x)
                :algo    (concat (list (symbol (name (get-in x [:entry :short]))))
                                 (map show (:children x))
                                 (when-let [as (:as x)] [:as as]))
                :param   (:key x)
                :literal (:value x))
    (algo? x) (symbol (name (:short (::entry (meta x)))))
    :else     x))

(defmethod print-method Node [n ^java.io.Writer w]
  (.write w (str "#node " (pr-str (show n)))))

(defmethod print-method ::algo [x ^java.io.Writer w]
  (let [{:keys [short params]} (::entry (meta x))]
    (.write w (str "#algo " (pr-str (cons (symbol (name short)) (map :name params)))))))

;; ---------------------------------------------------------------------------
;; Keys: a pure function of the tree
;; ---------------------------------------------------------------------------

(defn- occurrences
  "Every param read in `n`, children before their parent: [{:path :short
   :as :p}] for an algo's params, [{:path :read k}] for a keyword child."
  [n path]
  (case (:kind n)
    :algo  (concat (mapcat (fn [i c] (occurrences c (conj path i))) (range) (:children n))
                   (for [p (get-in n [:entry :params])]
                     {:path path :short (get-in n [:entry :short]) :as (:as n) :p p}))
    :param [{:path path :read (:key n)}]
    nil))

(defn- decide-keys
  "Occurrences -> the same, each with its :key (see the ns docstring)."
  [occs]
  (let [shared? (->> (remove #(or (:read %) (:as %)) occs)
                     (group-by (comp :name :p))
                     (into {} (map (fn [[nm os]] [nm (apply = (map #(dissoc (:p %) :doc) os))]))))]
    (for [{:keys [read as short p] :as o} occs]
      (assoc o :key (cond read               read
                          as                 (keyword (name as) (name (:name p)))
                          (shared? (:name p)) (:name p)
                          :else              (keyword (str (name short) "." (name (:name p)))))))))

(defn- spec-of [{:keys [read short p]}]
  (if read
    {:type :any :default ##NaN :algo :read :doc "read by a keyword child"}
    (-> p (dissoc :kind :name) (assoc :algo short :param (:name p)))))

(defn- resolve-tree
  "[tree' specs]: tree' carries each algo node's param keys (:key, a
   vector in param order); specs is [[key spec] ...], first read first."
  [tree]
  (let [occs  (decide-keys (occurrences tree []))
        by    (group-by :path (remove :read occs))
        walk  (fn walk [n path]
                (if (= :algo (:kind n))
                  (assoc n :key (mapv :key (get by path))
                           :children (mapv (fn [i c] (walk c (conj path i))) (range) (:children n)))
                  n))
        specs (reduce (fn [acc o] (if (some #(= (:key o) (first %)) acc) acc (conj acc [(:key o) (spec-of o)])))
                      [] occs)]
    [(walk tree []) specs]))

(defn param-keys
  "Every param `tree` reads: [{:key .. :type .. :default .. :min .. :max
   .. :algo .. :doc ..}], first read first."
  [tree]
  (mapv (fn [[k s]] (assoc s :key k)) (second (resolve-tree (as-node tree)))))

;; ---------------------------------------------------------------------------
;; The tctx: an atom of settings
;; ---------------------------------------------------------------------------

(def nan? reg/nan?)

(defn- problem
  "Why value `v` doesn't fit `spec`, or nil."
  [{:keys [type min max choices]} v]
  (cond
    (nan? v) nil
    (and (= :int type) (not (integer? v)))        "an integer"
    (and (= :double type) (not (number? v)))      "a number"
    (and (= :ratio type) (not (rational? v)))     "a ratio or integer"
    (and (= :vector type) (not (sequential? v)))  "a vector"
    (and (= :bool type) (not (boolean? v)))       "true or false"
    (and (= :keyword type) (not (keyword? v)))    "a keyword"
    (and (= :string type) (not (string? v)))      "a string"
    (and (= :map type) (not (map? v)))            "a map"
    (and (= :fn type) (not (ifn? v)))             "a function"
    (and choices (not (some #{v} choices)))       (str "one of " (pr-str choices))
    (and (number? min) (number? v) (< v min))     (str "at least " min)
    (and (number? max) (number? v) (> v max))     (str "at most " max)))

(defn- check-value! [k v spec]
  (when-let [why (problem spec v)]
    (throw (ex-info (str "musics.algo.tree: " k " " (pr-str v) " should be " why
                         " (" (name (:algo spec)) (when (:doc spec) (str ", " (:doc spec))) ")")
                    {:key k :value v :spec spec}))))

(defn- validate! [{:keys [params specs]}]
  (doseq [[k v] params]
    (check-value! k v (or (get specs k)
                          (throw (ex-info (str "musics.algo.tree: " k " is not a param of this tctx") {:key k})))))
  true)

(defn- defaults [specs]
  (into {} (map (fn [[k s]] [k (:default s)])) specs))

(defn tctx
  "A new tctx for `tree`: an atom of {:params :specs}, every param at its
   default (or at `overrides`), each value checked against its spec on
   every change. The tree itself isn't stored."
  ([tree] (tctx tree {}))
  ([tree overrides]
   (registries/log! :tctx)
   (let [specs (second (resolve-tree (as-node tree)))
         m     {:specs (into {} (map-indexed (fn [i [k s]] [k (assoc s :order i)])) specs)
                :params (merge (defaults specs) overrides)}]
     (validate! m)
     (atom m :validator validate!))))

(defn tctx? [x] (and (instance? clojure.lang.IAtom x) (map? @x) (contains? @x :specs)))

(defn fit!
  "Prepare `tctx` for `tree`: add every key the tree reads that tctx lacks,
   at its default. Existing keys and values stay. Returns the params."
  [tctx tree]
  (:params (swap! tctx (fn [{:keys [specs] :as m}]
                        (reduce (fn [m [k s]]
                                  (if (contains? specs k) m
                                      (-> m (assoc-in [:specs k] (assoc s :order (count (:specs m))))
                                          (assoc-in [:params k] (:default s)))))
                                m (second (resolve-tree (as-node tree))))))))

(defn setp!
  "assoc for a tctx's params, checked by its validator: (setp! tctx :k 5),
   (setp! tctx :k 5 :n 16), or a map (setp! tctx {:k 5 :n 16}). All
   values land in one swap!, so a bad one leaves every value as it was.
   Returns the params."
  ([tctx m] (:params (swap! tctx update :params merge m)))
  ([tctx k v & kvs] (:params (swap! tctx update :params #(apply assoc % k v kvs)))))

(defn describe
  "Print a table of a tctx's (or a tree's) params: key, value, range,
   default, algo, doc."
  [x]
  (let [[params specs] (if (tctx? x)
                         [(:params @x) (sort-by (comp :order val) (:specs @x))]
                         (let [s (second (resolve-tree (as-node x)))] [(defaults s) s]))
        fmt (fn [v] (cond (nan? v) "required" (nil? v) "" :else (pr-str v)))]
    (doseq [[k {:keys [min max default algo doc] :as s}] specs]
      (println (format "%-14s %-12s %-16s %-10s %-12s %s"
                       (pr-str k) (fmt (get params k))
                       (if (number? min) (str min ".." max) (name (:type s)))
                       (fmt default) (name algo) (or doc ""))))))

;; ---------------------------------------------------------------------------
;; Running
;; ---------------------------------------------------------------------------

(def ^:dynamic *trace* nil)

(defn- evaluate [n vals]
  (case (:kind n)
    :literal (:value n)
    :param   (get vals (:key n))
    :algo    (let [ds (mapv #(evaluate % vals) (:children n))
                   v  (try (reg/call (:entry n) ds (mapv #(get vals %) (:key n)))
                           (catch Exception e
                             (throw (ex-info (str "musics.algo.tree: " (pr-str (show n)) " threw: " (.getMessage e))
                                             {:node (show n)} e))))]
               (when *trace* (swap! *trace* conj {:node (show n) :data v}))
               v)))

(defn run
  "Run `tree` with the settings in `src`: a tctx, or a plain map (keys it
   lacks take their defaults, the values it has are checked against
   their specs). Throws, listing them, when a required param is unset
   or a tctx doesn't cover a key the tree reads."
  [tree src]
  (let [[t specs] (resolve-tree (as-node tree))
        given     (if (tctx? src) (:params @src) src)]
    (when (tctx? src)
      (when-let [ks (seq (remove (:specs @src) (map first specs)))]
        (throw (ex-info (str "musics.algo.tree: this tctx has no " (str/join " " ks) " -- (fit! tctx tree) adds them")
                        {:missing (vec ks)}))))
    (when-not (tctx? src)
      (doseq [[k s] specs :when (contains? given k)] (check-value! k (get given k) s)))
    (let [vals  (merge given (into {} (for [[k s] specs] [k (get given k (:default s))])))
          unset (for [[k] specs :when (nan? (get vals k))] k)]
      (when (seq unset)
        (throw (ex-info (str "musics.algo.tree: " (pr-str (show t)) " needs " (str/join " " unset) " -- not set")
                        {:missing (vec unset) :tree (show t)})))
      (evaluate t vals))))

(defn- preview [d limit]
  (if (and (seq? d) (not (counted? d)))
    (let [head (vec (take (inc limit) d))]
      (if (> (count head) limit) (conj (pop head) '...) head))
    d))

(defn trace
  "Run `tree` with `src` and return every node's result, children first:
   [{:node expr :data value} ...]. A lazy seq shows its first `limit`
   (default 16) items."
  ([tree src] (trace tree src 16))
  ([tree src limit]
   (let [log (atom [])]
     (binding [*trace* log] (run tree src))
     (mapv #(update % :data preview limit) @log))))

;; ---------------------------------------------------------------------------
;; Defining and exposing algos
;; ---------------------------------------------------------------------------

(defmacro defalgo
  "defn + register + constructor: the raw fn becomes name* (callable
   directly), name the node constructor.

     (defalgo up \"Shift pitches.\"
       {:algo {:in [:pitch] :out :pitch
               :params {:by {:type :int :min -48 :max 48 :default 12}}}}
       [pitches by] (map #(some-> % (+ by)) pitches))"
  [nm & fdecl]
  (let [[doc fdecl]  (if (string? (first fdecl)) [(first fdecl) (rest fdecl)] [nil fdecl])
        [attr fdecl] (if (map? (first fdecl)) [(first fdecl) (rest fdecl)] [{} fdecl])
        attr         (update attr :algo #(merge {:short (keyword (name nm))} %))
        raw          (symbol (str (name nm) "*"))]
    `(do (defn ~raw ~@(when doc [doc]) ~attr ~@fdecl)
         (def ~nm (constructor (reg/register! (var ~raw)))))))

(defn- short-of [v]
  (or (-> v meta :algo :short) (keyword (-> v meta :name))))

(defmacro expose
  "Register each annotated var and def its constructor under its short
   name (its :short, else its own name): (expose rhythm/euclidean-rhythm)
   defines `euclid`."
  [& syms]
  `(do ~@(for [s syms
               :let [v (resolve s)]]
           (if (-> v meta :algo)
             `(def ~(symbol (name (short-of v))) (constructor (reg/register! (var ~s))))
             (throw (ex-info (str "musics.algo.tree: " s " has no :algo metadata") {:sym s}))))))

(defmacro expose-ns
  "expose every var carrying :algo metadata in each namespace."
  [& nss]
  `(expose ~@(for [n nss
                   :let [_ (require n)]
                   [s v] (sort-by key (ns-publics n))
                   :when (-> v meta :algo)]
               (symbol (name n) (name s)))))

;; ---------------------------------------------------------------------------
;; The registry and live playback, from here
;; ---------------------------------------------------------------------------

(def algo       reg/algo)
(def full-name  reg/full-name)
(def short-name reg/short-name)
(def algos      reg/algos)

(defn- live [f] (requiring-resolve (symbol "musics.algo.tree.live" (name f))))

(defn play!   "Play `tree` once with `src` (a tctx or map)."            [tree src]      ((live 'play!) tree src))
(defn live!   "Bind `name` to `tree` + `tctx`; a generator tree also gets an endless voice." [name tree tctx] (registries/log! :live!) ((live 'live!) name tree tctx))
(defn retree! "Swap `name`'s tree, keeping its tctx."                    [name tree]     ((live 'retree!) name tree))
(defn stop!   "Stop every voice following `name`."                     [name]          ((live 'stop!) name))

(defn build-tree
  "Compose a tree by drag and drop (musics.gui.composer) and return
   [tree tctx] -- blocks until Finalize; nil when the window is closed.
     (build-tree)              a new tree
     (build-tree tree)         edit an existing one
     (build-tree tree tctx)    ... keeping its settings
   The same with :repl first -- (build-tree :repl), (build-tree :repl
   tree) -- composes at the REPL instead, step for step the same
   (musics.algo.tree.builder/repl-build)."
  [& args]
  (registries/log! :build-tree)
  (let [repl?       (= :repl (first args))
        [tree tctx] (if repl? (rest args) args)
        draft       (requiring-resolve 'musics.algo.tree.builder/draft)
        d           (if tree (draft tree) (draft))]
    (if repl?
      ((requiring-resolve 'musics.algo.tree.builder/repl-build) d tctx)
      @((requiring-resolve 'musics.gui.composer/open!) d tctx))))

(defn gui
  "A settings window (musics.gui.params): (gui tctx), (gui tree) -- a new
   tctx for it -- or (gui tree tctx), which also previews the result and
   has Play once / Live as. Returns the tctx. Needs a display."
  ([x] (registries/log! :gui) ((requiring-resolve 'musics.gui.params/open!) x))
  ([tree tctx] (registries/log! :gui) ((requiring-resolve 'musics.gui.params/open!) tree tctx)))
