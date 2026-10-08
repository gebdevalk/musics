(ns musics.mu.kernel
  "mu's kernel words (namespace kernel): the stack, quotations,
   printing, numbers, variables, the repo, playback, namespaces, help,
   files and the defaults (doc/mu-repl.md, section 8)."
  (:require [clojure.string :as str]
            [musics.common.music-elements :as el]
            [musics.core :as m]
            [musics.domain.resolve :as r]
            [musics.engine :as engine]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.registries :as reg]
            [musics.repo :as repo]))

;; -- stack ------------------------------------------------------------

(word! "kernel" "dup" "( a -- a a )"
       (fn [s] (need s 1 "dup") (conj s (peek s))))

(word! "kernel" "pop" "( a -- )"
       (fn [s] (need s 1 "pop") (pop s)))

(word! "kernel" "swap" "( a b -- b a )"
       (fn [s]
         (need s 2 "swap")
         (let [n (count s)] (assoc s (- n 1) (s (- n 2)) (- n 2) (s (- n 1))))))

(word! "kernel" "over" "( a b -- a b a )"
       (fn [s] (need s 2 "over") (conj s (s (- (count s) 2)))))

(word! "kernel" "rot" "( a b c -- b c a )"
       (fn [s]
         (need s 3 "rot")
         (let [n (count s)] (conj (subvec s 0 (- n 3)) (s (- n 2)) (s (- n 1)) (s (- n 3))))))

(word! "kernel" "nip" "( a b -- b )"
       (fn [s] (need s 2 "nip") (conj (pop (pop s)) (peek s))))

(word! "kernel" "tuck" "( a b -- b a b )"
       (fn [s]
         (need s 2 "tuck")
         (let [b (peek s) a (peek (pop s))] (conj (pop (pop s)) b a b))))

(word! "kernel" "pick" "( xn … x0 n -- xn … x0 xn )"
       (fn [s]
         (need s 1 "pick")
         (let [n (peek s) s (pop s)]
           (when-not (and (integer? n) (< -1 n (count s)))
             (e/mu-error "pick: needs an index 0 to" (dec (count s)) "under it, got" n))
           (conj s (s (- (count s) 1 n))))))

(word! "kernel" "depth" "( -- n )"
       (fn [s] (conj s (count s))))

;; -- quotations ---------------------------------------------------------

(word! "kernel" "call" "( q -- … )"
       (fn [s] (need s 1 "call") (e/call (pop s) (peek s))))

(defn- quote? [x] (v/quotation? x))

(word! "kernel" "if" "( flag q-then q-else -- … )"
       (fn [s]
         (need s 3 "if")
         (let [n (count s) [f t el] (subvec s (- n 3))]
           (when-not (and (quote? t) (quote? el)) (e/mu-error "if: needs two quotations '( … ) on top"))
           (e/call (subvec s 0 (- n 3)) (if f t el)))))

(word! "kernel" "when" "( flag q -- … )"
       (fn [s]
         (need s 2 "when")
         (let [q (peek s) f (peek (pop s)) s (pop (pop s))]
           (if f (e/call s q) s))))

(defn elements
  "The elements of something with elements: a line's or parallel's
   children (leaves as they are, containers as values of their own), a
   stream (a Clojure seq), a data container's values."
  [x]
  (cond
    (v/material? x) (let [{:keys [repo]} x]
                      (mapv (fn [k] (if (keyword? k) (assoc (v/->Material k repo) :base (:base x)) k))
                            (remove #(and (map? %) (#{:assignment :instruction} (:type %)))
                                    (:children (v/node x)))))
    (sequential? x) x
    :else (e/mu-error "has no elements:" (p/show x))))

(word! "kernel" "each" "( things q -- … )"
       (fn [s]
         (need s 2 "each")
         (let [q (peek s) xs (elements (peek (pop s)))]
           (reduce (fn [st x] (e/call (conj st x) q)) (pop (pop s)) xs))))

(word! "kernel" "reduce" "( things init q -- r )"
       (fn [s]
         (need s 3 "reduce")
         (let [n (count s) [xs init q] (subvec s (- n 3))]
           (reduce (fn [st x] (e/call (conj st x) q)) (conj (subvec s 0 (- n 3)) init) (elements xs)))))

;; -- printing -------------------------------------------------------------

(word! "kernel" "." "( a -- )"
       (fn [s] (need s 1 ".") (println (p/show (peek s))) (pop s)))

(word! "kernel" ".s" "( -- )"
       (fn [s] (println (str "<" (count s) ">") (str/join " " (map p/show s))) s))

