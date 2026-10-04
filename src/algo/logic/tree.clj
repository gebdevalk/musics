(ns algo.logic.tree
  "The tree algo registry as core.logic facts, and the questions a
   composer asks of it: which algos are there (find-algos), what type a
   hole in a half-built tree must be (hole-types -- through :same, which
   a plain per-slot check can't follow), which trees connect one type to
   another (how), what can take this output (feeds), why an algo
   doesn't fit (why-not), small worked trees (examples) and random
   trees that always type-check (surprise).

   Every algo is a fact [short ins out]: its :in types and its :out.
   :any is a fresh logic variable (it accepts, or gives, anything);
   :same is the variable of the first input, so the output is whatever
   that child gives. A tree is a term (short child ...), a leaf being a
   source algo (no inputs), or :input -- something of a given type you
   already have. Literals and keyword params are left out: they are
   :any, so with them every slot could always be filled.

   Search order is the registry's, sorted by name, so answers are the
   same every time; surprise shuffles with algo.random's seed."
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :refer [== all and* conde conso emptyo fresh
                                        lvar membero or* run succeed fail]]
            [clojure.string :as str]
            [algo.tree :as t]
            [algo.tree.registry :as reg]
            [algo.random :as rand]))

;; ---------------------------------------------------------------------------
;; Facts
;; ---------------------------------------------------------------------------

(defn- type-term [ty] (if (= :any ty) (lvar) ty))

(defn- fresh-types
  "[ins out] for one use of entry: fresh variables for :any inputs,
   :same tied to the first input -- so two uses of one algo are typed
   apart. An :any output is a variable too (algo.tree/fits? lets it
   fill any slot), except when strict: then it stays :any and fills
   only an open slot -- searches use that, or every answer would be
   'an algo that gives anything'."
  ([e] (fresh-types e false))
  ([{:keys [in out]} strict?]
   (let [ins (mapv type-term in)]
     [ins (cond (= :same out) (or (first ins) (lvar))
                (and strict? (= :any out)) :any
                :else (type-term out))])))

(def ^:private cache
  "[registry-state {:entries .. ty completable?}], rebuilt when the
   registry changes."
  (atom nil))

