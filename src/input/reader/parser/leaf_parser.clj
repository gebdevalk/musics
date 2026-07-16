;; leaf_parser.clj
;; Clojure port of the pymusics leaf-level parser.
;; Pitch parsing, pitch->MIDI resolution, articulation resolution,
;; dynamic mark resolution and duration expression evaluation.
;; No dependency on the lexer -- all regex patterns are self-contained.

(ns input.reader.parser.leaf-parser
  (:require [clojure.string :as str]
            [common.data.music-data :as data]))

;; ============================================================
;; Pitch parsing helpers (ported from regex.py parse_pitch)
;; ============================================================

(def ^:private PITCH_PARSE_RE
  #"([A-G]|[a-g])?([b#n]{0,2})([',]*|[1-8]/)")

(defn parse-pitch
  "Split a pitch string like 'C#4' or 'a#' into [name accidental octave]."
  [pitch-str]
  (when-let [m (re-matches PITCH_PARSE_RE pitch-str)]
    [(or (nth m 1) "") (nth m 2) (nth m 3)]))

(defn parse-pitches
  "Split chord content '<C E G>' into individual pitch tuples."
  [chord-content]
  (let [inner (str/replace chord-content #"^<|>$" "")]
    (keep parse-pitch (str/split inner #"\s+"))))

;; ============================================================
;; Pitch -> MIDI resolution
;; ============================================================

(def ^:private diatonic-pcs
  "Map note letter (lowercase) -> diatonic pitch class (C=0 through B=11)."
  {\c 0, \d 2, \e 4, \f 5, \g 7, \a 9, \b 11})

(def ^:private diatonic-degree
  "Note letter (lowercase) -> plain scale-degree 0..6 (c=0 .. b=6), with no
   pitch-class/semitone information at all. LilyPond's \\relative octave
   rule (see rel->midi below) compares these degrees, not resolved
   pitch classes, so an accidental on either note never skews where the
   next relative note lands."
  {\c 0, \d 1, \e 2, \f 3, \g 4, \a 5, \b 6})

(def ^:private pc->natural-letter
  "Best-effort pitch-class -> natural letter, spelling black keys as a
   sharp of the letter below (never wraps an octave). Only used to turn a
   bare starting MIDI int (no known spelling) into a {:letter :octave}
   ref -- see midi->ref."
  {0 \c, 1 \c, 2 \d, 3 \d, 4 \e, 5 \f, 6 \f, 7 \g, 8 \g, 9 \a, 10 \a, 11 \b})

(defn- accidental-semitones
  "Convert accidental string to semitone offset."
  [s]
  (case s
    ""   0
    "#"  1  "##"  2
    "b"  -1 "bb" -2
    "n"  0  "nn"  0
    0))

(def ^:private default-ref
  "Bootstrap reference point when no previous note exists yet -- matches
   LilyPond's own \\relative default and this DSL's old plain-60 default."
  {:letter \c :octave 4})

(defn- midi->ref
  "Best-effort {:letter :octave} for a plain MIDI int with no known
   spelling -- only needed at resolve-pitches-seq's public int-in
   boundary; the real walker chain threads the exact ref throughout via
   resolve-pitch's 2-arg form instead of ever reconstructing one."
  [midi]
  {:letter (get pc->natural-letter (mod midi 12))
   :octave (dec (quot midi 12))})

(defn- letter+octave->midi
  [letter accidental-str octave]
  (+ (get diatonic-pcs letter) (accidental-semitones accidental-str) (* (inc octave) 12)))

(defn- abs->midi
  "Absolute pitch: octave is given explicitly. Returns [midi ref]."
  [name-str accidental-str octave-str]
  (let [letter (Character/toLowerCase ^Character (first name-str))
        octave (Character/digit ^char (first octave-str) 10)]
    [(letter+octave->midi letter accidental-str octave)
     {:letter letter :octave octave}]))

(defn- rel->midi
  "Compute MIDI pitch (and the resulting {:letter :octave} ref, for
   chaining) for a relative note, following LilyPond's actual \\relative
   octave rule: fold the *letter* distance (0..6, ignoring accidentals on
   both notes) between this note and the last one into (-3,+3]
   scale-degree steps -- \"never more than a fourth\" -- to pick the
   octave, and only then apply this note's own accidental, as a semitone
   offset within whichever octave that letter-only comparison picked.
   ' / , ticks each shift a further full octave (7 diatonic steps)."
  [{:keys [letter octave]} name-str accidental-str octave-ticks]
  (let [this-letter (Character/toLowerCase ^Character (first name-str))
        this-degree (get diatonic-degree this-letter)
        last-degree (get diatonic-degree letter)
        raw-delta   (- this-degree last-degree)
        folded      (cond (> raw-delta 3)  (- raw-delta 7)
                           (< raw-delta -3) (+ raw-delta 7)
                           :else            raw-delta)
        oct-shift   (Math/floorDiv (+ last-degree folded) 7)
        ups         (count (filter #{\'} octave-ticks))
        downs       (count (filter #{\,} octave-ticks))
        new-octave  (+ octave oct-shift (- ups downs))]
    [(letter+octave->midi this-letter accidental-str new-octave)
     {:letter this-letter :octave new-octave}]))

(defn resolve-pitch
  "Resolve a parsed pitch tuple [name accidental octave-spec] to a MIDI
   note number. Absolute notation (uppercase + 'N/') resets the reference
   point. last-ref is the previous note's {:letter :octave} (no
   accidental baked in -- see rel->midi) and defaults to c4, like
   LilyPond's own \\relative entry point. Returns [midi new-last-ref]."
  ([tuple] (resolve-pitch tuple default-ref))
  ([[name accidental octave-spec] last-ref]
   (let [upper? (Character/isUpperCase (char (first name)))]
     (if upper?
       (abs->midi name accidental (if (seq octave-spec) octave-spec "4/"))
       (rel->midi (or last-ref default-ref) name accidental (or octave-spec ""))))))

(defn resolve-fixed-pitch
  "Resolve a pitch tuple as a literal pitch anchored at a single fixed
   octave (c4), with explicit ' / , ticks shifting a full octave each --
   no \\relative-style nearest-fourth folding, and no dependency on
   whatever note came before elsewhere. This is what \\transpose's
   from/to pitches want: LilyPond treats those as a fixed, context-free
   pitch pair (\\transpose c g is always a fifth up, never folded to a
   fourth down the way two consecutive \\relative notes would be), not
   as notes chained onto the piece's ongoing last-pitch state."
  [[name accidental octave-spec]]
  (let [letter (Character/toLowerCase ^Character (first name))
        spec   (or octave-spec "")]
    (if (re-find #"\d" spec)
      (letter+octave->midi letter accidental (Character/digit (first spec) 10))
      (let [ups   (count (filter #{\'} spec))
            downs (count (filter #{\,} spec))]
        (letter+octave->midi letter accidental (+ 4 (- ups downs)))))))

(defn resolve-pitches-seq
  "Resolve a seq of [name accidental ticks] tuples sequentially.
   Each successive pitch is relative to the previous.
   Returns [midis-vec final-last-ref]."
  [tuples last-midi]
  (reduce (fn [[midis ref] t]
            (let [[midi new-ref] (resolve-pitch t ref)]
              [(conj midis midi) new-ref]))
          [[] (midi->ref (or last-midi 60))]
          tuples))

;; ============================================================
;; Articulation resolution (ported from articulations.py Articulation.get)
;; ============================================================

(defn resolve-articulation
  "Resolve an articulation shorthand or name to a {:duration :dynamic} map.
   Accepts shorthand with or without dash (\"-^\" or \"^\") or full name
   (\"marcato\"), case-insensitive. Returns nil for nil input, the original
   string if unknown."
  [s]
  (when s
    (let [s-lower           (str/lower-case s)
          shorthand         (or (get data/articulation-shorthand s-lower)
                                (get data/articulation-shorthand (str "-" s-lower)))
          art-from-shorthand (when shorthand (get data/articulations shorthand))
          art-from-name      (get data/articulations (keyword s-lower))]
      (or art-from-shorthand art-from-name s))))

;; ============================================================
;; Dynamic mark resolution
;; ============================================================

(defn resolve-dynamic
  "Resolve a dynamic mark string to a MIDI velocity integer.
   Accepts standard dynamic marks (pp, mf, ff etc.) or a plain integer string.
   Returns nil if the input cannot be resolved."
  [s]
  (when s
    (or (get data/dynamics (keyword (str/lower-case s)))
        (try (Integer/parseInt s)
             (catch NumberFormatException _ nil)))))

;; ============================================================
;; Duration expression evaluation
;; ============================================================

(defn resolve-duration-expr
  "Evaluate a duration expression from a seq of atom values.
   Atoms are already parsed to numbers (Int -> integer, Ratio -> clojure ratio).
   The expression is a product: [16 4] -> 64, [3/2] -> 3/2, [4 3/2] -> 6.
   Used for timed ramp durations: !cresc<16*4:ff, !rit>3/2:60 etc."
  [atoms]
  (reduce * 1 atoms))

(defn parse-duration-atom
  "Parse a DurationAtom value string to a number.
   Handles integers ('16', '4') and ratios ('3/2', '16/1')."
  [s]
  (if (str/includes? s "/")
    (let [parts (str/split s #"/")]
      (/ (Integer/parseInt (first parts))
         (Integer/parseInt (second parts))))
    (Integer/parseInt s)))