(ns musics.algo.numeric.counter-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.counter :as c]))

(deftest a-draw-is-a-pure-function-of-seed-and-index
  (is (= (c/draw 42 7) (c/draw 42 7)))
  (is (not= (c/draw 42 7) (c/draw 43 7)) "another seed")
  (is (not= (c/draw 42 7) (c/draw 42 8)) "another index")
  (is (= (c/draw 5 100) (nth (c/draws 5) 100)) "read at 100 without the 100 before it")
  (is (every? #(and (<= 0.0 %) (< % 1.0)) (take 2000 (c/draws 1))))
  (is (< 0.45 (/ (reduce + (take 5000 (c/draws 9))) 5000) 0.55) "uniform enough: the mean near a half"))

(deftest draw-int-stays-in-range-and-covers-it
  (let [xs (map #(c/draw-int 3 % 6) (range 600))]
    (is (every? #(<= 0 % 5) xs))
    (is (= (set (range 6)) (set xs)))))

(deftest a-permutation-is-a-permutation-and-repeats
  (is (= (range 10) (sort (c/permutation 1 0 10))))
  (is (= (c/permutation 1 3 10) (c/permutation 1 3 10)))
  (is (not= (c/permutation 1 0 10) (c/permutation 1 1 10)) "a pass of its own")
  (is (= [0] (c/permutation 1 0 1)))
  (is (= [] (c/permutation 1 0 0))))

(deftest shuffled-passes-the-pool-in-a-new-order-each-time
  (let [pool [:a :b :c :d]
        out  (vec (take 12 (c/shuffled 7 pool)))]
    (is (every? #(= (set pool) (set %)) (partition 4 out)) "each pass holds the pool once")
    (is (not= (take 4 out) (take 4 (drop 4 out))) "the passes differ")
    (is (= out (vec (take 12 (c/shuffled 7 pool)))) "the same seed, the same stream")
    (is (nil? (c/shuffled 7 [])))))

(deftest shuffled-windows-is-lazy-and-keeps-each-window
  (let [out (take 24 (c/shuffled-windows 4 11 (range)))]
    (is (every? (fn [[k w]] (= (set (range (* 4 k) (* 4 (inc k)))) (set w)))
                (map-indexed vector (partition 4 out))) "each window holds its own elements")
    (is (not= (range 24) out) "and they are reordered"))
  (is (= [0 1 2 3 4 5 6] (sort (c/shuffled-windows 4 1 (range 7)))) "a short last window shuffles alone"))

(deftest pick-and-pick-weighted-are-pure
  (is (= (c/pick 3 5 [:a :b :c]) (c/pick 3 5 [:a :b :c])))
  (is (= #{:a :b :c} (set (map #(c/pick 3 % [:a :b :c]) (range 100)))))
  (let [xs (map #(c/pick-weighted 3 % [:a :b] [9 1]) (range 2000))
        n  (count (filter #{:a} xs))]
    (is (< 1700 n 1900) "about nine in ten: a")
    (is (every? #{:a :b} xs)))
  (is (= :b (c/pick-weighted 3 0 [:a :b] [0 1])) "a zero weight is never picked"))