(defn- cached
  "The cache for the current registry state."
  []
  (let [st (reg/state)
        [st' m] @cache]
    (if (identical? st st')
      m
      (let [m {:entries (vec (sort-by (comp name key) (t/algos)))}]
        (reset! cache [st m])
        m))))

(defn- entries
  "The registry as [short entry] pairs, sorted by name."
  []
  (:entries (cached)))

(defn- algoo
  "short is one of es (registry pairs), with input types ins and output
   type out."
  [es short ins out]
  (or* (for [[s e] es]
         (let [[ins* out*] (fresh-types e true)]
           (all (== short s) (== ins ins*) (== out out*))))))

;; ---------------------------------------------------------------------------
;; Trees
;; ---------------------------------------------------------------------------

(declare childreno)

(defn- treeo
  "tree is a complete tree at most depth levels deep giving type ty,
   using es; a leaf may also be :input, of type from (when given)."
  [es depth tree ty from]
  (conde
    [(if from (all (== tree :input) (== ty from)) fail)]
    [(if (pos? depth)
       (fresh [short ins kids]
         (algoo es short ins ty)
         (conso short kids tree)
         (childreno es (dec depth) kids ins from))
       fail)]))

(defn- childreno [es depth kids ins from]
  (conde
    [(emptyo ins) (emptyo kids)]
    [(fresh [ty tys k ks]
       (conso ty tys ins)
       (conso k ks kids)
       (treeo es depth k ty from)
       (childreno es depth ks tys from))]))

(declare some-childo)

(defn- bridgeo
  "Like treeo, but the tree must use :input (of type from) somewhere --
   generated that way, not filtered after."
  [es depth tree ty from]
  (conde
    [(== tree :input) (== ty from)]
    [(if (pos? depth)
       (fresh [short ins kids]
         (algoo es short ins ty)
         (conso short kids tree)
         (some-childo es (dec depth) kids ins from))
       fail)]))

(defn- some-childo
  "One child bridges from `from`; the rest are plain trees."
  [es depth kids ins from]
  (fresh [ty tys k ks]
    (conso ty tys ins)
    (conso k ks kids)
    (conde
      [(bridgeo es depth k ty from) (childreno es depth ks tys nil)]
      [(treeo es depth k ty nil) (some-childo es depth ks tys from)])))

(defn- reacho
  "Some chain of algos, at most depth long, turns a `from` into a `to`
   -- types only, no trees: a dozen types, so this fails fast where a
   tree search would try every tree first."
  [es depth from to]
  (conde
    [(== from to)]
    [(if (pos? depth)
       (fresh [s ins mid]
         (algoo es s ins to)
         (membero mid ins)
         (reacho es (dec depth) from mid))
       fail)]))

(defn- reachable?
  "A :same algo never changes a type, so the type chain skips them."
  [es depth from to]
  (let [es (remove #(= :same (:out (val %))) es)]
    (boolean (seq (run 1 [_] (reacho es depth from to))))))

(defn- smallest
  "Up to n distinct trees for (goal-fn depth tree), shallowest first --
   depth 1, then 2, then 3, so the simplest answers come first."
  [n max-depth goal-fn]
  (->> (range 1 (inc max-depth))
       (mapcat (fn [d] (run n [tr] (goal-fn d tr))))
       distinct
       (take n)))

(defn show
  "A tree term as text: (notes (gate (euclid) (cycled (scale))))."
  [term]
  (if (seq? term)
    (str "(" (str/join " " (cons (name (first term)) (map show (rest term)))) ")")
    (if (keyword? term) (name term) (pr-str term))))

(defn ->tree
  "A tree term as a real tree; :input becomes the param :input, so
   (t/run tree {:input x}) runs it on your x."
  [term]
  (if (seq? term)
    (apply (t/constructor (t/algo (first term))) (map ->tree (rest term)))
    term))

;; ---------------------------------------------------------------------------
;; Completable types
;; ---------------------------------------------------------------------------

(def completion-depth
  "How many levels a hole may still take to fill, for dimming."
  3)

(defn completable?
  "Whether real algos can fill a slot of type ty within
   completion-depth levels. Cached per registry state."
  [ty]
  (or (not (keyword? ty))
      (let [m (cached)]
        (if (contains? m ty)
          (m ty)
          (let [ok (boolean (seq (run 1 [tr] (treeo (:entries m) completion-depth tr ty nil))))]
            (swap! cache (fn [[st m']] [st (assoc m' ty ok)]))
            ok)))))

;; ---------------------------------------------------------------------------
;; Half-built trees (algo.tree.builder's slots)
;; ---------------------------------------------------------------------------

(defn- slot-goal
  "slot (nil = a hole, {:algo .. :children ..}, {:literal ..},
   {:param ..}) gives type ty; each hole's type variable goes in holes
   under its path."
  [slot ty path holes]
  (cond
    (nil? slot) (do (swap! holes assoc path ty) succeed)
    (:algo slot)
    (let [[ins out] (fresh-types (t/algo (:algo slot)))]
      ;; mapv, not map: the holes are recorded while the goal is built
      (and* (cons (== ty out)
                  (mapv (fn [i child] (slot-goal child (nth ins i) (conj path i) holes))
                        (range) (:children slot)))))
    :else succeed))

(defn- solve
  "{path type} for root's holes, or nil when root can't type-check.
   A type left open is :any."
  [root]
  (let [holes (atom {})
        goal  (slot-goal root (lvar) [] holes)
        paths (vec (keys @holes))
        vars  (mapv @holes paths)
        sol   (first (run 1 [q] goal (== q vars)))]
    (when sol
      (zipmap paths (map #(if (keyword? %) % :any) sol)))))

(defn hole-types
  "{path type} for every hole in root (a draft's :root): what each must
   give, inferred through the whole tree -- a hole under a :same node
   takes the type the node's own slot wants."
  [root]
  (or (solve root) {}))

(defn fits?
  "Whether root type-checks, and every hole in it can still be filled
   (completable?)."
  [root]
  (boolean (when-let [hs (solve root)] (every? completable? (vals hs)))))

;; ---------------------------------------------------------------------------
;; Questions
;; ---------------------------------------------------------------------------

(defn find-algos
  "Algos matching every given condition, as maps
   {:short :category :in :out :params :doc}:
     :category \"rhythmic\"   :out :pulse   :in :pitch (an input that
     accepts it)   :param :k (a param of that name)   :short :euclid.
   (find-algos {:in :pulse :out :pitch}) -- what turns pulses into
   pitches in one step."
  [{:keys [category out in param short]}]
  (let [es (entries)
        hits (run (count es) [s]
               (fresh [c ins o ps]
                 (or* (for [[s* e] es]
                        (let [[ins* o*] (fresh-types e true)]
                          (== [s c ins o ps] [s* (:category e) ins* o* (mapv :name (:params e))]))))
                 (if short (== s short) succeed)
                 (if category (== c category) succeed)
                 (if out (== o out) succeed)
                 (if in (membero in ins) succeed)
                 (if param (membero param ps) succeed)))]
    (for [h (distinct hits) :let [e (t/algo h)]]
      {:short h :category (:category e) :in (:in e) :out (:out e)
       :params (mapv :name (:params e)) :doc (:doc e)})))

(defn how
  "Up to n of the smallest trees turning a `from` into a `to` -- terms
   with :input for what you have (->tree / show)."
  ([from to] (how from to 5))
  ([from to n]
   (let [es (entries)]
     ;; a depth no type chain reaches is skipped, not searched
     (smallest n 3 #(if (reachable? es %1 from to) (bridgeo es %1 %2 to from) fail)))))

(defn steps
  "The fewest algos that turn a `from` into a `to` (0 for the same type),
   or nil when no chain of at most max-steps (default 4) does. Types
   only: what doc/bridge-table.md tabulates."
  ([from to] (steps from to 4))
  ([from to max-steps]
   (let [es (entries)]
     (first (filter #(reachable? es % from to) (range 0 (inc max-steps)))))))

(defn feeds
  "Algos with an input that takes `x`'s output: x a type keyword, or a
   tree/node/constructor. An input of :any takes everything; those are
   listed after the ones that name the type."
  [x]
  (let [ty  (if (keyword? x) x (t/out-type (t/as-node x)))
        own (fn [{:keys [in]}] (boolean (some #{ty} in)))
        es  (entries)
        hit (set (run (count es) [s]
                   (fresh [ins out] (algoo es s ins out) (membero ty ins))))]
    (->> es (map key) (filter hit) (sort-by (comp not own t/algo)) vec)))

(defn why-not
  "Why algo `short` doesn't fit a slot wanting `want`, with the
   shortest bridge between the two types when there is one."
  [short want]
  (let [e (t/algo short)
        [_ got] (fresh-types e)
        got (if (keyword? got) got :any)]
    (if (t/fits? want got)
      (if (completable? want)
        (str (name short) " fits a " (name want) " slot")
        (str "nothing completes a " (name want) " slot within " completion-depth " levels"))
      (let [bridge (first (how got want 1))]
        (str (name short) " gives " got ", this slot wants " want
             (if bridge
               (str "; " (show bridge) " turns one into the other (input = " (name short) ")")
               (str "; nothing turns a " got " into a " want " within 3 levels")))))))

(defn examples
  "Up to n of the smallest complete trees with `short` at the root."
  ([short] (examples short 3))
  ([short n]
   (let [es (entries)
         e  (t/algo short)]
     (smallest n 3
               (fn [d tr]
                 (let [[ins _] (fresh-types e true)]
                   (fresh [kids]
                     (conso short kids tr)
                     (childreno es (dec (max d 1)) kids ins nil))))))))

(defn surprise
  "A random complete tree giving `ty`, at most depth levels deep --
   candidates shuffled with algo.random, so it follows its seed."
  ([ty] (surprise ty 3))
  ([ty depth]
   (first (run 1 [tr] (treeo (rand/shuffle (vec (entries))) depth tr ty nil)))))
