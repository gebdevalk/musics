(ns vars-test
  "Test variable definition and expansion.
   Run: lein test vars-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.vars :as vars]
            [input.reader.parser.music-parser :as p]
            [core.domain.music-domain :as d]))

;; ============================================================
;; Variable extraction
;; ============================================================

(deftest extract-single-line
  (testing "single-line variable definition"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "verse = c4 d4 e4")]
      (is (= "" cleaned) "definition line removed")
      (is (= "c4 d4 e4" (vars/get-var "verse"))))))

(deftest extract-multi-line
  (testing "multi-line bracketed definition"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "verse = [c4 d4\ne4 f4]")]
      (is (= "" cleaned) "definition removed")
      (is (= "[c4 d4\ne4 f4]" (vars/get-var "verse"))))))

(deftest extract-nested-brackets
  (testing "nested brackets in definition"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "verse = {{c4 d4} {e4 f4}}")]
      (is (= "" cleaned))
      (is (= "{{c4 d4} {e4 f4}}" (vars/get-var "verse"))))))

(deftest extract-multiple-defs
  (testing "multiple definitions on separate lines"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "verse = c4 d4\nchorus = e4 f4")]
      (is (= "" cleaned) "both lines removed")
      (is (= "c4 d4" (vars/get-var "verse")))
      (is (= "e4 f4" (vars/get-var "chorus"))))))

(deftest extract-keeps-other-lines
  (testing "non-definition lines are preserved"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "verse = c4 d4\n!mf e4 f4")]
      (is (= "!mf e4 f4" cleaned))
      (is (= "c4 d4" (vars/get-var "verse"))))))

;; ============================================================
;; Variable expansion
;; ============================================================

(deftest expand-simple
  (testing "simple variable expansion"
    (vars/clear-vars!)
    (vars/def-var! "verse" "c4 d4 e4")
    (is (= "c4 d4 e4 r4" (vars/expand-vars "\\verse r4")))))

(deftest expand-undefined
  (testing "undefined variable left unchanged"
    (vars/clear-vars!)
    (is (= "c4 \\missing r4" (vars/expand-vars "c4 \\missing r4")))))

(deftest expand-nested
  (testing "nested variable references"
    (vars/clear-vars!)
    (vars/def-var! "inner" "d4 e4")
    (vars/def-var! "outer" "c4 \\inner f4")
    (is (= "c4 d4 e4 f4 r4" (vars/expand-vars "\\outer r4")))))

;; ============================================================
;; End-to-end: parse with variables
;; ============================================================

(deftest parse-with-vars
  (testing "variables substituted during parse"
    (let [result (p/parse (vars/expand-vars
                            (do (vars/clear-vars!)
                                (vars/def-var! "motif" "c4 d4 e4")
                                "[ motif \\motif f4]")))
          children (d/composite-children (:score result))]
      (is (= 1 (count children)) "one composite from [ ... ]")
      (let [leaves (filter d/leaf? (d/composite-children (first children)))]
        (is (= 4 (count leaves)) "c4 d4 e4 from $motif + f4 = 4 leaves")))))

(deftest parse-with-var-def-and-ref
  (testing "parse extracts vars and expands refs in one call"
    ;; Use the full parse pipeline (which calls extract-vars + expand-vars)
    ;; But we test via the parser directly to isolate
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars "motif = c4 d4 e4\n[motif \\motif f4]")
          expanded  (vars/expand-vars cleaned)
          result    (p/parse expanded)
          children  (d/composite-children (:score result))]
      (is (= 1 (count children)))
      (let [seq-children (d/composite-children (first children))]
        (is (= "motif" (:id (first children))) "bare word names composite")
        (is (= 4 (count (filter d/leaf? seq-children)))
            "c4 d4 e4 from $motif + f4")))))

(deftest parse-with-var-in-composite
  (testing "variable ref inside composite body"
    (vars/clear-vars!)
    (vars/def-var! "fill" "d4 e4")
    (let [expanded (vars/expand-vars "{c4 \\fill f4}")
          result   (p/parse expanded)
          children (d/composite-children (:score result))
          leaves   (filter d/leaf? (d/composite-children (first children)))]
      (is (= 4 (count leaves)) "c4 d4 e4 f4"))))
