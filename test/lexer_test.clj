(ns lexer-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.lexer :as lex]))

(deftest tokenizer
  (testing "classifies note rest instruction"
    (let [tokens (lex/tokenize "c4 r4 !mf")]
      (is (= :NOTE (:type (nth tokens 0))))
      (is (= :REST (:type (nth tokens 1))))
      (is (= :BANG_CONST (:type (nth tokens 2)))))))

(deftest comment-stripping
  (testing "; line comment"
    (let [with    (lex/tokenize "c4 ; a comment\nd4")
          without (lex/tokenize "c4 d4")]
      (is (= (map :type with) (map :type without)))
      (is (= (map :value with) (map :value without)))))
  (testing "; at end of line"
    (let [tokens (lex/tokenize "c4 ; comment")]
      (is (= 1 (count tokens)))
      (is (= :NOTE (:type (first tokens))))
      (is (= "c4" (:value (first tokens))))))
  (testing "(comment ...) block single-line"
    (let [with    (lex/tokenize "c4 (comment skip this) d4")
          without (lex/tokenize "c4 d4")]
      (is (= (map :type with) (map :type without)))
      (is (= (map :value with) (map :value without)))))
  (testing "(comment ...) block multi-line"
    (let [with    (lex/tokenize "c4 (comment\n  skip\n  this\n) d4")
          without (lex/tokenize "c4 d4")]
      (is (= (map :type with) (map :type without)))
      (is (= (map :value with) (map :value without)))))
  (testing "(comment ...) with nested parens"
    (let [with    (lex/tokenize "c4 (comment (nested (parens))) d4")
          without (lex/tokenize "c4 d4")]
      (is (= (map :type with) (map :type without)))
      (is (= (map :value with) (map :value without)))))
  (testing "(commentary is not a comment)"
    (let [tokens (lex/tokenize "c4 (commentary stays) d4")]
      (is (= 6 (count tokens)) "commentary should tokenize as list, not comment")))
  (testing "comment after content with no following tokens"
    (let [tokens (lex/tokenize "c4 (comment last)")]
      (is (= 1 (count tokens)))
      (is (= :NOTE (:type (first tokens)))))))

(deftest pattern-matching
  (testing "NOTE_RE matches relative pitch"
    (is (re-matches lex/NOTE_RE "c4"))
    (is (re-matches lex/NOTE_RE "c")))
  (testing "REST_RE matches rests"
    (is (re-matches lex/REST_RE "r4"))
    (is (re-matches lex/REST_RE "r")))
  (testing "classify-token"
    (is (= [:NOTE "c4"] (lex/classify-token "c4")))
    (is (= [:REST "r4"] (lex/classify-token "r4")))
    (is (= [:INT "42"] (lex/classify-token "42")))))