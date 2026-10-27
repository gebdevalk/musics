(ns musics.algo.tree.builder
  "Build a tree step by step, root to leaves -- the model behind
   (build-tree)'s window, musics.gui.composer.

   A draft is plain data:
     {:root slot :active path :history [..] :future [..]}
     slot = nil (a hole) | {:algo :euclid :children [slot ..]}
          | {:as-part :volume :children [slot]} | {:literal v} | {:param :k}
   A join (zip) holds all its children in one slot -- (zip ① ② ③) --
   and widen gives it one more; ->tree folds them as the constructor does.
   A path is a vector of child indexes from the root ([] is the root).

   Every operation returns a new draft. place fills a hole (or replaces a
   node) with an algo that keeps the draft type-correct -- checked over
   the whole draft in core.logic (musics.algo.logic.tree), so a hole under a
   :same node gets the type that node's own slot wants -- and moves
   :active to the next hole, so a tree grows root to leaves. ->tree builds the real nodes through the
   algos' constructors, so the usual checks apply."
  (:refer-clojure :exclude [remove])
  (:require [musics.algo.tree :as t]
            [musics.algo.tree.registry :as reg]
            [musics.algo.logic.tree :as lt]))

;; ---------------------------------------------------------------------------
;; The draft
;; ---------------------------------------------------------------------------

(declare from-tree next-hole)

(defn draft
  "An empty draft, or one holding an existing tree to edit."
  ([] {:root nil :active [] :history [] :future []})
  ([tree] (let [d {:root (from-tree tree) :active [] :history [] :future []}]
            (assoc d :active (or (next-hole d []) [])))))

(defn- kpath [path] (vec (mapcat (fn [i] [:children i]) path)))

(defn slot-at [d path] (get-in (:root d) (kpath path)))

(defn- set-slot [d path slot]
  (if (empty? path) (assoc d :root slot) (assoc-in d (into [:root] (kpath path)) slot)))

(defn- entry [short] (reg/algo short))

