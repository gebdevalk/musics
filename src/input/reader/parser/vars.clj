;; vars.clj
;; Variable definition and expansion system.
;; Pre-parser: strips "name = ..." definitions from text,
;; stores source in a registry, and expands \name references.
;;
;; Pipeline: text → extract-vars → expand-vars → lex/tokenize → parse

(ns input.reader.parser.vars
  (:require [clojure.string :as str])
  (:import (java.util ArrayList)))

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

;; Every composite bracket pair musics.ebnf defines (see the bracket table
;; at the top of that file): { } Sequence, << >> Parallel, ( ) Unit,
;; '[ ] Data / @'[ ] AtomicAlgo / @[ ] ElementAlgo (all three keyed off the
;; same [ ] pair -- the leading '/@' chars aren't brackets themselves),
;; ^{ } Context (keyed off the same { } pair as Sequence).
(def ^:private bracket-pairs
  {\{ \}, \< \>, \( \), \[ \]})

;; A value can start with a bracket char directly ({, <, (, [) or with one
;; of the non-bracket prefix chars that always immediately precede one in
;; this grammar (' before [, @ before ' or [, ^ before {) -- recognizing
;; those too is what lets '[ ]/@'[ ]/@[ ]/^{ } values trigger multi-line
;; tracking the same as a bare { or [ does.
(def ^:private multiline-trigger-chars
  (into (set (keys bracket-pairs)) [\' \@ \^]))

(defn- count-brackets
  "Track nesting across every composite bracket the grammar defines, as a
   real per-type stack (not a flat net counter) -- so a mismatched
   bracket (an errant ] where a } was expected) can't accidentally read
   as balanced just because the counts happen to cancel out. stack is a
   vector of expected closers, innermost last; empty means balanced.
   Chars that aren't one of the four bracket types (including the '/@/^
   prefix chars above) are no-ops, same as any other content character."
  [stack s]
  (reduce (fn [stk c]
            (cond
              (contains? bracket-pairs c)        (conj stk (bracket-pairs c))
              (= c (peek stk))                   (pop stk)
              :else                               stk))
          stack
          s))

(defn extract-vars
  "Extract variable definitions from text.
   Returns [cleaned-text], and side-effects var-registry.

   Single-line:   verse = c4 d4 e4
   Multi-line:    verse = {c4 d4 e4}
                  verse = <<{c4 d4} {e4 f4}>>
                  verse = '[c 4 3/2]
                  (everything from the opening bracket to its match,
                  whichever of the grammar's bracket pairs it is)

   Supports nested brackets (including mixed types) in multi-line
   definitions."
  [text]
  (let [lines     (str/split-lines text)
        out-lines (ArrayList.)
        in-def?   (atom false)
        def-name  (atom nil)
        def-lines (atom [])
        depth     (atom [])]
    (doseq [line lines]
      (if @in-def?
        ;; Accumulating a multi-line definition
        (do
          (swap! def-lines conj line)
          (swap! depth count-brackets line)
          (when (empty? @depth)
            ;; Definition complete
            (def-var! @def-name (str/join "\n" @def-lines))
            (reset! in-def? false)
            (reset! def-lines [])))
        ;; Looking for a new definition
        (if-let [[match name] (re-find var-def-re line)]
          (let [val-start (+ (.indexOf line match) (count match))
                val       (str/trim (subs line val-start))]
            (if (multiline-trigger-chars (first val))
              ;; Multi-line bracketed definition
              (do
                (reset! in-def? true)
                (reset! def-name name)
                (reset! def-lines [val])
                (reset! depth (count-brackets [] val))
                (when (empty? @depth)
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
  "Replace \\name references with stored variable source.
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
