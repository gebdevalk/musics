(ns ^:domain music-algorithms-test
  "Tests for rhythm and melodic algorithms. Run: lein test music-algorithms-test"
  (:require [clojure.test :refer [deftest is]]
            [algorithm.music-algorithms :as a]))

(deftest psi-test
  (is (= 12 (a/psi 0 [2 3 2])))
  (is (= [1 1/4 3/4 1/2] (a/psi-fractions [2 2]))))

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

(deftest modular-test
  (is (= [1 0 0 0 1 0 0] (a/modular-rhythm 4 3 7 0))))

(deftest constraint-melody-test
  (let [melody (a/constraint-melody a/c-major 8 [a/no-repeat-constraint])]
    (is (= 8 (count melody)))
    (is (every? #(contains? (set a/c-major) %) melody))))