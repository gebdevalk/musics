;; music_parser.clj
;; Clojure port of the pymusics input parser.
;; Tokenizes the LilyPond-like text notation using regex patterns
;; ported from regex.py, then builds domain objects using
;; core.domain.music-domain records (Leaf, Rest, Drum, Composite, etc.).
;;
;; Usage: (parse text)
;;   Returns {:score Composite, :tokens [Part ...]} map.

(ns input.reader.parser.music-parser
  (:require [clojure.string :as str]
            [core.domain.music-domain :as d]))

;; ============================================================
;; Regex patterns (ported from regex.py)
;; ============================================================

(def ^:private NAME          #"[a-zA-Z][a-zA-Z0-9_]*")
(def ^:private EQUALS        #"\s*=\s*")
(def ^:private INT           #"[0-9]+")
(def ^:private FLOAT         #"[0-9]+\.[0-9]+")
(def ^:private STRING        #"\"[^\"]*\"")

(def ^:private PITCH_NAME    #"[A-G][1-8]|[a-g]|p")
(def ^:private ACCIDENTAL    #"[b#]{0,2}|n+")
(def ^:private OCTAVE        #"[']*")

(def ^:private DURATION      #"longa|breve|\d{1,3}\.*")
(def ^:private ARTICULATION  #"-[.>^_!+]")
(def ^:private TIE            #"~")

;; --- Whole-unit patterns ---
(def ^:private PITCH_UNIT
  (re-pattern (str PITCH_NAME ACCIDENTAL OCTAVE)))

(def ^:private CHORD_CORE
  (re-pattern (str "<(?!<)(" PITCH_UNIT "(?:\\s+" PITCH_UNIT ")*?)>")))

(def ^:private MODIFIER
  (re-pattern (str "\\\\(?:" NAME ")(?:" EQUALS "(?:" FLOAT "|" INT "|" NAME "|" STRING "))?")))

(def ^:private MODIFIERS
  (re-pattern (str "(?:" MODIFIER ")*")))

;; --- Main leaf patterns (first-pass matching) ---

;; NOTE: (PITCH_UNIT) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def ^:private NOTE_RE
  (re-pattern (str "(" PITCH_UNIT ")(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; CHORD: (CHORD_CORE) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def ^:private CHORD_RE
  (re-pattern (str CHORD_CORE "(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; REST: r(DURATION)?
(def ^:private REST_RE  #"r(longa|breve|\d{1,3}\.*)?")

;; DRUM: x (DURATION)? (NAME|INT)
(def ^:private DRUM_RE
  (re-pattern (str "x(" DURATION ")?(" NAME "|" INT ")")))

;; --- Instruction patterns ---
(def ^:private BANG_CONST_RE
  (re-pattern (str "!\\s*(" NAME ")")))

(def ^:private ASSIGN_INT_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" INT ")")))

(def ^:private ASSIGN_FLOAT_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" FLOAT ")")))

(def ^:private ASSIGN_CONST_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" NAME ")")))

(def ^:private ASSIGN_STRING_RE
  (re-pattern (str "!\\s*(" NAME ")" EQUALS "(" STRING ")")))

;; --- Constant keywords (ported from lexer.py CONST_KEYWORD) ---
(def ^:private CONST_KEYWORD_RE
  (re-pattern
   (str "!silence|!pppp|!ppp|!pp|!p|!mp|!mf|!f|!ff|!fff|!ffff"
        "|!cresc|!decresc|!dim|!sfz|!fp"
        "|!left|!center|!right|!near|!far"
        "|!stageLeft|!stageCenter|!stageRight"
        "|!largo|!lento|!adagio|!andante|!moderato|!allegro"
        "|!vivace|!presto|!prestissimo"
        "|!rit|!acc|!rubato"
        "|!straight|!swing|!shuffle"
        "|!jazz|!latin|!rock|!classical|!swingFeel"
        "|!DC|!DS|!Segno|!Coda|!ToCoda|!Fine"
        "|!DC_al_Fine|!DS_al_Coda"
        "|!repeatStart|!repeatEnd"
        "|!\\(|!\\)"
        "|!pedOn|!pedOff|!unaCorda|!treCorde|!sostPed"
        "|!commonTime|!cutTime"
        "|!key:[A-G][b#]?")))

;; --- Composite delimiters ---
(def ^:private SEQ_OPEN      #"\[")
(def ^:private SEQ_CLOSE     #"\]")
(def ^:private PAR_OPEN      #"<<")
(def ^:private PAR_CLOSE     #">>")
(def ^:private LIST_OPEN     #"\(")
(def ^:private LIST_CLOSE    #"\)")
(def ^:private ALGO_OPEN     #"@\(")
(def ^:private ALGO_CLOSE    #"\)")
(def ^:private DATA_OPEN     #"'\[")
(def ^:private DATA_CLOSE    #"\]'")

;; --- Single-quote list: '( ... ) ---
(def ^:private QUOTE_OPEN    #"'\(")
(def ^:private QUOTE_CLOSE   #"\)")

;; ============================================================
;; Token classification — vector-of-pairs + some (replaces cond)
;; ============================================================

(def ^:private token-classifiers
  "Ordered priority list of [regex-pattern, token-type-kw] pairs.
   First match wins. Tested in sequence — assignments first,
   then delimiters, then bang constants, then leaf patterns,
   then primitives, then bare names."
  [[ASSIGN_STRING_RE  :ASSIGN_STRING]
   [ASSIGN_FLOAT_RE   :ASSIGN_FLOAT]
   [ASSIGN_INT_RE     :ASSIGN_INT]
   [ASSIGN_CONST_RE   :ASSIGN_CONST]
   [SEQ_OPEN          :SEQ]
   [SEQ_CLOSE         :SEQ_CLOSE]
   [PAR_OPEN          :PAR]
   [PAR_CLOSE         :PAR_CLOSE]
   [ALGO_OPEN         :ALGO]
   [DATA_OPEN         :DATA]
   [DATA_CLOSE        :DATA_CLOSE]
   [LIST_OPEN         :LIST]
   [LIST_CLOSE        :LIST_CLOSE]
   [QUOTE_OPEN        :QUOTE]
   [QUOTE_CLOSE       :QUOTE_CLOSE]
   [CONST_KEYWORD_RE  :BANG_CONST]
   [BANG_CONST_RE     :BANG_CONST]
   [NOTE_RE           :NOTE]
   [CHORD_RE          :CHORD]
   [REST_RE           :REST]
   [DRUM_RE           :DRUM]
   [FLOAT             :FLOAT]
   [INT               :INT]
   [STRING            :STRING]
   [NAME              :TYPE]])

(defn classify-token
  "Given a raw token string, return [type-kw value].
   Uses vector-of-pairs classifiers — first regex match wins."
  [s]
  (or (some (fn [[re tag]] (when (re-matches re s) [tag s]))
            token-classifiers)
      [:UNKNOWN s]))

;; ============================================================
;; Tokenizer
;; ============================================================

(def ^:private TOKEN_PATTERN
  "Master regex that matches any token at the top level.
   Order is significant — longer/more-specific patterns first."
  (re-pattern
   (str
    ;; Composite openers (longer first: DATA_OPEN before SEQ_OPEN, ALGO before LIST)
    "'\\[|@\\(|'\\("      ; DATA_OPEN, ALGO_OPEN, QUOTE_OPEN
    "|<<|\\["             ; PAR_OPEN, SEQ_OPEN
    "|\\("                ; LIST_OPEN

    ;; Composite closers
    "|\\]'|>>|\\]|\\)"   ; DATA_CLOSE, PAR_CLOSE, SEQ_CLOSE, LIST_CLOSE

    ;; Leaves: chords first (they contain < >)
    "|<(?!<)[^>]*?>[a-zA-Z0-9.*\\-^_!+\\\\~]*"  ; CHORD
    "|x\\d+[a-zA-Z0-9.]*"                         ; DRUM
    "|r(longa|breve|\\d{1,3}\\.*)?"               ; REST

    ;; Notes with full modifiers
    "|[a-gA-G][b#n]{0,2}'*[a-zA-Z0-9.*\\-^_!+\\\\~]*" ; NOTE

    ;; Assignments (before plain names)
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*\\s*=\\s*\"[^\"]*\"" ; ASSIGN_STRING
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*\\s*=\\s*[0-9]+\\.[0-9]+" ; ASSIGN_FLOAT
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*\\s*=\\s*[0-9]+"    ; ASSIGN_INT
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*\\s*=\\s*[a-zA-Z][a-zA-Z0-9_]*" ; ASSIGN_CONST

    ;; Bang constants (must be before plain numbers/names)
    "|!" (str CONST_KEYWORD_RE)

    ;; Primitives
    "|[0-9]+\\.[0-9]+"   ; FLOAT
    "|[0-9]+"            ; INT
    "|\"[^\"]*\""        ; STRING
    "|[a-zA-Z][a-zA-Z0-9_]*" ; TYPE / NAME (operation, plain name)
    )))

(defn tokenize
  "Split input text into a sequence of classified token maps.
   Returns a lazy seq of {:type :NOTE, :value \"c4\"}."
  [text]
  (->> (re-seq TOKEN_PATTERN text)
       (map (fn [m]
              (let [raw (if (coll? m) (first m) m)]
                (zipmap [:type :value] (classify-token raw)))))))

;; ============================================================
;; Pitch parsing helpers (ported from regex.py parse_pitch)
;; ============================================================

(defn parse-pitch
  "Split a pitch string like 'C#4' or 'a#' into [name accidental octave]."
  [pitch-str]
  (when-let [m (re-matches #"([A-G][1-8]|[a-g])?([b#n]{0,2})('*)" pitch-str)]
    [(or (nth m 1) "") (nth m 2) (nth m 3)]))

(defn parse-pitches
  "Split chord content '<C E G>' into individual pitch tuples."
  [chord-content]
  (let [inner (str/replace chord-content #"^<|>$" "")]
    (keep parse-pitch (str/split inner #"\s+"))))

;; ============================================================
;; Duration helpers
;; ============================================================

(defn parse-duration
  "Convert a duration string ('4', '2.', '8..') to a rational.
   longa = 4, breve = 2, otherwise n = 1/n with dots adding half each."
  [s]
  (cond
    (nil? s) nil
    (= s "longa") 4
    (= s "breve") 2
    :else
    (let [dots (count (take-while #{\\.} (str/replace s #"[^.]+" "")))
          n    (Integer/parseInt (str/replace s #"\\.+" ""))]
      (loop [val (/ 1 n)
             i dots]
        (if (zero? i)
          val
          (recur (+ val (/ val 2)) (dec i)))))))

;; ============================================================
;; Modifier parsing (ported from regex.py parse_modifiers)
;; ============================================================

(def ^:private MODIFIER_RE_SINGLE
  #"\\([a-zA-Z][a-zA-Z0-9_]*)(?:\s*=\s*([a-zA-Z][a-zA-Z0-9_]*|\d+\.\d+|\d+|\"[^\"]*\"))?")

(defn parse-modifiers
  "Split '\\vol=80\\tempo=120' into [[key val] ...] pairs."
  [s]
  (when s
    (for [m (re-seq MODIFIER_RE_SINGLE s)]
      (let [key (or (nth m 1) "")
            val (nth m 2)]
        [key val]))))

;; ============================================================
;; Instruction parsing
;; ============================================================

(defn parse-bang-const
  "Parse !mf / !cresc etc. Returns a context map with :instruction and :const."
  [s]
  (let [kw (keyword (subs (str/replace s #"\s+" "") 1))]
    {:type :instruction :const kw :raw s}))

(def ^:private ASSIGN_RE
  #"!\s*([a-zA-Z][a-zA-Z0-9_]*)\s*=\s*(.*)")

(defn parse-assignment
  "Parse !art=80 / !pan=0.0 / !vol=mf / !timbre=\"piano\"
   into {:type :assignment :key :art :val 80 :raw ...}"
  [s]
  (when-let [m (re-matches ASSIGN_RE s)]
    (let [key     (keyword (nth m 1))
          raw-val (nth m 2)
          val     (cond
                    (re-matches INT raw-val)    (Integer/parseInt raw-val)
                    (re-matches FLOAT raw-val)  (Double/parseDouble raw-val)
                    (re-matches STRING raw-val) (subs raw-val 1 (dec (count raw-val)))
                    :else                       (keyword raw-val))]
      {:type :assignment :key key :val val :raw s})))

;; ============================================================
;; Composite stack management
;; ============================================================

(defn push-container
  "Create a new container inheriting the parent's context.
   Pushes onto the stack.
   - :SEQ, :PAR, :ALGO, :DATA, :QUOTE → Composite
   - :LIST → Transient"
  [stack container-type id]
  (let [parent      (peek stack)
        parent-ctx  (when parent (:context parent))
        ctx         (d/context parent-ctx)]
    (if (= container-type :LIST)
      (conj stack (d/transient* container-type id ctx))
      (conj stack (d/composite container-type id ctx)))))

(defn pop-and-collect
  "Pop the top container and add it (or its children) to the parent.
   Returns [new-stack popped-result] — result is nil when parent exists
   (container absorbed), or the popped container when it's the root."
  [stack]
  (let [current    (peek stack)
        rest-stack (pop stack)
        parent     (peek rest-stack)]
    (if (nil? parent)
      [rest-stack current]
      (do (if (d/transient? current)
            (doseq [child (d/transient-children current)]
              (d/composite-append parent child))
            (d/composite-append parent current))
          [rest-stack nil]))))

;; ============================================================
;; Main parser dispatch
;; ============================================================

(defn parse
  "Parse a text string into domain objects.
   Returns {:score Composite, :tokens [Part ...]}.

   Example:
     (parse \"[c4 d4 e4] f4 g4\")"
  [text]
  (let [tokens    (tokenize text)
        init-ctx  (d/context-root {"tempo" 120 "volume" 0.8})]
    (loop [remaining tokens
           stack     (vector (d/make-score init-ctx))
           results   []]
      (if-let [{:keys [type value]} (first remaining)]
        (let [current-ctx (:context (peek stack))]
          (case type
            ;; --- Composite openers ---
            (:SEQ :PAR :LIST :ALGO :DATA :QUOTE)
            (recur (rest remaining)
                   (push-container stack type nil)
                   results)

            ;; --- Composite closers ---
            (:SEQ_CLOSE :PAR_CLOSE :LIST_CLOSE :DATA_CLOSE :QUOTE_CLOSE)
            (let [[new-stack result] (pop-and-collect stack)]
              (if result
                (recur (rest remaining) new-stack (conj results result))
                (recur (rest remaining) new-stack results)))

            ;; --- String ID: record as string-id ---
            :STRING
            (let [id (subs value 1 (dec (count value)))]
              (recur (rest remaining) stack
                     (conj results {:type :string-id :value id})))

            ;; --- Instructions ---
            :BANG_CONST
            (let [parsed (parse-bang-const value)]
              (recur (rest remaining) stack (conj results parsed)))

            (:ASSIGN_INT :ASSIGN_FLOAT :ASSIGN_CONST :ASSIGN_STRING)
            (let [parsed (parse-assignment value)]
              (recur (rest remaining) stack (conj results parsed)))

            ;; --- Leaves: produce domain records ---
            :NOTE
            (let [result (if-let [m (re-matches NOTE_RE value)]
                           (let [pitch        (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)]
                             (d/leaf value
                                     (or current-ctx (d/context))
                                     (parse-duration duration)
                                     []  ;; TODO: resolve pitch to MIDI
                                     (when articulation (subs articulation 1))
                                     nil
                                     (parse-modifiers modifiers)
                                     (boolean tie)))
                           {:type :parse-error :value value})]
              (recur (rest remaining) stack (conj results result)))

            :CHORD
            (let [result (if-let [m (re-matches CHORD_RE value)]
                           (let [chord-core   (nth m 1)
                                 duration     (nth m 2)
                                 articulation (nth m 3)
                                 modifiers    (nth m 4)
                                 tie          (nth m 5)]
                             (d/leaf value
                                     (or current-ctx (d/context))
                                     (parse-duration duration)
                                     []  ;; TODO: resolve chord pitches to MIDI
                                     (when articulation (subs articulation 1))
                                     nil
                                     (parse-modifiers modifiers)
                                     (boolean tie)))
                           {:type :parse-error :value value})]
              (recur (rest remaining) stack (conj results result)))

            :REST
            (let [m   (re-matches REST_RE value)
                  dur (when m (nth m 1))]
              (recur (rest remaining) stack
                     (conj results
                           (d/rest* value
                                    (or current-ctx (d/context))
                                    (parse-duration dur)))))

            :DRUM
            (let [m    (re-matches DRUM_RE value)
                  dur  (when m (nth m 1))
                  prog (when m (nth m 2))]
              (recur (rest remaining) stack
                     (conj results
                           (d/drum value
                                   (or current-ctx (d/context))
                                   (parse-duration dur)
                                   (when prog (Integer/parseInt prog))))))

            ;; --- Primitives ---
            :INT
            (recur (rest remaining) stack
                   (conj results {:type :int :val (Integer/parseInt value)}))

            :FLOAT
            (recur (rest remaining) stack
                   (conj results {:type :float :val (Double/parseDouble value)}))

            :TYPE
            (recur (rest remaining) stack
                   (conj results {:type :type-ref :val value}))

            ;; --- Fallback ---
            (recur (rest remaining) stack
                   (conj results {:type :unknown :val value}))))
        ;; All tokens consumed — pop remaining stack levels
        (let [final (reduce (fn [[stk rslts] _]
                              (let [[ns result] (pop-and-collect stk)]
                                [ns (if result (conj rslts result) rslts)]))
                            [stack results]
                            (range (dec (count stack))))]
          {:score  (first final)
           :tokens (vec (second final))})))))

;; ============================================================
;; Pretty printing
;; ============================================================

(defn pp
  "Pretty-print the parse result."
  [parsed]
  (println "\n=== Parse Result ===")
  (let [score (:score parsed)]
    (println "Score:" (name (:type score)) "with"
             (d/composite-count score) "children"))
  (println "\nTokens:" (count (:tokens parsed)))
  (doseq [t (:tokens parsed)]
    (println " " (pr-str t))))

;; ============================================================
;; REPL test
;; ============================================================

(comment
  ;; Small test
  (def t1 (tokenize "c4 d4 e4 r4"))
  (println "\n=== Tokens ===")
  (run! println t1)

  ;; Full test
  (def text
    "a#'4..-^\\prall~ !mf !art=80 !pan=0.0 !vol=mf !timbre=\"piano\" !cresc c4 <c e g>2-! r4 !f [ d' e' ] << f' g' >>
     << [ \"1\" a b c] [ \"2\" [ \"3\" d e f ] [g a' b]] [[c d e][ f g a][ b c d]] >>
     << \"composite\" [a b c] [e f g] >>
     '[ int 1 2 3 4']
     ( trans(9) A4 b c )
     @( reverse [ a b c d e f g ])")

  (def result (parse text))
  (pp result)

  ;; Just tokens
  (println "\n=== Tokens ===")
  (run! #(println (str (name (:type %)) \tab (:value %))) (tokenize text))
  )
