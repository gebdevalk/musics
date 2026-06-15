(ns lexer-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.lexer :as lex]))

(deftest tokenizer
  (testing "classifies note rest instruction"
    (let [tokens (lex/tokenize "c4 r4 !mf")]
      (is (= :NOTE (:type (nth tokens 0))))
      (is (= :REST (:type (nth tokens 1))))
      (is (= :BANG_CONST (:type (nth tokens 2)))))))

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
