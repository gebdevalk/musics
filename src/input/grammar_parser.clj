;; grammar_parser.clj
;; Instaparse-based parser using musics.ebnf grammar.
;; Pipeline: text -> instaparse -> tree
;; (Comments and variables are both handled natively by the grammar/
;; walker now -- see musics.ebnf's Comment/VarDef/VarRef and
;; flat-tree-walker's walk-var-def/walk-var-ref -- so there is no
;; pre-processing step left here at all; parser runs directly against
;; whatever text was actually written.)

(ns input.grammar-parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [input.reader.flat-tree-walker :as tw]
            [input.reader.radical-tree-walker :as rtw]))

;; ============================================================
;; Grammar loading
;; ============================================================

(def ^:private grammar-str
  (slurp (io/resource "input/musics.ebnf")))

(def parser
  (insta/parser grammar-str :string-ci false))

;; radical.ebnf -- a second, Clojure-flavored grammar alongside the one
;; above (see radical.ebnf's own header comment). Experimental/unmerged
;; still, so everything below is deliberately parallel to, never a
;; replacement for, the musics.ebnf functions above -- nothing above
;; this comment was touched to add it.
(def ^:private radical-grammar-str
  (slurp (io/resource "input/radical.ebnf")))

(def radical-parser
  (insta/parser radical-grammar-str :string-ci false))

;; ============================================================
;; Error formatting
;; ============================================================

(def ^:private string-labels
  {"}" "}" ">" ">" "{" "{" "<<" "<<" "|" "|"
   "~" "~ (tie)" "-" "- (articulation)" ":" ": (id or reference)"
   "!" "!" "!key:" "!key:" "!(" "!( (slur start)" "!)" "!) (slur end)"
   "r" "r (rest)" "x" "x (drum)" "(" "(" "[" "[" "'(" "'("})

(def ^:private regex-labels
  {"[A-Ga-gp]"              "pitch letter"
   "[a-zA-Z][a-zA-Z0-9_]*"  "name"
   "[1-9][0-9]?\\.*"        "duration (e.g. 4, 8., 16..)"
   "[1-8]/"                 "octave number (e.g. 4/)"
   "##|bb|[#bn]"            "accidental"
   "'+|,+"                  "octave mark (' or ,)"
   "[0-9]+"                 "integer"
   "[0-9]+\\.[0-9]+"        "decimal"
   "[0-9]+/[0-9]+"          "ratio (e.g. 3/4)"
   "[-.>^_!+]"              "articulation mark"
   "[a-zA-Z0-9_][a-zA-Z0-9_\\-]*" "keyword"
   "%\\{[\\s\\S]*?%\\}"     "comment (%{ ... %})"
   "%(?!\\{)[^\\n]*"        "comment (% ...)"
   "(?!(prallmordent|prallprall|lineprall|prallup|pralldown|downprall|prall|upmordent|downmordent|mordent|trill|turn|reverseturn|verylongfermata|longfermata|shortfermata|fermata|transpose|times|tuplet|repeat|alternative|acciaccatura|appoggiatura|slashedGrace|afterGrace|grace)\\b)[a-zA-Z][a-zA-Z0-9_]*"
   "variable name"})

;; radical.ebnf's own error-formatting tables -- different brackets,
;; different comment/VarName regexes (see radical.ebnf's own header
;; comment), so these can't just reuse musics.ebnf's tables above. Kept
;; as separate parallel tables rather than trying to merge/parameterize
;; the strings themselves -- humanize-expecting/format-parse-error
;; below take a labels pair as an argument instead, so the SHARED
;; formatting logic isn't duplicated, only the label data is.
(def ^:private radical-string-labels
  {"}" "}" "#{" "#{" "{" "{" "|" "|"
   "~" "~ (tie)" "-" "- (articulation)" ":" ": (id or reference)"
   "!" "!" "!key:" "!key:" "!(" "!( (slur start)" "!)" "!) (slur end)"
   "r" "r (rest)" "x" "x (drum)" "(" "(" "[" "[" "'[" "'["})

(def ^:private radical-regex-labels
  {"[A-Ga-gp]"              "pitch letter"
   "[a-zA-Z][a-zA-Z0-9_]*"  "name"
   "[1-9][0-9]?\\.*"        "duration (e.g. 4, 8., 16..)"
   "[1-8]/"                 "octave number (e.g. 4/)"
   "##|bb|[#bn]"            "accidental"
   "'+|,+"                  "octave mark (' or ,)"
   "[0-9]+"                 "integer"
   "[0-9]+\\.[0-9]+"        "decimal"
   "[0-9]+/[0-9]+"          "ratio (e.g. 3/4)"
   "[-.>^_!+]"              "articulation mark"
   "[a-zA-Z0-9_][a-zA-Z0-9_\\-]*" "keyword"
   "%\\{[\\s\\S]*?%\\}"     "comment (%{ ... %})"
   ";[^\\n]*"               "comment (; ...)"
   "(?!(prallmordent|prallprall|lineprall|prallup|pralldown|downprall|prall|upmordent|downmordent|mordent|trill|turn|reverseturn|verylongfermata|longfermata|shortfermata|fermata)\\b)[a-zA-Z][a-zA-Z0-9_]*"
   "variable name"})

(def ^:private musics-labels {:strings string-labels :regexes regex-labels})
(def ^:private radical-labels {:strings radical-string-labels :regexes radical-regex-labels})

(defn- humanize-expecting
  ([reason] (humanize-expecting reason musics-labels))
  ([{:keys [tag expecting]} {:keys [strings regexes]}]
   (case tag
     :string  (or (get strings expecting)
                  (str "'" expecting "'"))
     :regexp  (let [s (str expecting)]
                (or (get regexes s)
                    (when (and (> (count s) 2) (str/starts-with? s "\\\\"))
                      (str "\\" (subs s 2)))
                    (when (= s "\\\\")
                      "\\ (modifier/ornament)")
                    (when (str/includes? s "\\s")
                      "whitespace")
                    (str "/" s "/")))
     :optional (if (= expecting :end-of-string)
                 "end of input"
                 (str expecting))
     (str expecting))))

(defn format-parse-error
  ([failure text] (format-parse-error failure text musics-labels))
  ([failure text labels]
   (let [{:keys [line column reason]} failure
         lines   (str/split-lines text)
         src     (when (<= line (count lines))
                   (nth lines (dec line)))
         pointer (when src
                   (str (apply str (repeat (dec column) \space)) "^"))
         humanized (->> reason
                        (map #(humanize-expecting % labels))
                        distinct
                        (remove #(= % "whitespace"))
                        (take 6))]
     (str "-- Parse error --- line " line ", column " column " --\n"
          "|\n"
          (when src
            (str "|  " src "\n"
                 "|  " pointer "\n"
                 "|\n"))
          "|  Expected one of:\n"
          (str/join (map #(str "|    * " % "\n") humanized))
          (when (> (count reason) 6)
            (str "|    ... and " (- (count reason) 6) " more\n"))
          "------------------------------------------"))))

;; ============================================================
;; Public API
;; ============================================================

(defn parse-string
  [text]
  (parser text))

(defn failure-info
  [result]
  (when (insta/failure? result)
    {:line   (:line result)
     :column (:column result)
     :index  (:index result)
     :reason (pr-str (:reason result))}))

(defn try-parse
  "Parse text and return the tree, or print a formatted error and return nil."
  [text]
  (try
    (let [result (parser text)]
      (if (insta/failure? result)
        (do (println (format-parse-error (insta/get-failure result) text))
            nil)
        result))
    (catch Exception e
      (println (str "-- Error --- " (.getMessage e) " --"))
      nil)))

(defn parse-domain-string
  "Full pipeline: parse then walk. Throws on parse failure with formatted
   message."
  [text]
  (let [tree (parser text)]
    (when (insta/failure? tree)
      (let [msg (format-parse-error (insta/get-failure tree) text)]
        (throw (ex-info msg {:failure (failure-info tree)}))))
    (tw/walk tree text)))

;; ============================================================
;; Public API -- radical.ebnf
;; ============================================================
;; Exact parallels of the musics.ebnf functions above, radical-parser/
;; radical-tree-walker in place of parser/flat-tree-walker -- see that
;; grammar's own header comment for what's actually different in the
;; text itself.

(defn parse-string-radical
  [text]
  (radical-parser text))

(defn try-parse-radical
  "Parse text (radical.ebnf) and return the tree, or print a formatted
   error and return nil."
  [text]
  (try
    (let [result (radical-parser text)]
      (if (insta/failure? result)
        (do (println (format-parse-error (insta/get-failure result) text radical-labels))
            nil)
        result))
    (catch Exception e
      (println (str "-- Error --- " (.getMessage e) " --"))
      nil)))

(defn parse-domain-string-radical
  "Full pipeline (radical.ebnf): parse then walk. Throws on parse
   failure with formatted message."
  [text]
  (let [tree (radical-parser text)]
    (when (insta/failure? tree)
      (let [msg (format-parse-error (insta/get-failure tree) text radical-labels)]
        (throw (ex-info msg {:failure (failure-info tree)}))))
    (rtw/walk tree text)))

;; ============================================================
;; REPL helpers
;; ============================================================

(comment
  (try-parse "{c4 d4")
  (try-parse "c4 d4\ne4 f4\n!")
  (try-parse "<c e g")
  (parse-string "c4 d4 e4")
  (parse-string "{verse: c4 d4 e4}")
  )
