(ns musics.mu.about
  "about: what a name is, and where it can appear, in one sentence for each
   thing it can be -- a musical parameter (a context key), a property of
   leaves, a mark, a word of mu, an instrument, a drum, a kit, a scale, a
   chord quality, an articulation, a rule of the grammar. Where it can
   appear is read from the grammar itself: the containers (^{ }, { }, [ ])
   that allow the rule directly. settings gives a parameter's detail and
   marks its named values."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [musics.common.context-keys :as ck]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.mu.grammar :as g]))

;; -- where a rule can appear ------------------------------------------------------------

(def ^:private containers
  "The brackets that hold music, with how they are written."
  [[:Context "^{ }"] [:Parallel "{ }"] [:Sequence "[ ]"]])

(def ^:private wrappers
  "Rules a search does not enter: another container, a data list, a command and its
   body hold their contents one level down."
  #{:Context :Parallel :Sequence :Data :Command :Scope})

(defn- rules-in
  "The rules a combinator mentions."
  [body]
  (let [acc (atom #{})]
    (walk/postwalk (fn [x] (when (and (map? x) (= :nt (:tag x))) (swap! acc conj (:keyword x))) x) body)
    @acc))

(defn- direct
  "The rules reachable from container rule c without entering another container."
  [grammar c]
  (loop [seen #{} todo (vec (rules-in (grammar c)))]
    (if-let [r (peek todo)]
      (let [todo (pop todo)]
        (if (or (seen r) (wrappers r) (not (grammar r)))
          (recur seen todo)
          (recur (conj seen r) (into todo (rules-in (grammar r))))))
      seen)))

(defn homes
  "The containers (^{ }, { }, [ ]) that allow rule directly, as \"a, b and c\"
   (nil when none does)."
  [rule]
  (let [grammar (:grammar g/parser)
        in (for [[c shown] containers :when (contains? (direct grammar c) rule)] shown)]
    (when (seq in)
      (if (next in)
        (str (str/join ", " (butlast in)) " and " (last in))
        (first in)))))

(defn- appears [rule]
  (if-let [h (homes rule)] (str "can appear in " h) "can not appear directly in a bracket"))

;; -- the things a name can be ---------------------------------------------------------------------

(defn- same? [a b] (= (str/lower-case (str a)) (str/lower-case (str b))))

(defn- distinct-ci [xs] (map first (vals (group-by str/lower-case xs))))

(defn- key-names [{:keys [name aliases]}] (cons name (map clojure.core/name aliases)))

(defn- parameters
  "The context keys t names (by name or alias)."
  [t]
  (filter #(some (partial same? t) (key-names %)) (vals (ck/context-keys))))

(def ^:private leaf-properties
  "What a note, chord, rest or drum carries of its own, and for a duration also what a seq has."
  {"pitch" "a note or chord"
   "duration" "a note, chord or rest, or the length of a seq"
   "articulation" "a note or chord"})

(defn- property [t]
  (when-let [of (leaf-properties (str/lower-case t))]
    [(str t " is a property of " of ".")]))

(defn- parameter [t]
  (for [k (parameters t)]
    (str t " is a musical parameter (" (str/join "|" (distinct-ci (key-names k))) ") and " (appears :Assignment) ".")))

(defn- mark [t]
  (for [[k [ctx-key v]] data/instruction-context :when (same? t (name k))]
    (str "!" (name k) " is a mark (it sets " (name ctx-key) " to " v ") and " (appears :BangConst) ".")))

(defn- word [st t]
  (for [[ns ws] (sort-by key (:ns st)) [w entry] ws
        :when (or (same? t w) (same? t (str ns "/" w)))
        :let [eff (str (:effect entry))
              head (or (re-find #"^\(.*?\)" eff) "")]]
    (str w " is a word of mu " head ".")))

(defn- instrument [t]
  (for [{:keys [name short program]} (data/instrument-matches t)
        :when (or (same? t name) (same? t short))]
    (str name " is an instrument (" short ", program " program ") and " (appears :Assignment) ".")))

(defn- drum [t]
  (for [{:keys [key name short also]} (data/drum-matches t)
        :when (some #(same? t %) (cons name (cons short also)))]
    (str name " is a drum (key " key ") and " (appears :Drum) ".")))

(defn- kit [t]
  (for [{:keys [name program]} (data/kit-matches t) :when (same? t name)]
    (str name " is a drum kit (program " program ") and " (appears :Assignment) ".")))

(defn- scale [t]
  (for [[k v] data/scales
        :when (or (same? t (name k)) (same? t (:display v)))
        :let [alt (map name (:alternatives v))]]
    (str (:display v) " is a scale" (when (seq alt) (str " (also " (str/join ", " alt) ")")) ".")))

(defn- chord [t]
  (for [k (keys data/chord-qualities) :when (same? t (name k))]
    (str (name k) " is a chord quality and " (appears :ChordSymbol) ".")))

(defn- articulation [t]
  (for [[k v] data/articulations :when (same? t (name k))]
    (str (name k) " is an articulation (length " (:duration v) ", accent " (:dynamic v) ") and "
         (appears :Leaf) ", after a note.")))

(defn- rule-names
  "The rule names of the grammar resources."
  []
  (set (for [f ["musics/input/musics.ebnf" "musics/mu.ebnf"] :let [r (io/resource f)] :when r
             l (str/split-lines (slurp r))
             :let [m (re-matches #"^<?([A-Z][A-Za-z0-9]*)>?\s*=.*" l)] :when m]
         (second m))))

(defn- rule [t]
  ;; a rule is named as the grammar writes it (Leaf, TempoMark), so pitch is not Pitch
  (for [r (rule-names) :when (= t r)]
    (str r " is a rule of the grammar and " (appears (keyword r)) ".")))

;; -- names that start with it, else contain it -----------------------------------------------

(defn- similar
  "Up to eight parameters, marks and words whose name starts with t, else contains it."
  [st t]
  (let [t (str/lower-case t)
        names (distinct (concat (map :name (vals (ck/context-keys)))
                                (map name (keys data/instruction-context))
                                (for [[_ ws] (:ns st) w (keys ws)] w)))
        by (fn [f] (->> names (filter #(f (str/lower-case %) t)) sort (take 8)))
        starts (by str/starts-with?)]
    (if (seq starts) starts (by str/includes?))))

;; -- together ------------------------------------------------------------------------------------------

(def ^:private usage
  "about <name>: what a word, parameter, mark, instrument, drum, scale or grammar rule is, and where it can appear -- about tempo, about violin; settings tempo and marks tempo say more")

(defn report
  "The text about prints for name n against state st: a sentence for each thing it is."
  [st n]
  (let [t (str/trim (str n))]
    (if (str/blank? t)
      usage
      (let [lines (concat (property t) (parameter t) (mark t) (word st t) (instrument t) (drum t) (kit t)
                          (scale t) (chord t) (articulation t) (rule t))]
        (if (seq lines)
          (str/join "\n" lines)
          (let [sim (similar st t)]
            (str "nothing is called " (pr-str t)
                 (if (seq sim) (str "; names with it: " (str/join ", " sim)) "; words lists the words"))))))))

(defn- written
  "A default as it is written: a key as D.major, a meter as 4/4."
  [x]
  (cond (instance? musics.common.music_elements.Key x) (el/key->str x)
        (instance? musics.common.music_elements.Meter x) (el/meter->str x)
        :else (pr-str x)))

(defn settings-text
  "A musical parameter in detail: its range, default, what it is, how it is written
   and set. For any other name, where to look."
  [n]
  (let [t (str/trim (str n))
        ks (parameters t)]
    (cond
      (str/blank? t) "settings <name>: a musical parameter's range, default and how it is written -- settings tempo"
      (seq ks)
      (str/join "\n"
                (for [{:keys [range default description]} ks
                      :let [shown (if (number? default) default "value")]]
                  (str (when range (str (first range) ".." (second range) ", ")) "default " (written default) ", "
                       (str/replace (str description) #"\.$" "") ". Written !" t ":" shown
                       "; on a line: x !" t ":" shown " with")))
      :else (str (pr-str t) " is no musical parameter; about " t " says what it is"))))

(defn marks-text
  "The marks that set a parameter, by value (no numbers). t is the parameter, or one
   of its marks."
  [n]
  (let [t (str/trim (str n))
        ctxs (or (seq (map #(clojure.core/name (:name %)) (parameters t)))
                 (some (fn [[k [c _]]] (when (same? t (name k)) [(name c)])) data/instruction-context))
        ms (when ctxs
             (sort-by (fn [[k [_ v]]] (if (number? v) [0 v "" (name k)] [1 0 (str v) (name k)]))
                      (filter (fn [[_ [c _]]] (some #(same? (name c) %) ctxs)) data/instruction-context)))]
    (cond
      (str/blank? t) "marks <name>: the marks that set a musical parameter, by value -- marks tempo"
      (seq ms) (str/join " " (for [[k _] ms] (str "!" (name k))))
      :else (str (pr-str t) " has no marks"))))
