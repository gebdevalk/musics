(ns musics.algo.tree.builder
  "Build a tree step by step, root to leaves -- the model behind
   (build-tree), shared by its window (musics.gui.composer) and its REPL
   twin (repl-build), so both follow exactly the same rules.

   A draft is plain data:
     {:root slot :active path :history [..] :future [..]}
     slot = nil (a hole) | {:algo :euclid :children [slot ..]}
          | {:literal v} | {:param :k}
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
            [musics.algo.logic.tree :as lt]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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
            (cons path (when (:algo slot)
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
;; The pane: categories and their algos
;; ---------------------------------------------------------------------------

(def category-order
  ["output" "rhythmic" "melodic" "metric" "random" "indisp" "shape" "bridges" "sources" "common"])

(defn- category-rank [c]
  (let [i (.indexOf ^java.util.List category-order c)] [(if (neg? i) 99 i) c]))

(defn algos-in
  "Category `c`'s algos, each with :fits? for the active slot, sorted by name."
  [d c]
  (->> (t/algos)
       (filter #(= c (:category (val %))))
       (map (fn [[short {:keys [in out doc]}]]
              {:short short :in in :out out :doc doc :fits? (fits? d (:active d) short)}))
       (sort-by (comp name :short))
       vec))

(defn categories
  "[{:name :count :fit}] -- :fit counts the algos that fit the active slot."
  [d]
  (->> (t/algos)
       (group-by (comp :category val))
       (map (fn [[c es]] {:name c :count (count es)
                          :fit (count (filter #(fits? d (:active d) (key %)) es))}))
       (sort-by (comp category-rank :name))
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
  (letfn [(build [{:keys [algo children as literal param]}]
            (cond algo    (apply (t/constructor (entry algo))
                                 (concat (map build children) (when as [:as as])))
                  param   param
                  :else   literal))]
    (t/as-node (build (:root d)))))

(defn- from-tree [tree]
  (letfn [(conv [n]
            (case (:kind n)
              :algo    (cond-> {:algo (get-in n [:entry :short]) :children (mapv conv (:children n))}
                         (:as n) (assoc :as (:as n)))
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
                   (:algo slot)     (mark path (str "(" (name (:algo slot)) n
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
        vals (when settings (select-keys (:params @settings) ks))]
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

;; ---------------------------------------------------------------------------
;; The REPL twin
;; ---------------------------------------------------------------------------

(def ^:private help
  "  <n>          open category n, or place algo n in the active slot
  b            back to the categories
  s <n>        select slot n (the superscript/circled numbers)
  l <edn>      a literal in the active slot     :key   a param keyword
  r            remove the active slot
  u            undo                             y      redo
  k <key> <v>  set a setting (once the tree is complete)
  f            finalize -> [tree tctx]          q      cancel -> nil")

(defn- screen [d category settings]
  (let [a (:active d)]
    (println)
    (println "canvas:" (if (:root d) (render d true) (str "▸" (hole-mark 1) "  (empty: choose a root)")))
    (println (str "active: slot " (inc (.indexOf ^java.util.List (positions d) a))
                  (when-not (slot-at d a) (str ", needs " (slot-type d a)))
                  (when (complete? d) "   -- complete: f to finalize")))
    (if category
      (do (println (str "pane:   " category "   (b: back)"))
          (doseq [[i {:keys [short in out doc fits?]}] (map-indexed vector (algos-in d category))]
            (println (format "  %s%3d %-16s %-22s %s" (if fits? " " "-") (inc i) (name short)
                             (str (str/join " " (map name in)) " -> " (name out)) (or doc "")))))
      (do (println "pane:   categories")
          (doseq [[i {:keys [name count fit]}] (map-indexed vector (categories d))]
            (println (format "  %s%3d %-10s %d fit of %d" (if (pos? fit) " " "-") (inc i) name fit count)))))
    (when (and settings (complete? d))
      (println "settings:" (pr-str (:params @settings))))))

(defn- parse-int [s] (try (Long/parseLong (str/trim s)) (catch Exception _ nil)))

(defn repl-build
  "The REPL twin of the composer window: the same steps as text. Returns
   [tree tctx] on f, nil on q."
  ([] (repl-build (draft) nil))
  ([d settings]
   (println "build-tree -- ? for help")
   (loop [d d, category nil, settings (settings-for d settings)]
     (screen d category settings)
     (print "> ") (flush)
     (let [line (some-> (read-line) str/trim)
           [cmd arg] (when line (str/split line #"\s+" 2))
           n (some-> line parse-int)
           step (fn [f] (try (let [d' (f)] [d' (or (settings-for d' settings) settings)])
                             (catch Exception e (println "!" (ex-message e)) [d settings])))]
       (cond
         (or (nil? line) (= "q" line)) nil
         (= "?" line) (do (println help) (recur d category settings))
         (= "f" line) (if (complete? d)
                        (finalize d settings)
                        (do (println "! the tree still has open slots") (recur d category settings)))
         (= "b" line) (recur d nil settings)
         (= "u" line) (let [[d' s] (step #(undo d))] (recur d' category s))
         (= "y" line) (let [[d' s] (step #(redo d))] (recur d' category s))
         (= "r" line) (let [[d' s] (step #(remove d (:active d)))] (recur d' category s))
         (= "s" cmd)  (let [i (some-> arg parse-int) p (when i (get (positions d) (dec i)))]
                        (if p (recur (select d p) category settings)
                            (do (println "! no slot" arg) (recur d category settings))))
         (= "l" cmd)  (let [[d' s] (step #(place-literal d (edn/read-string arg) (:active d)))]
                        (recur d' category s))
         (str/starts-with? line ":")
         (let [[d' s] (step #(place-literal d (keyword (subs line 1)) (:active d)))] (recur d' category s))
         (= "k" cmd)  (if (and settings (complete? d))
                        (let [[k v] (str/split (or arg "") #"\s+" 2)]
                          (try (t/setp! settings (edn/read-string k) (edn/read-string v))
                               (catch Exception e (println "!" (ex-message e))))
                          (recur d category settings))
                        (do (println "! settings exist once the tree is complete") (recur d category settings)))
         (and n (nil? category))
         (let [c (get (categories d) (dec n))]
           (recur d (or (:name c) (do (println "! no category" n) nil)) settings))
         (and n category)
         (let [a (get (algos-in d category) (dec n))]
           (cond (nil? a) (do (println "! no algo" n) (recur d category settings))
                 (not (:fits? a)) (do (println "!" (name (:short a)) "doesn't fit this slot") (recur d category settings))
                 :else (let [[d' s] (step #(place d (:short a) (:active d)))] (recur d' nil s))))
         :else (do (println "! ? for help") (recur d category settings)))))))
