;; grammar_parser.clj
;; Instaparse-based parser using musics.ebnf grammar.
;; Replaces the hand-rolled lexer + recursive-descent parser
;; (parser/lexer.clj + parser/music_parser.clj).
;;
;; Pipeline: text → vars → strip-comments → instaparse → tree
;;
;; Usage: (parse text)  →  instaparse tree (raw, no tree-walker yet)

(ns input.reader.grammar-parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [input.reader.parser.vars :as vars]))

;; ============================================================
;; Grammar loading
;; ============================================================

(def ^:private grammar-str
  "Load the EBNF grammar from the classpath."
  (slurp (io/resource "input/reader/musics.ebnf")))

(def parser
  "The instaparse parser instance, created once at load time.
   No auto-whitespace — whitespace is handled explicitly via
   the hidden `ws` rule in `<Element>` alternatives."
  (insta/parser grammar-str
                :string-ci false))

;; ============================================================
;; Comment stripping
;; ============================================================

(defn- strip-comments
  "Remove ; line comments and (comment ...) blocks from source text.
   Handles nested parentheses inside comment blocks.
   Extracted from parser/lexer.clj for the instaparse pipeline."
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
;; Public API
;; ============================================================

(defn parse
  "Parse text through the full pre-processing pipeline and instaparse.
   Returns the raw instaparse tree (nested vectors).
   
   Pipeline: extract-vars → expand-vars → strip-comments → instaparse
   
   On parse failure, instaparse returns a map with :failure key.
   Check with (insta/failure? result)."
  [text]
  (let [[cleaned _] (vars/extract-vars text)
        expanded    (vars/expand-vars cleaned)
        stripped    (strip-comments expanded)]
    (parser stripped)))

(defn parse-string
  "Parse without variable pre-processing (for testing individual forms).
   Only strips comments, then feeds to instaparse."
  [text]
  (parser (strip-comments text)))

;; ============================================================
;; REPL helpers
;; ============================================================

(comment
  ;; Quick test: parse a simple note
  (parse-string "c4 d4 e4")

  ;; Parse composite
  (parse-string "{c4 d4 e4}")

  ;; Parse with instructions
  (parse-string "!mf c4 !ff e4")

  ;; Check for failure
  (insta/failure? (parse-string "!bad_token @@@@"))

  ;; Full pipeline (with vars)
  (parse "motif = c4 d4\n\\motif e4")

  ;; Inspect the tree
  (require '[instaparse.core :as insta])
  (insta/visualize (parse-string "{c4 d4 e4}"))
  )
