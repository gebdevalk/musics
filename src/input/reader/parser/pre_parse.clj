;; pre_parse.clj
;; Text-level pre-processing that runs before the grammar ever sees the
;; input: comment stripping, then variable extraction/expansion. Kept
;; separate from grammar-parser since none of this touches the grammar
;; or instaparse at all -- it's pure text transformation.

(ns input.reader.parser.pre-parse
  (:require [clojure.string :as str]
            [input.reader.parser.vars :as vars]))

;; ============================================================
;; Comment stripping
;; ============================================================

(defn strip-comments
  "Removes every comment form this DSL supports: %{...%} blocks (non-
   nesting -- matches up to the first %}), %...-to-end-of-line, ;...-to-
   end-of-line, and (comment ...) Clojure-style balanced blocks. Runs
   before vars extraction/expansion (see preprocess) so a variable's
   captured source, or a var-definition line itself, is never
   accidentally read out of a comment -- e.g. a %-commented-out multi-
   line var reference could otherwise splice real newlines into what
   should stay inert comment text. musics.ebnf's own `ws` rule still
   separately matches %/%{...%}/; too, as a second line of defense for
   anything reaching the grammar directly (grammar-parser's *-string fns
   bypass vars but still call this fn first) -- not because this fn is
   expected to miss anything in normal use."
  [text]
  (let [text (str/replace text #"%\{[\s\S]*?%\}" "")
        text (str/replace text #"%[^\n]*" "")
        text (str/replace text #";[^\n]*" "")]
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
;; Full pre-parse pipeline
;; ============================================================

(defn preprocess
  "Run the full text-level pipeline a piece of musics text needs before
   the grammar ever sees it: strip-comments, then vars/extract-vars,
   then vars/expand-vars, in that order -- comments have to be gone
   FIRST, or a variable definition/reference sitting inside one could be
   read as real (see strip-comments). Returns the fully processed text."
  [text]
  (let [stripped    (strip-comments text)
        [cleaned _] (vars/extract-vars stripped)]
    (vars/expand-vars cleaned)))
