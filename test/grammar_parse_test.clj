(ns grammar-parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.grammar-parser :as gp]
            [instaparse.core :as insta]))

(deftest note-parses-not-bareword
  (testing "Note c4 parses as Note, not BareWord"
    (let [result (gp/parse-string "c4")]
      (is (not (insta/failure? result)))
      ;; Check that the tree contains :Note, not :BareWord
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Note")
            (str "Expected :Note in tree, got: " tree-str))
        (is (not (clojure.string/includes? tree-str ":BareWord"))
            (str "Did not expect :BareWord in tree, got: " tree-str)))))

  (testing "Rest r4 parses as Rest, not BareWord"
    (let [result (gp/parse-string "r4")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Rest")
            (str "Expected :Rest in tree, got: " tree-str)))))

  (testing "Chord <c e g>2 parses as Chord"
    (let [result (gp/parse-string "<c e g>2")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Chord")
            (str "Expected :Chord in tree, got: " tree-str)))))

  (testing "Drum x8\\kick parses as Drum"
    (let [result (gp/parse-string "x8\\kick")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Drum")
            (str "Expected :Drum in tree, got: " tree-str))))))

(deftest full-input-parse
  (testing "Full input-text.txt parses successfully"
    (let [result (gp/parse (slurp "resources/input-text.txt"))]
      (is (not (insta/failure? result)) (str "Parse failure: " (pr-str result))))))
