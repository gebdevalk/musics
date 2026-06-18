(ns grammar-parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.grammar-parser :as gp]
            [instaparse.core :as insta]))

(deftest note-parses-not-bareword
  (testing "Note c4 parses as Note, not BareWord"
    (let [result (gp/parse-string "c4")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Note")
            (str "Expected :Note in tree, got: " tree-str))
        (is (not (clojure.string/includes? tree-str ":BareWord"))
            (str "Did not expect :BareWord in tree, got: " tree-str)))))

  (testing "Rest r4 parses as Rest"
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
            (str "Expected :Chord in tree, got: " tree-str))))))

(deftest drum-parses
  (testing "Drum x8\\kick parses as Drum"
    (let [result (gp/parse-string "x8\\kick")]
      (is (not (insta/failure? result)))
      (let [tree-str (pr-str result)]
        (is (clojure.string/includes? tree-str ":Drum")
            (str "Expected :Drum in tree, got: " tree-str))))))

(deftest composites-parse
  (testing "Sequence"
    (is (not (insta/failure? (gp/parse-string "{c4 d4 e4}")))))
  (testing "Named sequence"
    (is (not (insta/failure? (gp/parse-string "{verse c4 d4}")))))
  (testing "Parallel"
    (is (not (insta/failure? (gp/parse-string "<<c4 e4 g4>>")))))
  (testing "Data"
    (is (not (insta/failure? (gp/parse-string "[c4 d4 e4]")))))
  (testing "List"
    (is (not (insta/failure? (gp/parse-string "(c4 d4 e4)")))))
  (testing "Quoted"
    (is (not (insta/failure? (gp/parse-string "'(c4 d4 e4)"))))))

(deftest instructions-parse
  (testing "Bang constant"
    (is (not (insta/failure? (gp/parse-string "!mf")))))
  (testing "Assignment int"
    (is (not (insta/failure? (gp/parse-string "!art:80")))))
  (testing "Assignment keyword"
    (is (not (insta/failure? (gp/parse-string "!vol:mf")))))
  (testing "Key assignment"
    (is (not (insta/failure? (gp/parse-string "!key:C.major")))))
  (testing "Ramp up"
    (is (not (insta/failure? (gp/parse-string "!vol:<")))))
  (testing "Ramp smooth down"
    (is (not (insta/failure? (gp/parse-string "!vol:s>"))))))