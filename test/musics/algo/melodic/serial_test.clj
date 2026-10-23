(ns ^:algo musics.algo.melodic.serial-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.melodic.serial :as s]
            [musics.algo.random.core :as rc]))

(deftest a-row-holds-every-pitch-class-once
  (is (= (set (range 12)) (set (s/row :random))))
  (is (= 12 (count (s/row :random)))))

(deftest an-all-interval-row-has-every-interval-once
  (doseq [seed (range 5)]
    (let [r (rc/with-seed seed (s/row :allInterval))]
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

(deftest infrapolate-inserts-before-each-tone
  (is (= [9 1 9 2 9 3] (s/infrapolate [1 2 3] [9]))))

(deftest ultrapolate-inserts-after-every-tone-including-the-last
  (is (= [1 9 2 9 3 9] (s/ultrapolate [1 2 3] [9]))))

(deftest interpolate-inserts-between-consecutive-tones-only
  (is (= [1 9 2 9 3] (s/interpolate [1 2 3] [9]))
      "no trailing insertion after the last tone -- nothing left to interpolate toward"))

(deftest interpolate-passes-through-fewer-than-2-tones-unchanged
  (is (= [1] (s/interpolate [1] [9])))
  (is (= [] (s/interpolate [] [9]))))

(deftest mixed-polations-combines-all-three-layers-in-slonimskys-own-order
  ;; per tone: infra, principal, ultra (unless last & not ultra-after-last),
  ;; inter (unless last)
  (is (= [8 1 7 9 8 2 7 9 8 3]
         (s/mixed-polations [1 2 3] [8] [9] [7]))))

(deftest mixed-polations-ultra-after-last-forces-ultra-on-the-final-tone-too
  (is (= [1 5 2 5] (s/mixed-polations [1 2] nil nil [5] true))))

(deftest mixed-polations-with-no-insertions-is-the-identity
  (is (= [1 2 3] (s/mixed-polations [1 2 3]))))

(deftest mixed-polations-any-layer-can-be-independently-disabled
  (is (= [1 9 2 9 3] (s/mixed-polations [1 2 3] nil [9] nil))
      "only inter given -- same as interpolate")
  (is (= [9 1 9 2 9 3] (s/mixed-polations [1 2 3] [9] nil nil))
      "only infra given -- same as infrapolate"))

