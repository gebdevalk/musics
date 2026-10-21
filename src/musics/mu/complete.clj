(ns musics.mu.complete
  "Tab completion at mu's prompt, of the name being written: an
   instrument after an instrument key (!i:, \\i:, !instrument: ...), a
   drum after its \\ (x8\\cr), a kit after !kit:, a context key or an
   instruction after ! (!tem, !mf), what a note takes after its \\
   (c4\\vo, c4\\tr: a key, an ornament, a dynamic, an articulation), and
   a scale after a key's tonic (!K:F.ma), a stored part after : (:blu),
   and else a mu word (walk, pitch/lo) -- every name (or short name) that
   begins with what is typed, in any case. A fn of the word and of what
   mu knows now (its words, the repo); musics.mu hands its candidates to
   JLine, which lists them when there are several."
  (:require [clojure.string :as str]
            [musics.common.context-keys :as ck]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.domain.ornaments :as orn]
            [musics.mu.eval :as e]
            [musics.repo :as repo]))

(def ^:private instrument-keys "i|instrument|timbre|program|prog")

(defn- loose [s] (str/replace (str/lower-case s) #"[-_ ]" ""))

(defn- camel
  "A drum's General MIDI name as one word (Closed Hi-Hat: closedHiHat),
   which reads back as that drum."
  [s]
  (let [[a & r] (str/split (str/replace s "-" " ") #" ")]
    (apply str (str/lower-case a) (map str/capitalize r))))

(defn- pick
  "The first of names that typed begins (loosely), else nil."
  [typed names]
  (first (filter #(str/starts-with? (loose %) (loose typed)) names)))

(defn- instruments [typed]
  (for [{:keys [program name short]} (data/instrument-matches "")
        :let [n (pick typed [name short])] :when n]
    {:name n :descr (str program " " name " " short)}))

(defn- drums [typed]
  (for [{:keys [key name short also]} (data/drum-matches "")
        :let [n (pick typed (concat [short] also [(camel name)]))] :when n]
    {:name n :descr (str key " " name)}))

(defn- kits [typed]
  (for [{:keys [name program]} (data/kit-matches "")
        :when (str/starts-with? name (str/lower-case typed))]
    {:name name :descr (str program)}))

(defn- key-names
  "Every context key and alias that typed begins: name, then : to write
   its value after."
  [typed]
  (for [[_ {n :name :keys [aliases description range]}] (sort-by key (ck/context-keys))
        a    (cons n (map name aliases))
        :when (str/starts-with? (str/lower-case a) (str/lower-case typed))
        :when (or (> (count a) 1) (= a typed))]
    {:name (str a ":") :complete false
     :descr (str (when (not= a n) (str n ", ")) description
                 (when range (str " " (first range) ".." (second range))))}))

(defn- instructions
  "Every ! instruction (!mf, !allegro, !swing) that typed begins."
  [typed]
  (for [[k [key v]] (sort-by key data/instruction-context)
        :let [n (name k)]
        ;; a kebab-case name has a camelCase twin; ! can't write a hyphen
        :when (and (not (str/includes? n "-")) (str/starts-with? (str/lower-case n) (str/lower-case typed)))]
    {:name n :descr (str (name key) " " v)}))

(def ^:private articulation-names
  "musics.ebnf's ArticulationName, a note's \\staccato."
  ["staccato" "staccatissimo" "tenuto" "marcato" "portato" "accent" "espressivo" "ghost"])

(def ^:private dynamic-marks
  "musics.ebnf's DynamicMark."
  ["pppp" "ppp" "pp" "p" "mp" "mf" "f" "ff" "fff" "ffff"])

(defn- dynamics
  "Every dynamic mark (a volume) that typed begins."
  [typed]
  (for [n dynamic-marks :when (str/starts-with? n typed)]
    {:name n :descr (str "dynamic, volume " (data/dynamics (keyword n)))}))

(defn- note-tags
  "What a note takes after its \\: a context key's override (\\vol:), an
   ornament, a dynamic or an articulation that typed begins."
  [typed]
  (let [starts? #(str/starts-with? (str/lower-case %) (str/lower-case typed))]
    (concat (key-names typed)
            (for [n (sort (keys orn/ornament-map)) :when (starts? n)] {:name n :descr "ornament"})
            (dynamics (str/lower-case typed))
            (for [n articulation-names :when (starts? n)] {:name n :descr "articulation"}))))

(defn- scales
  "Every scale a key's tonic can take (!K:F.major) that typed begins."
  [typed]
  (for [n (sort (map name (keys el/scale-steps))) :when (str/starts-with? n (str/lower-case typed))]
    {:name n :descr "scale"}))

(defn- effect
  "A word's stack effect, the ( a -- b ) its description opens with."
  [description]
  (or (re-find #"^\(.*?\)" (str description)) ""))

(defn- auto-id?
  "Was id made up by the walker (:s12, :p3, :repeat1), not named?"
  [id node]
  (let [prefix (get {:SEQ "s" :PAR "p" :CONTEXT "c" :DATA "d"} (:type node)
                    (str/lower-case (name (or (:type node) ""))))]
    (boolean (re-matches (re-pattern (str prefix "\\d+")) (name id)))))

(defn live
  "What mu knows now: :words, word name -> its description (the words
   in use bare, every namespace's qualified, ns/name), and :ids, the
   parts stored under a name of their own."
  []
  (let [{:keys [ns use]} @e/state]
    {:words (merge (into {} (for [[n m] ns, [w entry] m] [(str n "/" w) (:effect entry)]))
                   (into {} (for [n use, [w entry] (get ns n)] [w (:effect entry)])))
     :ids   (sort (for [[id node] @(repo/registry)
                        :when (and (keyword? id) (not= :ROOT id) (not (auto-id? id node)))]
                    (name id)))}))

(defn- words [env typed]
  (let [qualified? (str/includes? typed "/")]
    (for [[w desc] (sort-by key (:words env))
          :when (= qualified? (str/includes? w "/"))
          :when (str/starts-with? (str/lower-case w) (str/lower-case typed))]
      {:name w :descr (effect desc)})))

(defn- parts [env typed]
  (for [id (:ids env) :when (str/starts-with? (str/lower-case id) (str/lower-case typed))]
    {:name id :descr "part"}))

(def ^:private kinds
  "[pattern kind], tried in turn: what is being written, the text before
   the name and the name so far; a kind is a fn of the name, or, marked
   :env, of what mu knows and the name."
  [[(re-pattern (str "(?s)(.*[!\\\\](?:" instrument-keys "):(?:[^\\s,]*,)*)([A-Za-z0-9_-]*)")) instruments]
   [#"(?s)(.*!kit:)([A-Za-z0-9_-]*)" kits]
   [#"(?s)(.*[!\\](?:volume|vol|v):)([a-z]*)" dynamics]
   [#"(?s)(.*![Kk](?:ey)?:[A-Ga-g](?:##|&&|#|&)?\.)([A-Za-z-]*)" scales]
   [#"(?s)(.*x[0-9./*]*\\(?:[A-Za-z0-9_-]+,)*)([A-Za-z0-9_-]*)" drums]
   [#"(?s)(.*!)([A-Za-z][A-Za-z0-9]*)?" (fn [typed] (concat (instructions (or typed "")) (key-names (or typed ""))))]
   [#"(?s)(.*[^\\\s]\\)([A-Za-z]*)" note-tags]
   [#"(?s)((?:.*[\[{(])?:)([A-Za-z][A-Za-z0-9_]*)?" parts :env]
   [#"(?s)()([A-Za-z][^\s\[\]{}()'\"\\]*)" words :env]])

(defn candidates
  "The completions of word (the text up to the cursor, from its last
   space): [{:value :display :descr :complete}], :value the whole word
   as it will read (:complete false when a value follows, as after
   !tempo:), or nil when word isn't a name being written."
  ([word] (candidates word (live)))
  ([word env]
   (some (fn [[re f env?]]
           (when-let [[_ before typed] (re-matches re word)]
             (for [{:keys [name descr complete] :or {complete true}}
                   (if env? (f env (or typed "")) (f (or typed "")))]
               {:value (str before name) :display name :descr descr :complete complete})))
         kinds)))
