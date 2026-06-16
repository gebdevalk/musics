;; lexer.clj
;; Clojure port of the pymusics lexer/tokenizer.
;; All regex patterns, token classification, and tokenization.
;;
;; Public API: tokenize, classify-token
;; Exported patterns: NOTE_RE, CHORD_RE, REST_RE, DRUM_RE,
;;   MODIFIER_RE_SINGLE, ASSIGN_RE, INT, FLOAT, STRING

(ns input.reader.parser.lexer
  (:require [clojure.string :as str]))

;; ============================================================
;; Regex patterns (ported from regex.py)
;; ============================================================

(def ^:private NAME          #"[a-zA-Z][a-zA-Z0-9_]*")
(def ^:private EQUALS        #"\s*=\s*")
(def INT                     #"[0-9]+")
(def FLOAT                   #"[0-9]+\.[0-9]+")
(def STRING                  #"\"[^\"]*\"")
(def ^:private KEYWORD       #":[a-zA-Z0-9_][a-zA-Z0-9_\-]*")

(def ^:private PITCH_NAME    #"[A-G]|[a-g]|p")
(def ^:private ACCIDENTAL    #"[b#]{0,2}|n+")
(def ^:private OCTAVE        #"[',]*|[1-8]/")

(def ^:private DURATION      #"longa|breve|\d{1,3}\.*")
(def ^:private ARTICULATION  #"-[.>^_!+]")
(def ^:private TIE            #"~")

;; --- Whole-unit patterns ---
(def ^:private PITCH_UNIT
  (re-pattern (str "(?:" PITCH_NAME ")(?:" ACCIDENTAL ")(?:" OCTAVE ")")))

(def ^:private CHORD_CORE
  (re-pattern (str "<(?!<)(" PITCH_UNIT "(?:\\s+" PITCH_UNIT ")*?)>")))

(def ^:private MODIFIER
  (re-pattern (str "\\\\(?:" NAME ")(?:" EQUALS "(?:" FLOAT "|" INT "|" NAME "|" STRING "))?")))

(def ^:private MODIFIERS
  (re-pattern (str "(?:" MODIFIER ")*")))

;; --- Main leaf patterns (first-pass matching) ---

;; NOTE: (PITCH_UNIT) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def NOTE_RE
  (re-pattern (str "(" PITCH_UNIT ")(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; CHORD: (CHORD_CORE) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
(def CHORD_RE
  (re-pattern (str CHORD_CORE "(" DURATION ")?(" ARTICULATION ")?(" MODIFIERS ")?(" TIE ")?")))

;; REST: r(DURATION)?
(def REST_RE  #"r(longa|breve|\d{1,3}\.*)?")

;; DRUM_MODIFIER: \\INT or \\NAME
(def ^:private DRUM_MODIFIER
  (re-pattern (str "\\\\(" INT "|" NAME ")")))

;; DRUM: x(DURATION)?(DRUM_MODIFIER)?
(def DRUM_RE
  (re-pattern (str "x(" DURATION ")?(" DRUM_MODIFIER ")?")))

;; --- Instruction patterns ---
(def ^:private BANG_CONST_RE
  (re-pattern (str "!\\s*(" NAME ")")))

(def ^:private NAME_DOT
  #"[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*")

(def ^:private ASSIGN_INT_RE
  (re-pattern (str "!\\s*(" NAME "):(" INT ")")))

(def ^:private ASSIGN_FLOAT_RE
  (re-pattern (str "!\\s*(" NAME "):(" FLOAT ")")))

(def ^:private ASSIGN_CONST_RE
  (re-pattern (str "!\\s*(" NAME "):(" NAME_DOT ")")))

(def ^:private ASSIGN_STRING_RE
  (re-pattern (str "!\\s*(" NAME "):(" STRING ")")))

(def ^:private KEY_DEF_RE
  (re-pattern (str "!key:([A-Ga-g][b#]?(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*)")))

(def ^:private STRUCT_ASSIGN_RE
  (re-pattern (str "!\\s*(" NAME "):\\(([^)]+)\\)")))

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

;; --- Modifier pattern (used by parse-modifiers in music-parser) ---
(def MODIFIER_RE_SINGLE
  #"\\([a-zA-Z][a-zA-Z0-9_]*)(?:\s*=\s*([a-zA-Z][a-zA-Z0-9_]*|\d+\.\d+|\d+|\"[^\"]*\"))?")

;; --- Assignment pattern (used by parse-assignment in music-parser) ---
(def ASSIGN_RE
  #"!\s*([a-zA-Z][a-zA-Z0-9_]*):(.*)")

;; ============================================================
;; Token classification
;; ============================================================

(def ^:private token-classifiers
  [[KEY_DEF_RE        :KEY_DEF]
   [STRUCT_ASSIGN_RE  :STRUCT_ASSIGN]
   [ASSIGN_STRING_RE  :ASSIGN_STRING]
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
   [KEYWORD           :KEYWORD]
   [STRING            :STRING]
   [NAME              :TYPE]])

(defn classify-token
  "Given a raw token string, return [type-kw value]."
  [s]
  (or (some (fn [[re tag]] (when (re-matches re s) [tag s]))
            token-classifiers)
      [:UNKNOWN s]))

;; ============================================================
;; Tokenizer
;; ============================================================

(def ^:private TOKEN_PATTERN
  (re-pattern
   (str
    "'\\[|@\\(|'\\("
    "|<<|\\["
    "|\\("
    "|\\]'|>>|\\]|\\)"
    "|<(?!<)[^>]*?>[a-zA-Z0-9.*\\-^_!+\\\\~]*"
    "|x(\\d+(?:\\.\\d*)?)?(\\\\(\\d+|[a-zA-Z][a-zA-Z0-9_]*))?"
    "|r(longa|breve|\\d{1,3}\\.*)?"
    "|[a-gA-G][b#n]{0,2}([',]*|[1-8]/)[a-zA-Z0-9.*\\-^_!+\\\\~]*"
    "|!key:[A-Ga-g][b#]?(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*"
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*:\\([^)]+\\)"
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*:\"[^\"]*\""
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*:[0-9]+\\.[0-9]+"
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*:[0-9]+"
    "|!\\s*[a-zA-Z][a-zA-Z0-9_]*:[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*"
    "|!prestissimo|!repeatStart|!stageCenter|!DC_al_Fine|!DS_al_Coda|!commonTime|!stageRight"
    "|!classical|!repeatEnd|!stageLeft|!swingFeel|!moderato|!straight|!treCorde|!unaCorda"
    "|!allegro|!andante|!cutTime|!decresc|!shuffle|!silence|!sostPed|!ToCoda|!adagio|!center"
    "|!pedOff|!presto|!rubato|!vivace|!Segno|!cresc|!largo|!latin|!lento|!pedOn|!right|!swing"
    "|!Coda|!Fine|!ffff|!jazz|!left|!near|!pppp|!rock|!acc|!dim|!far|!fff|!ppp|!rit|!sfz|!DC"
    "|!DS|!ff|!fp|!mf|!mp|!pp|!f|!p"
"|[0-9]+\\.[0-9]+"
    "|[0-9]+"
    "|:[a-zA-Z0-9_][a-zA-Z0-9_\\-]*"
    "|\"[^\"]*\""
    "|[a-zA-Z][a-zA-Z0-9_]*"
    )))

;; ============================================================
;; Comment stripping
;; ============================================================

(defn- strip-comments
  "Remove (comment ...) blocks and ; line comments from source text.
   Handles nested parentheses inside comment blocks."
  [text]
  (let [text (str/replace text #";[^\n]*" "")]
    (loop [i 0 depth 0 in-comment false sb (StringBuilder.)]
      (if (>= i (count text))
        (.toString sb)
        (if in-comment
          (let [c (.charAt text i)]
            (cond
              (= c \() (recur (inc i) (inc depth) true sb)
              (= c \)) (if (= depth 1)
                         (recur (inc i) 0 false sb)
                         (recur (inc i) (dec depth) true sb))
              :else   (recur (inc i) depth true sb)))
          (if (and (= (.charAt text i) \()
                   (> (- (count text) i) 7)
                   (= (.substring text i (+ i 8)) "(comment")
                   (or (= (- (count text) i) 8)
                       (not (Character/isLetterOrDigit (.charAt text (+ i 8))))))
            (recur (+ i 8) 1 true sb)
            (do (.append sb (.charAt text i))
                (recur (inc i) 0 false sb))))))))

;; ============================================================
;; Tokenizer
;; ============================================================

(defn tokenize
  "Split input text into a sequence of classified token maps.
   Comments (; line and (comment ...) block) are stripped first."
  [text]
  (->> (re-seq TOKEN_PATTERN (strip-comments text))
       (map (fn [m]
              (let [raw (if (coll? m) (first m) m)]
                (zipmap [:type :value] (classify-token raw)))))))