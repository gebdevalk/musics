(ns ^:algo musics.algo.tree.tools-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]))

(deftest the-tools-keep-the-type-and-work-lazily
  (is (= [1 2 3 1 2] (take 5 (t/run (lib/cycle> [1 2 3]) {}))))
  (is (= [1 2] (t/run (lib/take> [1 2 3]) {:len 2})))
  (is (= #{1 2 3} (set (take 3 (t/run (lib/shuffle> [1 2 3]) {})))))
  (is (= [2 4 6] (t/run (lib/map> [1 2 3]) {:fn #(* 2 %)})))
  (is (= [2] (t/run (lib/filter> [1 2 3]) {:fn even?})))
  (is (= [1/4 1/8] (t/run (lib/stretch> [1/2 1/4]) {:factor 1/2}))))

(deftest scale-maps-one-range-onto-another
  (is (= [0.0 6.5 13.0] (t/run (lib/scale> [55 60 65]) {:from-lo 55.0 :from-hi 65.0 :to-lo 0.0 :to-hi 13.0})))
  (is (= [0.0 1.5 3.0] (t/run (lib/scale> [0 0.5 1]) {:to-hi 3.0})) "a factor: 0..1 onto 0..3")
  (is (= 4 (count (take 4 (t/run (lib/scale> (lib/cycle> [0.1 0.9])) {})))) "lazy"))

(deftest a-number-range-onto-a-scale-is-scale-then-degrees
  (is (= [60 62 64 65 67] (t/run (lib/degrees->pitches (lib/scale> [0 1 2 3 4])) {:to-hi 1.0 :from-hi 1.0}))
      "whole numbers pass through")
  (is (= [60 67 72] (t/run (lib/degrees->pitches (lib/scale> [55 60 65])) {:from-lo 55.0 :from-hi 65.0 :to-lo 0.0 :to-hi 7.0}))
      "a walk's range onto one octave of steps"))
