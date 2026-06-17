;; vars.clj
;; Variable definition and expansion system.
;; Pre-parser: strips "name = ..." definitions from text,
;; stores source in a registry, and expands $name references.
;;
;; Pipeline: text → extract-vars → expand-vars → lex/tokenize → parse

(ns input.reader.parser.vars
  (:require [clojure.string :as str]))

;; ============================================================
;; Variable registry
;; ============================================================

(defonce var-registry (atom {}))

(defn def-var! [name source]
  (swap! var-registry assoc name source))

(defn get-var [name]
  (get @var-registry name))

(defn clear-vars! []
  (reset! var-registry {}))

(defn list-vars []
  (keys @var-registry))

;; ============================================================
;; Variable extraction
;; ============================================================

(def ^:private var-def-re
  #"^\s*([a-zA-Z][a-zA-Z0-9_]*)\s*=\s*")

(defn- count-brackets
  "Count net [ openings minus ] closings in string."
  [initial s]
  (reduce (fn [acc c]
            (cond
              (= c \[) (inc acc)
              (= c \]) (dec acc)
              :else    acc))
          initial
          s))

(defn extract-vars
  "Extract variable definitions from text.
   Returns [cleaned-text], and side-effects var-registry.

   Single-line:   verse = c4 d4 e4
   Multi-line:    verse = [c4 d4 e4]
                  (everything from [ to matching ])

   Supports nested brackets in multi-line definitions."
  [text]
  (let [lines     (str/split-lines text)
        out-lines (java.util.ArrayList.)
        in-def?   (atom false)
        def-name  (atom nil)
        def-lines (atom [])
        depth     (atom 0)]
    (doseq [line lines]
      (if @in-def?
        ;; Accumulating a multi-line definition
        (do
          (swap! def-lines conj line)
          (swap! depth count-brackets line)
          (when (zero? @depth)
            ;; Definition complete
            (def-var! @def-name (str/join "\n" @def-lines))
            (reset! in-def? false)
            (reset! def-lines [])))
        ;; Looking for a new definition
        (if-let [[match name] (re-find var-def-re line)]
          (let [val-start (+ (.indexOf line match) (count match))
                val       (str/trim (subs line val-start))]
            (if (= (first val) \[)
              ;; Multi-line bracketed definition
              (do
                (reset! in-def? true)
                (reset! def-name name)
                (reset! def-lines [val])
                (reset! depth (count-brackets 0 val))
                (when (zero? @depth)
                  ;; Closed on same line
                  (def-var! name val)
                  (reset! in-def? false)
                  (reset! def-lines [])))
              ;; Single-line definition
              (def-var! name val)))
          ;; Not a definition — keep this line
          (.add out-lines line))))
    [(str/join "\n" (seq out-lines))]))

;; ============================================================
;; Variable expansion
;; ============================================================

(def ^:private var-ref-re
  #"[\\]([a-zA-Z][a-zA-Z0-9_]*)")

(defn expand-vars
  "Replace $name references with stored variable source.
   Recursively expands until stable (supports nested references)."
  [text]
  (loop [t text
         prev nil]
    (if (= t prev)
      t
      (let [t' (str/replace t var-ref-re
                            (fn [[_ name]]
                              (if-let [source (get-var name)]
                                source
                                (str "\\" name))))]
        (recur t' t)))))
