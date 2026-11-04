(ns ^:algo musics.algo.tree.tools-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.tree.tools :as tools]))

(deftest the-tools-keep-the-type-and-work-lazily
  (is (= [1 2 3 1 2] (take 5 (t/run (tools/cycle [1 2 3]) {}))))
  (is (= [1 2] (t/run (tools/take [1 2 3]) {:len 2})))
  (is (= #{1 2 3} (set (take 3 (t/run (tools/shuffle [1 2 3]) {})))))
  (is (= [2 4 6] (t/run (tools/map [1 2 3]) {:fn #(* 2 %)})))
  (is (= [2] (t/run (tools/filter [1 2 3]) {:fn even?})))
  (is (= [1/4 1/8] (t/run (tools/stretch [1/2 1/4]) {:factor 1/2}))))

(deftest scale-maps-one-range-onto-another
  (is (= [0.0 6.5 13.0] (t/run (tools/rescale [55 60 65]) {:from-lo 55.0 :from-hi 65.0 :to-lo 0.0 :to-hi 13.0})))
  (is (= [0.0 1.5 3.0] (t/run (tools/rescale [0 0.5 1]) {:to-hi 3.0})) "a factor: 0..1 onto 0..3")
  (is (= 4 (count (take 4 (t/run (tools/rescale (tools/cycle [0.1 0.9])) {})))) "lazy"))

(deftest a-number-range-onto-a-scale-is-scale-then-degrees
  (is (= [60 62 64 65 67] (t/run (lib/degrees->pitches (tools/rescale [0 1 2 3 4])) {:to-hi 1.0 :from-hi 1.0}))
      "whole numbers pass through")
  (is (= [60 67 72] (t/run (lib/degrees->pitches (tools/rescale [55 60 65])) {:from-lo 55.0 :from-hi 65.0 :to-lo 0.0 :to-hi 7.0}))
      "a walk's range onto one octave of steps"))

(deftest reverse-turns-the-items-round
  (is (= [3 2 1] (t/run (tools/reverse [1 2 3]) {})))
  (is (= [] (t/run (tools/reverse []) {})))
  (is (= [0 1 0 0 1 0 0 1] (t/run (tools/reverse lib/euclid) {})) "euclid, backwards"))

(deftest rotate-moves-the-front-round-to-the-back
  (is (= [2 3 1] (t/run (tools/rotate [1 2 3]) {})) "one place left by default")
  (is (= [3 1 2] (t/run (tools/rotate [1 2 3]) {:steps -1})) "negative goes right")
  (is (= [] (t/run (tools/rotate []) {})) "nothing to rotate")
  (is (= [0 1 0 0 1 0 1 0] (t/run (tools/rotate lib/euclid) {:steps 2}))
      "euclid, rotated: the tresillo two places on")
  (is (= [1 0 0 1 0 0 1 0] (t/run lib/euclid {})) "euclid itself takes no rotation"))

(deftest rotating-by-an-index-is-what-the-necklace-algo-gave
  ;; necklace picked the index-th unique rotation, wrapping: the same pattern as rotating by the index
  (doseq [n (range 1 7) m (range (bit-shift-left 1 n))
          :let [p (mapv #(bit-and 1 (bit-shift-right m %)) (range n))
                unique (vec (distinct (map #(vec (concat (drop % p) (take % p))) (range n))))]
          i (range 12)]
    (is (= (unique (mod i (count unique))) (t/run (tools/rotate p) {:steps i})))))
