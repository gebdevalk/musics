(ns musics.mu.kernel
  "mu's kernel words (namespace kernel): the stack, quotations,
   printing, numbers, bindings, the repo, playback, namespaces, help,
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

(word! "kernel" "execute" "( 'word -- … )  run the word a quoted name names (or a quotation, as call)"
       (fn [s]
         (need s 1 "execute")
         (let [x (peek s)]
           (cond
             (symbol? x)       (e/run-word (pop s) (str x))
             (v/quotation? x)  (e/call (pop s) x)
             :else (e/mu-error "execute: needs a quoted word, like 'dup, got" (p/show x))))))

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

(word! "kernel" "cs" "( … -- )  clear the stack" (fn [_] []))

(word! "kernel" ".s" "( -- )  the stack: mu<depth>, then one item a line, top first, numbered as pick counts"
       (fn [s]
         (println (str "mu<" (count s) ">"))
         (doseq [[i x] (map-indexed vector (rseq s))] (println (str i "> " (p/show x))))
         s))

;; -- numbers ------------------------------------------------------------
;; The plain symbols are musical; arithmetic and comparison on numbers
;; take a leading . (doc/mu-repl.md).

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

(word! "kernel" ".+" "( a b -- a+b )" (num2 ".+" +))
(word! "kernel" ".-" "( a b -- a-b )" (num2 ".-" -))
(word! "kernel" ".*" "( a b -- a*b )" (num2 ".*" *))
(word! "kernel" "./" "( a b -- a/b )"
       (num2 "./" (fn [a b] (when (zero? b) (e/mu-error "./: division by zero")) (/ a b))))
(word! "kernel" "mod" "( a b -- a mod b )" (num2 "mod" mod))
(word! "kernel" "min" "( a b -- min )" (num2 "min" min))
(word! "kernel" "max" "( a b -- max )" (num2 "max" max))
(word! "kernel" "neg" "( a -- -a )" (num1 "neg" -))
(word! "kernel" "abs" "( a -- |a| )" (num1 "abs" abs))
(word! "kernel" "round" "( a -- n )" (num1 "round" #(long (Math/round (double %)))))
(word! "kernel" "int" "( a -- n )" (num1 "int" long))
(word! "kernel" "float" "( a -- f )" (num1 "float" double))
(word! "kernel" "rational" "( a -- r )" (num1 "rational" rationalize))

(word! "kernel" ".=" "( a b -- flag )" (fn [s] (need s 2 ".=") (conj (pop (pop s)) (= (peek (pop s)) (peek s)))))
(word! "kernel" ".<" "( a b -- flag )" (num2 ".<" <))
(word! "kernel" ".>" "( a b -- flag )" (num2 ".>" >))
(word! "kernel" ".<=" "( a b -- flag )" (num2 ".<=" <=))
(word! "kernel" ".>=" "( a b -- flag )" (num2 ".>=" >=))
(word! "kernel" "not" "( flag -- flag )" (fn [s] (need s 1 "not") (conj (pop s) (not (peek s)))))

;; -- the musical symbols ------------------------------------------------------

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

(defn- on-material
  "A word ( material n -- material' ): g n on every leaf. On two numbers
   it points to the numeric .word."
  [word g check]
  (fn [s]
    (need s 2 word)
    (let [n (peek s) x (peek (pop s))]
      (cond
        (and (number? x) (number? n))
        (e/mu-error (str word ":") "is musical; on numbers write" (str "." word))
        (not (and (musical? x) (number? n)))
        (e/mu-error (str word ":") "needs material and a number, got" (p/show x) "and" (p/show n))
        :else (do (check n) (conj (pop (pop s)) (mt/map-leaves (partial g n) x)))))))

(defn- transpose-leaf [n leaf]
  (if (seq (:pitches leaf)) (update leaf :pitches #(mapv (partial + n) %)) leaf))

(defn- whole! [word n] (when-not (integer? n) (e/mu-error (str word ":") "transposes by a whole number of semitones, got" n)))
(defn- positive! [word n] (when-not (pos? n) (e/mu-error (str word ":") "needs a positive factor, got" n)))

(word! "kernel" "+" "( material n -- up n semitones )"
       (on-material "+" transpose-leaf (partial whole! "+")))
(word! "kernel" "-" "( material n -- down n semitones )"
       (on-material "-" (fn [n leaf] (transpose-leaf (- n) leaf)) (partial whole! "-")))
;; The factor is kept as the leaf's :ratio, as c4*1/3 keeps it, so a
;; length that is no note value prints as written: c4 1/3 * as C4/4*1/3.
(defn- scale-duration [leaf f]
  (-> leaf (update :duration * f) (update :ratio (fnil * 1) f)))

(word! "kernel" "*" "( material f -- durations times f )"
       (on-material "*" (fn [f leaf] (scale-duration leaf f)) (partial positive! "*")))
(word! "kernel" "/" "( material f -- durations divided by f )"
       (on-material "/" (fn [f leaf] (scale-duration leaf (/ 1 f))) (partial positive! "/")))

(defn- hairpin [word dir]
  (fn [s]
    (need s 2 word)
    (let [target (peek s) x (peek (pop s))]
      (when-not (and (number? target) (<= 0 target 100))
        (e/mu-error (str word ":") "needs a target volume 0-100 on top, got" (p/show target)))
      (when-not (musical? x) (e/mu-error (str word ":") "needs material under the target, got" (p/show x)))
      (conj (pop (pop s)) (mt/hairpin (mt/->material x) dir target)))))

(word! "kernel" "cr" "( material target -- crescendo to target )" (hairpin "cr" "<"))
(word! "kernel" "decr" "( material target -- decrescendo to target )" (hairpin "decr" ">"))

(doseq [w ["<" "<=" ">="]]
  (word! "kernel" w "( -- )"
         (fn [_] (e/mu-error (str w ":") "isn't a word; to compare numbers write" (str "." w)))))

;; -- bindings -------------------------------------------------------------

(word! "kernel" "=" "( 'name value -- )  bind; \\name is the value"
       (fn [s]
         (need s 2 "=")
         (let [x (peek s) n (peek (pop s))]
           (when-not (symbol? n)
             (e/mu-error "=: needs a name under the value, as 'name value =, got" (p/show n)))
           (swap! e/state assoc-in [:bindings (str n)] x)
           (pop (pop s)))))

;; -- the repo -------------------------------------------------------------

(word! "kernel" "R!" "( value [:id] -- :id )  store, silently; leaves the id it stored under"
       (fn [s]
         (need s 1 "R!")
         (let [[name s] (if (and (keyword? (peek s)) (> (count s) 1)) [(peek s) (pop s)] [nil s])
               id (mt/store! (peek s) name)]
           (conj (pop s) id))))

(word! "kernel" "R@" "( :id -- value )"
       (fn [s]
         (need s 1 "R@")
         (let [id (peek s)]
           (when-not (keyword? id) (e/mu-error "R@: needs a name, like :motif, got" (p/show id)))
           (conj (pop s) (mt/fetch id)))))

;; -- inspecting the repo -------------------------------------------------

(defn- stored-id
  "The id on top of the stack -- a name, or material fetched with R@ --
   if something is stored under it."
  [s word]
  (need s 1 word)
  (let [x (peek s) id (cond (keyword? x) x (v/material? x) (:id x))]
    (when-not (and id (repo/current id))
      (e/mu-error (str word ":") "needs the name of something stored, like :motif, got" (p/show x)))
    id))

;; Each prints what its musics.core namesake gives at the REPL.

(word! "kernel" "ids" "( -- )  every stored id, as (musics.core/ids)"
       (fn [s] (prn (m/ids)) s))

(word! "kernel" "usages" "( :id -- )  the ids whose content uses it directly, as (musics.core/usages id)"
       (fn [s] (prn (m/usages (stored-id s "usages"))) (pop s)))

(word! "kernel" "ctx" "( :id -- )  a part's context chain, as (musics.core/ctx id)"
       (fn [s] (m/ctx (stored-id s "ctx")) (pop s)))

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

(word! "kernel" ">" "( material [^{ }] -- )  play" (:fn (get-in @e/state [:ns "kernel" "play"])))
(word! "kernel" ">>" "( material [^{ }] -- )  play+" (:fn (get-in @e/state [:ns "kernel" "play+"])))

(word! "kernel" "stop" "( -- )" (fn [s] (m/stop!) s))
(word! "kernel" "pause" "( -- )" (fn [s] (m/pause!) s))
(word! "kernel" "resume" "( -- )" (fn [s] (m/resume!) s))

(defn- context-text
  "vals ({key value}, keys as a context holds them) as a ^{ } literal,
   those keep? accepts."
  [vals keep?]
  (let [fmt (fn [k x]
              (cond
                (instance? musics.common.music_elements.Key x) (str "!key:" (el/key->str x))
                (instance? musics.common.music_elements.Meter x) (str "!Meter:" (el/meter->str x))
                (number? x) (str "!" k ":" (if (ratio? x) x (pr-str x)))
                (keyword? x) (str "!" k ":" (name x))
                :else nil))]
    (str "^{ " (str/join " " (keep (fn [[k x]] (when (keep? k) (fmt k x))) (sort-by key vals))) " }")))

(defn defaults-text
  "The current defaults (:ROOT's played keys) as a ^{ } literal."
  []
  (context-text (some-> (repo/current :ROOT) :context :envelopes-atom deref)
                (set (map name r/played-keys))))

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

(word! "kernel" "clear" "( -- )  clear the screen; the stack stays"
       (fn [s] (print "\u001b[H\u001b[2J") (flush) s))

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
