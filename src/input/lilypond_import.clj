(ns input.lilypond-import

  "Best-effort, one-way LilyPond (.ly) -> musics DSL (.mus) TEXT converter.

   This is a text-to-text transpiler, not a domain-model builder: it never
   touches core.domain.* or input.reader.flat-*, it only produces musics
   surface syntax which the caller can then feed through the normal parse
   pipeline (or leave on disk as a .mus file).

   LilyPond's real grammar is effectively unbounded (arbitrary embedded
   Scheme, engraving overrides, markup). This handles the common musical
   core found in practice -- notes/rests/chords, durations, ties, Dutch
   pitch names (both \\relative and absolute), dynamics, hairpins, slurs,
   repeats/alternatives, tuplets, grace notes, tempo/time/key, comments,
   simple variable definitions and << >> / \\new Staff structure -- and
   silently drops anything outside that (markup, lyrics, \\override/\\set,
   layout/paper/midi blocks, clef, beaming brackets, engraving hints).
   Dropped/unhandled input never crashes the conversion; worst case a
   passage is missing some decoration, not garbled.

   Known simplifications, not fixed:
   - Assumes default (nederlands/Dutch) pitch-name language throughout.
   - Chord-internal relative-pitch octave choices follow whatever
     resolve-pitch/rel->midi already does for a plain sequential note
     stream, not LilyPond's own chord-relative quirk.
   - Duration-scaling suffixes (`4*2/3`) and multi-measure rests (`R`'s
     measure count) collapse to the base duration/a single rest.
   - \\alternative with more than one bracketed ending (3+ voltas) only
     keeps the first ending group -- our grammar's volta takes one Sequence.
   - \\partial (pickup upbeats) is dropped, not reinterpreted as an offset."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [input.reader.leaf-parser :as leaf]))

;; ============================================================
;; Tokenizer
;; ============================================================
;; Produces a tree of tokens:
;;   [:word    "cis,4->(\\f"]   raw non-whitespace run, sub-parsed later
;;   [:string  "some text"]     contents of a "..." literal
;;   [:comment "% raw"]         verbatim comment, incl. delimiters
;;   [:brace   [...tokens]]     { ... }
;;   [:dbl     [...tokens]]     << ... >>
;;   [:chord   "c e g"]         raw text between < and > (not <<>>)
;;   [:scheme  "raw"]           #( ... ) or a bare #atom -- always dropped

(defn- ws? [c] (Character/isWhitespace (char c)))

