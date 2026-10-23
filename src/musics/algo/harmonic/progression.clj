(ns musics.algo.harmonic.progression
  "Chord progressions written as roman numerals and spelled out as
   chord symbols (musics.ebnf's ChordSymbol, `F3/1:7 | B&3:7 | ...`), so
   they parse, play and print like any other musics text.

   A scheme is a string of bars separated by `|`, each bar one or more
   chords sharing it equally: \"I7 | IV7 | IIm7 V7\". A chord is a
   numeral, I to VII (degrees of the major scale), optionally after a
   `b`/`&` or `#` that lowers or raises it, followed by a chord
   quality (musics.common.music-data/chord-qualities, the shorthand and
   additions and removals included: `m7`, `d7`, `m7.5-`, `9^3`); no
   quality is a major triad, written :M."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]))

(def blues
  "Twelve-bar blues schemes, in 4/4.
     :basic        the plain I-IV-V blues, V7 as turnaround in bar 12
     :quick-change IV7 already in bar 2
     :minor        minor tonic and subdominant, bVI7-V7 in bars 9-10
     :jazz         the jazz blues: II-V into IV, #IVd7, VI7 and
                   II-V turnarounds"
  {:basic        "I7 | I7 | I7 | I7 | IV7 | IV7 | I7 | I7 | V7 | IV7 | I7 | V7"
   :quick-change "I7 | IV7 | I7 | I7 | IV7 | IV7 | I7 | I7 | V7 | IV7 | I7 | V7"
   :minor        "Im7 | Im7 | Im7 | Im7 | IVm7 | IVm7 | Im7 | Im7 | bVI7 | V7 | Im7 | V7"
   :jazz         "I7 | IV7 | I7 | Vm7 I7 | IV7 | #IVd7 | I7 | VI7 | IIm7 | V7 | I7 VI7 | IIm7 V7"})

(def ^:private major-steps [0 2 4 5 7 9 11])

(def ^:private numerals {"I" 0 "II" 1 "III" 2 "IV" 3 "V" 4 "VI" 5 "VII" 6})

(def ^:private accidental-text {-2 "&&" -1 "&" 0 "" 1 "#" 2 "##"})

(defn- parse-tonic
  "\"B&\" -> [\\b -1]: a key's tonic as musics text spells it."
  [tonic]
  (let [[_ l a] (or (re-matches #"([A-Ga-g])(##|&&|#|&)?" tonic)
                    (throw (ex-info (str "not a tonic: " tonic) {:tonic tonic})))]
    [(Character/toLowerCase (.charAt ^String l 0)) (get {"##" 2 "&&" -2 "#" 1 "&" -1} a 0)]))

(defn chord
  "One numeral (\"bVI7\") in the key whose tonic is tonic-letter, at
   tonic-midi -> {:letter :acc :root :quality},
   :root the MIDI pitch of the chord's root, at or above the tonic."
  [tonic-letter tonic-midi numeral]
  (let [[_ alter degree quality] (or (re-matches #"([b&#]?)(VII|VI|V|IV|III|II|I)(.*)" numeral)
                                     (throw (ex-info (str "not a chord numeral: " numeral) {:numeral numeral})))
        n      (numerals degree)
        shift  (case alter "#" 1 ("b" "&") -1 0)
        letter (nth data/letter-order (mod (+ (data/diatonic-degree tonic-letter) n) 7))
        root   (+ tonic-midi (major-steps n) shift)
        acc    (let [raw (mod (- root (data/diatonic-pcs letter)) 12)] (if (> raw 6) (- raw 12) raw))]
    {:letter letter :acc acc :root root :quality (not-empty quality)}))

(defn- duration-text
  "A note value, 1 or 1/2^n, as a Duration: 1/2 -> \"2\"."
  [v]
  (let [d (/ 1 v)]
    (if (and (integer? d) (= 1 (Long/bitCount d)))
      (str d)
      (throw (ex-info (str v " is not a note value") {:value v})))))

(defn- chord-text
  "A chord as a chord symbol, its quality always written (:M a major
   triad: without one it would be a note), its duration only where it
   differs from the one before (last, a volatile, the text so far):
   chords inherit it as notes do, in the order they're written."
  [{:keys [letter acc root quality duration]} last]
  (str (str/upper-case letter) (accidental-text acc)
       (dec (quot (- root acc (data/diatonic-pcs letter)) 12))
       (when (not= duration @last) (vreset! last duration) (str "/" (duration-text duration)))
       ":" (or quality "M")))

(defn- tonic-chord
  "The scheme's first chord on I (\"I7\", \"Im7\"), else a plain I."
  [text]
  (or (first (filter #(re-matches #"I(?![IV]).*" %) (str/split (str/trim text) #"[\s|]+"))) "I"))

(defn- scheme-text
  "scheme's roman numerals: a key of blues or of musics.common.music-data/
   choruses, or a scheme string itself."
  [scheme]
  (let [text (or (blues scheme) (get-in data/choruses [scheme :scheme]) scheme)]
    (when-not (string? text) (throw (ex-info (str "no scheme " scheme) {:scheme scheme})))
    text))

(defn- tonic-of
  "The tonic opts asks for, else the chorus' own, else C."
  [scheme opts]
  (or (:tonic opts) (get-in data/choruses [scheme :tonic]) "C"))

(defn bars
  "scheme (a scheme string, a key of blues or of music-data's choruses)
   in tonic's key (a chorus' own by default, else C) ->
   a vector of bars, each a vector of chords (see chord) with their
   :duration, one bar a whole note. With hit (a note value), each
   chord is struck as many times as hit fits in its share of the bar.
   With end (true), a last bar holds the tonic (tonic-chord), so the
   turnaround lands, unless the scheme ends on it already; false leaves
   the form open, to loop."
  [scheme {:keys [octave hit end] :or {octave 3 end true} :as opts}]
  (let [text       (scheme-text scheme)
        [l a]      (parse-tonic (tonic-of scheme opts))
        tonic-midi (+ (* 12 (inc octave)) (data/diatonic-pcs l) a)
        form       (vec (for [bar (str/split (str/trim text) #"\s*\|\s*")
                              :let [numerals (str/split bar #"\s+")
                                    share    (/ 1 (count numerals))
                                    strikes  (if (and hit (integer? (/ share hit))) (/ share hit) 1)]]
                          (vec (for [n numerals
                                     c (repeat strikes (chord l tonic-midi n))]
                                 (assoc c :duration (/ share strikes))))))]
    (cond-> form
      (and end (not= (tonic-chord text) (str/trim (peek (str/split text #"\|")))))
      (conj [(assoc (chord l tonic-midi (tonic-chord text)) :duration 1)]))))

(defn- section
  "bars as chord symbols, a bar line between bars."
  [bars last]
  (str/join " | " (mapv (fn [bar] (str/join " " (mapv #(chord-text % last) bar))) bars)))

(defn- volta-form
  "scheme's bars as repeat n [ ... ] volta [ [ ... ] [ ... ] ]: the bars
   before the last two played n times, the last two (the turnaround) as
   the first ending, two bars of the tonic, the last held, as the
   second."
  [scheme n opts last]
  (let [form  (bars scheme (assoc opts :end false))
        text  (scheme-text scheme)
        tonic (bars (tonic-chord text) (assoc opts :end false))
        held  (peek (bars (tonic-chord text) (assoc opts :end true :hit nil)))]
    (when (< (count form) 3)
      (throw (ex-info "a repeat needs at least three bars: a body and a two-bar turnaround" {:scheme scheme})))
    (let [body (section (drop-last 2 form) last)
          e1   (section (take-last 2 form) last)
          e2   (section (conj tonic held) last)]
      (str "repeat " n " [ " body " ] volta [ [ " e1 " ] [ " e2 " ] ]"))))

(defn ->mus
  "scheme (see bars) as musics text: `[name: !M:4/4 F3/1:7 | ... ]`,
   a bar line between bars, ready for musics.input.grammar-parser/parse. Options:
   :tonic (spelled as musics text spells it: \"B&\", \"F#\"; a chorus'
   own, else \"C\"),
   :octave of the tonic (3), :hit (nil: one chord per its share of a bar),
   :end (true: a closing bar on the tonic), :name (none: an unnamed
   line), :repeat n (none: once) the choruses as a repeat with volta
   endings (volta-form), the turnaround first, the tonic last."
  ([scheme] (->mus scheme {}))
  ([scheme {:keys [name repeat] :as opts}]
   (let [last (volatile! nil)
         opts (assoc opts :tonic (tonic-of scheme opts))]
     (str "[" (when name (str (clojure.core/name name) ": ")) "!M:4/4 "
          (if repeat (volta-form scheme repeat opts last) (section (bars scheme opts) last))
          " ]"))))
