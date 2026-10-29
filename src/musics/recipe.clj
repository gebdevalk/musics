(ns musics.recipe
  "Tree recipes: an algo tree written concatenatively, as mu is
   (doc/md/decisions.md, 2026-10-29).

     @<< ^{ seed:7 }  k:3 n:8 euclid  len:8 pitch/walk  zip >>      a generator
     @^{ k:3 n:8 len:8 } @<< euclid pitch/walk zip >> run           settings given from outside
     [c8 d e f g a b c] @<< dup duration/emi zip >>                 music from the stack, changed

   Inside @<< >> the words build the tree on a stack of their own: an algo
   takes the params (name:value) and the 'alias lying on top, then as
   many inputs as it has, and pushes itself -- bridges put in where a
   type doesn't fit (musics.algo.tree's constructors); zip and par join
   the top two, or all above a |; dup swap over rot arrange, a dup'd node
   being one node, read once per run; data and numbers are leaves. What
   the tree takes from below its own stack are its inputs: the music
   under the recipe, given when it runs. ^{ } first is the head.

   As in the music domain a param is a leaf and an algo a composite. The
   tree is built in three phases: collect every param into one flat tctx
   -- the head's under the names it writes, a node's own under its
   specific name (alias.p, else algo.p), each checked against the specs
   that read it -- associate the tree with it, run with each param
   resolved by name: the alias's, the algo's, the plain one, the
   default. The tree is a musics.algo.tree tree, the tctx its tctx, the
   result lazy."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [musics.algo.random.core :as rc]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.tree.registry :as reg]
            [musics.input.reader.walker :as walker]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.value :as v]))

(defn- fail [& parts] (apply e/mu-error "@<< >>:" parts))

