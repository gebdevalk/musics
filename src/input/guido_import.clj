(ns input.guido-import
  "Best-effort GUIDO Music Notation (GMN) -> musics DSL text converter --
   the sibling of input.lilypond-import and input.abc-import, but for
   GUIDO (a plain-text score format from the TU Darmstadt/Grame GUIDO
   project). Every syntax claim in this file was verified live against
   the GUIDO Music Notation Format spec and Grame's own guidodoc
   reference (http://guidolib.sourceforge.io/doc/GUIDO-Music%20Notation%20Format.html,
   https://guidodoc.grame.fr/), not guessed from memory -- musics.ebnf's
   own accidental symbols (#/##/&/&&) and Parallel bracket ({ }) were
   themselves GUIDO-inspired (see musics.ebnf's own header comment), so
   this converter has an unusually close, nearly 1:1 relationship to
   its source format compared to lilypond_import.clj/abc_import.clj.

   IN SCOPE:
   - Score structure: [ ... ] a single voice; { [...], [...], ... } (each
     item itself [ ]-wrapped) parallel voices; { n1, n2, ... } (comma-
     separated bare notes, none [ ]-wrapped) a chord -- GUIDO
     disambiguates these two { }-uses entirely by the shape of what's
     inside, not a different bracket, and so does this converter (see
     read-brace-content below).
   - Notes: diatonic letters a-h (h and b are the SAME pitch class,
     confirmed live -- GUIDO's own doc: 'b being used in the
     international system, h in the german system', deliberately NOT
     German notation's usual b=B-flat/h=B-natural split); stacked
     accidentals (#/&, any count -- already musics.ebnf's own spelling,
     verbatim, no translation needed at all); an optional octave
     (integer, STICKY within the current sequence per GUIDO's own
     documented elision rule, defaulting to GUIDO's own octave 1 --
     GUIDO octave 1 contains a1, the 440Hz A, i.e. GUIDO's own middle-C
     octave -- this DSL's own C4 is ALSO the middle-C octave, so every
     GUIDO octave number is this DSL's own octave number + 3); an
     optional duration (/d, *n/d, or *n, with dots, ALSO sticky per
     GUIDO's own identical elision rule, defaulting to 1/4 -- the exact
     same default this DSL's own grammar already has). Always emitted
     absolute (uppercase letter + explicit octave digit) and with an
     explicit duration digit on every note, never relying on this
     grammar's own elision -- deliberately sidesteps any elision/
     *Ratio interaction bug the way lilypond_import.clj's own \\times/
     \\tuplet conversion once had to actively guard against (see that
     ns's own *force-explicit-duration?* docstring for the confirmed
     bug this sidesteps by construction here instead).
   - Rests (_), ties (\\tie(...)), slurs (\\slur(...)) -- GUIDO's own
     tie/slur are RANGE tags wrapping the notes they span, not a
     note-suffix character the way this DSL's own ~/(/) are; converted
     to this DSL's own glued suffixes on the first/last note of the
     range.
   - \\meter<...> (time signature, \"N/D\" or \"C\"/\"C/\" common/cut time),
     \\key<...> (either a signed sharp/flat count, e.g. \\key<-3>, or a
     tonality string whose CASE picks major/minor, e.g. \\key<\"D\">
     major vs \\key<\"d\">minor -- both confirmed live against Grame's
     own \\key tag reference), \\tempo<...> (a string that may embed a
     '[n/d] = bpm' marker) -- all converted to !Meter:/!key:/!tempo:.
   - Bar lines (|), comments (% to end of line, (* ... *) block).

   OUT OF SCOPE (dropped silently, not a bug -- matches lilypond_import/
   abc_import's own established 'known gaps documented, not silently
   mishandled' precedent): chromatic-named (cis/dis/...) and solfège
   (do/re/mi...) note-name systems -- only the diatonic a-h system is
   supported; micro-tonal accidentals ([0.5]); a free-key-string
   signature (\\key<\"g#d&\">) or any key signature this converter's own
   sharp/flat-count table (see key-signature below, shared logic with
   abc_import.clj's own) doesn't cover; every tag besides meter/key/
   tempo/tie/slur (clef, dynamics, text, instrument, layout tags, ...);
   $variables (GUIDO's own, unrelated to this DSL's \\name variables);
   compound/complex meter strings (\"3/8+2/8\"); grace notes.

   KEY-IMPLIED ACCIDENTALS -- deliberately NOT applied to bare notes
   (unlike abc_import.clj's own key-signature, which DOES imply an
   accidental onto every unmarked letter, real-staff-notation-style).
   Three separate searches against guidodoc.grame.fr and the formal
   GUIDO Music Notation Format spec came back without an explicit
   statement either way -- this isn't settled by direct citation the
   way everything else in this docstring is. The call made here rests
   on structural evidence instead: GUIDO's own note-name grammar has a
   SEPARATE chromatic system (cis/dis/fis/gis/ais, the accidental baked
   directly into the letter) sitting alongside the diatonic one this
   converter reads -- a format whose key signature already implied
   accidentals onto bare notes would have no reason to also need
   accidental-baked-in letter names as a distinct spelling system, the
   same way ABC/real staff notation never needed one. Every note this
   converter emits already carries its own explicit accidental (or
   none) taken literally from the source text, matching LilyPond's own
   convention, not ABC's -- if this call turns out wrong for some real
   GUIDO corpus, that would be the first place to look.

   PITCH SPELLING: absolute (uppercase letter + explicit octave digit),
   same deliberate scope choice abc_import.clj already documents and
   for the identical reason -- GUIDO's own note spelling is already
   fully absolute, so mapping straight to absolute sidesteps an entire
   class of relative-pitch-tracking bugs a respelling pass would need
   to get right instead."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ============================================================
;; Duration: GUIDO's own /d, *n/d, *n forms (all fractions of a whole
;; note, exactly like this DSL's own Duration digit means 1/n) -- see
;; abc_import.clj's own duration->mus for the identical "clean digit
;; match, or fall back to a whole note scaled by an explicit *Ratio
;; suffix" strategy, duplicated here rather than shared: lilypond_import
;; .clj and abc_import.clj are already independent of each other by the
;; same design (see this project's own CLAUDE.md), not an oversight.
;; ============================================================

(defn- dotted-candidates
  "Every (base dots) pair whose spelled-out value ([1/base, dotted])
   could plausibly match ratio, base a power of two 1..128, dots 0..3."
  []
  (for [base [1 2 4 8 16 32 64 128] dots (range 0 4)]
    [base dots (* (/ 1 base) (- 2 (/ 1 (long (Math/pow 2 dots)))))]))

(defn duration->mus
  "ratio (a Clojure ratio, fraction of a whole note) -> a musics-DSL
   Duration string: a bare Duration digit ('4', '8.', ...) when ratio
   matches one exactly, else a whole note (duration '1') scaled by an
   explicit *Ratio suffix ('1*5/16') -- always correct, just less
   idiomatic for a genuinely irregular length."
  [ratio]
  (if-let [[base dots] (some (fn [[b d v]] (when (= v ratio) [b d])) (dotted-candidates))]
    (str base (apply str (repeat dots ".")))
    (str "1*" (numerator ratio) "/" (denominator ratio))))

(defn- parse-guido-duration
  "GUIDO's own duration text (everything matched by note-re/rest-re's
   own dur+dots groups, e.g. \"/4\" \"*2/3\" \"*3\" \"\") -> a Clojure
   ratio, or nil when dur is blank (elided -- caller substitutes
   whatever's currently sticky). dots is applied on TOP of the parsed
   base fraction, same ×3/2 (one dot) / ×7/4 (two dots) / ×15/8 (three
   dots) convention as everywhere else in this project."
  [dur dots]
  (when-not (str/blank? dur)
    (let [base (cond
                 (str/starts-with? dur "*")
                 (let [body (subs dur 1)]
                   (if (str/includes? body "/")
                     (let [[n d] (str/split body #"/")]
                       (/ (Long/parseLong n) (Long/parseLong d)))
                     (Long/parseLong body)))

                 (str/starts-with? dur "/")
                 (/ 1 (Long/parseLong (subs dur 1))))
          ndots (count dots)]
      (if (zero? ndots)
        base
        (* base (- 2 (/ 1 (long (Math/pow 2 ndots)))))))))

;; ============================================================
;; Key signatures -- GUIDO's own \key<n> integer form, verbatim: n
;; sharps (positive) or |n| flats (negative), n=0 meaning neither.
;; This DSL's own !key: only ever takes a tonic + mode, never a raw
;; sharp/flat count, so this resolves straight to a tonic letter +
;; accidental -- NOT the {letter -> sign} map abc_import.clj's own
;; key-signature builds (that one exists to imply an accidental onto
;; every BARE letter in a piece; GUIDO's own notes already carry their
;; own explicit accidentals verbatim, so there's no bare letter here
;; that needs one implied at all). The tonic sequence itself is the
;; circle of fifths up (sharps)/down (flats) from C -- G D A E B F# C#
;; for 1..7 sharps, F Bb Eb Ab Db Gb Cb for 1..7 flats -- a genuinely
;; different table from abc_import.clj's own sharp-add-order/
;; flat-add-order (those answer 'which letters get altered in THIS
;; signature', not 'what's the tonic for N sharps/flats'), confirmed
;; live after an initial wrong reuse of that other table gave \key<-3>
;; (3 flats) as Ab major (4 flats) instead of the correct Eb major.
;; ============================================================

;; 'b' (not GUIDO's own '&') for the flat tonics below is deliberate --
;; musics.ebnf's own KeySpec rule ([A-Ga-g][b#]?...) was left on its
;; historical b/# spelling when the rest of the grammar moved to
;; GUIDO's own #/&/##/&& (see musics.ebnf's own header comment); this
;; is the one place in this whole converter that has to know that.
(def ^:private sharp-tonics ["G" "D" "A" "E" "B" "F#" "C#"])
(def ^:private flat-tonics  ["F" "Bb" "Eb" "Ab" "Db" "Gb" "Cb"])

(defn- key-signature-by-count
  [n]
  (cond
    (zero? n) "C"
    (pos? n)  (nth sharp-tonics (min 6 (dec n)))
    :else     (nth flat-tonics (min 6 (dec (- n))))))

;; ============================================================
;; Reading -- a small recursive-descent reader over the raw text
;; (GUIDO's own grammar nests brackets, unlike ABC's flat token stream,
;; but has none of LilyPond's \\relative-pitch-mode/user-defined-command
;; complexity -- a direct string-position reader is simpler here than a
;; separate tokenize-then-parse pass would be).
;; ============================================================

(def ^:private note-re
  #"(?i)^([a-h])([#&]*)([0-9]+)?((?:\*[0-9]+(?:/[0-9]+)?)|(?:/[0-9]+))?(\.*)")

(def ^:private rest-re
  #"^_((?:\*[0-9]+(?:/[0-9]+)?)|(?:/[0-9]+))?(\.*)")

(defn- skip-ws-comments
  "Advance s past any run of whitespace, '%' line comments, and
   '(* ... *)' block comments -- GUIDO's own two comment forms,
   confirmed live against the GUIDO Music Notation Format spec."
  [^String s]
  (loop [s s]
    (cond
      (and (seq s) (Character/isWhitespace (.charAt s 0)))
      (recur (subs s 1))

      (str/starts-with? s "%")
      (recur (str/replace-first s #"^[^\n]*\n?" ""))

      (str/starts-with? s "(*")
      (let [end (str/index-of s "*)" 2)]
        (recur (subs s (+ 2 (or end (- (count s) 2))))))

      :else s)))

(defn- read-quoted
  "s starts with '\"' -- returns [text rest] with text unquoted."
  [^String s]
  (let [end (or (str/index-of s "\"" 1) (count s))]
    [(subs s 1 end) (subs s (min (count s) (inc end)))]))

(defn- read-params
  "s starts right after a tag's own opening '<' -- returns [params
   rest-after->], params a vector of raw (already-trimmed, already-
   unquoted-if-quoted) param strings."
  [s]
  (loop [s s params []]
    (let [s (skip-ws-comments s)]
      (cond
        (str/blank? s) [params s]
        (str/starts-with? s ">")
        [params (subs s 1)]
        (str/starts-with? s "\"")
        (let [[text rest] (read-quoted s)
              rest (skip-ws-comments rest)
              rest (if (str/starts-with? rest ",") (subs rest 1) rest)]
          (recur rest (conj params text)))
        :else
        (let [whole (re-find #"^[^,>]+" s)
              rest (subs s (count (or whole "")))
              rest (skip-ws-comments rest)
              rest (if (str/starts-with? rest ",") (subs rest 1) rest)]
          (if whole
            (recur rest (conj params (str/trim whole)))
            [params (subs s 1)]))))))

(declare read-symbols read-brace-content)

(defn- read-tag
  "s starts with '\\' -- returns [{:type :tag :name :params :range}
   rest]. GUIDO's own four tag shapes (\\id, \\id<params>, \\id(range),
   \\id<params>(range)), confirmed live against Grame's own reference:
   params always angle-bracketed, a range (the symbols the tag applies
   over) always parenthesized -- never the other way around."
  [s]
  (let [[_ name] (re-find #"^\\([a-zA-Z][a-zA-Z0-9]*)" s)]
    (if-not name
      [{:type :word} (subs s 1)]
      (let [after (subs s (inc (count name)))
            s1    (skip-ws-comments after)
            [params s2] (if (str/starts-with? s1 "<")
                          (read-params (subs s1 1))
                          [nil s1])
            s2'   (skip-ws-comments s2)
            [range-nodes s3] (if (str/starts-with? s2' "(")
                               (read-symbols (subs s2' 1) \))
                               [nil s2'])]
        [{:type :tag :name (str/lower-case name) :params params :range range-nodes} s3]))))

(defn- read-symbols
  "Read notes/rests/tags/bars/nested groups from s until close (a
   single char, e.g. \\] or \\)) is consumed, or s runs out (best-
   effort: an unterminated group still returns whatever it read rather
   than throwing, same tolerant philosophy lilypond_import.clj/
   abc_import.clj already have). Returns [nodes rest-after-close]."
  [s close]
  (loop [s s nodes []]
    (let [s (skip-ws-comments s)]
      (cond
        (str/blank? s) [nodes s]
        (= (.charAt ^String s 0) close) [nodes (subs s 1)]

        (str/starts-with? s "[")
        (let [[inner rest] (read-symbols (subs s 1) \])]
          (recur rest (conj nodes {:type :seq :children inner})))

        (str/starts-with? s "{")
        (let [[items rest] (read-brace-content (subs s 1))]
          (recur rest (conj nodes {:type :brace :items items})))

        (str/starts-with? s "\\")
        (let [[tag rest] (read-tag s)]
          (recur rest (conj nodes tag)))

        (str/starts-with? s "|")
        (recur (subs s 1) (conj nodes {:type :bar}))

        (re-find rest-re s)
        (let [[whole dur dots] (re-find rest-re s)]
          (recur (subs s (count whole)) (conj nodes {:type :rest :dur dur :dots dots})))

        (re-find note-re s)
        (let [[whole letter accs octave dur dots] (re-find note-re s)]
          (recur (subs s (count whole))
                 (conj nodes {:type :note :letter (str/lower-case letter)
                              :accs accs :octave octave :dur dur :dots dots})))

        ;; $variables (definition or reference) -- out of scope, drop
        (str/starts-with? s "$")
        (let [whole (re-find #"^\$[a-zA-Z_][a-zA-Z0-9_]*(?:\s*=\s*(?:\"[^\"]*\"|[^\s\[\]{}()]+))?" s)]
          (recur (subs s (count (or whole "$"))) nodes))

        :else
        (recur (subs s 1) nodes)))))

(defn- read-brace-content
  "s starts right after '{' -- returns [items rest-after-}]. Each item
   is either {:type :seq :children [...]} (a voice, when it starts with
   '[') or {:type :note ...} (a chord tone, when it doesn't) -- GUIDO's
   own chord-vs-voice disambiguation, entirely by shape (see this ns's
   own header comment)."
  [s]
  (loop [s s items []]
    (let [s (skip-ws-comments s)]
      (cond
        (str/blank? s) [items s]
        (str/starts-with? s "}") [items (subs s 1)]

        (str/starts-with? s "[")
        (let [[inner rest] (read-symbols (subs s 1) \])
              rest (skip-ws-comments rest)
              rest (if (str/starts-with? rest ",") (subs rest 1) rest)]
          (recur rest (conj items {:type :seq :children inner})))

        (re-find note-re s)
        (let [[whole letter accs octave dur dots] (re-find note-re s)
              rest (subs s (count whole))
              rest (skip-ws-comments rest)
              rest (if (str/starts-with? rest ",") (subs rest 1) rest)]
          (recur rest (conj items {:type :note :letter (str/lower-case letter)
                                   :accs accs :octave octave :dur dur :dots dots})))

        :else (recur (subs s 1) items)))))

(defn- read-score
  "raw GUIDO text -> one top-level node ({:type :seq ...} or {:type
   :brace ...}). A bare score with no wrapping [ ] at all (some real
   files omit it for a single voice) is read as an implicit sequence."
  [text]
  (let [s (skip-ws-comments text)]
    (cond
      (str/starts-with? s "[")
      (let [[inner _] (read-symbols (subs s 1) \])]
        {:type :seq :children inner})

      (str/starts-with? s "{")
      (let [[items _] (read-brace-content (subs s 1))]
        {:type :brace :items items})

      :else
      (let [[inner _] (read-symbols s \u0000)]
        {:type :seq :children inner}))))

;; ============================================================
;; Pitch spelling
;; ============================================================

(def ^:private guido-letter->mus-letter
  "GUIDO's own 'h' is the SAME pitch class as 'b' (confirmed live, see
   this ns's own header comment), but musics.ebnf's own PitchLetterAbs
   is exactly [A-G] -- no H at all -- so 'h' has to be normalized to
   'b' here, not just upper-cased directly onto an invalid letter."
  {"h" "b"})

(defn- note->pitch-text
  "One note node -> musics-DSL absolute Pitch text (uppercase letter +
   accidental? + octave digit) -- GUIDO's own accidental spelling (#/&,
   stackable) is already musics.ebnf's own, verbatim, no translation at
   all. octave is the RESOLVED (already-defaulted/inherited) GUIDO
   octave number -- converted by +3, since GUIDO's own octave 1 is this
   DSL's own octave 4 (both are the middle-C octave, see this ns's own
   header comment).
   The trailing '/' after the octave digit is NOT optional here --
   musics.ebnf's own OctaveAbs only omits it safely when nothing
   digit-shaped immediately follows, and this fn's own caller always
   appends an explicit Duration digit right after this text (this ns
   never relies on this grammar's own duration elision, see the header
   comment on why). Without it, OctaveAbs's own regex fails to match an
   octave digit immediately followed by another digit, and the whole
   thing silently reparses as no-octave (defaulting to this DSL's own
   octave 4) plus a wrong, mashed-together Duration instead of a parse
   error -- confirmed live against abc_import.clj's own identical fix,
   not hypothetical here."
  [{:keys [letter accs]} octave]
  (str (str/upper-case (get guido-letter->mus-letter letter letter)) accs (+ octave 3) "/"))

;; ============================================================
;; Emit -- one top-level node -> musics-DSL text. State ({:octave
;; :duration}) threads explicitly through recursive calls rather than
;; via dynamic vars (unlike lilypond_import.clj's own imperative,
;; token-stream-consuming style, this reader already produces a real
;; tree, so ordinary Clojure state-threading is the natural fit -- see
;; abc_import.clj's own tokens->mus-body for the same plain-loop-state
;; style, no dynamic vars there either). Reset to GUIDO's own defaults
;; (octave 1, duration 1/4) at the start of EVERY [ ] sequence, not
;; inherited from any enclosing one -- GUIDO's own documented elision
;; rule is explicitly per-sequence ('the last octave/duration specified
;; in the CURRENT sequence'), confirmed live against the spec, not
;; assumed from this DSL's own (different, walk-wide) convention.
;; ============================================================

(def ^:private default-state {:octave 1 :duration 1/4})

(defn- resolve-note
  "node (:note) + state -> [pitch-text duration-text state'] -- octave/
   duration explicitly resolved (inherited from state when elided,
   parsed and sticking forward in state' otherwise), both ALWAYS
   printed explicitly in the output (see this ns's own header comment
   on why -- never relies on this grammar's own elision)."
  [{:keys [octave dur dots] :as node} state]
  (let [oct  (if (str/blank? octave) (:octave state) (Long/parseLong octave))
        drat (or (parse-guido-duration dur dots) (:duration state))]
    [(note->pitch-text node oct) (duration->mus drat)
     (assoc state :octave oct :duration drat)]))

(defn- resolve-rest
  [{:keys [dur dots]} state]
  (let [drat (or (parse-guido-duration dur dots) (:duration state))]
    [(duration->mus drat) (assoc state :duration drat)]))

(declare emit-symbols emit-brace)

(defn- emit-tag
  "One :tag node -> [text state']. Only meter/key/tempo (standalone,
   value-setting instructions) and tie/slur (note-suffix-glued ranges)
   are handled -- every other tag name is dropped silently (see this
   ns's own header comment)."
  [{:keys [name params range]} state]
  (case name
    "meter"
    (let [v (first params)]
      [(when v
         (str "!Meter:" (cond (or (= v "C") (= v "c")) "4/4"
                               (or (= v "C/") (= v "c/")) "2/2"
                               :else v)))
       state])

    "key"
    (let [v (first params)]
      [(when v
         (str "!key:"
              (if (re-matches #"[+-]?[0-9]+" v)
                (str (key-signature-by-count (Long/parseLong v)) ".major")
                (let [[_ letter acc] (re-matches #"(?i)([a-h])([#&])?" v)]
                  (when letter
                    (str (str/upper-case (get guido-letter->mus-letter (str/lower-case letter) letter))
                         (get {"&" "b" "#" "#"} acc "")
                         "." (if (= letter (str/lower-case letter)) "minor" "major")))))))
       state])

    "tempo"
    (let [text (str/join " " params)
          [_ _n _d bpm] (re-find #"\[(\d+)/(\d+)\]\s*=\s*(\d+)" (or text ""))
          bpm   (or bpm (some #(re-find #"^\d+$" %) params))]
      [(when bpm (str "!tempo:" bpm)) state])

    "tie"
    (let [[inner _] (emit-symbols range state)]
      [(when (seq inner)
         (str (str/join " " (butlast inner))
              (when (> (count inner) 1) " ")
              (some-> (last inner) (str "~"))))
       state])

    "slur"
    (let [[inner _] (emit-symbols range state)]
      [(when (seq inner)
         (str/join " "
                    (-> (vec inner)
                        (update 0 #(str % "("))
                        (update (dec (count inner)) #(str % ")")))))
       state])

    [nil state]))

(defn- emit-symbols
  "nodes (from read-symbols) + starting state -> [text-fragments-vec
   final-state]."
  [nodes state]
  (reduce
    (fn [[out state] node]
      (case (:type node)
        :note (let [[pitch dur state'] (resolve-note node state)]
                [(conj out (str pitch dur)) state'])
        :rest (let [[dur state'] (resolve-rest node state)]
                [(conj out (str "r" dur)) state'])
        :bar  [(conj out "|") state]
        :seq  (let [[inner _] (emit-symbols (:children node) default-state)]
                [(if (seq inner) (conj out (str "[ " (str/join " " inner) " ]")) out) state])
        :brace (let [text (emit-brace node)]
                 [(if text (conj out text) out) state])
        :tag  (let [[text state'] (emit-tag node state)]
                [(if (str/blank? text) out (conj out text)) state'])
        [out state]))
    [[] state]
    nodes))

(defn- emit-brace
  "One :brace node -> musics-DSL text, or nil if it converts to
   nothing. { [seq1], [seq2], ... } -> { [ ... ] [ ... ] } (Parallel,
   the exact same bracket GUIDO itself uses -- see this ns's own header
   comment); { note1, note2, ... } -> <note1 note2 ...>duration (a
   Chord), duration taken from the FIRST tone's own (state threads
   through chord tones left to right same as any other sequence
   content, GUIDO's own elision rule carrying no chord-specific
   exception -- see this ns's own header comment)."
  [{:keys [items]}]
  (if (every? #(= (:type %) :seq) items)
    (let [voices (keep (fn [{:keys [children]}]
                          (let [[inner _] (emit-symbols children default-state)]
                            (when (seq inner) (str "[ " (str/join " " inner) " ]"))))
                        items)]
      (when (seq voices) (str "{ " (str/join " " voices) " }")))
    (let [[pitches _ dur]
          (reduce (fn [[pitches state dur] item]
                    (let [[pitch this-dur state'] (resolve-note item state)]
                      [(conj pitches pitch) state' (or dur this-dur)]))
                  [[] default-state nil]
                  (filter #(= (:type %) :note) items))]
      (when (seq pitches)
        (str "<" (str/join " " pitches) ">" dur)))))

;; ============================================================
;; Top-level driver
;; ============================================================

(defn guido-text->mus-text
  "Convert GUIDO Music Notation text (one score) to musics DSL surface
   text (best effort) -- see this ns's own docstring for exactly what's
   handled. Everything is wrapped in one named Sequence so the result
   is always a single, directly addressable id, same shape lilypond_
   import.clj/abc_import.clj already use for their own top-level
   output. !accidentals:explicit is NOT needed here the way it is for
   those two -- GUIDO notes are converted with every accidental (or
   lack of one) already fully resolved and printed explicitly (see
   note->pitch-text), so there's no bare, key-implied letter for this
   DSL's own :implied default to affect either way."
  [guido-text]
  (let [score (read-score guido-text)
        body  (case (:type score)
                :seq   (let [[inner _] (emit-symbols (:children score) default-state)]
                         (str/join " " inner))
                :brace (or (emit-brace score) ""))]
    (str "[ guido1:\n" body "\n]")))

(defn guido-to-mus
  "Read a GUIDO .gmn/.guido file, convert it to musics DSL text (best
   effort), and write it back next to the source as a sibling
   <name>.mus file. Returns the path written to -- same contract as
   input.lilypond-import/from-ly-to-mus and input.abc-import/
   abc-to-mus."
  [guido-path]
  (let [guido-file (io/file guido-path)
        base       (first (str/split (.getName guido-file) #"\.(gmn|guido)$"))
        mus-file   (io/file (.getParent guido-file) (str base ".mus"))
        mus-text   (guido-text->mus-text (slurp guido-file))]
    (spit mus-file mus-text)
    (.getPath mus-file)))
