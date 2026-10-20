(ns musics.mu.complete
  "Tab completion at mu's prompt: the name being written after an
   instrument key (!i:, \\i:, !instrument: ...), a drum's \\ (x8\\cr) or
   !kit:, completed from musics.common.music-data's names -- every
   instrument, drum or kit whose name or short name begins with what is
   typed, in any case. A pure fn of the word; musics.mu hands its
   candidates to JLine, which lists them when there are several."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]))

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

(defn candidates
  "The completions of word (the text up to the cursor, from its last
   space): [{:value :display :descr}], :value the whole word as it will
   read, or nil when word isn't a name being written."
  [word]
  (when-let [[_ before typed kind]
             (or (some-> (re-matches (re-pattern (str "(?s)(.*[!\\\\](?:" instrument-keys "):(?:[^\\s,]*,)*)([A-Za-z0-9_-]*)")) word)
                         (conj :instrument))
                 (some-> (re-matches #"(?s)(.*!kit:)([A-Za-z0-9_-]*)" word) (conj :kit))
                 (some-> (re-matches #"(?s)(.*x[0-9./*]*\\(?:[A-Za-z0-9_-]+,)*)([A-Za-z0-9_-]*)" word) (conj :drum)))]
    (for [{:keys [name descr]} ((case kind :instrument instruments :kit kits :drum drums) typed)]
      {:value (str before name) :display name :descr descr})))
