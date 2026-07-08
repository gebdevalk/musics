;; grammar_parser2.clj
;; Copy to src/input/reader/grammar_parser.clj after review.
;;
;; Instaparse-based parser using musics.ebnf grammar.
;; Pipeline: text -> vars -> strip-comments -> instaparse -> tree
;;
;; New: format-parse-error, try-parse, try-parse-string

(ns input.reader.parser.grammar-parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [input.reader.parser.vars :as vars]
            [input.reader.flat-tree-walker :as tw]))

;; ============================================================
;; Grammar loading
;; ============================================================

(def ^:private grammar-str
  (slurp (io/resource "input/reader/parser/musics.ebnf")))

(def parser
  (insta/parser grammar-str :string-ci false))

;; ============================================================
;; Comment stripping
;; ============================================================

(defn- strip-comments
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
   "[a-zA-Z0-9_][a-zA-Z0-9_\\-]*" "keyword"})

(defn- humanize-expecting
  [{:keys [tag expecting]}]
  (case tag
    :string  (or (get string-labels expecting)
                 (str "'" expecting "'"))
    :regexp  (let [s (str expecting)]
               (or (get regex-labels s)
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
    (str expecting)))

(defn format-parse-error
  [failure text]
  (let [{:keys [line column reason]} failure
        lines   (str/split-lines text)
        src     (when (<= line (count lines))
                  (nth lines (dec line)))
        pointer (when src
                  (str (apply str (repeat (dec column) \space)) "^"))
        labels  (->> reason
                     (map humanize-expecting)
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
         (str/join (map #(str "|    * " % "\n") labels))
         (when (> (count reason) 6)
           (str "|    ... and " (- (count reason) 6) " more\n"))
         "------------------------------------------")))

;; ============================================================
;; Public API
;; ============================================================

(defn- parse*
  "Parse text with full pre-processing, return [tree processed-input]."
  [text]
  (let [[cleaned _] (vars/extract-vars text)
        expanded    (vars/expand-vars cleaned)
        stripped    (strip-comments expanded)]
    [(parser stripped) stripped]))

(defn parse
  [text]
  (first (parse* text)))

(defn parse-string
  [text]
  (parser (strip-comments text)))

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
    (let [result (parse text)]
      (if (insta/failure? result)
        (do (println (format-parse-error (insta/get-failure result) text))
            nil)
        result))
    (catch Exception e
      (println (str "-- Error --- " (.getMessage e) " --"))
      nil)))

(defn try-parse-string
  "Like try-parse but without variable pre-processing."
  [text]
  (try
    (let [stripped (strip-comments text)
          result   (parser stripped)]
      (if (insta/failure? result)
        (do (println (format-parse-error (insta/get-failure result) text))
            nil)
        result))
    (catch Exception e
      (println (str "-- Error --- " (.getMessage e) " --"))
      nil)))

(defn parse-domain
  "Full pipeline. Throws on parse failure with formatted message."
  [text]
  (let [[tree input] (parse* text)]
    (when (insta/failure? tree)
      (let [msg (format-parse-error (insta/get-failure tree) text)]
        (throw (ex-info msg {:failure (failure-info tree)}))))
    (tw/walk tree input)))

(defn parse-domain-string
  [text]
  (let [stripped (strip-comments text)
        tree     (parser stripped)]
    (when (insta/failure? tree)
      (let [msg (format-parse-error (insta/get-failure tree) text)]
        (throw (ex-info msg {:failure (failure-info tree)}))))
    (tw/walk tree stripped)))

;; ============================================================
;; REPL helpers
;; ============================================================

(comment
  (try-parse-string "{c4 d4")
  (try-parse-string "c4 d4\ne4 f4\n!")
  (try-parse-string "<c e g")
  (parse-string "c4 d4 e4")
  (parse-string "{verse: c4 d4 e4}")
  )
