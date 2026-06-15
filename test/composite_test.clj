(ns composite-test
  "Test all composite types and ID generation.
   Run: lein test composite-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.music-parser :as p]
            [core.domain.music-domain :as d]))

(defn score-children [text] (d/composite-children (:score (p/parse text))))

(deftest id-generation
  (testing "implicit auto-generated IDs"
    (let [children (score-children "[c4 d4]")]
      (is (= :SEQ.1 (:id (first children))) "first SEQ")))
  (testing "explicit keyword ID"
    (let [children (score-children "<< :piano [c4 d4] >>")]
      (is (= :piano (:id (first children))) "explicit PAR ID")))
  (testing "nested explicit IDs"
    (let [children (score-children "<< [ :1 c4 d4] [ :2 e4 f4] >>")
          par (first children)]
      (is (= :PAR (:type par)))
      (let [seqs (d/composite-children par)]
        (is (= :1 (:id (first seqs))))
        (is (= :2 (:id (second seqs)))))))
  (testing "keyword ID updates current container"
    (let [children (score-children "[ :phrase c4 d4 e4]")]
      (is (= :phrase (:id (first children))))))
  (testing "multiple implicit increment"
    (let [children (score-children "[c4 d4] [e4 f4] [g4 a4]")]
      (is (= :SEQ.1 (:id (nth children 0))))
      (is (= :SEQ.2 (:id (nth children 1))))
      (is (= :SEQ.3 (:id (nth children 2)))))))

(deftest sequence-seq
  (testing "flat sequence"
    (let [children (score-children "[c4 d4 e4]")
          c (first children)]
      (is (= 1 (count children)))
      (is (= :SEQ (:type c)))
      (is (= 3 (d/composite-count c)))))
  (testing "nested sequences"
    (let [children (score-children "[[c4 d4][e4 f4]]")
          c (first children)]
      (is (= :SEQ (:type c)))
      (is (= 2 (d/composite-count c)))
      (is (every? d/composite? (d/composite-children c))))))

(deftest parallel-par
  (testing "parallel collects SEQs"
    (let [children (score-children "<<[c4 d4] [e4 f4]>>")
          c (first children)]
      (is (= 1 (count children)))
      (is (= :PAR (:type c)))
      (is (= 2 (d/composite-count c)))
      (is (every? d/composite? (d/composite-children c)))))
  (testing "PAR can contain instructions"
    (let [sc (score-children "<<!mf [c4 d4] !ff [e4 f4]>>")
          par (first sc)
          children (d/composite-children par)]
      (is (some d/composite? children) "has composite child")
      (is (some #(and (map? %) (= :instruction (:type %))) children) "has instruction in children"))))

(deftest list-transient
  (testing "list flattens into parent container"
    (let [children (score-children "[ (c4 d4) e4]")
          c (first children)]
      (is (= :SEQ (:type c)))
      (is (= 3 (d/composite-count c)))))
  (testing "standalone list flattens to tokens"
    (let [ls (filter d/leaf? (:tokens (p/parse "(c4 d4 e4)")))]
      (is (= 3 (count ls))))))

(deftest data-composite
  (testing "data composite"
    (let [children (score-children "'[ c4 d4 ]'")
          c (first children)]
      (is (= 1 (count children)))
      (is (= :DATA (:type c)))
      (is (= 2 (d/composite-count c))))))

(deftest quote-composite
  (testing "quoted composite"
    (let [children (score-children "'( c4 d4 )")
          c (first children)]
      (is (= 1 (count children)))
      (is (= :QUOTE (:type c)))
      (is (= 2 (d/composite-count c))))))

(deftest algo-composite
  (testing "algo composite tokenizes correctly"
    (let [tokens (p/tokenize "@( c4 d4 )")]
      (is (= :ALGO (:type (first tokens))))
      (is (some #(= :NOTE (:type %)) tokens) "contains notes")))
  (testing "algo composite parses"
    (let [r (p/parse "@( c4 d4 )")]
      (is (:score r))
      (is (pos? (count (:tokens r))) "produces tokens"))))