(defn- algo-of
  "The registry entry a word names: its registry name (euclid,
   pitch/walk), or a mu word's shelf/name (parallel/drums); nil if none."
  [nm]
  (or (reg/algo (keyword nm))
      (some->> (re-matches #"[^/]+/(.+)" nm) second keyword reg/algo)))

(defn- label [entry] (subs (str (:short entry)) 1))

(defn- setting
  "[name value] of a name:value."
  [[_ & kids]]
  [(second (first kids)) (walker/algo-value (second kids))])

;; -- building: an RPN stack of nodes ---------------------------------------
;; The stack holds tree nodes and literal leaves, ::param and ::alias
;; entries waiting for their algo, and ::mark. :inputs counts what the
;; tree took from below its own stack.

(defn- pop1
  "[x st]: the top of the stack, or -- the stack empty -- a new input, the
   next value below the recipe (a :leaf, taken apart by the bridges)."
  [{:keys [stack inputs] :as st}]
  (if (seq stack)
    [(peek stack) (update st :stack pop)]
    [(t/as-part :leaf (keyword (str "in" inputs))) (update st :inputs inc)]))

(defn- pop-n [st n]
  (reduce (fn [[xs st] _] (let [[x st] (pop1 st)] [(cons x xs) st])) [() st] (range n)))

(defn- node? [x] (not (and (map? x) (#{::param ::alias ::mark} (::kind x)))))

(defn- algo-word
  "An algo: its params and alias from the top, then its inputs; pushed."
  [st entry written]
  (let [[params alias st]
        (loop [ps [] alias nil {:keys [stack] :as st} st]
          (let [x (peek stack)]
            (case (some-> x ::kind)
              ::param (recur (conj ps (:value x)) alias (update st :stack pop))
              ::alias (recur ps (:value x) (update st :stack pop))
              [ps alias st])))
        prefix (or alias (label entry))]
    (doseq [[p val] params :let [k (str prefix "." p)]]
      (when (contains? @written k)
        (fail (str p " is written twice for " prefix " -- give one an alias, 'name " (label entry))))
      (swap! written assoc k val))
    (let [[ins st] (pop-n st (count (:in entry)))]
      (when-let [bad (first (remove node? ins))]
        (fail (label entry) "takes inputs, got" (pr-str (::kind bad)) "-- a param or alias goes right before its algo"))
      (update st :stack conj (apply (t/constructor entry) (concat ins (when alias [:as (keyword alias)])))))))

(defn- join-word
  "zip or par: the top two, or all above a | mark."
  [{:keys [stack] :as st} word]
  (let [mark (some #(when (= ::mark (::kind (nth stack %))) %) (range (dec (count stack)) -1 -1))
        [xs st] (if mark
                  [(subvec stack (inc mark)) (assoc st :stack (subvec stack 0 mark))]
                  (pop-n st 2))]
    (when (< (count xs) 2) (fail word "joins two or more"))
    (update st :stack conj (if (= "zip" word)
                             (apply lib/zip xs)
                             (reduce lib/+part (lib/parts (first xs) (second xs)) (drop 2 xs))))))

(defn- shared
  "x as one node read once per run wherever it is used (dup)."
  [x]
  (if (t/node? x) (assoc x :share (or (:share x) (gensym "share"))) x))

(defn- stack-word [st word]
  (case word
    "dup"  (let [[a st] (pop1 st) a (shared a)] (update st :stack into [a a]))
    "swap" (let [[[a b] st] (pop-n st 2)] (update st :stack into [b a]))
    "over" (let [[[a b] st] (pop-n st 2) a (shared a)] (update st :stack into [a b a]))
    "rot"  (let [[[a b c] st] (pop-n st 3)] (update st :stack into [b c a]))))

(defn- item
  "One item of a tree recipe on the building state."
  [st [tag v :as n] env written]
  (case tag
    :Setting    (update st :stack conj {::kind ::param :value (setting n)})
    :QuotedName (update st :stack conj {::kind ::alias :value (second v)})
    :Mark       (update st :stack conj {::kind ::mark})
    :TreeLoop   (let [[x st] (pop1 st)] (update st :stack conj (lib/cycle> x)))
    (:SignedInt :SignedFloat :SignedRatio) (update st :stack conj (edn/read-string v))
    :StringLit  (update st :stack conj v)
    :TreeWord   (cond (#{"zip" "par"} v)                 (join-word st v)
                      (#{"dup" "swap" "over" "rot"} v)   (stack-word st v)
                      :else (if-let [entry (algo-of v)]
                              (algo-word st entry written)
                              (fail v "is no algo -- words lists them; music comes from the stack under the recipe")))
    ;; data: a leaf of material
    (if-let [i (:mu/index (meta n))]
      (let [x (nth (:values env) i)]
        (when-not (and (v/material? x) (= :DATA (mt/kind x)))
          (fail "holds no music -- music comes from the stack under the recipe, got" (pr-str tag)))
        (update st :stack conj (vec (mt/elements x))))
      (fail "can't hold" (pr-str tag)))))

(defn- algo-error [ex]
  (if (:mu/error (ex-data ex)) (throw ex) (fail (str/replace (ex-message ex) #"^musics\.algo\.tree: " ""))))

(defn build
  "The recipe value of a :TreeRecipe parse node, its data read in env:
   the tree its words build, the inputs it takes from below (:arity), and
   its tctx -- the head's settings and its nodes' own, every name checked."
  [node env]
  (let [[head & items] (if (= :TreeHead (first (second node))) (rest node) (cons nil (rest node)))
        head-values (into {} (map setting (rest head)))
        written     (atom {})
        st          (try (reduce #(item %1 %2 env written) {:stack [] :inputs 0} items)
                         (catch clojure.lang.ExceptionInfo ex (algo-error ex)))
        left        (:stack st)]
    (when-let [bad (first (remove node? left))]
      (fail (case (::kind bad) ::mark "a | is left -- zip or par it" "a param or alias is left -- put it right before its algo")))
    (when (not= 1 (count left))
      (fail "leaves" (count left) "-- one tree: zip or par what it makes"))
    (let [tree (t/as-node (first left))
          tctx (try (t/tctx tree (merge head-values @written)) (catch clojure.lang.ExceptionInfo ex (algo-error ex)))]
      (v/->Recipe tree tctx (:source (meta node)) (:inputs st)))))

(defn with-settings
  "recipe with settings ({name value}, an @^{ } given at use) merged into
   a fresh tctx of its own, every name checked."
  [recipe m]
  (let [tctx (try (t/tctx (:tree recipe) (merge @(:params-atom (:tctx recipe)) m))
                  (catch clojure.lang.ExceptionInfo ex (algo-error ex)))]
    (assoc recipe :tctx tctx)))

(defn- leaves-of
  "An input from below the recipe as the leaves it is: a line's notes, a
   note, or a stream as it is."
  [x]
  (cond (v/material? x) (if (= :DATA (mt/kind x)) (vec (mt/elements x)) (vec (mt/played-leaves x)))
        (mt/leaf? x)    [x]
        :else           x))

(defn result
  "What recipe gives, lazily: leaves for a line, parts for a par, a
   stream otherwise -- run on inputs (the values it takes from below,
   deepest first), its params resolved in tctx (the recipe's own, or a
   live voice's), then, for a key or meter, chain (the music it plays
   in). A seed in the settings starts the random draws there."
  ([recipe] (result recipe (:tctx recipe) nil nil))
  ([recipe tctx chain inputs]
   (when-not (= (:arity recipe) (count inputs))
     (fail "takes" (:arity recipe) "from the stack under it, got" (count inputs)))
   (when-let [seed (get @(:params-atom tctx) "seed")]
     (when (number? seed) (rc/seed! (long seed))))
   ;; in0 is the first value the tree took from below: the top of what lies under it
   (let [given (into {} (map-indexed (fn [i x] [(keyword (str "in" i)) (leaves-of x)]) (reverse inputs)))]
     (t/run (:tree recipe) (if (seq given) [given tctx] tctx) chain))))
