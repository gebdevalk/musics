;; leaf_parser.clj
;; Clojure port of the pymusics leaf-level parser.
;; Pitch parsing, pitch->MIDI resolution, articulation resolution,
;; dynamic mark resolution and duration expression evaluation.
;; No dependency on the lexer -- all regex patterns are self-contained.

(ns input.reader.leaf-parser
  (:require [clojure.string :as str]
            [common.music-data :as data]
            [common.music-elements :as el]))

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

;; diatonic-pcs/diatonic-degree now live in common.music-data (shared
;; with music-elements' key-implied-accidental lookup) -- referenced
;; here as data/diatonic-pcs, data/diatonic-degree.

(def ^:private pc->natural-letter
  "Best-effort pitch-class -> natural letter, spelling black keys as a
   sharp of the letter below (never wraps an octave). Only used to turn a
   bare starting MIDI int (no known spelling) into a {:letter :octave}
   ref -- see midi->ref."
  {0 \c, 1 \c, 2 \d, 3 \d, 4 \e, 5 \f, 6 \f, 7 \g, 8 \g, 9 \a, 10 \a, 11 \b})

(defn- accidental-semitones
  "Convert accidental string to semitone offset. Accepts our own symbols
   (#, b, doubled, n) as well as LilyPond/Dutch (nederlands) suffixes --
   is/isis (sharp/double-sharp), es/eses (flat/double-flat), and the
   vowel-elided s/ses used after a and e (as, ases, es, eses) -- all
   resolving to the same semitone offset as their # / b equivalent."
  [s]
  (case s
    ""   0
    "#"  1  "##"  2
    "b"  -1 "bb" -2
    "n"  0  "nn"  0
    "is" 1  "isis" 2
    "es" -1 "eses" -2
    "s"  -1 "ses"  -2
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
  "accidental-str nil means no accidental was written at all -- look up
   ks's own implied offset for letter (0 under C major, or any
   non-7-note scale); a real string means an explicit accidental was
   written, which always wins outright regardless of ks (exactly like
   real notation: the symbol is the note's actual alteration, not an
   offset added on top of the key). ks is never optional/nilable here --
   every caller passes one (C major when they want literal/key-
   independent behavior, e.g. resolve-fixed-pitch below), so there's
   exactly one code path, not a separate key/no-key branch."
  [ks letter accidental-str octave]
  (+ (data/diatonic-pcs letter)
     (if accidental-str
       (accidental-semitones accidental-str)
       (el/key-letter-offset ks letter))
     (* (inc octave) 12)))

(defn- abs->midi
  "Absolute pitch: octave is given explicitly. Returns [midi ref]."
  [ks name-str accidental-str octave-str]
  (let [letter (Character/toLowerCase ^Character (first name-str))
        octave (Character/digit ^char (first octave-str) 10)]
    [(letter+octave->midi ks letter accidental-str octave)
     {:letter letter :octave octave}]))

(defn- rel->midi
  "Compute MIDI pitch (and the resulting {:letter :octave} ref, for
   chaining) for a relative note, following LilyPond's actual \\relative
   octave rule: fold the *letter* distance (0..6, ignoring accidentals on
   both notes) between this note and the last one into (-3,+3]
   scale-degree steps -- \"never more than a fourth\" -- to pick the
   octave, and only then apply this note's own accidental (or ks's
   implied one, if none was written), as a semitone offset within
   whichever octave that letter-only comparison picked.
   ' / , ticks each shift a further full octave (7 diatonic steps)."
  [ks {:keys [letter octave]} name-str accidental-str octave-ticks]
  (let [this-letter (Character/toLowerCase ^Character (first name-str))
        this-degree (data/diatonic-degree this-letter)
        last-degree (data/diatonic-degree letter)
        raw-delta   (- this-degree last-degree)
        folded      (cond (> raw-delta 3)  (- raw-delta 7)
                           (< raw-delta -3) (+ raw-delta 7)
                           :else            raw-delta)
        oct-shift   (Math/floorDiv (+ last-degree folded) 7)
        ups         (count (filter #{\'} octave-ticks))
        downs       (count (filter #{\,} octave-ticks))
        new-octave  (+ octave oct-shift (- ups downs))]
    [(letter+octave->midi ks this-letter accidental-str new-octave)
     {:letter this-letter :octave new-octave}]))

(defn resolve-pitch
  "Resolve a parsed pitch tuple [name accidental octave-spec] to a MIDI
   note number. Absolute notation (uppercase + 'N/') resets the reference
   point. last-ref is the previous note's {:letter :octave} (no
   accidental baked in -- see rel->midi) and defaults to c4, like
   LilyPond's own \\relative entry point. ks (the active Key, for
   resolving a note with no explicit accidental) defaults to C major
   when omitted -- the 1-/2-arg forms exist for callers that don't
   thread one at all (lilypond-import, direct leaf-parser-test calls),
   not as a separate no-key behavior; C major's own implied offset is
   just 0 for every letter, same as before this parameter existed.
   Returns [midi new-last-ref]."
  ([tuple] (resolve-pitch tuple default-ref (el/key :C :major)))
  ([tuple last-ref] (resolve-pitch tuple last-ref (el/key :C :major)))
  ([[name accidental octave-spec] last-ref ks]
   (let [upper? (Character/isUpperCase (char (first name)))]
     (if upper?
       (abs->midi ks name accidental (if (seq octave-spec) octave-spec "4/"))
       (rel->midi ks (or last-ref default-ref) name accidental (or octave-spec ""))))))

(defn resolve-fixed-pitch
  "Resolve a pitch tuple as a literal pitch anchored at a single fixed
   octave (c4), with explicit ' / , ticks shifting a full octave each --
   no \\relative-style nearest-fourth folding, and no dependency on
   whatever note came before elsewhere. This is what \\transpose's
   from/to pitches want: LilyPond treats those as a fixed, context-free
   pitch pair (\\transpose c g is always a fifth up, never folded to a
   fourth down the way two consecutive \\relative notes would be), not
   as notes chained onto the piece's ongoing last-pitch state.
   Deliberately always C major here, never whatever key happens to be
   active -- a transpose interval is a structural spec (\\transpose c d
   is always a whole tone), not a note being played, so it stays
   literal regardless of context, the same way LilyPond's \\transpose
   arguments do."
  [[name accidental octave-spec]]
  (let [letter (Character/toLowerCase ^Character (first name))
        spec   (or octave-spec "")
        ks     (el/key :C :major)]
    (if (re-find #"\d" spec)
      (letter+octave->midi ks letter accidental (Character/digit (first spec) 10))
      (let [ups   (count (filter #{\'} spec))
            downs (count (filter #{\,} spec))]
        (letter+octave->midi ks letter accidental (+ 4 (- ups downs)))))))

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