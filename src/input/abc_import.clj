(ns input.abc-import
  "Best-effort ABC notation -> musics DSL text converter -- the sibling
   of input.lilypond-import, but for ABC (a compact, plain-text format
   for folk/traditional tunes) instead of LilyPond. Structurally the same
   idea (read a real notation format, emit real musics-DSL text), a
   simpler implementation than lilypond_import.clj because ABC's own
   grammar is flatter: no `{ }`/`<< >>` nesting to track, no \\relative
   pitch mode, no user-definable commands -- a tune is a header (a run of
   `Field:value` lines) followed by one flat stream of body tokens.

   IN SCOPE: header fields X/T/C/M/L/Q/K (meter, unit note length, tempo,
   key -- including the 7 standard modes, not just major/minor); notes
   with accidentals/octave marks/length modifiers; rests (z); chords
   ([CEG]); ties (-); slurs ( ); simple tuplets ((2/(3/(4/(6); broken
   rhythm (>/<); bar lines (repeat marks included, but see OUT OF SCOPE);
   multiple tunes in one file (each X: starts a new one, all wrapped in
   one outer Sequence, same shape lilypond_import.clj already uses for
   multiple \\scores).

   OUT OF SCOPE (dropped silently, not a bug -- matches lilypond_import's
   own \"known gaps documented, not silently mishandled\" precedent):
   lyrics (w: lines), guitar-chord annotations (\"Cmaj7\"), grace notes
   ({...}), decorations (!fermata!, .~HLMOPSTuv single-char marks),
   multiple voices (V: fields -- only the first/only voice in a tune is
   converted), variant endings ([1 [2), macros (U:), %%formatting
   directives, repeat-bar PLAYBACK semantics (:|/|: convert to plain bar
   markers, written out once -- the engine never loops back).

   PITCH SPELLING: absolute (uppercase letter + explicit octave digit),
   not the relative (lowercase, nearest-fourth/fifth) spelling
   lilypond_import.clj favours per this project's own guideline #7 --
   a deliberate scope choice, not an oversight: ABC's own note spelling
   is already fully absolute (every octave mark is relative to a fixed
   reference, unlike LilyPond's own un-marked relative-by-default
   convention), so mapping straight to absolute sidesteps an entire class
   of relative-pitch-tracking bugs for a first version. A later pass
   could add relative respelling to match lilypond_import.clj's own
   output style if that's ever wanted."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ============================================================
;; Key signatures -- implied accidentals per mode, computed from
;; common.music-elements' own scale-steps table rather than a second,
;; hand-copied circle-of-fifths table.
;; ============================================================

(def ^:private letters ["C" "D" "E" "F" "G" "A" "B"])
(def ^:private natural-pc {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

;; Major-scale degree offsets (semitones from tonic), indexed by mode --
;; ionian=0 (unison) through locrian=6 (7th degree, 11 semitones). Same
;; numbers as (reductions + 0 (:major common.music-elements/scale-steps)),
;; not re-derived independently -- these ARE that table, just inlined so
;; this ns doesn't need to require common.music-elements only for one
;; lookup.
(def ^:private mode-degree-offset
  {:ionian 0 :major 0 :dorian 2 :phrygian 4 :lydian 5
   :mixolydian 7 :aeolian 9 :minor 9 :locrian 11})

(defn- relative-major-pc
  "The pitch class (0-11) of tonic-pc's own relative major, given mode --
   e.g. D dorian (pc 2) -> C major (pc 0). Every mode's key signature
   equals its relative major's key signature."
  [tonic-pc mode]
  (mod (- tonic-pc (get mode-degree-offset mode 0)) 12))

;; The 15 real major key signatures, keyed by pitch class -- ambiguous
;; slots (pc 1/6/11, each reachable as either a sharp or a flat key) list
;; BOTH; key-signature picks the right one from the tonic's own written
;; accidental (K:Db vs K:C#), defaulting to sharp when the source gave no
;; accidental to disambiguate (the far more common real-world case).
;; Built from the two fixed circle-of-fifths add-orders (sharps add F C G
;; D A E B in that order; flats add B E A D G C F) rather than hand-typed
;; per key -- an earlier hand-typed version of this table had three real
;; transcription errors (Gb missing its own C, Db carrying an extra one,
;; Cb missing its own F), caught only by actually running K:Db/K:Gb/K:Cb
;; through key-signature and checking every one of the 7 letters, not by
;; spot-checking a couple of the more common keys.
(def ^:private sharp-add-order ["F" "C" "G" "D" "A" "E" "B"])
(def ^:private flat-add-order  ["B" "E" "A" "D" "G" "C" "F"])
(def ^:private sharp-count-by-pc {0 0, 7 1, 2 2, 9 3, 4 4, 11 5, 6 6, 1 7})
(def ^:private flat-count-by-pc  {0 0, 5 1, 10 2, 3 3, 8 4, 1 5, 6 6, 11 7})
(def ^:private major-key-signatures
  (into {}
        (map (fn [pc]
               [pc {:sharps (vec (take (get sharp-count-by-pc pc 0) sharp-add-order))
                    :flats  (vec (take (get flat-count-by-pc pc 0) flat-add-order))}]))
        (range 12)))

(defn key-signature
  "{letter -> implied-accidental \"#\"/\"&\"/nil} for tonic-letter (a bare
   capital A-G) + tonic-accidental (\"#\"/\"b\"/nil, the tonic's OWN
   written accidental, ABC's own spelling, not one of the 7 scale
   degrees') + mode (a keyword, see mode-degree-offset). The RETURNED
   sign is GUIDO's own & for flat, not ABC's b -- this map feeds
   directly into note->pitch-text's own emitted Pitch text, which must
   match musics.ebnf's own Accidental rule (GUIDO-only now)."
  [tonic-letter tonic-accidental mode]
  (let [tonic-pc  (mod (+ (get natural-pc tonic-letter)
                          (case tonic-accidental "#" 1 "b" -1 0))
                       12)
        maj-pc    (relative-major-pc tonic-pc mode)
        sig       (get major-key-signatures maj-pc {:sharps [] :flats []})
        ambiguous? (and (seq (:sharps sig)) (seq (:flats sig)))
        ;; Ambiguous slots (both directions populated -- pc 1/6/11) pick
        ;; flats only when the tonic itself was WRITTEN flat (K:Db,
        ;; K:Gb); sharp by default otherwise. An UNAMBIGUOUS slot (every
        ;; other key -- exactly one direction ever populated there, the
        ;; other always []) just uses whichever direction actually has
        ;; entries, regardless of the tonic's own written accidental --
        ;; real bug, found only by running Eb major through this and
        ;; getting zero accidentals back: the OLD version here also
        ;; required :sharps to be non-empty before ever picking :flats,
        ;; which is exactly backwards for a genuinely flat, unambiguous
        ;; key (:sharps is ALWAYS [] there).
        use-flats? (if ambiguous? (= tonic-accidental "b") (seq (:flats sig)))
        accs      (if use-flats? (:flats sig) (:sharps sig))
        sign      (if use-flats? "&" "#")]
    (into {} (map (fn [l] [l (when ((set accs) l) sign)]) letters))))

;; ============================================================
;; Duration: ABC expresses a note's length as (unit-length * multiplier),
;; both plain fractions of a whole note -- convert the PRODUCT into a
;; musics-DSL Duration digit (1/n, optionally dotted) when it cleanly is
;; one, else fall back to a whole note (duration "1") scaled by an
;; explicit *Ratio suffix -- see ns docstring's own "always correct,
;; occasionally verbose" note.
;; ============================================================

(defn unit-length
  "ABC's own default-unit-length rule when no L: field is given: 1/16 if
   the meter's own value (num/den) is under 3/4, else 1/8 -- the ABC 2.1
   standard's own rule, not a simplification. meter is [num den] or nil
   (M:none/no M: at all defaults same as a slow meter -- 1/8)."
  [meter]
  (if (and meter (< (/ (double (first meter)) (second meter)) 0.75))
    1/16
    1/8))

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
   value intact and idiomatic, exactly mirroring how ABC itself writes
   it (the triplet marker scales notated eighth notes, not some
   already-computed irregular fraction). When ratio alone doesn't match
   any plain/dotted value, ratio and factor are combined into ONE
   *Ratio suffix on a whole note (duration \"1\") instead, since
   musics.ebnf's own DurationRatio allows only a single *Ratio per
   Duration, not two chained ones -- always correct, just less
   idiomatic for a genuinely irregular length (rare in real tunes)."
  ([ratio] (duration->mus ratio 1))
  ([ratio factor]
   (if-let [[base dots] (some (fn [[b d v]] (when (= v ratio) [b d])) (dotted-candidates))]
     (let [base-str (str base (apply str (repeat dots ".")))]
       (if (= factor 1)
         base-str
         (str base-str "*" (numerator factor) "/" (denominator factor))))
     (let [combined (* ratio factor)]
       (str "1*" (numerator combined) "/" (denominator combined))))))

;; ============================================================
;; Header parsing
;; ============================================================

(defn- parse-meter [v]
  (cond
    (= v "C")  [4 4]
    (= v "C|") [2 2]
    (= v "none") nil
    :else (let [[n d] (str/split (str/trim v) #"/")]
            [(Long/parseLong n) (Long/parseLong d)])))

(defn- parse-unit-length [v]
  (let [[n d] (str/split (str/trim v) #"/")]
    (/ (Long/parseLong n) (Long/parseLong d))))

(defn- parse-tempo
  "Q: field -> [duration-str bpm] for a !tempo:duration-str=bpm
   instruction, or [nil bpm] for a bare BPM. Drops any leading/trailing
   \"name\" text some Q: fields carry (Q:\"Allegro\" 1/4=120) -- only the
   numeric part is meaningful for playback."
  [v]
  (let [v (str/trim (str/replace v #"\"[^\"]*\"" ""))]
    (if-let [[_ n d bpm] (re-find #"(\d+)/(\d+)\s*=\s*(\d+)" v)]
      (let [ratio (/ (Long/parseLong n) (Long/parseLong d))]
        [(if (= (numerator ratio) 1) (str (denominator ratio)) (str n "/" d))
         (Long/parseLong bpm)])
      (when-let [[_ bpm] (re-find #"(\d+)" v)]
        [nil (Long/parseLong bpm)]))))

(def ^:private mode-aliases
  {"maj" :major "ion" :ionian "min" :minor "m" :minor "aeo" :aeolian
   "dor" :dorian "phr" :phrygian "lyd" :lydian "mix" :mixolydian "loc" :locrian})

(defn parse-key
  "K:tonic[mode] -> {:letter :accidental :mode}, e.g. \"K:Dmix\" ->
   {:letter \"D\" :accidental nil :mode :mixolydian}, \"K:C\" -> major
   (ABC's own default mode when none is written). Any trailing explicit
   extra-accidental list (K:D Phr ^f) is dropped -- rare, and this
   converter already computes implied accidentals from the mode itself."
  [v]
  (let [v (str/trim v)
        [_ letter acc rest] (re-find #"(?i)^([A-G])(#|b)?\s*(.*)$" v)
        mode-word (first (str/split (str/trim rest) #"\s+"))
        mode-key  (some-> mode-word str/lower-case (subs 0 (min 3 (count mode-word))))]
    {:letter (str/upper-case letter)
     :accidental acc
     :mode (get mode-aliases mode-key :major)}))

(defn- parse-header-lines
  "Consume header field lines (X:/T:/etc, one per line, up to and
   including K: which always ends the header) from the start of lines.
   Returns [header-map remaining-lines]."
  [lines]
  (loop [lines lines header {}]
    (if (empty? lines)
      [header lines]
      (let [line (first lines)]
        (if-let [[_ field v] (re-find #"^([A-Za-z]):\s?(.*)$" line)]
          (let [header (update header (keyword (str/lower-case field))
                                (fnil conj []) (str/trimr v))]
            (if (= field "K")
              [header (rest lines)]
              (recur (rest lines) header)))
          [header lines])))))

;; ============================================================
;; Body tokenizing -- a flat left-to-right scan, no nesting to track
;; (chords/tuplets/slurs are all single-level in real ABC usage).
;; ============================================================

(def ^:private note-re
  #"^(\^\^|__|\^|_|=)?([A-Ga-g])([,']*)((?:[0-9]+)?(?:/[0-9]*)*)")

;; Same pattern, unanchored -- for re-seq (scanning a whole string for
;; ALL matches, e.g. a chord's own inner pitch list) rather than re-find
;; against the START of a remaining substring. re-seq against an
;; anchored (^...) pattern only ever finds ONE match total: Java's find()
;; re-tries from later offsets on each call, where ^ (start-of-INPUT, no
;; MULTILINE flag here) can never match again -- confirmed live, this
;; silently dropped every pitch but a chord's first.
(def ^:private note-re-unanchored
  #"(\^\^|__|\^|_|=)?([A-Ga-g])([,']*)((?:[0-9]+)?(?:/[0-9]*)*)")

(defn- parse-note-length
  "The trailing length-modifier text of a note/rest (\"\", \"2\", \"/2\",
   \"3/2\", \"//\", ...) -> a multiplier ratio onto the unit length.
   \"/\" alone means /2; each further \"/\" halves again (\"//\" = /4)."
  [s]
  (cond
    (str/blank? s) 1
    :else
    (let [[_ whole slashes _denom] (re-find #"^([0-9]*)((?:/[0-9]*)*)$" s)
          whole  (if (str/blank? whole) 1 (Long/parseLong whole))
          slash-parts (rest (str/split slashes #"(?=/)"))]
      (if (empty? slash-parts)
        whole
        ;; One or more "/[n]?" groups multiply together -- "/" alone is
        ;; /2, "/3" is /3, "//" is /2/2 = /4.
        (reduce (fn [acc part]
                  (let [n (re-find #"[0-9]+" part)]
                    (/ acc (if n (Long/parseLong n) 2))))
                whole slash-parts)))))

(defn tokenize-body
  "body-text (already stripped of header lines, comments joined across
   any \\-continued lines) -> a seq of tokens: {:type :note/:rest/:chord/
   :bar/:tuplet/:tie/:slur-open/:slur-close/:broken, ...fields}. Anything
   this converter doesn't handle (grace notes, decorations, guitar
   chords, lyrics lines) is consumed and dropped right here, not left for
   a later pass to trip over."
  [body-text]
  (loop [s body-text out []]
    (cond
      (str/blank? s) out

      ;; comment to end of line
      (str/starts-with? s "%")
      (recur (str/replace-first s #"^[^\n]*" "") out)

      ;; a lyrics line -- drop the whole line
      (re-find #"^w:" s)
      (recur (str/replace-first s #"^[^\n]*" "") out)

      ;; inline field [X:...] (e.g. mid-tune meter/key change) -- only
      ;; M:/L:/K: are meaningful here; parsed the same way as a header
      ;; field and emitted as a :field token for the caller to act on.
      (re-find #"^\[[A-Za-z]:" s)
      (let [[whole field v] (re-find #"^\[([A-Za-z]):([^\]]*)\]" s)]
        (recur (subs s (count whole)) (conj out {:type :field :field field :value (str/trim v)})))

      ;; guitar chord annotation -- drop
      (str/starts-with? s "\"")
      (let [[whole] (re-find #"^\"[^\"]*\"" s)]
        (recur (subs s (count (or whole "\""))) out))

      ;; decoration in !...! -- drop
      (str/starts-with? s "!")
      (let [[whole] (re-find #"^![^!]*!" s)]
        (recur (subs s (count (or whole "!"))) out))

      ;; grace notes {...} -- drop entirely (content not carried over)
      (str/starts-with? s "{")
      (let [[whole] (re-find #"^\{[^}]*\}" s)]
        (recur (subs s (count (or whole "{"))) out))

      ;; tuplet marker (n, e.g. (3 -- must be checked before slur-open,
      ;; since both start with a bare "("
      (re-find #"^\(\d" s)
      (let [[whole n] (re-find #"^\((\d)" s)]
        (recur (subs s (count whole)) (conj out {:type :tuplet :n (Long/parseLong n)})))

      (str/starts-with? s "(")
      (recur (subs s 1) (conj out {:type :slur-open}))

      (str/starts-with? s ")")
      (recur (subs s 1) (conj out {:type :slur-close}))

      ;; chord [CEG]length
      (re-find #"^\[" s)
      (let [[whole inner] (re-find #"^\[([^\]]*)\]" s)
            after (subs s (count whole))
            [_ len] (re-find #"^((?:[0-9]+)?(?:/[0-9]*)*)" after)
            notes (re-seq note-re-unanchored inner)]
        (recur (subs after (count len))
               (conj out {:type :chord :notes notes :length (parse-note-length len)})))

      ;; bar lines, longest match first
      (re-find #"^(\|\]|\|:|:\||::|\|\|)" s)
      (let [[whole] (re-find #"^(\|\]|\|:|:\||::|\|\|)" s)]
        (recur (subs s (count whole)) (conj out {:type :bar :strength 2})))

      (str/starts-with? s "|")
      (recur (subs s 1) (conj out {:type :bar :strength 1}))

      ;; broken rhythm
      (re-find #"^[><]+" s)
      (let [[whole] (re-find #"^[><]+" s)]
        (recur (subs s (count whole)) (conj out {:type :broken :dir (subs whole 0 1) :n (count whole)})))

      (str/starts-with? s "-")
      (recur (subs s 1) (conj out {:type :tie}))

      ;; rest -- z/Z/x, optional length
      (re-find #"^[zZx]" s)
      (let [[whole _letter len] (re-find #"^([zZx])((?:[0-9]+)?(?:/[0-9]*)*)" s)]
        (recur (subs s (count whole)) (conj out {:type :rest :length (parse-note-length len)})))

      ;; note
      (re-find note-re s)
      (let [[whole acc letter ticks len] (re-find note-re s)]
        (recur (subs s (count whole))
               (conj out {:type :note :acc acc :letter letter :ticks ticks
                          :length (parse-note-length len)})))

      ;; whitespace/line breaks/anything unrecognized -- skip one char
      :else
      (recur (subs s 1) out))))

;; ============================================================
;; Pitch spelling
;; ============================================================

(def ^:private abc-acc->sign {"^^" "##" "__" "&&" "^" "#" "_" "&" "=" "n" nil nil})

(defn note->pitch-text
  "One note token -> musics-DSL absolute Pitch text (letter + accidental?
   + octave digit), applying key-sig's own implied accidental when the
   note carries none of its own. ABC octave convention: bare lowercase
   c/d/e/f/g/a/b sits in the octave containing MIDI 60 (middle C) -- this
   project's own C4 (see CLAUDE.md's \"this DSL's own C1\" note, (inc
   octave)*12 with octave=1 -- C4 lands on MIDI 60 the same way). Bare
   uppercase is one octave below that (C3); each ' raises an octave, each
   , lowers one, counted on whichever case was actually written.
   The trailing '/' after the octave digit is NOT optional here, unlike
   musics.ebnf's own OctaveAbs comment ('C4 alone... the slash isn't
   needed at all and may be omitted') -- that omission is only safe
   when nothing digit-shaped immediately follows the octave, and this
   fn's own caller always appends an explicit Duration digit right
   after this text, every time (see this ns's own header comment on
   why duration is never elided). Without the '/', OctaveAbs's own
   regex ([1-8](?:/|(?!\\d))) fails to match a bare octave digit
   immediately followed by another digit, and the whole thing silently
   reparses as no-octave (defaulting to 4) plus a wrong, mashed-
   together Duration instead -- confirmed live, not hypothetical:
   'C38' (meant as octave 3, duration 8) actually parsed as octave 4,
   duration 1/38, with no parse error to catch it."
  [{:keys [acc letter ticks]} key-sig]
  (let [upper   (str/upper-case letter)
        base-oct (if (= letter upper) 3 4)
        octave  (+ base-oct
                   (count (filter #(= % \') ticks))
                   (- (count (filter #(= % \,) ticks))))
        sign    (if acc (get abc-acc->sign acc) (get key-sig upper))]
    (str upper sign octave "/")))

;; ============================================================
;; Emit -- one tune's own token stream -> musics-DSL body text
;; ============================================================

(defn- emit-note
  ([tok unit key-sig] (emit-note tok unit key-sig 1))
  ([{:keys [length] :as tok} unit key-sig factor]
   (str (note->pitch-text tok key-sig) (duration->mus (* unit length) factor))))

(defn- emit-rest
  ([tok unit] (emit-rest tok unit 1))
  ([{:keys [length]} unit factor]
   (str "r" (duration->mus (* unit length) factor))))

(defn- emit-chord
  ([tok unit key-sig] (emit-chord tok unit key-sig 1))
  ([{:keys [notes length]} unit key-sig factor]
   (let [pitches (map (fn [[_ acc letter ticks]]
                         (note->pitch-text {:acc acc :letter letter :ticks ticks} key-sig))
                       notes)]
     (str "<" (str/join " " pitches) ">" (duration->mus (* unit length) factor)))))

;; Broken rhythm (>/<) adjusts the length of the PAIR of notes/chords/
;; rests straddling it -- applied as a pre-pass over the raw token seq
;; (before duration text is emitted), since it needs to see and rewrite
;; the neighbours on both sides.
(defn- apply-broken-rhythm
  [tokens]
  (loop [[a b c & more] tokens out []]
    (cond
      (nil? a) out
      (and b (= (:type b) :broken) c (#{:note :rest :chord} (:type a)) (#{:note :rest :chord} (:type c)))
      (let [factor (case (:n b) 1 [3/2 1/2] [7/4 1/4])
            [fa fc] (if (= (:dir b) ">") factor (reverse factor))]
        (recur more (into out [(update a :length * fa) (update c :length * fc)])))
      :else
      (recur (cons b (cons c more)) (conj out a)))))

(defn- glue-suffix
  "out with suffix-fn applied to the LAST emitted unit's own text -- same
   'note-suffix, not standalone' shape musics-DSL's own grammar requires
   for a tie or a CLOSING slur mark, both of which land on the note
   already emitted. An OPENING slur mark is different -- in ABC, '('
   precedes the note it attaches to in the source stream, but in
   musics-DSL it's a trailing suffix on that SAME note's own text -- so
   :slur-open can't glue onto the last emitted unit (wrong note, or
   none yet); see open-suffix, applied when the NEXT note/chord/rest is
   actually emitted instead. A tuplet's own notes need no special
   handling here anymore -- each one already carries its own *Ratio
   scaling baked directly into its emitted text (see emit-note/emit-
   chord/emit-rest), so every emitted unit lands straight in out, tuplet
   or not, same as the old dedicated buffer used to require."
  [out suffix-fn]
  (if (seq out)
    (update out (dec (count out)) suffix-fn)
    out))

(defn- open-suffix
  "text with a pending opening slur mark appended (still a TRAILING
   suffix on this note's own text, same position a closing mark or tie
   would take -- '(' just happens to visually precede the note in ABC's
   own source order, not in musics-DSL's)."
  [text pending-open?]
  (if pending-open? (str text "(") text))

(defn tokens->mus-body
  "tokens (from tokenize-body) -> musics-DSL body text, unit/key-sig
   fixed for the whole tune (mid-tune [M:.../[K:...] fields update them
   for everything after). tuplet is {:remaining :factor} or nil -- a
   tuplet's own ratio is baked directly into each of its own n notes'
   text as they're emitted (see emit-note/emit-chord/emit-rest's own
   factor argument), not collected into a separate buffer and wrapped
   afterward the way a Lisp-call (times ...) command once needed --
   musics.ebnf's own *Ratio duration suffix scales one note at a time,
   so there's nothing left to buffer or wrap: every emitted unit lands
   straight in out, tuplet or not."
  [tokens unit0 key-sig0]
  (loop [tokens (apply-broken-rhythm tokens) out [] unit unit0 key-sig key-sig0 tuplet nil open? false]
    (if (empty? tokens)
      (str/join " " out)
      (let [tok (first tokens) more (rest tokens)
            factor (if tuplet (:factor tuplet) 1)]
        (case (:type tok)
          :field
          (case (:field tok)
            "L" (recur more out (parse-unit-length (:value tok)) key-sig tuplet open?)
            "K" (let [{:keys [letter accidental mode]} (parse-key (:value tok))]
                  (recur more out unit (key-signature letter accidental mode) tuplet open?))
            (recur more out unit key-sig tuplet open?))

          :tuplet
          ;; (n -> the next n notes play in the time of q -- standard ABC
          ;; defaults: 2->3, 3->2, 4->3, 6->2; anything else falls back
          ;; to 3:2, the single most common ratio in real tunes. A
          ;; tuplet marker met while ANOTHER one is still open (nested
          ;; tuplets, rare and not real ABC anyway) just replaces it,
          ;; best-effort.
          (let [q (get {2 3 3 2 4 3 6 2} (:n tok) 2)]
            (recur more out unit key-sig {:remaining (:n tok) :factor (/ q (:n tok))} open?))

          :note
          (let [text (open-suffix (emit-note tok unit key-sig factor) open?)
                tuplet' (when tuplet
                          (let [r (dec (:remaining tuplet))]
                            (when (pos? r) (assoc tuplet :remaining r))))]
            (recur more (conj out text) unit key-sig tuplet' false))

          :chord (recur more (conj out (open-suffix (emit-chord tok unit key-sig factor) open?))
                        unit key-sig tuplet false)
          :rest  (recur more (conj out (open-suffix (emit-rest tok unit factor) open?))
                        unit key-sig tuplet false)
          :bar   (recur more (conj out (apply str (repeat (:strength tok) "|")))
                        unit key-sig tuplet open?)

          :tie
          (recur more (glue-suffix out #(str % "~")) unit key-sig tuplet open?)
          :slur-open
          (recur more out unit key-sig tuplet true)
          :slur-close
          (recur more (glue-suffix out #(str % ")")) unit key-sig tuplet open?)

          (recur more out unit key-sig tuplet open?))))))

;; ============================================================
;; Tune assembly -- header fields -> !Meter:/!tempo:/!key: assignments,
;; body tokens -> the note stream, both wrapped into one named Sequence.
;; ============================================================

(defn- sanitize-id
  "title (an X:'s own T: field, or nil) -> a lowercase, underscore-only Id
   fragment safe to append after tuneN_ -- non-alphanumeric collapses to
   a single _, leading/trailing _ trimmed, capped at 30 chars so a long
   title doesn't produce an unwieldy id. Empty/nil title -> \"\"."
  [title]
  (if (str/blank? title)
    ""
    (-> title
        str/lower-case
        (str/replace #"[^a-z0-9]+" "_")
        (str/replace #"^_+|_+$" "")
        (as-> s (subs s 0 (min 30 (count s)))))))

(defn- header-field [header k] (first (get header k)))

(defn tune->mus
  "One tune's own [header body-text] -> its musics-DSL text, as a single
   named Sequence (\"tuneN[_title]: [ ... ]\"), N being this tune's own
   X: reference number so multiple tunes in one file never collide.
   header is the {:x [...] :t [...] ...} map parse-header-lines returns;
   body-text is everything after the header (up to the next X: or EOF)."
  [header body-text]
  (let [x         (or (header-field header :x) "1")
        title     (header-field header :t)
        id        (str "tune" x (when-not (str/blank? (sanitize-id title)) (str "_" (sanitize-id title))))
        meter     (some-> (header-field header :m) parse-meter)
        unit      (if-let [l (header-field header :l)] (parse-unit-length l) (unit-length meter))
        {:keys [letter accidental mode]} (parse-key (or (header-field header :k) "C"))
        key-sig   (key-signature letter accidental mode)
        [tempo-dur tempo-bpm] (some-> (header-field header :q) parse-tempo)
        tokens    (tokenize-body body-text)
        body      (tokens->mus-body tokens unit key-sig)
        key-text  (str letter accidental (when mode (str "." (name mode))))]
    (str "[" id ": "
         (when meter (str "!Meter:" (first meter) "/" (second meter) " "))
         (when tempo-bpm (str "!tempo:" (when tempo-dur (str tempo-dur "=")) tempo-bpm " "))
         "!key:" key-text " "
         body
         "]")))

(defn- split-tunes
  "raw ABC text -> a seq of tune chunks, each starting at its own X: line
   -- any text before the first X: (file-level %%directives, comments)
   is dropped, same \"not carried over\" treatment as every other
   OUT OF SCOPE construct this converter drops."
  [text]
  (let [lines (str/split-lines text)
        starts (keep-indexed (fn [i l] (when (re-find #"^X:" l) i)) lines)]
    (map (fn [start end] (str/join "\n" (subvec (vec lines) start end)))
         starts
         (concat (rest starts) [(count lines)]))))

(defn abc-text->mus-text
  "Convert ABC notation text (one or more tunes, a whole tunebook or a
   single tune) to musics DSL surface text (best effort) -- see this ns's
   own docstring for exactly what's handled. Every tune becomes its own
   named Sequence (tuneN[_title]:), all wrapped inside one outer
   Sequence, same shape lilypond_import.clj's own ly-text->mus-text uses
   for a multi-\\score .ly file -- keeps every tune individually
   addressable by its own id (the flat repo addresses by id, not by
   nesting depth) while still needing only one (parse ...) call.
   !accidentals:explicit is set once, ahead of everything, same reason
   lilypond_import.clj sets it: ABC's own input pitches are already
   always literal (like a real staff, a key signature implies its own
   accidentals silently) -- this converter computes and writes out the
   EXPLICIT resulting accidental per note already (see note->pitch-text/
   key-signature), so nothing here should additionally apply THIS
   format's own :implied default on top of an already-resolved pitch."
  [abc-text]
  (let [chunks (split-tunes abc-text)
        tunes  (for [chunk chunks]
                 (let [[header body-lines] (parse-header-lines (str/split-lines chunk))]
                   (tune->mus header (str/join "\n" body-lines))))]
    (str "[ !accidentals:explicit\n" (str/join "\n" tunes) "\n]")))

(defn abc-to-mus
  "Read an ABC .abc file, convert it to musics DSL text (best effort),
   and write it back next to the source as a sibling <name>.mus file.
   Returns the path written to -- same contract as
   input.lilypond-import/from-ly-to-mus."
  [abc-path]
  (let [abc-file (io/file abc-path)
        base     (first (str/split (.getName abc-file) #"\.abc$"))
        mus-file (io/file (.getParent abc-file) (str base ".mus"))
        mus-text (abc-text->mus-text (slurp abc-file))]
    (spit mus-file mus-text)
    (.getPath mus-file)))
