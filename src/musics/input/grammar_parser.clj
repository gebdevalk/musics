;; grammar_parser.clj
;; Instaparse-based parser using musics.ebnf grammar.
;; Pipeline: text -> instaparse -> tree
;; (Comments and variables are both handled natively by the grammar/
;; walker now -- see musics.ebnf's Comment/VarDef/VarRef and
;; walker's walk-var-def/walk-var-ref -- so there is no
;; pre-processing step left here at all; parser runs directly against
;; whatever text was actually written.)

(ns musics.input.grammar-parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [musics.domain :as d]
            [musics.engine :as engine]
            [musics.input.reader.builder :as flat]
            [musics.input.reader.walker :as tw]
            [musics.registries :as reg]
            [musics.repo :as repo]))

;; ============================================================
;; Grammar loading
;; ============================================================

(def ^:private grammar-str
  (slurp (io/resource "musics/input/musics.ebnf")))

(def parser
  (insta/parser grammar-str :string-ci false))

;; ============================================================
;; Error formatting
;; ============================================================

(def ^:private string-labels
  {"}" "}" "#{" "#{" "{" "{" "|" "|"
   "~" "~ (tie)" "-" "- (articulation)" ":" ": (id or reference)"
   "!" "!" "!key:" "!key:" "!(" "!( (slur start)" "!)" "!) (slur end)"
   "r" "r (rest)" "x" "x (drum)" "(" "(" "[" "[" "'[" "'["})

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
   ";[^\\n]*"               "comment (; ...)"
   "(?!(prallmordent|prallprall|lineprall|prallup|pralldown|downprall|prall|upmordent|downmordent|mordent|trill|turn|reverseturn|verylongfermata|longfermata|shortfermata|fermata)\\b)[a-zA-Z][a-zA-Z0-9_]*"
   "variable name"})

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
;; Reading text into the repo
;; ============================================================

(defn- commit-root!
  "Commit a fresh :ROOT, with every default."
  []
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT)))

;; A real :ROOT exists before anything is read; defonce, so reloading this
;; ns keeps what is stored.
(defonce ^:private _bootstrap
  (when (nil? (repo/current :ROOT)) (commit-root!)))

(defn usages
  "Every id whose content references id directly as one of its children
   -- what else changes when id is read again. parse warns when a
   redefinition reaches one of these outside what it read itself."
  [id]
  (into #{}
        (keep (fn [[candidate-id node]]
                (when (and (d/container? node) (some #{id} (:children node)))
                  candidate-id)))
        @(repo/registry)))

(defn- root-id-of [child]
  (if (keyword? child) child (:id child)))

(defn parse
  "Read musics text against what is stored and store it at once: every id
   it introduces or changes is committed in one step. Returns {:ids ids},
   its top-level ids in the order written, or nil when it doesn't parse
   (the error printed). Warns (without blocking) when a redefined id is
   also used by a container this text didn't read. Auto-ids and
   variables carry on from the session (musics.registries/session)."
  [text]
  (try
    (if-let [insta-tree (try-parse text)]
      (let [session     reg/session
            old-repo    (into {} @(repo/registry))
            flat-result (tw/walk insta-tree text
                                 {:repo old-repo :auto-ids (:auto-ids @session)
                                  :var-map (:var-map @session)})
            new-repo    (:tree flat-result)
            changed-ids (repo/changed-ids old-repo new-repo)
            edits       (select-keys new-repo changed-ids)
            ids         (into [] (comp (map root-id-of) (filter changed-ids))
                              (:children (get new-repo :ROOT)))]
        (doseq [id changed-ids]
          (let [affected (remove (into changed-ids #{:ROOT}) (usages id))]
            (when (seq affected)
              (println "[musics] Redefining" id "also affects" (vec affected)
                       "-- give it a new id instead if that's not intended."))))
        (repo/commit-many! edits)
        (swap! session assoc
               :auto-ids (:auto-ids flat-result)
               :var-map  (:var-map flat-result))
        (reg/log! :parse)
        {:ids ids})
      nil)
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn parse-file
  "Read the musics text in the file at path (see parse)."
  [path]
  (parse (slurp path)))

(defn reset
  "Clear everything: what is stored, the session, every registered algo
   and conductor entry, the MIDI output; a fresh :ROOT is committed."
  []
  (repo/reset-all!)
  (reg/reset-all!)
  (commit-root!)
  (engine/disconnect!)
  (println "[musics] Reset."))

;; ============================================================
;; REPL helpers
;; ============================================================

(comment
  (try-parse "[c4 d4")
  (try-parse "c4 d4\ne4 f4\n!")
  (try-parse "<c e g")
  (parse-string "c4 d4 e4")
  (parse-string "[verse: c4 d4 e4]")
  )
