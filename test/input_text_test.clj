(ns input-text-test
  "Parse the comprehensive input-text.txt and verify it doesn't error.
   Run: lein test input-text-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.music-parser :as p]
            [input.reader.parser.vars :as vars]
            [core.domain.music-domain :as d]))

(def input-text (slurp "resources/input-text.txt"))

(deftest parse-comprehensive-input
  (testing "input-text.txt parses without errors"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars input-text)
          expanded  (vars/expand-vars cleaned)
          result    (p/parse expanded)
          score     (:score result)
          tokens    (:tokens result)]
      (is (some? score) "score exists")
      (is (pos? (count tokens)) (str "token count: " (count tokens)))
      (is (d/composite? score) "score is composite")
      (let [children (d/composite-children score)]
        (is (pos? (count children)) (str "score child count: " (count children))))))

  (testing "named composites from input-text"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars input-text)
          expanded  (vars/expand-vars cleaned)
          result    (p/parse expanded)
          children  (d/composite-children (:score result))]
      (let [named (filter #(and (d/composite? %)
                                (not= "SEQ.1" (:id %))
                                (not= "PAR.1" (:id %))
                                (not= "SEQ.2" (:id %))
                                (not= "SEQ.3" (:id %)))
                          children)]
        (is (seq named) "should have user-named composites"))))

  (testing "variable expansion works"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars input-text)
          expanded  (vars/expand-vars cleaned)]
      (is (not (.contains ^String expanded "$motif"))
          "$motif should be expanded away")
      (is (not (.contains ^String expanded "$phrase"))
          "$phrase should be expanded away")))

  (testing "no parse errors in output"
    (vars/clear-vars!)
    (let [[cleaned] (vars/extract-vars input-text)
          expanded  (vars/expand-vars cleaned)
          result    (p/parse expanded)
          errors    (filter #(= :parse-error (:type %)) (:tokens result))]
      (is (zero? (count errors)) (str "parse errors: " (pr-str errors))))))
