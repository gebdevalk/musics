(ns ^:algo musics.algo.melodic.serial-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.melodic.serial :as s]
            [musics.algo.random.core :as rc]))

(deftest a-row-holds-every-pitch-class-once
  (is (= (set (range 12)) (set (s/row :random))))
  (is (= 12 (count (s/row :random)))))

(deftest an-all-interval-row-has-every-interval-once
  (doseq [seed (range 5)]
    (let [r (rc/with-seed seed (s/row :all-interval))]
      (is (= (set (range 12)) (set r)))
      (is (= (set (range 1 12)) (set (map #(mod (- %2 %1) 12) r (rest r))))))))

(deftest row-forms
  (is (= [5 6 7 8] (s/row-form [0 1 2 3] :P 5)))
  (is (= [0 8 5] (s/row-form [0 4 7] :I 0)) "inverted around the first class")
  (is (= [3 2 1 0] (s/row-form [0 1 2 3] :R 0)))
  (is (= [2 3 4 5] (s/row-form [0 1 2 3] :RI 5))))

(deftest the-matrix-reads-p-across-and-i-down
  (let [r [0 5 7 2 11 10 1 9 3 4 8 6] m (s/matrix r)]
    (is (= r (first m)))
    (is (= (s/row-form r :I 0) (mapv first m)))
    (is (every? zero? (map-indexed (fn [i row] (nth row i)) m)))))

(deftest combinatorial-forms-complete-the-first-hexachord
  (is (= [[:P 6] [:I 11]] (s/combinatorial (range 12)))))

(deftest set-classes
  (is (= [0 3 7] (s/prime-form [0 4 7])) "major and minor triads are one class")
  (is (= [0 1 5 6] (s/prime-form [11 0 4 5])))
  (is (= [0 1 3 7] (s/prime-form [0 1 3 7])))
  (is (= [0 0 1 1 1 0] (s/interval-vector [0 4 7]))))

(deftest classes-become-pitches-from-the-root-up
  (is (= [72 71 73 65] (s/classes->pitches [0 11 13 5] 62))))

(deftest time-points-last-until-the-next-starts
  (is (= [3/16 3/4 5/16 1/4] (s/time-points->durations [0 3 3 8] 1/16)))
  (is (= 3/4 (reduce + (s/time-points->durations [0 4 8] 1/16))) "a cycle of points fills one bar"))
