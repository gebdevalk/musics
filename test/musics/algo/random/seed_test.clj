(ns ^:algo musics.algo.random.seed-test
  "Tests for musics.algo.random.core's with-seed, and that its reach extends
   into musics.algo.random's rand-double/rand-int/choose/shuffle and
   everything built on top of them. Run: lein test musics.algo.random.seed-test"
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.random.core :as seed]
            [musics.algo.random :as r]))

(deftest same-seed-reproduces-rand
  (is (= (seed/with-seed 42 (vec (repeatedly 10 r/rand-double)))
         (seed/with-seed 42 (vec (repeatedly 10 r/rand-double))))))

(deftest same-seed-reproduces-rand-int
  (is (= (seed/with-seed 42 (vec (repeatedly 10 #(r/rand-int 0 1000))))
         (seed/with-seed 42 (vec (repeatedly 10 #(r/rand-int 0 1000)))))))

(deftest same-seed-reproduces-shuffle
  (is (= (seed/with-seed 42 (r/shuffle (range 20)))
         (seed/with-seed 42 (r/shuffle (range 20))))))

(deftest different-seeds-usually-differ
  (is (not= (seed/with-seed 1 (vec (repeatedly 10 r/rand-double)))
            (seed/with-seed 2 (vec (repeatedly 10 r/rand-double))))))

(deftest with-seed-covers-distributions
  (is (= (seed/with-seed 7 [(r/uniform 0 1) (r/normal 0 1) (r/beta 2 5)])
         (seed/with-seed 7 [(r/uniform 0 1) (r/normal 0 1) (r/beta 2 5)]))))

(deftest with-seed-covers-chance
  (is (= (seed/with-seed 7 [(r/choose [1 2 3 4 5])
                             (r/weighted-coin 0.5)
                             (r/weighted-choose {:a 1 :b 1})])
         (seed/with-seed 7 [(r/choose [1 2 3 4 5])
                             (r/weighted-coin 0.5)
                             (r/weighted-choose {:a 1 :b 1})]))))

(deftest with-seed-covers-rand-ns
  (is (= (seed/with-seed 7 [(r/int-range 0 100)
                             (r/triangular 0 1 0.5)
                             (r/shuffle [1 2 3 4 5])])
         (seed/with-seed 7 [(r/int-range 0 100)
                             (r/triangular 0 1 0.5)
                             (r/shuffle [1 2 3 4 5])]))))

(deftest unseeded-use-is-unaffected
  ;; outside with-seed, *rng* stays a fresh, unseeded Random -- ordinary
  ;; calls should still vary run to run, same as clojure.core/rand
  (is (not= (r/uniform 0 1e12) (r/uniform 0 1e12))))

(deftest seed-zero-still-draws
  ;; xorshift's state 0 never leaves 0: every draw would be 0.0, and a
  ;; rejection sampler (gamma, beta) would loop forever
  (seed/with-seed 0
    (is (< 1 (count (distinct (repeatedly 8 r/rand-double)))))
    (is (number? (r/gamma 2.0 1.0)))))

(deftest draws-are-uniform
  ;; xorshift32 with each shift applied to the one before: a mean of 1/2
  ;; and every tenth about as full as the others
  (seed/seed! 1)
  (let [xs (repeatedly 100000 r/rand-double)]
    (is (< 0.49 (/ (reduce + xs) 100000) 0.51))
    (is (every? #(< 9500 % 10500) (vals (frequencies (map #(int (* 10 %)) xs))))))
  (seed/seed! 1)
  (is (< 29000 (get (frequencies (repeatedly 40000 #(r/weighted-choose [0 1] [3 1]))) 0) 31000)
      "3,1 weights pick the first three times as often"))
