(ns musics.feedback
  "Ratings of what was heard (doc/feedback.md): like 1, neutral 0,
   dislike -1, appended one map per line to a file. A trial's :what is
   the sound as mu prints it; what it is made of is read back from that
   text (sound-parts), so the file never needs migrating. Knows nothing
   of mu or the engine."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [musics.input.grammar-parser :as gp]))

(defonce ^{:doc "The ratings file; tests point it elsewhere."}
  file (atom (str (System/getProperty "user.home") "/.mu-feedback.edn")))

(defn rate!
  "Append a rating of trial ({:what :synth :soundfont :where}) with score
   (-1, 0 or 1); returns the record."
  [trial score]
  (when-not (contains? #{-1 0 1} score)
    (throw (ex-info (str "rate!: a score is -1, 0 or 1, got " score) {:score score})))
  (let [rec (merge {:at (java.util.Date.)} (select-keys trial [:what :synth :soundfont :where]) {:score score})]
    (locking file
      (spit @file (str (pr-str rec) "\n") :append true))
    rec))

(defn ratings
  "Every rating in the file, oldest first; a line that doesn't read is
   skipped."
  []
  (let [f (io/file @file)]
    (if-not (.isFile f)
      []
      (with-open [r (io/reader f)]
        (into [] (keep #(try (edn/read-string %) (catch Exception _ nil))) (line-seq r))))))

(defn sound-parts
  "What the sound in text is made of: {:kind :drum :keys [36 39]} or
   {:kind :note :instruments [40 73]} (a note without its own
   instrument plays the default, 0); nil when text holds no single
   note, chord or drum."
  [text]
  (try
    (let [{:keys [tree]} (gp/parse-domain-string (str "[" text "]"))
          leaf (some #(when (#{:LEAF :DRUM} (:type %)) %)
                     (mapcat :children (filter #(= :SEQ (:type %)) (vals tree))))
          many (fn [x] (cond (vector? x) x (nil? x) nil :else [x]))]
      (case (:type leaf)
        :DRUM {:kind :drum :keys (or (many (:program leaf)) [35])}
        :LEAF {:kind :note :instruments (or (many (get-in leaf [:overrides :instrument])) [0])}
        nil))
    (catch Exception _ nil)))

(defn- summary [recs]
  {:n (count recs) :mean (double (/ (reduce + (map :score recs)) (count recs)))})

(defn by-sound
  "[[what {:n :mean}] ...] per rated sound, best first."
  ([] (by-sound (ratings)))
  ([recs]
   (->> (group-by :what recs)
        (map (fn [[what rs]] [what (summary rs)]))
        (sort-by (fn [[what {:keys [mean n]}]] [(- mean) (- n) what])))))

(defn by-part
  "[[part {:n :mean}] ...] per instrument ([:instrument 40]) or drum key
   ([:drum 36]), best first: every rating of a sound counts for each
   instrument or key it layers."
  ([] (by-part (ratings)))
  ([recs]
   (->> (for [r recs
              :let [{:keys [kind keys instruments]} (sound-parts (:what r))]
              p (if (= kind :drum) (map #(vector :drum %) keys) (map #(vector :instrument %) instruments))]
          [p r])
        (group-by first)
        (map (fn [[p prs]] [p (summary (map second prs))]))
        (sort-by (fn [[p {:keys [mean n]}]] [(- mean) (- n) (str p)])))))

(defn sounds-where
  "The rated sounds whose mean score passes pred, best first."
  [pred]
  (for [[what {:keys [mean]}] (by-sound) :when (pred mean)] what))

(defn table
  "rows ([label {:n :mean}]) as aligned text lines."
  [rows]
  (let [w (reduce max 4 (map (comp count first) rows))]
    (for [[label {:keys [n mean]}] rows]
      (format (str "%-" w "s  %+.2f  %d") label mean n))))

(defn part-label
  "[:instrument 40] -> \"\\i:40 trumpet\"-like text, via name-of (n -> name or nil)."
  [[kind n] name-of]
  (str (if (= kind :drum) "x\\" "\\i:") n (some->> (name-of kind n) (str " "))))
