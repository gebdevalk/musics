(ns input.reader.lilypond-import

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
            [input.reader.parser.leaf-parser :as leaf]))

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
;; Dutch pitch-name -> ours
;; ============================================================

(defn- split-pitch-token
  "Split a bare Dutch pitch token (no duration/suffixes) into
   [letter accidental ticks]. letter is lowercase; accidental is our
   symbol form (#, b, ##, bb, or \"\"); ticks is the raw '/, run (or \"\")."
  [tok]
  (let [tick-idx (loop [i 0]
                   (cond
                     (>= i (count tok)) i
                     (contains? #{\' \,} (.charAt ^String tok i)) i
                     :else (recur (inc i))))
        body     (subs tok 0 tick-idx)
        ticks    (subs tok tick-idx)]
    (cond
      (= body "es") ["e" "b" ticks]
      (= body "as") ["a" "b" ticks]
      (empty? body) ["c" "" ticks]
      :else
      (let [letter (subs body 0 1)
            suffix (subs body 1)]
        [letter (case suffix
                  ""     ""
                  "is"   "#"
                  "es"   "b"
                  "isis" "##"
                  "eses" "bb"
                  suffix)
         ticks]))))

(defn- ticks->our-octave
  "Absolute-mode octave digit for a tick run, per LilyPond's own
   convention: bare letter (no ticks) sits in the octave below middle C;
   each ' raises one octave, each , lowers one. Our octave 4 = middle C."
  [ticks]
  (+ 3 (- (count (filter #{\'} ticks)) (count (filter #{\,} ticks)))))

(defn- pitch-seed-midi
  "Absolute MIDI value of a \\relative START pitch (e.g. \"c''\", \"g,\")."
  [start-pitch-tok]
  (let [[letter accidental ticks] (split-pitch-token start-pitch-tok)
        octave (max 1 (min 8 (ticks->our-octave ticks)))]
    (first (leaf/resolve-pitch [(str/upper-case letter) accidental (str octave "/")] nil))))

(defn- midi->octave-digit
  "Given a resolved MIDI value and the letter/accidental it was spelled
   with, recover the octave digit our grammar would print for it."
  [midi letter accidental]
  (let [diatonic {"c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11}
        acc-off  (case accidental "" 0 "#" 1 "##" 2 "b" -1 "bb" -2 0)
        base-pc  (get diatonic (str/lower-case letter) 0)]
    (max 1 (min 8 (- (quot (- midi base-pc acc-off) 12) 1)))))

(defn- reanchor-first-note
  "Replace the first note token in an already-emitted relative-mode note
   stream with an explicit absolute pitch, recomputed against seed-midi."
  [emitted-text seed-midi]
  (let [tokens (str/split emitted-text #" +")
        idx    (first (keep-indexed
                         (fn [i t] (when (re-matches #"^[a-g][#b]{0,2}[',]*[0-9]*\.*.*$" t) i))
                         tokens))]
    (if (nil? idx)
      emitted-text
      (let [tok (nth tokens idx)
            m   (re-matches #"^([a-g])([#b]{0,2})([',]*)([0-9.]*)(.*)$" tok)]
        (if (nil? m)
          emitted-text
          (let [[_ letter accidental ticks dur suffix] m
                [midi _] (leaf/resolve-pitch [letter accidental ticks] seed-midi)
                octave   (midi->octave-digit midi letter accidental)
                new-tok  (str (str/upper-case letter) accidental octave "/" dur suffix)]
            (str/join " " (assoc (vec tokens) idx new-tok))))))))

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

(def ^:private dynamic-marks
  #{"pppp" "ppp" "pp" "p" "mp" "mf" "ffff" "fff" "ff" "f"
    "sf" "sfz" "sfp" "fp" "rfz" "sff" "sfffz"})

(defn- pitch-token? [tok]
  (boolean (re-find #"^[a-grR]" tok)))

(defn- parse-note-head
  "Pull [emitted-text remaining] off the front of a note-chunk string,
   converting the pitch (Dutch->ours, absolute-vs-relative octave form)
   and any duration/dots along with it. Returns nil if tok doesn't start
   with a recognizable pitch/rest."
  [tok relative?]
  (when-let [m (re-matches #"^(r|R|[a-g](?:is|es|isis|eses)?)([',]*)([0-9]+\.*)?(.*)$" tok)]
    (let [[_ head ticks dur rest-str] m]
      (if (contains? #{"r" "R"} head)
        [(str "r" (or dur "")) rest-str]
        (let [[letter accidental _] (split-pitch-token (str head ticks))]
          (if relative?
            [(str letter accidental ticks (or dur "")) rest-str]
            (let [octave (max 1 (min 8 (ticks->our-octave ticks)))]
              [(str (str/upper-case letter) accidental octave "/" (or dur "")) rest-str])))))))

(defn- peel-suffix
  "Try each known trailing-suffix pattern against s (a note-chunk tail).
   Returns [kind text remaining]:
     :glue  text glues directly onto the current note token (shorthand
            articulation, tie, tremolo -- all already our own syntax)
     :token text becomes its own space-separated Instruction token
     :drop  nothing emitted (text is nil), just consumes and continues
   Returns nil if s doesn't match any known suffix at all."
  [s]
  (cond
    (empty? s) nil
    (str/starts-with? s "~") [:glue "~" (subs s 1)]
    (str/starts-with? s "(") [:token "!(" (subs s 1)]
    (str/starts-with? s ")") [:token "!)" (subs s 1)]

    (re-find #"^:[0-9]+" s)
    (let [m (re-find #"^:[0-9]+" s)] [:glue m (subs s (count m))])

    :else
    (if-let [[_ _dir name rest-str] (re-matches #"^([-^_]?)\\([a-zA-Z]+)(.*)$" s)]
      (cond
        ;; Ornament/Modifier are NoteSuffixes in our grammar -- glued
        ;; directly onto the note (Note = Pitch Duration Articulation?
        ;; NoteSuffix* Tie?), not a separate Instruction like a dynamic.
        (contains? ornament-names name)     [:glue (str "\\" name) rest-str]
        (contains? articulation-names name) [:glue (str "\\" name) rest-str]
        (contains? dynamic-marks name)      [:token (str "!" name) rest-str]
        :else                               [:drop nil rest-str])
      (if-let [[_ cmd rest-str] (re-matches #"^([-^_]?\\[<>!])(.*)$" s)]
        (case (subs cmd (dec (count cmd)))
          "<" [:token "!vol<" rest-str]
          ">" [:token "!vol>" rest-str]
          "!" [:drop nil rest-str])
        (if-let [[_ shorthand rest-str] (re-matches #"^(-[-.>^_!+])(.*)$" s)]
          [:glue shorthand rest-str]
          nil)))))

(defn convert-note-chunk
  "Convert one glued LilyPond note-chunk into musics text.
   Returns a vector of emitted tokens (e.g. [\"c#4\" \"!(\"] for a note
   immediately followed by a slur-start), since a slur/dynamic suffix
   needs to be its own space-separated Instruction in our grammar. Returns
   nil if tok isn't a recognizable note/rest chunk at all."
  [tok relative?]
  (when-let [[head rest-str] (parse-note-head tok relative?)]
    (loop [s rest-str out [head]]
      (if (empty? s)
        out
        (if-let [[kind text rest-str'] (peel-suffix s)]
          (recur rest-str'
                 (case kind
                   :token (conj out text)
                   :drop  out
                   :glue  (conj (vec (butlast out)) (str (last out) text))))
          ;; unrecognized trailing garbage -- stop, drop the remainder
          out)))))

;; ============================================================
;; Variable pre-pass
;; ============================================================

(defn- top-level-keyword? [w]
  (contains? #{"\\header" "\\paper" "\\layout" "\\midi" "\\score"
               "\\version" "\\language" "\\include"} w))

(defn- assignment-name? [w]
  (boolean (re-matches #"^[A-Za-z][A-Za-z0-9]*$" w)))

(defn- word-text [tok] (when (= (first tok) :word) (second tok)))

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
   Returns {name -> [tokens]}."
  [tokens]
  (loop [remaining tokens vars {}]
    (if (empty? remaining)
      vars
      (let [t1 (first remaining)
            t2 (second remaining)]
        (if (and (= (first t1) :word) (assignment-name? (second t1))
                 t2 (= (first t2) :word) (= (second t2) "="))
          (let [body  (drop 2 remaining)
                n     (assignment-value-span body)
                value-tokens (vec (take n body))]
            (recur (drop n body) (assoc vars (second t1) value-tokens)))
          (recur (rest remaining) vars))))))

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

(defn- normalize-comment
  "A LilyPond line comment (% ...) relies on a real newline to terminate --
   but our output space-joins everything within one emit-stream call, so
   a line comment emitted inline would swallow every token after it clear
   to the next actual newline in the whole file (often the rest of the
   voice). Re-emit it as a self-terminating block comment (%{ ... %})
   instead, which is safe regardless of surrounding whitespace. An
   already-block comment passes through unchanged."
  [raw]
  (if (str/starts-with? raw "%{")
    raw
    (str "%{" (subs raw 1) " %}")))

(defn- push-barline
  "Append a bar line to out, collapsing it against an already-adjacent one
   (a bare | bar check and a \\bar \"...\" command are two different
   source constructs that can both map to \"|\" with only a comment
   between them -- our grammar's Sep only takes one BarLine per gap)."
  [out]
  (if (= (last (remove #(str/starts-with? % "%") out)) "|")
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
          (= (first tok) :comment)
          (recur more (conj out (normalize-comment (second tok))))

          ;; variable reference
          (and (= (first tok) :word) (str/starts-with? (second tok) "\\")
               (contains? vars (subs (second tok) 1)))
          (recur more (conj out (emit-stream (get vars (subs (second tok) 1)) vars relative?)))

          ;; bare variable/context assignment appearing inline: skip its value
          (and (= (first tok) :word) (assignment-name? (second tok))
               (looks-like-next-assignment? tokens))
          (let [body (drop 2 tokens)]
            (recur (drop (assignment-value-span body) body) out))

          (nil? cmd)
          (cond
            (= (first tok) :brace)
            (recur more (conj out (str "{ " (emit-stream (second tok) vars relative?) " }")))

            (= (first tok) :dbl)
            (recur more (conj out (emit-voice tok vars relative?)))

            (= (first tok) :chord)
            (let [pitches (remove str/blank? (str/split (str/trim (second tok)) #"\s+"))
                  conv    (fn [p]
                            (let [[letter accidental ticks] (split-pitch-token p)]
                              (if relative?
                                (str letter accidental ticks)
                                (str (str/upper-case letter) accidental
                                     (max 1 (min 8 (ticks->our-octave ticks))) "/"))))]
              (recur more (conj out (str "<" (str/join " " (map conv pitches)) ">"))))

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
          (let [has-start? (= (first (first more)) :word)
                start      (when has-start? (second (first more)))
                body-tok   (if has-start? (second more) (first more))
                remaining  (if has-start? (drop 2 more) (rest more))
                seed       (when start (pitch-seed-midi start))
                inner      (emit-stream (second body-tok) vars true)
                inner      (if (and seed (not= seed 60)) (reanchor-first-note inner seed) inner)]
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

          (= cmd "time")
          (recur (rest more) (conj out (when-let [w (word-text (first more))] (str "!time:" w))))

          (= cmd "key")
          (let [pitch-tok (word-text (first more))
                mode-cmd  (backslash-cmd (second more))]
            (recur (drop 2 more)
                   (conj out (when (and pitch-tok mode-cmd)
                               (let [[letter accidental _] (split-pitch-token pitch-tok)]
                                 (str "!key:" (str/upper-case letter) accidental "." mode-cmd))))))

          (= cmd "tempo")
          (let [args0     more
                args1     (if (= (first (first args0)) :string) (rest args0) args0)
                dur-tok   (word-text (first args1))
                eq-tok    (word-text (second args1))
                bpm-tok   (word-text (nth args1 2 nil))]
            (if (and dur-tok (= eq-tok "=") bpm-tok)
              (recur (drop 3 args1) (conj out (str "!tempo:" bpm-tok)))
              (recur args1 out)))

          (contains? #{"times" "tuplet"} cmd)
          ;; \tuplet also accepts an optional unit-duration arg between the
          ;; fraction and the body (\tuplet 3/2 8 { ... }) -- purely a
          ;; bracket-grouping display hint in LilyPond, no equivalent of
          ;; our own, so just skip over it if present.
          (let [factor      (word-text (first more))
                has-unit?   (not= (first (second more)) :brace)
                body-tok    (if has-unit? (nth more 2) (second more))
                consumed    (if has-unit? 3 2)]
            (recur (drop consumed more)
                   (conj out (str "\\" cmd " " factor " { "
                                  (emit-stream (second body-tok) vars relative?) " }"))))

          (= cmd "repeat")
          (let [rtype         (word-text (first more))
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
                                       alt-children)]
                    [(str (apply str (map (comp normalize-comment second) lead-comments))
                          " \\alternative { " (emit-stream alt-inner vars relative?) " }")
                     (drop 2 after-cmts)])
                  [nil after-body])]
            (recur remaining
                   (conj out (str "\\repeat " rtype " " n " { "
                                  (emit-stream body-children vars relative?) " }" alt-text))))

          (contains? #{"grace" "acciaccatura" "appoggiatura" "slashedGrace" "afterGrace"} cmd)
          (let [g1        (first more)
                g2        (second more)
                remaining (drop 2 more)
                as-text   (fn [t]
                            (cond
                              (= (first t) :brace) (str "{ " (emit-stream (second t) vars relative?) " }")
                              (= (first t) :word)  (first (convert-note-chunk (second t) relative?))
                              :else nil))]
            (recur remaining (conj out (str "\\" cmd " " (as-text g1) " " (as-text g2)))))

          (= cmd "transpose")
          (let [from-tok (transpose-pitch (first more))
                to-tok   (transpose-pitch (second more))
                body-tok (nth more 2 nil)]
            (if (and from-tok to-tok body-tok (= (first body-tok) :brace))
              (recur (drop 3 more)
                     (conj out (str "\\transpose " from-tok " " to-tok " { "
                                    (emit-stream (second body-tok) vars relative?) " }")))
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

(defn- ensure-bracketed [text]
  (if (or (str/starts-with? text "{") (str/starts-with? text "<<"))
    text
    (str "{ " text " }")))

(defn emit-voice
  "Emit one << >> voice group as a Parallel of Sequences, or a single
   voice as a bare Sequence when there's exactly one."
  [tok vars relative?]
  (cond
    (nil? tok) "{ }"

    (= (first tok) :dbl)
    (let [children (second tok)
          groups   (remove #(= (word-text (first %)) "\\\\") (split-voices children))
          raw      (remove str/blank? (map #(emit-stream % vars relative?) groups))
          voices   (map ensure-bracketed raw)]
      (if (= 1 (count voices))
        (first voices)
        (str "<< " (str/join " " voices) " >>")))

    (= (first tok) :brace)
    (str "{ " (emit-stream (second tok) vars relative?) " }")

    (and (= (first tok) :word) (str/starts-with? (second tok) "\\")
         (contains? vars (subs (second tok) 1)))
    (str "{ " (emit-stream (get vars (subs (second tok) 1)) vars relative?) " }")

    :else "{ }"))

;; ============================================================
;; Top-level driver
;; ============================================================

(defn ly-text->mus-text
  "Convert LilyPond source text to musics DSL surface text (best effort)."
  [ly-text]
  (let [tokens (tokenize ly-text)
        vars   (collect-vars tokens)]
    (loop [tokens tokens out []]
      (if (empty? tokens)
        (str/join "\n" (remove str/blank? out))
        (let [tok  (first tokens)
              more (rest tokens)
              cmd  (backslash-cmd tok)]
          (cond
            (= (first tok) :comment) (recur more (conj out (normalize-comment (second tok))))

            (= cmd "header")
            (recur (rest more) (conj out (header-comment (second (first more)))))

            (contains? #{"version" "language" "include"} cmd)
            (recur (rest more) out)

            (contains? #{"paper" "layout" "midi"} cmd)
            (recur (rest more) out)

            ;; \score's body isn't necessarily a bare << >> / { } -- it's
            ;; often \context PianoStaff << ... >> \layout {} \midi {},
            ;; which emit-stream already knows how to unwrap/drop.
            (= cmd "score")
            (recur (rest more) (conj out (emit-stream (second (first more)) vars false)))

            ;; top-level assignment already captured by collect-vars
            (and (= (first tok) :word) (assignment-name? (second tok))
                 (looks-like-next-assignment? tokens))
            (let [body (drop 2 tokens)]
              (recur (drop (assignment-value-span body) body) out))

            ;; a bare top-level << >> or { } with no \score wrapper
            (contains? #{:dbl :brace} (first tok))
            (recur more (conj out (emit-voice tok vars false)))

            :else (recur more out)))))))

(defn from-ly-to-me
  "Read a LilyPond .ly file, convert it to musics DSL text (best effort),
   and write it back next to the source as a sibling <name>.mus file.
   Returns the path written to."
  [ly-path]
  (let [ly-file  (io/file ly-path)
        base     (first (str/split (.getName ly-file) #"\.ly$"))
        mus-file (io/file (.getParent ly-file) (str base ".mus"))
        mus-text (ly-text->mus-text (slurp ly-file))]
    (spit mus-file mus-text)
    (.getPath mus-file)))
