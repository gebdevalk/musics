(ns musics.kernel
  "mu's kernel words as Clojure functions: numbers and comparison, the
   repo, namespaces, help, files and the defaults -- registered into mu's
   kernel namespace by musics.mu.library. The words of the stack itself
   (dup, swap, pick, …), of quotations (call, if, each, …), binding (:=)
   and printing the stack stay stack words in musics.mu.kernel."
  (:refer-clojure :exclude [min max mod abs int float])
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
            [musics.mu.suggest :as suggest]
            [musics.mu.value :as v]
            [musics.recipe :as recipe]
            [musics.repo :as repo]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

;; -- numbers ------------------------------------------------------------
;; Arithmetic is + - * / (musics.arith); comparison and flags are
;; musics.compare.

(defn- numbers [& xs]
  (when-not (every? number? xs)
    (fail (if (next xs) "needs two numbers, got" "needs a number, got") (str/join " and " (map p/show xs)))))

(defn mod      "The remainder of a divided by b" [a b] (numbers a b) (core/mod a b))
(defn- extreme
  "f over the numbers of a stream, at least one."
  [word f xs]
  (let [ns (mt/numbers-of word xs)]
    (when (empty? ns) (fail "needs at least one number"))
    (core/apply f ns)))

(defn min
  "The smaller of two numbers, or the smallest of a stream's: 3 5 min, '[3 5 1] min"
  {:mu {:optional {:b number?}}}
  ([xs] (extreme "min" core/min xs))
  ([a b] (numbers a b) (core/min a b)))

(defn max
  "The larger of two numbers, or the largest of a stream's: 3 5 max, '[3 5 1] max"
  {:mu {:optional {:b number?}}}
  ([xs] (extreme "max" core/max xs))
  ([a b] (numbers a b) (core/max a b)))
(defn negate   "The number negated" [a] (numbers a) (- a))
(defn abs      "The number without its sign" [a] (numbers a) (core/abs a))
(defn round    "Rounded to the nearest whole number" [a] (numbers a) (long (Math/round (double a))))
(defn int      "Cut to a whole number, toward zero: 7/2 int gives 3" [a] (numbers a) (long a))
(defn float    "As a decimal number: 1/4 float gives 0.25" [a] (numbers a) (double a))
(defn rational "As an exact ratio: 0.1 rational gives 1/10" [a] (numbers a) (rationalize a))

;; -- the repo -------------------------------------------------------------

(defn- store-as [word refuse? value id]
  (let [stored (mt/store! value id word refuse?)]
    (when (v/recipe? value) (live/follow! stored (recipe/stored (repo/current stored))))
    stored))

(defn store
  "Store, silently, under the id on top if given; leaves the id it
   stored under. Refused when it would redefine an id that other stored
   parts use (R!! does that). A recipe is stored as its text; a live voice
   playing the recipe stored under that id plays the new one from its next note"
  {:mu {:names ["R!"] :optional {:id keyword?}}}
  ([value] (store value nil))
  ([value id] (store-as "R!" true value id)))

(defn overwrite
  "Store as R! does, but redefine an id other stored parts use, saying which
   they are: [a: c d] R! [b: :a e] R! [a: e f] R!! changes b too"
  {:mu {:names ["R!!"] :optional {:id keyword?}}}
  ([value] (overwrite value nil))
  ([value id] (store-as "R!!" false value id)))

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
(defn forget!
  "Take a stored name out of the repo, and out of :ROOT: :motif forget.
   Refused while something stored uses it; what plays keeps playing"
  [id]
  (repo/forget! (stored-id id)))

(defn rename!
  "Give a stored name another, everywhere it is used: :motif :theme
   rename. Refused when the new name is stored already or is no name"
  [old new]
  (when-not (keyword? new) (fail "needs the new name on top, like :theme, got" (p/show new)))
  (repo/rename! (stored-id old) new))

(defn ctx!
  "A part's context chain: each ancestor's own settings, nearest first; a
   line not stored: its own settings, where they begin"
  [x]
  (r/ctx (if (and (v/material? x) (not (repo/current (:id x)))) (v/node x) (stored-id x))))

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
     (swap! e/state #(-> % (update :ns dissoc alias) (assoc-in [:required alias] sym) (assoc-in [:sources alias] n)))
     ((requiring-resolve 'musics.mu.library/register!) sym alias)
     (println (str sym) "as" (str alias "/") (str "(" (count (get-in @e/state [:ns alias])) " words)")))))

(defn in!
  "Put new definitions into namespace ns"
  [ns]
  (swap! e/state assoc :in (name-of ns)))

(defn cls!
  "Clear the screen; the stack stays"
  []
  (@e/clear-screen))

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
    (println (cond (nil? entry) (str "unknown word: " n " -- " (suggest/hint @e/state n))
                   (:source entry) (:source entry)
                   :else (let [[_ eff what] (re-matches #"(?s)(\(.*?\))\s\s(.*)" (str (:effect entry)))]
                           (if eff
                             (str n " " eff "\n  " what)
                             (str n " " (:effect entry)))))))))