(defn tokenize
  "Parse LilyPond source text into a token tree (see shapes above)."
  [^String s]
  (let [n (count s)]
    (letfn [(skip-ws [i] (loop [i (int i)]
                           (if (and (< i n) (ws? (.charAt s i))) (recur (inc i)) i)))
            (read-line-comment [i]
              (let [end (or (str/index-of s "\n" i) n)]
                [[:comment (subs s i end)] end]))
            (read-block-comment [i]
              (let [close (str/index-of s "%}" (+ i 2))
                    end   (if close (+ close 2) n)]
                [[:comment (subs s i end)] end]))
            (read-string-lit [i]
              (loop [j (inc i)]
                (cond
                  (>= j n)             [[:string (subs s (inc i) n)] n]
                  (= (.charAt s j) \\) (recur (+ j 2))
                  (= (.charAt s j) \") [[:string (subs s (inc i) j)] (inc j)]
                  :else                (recur (inc j)))))
            (read-scheme [i]
              (if (and (< (inc i) n) (= (.charAt s (inc i)) \())
                (loop [j (+ i 2) depth 1]
                  (cond
                    (>= j n)             [[:scheme (subs s i n)] n]
                    (= (.charAt s j) \() (recur (inc j) (inc depth))
                    (= (.charAt s j) \)) (if (= depth 1)
                                           [[:scheme (subs s i (inc j))] (inc j)]
                                           (recur (inc j) (dec depth)))
                    :else                (recur (inc j) depth)))
                (let [end (loop [j (inc i)]
                            (if (and (< j n)
                                     (not (ws? (.charAt s j)))
                                     (not (contains? #{\{ \} \" \%} (.charAt s j))))
                              (recur (inc j))
                              j))]
                  [[:scheme (subs s i end)] end])))
            (read-chord [i]
              (let [close (loop [j (inc i)]
                            (cond (>= j n) n
                                  (= (.charAt s j) \>) j
                                  :else (recur (inc j))))]
                [[:chord (subs s (inc i) close)] (min n (inc close))]))
            (read-word [i]
              ;; A word run swallows bare < and > freely (they're almost
              ;; always glued suffixes like -> or \< /\> hairpins), but a
              ;; literal >> always closes an enclosing << >> group even
              ;; with no preceding space (a common divisi style: "\\bar>>"),
              ;; so it must stay its own token or the group's terminator
              ;; check downstream would never see it.
              ;;
              ;; The very first character is always consumed unconditionally
              ;; -- a malformed/mismatched source (a stray, unopened } for
              ;; instance) can hand read-word a position that's already a
              ;; stop-char, and a zero-length token there would spin
              ;; read-group forever instead of just producing garbage that
              ;; downstream drops.
              (let [end (loop [j (inc i)]
                          (if (and (< j n)
                                   (not (ws? (.charAt s j)))
                                   (not (contains? #{\{ \} \" \% \|} (.charAt s j)))
                                   (not (and (= (.charAt s j) \>) (< (inc j) n) (= (.charAt s (inc j)) \>))))
                            (recur (inc j))
                            j))]
                [[:word (subs s i end)] end]))
            (read-one [i]
              (let [c (.charAt s i)]
                (cond
                  (and (= c \%) (< (inc i) n) (= (.charAt s (inc i)) \{)) (read-block-comment i)
                  (= c \%) (read-line-comment i)
                  (= c \") (read-string-lit i)
                  (= c \#) (read-scheme i)
                  (and (= c \<) (< (inc i) n) (= (.charAt s (inc i)) \<))
                  (let [[toks j] (read-group (+ i 2) :dbl)] [[:dbl toks] j])
                  (= c \<) (read-chord i)
                  (= c \{) (let [[toks j] (read-group (inc i) :brace)] [[:brace toks] j])
                  ;; bar check -- often glued directly onto a note (\"g2|\"),
                  ;; so it needs its own dedicated single-char dispatch
                  ;; rather than falling into read-word (which would stop
                  ;; on it immediately and return a zero-length token).
                  (= c \|) [[:word "|"] (inc i)]
                  :else (read-word i))))
            (read-group [i terminator]
              (loop [i (int i) acc []]
                (let [i (skip-ws i)]
                  (cond
                    (>= i n) [acc n]
                    (and (= terminator :brace) (= (.charAt s i) \})) [acc (inc i)]
                    (and (= terminator :dbl) (<= (+ i 2) n) (= (subs s i (+ i 2)) ">>")) [acc (+ i 2)]
                    :else (let [[tok j] (read-one i)] (recur j (conj acc tok)))))))]
      (first (read-group (skip-ws 0) nil)))))

;; ============================================================
;; Pitch-name language
;; ============================================================

(def ^:dynamic *pitch-language*
  "LilyPond pitch-name language in effect for the file currently being
   converted -- :nederlands (LilyPond's own default, and this converter's
   own long-standing assumption) or :english, detected once up front (see
   detect-pitch-language) from a \\language \"...\" or \\include
   \"....ly\" directive and bound for the whole ly-text->mus-text call.
   Only these two have a real, distinct implementation; any other
   language LilyPond supports (deutsch, italiano, ...) falls back to
   :nederlands the same way a file that names no language at all always
   has -- a known simplification, not a silent wrong answer: guessing
   would be a real musical error, not a cosmetic one, since accidental
   TEXT is genuinely ambiguous between languages (a bare suffix \"s\"
   means sharp in English but is Dutch's own vowel-elided flat, e/a-s ->
   es/as). Confirmed live as exactly the bug this exists to prevent:
   Beethoven's Pathétique (\\include \"english.ly\") opens with <c g ef>
   in its very first bar -- \"ef\" (E-flat) read under the Dutch default
   isn't a recognized Dutch suffix at all, so it silently fell through
   split-pitch-token's own unrecognized-suffix case straight to a bare E
   natural, not a parse error -- wrong output with no warning, the worst
   kind."
  :nederlands)

(def ^:private known-pitch-languages
  #{"nederlands" "english" "deutsch" "italiano" "norsk" "svenska" "suomi"
    "espanol" "español" "portugues" "português" "vlaams" "catalan"})

;; detect-pitch-language itself is defined further down, right after
;; word-text (its own dependency, not yet defined at this point in the
;; file) -- see there.

;; ============================================================
;; Dutch pitch-name -> ours
;; ============================================================

(defn- split-pitch-token-nederlands
  "Split a bare Dutch pitch token (no duration/suffixes) into
   [letter accidental ticks]. letter is lowercase; accidental is passed
   through UNCHANGED in Dutch spelling (is/es/isis/eses/s/ses) rather than
   translated to our own #/b symbols -- musics-DSL's own grammar accepts
   Dutch accidental suffixes natively (Accidental = #'isis|eses|ses|is|es|s|
   ##|bb|[#bn]', see musics.ebnf, and leaf-parser/accidental-semitones
   resolves them to the identical semitone offset as #/b -- verified live,
   see the REPL check in this session's own notes), so translating away
   from the original source's own spelling is unnecessary work that only
   moves the converted text further from what was actually written. \"es\"/
   \"as\" (bare, no leading consonant -- the vowel-elided flat spelling
   LilyPond uses after e/a, e.g. \"es\" = e-flat, \"as\" = a-flat) still
   need to be split into letter+suffix specially, same as before, since
   \"e\"/\"a\" aren't themselves valid Accidental suffixes; ticks is the
   raw '/, run (or \"\")."
  [tok]
  (let [tick-idx (loop [i 0]
                   (cond
                     (>= i (count tok)) i
                     (contains? #{\' \,} (.charAt ^String tok i)) i
                     :else (recur (inc i))))
        body     (subs tok 0 tick-idx)
        ticks    (subs tok tick-idx)]
    (cond
      ;; e/a are the two Dutch letters whose own name ends in a vowel, so
      ;; their flat/double-flat suffix elides the accidental's leading
      ;; vowel too (es/eses, as/ases) rather than spelling the general
      ;; letter+eses form other letters use (deses/geses/...) -- these
      ;; four bodies are exactly the elided forms, matched whole here so
      ;; split back into [letter accidental] correctly reconstructs the
      ;; ORIGINAL elided spelling (letter+accidental = body exactly, e.g.
      ;; "e"+"ses"="eses", not the unelided "e"+"eses"="eeses"). "eses"
      ;; was a real, confirmed gap: without its own case here it fell
      ;; through to the generic letter+suffix branch below as letter "e"
      ;; + suffix "ses", which that branch's case doesn't recognize (only
      ;; the FULL "eses" suffix is listed there, for non-eliding letters
      ;; like deses/geses) -- so an E-double-flat silently lost its
      ;; accidental entirely instead of being passed through.
      (= body "es") ["e" "s" ticks]
      (= body "eses") ["e" "ses" ticks]
      (= body "as") ["a" "s" ticks]
      (= body "ases") ["a" "ses" ticks]
      (empty? body) ["c" "" ticks]
      :else
      (let [letter (subs body 0 1)
            suffix (subs body 1)]
        [letter (case suffix
                  ""     ""
                  "is"   "is"
                  "es"   "es"
                  "isis" "isis"
                  "eses" "eses"
                  ;; Anything else isn't a Dutch accidental spelling at
                  ;; all -- a fingering/editorial mark glued onto a note
                  ;; (e.g. "c--", confirmed live: a real .ly source using
                  ;; this on the lowest note of several chords) has no
                  ;; accidental meaning and no equivalent in this
                  ;; grammar, so it's dropped (treated as no accidental)
                  ;; rather than passed through raw -- the previous
                  ;; behavior emitted the unrecognized suffix verbatim
                  ;; into the output pitch token, which isn't valid
                  ;; syntax there and broke the conversion outright.
                  "")
         ticks]))))

;; ============================================================
;; English pitch-name -> ours
;; ============================================================

(defn- split-pitch-token-english
  "English pitch-name splitting (\\language \"english\"/\\include
   \"english.ly\"): letter + suffix directly, no Dutch-style vowel
   elision -- English always spells the full letter (\"ef\" for E-flat,
   never eliding the way Dutch's own \"es\" does), so unlike
   split-pitch-token-nederlands this needs no special two-letter-body
   cases at all. The suffix itself, unlike Dutch's, has NO representation
   in musics-DSL's own Accidental grammar rule (isis|eses|ses|is|es|s|
   ##|bb|[#bn] has no English s/f/x/ss/ff) -- and English's bare \"s\"
   would, passed through unchanged the way Dutch's own is, silently
   collide with Dutch's *own* \"s\" spelling, which means the opposite
   thing (flat, not sharp) -- so this DOES translate to our own #/b
   symbols rather than passing the source spelling through, the reverse
   of split-pitch-token-nederlands' own choice."
  [tok]
  (let [tick-idx (loop [i 0]
                   (cond
                     (>= i (count tok)) i
                     (contains? #{\' \,} (.charAt ^String tok i)) i
                     :else (recur (inc i))))
        body     (subs tok 0 tick-idx)
        ticks    (subs tok tick-idx)]
    (if (empty? body)
      ["c" "" ticks]
      (let [letter (subs body 0 1)
            suffix (subs body 1)]
        [letter (case suffix
                  ""   ""
                  "s"  "#"
                  "f"  "b"
                  "x"  "##"
                  "ss" "##"
                  "ff" "bb"
                  ;; Anything else -- same fallback convention as the
                  ;; Dutch case: no accidental meaning here, dropped
                  ;; rather than passed through raw.
                  "")
         ticks]))))

(defn- split-pitch-token
  "Dispatches to the pitch-language-specific splitter currently bound
   in *pitch-language* -- see its own docstring for which languages are
   actually implemented and why the rest fall back to Dutch."
  [tok]
  (if (= *pitch-language* :english)
    (split-pitch-token-english tok)
    (split-pitch-token-nederlands tok)))

(defn- ticks->our-octave
  "Absolute-mode octave digit for a tick run, per LilyPond's own
   convention: bare letter (no ticks) sits in the octave below middle C;
   each ' raises one octave, each , lowers one. Our octave 4 = middle C."
  [ticks]
  (+ 3 (- (count (filter #{\'} ticks)) (count (filter #{\,} ticks)))))

(def ^:private known-key-modes
  "Exactly the mode words our own \\key grammar rule accepts (ModeName,
   musics.ebnf) -- checked before emitting a \\key command at all, so an
   unsupported/unrecognized mode word (real LilyPond has none beyond
   these anyway) is dropped rather than emitted as text our own grammar
   would then fail to parse back."
  #{"major" "minor" "ionian" "dorian" "phrygian" "lydian" "mixolydian"
    "aeolian" "locrian" "harmonic-minor" "melodic-minor"})

(defn- tempo-notevalue
  "Convert a LilyPond \\tempo note-value token (\"4\", \"8.\", \"2..\", ...)
   into the form musics.ebnf's own TempoMark rule accepts on that side --
   (Int | Ratio), never a dotted digit (musics-DSL has no '4.' token of
   its own the way a note's own Duration does; a tempo note-value is
   always a plain whole-note fraction). An UNDOTTED digit N passes
   through as the bare Int 1/N already means (\"4\" -> \"4\"). A dotted
   digit is expanded to the equivalent Ratio -- each dot adds half of
   the previous increment (one dot: *3/2, two dots: *7/4, ...), same
   augmentation rule notated duration dots always follow -- e.g. \"4.\"
   (dotted quarter) -> \"3/8\", matching root CLAUDE.md's own worked
   example (!tempo:3/8=120 for a dotted quarter). Confirmed live as a
   real bug this fixes: the caller used to discard the note-value
   entirely and emit the bpm number bare, which only happens to be
   correct when the note-value is a plain undotted quarter -- a real
   \\tempo \"Moderato\" 4. = 50 (Bartok's own Mikrokosmos49) silently
   became !tempo:50 (quarter=50) instead of !tempo:4.=50's true
   quarter-equivalent (75), a genuine 1.5x tempo error, not a rounding
   nicety."
  [tok]
  (when tok
    (let [[_ digits dots] (re-matches #"(\d+)(\.*)" tok)]
      (when digits
        (let [n       (Integer/parseInt digits)
              ndots   (count dots)]
          (if (zero? ndots)
            digits
            (let [num (dec (bit-shift-left 1 (inc ndots)))   ;; 2^(ndots+1) - 1
                  den (* n (bit-shift-left 1 ndots))]         ;; n * 2^ndots
              (str num "/" den))))))))

(def ^:dynamic *last-duration*
  "The LilyPond source duration digit-string (\"4\", \"8.\", ...) that the
   most recently converted note/rest/chord's own EFFECTIVE duration was
   -- \"effective\" meaning either its own explicit digit, or (if the
   source itself omitted one) whatever this value already was, exactly
   the elision rule our own grammar's Note/Rest/Chord already apply on
   the parsing side (walk-note et al's own @(:last-dur state) fallback,
   flat_tree_walker.clj). elide-duration consults and updates this so a
   note whose duration equals it can have its own digit omitted on
   OUTPUT too, not just pass through whatever the LilyPond source itself
   already omitted -- real .ly sources routinely re-write an unchanged
   duration on every note regardless.
   Bound fresh (\"4\", this DSL's own default -- flat-core-builder's own
   :last-dur seed) at the start of each independent walk unit: the main
   top-level content, and each LilyPond variable's own body separately
   (compute-usable-vars) -- mirroring walk-var-def's own save/reset/
   restore of :last-dur around a variable's body, never reset at a
   nested { }/<< >>/\\repeat/\\times boundary, since the real walker's
   own :last-dur isn't either (one shared atom for the whole walk,
   except across a VarDef's own body)."
  nil)

(def ^:dynamic *last-ref*
  "The previous relative-mode note's own {:letter :octave} ref -- our own
   leaf-parser/resolve-pitch's exact last-ref shape, threaded here so an
   ABSOLUTE-source note (relative?=false) can still be respelled into
   relative form chaining correctly from whatever came before it (see
   respell-relative), and so a relative-source note's own pass-through
   spelling still keeps this in sync for whatever note (relative OR
   absolute-source) comes after it. nil means 'no previous note yet',
   matching resolve-pitch's own default-ref fallback. Same fresh-per-
   walk-unit reset discipline as *last-duration* -- see its own
   docstring for why (mirrors walk-var-def)."
  nil)

(defn- elide-duration
  "Given a raw LilyPond duration digit-string (or nil, when the source
   itself already omitted it), return what to actually print: nil (omit)
   when the effective duration equals *last-duration*'s current value --
   this DSL's own implicit-duration fallback reconstructs the identical
   value once parsed either way, so the digit is genuinely redundant --
   or the digit itself when it changed. Always updates *last-duration*
   to the resolved effective value, so the NEXT call compares against
   what THIS one actually resolved to, not just its own written digit."
  [dur]
  (let [effective (or dur *last-duration*)
        changed?  (not= effective *last-duration*)]
    (set! *last-duration* effective)
    (when changed? effective)))

(defn- respell-relative
  "Given a target absolute MIDI value, the previous relative note's own
   {:letter :octave} last-ref (nil for the very first note), and
   [letter accidental] already spelled exactly as the source wrote it
   (language-aware, via split-pitch-token) -- compute [ticks new-ref]
   such that leaf/resolve-pitch [letter accidental ticks] last-ref
   resolves to EXACTLY target-midi. This is the reverse of what
   leaf/rel->midi computes forward (given a written relative pitch,
   resolve its MIDI value): here the MIDI value is already fixed (from
   an absolute-source note this converter wants to respell relative,
   see guideline #7 -- 'favour relative over absolute but stay true to
   the source', musics-DSL's own CLAUDE.md), and the unknown is how many
   extra octave-ticks a relative spelling needs to land on it exactly.
   Since letter+accidental already fix the correct pitch class by
   construction (both were derived from the identical source pitch),
   the gap between the naive (zero extra ticks) relative resolution and
   target-midi is always an exact multiple of 12 -- one octave per
   extra tick, same 'nearest fourth/fifth, then ticks shift by a whole
   octave' rule leaf-parser/rel->midi itself implements."
  [target-midi last-ref letter accidental]
  (let [[naive-midi _] (leaf/resolve-pitch [letter accidental ""] last-ref)
        octaves        (quot (- target-midi naive-midi) 12)
        ticks          (cond (pos? octaves) (apply str (repeat octaves \'))
                              (neg? octaves) (apply str (repeat (- octaves) \,))
                              :else "")
        [_ new-ref]    (leaf/resolve-pitch [letter accidental ticks] last-ref)]
    [ticks new-ref]))

(defn- pitch-seed-ref
  "{:letter :octave} last-ref for a \\relative START pitch (e.g. \"c''\",
   \"g,\") -- what leaf/resolve-pitch/rel->midi themselves expect as a
   last-ref to chain from. Used to SEED *last-ref* before a \\relative
   block's own body is walked (relative-block-text), not to patch
   already-emitted text after the fact the way an earlier version of
   this converter did (reanchor-first-note, since removed -- seeding
   upfront means every note in the block chains correctly from the
   start, not just textually the first, and *last-ref* itself stays
   correctly in sync afterward for guideline #7's respell-relative on
   whatever comes next)."
  [start-pitch-tok]
  (let [[letter accidental ticks] (split-pitch-token start-pitch-tok)
        octave (max 1 (min 8 (ticks->our-octave ticks)))]
    (second (leaf/resolve-pitch [(str/upper-case letter) accidental (str octave "/")] nil))))

;; ============================================================
;; Note-chunk sub-parser
;; ============================================================
;; A single glued run like "d4->(\\f" or "cis,8.~" -- pulls the leading
;; pitch/rest/duration off the front, then peels known suffixes off the
;; back in order, translating each to musics surface syntax.

(def ^:private ornament-names
  #{"prall" "prallup" "pralldown" "upprall" "downprall" "prallprall" "lineprall"
    "prallmordent" "mordent" "upmordent" "downmordent" "trill" "turn" "reverseturn"
    "shortfermata" "fermata" "longfermata" "verylongfermata"})

(def ^:private articulation-names
  #{"staccato" "staccatissimo" "tenuto" "marcato" "portato" "accent" "espressivo"})

;; The exact word list our own grammar's DynamicMark rule accepts as a
;; glued Note/Chord suffix (\f, \mf, ...) -- these translate to *identical*
;; text, no `!name` Instruction needed at all (see peel-suffix).
(def ^:private core-dynamic-marks
  #{"pppp" "ppp" "pp" "p" "mp" "mf" "ffff" "fff" "ff" "f"})

;; Accent-style marks LilyPond also allows glued to a note but our
;; DynamicMark rule doesn't cover -- these still need the `!name`
;; Instruction fallback.
(def ^:private extended-dynamic-marks
  #{"sf" "sfz" "sfp" "fp" "rfz" "sff" "sfffz"})

(defn- pitch-token? [tok]
  (boolean (re-find #"^[a-grR]" tok)))

(def ^:private note-head-accidental-alternation
  "The note-head regex's own (?:...)? accidental alternation, per pitch
   language -- see parse-note-head's own comment for why this has to
   consume the accidental suffix itself, not just the bare letter,
   before duration/trailing-garbage splitting ever happens. Longest
   spellings listed first in each so the alternation (tried
   left-to-right) never stops early on a shorter prefix of a longer one
   (isis before is, ss/ff before s/f) -- confirmed live as a real gap
   for English specifically: without this ordering/coverage at all, a
   token like \"cf4\"/\"css4\" (Beethoven's Pathétique, \\include
   \"english.ly\") matched head=\"c\" only under the Dutch-only
   alternation this used to be hardcoded to (English's own f/ss/x/ff
   aren't Dutch spellings), leaving \"f4\"/\"ss4\" as unrecognized
   trailing garbage that convert-note-chunk then silently dropped -- the
   note lost both its accidental AND its duration, not just one."
  {:nederlands "isis|eses|ses|is|es|s"
   :english    "ss|ff|x|s|f"})

(defn- note-head-regex []
  (re-pattern (str "^(r|R|[a-g](?:"
                    (get note-head-accidental-alternation *pitch-language*
                         (note-head-accidental-alternation :nederlands))
                    ")?)([',]*)([0-9]+\\.*)?(.*)$")))

(defn- chord-pitch-token
  "A pitch inside a chord's own pitch list may have some other note-level
   decoration glued directly onto it in the source -- confirmed live,
   Handel's overture: a fermata on one specific chord tone,
   `<d,\\fermata a'>1`. Our own ChordPitches grammar rule (musics.ebnf)
   has no per-pitch decoration slot at all, unlike a real Note. Strips
   anything after the recognized letter+accidental+ticks prefix (reusing
   note-head-regex, so this stays language-aware the same way
   parse-note-head is) rather than passing it through raw -- without
   this, the naive whitespace-split token \"d,\\fermata\" has no ' or ,
   past the comma to separate pitch from decoration, so the decoration
   text leaked straight into the ticks position and broke the chord's
   grammar outright, not just lost one decoration.
   Returns nil, not the raw token, when tok doesn't start with a real
   pitch letter/rest at all -- a chord entry that isn't a pitch shape
   has no equivalent here either way, same as any other unrepresentable
   construct this converter drops, and passing it through raw let its
   first character get blindly treated as a pitch letter by whatever
   called this (confirmed live as a real bug: LilyPond's own manual-
   style placeholder pitches, `< noteB noteC >`, degraded to a bogus
   bare `n` chord tone instead of being dropped -- this project's own
   test/voices.ly, copied verbatim from LilyPond's documentation on
   \\relative chord scoping, not real playable music)."
  [tok]
  (when-let [[_ head ticks] (re-matches (note-head-regex) tok)]
    (str head ticks)))

(defn- parse-note-head
  "Pull [emitted-text remaining] off the front of a note-chunk string,
   converting the pitch and any duration/dots along with it. Returns nil
   if tok doesn't start with a recognizable pitch/rest.
   Always emits RELATIVE (lowercase) pitch form now, source spelling
   convention regardless -- guideline #7 ('favour relative over absolute
   but stay true to the source'). A relative-source note (relative?
   true) passes its own letter/accidental/ticks straight through
   unchanged (already optimal -- LilyPond's own \\relative resolution IS
   this DSL's own lowercase resolution, see musics-DSL's own CLAUDE.md),
   but still resolves+tracks *last-ref* via leaf/resolve-pitch so a
   LATER absolute-source note in the same stream chains from the
   correct running reference. An absolute-source note (relative?
   false, explicit octave already written) is resolved to its target
   MIDI value first (abs->midi, via resolve-pitch's own uppercase-
   letter dispatch), then respell-relative works out the tick-string a
   relative spelling needs to land on that exact same MIDI value -- the
   source's own sounding pitch is preserved exactly, only the spelling
   changes.
   Duration is elided via elide-duration -- see its own docstring."
  [tok relative?]
  (when-let [m (re-matches (note-head-regex) tok)]
    (let [[_ head ticks dur rest-str] m
          dur' (elide-duration dur)]
      (if (contains? #{"r" "R"} head)
        [(str "r" (or dur' "")) rest-str]
        (let [[letter accidental _] (split-pitch-token (str head ticks))]
          (if relative?
            (let [[_ new-ref] (leaf/resolve-pitch [letter accidental ticks] *last-ref*)]
              (set! *last-ref* new-ref)
              [(str letter accidental ticks (or dur' "")) rest-str])
            (let [octave (max 1 (min 8 (ticks->our-octave ticks)))
                  [target-midi] (leaf/resolve-pitch [(str/upper-case letter) accidental (str octave "/")] nil)
                  [rel-ticks new-ref] (respell-relative target-midi *last-ref* letter accidental)]
              (set! *last-ref* new-ref)
              [(str letter accidental rel-ticks (or dur' "")) rest-str])))))))

(defn- peel-suffix
  "Try each known trailing-suffix pattern against s (a note-chunk tail).
   Returns [kind text remaining]:
     :articulation text is our grammar's single Articulation slot
                   (shorthand or \\name) -- always glued right after
                   Duration, never repeated.
     :suffix       text is a NoteSuffix (Ornament/Modifier/Tremolo/Dynamic/
                   Hairpin/SlurMark) -- glued after Articulation, any
                   number of times. Dynamic/Hairpin/SlurMark's glued form
                   is *identical text* to LilyPond's own (\\f, \\<, `(`/`)`)
                   -- our grammar's DynamicMark/Hairpin/SlurMark rules
                   were written to match LilyPond one-for-one, so these
                   pass through unchanged rather than becoming a separate
                   Instruction (see below for why that distinction is
                   load-bearing, not cosmetic).
     :tie          text (always \"~\") glues as the note's own trailing
                   Tie -- always last, at most once.
     :token        text becomes its own space-separated Instruction token,
                   emitted *before* the note (see convert-note-chunk) --
                   only the handful of accent-style dynamics our grammar's
                   DynamicMark rule doesn't cover (extended-dynamic-marks)
                   still need this.
     :drop         nothing emitted (text is nil), just consumes and continues
   Returns nil if s doesn't match any known suffix at all.

   :articulation/:suffix/:tie are kept apart (rather than one generic
   :glue) so convert-note-chunk can reassemble them onto the note head in
   our grammar's fixed Articulation? NoteSuffix* Tie? order regardless of
   what order LilyPond's source wrote them in -- gluing onto \"whatever was
   emitted last\" produced invalid text like \"D3/4~-.\" for `d4~-.` (Tie
   emitted before Articulation), which our own grammar doesn't accept back.

   Dynamic/Hairpin/SlurMark used to become a standalone `!name`/`!vol<`/
   `!(` Instruction token placed *after* the note instead of a glued
   suffix -- that's a real timing bug, not just a style choice: a
   standalone Instruction's context-envelope point lands at whatever beat
   the walker's structural clock reads *when it's walked*
   (flat_tree_walker.clj's walk-bang-const, `t = (duration state)`), and
   placing it after the note means the clock has already advanced past
   that note's own duration -- the mark would only take effect from the
   *next* event onward, not from this note's onset the way LilyPond (and
   our own glued-suffix path, apply-note-dynamics!, which samples the
   note's own onset time directly) both intend. `d2.\\p~` (dynamic before
   tie) and `d2.~\\p` (tie before dynamic) mean the same thing in
   LilyPond -- both used to produce the same, wrongly-timed `D3/2. !p~`/
   `D3/2.~ !p` text; gluing them (`D3/2.~\\p`, matching apply-note-dynamics!
   which doesn't care what order Dynamic/Hairpin/Tie appear in a note's own
   modifiers) fixes both the timing and, incidentally, the earlier
   invalid-reparse bug in one move."
  [s]
  (cond
    (empty? s) nil
    (str/starts-with? s "~") [:tie "~" (subs s 1)]
    (str/starts-with? s "(") [:suffix "(" (subs s 1)]
    (str/starts-with? s ")") [:suffix ")" (subs s 1)]

    (re-find #"^:[0-9]+" s)
    (let [m (re-find #"^:[0-9]+" s)] [:suffix m (subs s (count m))])

    :else
    (if-let [[_ _dir name rest-str] (re-matches #"^([-^_]?)\\([a-zA-Z]+)(.*)$" s)]
      (cond
        ;; Ornament/Modifier are NoteSuffixes in our grammar -- glued
        ;; directly onto the note (Note = Pitch Duration Articulation?
        ;; NoteSuffix* Tie?).
        (contains? ornament-names name)      [:suffix (str "\\" name) rest-str]
        (contains? articulation-names name)  [:articulation (str "\\" name) rest-str]
        (contains? core-dynamic-marks name)  [:suffix (str "\\" name) rest-str]
        (contains? extended-dynamic-marks name) [:token (str "!" name) rest-str]
        :else                                [:drop nil rest-str])
      (if-let [[_ cmd rest-str] (re-matches #"^([-^_]?\\[<>!])(.*)$" s)]
        (case (subs cmd (dec (count cmd)))
          "<" [:suffix "\\<" rest-str]
          ">" [:suffix "\\>" rest-str]
          "!" [:drop nil rest-str])
        (if-let [[_ shorthand rest-str] (re-matches #"^(-[-.>^_!+])(.*)$" s)]
          [:articulation shorthand rest-str]
          nil)))))

(defn- convert-note-chunk*
  "Convert one glued LilyPond note-chunk into musics text.
   Returns a vector of emitted tokens with the note itself always *last*
   (e.g. [\"!sf\" \"c#4\"] for a note carrying an accent-style dynamic our
   grammar can't glue directly -- see peel-suffix's :token case for why
   that has to precede the note rather than follow it). The note's own
   Articulation/NoteSuffix*/Tie are assembled onto the head in that fixed
   order once the whole chunk is consumed, not incrementally as each is
   encountered. A second Articulation-kind suffix (LilyPond allows
   stacking, e.g. `c4-.->`; our grammar's Articulation slot is singular)
   is silently dropped, same as any other unrecognized suffix. Returns
   nil if tok isn't a recognizable note/rest chunk at all.

   A rest (head starting with \"r\" -- 'r' is reserved for Rest and never
   a real relative-mode pitch letter, see PitchLetterRel in musics.ebnf)
   drops any accumulated Articulation/NoteSuffix/Tie rather than gluing
   them on: our grammar's Rest rule is just 'r' Duration, with none of a
   Note's own trailing slots (musics.ebnf) -- unlike LilyPond, which
   allows e.g. a fermata glued straight onto a rest (`r4\\fermata`, a
   real, confirmed case: the final bar of bwv-988-v12.ly's own soprano
   line). The tokens are still fully consumed either way (peel-suffix's
   own loop doesn't change), only the ACCUMULATED text gets dropped for
   a rest's own head -- same 'best-effort, drop what has no equivalent'
   philosophy as every other unrepresentable decoration this converter
   already drops."
  [tok relative?]
  (when-let [[head rest-str] (parse-note-head tok relative?)]
    (loop [s rest-str articulation nil suffixes [] tie nil tokens []]
      (let [finish #(conj tokens (if (str/starts-with? head "r")
                                    head
                                    (str head articulation (apply str suffixes) tie)))]
        (if (empty? s)
          (finish)
          (if-let [[kind text rest-str'] (peel-suffix s)]
            (case kind
              :articulation (recur rest-str' (or articulation text) suffixes tie tokens)
              :suffix       (recur rest-str' articulation (conj suffixes text) tie tokens)
              :tie          (recur rest-str' articulation suffixes (or tie text) tokens)
              :token        (recur rest-str' articulation suffixes tie (conj tokens text))
              :drop         (recur rest-str' articulation suffixes tie tokens))
            ;; unrecognized trailing garbage -- stop, drop the remainder
            (finish)))))))

(defn convert-note-chunk
  "Public entry point over convert-note-chunk* -- establishes a fresh,
   isolated *last-duration*/*last-ref* binding (nil/nil, so this call's
   own duration is always shown rather than silently elided against
   whatever a PREVIOUS standalone call happened to leave behind) when
   neither is already thread-bound, i.e. when called directly (as this
   corpus's own lilypond_import_test.clj does, one isolated chunk at a
   time) rather than through ly-text->mus-text's own real conversion
   flow, which already establishes both (seeded \"4\"/nil, matching this
   DSL's own implicit-duration default) around the WHOLE conversion --
   see *last-duration*/*last-ref*'s own docstrings. thread-bound? is the
   correct check, not a nil check: nil is *last-duration*'s own root
   value too, indistinguishable from \"already bound to nil\" by value
   alone."
  [tok relative?]
  (if (thread-bound? #'*last-duration*)
    (convert-note-chunk* tok relative?)
    (binding [*last-duration* nil *last-ref* nil]
      (convert-note-chunk* tok relative?))))

(defn- parse-chord-tail
  "A Chord's own grammar shape (musics.ebnf: '<' ChordPitches '>' Duration
   Articulation? NoteSuffix* Tie?) is IDENTICAL to a Note's own trailing
   shape, just with no leading Pitch -- so whatever glued word-token
   immediately follows a chord's closing '>' in the LilyPond source
   (\"4~\", \"4.\\\\p-.\", or nil if the chord had none, which our grammar
   allows -- see Chord's second alternative) can reuse peel-suffix
   directly, seeded with an empty head instead of a real pitch.
   Returns [leading-tokens tail-text]: leading-tokens is a (possibly
   empty) vector of standalone Instruction tokens that must be emitted
   BEFORE the chord (same reasoning as convert-note-chunk's own :token
   case -- an extended dynamic like \\sf has no glued equivalent, so it
   has to land as a standalone !sf ahead of the chord to take effect at
   the chord's own onset); tail-text is the Duration+Articulation+
   NoteSuffix*+Tie text to glue directly after '>' with no space.
   A real, confirmed gap this closes: without this, a chord's own
   duration/tie/dynamics glued onto '>' were silently dropped entirely
   (the tokenizer never attaches them to the :chord token itself, and
   emit-stream never looked for them as a separate following token) --
   confirmed live against this corpus's own bwv-988 variations, which
   routinely glue a duration straight onto a chord's '>'."
  [tok]
  (if (str/blank? tok)
    ["" []]
    (let [[_ dur rest-str] (re-matches #"^([0-9]+\.*)?(.*)$" tok)
          dur' (elide-duration dur)]
      (loop [s (or rest-str "") articulation nil suffixes [] tie nil tokens []]
        (if (empty? s)
          [(str dur' articulation (apply str suffixes) tie) tokens]
          (if-let [[kind text rest-str'] (peel-suffix s)]
            (case kind
              :articulation (recur rest-str' (or articulation text) suffixes tie tokens)
              :suffix       (recur rest-str' articulation (conj suffixes text) tie tokens)
              :tie          (recur rest-str' articulation suffixes (or tie text) tokens)
              :token        (recur rest-str' articulation suffixes tie (conj tokens text))
              :drop         (recur rest-str' articulation suffixes tie tokens))
            [(str dur' articulation (apply str suffixes) tie) tokens]))))))

;; ============================================================
;; Variable pre-pass
;; ============================================================

(defn- top-level-keyword? [w]
  ;; \\book/\\bookpart missing here was a real, confirmed bug: without a
  ;; boundary keyword to stop at, assignment-value-span (used both by
  ;; collect-vars and the top driver's own "skip past this assignment"
  ;; logic) had nothing to stop it from swallowing the ENTIRE rest of the
  ;; file into the immediately-preceding variable's own value once a
  ;; \\book followed it with no further variable definition after -- a
  ;; real shape in this corpus (bwv1007.ly: \"prelude = \\relative c' {
  ;; ... }\" directly followed by \"\\book { \\score { ... } }\", no
  ;; other top-level keyword in between) that silently nested the WHOLE
  ;; \\book, including its own \\prelude reference, inside prelude's own
  ;; VarDef body -- a self-referential VarRef the walker correctly
  ;; rejected as \"referenced before its definition\".
  ;; \\addQuote missing here was the same bug in a different shape: a
  ;; variable directly followed by \\addQuote \"name\" \\varname (no
  ;; other top-level keyword between them, a real shape in this corpus's
  ;; own testAddQuote.ly) let assignment-value-span swallow \\addQuote's
  ;; own trailing \\varname reference into the PRECEDING variable's own
  ;; value -- a self-referential VarRef the walker correctly rejected as
  ;; \"referenced before its definition\", same failure mode \\book's own
  ;; gap above already produced once, just with the swallowed reference
  ;; sitting one token further along instead of at the very end.
  (contains? #{"\\header" "\\paper" "\\layout" "\\midi" "\\score"
               "\\version" "\\language" "\\include" "\\book" "\\bookpart"
               "\\addQuote"} w))

(defn- assignment-name? [w]
  ;; Real LilyPond identifiers allow a hyphen (part-soprano/part-alto/...,
  ;; a real, confirmed shape in this corpus's own bwv-1080-I.ly -- our own
  ;; assumption of camelCase-only was never actually a LilyPond rule, just
  ;; what the single earlier test piece happened to use) -- recognized
  ;; here so assignment-value-span's boundary detection doesn't miss it
  ;; (a real, confirmed bug: without this, \"part-soprano\" wasn't
  ;; recognized as the start of a NEW assignment at all, so it silently
  ;; got folded as trailing noise into whatever variable happened to
  ;; precede it in the source). musics-DSL's own VarName grammar rule
  ;; doesn't allow a hyphen, though (see musics.ebnf) -- sanitize-name
  ;; converts it to '_' at every point a name is actually EMITTED as a
  ;; VarDef/VarRef, not here (this predicate is detection-only)."
  (boolean (re-matches #"^[A-Za-z][A-Za-z0-9-]*$" w)))

(defn- sanitize-name
  "A LilyPond identifier as written may contain a hyphen (part-soprano);
   musics-DSL's own VarName grammar rule doesn't allow one (letters/
   digits/underscore only -- see musics.ebnf). Applied uniformly at every
   point a name is stored as a var-map key or emitted as VarDef/VarRef
   text, so a hyphenated LilyPond name and every reference to it still
   agree on the same (now-valid) spelling."
  [w]
  (str/replace w #"-" "_"))

(defn- word-text [tok] (when (= (first tok) :word) (second tok)))

(defn- detect-pitch-language
  "Scan tokens (top level only -- every real \\language/\\include this
   converter has ever seen sits ahead of any real music, never nested
   inside a { }/<< >>) for the first \\language \"name\" or
   \\include \"name.ly\" naming a known LilyPond pitch-name language, and
   return the matching keyword (:english/:nederlands/...), or nil if
   none is found (caller defaults to :nederlands, same as always -- see
   *pitch-language*'s own docstring). Only :english is actually
   implemented differently by split-pitch-token -- any OTHER recognized
   name still returns its own keyword rather than nil, so that fallback
   stays an explicit, visible default rather than this scan quietly
   pretending nothing was said."
  [tokens]
  (some (fn [[t1 t2]]
          (cond
            (and (= (word-text t1) "\\language") (= (first t2) :string))
            (let [nm (str/lower-case (second t2))]
              (when (contains? known-pitch-languages nm) (keyword nm)))

            (and (= (word-text t1) "\\include") (= (first t2) :string))
            (let [nm (-> (second t2) (str/replace #"(?i)\.ly$" "") str/lower-case)]
              (when (contains? known-pitch-languages nm) (keyword nm)))))
        (map vector tokens (rest tokens))))

(defn- looks-like-next-assignment? [tokens]
  (and (>= (count tokens) 2)
       (= (first (first tokens)) :word)
       (assignment-name? (second (first tokens)))
       (= (first (second tokens)) :word)
       (= (second (second tokens)) "=")))

(defn- assignment-value-span
  "How many leading tokens of body make up the VALUE of a `name = ...`
   assignment -- everything up to the next assignment/top-level keyword/
   EOF. Shared by collect-vars (to capture the value) and every place
   that must skip PAST an assignment it already captured, so the two
   never disagree about where the value ends."
  [body]
  (loop [i 0]
    (cond
      (>= i (count body)) i
      (looks-like-next-assignment? (drop i body)) i
      (and (= (first (nth body i)) :word)
           (top-level-keyword? (second (nth body i))))
      i
      :else (recur (inc i)))))

(defn collect-vars
  "Scan top-level tokens for `name = ...rest-of-statement...` definitions.
   A value runs until the next assignment/top-level keyword/EOF.
   Returns [{name -> [tokens]} ordered-names] -- ordered-names preserves
   original definition order (a plain map's own iteration order isn't
   reliable once there are more than a handful of entries, and this
   piece's own source routinely has more), needed so LilyPond variables
   can be re-emitted as musics-DSL VarDefs in an order that's still
   valid once one variable references another defined earlier -- the
   same ordering constraint LilyPond's own source already had to
   satisfy, so preserving definition order is sufficient, no separate
   dependency analysis needed."
  [tokens]
  (loop [remaining tokens vars {} order []]
    (if (empty? remaining)
      [vars order]
      (let [t1 (first remaining)
            t2 (second remaining)]
        (if (and (= (first t1) :word) (assignment-name? (second t1))
                 t2 (= (first t2) :word) (= (second t2) "="))
          (let [body         (drop 2 remaining)
                n            (assignment-value-span body)
                value-tokens (vec (take n body))
                name         (sanitize-name (second t1))]
            (recur (drop n body) (assoc vars name value-tokens) (conj order name)))
          (recur (rest remaining) vars order))))))

;; ============================================================
;; \include resolution
;; ============================================================

(defn- expand-includes
  "Recursively replace every `\\include \"path\"` TOKEN PAIR in tokens
   with the (recursively expanded) token stream of the referenced file,
   read relative to dir -- the INCLUDING file's own directory, matching
   real LilyPond include semantics (this matters concretely: bwv-1080-I/
   contrapunctusI.ly includes \"structure.ily\" meaning a sibling of
   ITSELF, not of whatever top-level file eventually points at it).
   The extension named in the \\include string is never assumed to be
   .ly -- this corpus's own included fragments are .ily, and whatever
   string is written is read as-is.

   Operates on the TOKEN tree (post-tokenize), not raw text -- load-
   bearing, not a style choice: a \\include sitting inside a %{ ... %}
   block comment (a real, confirmed case in this corpus -- bwv1007.ly
   disables three of its own movement includes exactly this way, while
   still referencing the resulting variables from an active \\score) is
   correctly left alone, since tokenize already turned that whole
   comment into one opaque :comment token with no separate :word/:string
   pair for a raw-text pass to stumble onto and wrongly expand.

   seen is the set of canonical paths already being expanded along the
   CURRENT inclusion chain (not a global 'already used anywhere' set --
   the same file legitimately included from two different places is
   fine) -- a repeat there is a genuine cycle, reported as a clear
   ex-info rather than silently recursing forever.

   Recurses into :brace/:dbl children too -- defensive: no file in this
   corpus nests an \\include inside a { }/<< >>, but nothing in LilyPond's
   own grammar rules it out for a differently-organized piece."
  [tokens dir seen]
  (loop [tokens tokens out []]
    (if (empty? tokens)
      out
      (let [t1 (first tokens)
            t2 (second tokens)]
        (cond
          (and (= (word-text t1) "\\include") (= (first t2) :string))
          (let [rel-path (second t2)
                f        (io/file dir rel-path)
                canon    (.getCanonicalPath f)]
            (when (contains? seen canon)
              (throw (ex-info (str "Circular \\include detected: " canon)
                               {:path canon :chain seen})))
            (if (.exists f)
              (recur (drop 2 tokens)
                     (into out (expand-includes (tokenize (slurp f)) (.getParent f)
                                                 (conj seen canon))))
              ;; Missing include target -- best-effort, same as any other
              ;; unhandled construct: drop it rather than fail the whole
              ;; conversion.
              (recur (drop 2 tokens) out)))

          (contains? #{:brace :dbl} (first t1))
          (recur (rest tokens)
                 (conj out [(first t1) (expand-includes (second t1) dir seen)]))

          :else
          (recur (rest tokens) (conj out t1)))))))

;; ============================================================
;; Header extraction
;; ============================================================

(defn- header-comment [brace-tokens]
  (let [text   (apply str (map (fn [t] (case (first t)
                                          :string (str "\"" (second t) "\"")
                                          (str (second t))))
                                brace-tokens))
        fields ["title" "subtitle" "composer" "opus" "piece" "dedication"]
        found  (keep (fn [field]
                       (when-let [[_ v] (re-find (re-pattern (str field "\\s*=\\s*\"([^\"]*)\"")) text)]
                         [field v]))
                     fields)]
    (when (seq found)
      (str "%{\n"
           (str/join "\n" (map (fn [[k v]] (str "  " k " = \"" v "\"")) found))
           "\n%}"))))

;; ============================================================
;; Main token-stream transform
;; ============================================================

(declare emit-stream emit-voice)

(def ^:private noise-commands
  "Commands whose musical meaning is engraving/property-only -- drop the
   command and any simple (non-brace, non-backslash) tokens that make up
   the rest of that same property statement."
  #{"override" "set" "unset" "tweak"})

(def ^:private bare-drop-commands
  "Zero-argument commands with no musical meaning to us."
  #{"break" "pageBreak" "noPageBreak" "pageTurn" "allowPageTurn"
    "voiceOne" "voiceTwo" "voiceThree" "voiceFour" "oneVoice"
    "stemUp" "stemDown" "stemNeutral" "autoBeamOff" "autoBeamOn"
    "numericTimeSignature" "defaultTimeSignature" "cadenzaOn" "cadenzaOff"
    "slurUp" "slurDown" "slurNeutral" "tieUp" "tieDown" "tieNeutral"})

(defn- backslash-cmd
  "If token is a :word starting with \\, return the bare command name."
  [tok]
  (when-let [w (word-text tok)]
    (when (str/starts-with? w "\\")
      (subs w 1))))

(defn- drop-noise-tail
  "After a noise command (\\override/\\set/...), drop tokens until (and
   including) the property statement's end: the next :string/:scheme, or
   an '=' word -- whichever ends the statement -- stopping early if real
   content (a \\command or a group) shows up first."
  [tokens]
  (loop [tokens tokens]
    (if (empty? tokens)
      tokens
      (let [tok (first tokens)]
        (cond
          (contains? #{:scheme :string} (first tok)) (rest tokens)
          (= (word-text tok) "=") (recur (rest tokens))
          (and (= (first tok) :word)
               (not (str/starts-with? (second tok) "\\")))
          (recur (rest tokens))
          :else tokens)))))

(defn- push-barline
  "Append a bar line to out, collapsing it against an already-adjacent one
   (a bare | bar check and a \\bar \"...\" command are two different
   source constructs that can both map to the same \"|\")."
  [out]
  (if (= (last out) "|")
    out
    (conj out "|")))

(defn- skip-with-block
  "Drop a \\with { ... } prefix if present, returning the remaining tokens."
  [tokens]
  (if (= (backslash-cmd (first tokens)) "with")
    (let [after-with (rest tokens)]
      (if (= (first (first after-with)) :brace)
        (rest after-with)
        after-with))
    tokens))

(defn- transpose-pitch [tok]
  (when-let [w (word-text tok)]
    (let [[letter accidental ticks] (split-pitch-token w)]
      (str (str/upper-case letter) accidental (max 1 (min 8 (ticks->our-octave ticks))) "/"))))

(defn- has-content?
  "True if text (already-converted musics surface text) holds at least
   one real Element once brace/bar/whitespace noise is stripped away --
   the same emptiness check ly-text->mus-text's own blank-variable
   filtering already used, generalized here so any nested { }/\\transpose/
   \\times/\\tuplet body that converted to nothing at all (most concretely:
   the body was only ever a reference to a variable whose own defining
   \\include never got expanded -- a real, confirmed case in this corpus,
   see bwv1007.ly, whose \\allemande/\\courante/\\sarabande \\include lines
   sit inside a %{ ... %} comment and so are correctly never expanded)
   can be dropped cleanly instead of emitted as an invalid, contentless
   `{  }`/`\\transpose x y {  }` -- same 'drop cleanly, don't emit
   garbage' philosophy this converter already applies to a blank
   LilyPond variable."
  [text]
  (not (str/blank? (str/replace (or text "") #"[{}|\s]+" ""))))

(defn- relative-block-text
  "\\relative PITCH? { ... } -- more is whatever immediately follows the
   \\relative command token. Shared by emit-stream's own :relative case
   (a \\relative nested inside other content) and the top-level driver
   (a bare \\relative with no \\score wrapper at all -- a real, confirmed
   gap: this corpus's own bwv1007.ly preludes are written exactly this
   way, `prelude = \\relative c' { ... }` aside, some pieces open with a
   bare top-level \\relative and no \\score/\\new at all -- the top-level
   driver used to have no case for \\relative whatsoever, so the command
   and its start pitch silently vanished into its own :else branch and
   the following { } got emitted with relative?=false, absolute pitches
   for content the source clearly wrote relative).

   Seeds *last-ref* to the block's own explicit start pitch
   (pitch-seed-ref) before walking its body, in a fresh nested binding
   -- every note in the block then chains correctly from the very
   start via parse-note-head's own leaf/resolve-pitch call, no separate
   after-the-fact text patching needed. No explicit start pitch (bare
   \\relative { ... }) seeds nil, which leaf/resolve-pitch's own
   default-ref fallback already treats as this DSL's own default
   reference -- matching what a real LilyPond \\relative block with no
   pitch argument means (start fresh, not continue from whatever came
   before), the only way this importer's flat, block-boundary-erased
   output text has to represent that reset at all. The block's own
   ENDING *last-ref* is captured before the nested binding closes and
   set! back onto the OUTER *last-ref* -- `binding` doesn't propagate a
   set! back out through a closed dynamic scope on its own, and
   whatever comes AFTER this block in the same walk unit (guideline
   #7's respell-relative on a later absolute-source note) needs to see
   where this block actually left off, not revert to whatever *last-ref*
   was before it started.
   Returns [text remaining]."
  [more vars]
  (let [has-start? (= (first (first more)) :word)
        start      (when has-start? (second (first more)))
        body-tok   (if has-start? (second more) (first more))
        remaining  (if has-start? (drop 2 more) (rest more))
        seed-ref   (when start (pitch-seed-ref start))
        [inner ending-ref]
        (binding [*last-ref* seed-ref]
          [(emit-stream (second body-tok) vars true) *last-ref*])]
    (set! *last-ref* ending-ref)
    [inner remaining]))

(defn emit-stream
  "Transform a flat token list (the contents of a { } / << >> body, a
   repeat/tuplet/grace body, etc.) into musics surface text. relative? is
   whether we're inside a \\relative scope (affects pitch emission)."
  [tokens vars relative?]
  (loop [tokens tokens out []]
    (if (empty? tokens)
      (str/join " " (remove str/blank? out))
      (let [tok  (first tokens)
            more (rest tokens)
            cmd  (backslash-cmd tok)]
        (cond
          ;; LilyPond's own % comments are dropped entirely, not carried
          ;; over as musics-DSL comments -- they're LilyPond-specific
          ;; commentary (engraving notes, commented-out alternate voicings,
          ;; ...) that doesn't carry meaning in the target format.
          (= (first tok) :comment)
          (recur more out)

          ;; variable reference -- emitted as a real musics-DSL VarRef
          ;; (\name), not inlined: ly-text->mus-text hoists every
          ;; collected LilyPond variable into its own VarDef ahead of
          ;; the main content (in original definition order), so \name
          ;; here always resolves to something already defined by the
          ;; time this text is walked, same as \pianohA etc. already had
          ;; to be in the LilyPond source itself. Safe for relative
          ;; pitch too -- confirmed live, not just assumed: musics-DSL's
          ;; own relative-pitch resolution chains correctly through a
          ;; VarRef splice, identically to the same notes written
          ;; inline, so a variable's own body can be converted once,
          ;; standalone, without needing to know in advance every point
          ;; it'll later be referenced from.
          (and (= (first tok) :word) (str/starts-with? (second tok) "\\")
               (contains? vars (sanitize-name (subs (second tok) 1))))
          (recur more (conj out (str "\\" (sanitize-name (subs (second tok) 1)))))

          ;; bare variable/context assignment appearing inline: skip its value
          (and (= (first tok) :word) (assignment-name? (second tok))
               (looks-like-next-assignment? tokens))
          (let [body (drop 2 tokens)]
            (recur (drop (assignment-value-span body) body) out))

          (nil? cmd)
          (cond
            (= (first tok) :brace)
            (let [inner (emit-stream (second tok) vars relative?)]
              (recur more (if (has-content? inner)
                            (conj out (str "\n{ " inner " }"))
                            out)))

            (= (first tok) :dbl)
            (recur more (conj out (emit-voice tok vars relative?)))

            ;; A Chord's own trailing Duration/Articulation/NoteSuffix*/Tie
            ;; is a SEPARATE token in LilyPond source (glued directly onto
            ;; '>', e.g. \"<c e g>4~\") that the tokenizer never attaches to
            ;; the :chord token itself -- peeked and consumed here (only
            ;; when it actually looks like a duration/tie/slur, i.e.
            ;; starts with a digit/~/(/), so an unrelated following bar
            ;; check or \\command never gets mistaken for one) via
            ;; parse-chord-tail, which reuses convert-note-chunk's own
            ;; peel-suffix (a Chord's trailing shape is identical to a
            ;; Note's, just with no leading Pitch). Also collapses a
            ;; single-pitch \"chord\" (<e>, real LilyPond usage for visual
            ;; column alignment) to a bare Note -- our own ChordPitches
            ;; rule requires 2+ pitches (musics.ebnf), so <e> has no
            ;; direct equivalent as an actual Chord.
            (= (first tok) :chord)
            (let [raw        (remove str/blank? (str/split (str/trim (second tok)) #"\s+"))
                  ;; Chord pitches are NOT respelled relative the way a
                  ;; plain note is (guideline #7 stays a "known
                  ;; simplification" for chords -- see this ns's own
                  ;; docstring: chord-internal octave choices already
                  ;; chain via resolve-pitch/rel->midi the same way a
                  ;; sequential note stream does, not LilyPond's own
                  ;; chord-relative-to-root convention, so respelling
                  ;; each tone independently would need to replicate
                  ;; that same chaining, not just one reverse lookup).
                  ;; *last-ref* IS still updated per tone here, though --
                  ;; the real walker's own :last-pitch chains through
                  ;; every chord tone too, ending at the chord's last
                  ;; one, so a LATER absolute-source note's own
                  ;; respell-relative call needs this to stay in sync
                  ;; rather than reference a stale pre-chord ref.
                  conv       (fn [p]
                               (when-let [head (chord-pitch-token p)]
                                 (let [[letter accidental ticks] (split-pitch-token head)]
                                   (if relative?
                                     (let [[_ new-ref] (leaf/resolve-pitch [letter accidental ticks] *last-ref*)]
                                       (set! *last-ref* new-ref)
                                       (str letter accidental ticks))
                                     (let [octave (max 1 (min 8 (ticks->our-octave ticks)))
                                           [_ new-ref] (leaf/resolve-pitch [(str/upper-case letter) accidental (str octave "/")] nil)]
                                       (set! *last-ref* new-ref)
                                       (str (str/upper-case letter) accidental octave "/"))))))
                  ;; A chord entry that isn't a real pitch at all (see
                  ;; chord-pitch-token's own docstring) has no equivalent
                  ;; here -- dropped rather than letting its first
                  ;; character get blindly treated as a pitch letter.
                  pitches    (remove nil? (map conv raw))
                  pitch-text (str/join " " pitches)
                  next-txt   (word-text (first more))
                  glued?     (and next-txt (re-find #"^[0-9~()]" next-txt))
                  [tail lead-tokens] (parse-chord-tail (when glued? next-txt))
                  more'      (if glued? (rest more) more)
                  chord-text (cond
                               (empty? pitches)      nil
                               (= 1 (count pitches)) (str pitch-text tail)
                               :else                 (str "<" pitch-text ">" tail))]
              (recur more' (into out (conj (vec lead-tokens) chord-text))))

            (= (first tok) :word)
            (let [w (second tok)]
              (cond
                (= w "|") (recur more (push-barline out))
                (= w "=") (recur more out)
                (pitch-token? w)
                (if-let [emitted (convert-note-chunk w relative?)]
                  (recur more (into out emitted))
                  (recur more out))
                :else (recur more out)))

            :else (recur more out))

          ;; \relative PITCH { ... }
          (= cmd "relative")
          (let [[inner remaining] (relative-block-text more vars)]
            (recur remaining (conj out inner)))

          ;; \new TYPE [= "name"] [\with {...}] CONTENT
          (contains? #{"new" "context"} cmd)
          (let [after-type  (rest more)
                after-name  (if (= (word-text (first after-type)) "=")
                              (drop 2 after-type)
                              after-type)
                after-with  (skip-with-block after-name)
                content     (first after-with)
                remaining   (rest after-with)]
            (recur remaining (conj out (emit-voice content vars relative?))))

          (contains? noise-commands cmd)
          (recur (drop-noise-tail more) out)

          (contains? bare-drop-commands cmd)
          (recur more out)

          ;; \layout {}/\midi {}/\paper {} can appear inside \score's own
          ;; body (as siblings of the actual << >>), not just at the very
          ;; top level -- drop the command and its following block.
          (contains? #{"layout" "midi" "paper"} cmd)
          (recur (rest more) out)

          (= cmd "bar")
          (recur (rest more) (push-barline out))

          (contains? #{"clef" "partial" "addlyrics" "lyricmode" "markup"} cmd)
          (recur (rest more) out)

          ;; \time/\key/\tempo now have real, native free-standing spellings
          ;; in our own grammar (Time/Key/Tempo, musics.ebnf) -- emitted
          ;; here verbatim (or near-verbatim) instead of converting to the
          ;; !-prefixed Assignment forms, which is both simpler (no
          ;; uppercase/symbolic-accidental KeySpec conversion needed for
          ;; \key -- see below) and stays closer to the original source
          ;; spelling. \time used to emit \"!time:...\", which was never a
          ;; registered :Meter alias at all (only !Meter:/!M: are) -- a
          ;; real, confirmed bug, silently losing every imported time
          ;; signature; \key/\tempo used to work via !key:/!tempo: (both
          ;; real aliases) but native \\key/\\tempo is simpler and closer
          ;; to source regardless.
          (= cmd "time")
          (recur (rest more) (conj out (when-let [w (word-text (first more))] (str "\\time " w))))

          ;; \key's own pitch is emitted through split-pitch-token, same as
          ;; any ordinary note pitch (language-aware -- Dutch suffixes pass
          ;; through unchanged, English translates to #/b) -- our own \\key
          ;; grammar rule reads a pitch exactly the same way a Note's own
          ;; Pitch/Accidental does (see musics.ebnf's own Key comment), so
          ;; no separate uppercase/single-symbol KeySpec conversion is
          ;; needed the way the old !key: path required.
          ;; mode-cmd is only emitted when it's one of our own ModeName
          ;; alternatives (musics.ebnf) -- anything else (a mode word real
          ;; LilyPond doesn't even define, or one we don't) is dropped
          ;; rather than emitted as unparseable text, same "best-effort,
          ;; drop what has no equivalent" philosophy as every other
          ;; unrepresentable construct here.
          (= cmd "key")
          (let [pitch-tok (word-text (first more))
                mode-cmd  (backslash-cmd (second more))]
            (recur (drop 2 more)
                   (conj out (when (and pitch-tok mode-cmd (contains? known-key-modes mode-cmd))
                               (let [[letter accidental _] (split-pitch-token pitch-tok)]
                                 (str "\\key " letter accidental " \\" mode-cmd))))))

          (= cmd "tempo")
          (let [args0     more
                args1     (if (= (first (first args0)) :string) (rest args0) args0)
                dur-tok   (word-text (first args1))
                eq-tok    (word-text (second args1))
                bpm-tok   (word-text (nth args1 2 nil))
                note-val  (tempo-notevalue dur-tok)]
            (if (and note-val (= eq-tok "=") bpm-tok)
              (recur (drop 3 args1)
                     (conj out (if (= note-val "4")
                                 (str "\\tempo " bpm-tok)
                                 (str "\\tempo " note-val "=" bpm-tok))))
              (recur args1 out)))

          (contains? #{"times" "tuplet"} cmd)
          ;; \tuplet also accepts an optional unit-duration arg between the
          ;; fraction and the body (\tuplet 3/2 8 { ... }) -- purely a
          ;; bracket-grouping display hint in LilyPond, no equivalent of
          ;; our own, so just skip over it if present.
          ;; \times/\tuplet's own body reuses Sequence's own '{ }' now,
          ;; same as real LilyPond's own \times 2/3 { c8 d8 e8 } spelling
          ;; -- our grammar's earlier, dedicated Scope rule on '( )' was
          ;; removed in favor of this reuse (see musics.ebnf's own Grammar
          ;; comment), so '( )' here is stale/dead syntax now, not a
          ;; harmless alternative spelling.
          (let [factor      (word-text (first more))
                has-unit?   (not= (first (second more)) :brace)
                body-tok    (if has-unit? (nth more 2) (second more))
                consumed    (if has-unit? 3 2)
                inner       (emit-stream (second body-tok) vars relative?)]
            (recur (drop consumed more)
                   (if (has-content? inner)
                     (conj out (str "\\" cmd " " factor " { " inner " }"))
                     out)))

          (= cmd "repeat")
          ;; The repeat-type keyword is usually a bare word (\repeat volta
          ;; 2 {...}) but LilyPond also accepts it quoted (\repeat "volta"
          ;; 2 {...}, confirmed live in Beethoven's Pathétique) -- both
          ;; spell the identical keyword, just as different token shapes
          ;; (:word vs :string), so both need checking here or the quoted
          ;; form silently produced an invalid `\repeat  2 { ... }` (rtype
          ;; nil, word-text only ever recognizing :word) rather than a
          ;; parse the grammar actually accepts.
          (let [rtype         (or (word-text (first more))
                                   (let [t (first more)] (when (= (first t) :string) (second t))))
                n              (word-text (second more))
                rest-after-n   (drop 2 more)
                ;; A command can be interposed between the count and the
                ;; actual { } body (e.g. \repeat volta 2 \time 3/8 { ... }
                ;; -- LilyPond's repeated MUSIC-EXPRESSION doesn't have to
                ;; be exactly one brace). Search a short bounded window for
                ;; the brace and fold anything before it into the body's
                ;; own leading content; a bare single-token body (no brace
                ;; at all, e.g. \repeat unfold 4 c4) falls back below.
                brace-idx     (first (keep-indexed
                                        (fn [i t] (when (= (first t) :brace) i))
                                        (take 6 rest-after-n)))
                body-children (if brace-idx
                                (into (vec (take brace-idx rest-after-n))
                                      (second (nth rest-after-n brace-idx)))
                                [(first rest-after-n)])
                after-body    (drop (if brace-idx (inc brace-idx) 1) rest-after-n)
                ;; a comment often sits between the repeat body's closing
                ;; } and \alternative (e.g. "} %end of repeated section"),
                ;; which would otherwise hide the \alternative lookahead.
                lead-comments (take-while #(= (first %) :comment) after-body)
                after-cmts    (drop (count lead-comments) after-body)
                [alt-text remaining]
                (if (= (backslash-cmd (first after-cmts)) "alternative")
                  (let [alt-children (second (second after-cmts))
                        non-cmt      (remove #(= (first %) :comment) alt-children)
                        ;; multiple bracketed endings ({ {1st} {2nd} }) --
                        ;; our grammar's volta only takes one Sequence, so
                        ;; keep just the first (the "second time" ending);
                        ;; a single flat ending (no nested braces) passes
                        ;; through as-is.
                        alt-inner    (if (and (seq non-cmt) (every? #(= (first %) :brace) non-cmt))
                                       (second (first non-cmt))
                                       alt-children)
                        alt-body     (emit-stream alt-inner vars relative?)]
                    ;; An \alternative whose own body converts to nothing
                    ;; at all (a real, confirmed case: bwv-988-v16.ly uses
                    ;; spacer rests -- 's1'/'s1*3/8' -- as BOTH endings of
                    ;; several \alternative blocks, purely to keep voices
                    ;; aligned; 's' isn't a pitch/rest our own grammar has
                    ;; any equivalent for, so it converts to nothing, same
                    ;; as any other unrepresentable construct) is dropped
                    ;; entirely rather than emitted as an invalid, empty
                    ;; \alternative { }.
                    [(when (has-content? alt-body)
                       (str " \\alternative { " alt-body " }"))
                     (drop 2 after-cmts)])
                  [nil after-body])
                body-inner (emit-stream body-children vars relative?)]
            (recur remaining
                   (if (has-content? body-inner)
                     (conj out (str "\\repeat " rtype " " n " { " body-inner " }" alt-text))
                     out)))

          (contains? #{"grace" "acciaccatura" "appoggiatura" "slashedGrace" "afterGrace"} cmd)
          (let [g1        (first more)
                g2        (second more)
                remaining (drop 2 more)
                as-text   (fn [t]
                            (cond
                              (= (first t) :brace) (str "\n{ " (emit-stream (second t) vars relative?) " }")
                              ;; note text is always last (see convert-note-chunk) --
                              ;; a leading extended-dynamic token has nowhere valid
                              ;; to go in \grace's two-bare-Element grammar slot, so
                              ;; it's dropped here, same as it silently was before.
                              (= (first t) :word)  (last (convert-note-chunk (second t) relative?))
                              :else nil))]
            (recur remaining (conj out (str "\\" cmd " " (as-text g1) " " (as-text g2)))))

          ;; \transpose's own body reuses '{ }' too now, same as \times/
          ;; \tuplet above.
          (= cmd "transpose")
          (let [from-tok (transpose-pitch (first more))
                to-tok   (transpose-pitch (second more))
                body-tok (nth more 2 nil)]
            (if (and from-tok to-tok body-tok (= (first body-tok) :brace))
              (let [inner (emit-stream (second body-tok) vars relative?)]
                (recur (drop 3 more)
                       (if (has-content? inner)
                         (conj out (str "\\transpose " from-tok " " to-tok " { " inner " }"))
                         out)))
              (recur more out)))

          :else
          (recur more out))))))

(defn- expr-span
  "How many leading tokens make up ONE complete top-level music expression
   at the head of tokens -- used to split << >> children into separate
   simultaneous voices, since two voices (\\new Staff {...} \\new Staff
   {...}, or a bare {...} sitting next to a \\relative ... {...}) are
   usually just adjacent with no separator at all between them."
  [tokens]
  (let [tok (first tokens)
        cmd (backslash-cmd tok)]
    (cond
      (nil? tok) 0
      (= (first tok) :comment) 1
      (= (word-text tok) "\\\\") 1
      (contains? #{:brace :dbl} (first tok)) 1
      (= cmd "relative")
      (if (= (first (nth tokens 1 nil)) :word) 3 2)
      (contains? #{"new" "context"} cmd)
      (loop [i 1]
        (let [t (nth tokens i nil)]
          (cond
            (nil? t) i
            (= (word-text t) "=") (recur (+ i 2))
            (= (backslash-cmd t) "with") (recur (+ i 2))
            (contains? #{:brace :dbl} (first t)) (inc i)
            (and (= (first t) :word) (str/starts-with? (second t) "\\")) (inc i)
            :else (recur (inc i)))))
      ;; \set/\override/\unset/\tweak span the command + the rest of that
      ;; property statement (same span drop-noise-tail would consume).
      (contains? noise-commands cmd)
      (inc (- (count (rest tokens)) (count (drop-noise-tail (rest tokens)))))
      ;; \addlyrics {...}/\clef treble/\partial 4/etc are trailing
      ;; decorations on the PREVIOUS voice, one argument wide -- without
      ;; this they'd split off their own argument as if it were a second,
      ;; separate voice (e.g. \addlyrics { lydian } splitting "{ lydian }"
      ;; off on its own, which then gets misread as bare note content).
      (contains? #{"clef" "partial" "addlyrics" "lyricmode" "markup"} cmd) 2
      :else 1)))

(defn- split-voices [tokens]
  (loop [tokens tokens groups []]
    (if (empty? tokens)
      groups
      (let [n (max 1 (expr-span tokens))]
        (recur (drop n tokens) (conj groups (vec (take n tokens))))))))

(defn- ensure-bracketed
  "text may already start with a leading newline (emit-stream/emit-voice's
   own brace-opening cases all prepend one now, for readability -- see
   ly-text->mus-text's own docstring), so the already-bracketed check
   trims that off first.

   A raw '<<' is deliberately NOT treated as already-bracketed here, even
   though it looks self-delimiting the same way '{' is -- our grammar's
   ParElement (a Parallel's own direct children) is Context | Sequence |
   Reference | Instruction | Command | VarRef; Parallel itself is NOT one
   of those alternatives, so a bare nested << ... >> can never sit
   directly inside an outer << ... >> the way a bare { ... } can. A real,
   confirmed case in this corpus (bwv-1080-I/contrapunctusI.ly's own
   pianoPart, which nests << voices >> three deep) failed to reparse
   until this recognized '<<' as needing its own { } wrapper same as any
   other voice."
  [text]
  (if (str/starts-with? (str/triml text) "{")
    text
    (str "\n{ " text " }")))

(defn emit-voice
  "Emit one << >> voice group as a Parallel of Sequences, or a single
   voice as a bare Sequence when there's exactly one."
  [tok vars relative?]
  (cond
    (nil? tok) ""

    (= (first tok) :dbl)
    (let [children (second tok)
          ;; split-voices splits << >> content into top-level expression
          ;; spans -- a bare comment token (e.g. a commented-out %<<
          ;; left over from the original source, as this project's own
          ;; conversion fixture actually has) gets its own 1-token span,
          ;; same as any real voice does. Dropped here, before ever
          ;; converting it: emit-stream's own comment case turns it into
          ;; a non-blank string (the comment text), so remove str/blank?
          ;; below never catches it on its own, and it would otherwise
          ;; survive as an orphaned, content-less { } group -- invalid,
          ;; a Sequence needs at least one real Element. Confirmed live,
          ;; not just reasoned: this exact shape broke play-file! on a
          ;; real .ly conversion (a commented-out %<< inside a
          ;; \new StaffGroup << ... >>).
          groups   (->> (split-voices children)
                        (remove #(= (word-text (first %)) "\\\\"))
                        (remove #(every? (fn [t] (= (first t) :comment)) %)))
          raw      (remove str/blank? (map #(emit-stream % vars relative?) groups))
          voices   (map ensure-bracketed raw)]
      (cond
        (empty? voices)      ""
        (= 1 (count voices)) (first voices)
        :else                (str "<< " (str/join " " voices) " >>")))

    (= (first tok) :brace)
    (let [inner (emit-stream (second tok) vars relative?)]
      (if (has-content? inner) (str "\n{ " inner " }") ""))

    ;; A whole << >> voice that's just one variable reference -- wrapped
    ;; in a Sequence containing a VarRef, not inlined (see emit-stream's
    ;; own analogous case for the reasoning).
    (and (= (first tok) :word) (str/starts-with? (second tok) "\\")
         (contains? vars (sanitize-name (subs (second tok) 1))))
    (str "\n{ \\" (sanitize-name (subs (second tok) 1)) " }")

    :else ""))

(defn- compute-usable-vars
  "Convert every collected LilyPond variable's raw token value to musics
   text, then fixpoint-filter out any whose OWN converted body carries no
   real content (has-content?) -- repeating with the shrunk set each
   round, since a var's body is computed AGAINST the current usable set
   (a VarRef to a not-(yet-)excluded var still emits as \\name; once that
   target is excluded, recomputing correctly drops the reference instead
   of leaving it dangling).

   The fixpoint is load-bearing, not one extra round of caution: a
   variable purely made of engraving noise (\\once \\override .../\\set
   ...) converts to a blank body on the FIRST pass same as any blank
   spacer-rest variable already was, but a DIFFERENT, genuinely musical
   variable can reference it mid-phrase (bwv-988-v01.ly's own `soprano`
   variable: `... cis16 \\adjustBeamOne d16 fis16 ...`, where
   adjustBeamOne = \\once \\override Beam #'positions = ...). Computing
   every body just once against the UNFILTERED raw-vars (the original
   approach) let that reference survive into soprano's own emitted text
   even after adjustBeamOne itself was correctly dropped -- a real,
   confirmed dangling-VarRef bug (walk-var-ref's own \"referenced before
   its definition\" ex-info, at WALK time, not conversion time, since
   nothing in this converter's own pipeline re-checks a VarDef's
   existence after the fact). Recomputing against the progressively-
   filtered set until it stops shrinking closes that gap for chains of
   any depth, not just one level.
   Terminates in at most (count var-order) rounds -- each round can only
   shrink the usable set, never grow it (a var already excluded is never
   reconsidered), so it can't cycle.
   Returns [bodies usable-set]."
  [raw-vars var-order]
  (loop [usable (set var-order)]
    (let [bodies  (into {} (map (fn [name]
                                   ;; Fresh *last-duration*/*last-ref* per
                                   ;; variable, same as walk-var-def's own
                                   ;; save/reset/restore of :last-dur/
                                   ;; :last-pitch around a VarDef's body
                                   ;; (flat_tree_walker.clj) -- one
                                   ;; variable's own trailing state must
                                   ;; never leak into the NEXT variable
                                   ;; processed in this same round, same
                                   ;; walk-order-artifact bug that fix
                                   ;; itself closed.
                                   [name (binding [*last-duration* "4" *last-ref* nil]
                                           (emit-stream (get raw-vars name)
                                                        (select-keys raw-vars usable)
                                                        true))])
                                 var-order))
          usable' (set (filter #(has-content? (get bodies %)) var-order))]
      (if (= usable' usable)
        [bodies usable]
        (recur usable')))))

;; ============================================================
;; Top-level driver
;; ============================================================

(defn ly-text->mus-text
  "Convert LilyPond source text to musics DSL surface text (best effort).

   Sets !accidentals:explicit once, ahead of everything else: LilyPond's
   own input is always literal (a bare pitch letter is never affected by
   \\key -- only the printed page is), so every note converted here
   already carries an explicit accidental wherever the original source
   needed one. Pinning explicit mode keeps that true regardless of
   whatever :implied/:explicit the native format's own default happens
   to be for a hand-written piece -- imported content's meaning must
   never depend on that default.
   Written as the first child of one outer wrapping Sequence around ALL
   converted content (a .ly source can hold more than one \\score, each
   becoming its own top-level piece) -- every piece nested inside that
   wrapper still gets :accidentals :explicit through the ordinary
   ctx-chain, and stays individually addressable by its own id
   regardless of the extra nesting level (the flat repo addresses by id,
   not by path). A bare Instruction directly here is fine even though
   one at Program's own top level isn't (see musics.ebnf's own
   TopElement comment on why that's restricted -- it would write
   straight into :ROOT's own read-only context) -- this Sequence is a
   real, ordinary container, not Program's own top-level list, so an
   Instruction as one of its Elements is exactly the same shape every
   !tempo:/!key:/... in this file already is. An earlier version routed
   this through a VarDef/VarRef (`acc = (...)` / `\\acc`) specifically
   to dodge the TopElement restriction, which was solving a problem
   that direct placement here never actually had.
   Every collected LilyPond variable is re-emitted as its own musics-DSL
   VarDef, ahead of the main content, in original definition order (same
   ordering constraint
   LilyPond's own source already satisfied, since a variable must be
   defined before it's referenced in both formats) -- a \\name reference
   anywhere in the main content or in another variable's own body stays
   a real VarRef rather than being inlined/expanded away (see
   emit-stream's own comment on the reasoning: relative pitch chains
   correctly through a VarRef splice, confirmed live, so a variable's
   body can be converted once, standalone). Each variable's own body is
   converted with relative?=true regardless of whether it has its own
   \\relative wrapper -- a bare, non-\\relative-wrapped LilyPond variable
   (e.g. a short motif meant to be spliced into a surrounding \\relative
   block) is only ever meaningful in relative-pitch terms; one that DOES
   have its own \\relative PITCH { ... } wrapper handles switching into
   relative mode for its own inner content regardless of what's passed
   in here, so this default doesn't affect it either way.
   A variable whose converted body carries no real content at all --
   only bar lines/whitespace, e.g. a LilyPond spacer-rest-only variable
   used purely to park dynamics between staves (`s2 \\mf s2 |`), which
   this converter doesn't yet have a musics-DSL equivalent for -- is
   dropped entirely rather than emitted as an invalid `name = ( | )`
   VarDef (Sequence requires at least one real Element, never just a
   BarLine); dropping it from `vars` itself, not just from the emitted
   VarDef list, also makes any `\\name` reference to it fall through to
   whatever this converter already does with an unrecognized backslash
   command, instead of pointing at a VarDef that was never emitted.

   dir (optional, defaults to \".\") is the SOURCE file's own directory --
   the base every \\include in ly-text resolves relative to, per real
   LilyPond semantics (see expand-includes). A caller converting a bare
   string with no \\include in it (every existing test, a REPL one-off)
   never needs to pass it; from-ly-to-mus passes the real file's own
   parent directory."
  ([ly-text] (ly-text->mus-text ly-text "."))
  ([ly-text dir]
  (binding [*pitch-language* (or (detect-pitch-language (tokenize ly-text)) :nederlands)]
  (let [tokens               (expand-includes (tokenize ly-text) dir #{})
        [raw-vars var-order] (collect-vars tokens)
        [bodies usable]      (compute-usable-vars raw-vars var-order)
        var-order (filter usable var-order)
        vars      (select-keys raw-vars var-order)
        var-defs  (map (fn [name] (str name " = { " (get bodies name) " }")) var-order)]
    ;; Fresh *last-duration*/*last-ref* for the main top-level content --
    ;; a separate walk unit from each variable's own body (which gets its
    ;; own fresh binding in compute-usable-vars above), but ONE shared
    ;; binding across the WHOLE top-level loop (every \score, every bare
    ;; << >>/{ }, chained together) -- the real walker's own :last-pitch/
    ;; :last-dur carry across sibling content in one Sequence the same
    ;; way (no VarDef-style reset for \score specifically), and the final
    ;; output wraps everything here in exactly one outer Sequence.
    (binding [*last-duration* "4" *last-ref* nil]
    (loop [tokens tokens out []]
      (if (empty? tokens)
        (str (str/join "\n" var-defs)
             (when (seq var-defs) "\n")
             "{ !accidentals:explicit\n"
             (str/join "\n" (remove str/blank? out))
             "\n}")
        (let [tok  (first tokens)
              more (rest tokens)
              cmd  (backslash-cmd tok)]
          (cond
            ;; Dropped entirely, same as emit-stream's own :comment case --
            ;; LilyPond-specific commentary, not carried over.
            (= (first tok) :comment) (recur more out)

            (= cmd "header")
            (recur (rest more) (conj out (header-comment (second (first more)))))

            (contains? #{"version" "language" "include"} cmd)
            (recur (rest more) out)

            ;; \addQuote "name" \varname -- registers a variable under a
            ;; quotable name for later \quoteDuring cross-references
            ;; elsewhere in the score. Purely a registration, no musical
            ;; content of its own (the actual music already lives in
            ;; \varname's own VarDef, converted and playable there) --
            ;; drop all three tokens rather than treating \varname as a
            ;; second, redundant top-level reference to splice in.
            (= cmd "addQuote")
            (recur (drop 2 more) out)

            (contains? #{"paper" "layout" "midi"} cmd)
            (recur (rest more) out)

            ;; \score's body isn't necessarily a bare << >> / { } -- it's
            ;; often \context PianoStaff << ... >> \layout {} \midi {},
            ;; which emit-stream already knows how to unwrap/drop.
            (= cmd "score")
            (recur (rest more) (conj out (emit-stream (second (first more)) vars false)))

            ;; a bare top-level \relative ... { ... } with no \score
            ;; wrapper at all -- see relative-block-text's own docstring
            ;; for why this needs its own case here, not just inside
            ;; emit-stream.
            (= cmd "relative")
            (let [[inner remaining] (relative-block-text more vars)]
              (recur remaining (conj out inner)))

            ;; top-level assignment already captured by collect-vars
            (and (= (first tok) :word) (assignment-name? (second tok))
                 (looks-like-next-assignment? tokens))
            (let [body (drop 2 tokens)]
              (recur (drop (assignment-value-span body) body) out))

            ;; a bare top-level << >> or { } with no \score wrapper
            (contains? #{:dbl :brace} (first tok))
            (recur more (conj out (emit-voice tok vars false)))

            :else (recur more out))))))))))

(defn from-ly-to-mus
  "Read a LilyPond .ly file, convert it to musics DSL text (best effort),
   and write it back next to the source as a sibling <name>.mus file.
   Returns the path written to."
  [ly-path]
  (let [ly-file  (io/file ly-path)
        base     (first (str/split (.getName ly-file) #"\.ly$"))
        mus-file (io/file (.getParent ly-file) (str base ".mus"))
        mus-text (ly-text->mus-text (slurp ly-file) (or (.getParent ly-file) "."))]
    (spit mus-file mus-text)
    (.getPath mus-file)))