;; -- numbers --------------------------------------------------------------

(defn- num2 [word f]
  (fn [s]
    (need s 2 word)
    (let [b (peek s) a (peek (pop s))]
      (when-not (and (number? a) (number? b))
        (e/mu-error (str word ":") "needs two numbers, got" (p/show a) "and" (p/show b)))
      (conj (pop (pop s)) (f a b)))))

(defn- num1 [word f]
  (fn [s]
    (need s 1 word)
    (let [a (peek s)]
      (when-not (number? a) (e/mu-error (str word ":") "needs a number, got" (p/show a)))
      (conj (pop s) (f a)))))

(word! "kernel" "+" "( a b -- a+b )" (num2 "+" +))
(word! "kernel" "-" "( a b -- a-b )" (num2 "-" -))
(word! "kernel" "*" "( a b -- a*b )" (num2 "*" *))
(word! "kernel" "/" "( a b -- a/b )"
       (num2 "/" (fn [a b] (when (zero? b) (e/mu-error "/: division by zero")) (/ a b))))
(word! "kernel" "mod" "( a b -- a mod b )" (num2 "mod" mod))
(word! "kernel" "min" "( a b -- min )" (num2 "min" min))
(word! "kernel" "max" "( a b -- max )" (num2 "max" max))
(word! "kernel" "neg" "( a -- -a )" (num1 "neg" -))
(word! "kernel" "abs" "( a -- |a| )" (num1 "abs" abs))
(word! "kernel" "round" "( a -- n )" (num1 "round" #(long (Math/round (double %)))))
(word! "kernel" "int" "( a -- n )" (num1 "int" long))
(word! "kernel" "float" "( a -- f )" (num1 "float" double))
(word! "kernel" "rational" "( a -- r )" (num1 "rational" rationalize))

(word! "kernel" "=" "( a b -- flag )" (fn [s] (need s 2 "=") (conj (pop (pop s)) (= (peek (pop s)) (peek s)))))
(word! "kernel" "<" "( a b -- flag )" (num2 "<" <))
(word! "kernel" ">" "( a b -- flag )" (num2 ">" >))
(word! "kernel" "<=" "( a b -- flag )" (num2 "<=" <=))
(word! "kernel" ">=" "( a b -- flag )" (num2 ">=" >=))
(word! "kernel" "not" "( flag -- flag )" (fn [s] (need s 1 "not") (conj (pop s) (not (peek s)))))

;; -- variables ------------------------------------------------------------

(word! "kernel" "!" "( value var -- )"
       (fn [s]
         (need s 2 "!")
         (let [var (peek s)]
           (when-not (instance? musics.mu.value.Variable var) (e/mu-error "!: needs a variable on top, got" (p/show var)))
           (reset! (:cell var) (peek (pop s)))
           (pop (pop s)))))

(word! "kernel" "@" "( var -- value )"
       (fn [s]
         (need s 1 "@")
         (let [var (peek s)]
           (when-not (instance? musics.mu.value.Variable var) (e/mu-error "@: needs a variable, got" (p/show var)))
           (conj (pop s) @(:cell var)))))

;; -- the repo -------------------------------------------------------------

(defn- leaf? [x] (and (map? x) (#{:LEAF :REST :DRUM} (:type x))))

(defn ->material
  "x as a Material, without storing it: a leaf is read as a one-leaf
   line."
  [x]
  (cond
    (v/material? x) x
    (leaf? x) (let [text (str "[" (p/show x) "]")
                    nodes (filterv v/literal? (rest (g/parse text)))]
                (first (:values (v/read-literals nodes text (into {} @(repo/registry))
                                                 (:auto-ids @m/session)))))
    :else (e/mu-error "not material:" (p/show x))))

(defn store!
  "Store x in the repo, under name if given, else its own id or an auto
   one; returns the id."
  [x name]
  (if (leaf? x)
    (first (:ids (m/parse (str "[" (when name (str (clojure.core/name name) ": ")) (p/show x) "]"))))
    (let [mat  (->material x)
          id   (or name (:id mat))
          own  (v/own-nodes mat)
          top  (assoc (v/node mat) :id id)
          root (repo/current :ROOT)]
      (repo/commit-many! (-> own (dissoc (:id mat)) (assoc id top)))
      (when (and (not= :CONTEXT (:type top)) (not (some #{id} (:children root))))
        (repo/commit-node! :ROOT (update root :children (fnil conj []) id)))
      (reg/log! :parse)
      id)))

(word! "kernel" "!!" "( value [:id] -- )"
       (fn [s]
         (need s 1 "!!")
         (let [[name s] (if (and (keyword? (peek s)) (> (count s) 1)) [(peek s) (pop s)] [nil s])
               id (store! (peek s) name)]
           (println (pr-str id))
           (pop s))))

(word! "kernel" "@@" "( :id -- value )"
       (fn [s]
         (need s 1 "@@")
         (let [id (peek s)]
           (when-not (keyword? id) (e/mu-error "@@: needs a name, like :motif, got" (p/show id)))
           (when-not (repo/current id) (e/mu-error "@@: nothing is stored as" (pr-str id)))
           (let [now (into {} @(repo/registry))]
             (conj (pop s) (assoc (v/->Material id now) :base now))))))

;; -- playback -------------------------------------------------------------

(defn- context? [x] (and (v/material? x) (= :CONTEXT (:type (v/node x)))))

(defn play!
  "Play x, under a context if given, without storing either: the engine
   reads a repo of what is stored plus their own nodes."
  [x ctx]
  (when (nil? @m/receiver) (m/connect))
  (let [mat  (->material x)
        snap (merge (into {} @(repo/registry)) (v/own-nodes mat) (when ctx (v/own-nodes ctx)))
        form (if ctx [(:id ctx) (:id mat)] (:id mat))]
    (reg/log! :play)
    (binding [engine/*engine* (assoc engine/*engine* :repo (atom snap))]
      (engine/play form))))

(word! "kernel" "play" "( material [^{ }] -- )"
       (fn [s]
         (need s 1 "play")
         (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
           (need s 1 "play")
           (play! (peek s) ctx)
           (pop s))))

(word! "kernel" "stop" "( -- )" (fn [s] (m/stop!) s))
(word! "kernel" "pause" "( -- )" (fn [s] (m/pause!) s))
(word! "kernel" "resume" "( -- )" (fn [s] (m/resume!) s))

(defn defaults-text
  "The current defaults (:ROOT's played keys) as a ^{ } literal."
  []
  (let [vals (some-> (repo/current :ROOT) :context :envelopes-atom deref)
        played (set (map name r/played-keys))
        fmt (fn [k x]
              (cond
                (instance? musics.common.music_elements.Key x) (str "!key:" (el/key->str x))
                (instance? musics.common.music_elements.Meter x) (str "!Meter:" (el/meter->str x))
                (number? x) (str "!" k ":" (if (ratio? x) x (pr-str x)))
                (keyword? x) (str "!" k ":" (name x))
                :else nil))]
    (str "^{ " (str/join " " (keep (fn [[k x]] (when (played k) (fmt k x))) (sort-by key vals))) " }")))

(word! "kernel" "defaults" "( -- ^{ } )"
       (fn [s]
         (let [text (defaults-text)
               nodes (filterv v/literal? (rest (g/parse text)))]
           (conj s (first (:values (v/read-literals nodes text (into {} @(repo/registry))
                                                    (:auto-ids @m/session))))))))

;; -- namespaces, help, files --------------------------------------------------

(defn- string-arg [s word]
  (need s 1 word)
  (let [x (peek s)]
    (when-not (string? x) (e/mu-error (str word ":") "needs a name in quotes, like \"tool\", got" (p/show x)))
    x))

(word! "kernel" "use" "( \"ns\" -- )"
       (fn [s] (let [n (string-arg s "use")] (swap! e/state update :use #(vec (distinct (conj % n)))) (pop s))))

(word! "kernel" "in" "( \"ns\" -- )"
       (fn [s] (let [n (string-arg s "in")] (swap! e/state assoc :in n) (pop s))))

(word! "kernel" "words" "( -- )"
       (fn [s]
         (doseq [ns (:use @e/state)
                 :let [ws (sort (keys (get-in @e/state [:ns ns])))]
                 :when (seq ws)]
           (println (str ns ":") (str/join " " ws)))
         s))

(word! "kernel" "see" "( \"word\" -- )"
       (fn [s]
         (let [n (string-arg s "see") entry (e/lookup @e/state n)]
           (println (cond (nil? entry) (str "unknown word: " n)
                          (:source entry) (:source entry)
                          :else (str n " " (:effect entry) "  (built in)")))
           (pop s))))

(word! "kernel" "load" "( \"file.mus\" -- )"
       (fn [s]
         (let [path (string-arg s "load")]
           (when-not (.exists (java.io.File. ^String path)) (e/mu-error "load: no such file:" path))
           (if-let [{:keys [ids]} (m/parse-file path)]
             (println "stored" (str/join " " (map pr-str ids)))
             (e/mu-error "load: the file didn't parse:" path))
           (pop s))))
