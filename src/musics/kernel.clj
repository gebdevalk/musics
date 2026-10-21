(ns musics.kernel
  "mu's kernel words as Clojure functions: numbers and comparison, the
   repo, namespaces, help, files and the defaults -- registered into mu's
   kernel namespace by musics.mu.library. The words of the stack itself
   (dup, swap, pick, …), of quotations (call, if, each, …), binding (:=)
   and printing the stack stay stack words in musics.mu.kernel."
  (:refer-clojure :exclude [min max mod abs not int float])
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [musics.common.music-elements :as el]
            [musics.core :as m]
            [musics.domain.resolve :as r]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

;; -- numbers ------------------------------------------------------------
;; The plain symbols are musical; arithmetic and comparison on numbers
;; have names (doc/md/mu-repl.md).

(defn- numbers [& xs]
  (when-not (every? number? xs)
    (fail (if (next xs) "needs two numbers, got" "needs a number, got") (str/join " and " (map p/show xs)))))

(defn add      "Add two numbers" [a b] (numbers a b) (+ a b))
(defn sub      "Subtract b from a" [a b] (numbers a b) (- a b))
(defn mul      "Multiply two numbers" [a b] (numbers a b) (* a b))
(defn div      "Divide a by b, exactly: 1 4 div gives 1/4" [a b]
  (numbers a b) (when (zero? b) (fail "division by zero")) (/ a b))
(defn mod      "The remainder of a divided by b" [a b] (numbers a b) (core/mod a b))
(defn min      "The smaller of two numbers" [a b] (numbers a b) (core/min a b))
(defn max      "The larger of two numbers" [a b] (numbers a b) (core/max a b))
(defn neg      "The number negated" [a] (numbers a) (- a))
(defn abs      "The number without its sign" [a] (numbers a) (core/abs a))
(defn round    "Rounded to the nearest whole number" [a] (numbers a) (long (Math/round (double a))))
(defn int      "Cut to a whole number, toward zero: 7/2 int gives 3" [a] (numbers a) (long a))
(defn float    "As a decimal number: 1/4 float gives 0.25" [a] (numbers a) (double a))
(defn rational "As an exact ratio: 0.1 rational gives 1/10" [a] (numbers a) (rationalize a))

(defn lt "True when a is less than b" [a b] (numbers a b) (< a b))
(defn gt "True when a is greater than b" [a b] (numbers a b) (> a b))
(defn le "True when a is at most b" [a b] (numbers a b) (<= a b))
(defn ge "True when a is at least b" [a b] (numbers a b) (>= a b))
(defn not "True becomes false, false true" [flag] (core/not flag))

(defn- same?
  "a and b equal: music (a line or a note) when it prints the same, its
   names left out, so two lines of the same notes are equal wherever
   each came from and whatever they are called; two loops when they
   repeat the same; data and streams element by element. An endless
   stream that is no loop is refused: it would never end."
  [a b]
  (let [data?    #(and (v/material? %) (= :DATA (mt/kind %)))
        music?   #(and (or (v/material? %) (v/leaf? %)) (core/not (data? %)))
        endless? #(and (sequential? %) (:mu/endless (meta %)))
        items?   #(or (data? %) (and (sequential? %) (core/not (string? %))))]
    (cond
      (and (music? a) (music? b)) (binding [p/*names* false] (= (p/show a) (p/show b)))
      (and (mt/loop? a) (mt/loop? b)) (same? (first (mt/repeat-of a)) (first (mt/repeat-of b)))
      (or (endless? a) (endless? b)) (fail "an endless stream never ends -- take some first")
      (and (items? a) (items? b)) (let [xs (vec (mt/elements a)) ys (vec (mt/elements b))]
                                    (and (= (count xs) (count ys)) (every? true? (map same? xs ys))))
      :else (= a b))))

(defn eq "True when a equals b (any values; music when it has the same notes)" [a b] (same? a b))
(defn ne "True when a differs from b (any values; music when its notes differ)" [a b] (core/not (same? a b)))

;; -- the repo -------------------------------------------------------------

(defn store
  "Store, silently, under the id on top if given; leaves the id it
   stored under"
  {:mu {:names ["R!"] :optional {:id keyword?}}}
  ([value] (mt/store! value nil))
  ([value id] (mt/store! value id)))

(defn fetch
  "What the repo holds under :id"
  {:mu {:names ["R@"]}}
  [id]
  (when-not (keyword? id) (fail "needs a name, like :motif, got" (p/show id)))
  (mt/fetch id))

(defn- stored-id
  "The id x names -- a name, or material fetched with R@ -- if something
   is stored under it."
  [x]
  (let [id (cond (keyword? x) x (v/material? x) (:id x))]
    (when-not (and id (repo/current id))
      (fail "needs the name of something stored, like :motif, got" (p/show x)))
    id))

;; Each prints what its musics.core namesake gives at the REPL.

(defn ids!    "Every stored id, as (musics.core/ids)" [] (prn (m/ids)))
(defn usages! "The ids whose content uses it directly, as (musics.core/usages id)" [id] (prn (m/usages (stored-id id))))
(defn ctx!    "A part's context chain, as (musics.core/ctx id)" [id] (m/ctx (stored-id id)))

;; -- the defaults -----------------------------------------------------------

(defn- context-text
  "vals ({key value}, keys as a context holds them) as a ^{ } literal,
   those keep? accepts."
  [vals keep?]
  (let [fmt (fn [k x]
              (cond
                (instance? musics.common.music_elements.Key x) (str "!K:" (el/key->str x))
                (instance? musics.common.music_elements.Meter x) (str "!M:" (el/meter->str x))
                (number? x) (str "!" k ":" (if (ratio? x) x (pr-str x)))
                (keyword? x) (str "!" k ":" (name x))
                :else nil))]
    (str "^{ " (str/join " " (keep (fn [[k x]] (when (keep? k) (fmt k x))) (sort-by key vals))) " }")))

(defn defaults-text
  "The current defaults (:ROOT's played keys) as a ^{ } literal."
  []
  (context-text (some-> (repo/current :ROOT) :context :envelopes-atom deref)
                (set (map name r/played-keys))))

(defn defaults
  "The context every part starts from (:ROOT's), as a ^{ }"
  []
  (mt/read-text (defaults-text)))

;; -- namespaces, help, files --------------------------------------------------

(defn- name-of
  "A name given in quotes (\"tool\") or quoted ('tool, '[R])."
  [x]
  (cond (string? x) x
        (symbol? x) (str x)
        :else (fail "needs a name, \"tool\" or 'tool, got" (p/show x))))

(defn use!
  "Make namespace ns's words usable without ns/: \"pitch\" use, or 'pitch use"
  [ns]
  (let [n (name-of ns)] (swap! e/state update :use #(vec (distinct (conj % n))))))

(defn in!
  "Put new definitions into namespace ns"
  [ns]
  (swap! e/state assoc :in (name-of ns)))

(defn cls!
  "Clear the screen; the stack stays"
  []
  (print "\u001b[H\u001b[2J") (flush))

(defn words!
  "List the words in use, then the other namespaces"
  []
  (doseq [ns (:use @e/state)
          :let [ws (sort (keys (get-in @e/state [:ns ns])))]
          :when (seq ws)]
    (println (str ns ":") (str/join " " ws)))
  (let [others (for [[ns m] (sort-by key (:ns @e/state)) :when (core/not (some #{ns} (:use @e/state)))]
                 (str ns "/ (" (count m) ")"))]
    (when (seq others)
      (println "also, qualified (pitch/lorenz) or after \"pitch\" use:" (str/join " " others)))))

(defn see!
  "Show a word: its stack effect and what it does -- \"dup\" see, or
   'dup see, '[R] see"
  [word]
  (let [n (name-of word) entry (e/lookup @e/state n)]
    (println (cond (nil? entry) (str "unknown word: " n)
                   (:source entry) (:source entry)
                   :else (let [[_ eff what] (re-matches #"(?s)(\(.*?\))\s\s(.*)" (str (:effect entry)))]
                           (if eff
                             (str n " " eff "\n  " what)
                             (str n " " (:effect entry))))))))

(defn load!
  "Read a .mus file into the repo"
  [file]
  (let [path (name-of file)]
    (when-not (.exists (java.io.File. ^String path)) (fail "no such file:" path))
    (if-let [{:keys [ids]} (m/parse-file path)]
      (println "stored" (str/join " " (map pr-str ids)))
      (fail "the file didn't parse:" path))))
