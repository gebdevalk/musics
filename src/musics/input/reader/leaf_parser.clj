;; leaf_parser.clj
;; Clojure port of the pymusics leaf-level parser.
;; Pitch parsing, pitch->MIDI resolution, articulation resolution,
;; dynamic mark resolution and duration expression evaluation.
;; No dependency on the lexer -- all regex patterns are self-contained.

(ns musics.input.reader.leaf-parser
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]))

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

;; diatonic-pcs/diatonic-degree now live in musics.common.music-data (shared
;; with music-elements' key-implied-accidental lookup) -- referenced
;; here as data/diatonic-pcs, data/diatonic-degree.

(def ^:private pc->natural-letter
  "Best-effort pitch-class -> natural letter, spelling black keys as a
   sharp of the letter below (never wraps an octave). Only used to turn a
   bare starting MIDI int (no known spelling) into a {:letter :octave}
   ref -- see midi->ref."
  {0 \c, 1 \c, 2 \d, 3 \d, 4 \e, 5 \f, 6 \f, 7 \g, 8 \g, 9 \a, 10 \a, 11 \b})

(def ^:private default-language
  "The LilyPond pitch-name language assumed when none is given --
   LilyPond's own default, nederlands (Dutch)."
  :nederlands)

(defn accidental-semitones
  "Convert an accidental string to a semitone offset. The symbols --
   # ## & && n, the only accidentals musics text has, plus b/bb/nn for
   programmatic callers -- mean the same in every language. Anything
   else is a LilyPond letter suffix (is/es/..., s/f/...), looked up in
   musics.common.music-data/accidental-tables under lang; only the LilyPond
   importer passes those."
  [lang s]
  (case s
    ""   0
    "#"  1  "##"  2
    "&"  -1 "&&" -2
    "b"  -1 "bb" -2
    "n"  0  "nn"  0
    (get (data/accidental-tables lang) s 0)))

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

(def ^:private black-key-pcs
  "Pitch classes pc->natural-letter spells as a sharp of the letter
   below (1/3/6/8/10 -- C#/D#/F#/G#/A#) -- the same five pitch classes
   midi->spelling below marks with an explicit accidental."
  #{1 3 6 8 10})

(defn midi->spelling
  "{:letter :accidental :octave} for a plain MIDI int, spelling every
   black key as a sharp of the letter below (same convention
   pc->natural-letter already encodes for midi->ref, just also
   surfacing the accidental midi->ref itself drops -- that fn only ever
   feeds a RELATIVE reference point, where the accidental doesn't
   matter, not an actual absolute spelling). Public (not -private, same
   reasoning accidental-semitones' own comment gives for its second
   caller): musics.input.midi-record needs to spell a recorded performance's
   literal, absolute pitches back out as musics text, which midi->ref's
   letter-only shape can't do on its own."
  [midi]
  (let [pc (mod midi 12)]
    {:letter      (get pc->natural-letter pc)
     :accidental  (if (contains? black-key-pcs pc) "#" "")
     :octave      (dec (quot midi 12))}))

;; ============================================================
;; Back to text -- a leaf as musics text writes it
;; ============================================================

(defn- dotted-candidates
  "Every (base dots) pair whose spelled-out value ([1/base, dotted]) could
   plausibly match ratio, base a power of two 1..128, dots 0..3."
  []
  (for [base [1 2 4 8 16 32 64 128] dots (range 0 4)]
    [base dots (* (/ 1 base) (- 2 (/ 1 (long (Math/pow 2 dots)))))]))

(defn duration->mus
  "ratio (a Clojure ratio, fraction of a whole note) -> a musics-DSL
   Duration string, optionally scaled by factor (a tuplet's own ratio,
   1 when none is active -- see musics.ebnf's own DurationRatio).
   When ratio alone matches a plain/dotted note value, that value is
   used as-is (\"4\", \"8.\", ...), with *factor appended only if
   factor isn't 1 (\"8*2/3\") -- keeps a tuplet note's own notated
   value intact and idiomatic. When ratio alone doesn't match any
   plain/dotted value, ratio and factor are combined into ONE *Ratio
   suffix on a whole note (duration \"1\") instead, since musics.ebnf's
   own DurationRatio allows only a single *Ratio per Duration, not two
   chained ones -- always correct, just less idiomatic for a genuinely
   irregular length."
  ([ratio] (duration->mus ratio 1))
  ([ratio factor]
   (if-let [[base dots] (some (fn [[b d v]] (when (= v ratio) [b d])) (dotted-candidates))]
     (let [base-str (str base (apply str (repeat dots ".")))]
       (if (= factor 1)
         base-str
         (str base-str "*" (numerator factor) "/" (denominator factor))))
     (let [combined (* ratio factor)]
       (str "1*" (numerator combined) "/" (denominator combined))))))

(defn pitch->mus
  "A MIDI int -> absolute musics pitch text (\"C#4/\"), always with the
   '/' after the octave digit: without it, a following duration digit
   reparses as part of a wrong octave (see musics.input.abc-import/
   note->pitch-text). musics text has octaves 1-8, MIDI 24-119."
  [midi]
  (let [{:keys [letter accidental octave]} (midi->spelling midi)]
    (str (str/upper-case letter) accidental octave "/")))

(def ^:private drum-accents
  "A drum's :dynamic -> its articulation (musics.common.music-data/articulations)."
  {5 "->" 10 "-^" -20 "\\ghost"})

(def ^:private articulation-spelling
  "[length-ratio dynamic] -> how a note's articulation is written: the
   shorthand where there is one (-> -. -^ ...), else \\name. Built from
   musics.common.music-data's own tables; legato reads as tenuto (both 1.0, 0)."
  (let [short (into {} (for [[sh k] data/articulation-shorthand] [k sh]))
        named #{:staccato :staccatissimo :tenuto :marcato :portato :accent :ghost}]
    (into {} (for [[k {:keys [duration dynamic]}] data/articulations
                   :let [spelled (or (short k) (when (named k) (str "\\" (name k))))]
                   :when spelled]
               [[duration dynamic] spelled]))))

(defn- value->mus
  "A context value as a \\name:value Modifier writes it."
  [v]
  (cond (string? v)  (str "\"" v "\"")
        (keyword? v) (name v)
        (ratio? v)   (str (numerator v) "/" (denominator v))
        :else        (str v)))

(defn- overrides->mus
  "A note's :overrides as \\name:value Modifiers (c4\\volume:90)."
  [overrides]
  (apply str (for [[k v] (sort-by key overrides)] (str "\\" (name k) ":" (value->mus v)))))

(defn part->mus
  "A Leaf/Rest/Drum as musics text: absolute pitch, explicit duration --
   c4 as \"C4/4\", a chord as \"<C4/ E4/ G4/>4\", \"r8\", a drum as
   \"x4\\36\" (accented \"x4\\36->\"), a tied note ending in \"~\".
   A note's articulation is written as its shorthand (\"C4/4->\") and its
   overrides as Modifiers (\"C4/4\\volume:90\"), so the text reads back
   the same. nil for anything else."
  [part]
  (when (#{:REST :DRUM :LEAF} (:type part))
    (let [dur (let [r (rationalize (:duration part))]
                (if (and (integer? r) (> r 1)) (str "1*" r "/1") (duration->mus r)))]
      (case (:type part)
        :REST (str "r" dur)
        :DRUM (str "x" dur "\\" (:program part)
                   (some-> (:dynamic part) long drum-accents)
                   (overrides->mus (:overrides part)))
        :LEAF (let [ps (:pitches part)]
                (str (if (= 1 (count ps))
                       (str (pitch->mus (first ps)) dur)
                       (str "<" (str/join " " (map pitch->mus ps)) ">" dur))
                     (get articulation-spelling [(:articulation part) (or (:dynamic part) 0)])
                     (overrides->mus (:overrides part))
                     (when (:tied part) "~")))))))

(defn- letter+octave->midi
  "accidental-str nil means no accidental was written at all -- look up
   ks's own implied offset for letter (0 under C major, or any
   non-7-note scale); a real string means an explicit accidental was
   written, which always wins outright regardless of ks (exactly like
   real notation: the symbol is the note's actual alteration, not an
   offset added on top of the key). ks is never optional/nilable here --
   every caller passes one (C major when they want literal/key-
   independent behavior, e.g. resolve-fixed-pitch below), so there's
   exactly one code path, not a separate key/no-key branch. lang is
   only consulted when accidental-str is non-nil (an implied offset
   never needs a language at all, since it comes from ks/key-letter-
   offset, not from anything written in the source text)."
  [ks lang letter accidental-str octave]
  (+ (data/diatonic-pcs letter)
     (if accidental-str
       (accidental-semitones lang accidental-str)
       (el/key-letter-offset ks letter))
     (* (inc octave) 12)))

(defn- abs->midi
  "Absolute pitch: octave is given explicitly. Returns [midi ref]."
  [ks lang name-str accidental-str octave-str]
  (let [letter (Character/toLowerCase ^Character (first name-str))
        octave (Character/digit ^char (first octave-str) 10)]
    [(letter+octave->midi ks lang letter accidental-str octave)
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
  [ks lang {:keys [letter octave]} name-str accidental-str octave-ticks]
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
    [(letter+octave->midi ks lang this-letter accidental-str new-octave)
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
   lang (the LilyPond pitch-name language for a letter-suffix accidental
   -- see accidental-semitones) defaults to :nederlands; musics text
   never needs it.
   Returns [midi new-last-ref]."
  ([tuple] (resolve-pitch tuple default-ref (el/key :C :major) default-language))
  ([tuple last-ref] (resolve-pitch tuple last-ref (el/key :C :major) default-language))
  ([tuple last-ref ks] (resolve-pitch tuple last-ref ks default-language))
  ([[name accidental octave-spec] last-ref ks lang]
   (let [upper? (Character/isUpperCase (char (first name)))]
     (if upper?
       (abs->midi ks lang name accidental (if (seq octave-spec) octave-spec "4/"))
       (rel->midi ks lang (or last-ref default-ref) name accidental (or octave-spec ""))))))

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
      (letter+octave->midi ks default-language letter accidental (Character/digit (first spec) 10))
      (let [ups   (count (filter #{\'} spec))
            downs (count (filter #{\,} spec))]
        (letter+octave->midi ks default-language letter accidental (+ 4 (- ups downs)))))))

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