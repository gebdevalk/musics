(ns musics.kernel
  "mu's kernel words as Clojure functions: numbers and comparison, the
   repo, namespaces, help, files and the defaults -- registered into mu's
   kernel namespace by musics.mu.library. The words of the stack itself
   (dup, swap, pick, …), of quotations (call, if, each, …), binding (:=)
   and printing the stack stay stack words in musics.mu.kernel."
  (:refer-clojure :exclude [min max mod abs not int float])
  (:require [clojure.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [musics.common.music-elements :as el]
            [musics.domain.resolve :as r]
            [musics.input.abc-import :as abc]
            [musics.input.guido-import :as guido]
            [musics.input.lilypond-import :as ly]
            [musics.midi.file :as midi-file]
            [musics.mu.eval :as e]
            [musics.mu.live :as live]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.recipe :as recipe]
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
   stored under. A recipe is stored as its text; a live voice playing
   the recipe stored under that id plays the new one from its next note"
  {:mu {:names ["R!"] :optional {:id keyword?}}}
  ([value] (store value nil))
  ([value id]
   (let [stored (mt/store! value id)]
     (when (v/recipe? value) (live/follow! stored (recipe/stored (repo/current stored))))
     stored)))

(defn fetch
  "What the repo holds under :id: music, or a recipe rebuilt from its text"
  {:mu {:names ["R@"]}}
  [id]
  (when-not (keyword? id) (fail "needs a name, like :motif, got" (p/show id)))
  (let [n (repo/current id)]
    (if (= :RECIPE (:type n)) (recipe/stored n) (mt/fetch id))))

(defn- stored-id
  "The id x names -- a name, or material fetched with R@ -- if something
   is stored under it."
  [x]
  (let [id (cond (keyword? x) x (v/material? x) (:id x))]
    (when-not (and id (repo/current id))
      (fail "needs the name of something stored, like :motif, got" (p/show x)))
    id))

(defn ids!    "Every stored id" [] (prn (repo/ids)))
(defn usages! "The ids whose content uses it directly" [id] (prn (repo/usages (stored-id id))))
(defn ctx!    "A part's context chain: each ancestor's own settings, nearest first" [id] (r/ctx (stored-id id)))

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

(defn- clj-ns
  "The Clojure namespace a .clj file declares, from its (ns …) form."
  [path]
  (let [form (with-open [r (java.io.PushbackReader. (io/reader path))]
               (binding [*read-eval* false] (read r)))]
    (if (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (second form)
      (fail "not a Clojure namespace (no ns form first):" path))))

(defn- short-name? [x]
  (and (or (string? x) (symbol? x)) (re-matches #"[a-zA-Z][a-zA-Z0-9_\-]*" (str x))))

(defn require!
  "Load a Clojure namespace now, its public functions mu's words under
   its last name or a short alias: \"musics.algo.random\" require gives
   random/uniform, \"musics.algo.random\" 'r require r/uniform; a .clj
   file too (\"my/words.clj\" require). Requiring again reloads it, a
   file afresh."
  {:mu {:optional {:alias short-name?}}}
  ([ns] (require! ns nil))
  ([ns alias]
   (let [n      (name-of ns)
         file?  (str/ends-with? n ".clj")
         _      (when (and file? (core/not (.exists (java.io.File. ^String n)))) (fail "no such file:" n))
         sym    (if file? (clj-ns n) (symbol n))
         alias  (str (or alias (last (str/split (str sym) #"\."))))
         st     @e/state
         owner  (get-in st [:required alias])]
     (when (and (contains? (:ns st) alias) (core/not= owner sym))
       (fail (str "'" alias) "is a namespace already" (if owner (str "(" owner ")") "of mu's own")
             (str "-- give a short alias: \"" sym "\" 'x require")))
     ;; a file's namespace is loaded afresh, so a function taken out of it goes
     (try (if file? (do (remove-ns sym) (load-file n)) (core/require sym :reload))
          (catch java.io.FileNotFoundException _ (fail "no namespace" (str sym) "on the classpath")))
     (swap! e/state #(-> % (update :ns dissoc alias) (assoc-in [:required alias] sym)))
     ((requiring-resolve 'musics.mu.library/register!) sym alias)
     (println (str sym) "as" (str alias "/") (str "(" (count (get-in @e/state [:ns alias])) " words)")))))

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
   'dup see, '[R] see; a tree recipe: the tree it builds, bridges put in"
  [word]
  (if (v/recipe? word)
    ;; a tree recipe: as written, and the tree it builds, bridges put in
    (println (p/show word) "\n  builds" (pr-str ((requiring-resolve 'musics.algo.tree/show) (:tree word)))
             (str "\n  takes " (:arity word) " from the stack under it"))
  (let [n (name-of word) entry (e/lookup @e/state n)]
    (println (cond (nil? entry) (str "unknown word: " n)
                   (:source entry) (:source entry)
                   :else (let [[_ eff what] (re-matches #"(?s)(\(.*?\))\s\s(.*)" (str (:effect entry)))]
                           (if eff
                             (str n " " eff "\n  " what)
                             (str n " " (:effect entry)))))))))

(defn- file-text
  "The musics text of the file at path: a .mus file as it is, LilyPond
   (.ly), ABC (.abc) and GUIDO (.gmn, .guido) converted."
  [^String path]
  (let [ext (some-> (re-find #"\.([^./]+)$" path) second str/lower-case)
        src (slurp path)]
    (case ext
      "ly"              (ly/ly-text->mus-text src (or (.getParent (java.io.File. path)) "."))
      "abc"             (abc/abc-text->mus-text src)
      ("gmn" "guido")   (guido/guido-text->mus-text src)
      src)))

(defn load!
  "Read a file into the repo: musics text (.mus), or LilyPond (.ly), ABC
   (.abc) or GUIDO (.gmn) converted to it"
  [file]
  (let [path (name-of file)]
    (when-not (.exists (java.io.File. ^String path)) (fail "no such file:" path))
    ;; resolved, since musics.mu.store loads this ns
    (if-let [{:keys [ids]} ((requiring-resolve 'musics.mu.store/read!) (file-text path))]
      (println "stored" (str/join " " (map pr-str ids)))
      (fail "the file didn't parse:" path))))

(defn render!
  "Write what playing the music would sound as to a MIDI file, no clock
   involved: x \"x.mid\" render; an endless one needs the seconds to
   write, x \"x.mid\" 60 render"
  {:mu {:optional {:seconds number?}}}
  ([x file] (render! x file nil))
  ([x file seconds]
   (let [path (name-of file)]
     (mt/sounding! "render" x)
     (when (and (nil? seconds) (or (mt/endless? x) (mt/loop? x)))
       (fail "the music is endless -- give the seconds to write: x \"x.mid\" 60 render"))
     (midi-file/write-events (take-while #(< (:t %) (or seconds 3600)) (mt/events x)) ; an hour at most
                             path :seed 0)
     (println "written" path))))
