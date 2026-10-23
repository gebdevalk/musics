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
            [musics.common.music-elements :as el]
            [musics.domain.context :as c]
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

(defn- label
  "An algo's short name as written: euclid, or pitch/lorenz for a
   specialised one (musics.algo.tree.half)."
  [short]
  (symbol short))

(defn- check! [{:keys [short in]} children]
  (when (not= (count in) (count children))
    (throw (ex-info (str "musics.algo.tree: " (label short) " takes " (count in) " child"
                         (when (not= 1 (count in)) "ren") ", got " (count children))
                    {:algo short})))
  (doseq [[i want c] (map vector (range) in children)
          :let [got (out-type c)]
          :when (not (fits? want got))]
    (throw (ex-info (str "musics.algo.tree: " (label short) ": child " (inc i) " should be " want
                         ", " (pr-str (show c)) " gives " got)
                    {:algo short :child (show c) :want want :got got}))))

(defn constructor
  "The node constructor for registry `entry`: called with its children,
   and optionally a trailing :as name, it returns a checked node."
  [{:keys [short] :as entry}]
  (with-meta
    (fn [& args]
      (let [[args as] (if (= :as (last (butlast args))) [(drop-last 2 args) (last args)] [args nil])
            children  (mapv #(->node (label short) %) args)]
        (check! entry children)
        (map->Node {:kind :algo :entry entry :children children :as as})))
    {::entry entry :type ::algo}))

(defn show
  "The expression a node (or constructor) stands for."
  [x]
  (cond
    (node? x) (case (:kind x)
                :algo    (concat (list (label (get-in x [:entry :short])))
                                 (map show (:children x))
                                 (when-let [as (:as x)] [:as as]))
                :param   (:key x)
                :literal (:value x))
    (algo? x) (label (:short (::entry (meta x))))
    :else     x))

(defmethod print-method Node [n ^java.io.Writer w]
  (.write w (str "#node " (pr-str (show n)))))

(defmethod print-method ::algo [x ^java.io.Writer w]
  (let [{:keys [short params]} (::entry (meta x))]
    (.write w (str "#algo " (pr-str (cons (label short) (map :name params)))))))

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
  "Occurrences -> the same, each with its :key (see the ns docstring) and
   :at, the context names it is read under, most specific first: the
   instance's (?bass.k), the algo's (?euclid.k), the plain one (?k).
   A tctx writes a key under the name that matches it."
  [occs]
  (let [shared? (->> (remove #(or (:read %) (:as %)) occs)
                     (group-by (comp :name :p))
                     (into {} (map (fn [[nm os]] [nm (apply = (map #(dissoc (:p %) :doc) os))]))))]
    (for [{:keys [read as short p] :as o} occs
          :let [pn  (name (or read (:name p)))
                key (cond read               read
                          as                 (keyword (name as) pn)
                          (shared? (:name p)) (:name p)
                          :else              (keyword (str (label short) "." pn)))]]
      (assoc o :key key
               :own (str "?" (if (namespace key) (str (namespace key) "." (name key)) (name key)))
               :at (vec (remove nil? [(when as (str "?" (name as) "." pn))
                                      (when-not read (str "?" (label short) "." pn))
                                      (str "?" pn)]))))))

(defn- spec-of [{:keys [read short p at own]}]
  (if read
    {:type :any :default ##NaN :algo :read :doc "read by a keyword child" :at at :own own}
    (-> p (dissoc :kind :name) (assoc :algo short :param (:name p) :at at :own own))))

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
;; The tctx: a Context of settings
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

(defn- coerce
  "A value as written in text (?key:D.major is a keyword, ?on?:true too),
   or a music key's own (a !key: is a Key), in the type `spec` wants."
  [{:keys [type]} v]
  (cond
    (and (= :string type) (instance? musics.common.music_elements.Key v)) (el/key->str v)
    (and (= :string type) (keyword? v))  (subs (str v) 1)
    (and (= :keyword type) (string? v))  (keyword v)
    (and (= :bool type) (#{:true :false} v)) (= :true v)
    (and (= :double type) (rational? v)) (double v)
    :else v))

(defn- defaults [specs]
  (into {} (map (fn [[k s]] [k (:default s)])) specs))

(defn- envs [tctx] (:envelopes-atom tctx))

(defn tctx?
  "A tctx: a Context whose envelopes atom carries the tree's :specs."
  [x]
  (and (map? x) (instance? clojure.lang.IAtom (:envelopes-atom x))
       (contains? (meta (:envelopes-atom x)) :specs)))

(defn specs
  "A tctx's specs: key -> spec (with :at, the context names it reads)."
  [tctx]
  (:specs (meta (envs tctx))))

(defn- at-of
  "The context name a key is written under: (at-of specs :bass/k) is
   \"?bass.k\"; a string is taken as it is."
  [specs k]
  (cond (string? k) k
        (get specs k) (get-in specs [k :own])
        :else (throw (ex-info (str "musics.algo.tree: " k " is not a param of this tctx") {:key k}))))

(defn- validator
  "Checks every @ value in the envelopes against its spec; other names
   (the music's own keys) pass."
  [a]
  (fn [m]
    (let [by-at (into {} (for [[k s] (:specs (meta a))] [(:own s) [k s]]))]
      (doseq [[n v] m :when (and (string? n) (= \? (first n)))]
        (if-let [[k s] (by-at n)]
          (check-value! k v s)
          (throw (ex-info (str "musics.algo.tree: " n " is not a param of this tctx") {:key n}))))
      true)))

(defn- spec-map [tree]
  (into {} (map-indexed (fn [i [k s]] [k (assoc s :order i)])) (second (resolve-tree (as-node tree)))))

(defn values
  "What a tctx sets itself, key -> value (keys it leaves to the chain or
   to their defaults are absent)."
  [tctx]
  (let [m @(envs tctx)]
    (into {} (for [[k s] (specs tctx) :let [n (:own s)] :when (contains? m n)] [k (get m n)]))))

(defn params
  "A tctx's params, key -> value: what it sets, else the default."
  [tctx]
  (merge (defaults (specs tctx)) (values tctx)))

(defn state
  "{:params :specs} -- a tctx as a plain value, for a GUI or a printout."
  [tctx]
  {:params (params tctx) :specs (specs tctx)})

(defn setp!
  "Set params in a tctx, each checked against its spec: (setp! tctx :k
   5), (setp! tctx :k 5 :n 16), or a map (setp! tctx {:k 5 :n 16}). A
   key is a tree's key (:k, :euclid.n, :bass/k) or a context name
   (\"?bass.k\"). All values land in one swap!, so a bad one leaves
   every value as it was. Returns the params."
  ([tctx m]
   (let [ss (specs tctx)]
     (swap! (envs tctx) merge (into {} (for [[k v] m] [(at-of ss k) v])))
     (params tctx)))
  ([tctx k v & kvs] (setp! tctx (apply hash-map k v kvs))))

(defn unsetp!
  "Leave params to the chain again (or their defaults): (unsetp! tctx :k)."
  [tctx & ks]
  (let [ss (specs tctx)]
    (apply swap! (envs tctx) dissoc (map #(at-of ss %) ks))
    (params tctx)))

(defn tctx
  "A new tctx for `tree`: an ordinary Context (doc/md/decisions.md,
   2026-10-14) whose settings are ? names (\"?k\", \"?bass.k\"), set
   with setp!, each checked against its spec on every change. It holds
   only what is set (`overrides`, then setp!); the rest falls through to
   the contexts given to run, then to the defaults. The tree isn't
   stored, its specs are."
  ([tree] (tctx tree {}))
  ([tree overrides]
   (registries/log! :tctx)
   (let [a (atom {} :meta {:specs (spec-map tree)})
         t {:envelopes-atom a :duration nil}]
     (set-validator! a (validator a))
     (when (seq overrides) (setp! t overrides))
     t)))

(defn fit!
  "Prepare `tctx` for `tree`: add the specs of every key the tree reads
   that tctx lacks. Values stay. Returns the params."
  [tctx tree]
  (alter-meta! (envs tctx) update :specs
               (fn [ss] (reduce (fn [ss [k s]] (if (contains? ss k) ss (assoc ss k (assoc s :order (count ss)))))
                                ss (sort-by (comp :order val) (spec-map tree)))))
  (params tctx))

(defn describe
  "Print a table of a tctx's (or a tree's) params: key, value, range,
   default, algo, doc."
  [x]
  (let [[params specs] (if (tctx? x)
                         [(params x) (sort-by (comp :order val) (specs x))]
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

(def ^:private ^:dynamic *runs*
  "Within one run: [as source values] -> what that source gave, so
   specialised algos with the same :as share one run of their source."
  nil)

(defn- source-run [as]
  (when (and as *runs*)
    (fn [source vs]
      (let [k [as (:short source) vs]]
        (if (contains? @*runs* k)
          (get @*runs* k)
          (let [v (reg/call source [] vs)] (swap! *runs* assoc k v) v))))))

(defn- evaluate [n vals]
  (case (:kind n)
    :literal (:value n)
    :param   (get vals (:key n))
    :algo    (let [ds (mapv #(evaluate % vals) (:children n))
                   v  (try (reg/call (:entry n) ds (mapv #(get vals %) (:key n)) (source-run (:as n)))
                           (catch Exception e
                             (throw (ex-info (str "musics.algo.tree: " (pr-str (show n)) " threw: " (.getMessage e))
                                             {:node (show n)} e))))]
               (when *trace* (swap! *trace* conj {:node (show n) :data v}))
               v)))

(defn- from-contexts
  "A param's value from contexts, nearest first; in each, the most
   specific of its names (:at) wins, then the music key its spec names
   (:context, e.g. degrees->pitches' key reads !key:). Read once per
   run, so the latest value a context holds counts: no timing. [value]
   or nil."
  [ctxs {:keys [at context]}]
  (some (fn [ctx]
          (some (fn [n] (when-let [v (c/ctx-value-chain [ctx] n Double/POSITIVE_INFINITY)] [v]))
                (cond-> at context (conj context))))
        ctxs))

(defn- settings*
  [specs src chain]
  (let [given (when-not (tctx? src) src)
        ctxs  (cond->> (map #(if (vector? %) (first %) %) chain) (tctx? src) (cons src))]
    (into {} (for [[k s] specs]
               [k (if-let [[v] (if (contains? given k) [(get given k)] (from-contexts ctxs s))]
                    (let [v (coerce s v)] (check-value! k v s) v)
                    (:default s))]))))

(defn settings
  "The values `tree` runs with, key -> value: from `src` (a tctx or a
   plain map), then `chain`, contexts nearest first (a voice's [ctx
   offset] links too: no timing here), then the defaults -- each checked
   against its spec."
  ([tree src] (settings tree src nil))
  ([tree src chain] (settings* (second (resolve-tree (as-node tree))) src chain)))

(defn run
  "Run `tree` with the settings in `src` -- a tctx, or a plain map (keys
   it lacks fall through) -- then `chain`, contexts nearest first (the
   music's own: a ^{ } or a voice's chain), where a param is a ? name
   (?k, ?euclid.k, ?bass.k). What none of them sets takes its default.
   Every value is checked against its spec; throws, listing them, when a
   required param is unset."
  ([tree src] (run tree src nil))
  ([tree src chain]
   (let [[t specs] (resolve-tree (as-node tree))
         vals      (settings* specs src chain)
         unset     (for [[k] specs :when (nan? (get vals k))] k)]
     (when (seq unset)
       (throw (ex-info (str "musics.algo.tree: " (pr-str (show t)) " needs " (str/join " " unset) " -- not set")
                       {:missing (vec unset) :tree (show t)})))
     (binding [*runs* (atom {})] (evaluate t vals)))))

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
     (build-tree tree tctx)    ... keeping its settings"
  [& [tree tctx]]
  (registries/log! :build-tree)
  (let [draft (requiring-resolve 'musics.algo.tree.builder/draft)
        d     (if tree (draft tree) (draft))]
    @((requiring-resolve 'musics.gui.composer/open!) d tctx)))

(defn gui
  "A settings window (musics.gui.params): (gui tctx), (gui tree) -- a new
   tctx for it -- or (gui tree tctx), which also previews the result and
   has Play once / Live as. Returns the tctx. Needs a display."
  ([x] (registries/log! :gui) ((requiring-resolve 'musics.gui.params/open!) x))
  ([tree tctx] (registries/log! :gui) ((requiring-resolve 'musics.gui.params/open!) tree tctx)))
