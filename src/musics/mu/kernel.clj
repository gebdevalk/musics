(ns musics.mu.kernel
  "mu's kernel words (namespace kernel): the stack, quotations,
   printing, numbers, variables, the repo, playback, namespaces, help,
   files and the defaults (doc/mu-repl.md, section 8)."
  (:require [clojure.string :as str]
            [musics.common.music-elements :as el]
            [musics.core :as m]
            [musics.domain.resolve :as r]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
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

(word! "kernel" "each" "( things q -- … )"
       (fn [s]
         (need s 2 "each")
         (let [q (peek s) xs (mt/elements (peek (pop s)))]
           (reduce (fn [st x] (e/call (conj st x) q)) (pop (pop s)) xs))))

(word! "kernel" "reduce" "( things init q -- r )"
       (fn [s]
         (need s 3 "reduce")
         (let [n (count s) [xs init q] (subvec s (- n 3))]
           (reduce (fn [st x] (e/call (conj st x) q)) (conj (subvec s 0 (- n 3)) init) (mt/elements xs)))))

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

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

(defn- transpose-leaf [n leaf]
  (if (seq (:pitches leaf)) (update leaf :pitches #(mapv (partial + n) %)) leaf))

(defn- scale-leaf [f leaf] (update leaf :duration #(* % f)))

(defn- num-or-material
  "word on two numbers is f; on material and a number, g on every leaf."
  [word f g hint]
  (fn [s]
    (need s 2 word)
    (let [b (peek s) a (peek (pop s))]
      (conj (pop (pop s))
            (cond
              (and (number? a) (number? b)) (f a b)
              (and (musical? a) (number? b) g) (mt/map-leaves (partial g b) a)
              (musical? a) (e/mu-error (str word ":") hint)
              :else (e/mu-error (str word ":") "needs two numbers, or material and a number; got"
                                (p/show a) "and" (p/show b)))))))

(word! "kernel" "+" "( a b -- a+b ) ( material n -- transposed by n semitones )"
       (num-or-material "+" + (fn [n leaf] (when-not (integer? n) (e/mu-error "+: transposes by a whole number of semitones, got" n))
                                (transpose-leaf n leaf))
                        nil))
(word! "kernel" "-" "( a b -- a-b )"
       (num-or-material "-" - nil "on material, transpose down with a negative number: -12 +"))
(word! "kernel" "*" "( a b -- a*b ) ( material f -- durations times f )"
       (num-or-material "*" * (fn [f leaf] (when-not (pos? f) (e/mu-error "*: needs a positive factor, got" f))
                                (scale-leaf f leaf))
                        nil))
(word! "kernel" "/" "( a b -- a/b )"
       (num-or-material "/" (fn [a b] (when (zero? b) (e/mu-error "/: division by zero")) (/ a b)) nil
                        "on material, shorten with a factor: 1/2 *"))
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

(word! "kernel" "!!" "( value [:id] -- )"
       (fn [s]
         (need s 1 "!!")
         (let [[name s] (if (and (keyword? (peek s)) (> (count s) 1)) [(peek s) (pop s)] [nil s])
               id (mt/store! (peek s) name)]
           (println (pr-str id))
           (pop s))))

(word! "kernel" "@@" "( :id -- value )"
       (fn [s]
         (need s 1 "@@")
         (let [id (peek s)]
           (when-not (keyword? id) (e/mu-error "@@: needs a name, like :motif, got" (p/show id)))
           (conj (pop s) (mt/fetch id)))))

;; -- playback -------------------------------------------------------------

(defn- context? [x] (and (v/material? x) (= :CONTEXT (:type (v/node x)))))

(word! "kernel" "play" "( material [^{ }] -- )"
       (fn [s]
         (need s 1 "play")
         (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
           (need s 1 "play")
           (mt/play! (peek s) ctx)
           (pop s))))

(word! "kernel" "play+" "( material [^{ }] -- )"
       (fn [s]
         (need s 1 "play+")
         (let [[ctx s] (if (context? (peek s)) [(peek s) (pop s)] [nil s])]
           (need s 1 "play+")
           (mt/play! (peek s) ctx true)
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
         (conj s (mt/read-text (defaults-text)))))

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
