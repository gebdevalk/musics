(ns musics.recipe
  "Recipes: an algo tree written with the music's brackets, @ before them
   (doc/md/decisions.md, 2026-10-29).

     @^{ seed:7 }                                   the head: settings for the whole tree
     @[ @( pulses->durations @( euclid k:3 n:8 ) )   zip: the inputs into a line
        @( bass pitch/walk len:8 ) ]                 an algo: alias? algo param:value … inputs …
     @{ line line … }                               par: lines side by side

   As in the music domain, a param is a leaf and an algo a composite, an
   input that has inputs. A recipe is built in three phases, each a
   depth-first visit:
     1. collect -- every param into one flat tctx: the head's under the
        names it writes, a node's own under its specific name (bass.k,
        else euclid.k), each name with its spec, checked;
     2. associate -- the tree and that tctx together, the recipe value;
     3. run -- each algo after its inputs, its params resolved in the
        tctx by name: the alias's, the algo's, the plain one, the default.
   The tree is a musics.algo.tree tree, the tctx its tctx, so running,
   playing live, set, settings and the GUI's settings are theirs. The
   result is lazy: an endless recipe gives what is taken of it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.tree.registry :as reg]
            [musics.algo.random.core :as rc]
            [musics.input.reader.walker :as walker]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.value :as v]))

(defn- algo-of
  "The registry entry an @( ) names: its registry name (euclid,
   pitch/walk), or a mu word's shelf/name (parallel/drums)."
  [nm]
  (or (reg/algo (keyword nm))
      (some->> (re-matches #"[^/]+/(.+)" nm) second keyword reg/algo)
      (e/mu-error (str "@( " nm " ):") "no algo of that name -- words lists them")))

(defn- label [entry] (subs (str (:short entry)) 1))

(defn- literal
  "A plain value inside a recipe as the input its slot wants (type, from
   the algo's :in; nil in a zip or a par): a line taken apart into its
   durations, pitches or leaves, as mu's algo words take it; data as its
   values; a number or a string as it is."
  [x type]
  (cond
    (and (v/material? x) (= :DATA (mt/kind x))) (vec (mt/elements x))
    (or (v/material? x) (mt/leaf? x))
    (let [ls (vec (mt/played-leaves x))]
      (case type
        :duration (t/as-part :duration (mapv #(if (= :REST (:type %)) % (:duration %)) ls))
        :pitch    (t/as-part :pitch (mapv #(some->> (:pitches %) seq (apply min)) ls))
        (t/as-part :leaf ls)))
    :else x))

(defn- setting
  "[name value] of a name:value param."
  [[_ & kids]]
  [(second (first kids)) (walker/algo-value (second kids))])

(declare tree-of)

(defn- input
  "One input of a recipe node: a nested bracket, or a plain value -- a
   literal mu has read (env's values), a number, a string."
  [n env written type]
  (case (first n)
    (:RecipeNode :RecipeZip :RecipePar) (tree-of n env written)
    (:SignedInt :SignedFloat :SignedRatio) (edn/read-string (second n))
    :StringLit (second n)
    :Setting (e/mu-error (str (second (second n)) ":") "is a setting, no input -- write it in the head @^{ } or inside an @( )")
    (if-let [i (:mu/index (meta n))]
      (literal (nth (:values env) i) type)
      (e/mu-error "a recipe can't hold" (pr-str (first n))))))

(defn- tree-of
  "The musics.algo.tree tree of a recipe bracket. Phase 1 for its nodes:
   a node's own params go into written ({name value}) under its specific
   name, alias.p, else algo.p -- two un-aliased nodes of one algo writing
   the same param are refused, since the tctx is flat."
  [[tag & kids] env written]
  (case tag
    :RecipeNode
    (let [names        (take-while #(= :RecipeName (first %)) kids)
          [alias nm]   (if (= 2 (count names)) (map second names) [nil (second (first names))])
          entry        (algo-of nm)
          items        (drop (count names) kids)
          params       (map setting (filter #(= :Setting (first %)) items))
          inputs       (remove #(= :Setting (first %)) items)
          prefix       (or alias (label entry))]
      (doseq [[p val] params :let [k (str prefix "." p)]]
        (when (contains? @written k)
          (e/mu-error (str "@( " nm " ):") (str p " is written twice for " prefix " -- give one of them an alias, @( name " nm " … )")))
        (swap! written assoc k val))
      (apply (t/constructor entry)
             (concat (map-indexed (fn [i x] (input x env written (get (:in entry) i))) inputs)
                     (when alias [:as (keyword alias)]))))
    :RecipeZip (apply lib/zip (map #(input % env written nil) kids))
    :RecipePar (let [[a b & more] (map #(input % env written nil) kids)]
                 (when-not b (e/mu-error "@{ }: par takes two lines or more"))
                 (reduce lib/+part (lib/parts a b) more))
    (e/mu-error "not a recipe:" (pr-str tag))))

(defn build
  "The recipe value of a :Recipe parse node, its literals read in env:
   the tree (phase 1 collecting its nodes' params), and its tctx holding
   them and the head's (phase 2) -- every name checked against the specs
   of the params that read it."
  [node env]
  (let [[head tree-node] (if (= :Settings (first (second node))) (rest node) [nil (second node)])
        head-values (when head (into {} (:params (v/node (nth (:values env) (:mu/index (meta head)))))))
        written     (atom {})
        tree        (try (tree-of tree-node env written)
                         (catch clojure.lang.ExceptionInfo ex
                           (if (:mu/error (ex-data ex)) (throw ex)
                               (e/mu-error "recipe:" (str/replace (ex-message ex) #"^musics\.algo\.tree: " "")))))
        tctx        (try (t/tctx tree (merge head-values @written))
                         (catch clojure.lang.ExceptionInfo ex
                           (e/mu-error "recipe:" (str/replace (ex-message ex) #"^musics\.algo\.tree: " ""))))]
    (v/->Recipe tree tctx (:source (meta node)))))

(defn result
  "What recipe gives (phase 3), lazily: leaves for a line, parts for a
   par, a stream otherwise -- its params resolved in tctx (the recipe's
   own, or a live voice's), then, for a key or meter, chain (the music it
   plays in). A seed in the settings starts the random draws there, so
   one seed gives one result; the result being lazy, the draws go on
   from that seed as it is taken."
  ([recipe] (result recipe (:tctx recipe) nil))
  ([recipe tctx chain]
   (when-let [seed (get @(:params-atom tctx) "seed")]
     (when (number? seed) (rc/seed! (long seed))))
   (t/run (:tree recipe) tctx chain)))