(defn positions
  "Every slot's path -- nodes, literals, params and holes -- depth first."
  [d]
  (letfn [(walk [slot path]
            (cons path (when (or (:algo slot) (:as-part slot))
                         (mapcat #(walk %1 (conj path %2)) (:children slot) (range)))))]
    (vec (walk (:root d) []))))

(defn holes
  "The paths of the unfilled slots, depth first."
  [d]
  (filterv #(nil? (slot-at d %)) (positions d)))

(defn complete? [d] (and (some? (:root d)) (empty? (holes d))))

(defn- next-hole
  "The first hole at or under `path`, else the first hole anywhere."
  [d path]
  (let [hs (holes d)]
    (or (first (filter #(= path (subvec % 0 (min (count path) (count %)))) hs))
        (first hs))))

;; ---------------------------------------------------------------------------
;; Types and compatibility
;; ---------------------------------------------------------------------------

(defn slot-type
  "What the slot at `path` must produce, inferred over the whole draft
   (musics.algo.logic.tree/hole-types): its parent's :in type at that index,
   or -- under a :same node -- whatever that node's own slot wants;
   :any when nothing constrains it."
  [d path]
  (get (lt/hole-types (:root (set-slot d path nil))) path :any))

(defn- with-algo [d path short]
  (set-slot d path {:algo short :children (vec (repeat (count (:in (entry short))) nil))}))

(defn fits?
  "Whether algo `short` may go in the slot at `path`: the draft still
   type-checks with it there, and every hole it leaves can still be
   filled (musics.algo.logic.tree/fits?)."
  [d path short]
  (lt/fits? (:root (with-algo d path short))))

;; ---------------------------------------------------------------------------
;; The pane: shelves and their algos
;; ---------------------------------------------------------------------------

(def shelf-order
  "What an algo delivers, half products first; the workshop (raw
   material: sources, bridges, raw transforms) last."
  ["pitch" "duration" "volume" "articulation" "instrument" "leaf" "parallel" "tools" "workshop"])

(defn- shelf-rank [c]
  (let [i (.indexOf ^java.util.List shelf-order c)] [(if (neg? i) 99 i) c]))

(defn algos-in
  "Shelf `c`'s algos (musics.algo.tree.registry/shelf), each with :fits?
   for the active slot, sorted by name."
  [d c]
  (->> (t/algos)
       (filter #(= c (:shelf (val %))))
       (map (fn [[short {:keys [in out doc]}]]
              {:short short :in in :out out :doc doc :fits? (fits? d (:active d) short)}))
       (sort-by (comp str :short))
       vec))

(defn categories
  "The shelves: [{:name :count :fit}] -- :fit counts the algos that fit
   the active slot."
  [d]
  (->> (t/algos)
       (group-by (comp :shelf val))
       (map (fn [[c es]] {:name c :count (count es)
                          :fit (count (filter #(fits? d (:active d) (key %)) es))}))
       (sort-by (comp shelf-rank :name))
       vec))

;; ---------------------------------------------------------------------------
;; Editing
;; ---------------------------------------------------------------------------

(defn- state-of [d] (select-keys d [:root :active]))

(defn- remember
  "Keep the current state for undo; a new edit clears what redo had."
  [d]
  (-> d (update :history (fnil conj []) (state-of d)) (assoc :future [])))

(defn place
  "Put algo `short` in the slot at `path` (a hole, or a node it replaces);
   :active moves to its first child slot, or the next hole."
  [d short path]
  (when-not (entry short) (throw (ex-info (str "No algo " short) {:algo short})))
  (when-not (fits? d path short)
    (throw (ex-info (lt/why-not short (slot-type d path)) {:algo short :path path})))
  (let [d' (with-algo (remember d) path short)]
    (assoc d' :active (or (next-hole d' path) path))))

(defn place-literal
  "A literal value (or a param keyword) as the slot at `path`."
  [d v path]
  (let [d' (set-slot (remember d) path (if (keyword? v) {:param v} {:literal v}))]
    (assoc d' :active (or (next-hole d' path) path))))

(defn joins?
  "Whether the slot at `path` is a join (zip), which widen can give one
   more child."
  [d path]
  (boolean (some-> (slot-at d path) :algo entry :join)))

(defn widen
  "One more hole in the join at `path` -- (zip ① ②) becomes (zip ① ② ③)
   -- made the active slot."
  [d path]
  (when-not (joins? d path)
    (throw (ex-info "Only a join (zip) takes one more child" {:path path})))
  (let [n (count (:children (slot-at d path)))]
    (-> (remember d)
        (set-slot path (update (slot-at d path) :children conj nil))
        (assoc :active (conj path n)))))

(defn remove
  "Empty the slot at `path` (its subtree goes with it)."
  [d path]
  (-> (remember d) (set-slot path nil) (assoc :active path)))

(defn undo
  "Back to the state before the last edit (redo brings it back)."
  [d]
  (if-let [prev (peek (:history d))]
    (-> d (update :history pop) (update :future (fnil conj []) (state-of d)) (merge prev))
    d))

(defn redo
  "Forward again to the state the last undo left."
  [d]
  (if-let [nxt (peek (:future d))]
    (-> d (update :future pop) (update :history (fnil conj []) (state-of d)) (merge nxt))
    d))

(defn can-undo? [d] (boolean (seq (:history d))))
(defn can-redo? [d] (boolean (seq (:future d))))

(defn select [d path] (assoc d :active path))

;; ---------------------------------------------------------------------------
;; Between drafts and trees
;; ---------------------------------------------------------------------------

(defn ->tree
  "The draft as a real tree (it must be complete)."
  [d]
  (when-not (complete? d)
    (throw (ex-info "The tree still has open slots" {:holes (holes d)})))
  (letfn [(build [{:keys [algo children as literal param] :as slot}]
            (cond algo            (apply (t/constructor (entry algo))
                                         (concat (map build children) (when as [:as as])))
                  (:as-part slot) (t/as-part (:as-part slot) (build (first children)))
                  param           param
                  :else           literal))]
    (t/as-node (build (:root d)))))

(defn- from-tree [tree]
  (letfn [(conv [n]
            (case (:kind n)
              :algo    (let [[c & cs] (:children n)
                             ;; a join of a join is one slot, as it prints
                             fold?    (and (get-in n [:entry :join]) (identical? (:entry c) (:entry n)) (nil? (:as c)))]
                         (cond-> {:algo (get-in n [:entry :short])
                                  :children (if fold? (into (:children (conv c)) (map conv cs)) (mapv conv (:children n)))}
                           (:as n) (assoc :as (:as n))))
              :as-part {:as-part (:name n) :children [(conv (first (:children n)))]}
              :param   {:param (:key n)}
              :literal {:literal (:value n)}))]
    (conv (t/as-node tree))))

;; ---------------------------------------------------------------------------
;; Text: the canvas as brackets
;; ---------------------------------------------------------------------------

(def ^:private circled "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳")
(def ^:private super-digits {\0 \⁰ \1 \¹ \2 \² \3 \³ \4 \⁴ \5 \⁵ \6 \⁶ \7 \⁷ \8 \⁸ \9 \⁹})

(defn- hole-mark [n] (if (<= 1 n 20) (str (nth circled (dec n))) (str "[" n "]")))
(defn- sup [n] (apply str (map super-digits (str n))))

(defn render
  "The draft as bracketed text. Holes are numbered ①②…; with
   `numbered?` every other slot carries its position number too
   (superscript), for the REPL's select command. ▸ marks the active slot."
  ([d] (render d false))
  ([d numbered?]
   (let [pos   (into {} (map-indexed (fn [i p] [p (inc i)]) (positions d)))
         hnum  (into {} (map-indexed (fn [i p] [p (inc i)]) (holes d)))
         mark  (fn [path s] (str (when (= path (:active d)) "▸") s))]
     (letfn [(r [slot path]
               (let [n (when numbered? (sup (pos path)))]
                 (cond
                   (nil? slot)      (mark path (if numbered? (hole-mark (pos path)) (hole-mark (hnum path))))
                   (or (:algo slot) (:as-part slot))
                   (mark path (str "(" (if (:algo slot) (symbol (:algo slot)) (str "as-part " (:as-part slot))) n
                                   (apply str (map (fn [c i] (str " " (r c (conj path i))))
                                                   (:children slot) (range)))
                                   ")"))
                   (:param slot)    (mark path (str (:param slot) n))
                   :else            (mark path (str (pr-str (:literal slot)) n)))))]
       (r (:root d) [])))))

;; ---------------------------------------------------------------------------
;; Finalize
;; ---------------------------------------------------------------------------

(defn finalize
  "[tree tctx] for a complete draft. `settings` (a tctx built along the
   way, or nil) keeps the values of the keys the final tree still reads."
  [d settings]
  (let [tree (->tree d)
        ks   (map :key (t/param-keys tree))
        vals (when settings (select-keys (t/values settings) ks))]
    [tree (t/tctx tree vals)]))

(defn settings-for
  "A tctx for a complete draft's tree, keeping `settings`' values; nil
   while the draft is incomplete."
  [d settings]
  (when (complete? d)
    (let [tree (->tree d)]
      (if settings
        (do (t/fit! settings tree) settings)
        (t/tctx tree)))))
