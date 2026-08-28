(ns ^:algo rhythm-test
  "Tests for rhythm-pattern generators. Run: lein test rhythm-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.rhythm :as a]))

(deftest euclidean-test
  (is (= [1 1 1 0 0 0 0 0] (a/euclidean-rhythm 3 8)))
  (is (= [1 1 1 1 0 0 0 0] (a/euclidean-rhythm 4 8)))
  (is (= [1 0 0 0 0] (a/euclidean-rhythm 1 5)))
  (is (= [0 0 0 0] (a/euclidean-rhythm 0 4))))

(deftest fibonacci-test
  (is (= [1 1 1 1 0 1 0 0 1 0 0 0 0] (a/fibonacci-rhythm 13))))

(deftest prime-test
  (let [r (a/prime-rhythm 10)]
    (is (= 10 (count r)))
    (is (= 1 (nth r 2)))
    (is (= 1 (nth r 3)))
    (is (= 1 (nth r 5)))
    (is (= 1 (nth r 7)))))
