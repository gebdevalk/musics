(ns ^:algo algo-logic-tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [algo.logic.tree :as lt]
            [algo.tree :as t]
            [algo.tree.lib]))

(deftest find-algos-by-category-types-and-params
  (let [shorts #(set (map :short (lt/find-algos %)))]
    (is (contains? (shorts {:category "rhythmic" :param :k}) :euclid))
    (is (contains? (shorts {:in :pulse :out :pitch}) :gate))
    (is (every? #(#{:pulse :same} (:out %)) (lt/find-algos {:out :pulse}))
        "a :same algo gives a grid when fed one; an :any output doesn't count")
    (is (= 1 (count (lt/find-algos {:short :euclid}))))
    (is (empty? (lt/find-algos {:category "no-such"})))))

(deftest hole-types-follow-same-nodes
  (let [root {:algo :notes :children [{:algo :gate :children [{:algo :euclid :children []}
                                                              {:algo :cycled :children [nil]}]}]}]
    (is (= {[0 1 0] :pitch} (lt/hole-types root)))
    (is (lt/fits? root))
    (is (not (lt/fits? (assoc-in root [:children 0 :children 1 :children 0] {:algo :euclid :children []})))
        "a grid under cycled in a pitches slot")
    (is (= {[] :any} (lt/hole-types nil)) "an empty draft's root is open")))

(deftest how-finds-runnable-bridges
  (let [trees (lt/how :pulse :leaf 3)]
    (is (= 3 (count trees)))
    (is (every? #(some #{:input} (flatten %)) trees))
    (is (every? #(= :leaf (t/out-type (t/as-node (lt/->tree %)))) trees))
    (is (seq (t/run (lt/->tree (first trees)) {:input [1 0 1 1]})) "it runs on your input"))
  (testing "no bridge answers quickly, empty"
    (is (empty? (lt/how :leaf :pitch 1)))))

(deftest feeds-why-not-examples-surprise
  (is (= :gate (some #{:gate} (lt/feeds :pulse))))
  (is (some #{:gate} (lt/feeds algo.tree.lib/euclid)) "a constructor's output")
  (is (re-find #"scale gives :pitch, this slot wants :pulse" (lt/why-not :scale :pulse)))
  (is (re-find #"fits" (lt/why-not :euclid :pulse)))
  (let [ex (lt/examples :gate 2)]
    (is (= 2 (count ex)))
    (is (every? #(= :gate (first %)) ex))
    (is (every? #(seq (t/run (lt/->tree %) {})) ex) "examples run with defaults"))
  (let [tr (lt/surprise :leaf)]
    (is (= :leaf (t/out-type (t/as-node (lt/->tree tr)))))))

(deftest steps-between-types
  (is (= 0 (lt/steps :pulse :pulse)))
  (is (= 1 (lt/steps :pulse :pitch)) "gate")
  (is (= 2 (lt/steps :pulse :leaf)))
  (is (nil? (lt/steps :pitch :pulse)) "nothing makes rhythm from pitches"))