(def ^:private guide
  ["mu: music is written as text and goes on the stack; words change it, join it and play it."
   ""
   "  [c d e f]            a line, now on the stack (a note alone is a value too)"
   "  7 +   2 *   [R]      up a fifth, twice as long, backwards: a word takes what lies under it"
   "  ->   ->>   ->+       play now, at the next boundary, joining what plays;  stop  pause"
   "  [bass: c2 g] [~] ->  a named line is a voice of that name;  :bass stop,  :bass <- is what it plays"
   "  dup drop swap over   the stack is Forth's;  .s shows it,  clear empties it"
   "  see  words  help     \"dup\" see explains a word, words lists them, \"chord\" help searches"
   "  Tab                  completes words, ids, instruments and keys;  bye leaves (Ctrl+D too)"
   ""
   "  more: doc/md/mu-tutorial.md walks through it, doc/md/mu-repl.md has every word"])

(defn- matches
  "[where word effect] for each word whose name or description mentions text."
  [text]
  (let [t (str/lower-case text) st @e/state]
    (sort-by (fn [[_ w _]] (if (str/includes? (str/lower-case w) t) 0 1))
             (for [[ns ws] (sort-by key (:ns st)) [w entry] (sort-by key ws)
                   :let [eff (str (:effect entry))]
                   :when (or (str/includes? (str/lower-case w) t) (str/includes? (str/lower-case eff) t))]
               [(if (some #{ns} (:use st)) w (str ns "/" w)) w eff]))))

(defn help!
  "mu in a few lines; with a word, as see explains it; with other text,
   the words that mention it: help, 'dup help, \"chord\" help, \"transpose\" help"
  {:mu {:optional {:topic #(or (string? %) (symbol? %))}}}
  ([] (run! println guide))
  ([topic]
   (let [n (name-of topic)]
     (if (try (e/lookup @e/state n) (catch clojure.lang.ExceptionInfo _ :ambiguous))
       (see! topic)
       (let [hits (matches n)]
         (if (empty? hits)
           (println (str "nothing mentions \"" n "\" -- " (suggest/hint @e/state n)))
           (do (doseq [[shown _ eff] (take 20 hits)]
                 (println (str shown "  " (first (str/split eff #"\s\s" 2)))))
               (when (> (count hits) 20) (println (str "… and " (- (count hits) 20) " more -- a longer text narrows it"))))))))))

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

(defn save-session!
  "Save the session to a file: what is stored, mu's words and bindings
   (not the stack): \"piece.edn\" save-session"
  [file]
  ((requiring-resolve 'musics.persist/persist-session) (name-of file)))

(defn restore-session!
  "Restore a session saved with save-session: what is stored and mu's
   words and bindings are replaced by the file's; the stack stays"
  [file]
  (let [path (name-of file)]
    (when-not (.exists (java.io.File. ^String path)) (fail "no such file:" path))
    ((requiring-resolve 'musics.persist/restore-session) path)))

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
