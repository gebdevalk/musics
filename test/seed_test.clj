(ns ^:domain seed-test
  "Tests for algo.random.core's seedable rand-double/rand-int/choose/shuffle
   and with-seed, and that with-seed's reach extends into algo.random (built
   on top of algo.random.core's primitives). Run: lein test seed-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.core :as seed]
            [algo.random.core :as chance]
            [algo.random :as dist]
            [algo.random :as r]))

(deftest same-seed-reproduces-rand
  (is (= (seed/with-seed 42 (vec (repeatedly 10 seed/rand-double)))
         (seed/with-seed 42 (vec (repeatedly 10 seed/rand-double))))))

(deftest same-seed-reproduces-rand-int
  (is (= (seed/with-seed 42 (vec (repeatedly 10 #(seed/rand-int 1000))))
         (seed/with-seed 42 (vec (repeatedly 10 #(seed/rand-int 1000)))))))

(deftest same-seed-reproduces-shuffle
  (is (= (seed/with-seed 42 (seed/shuffle (range 20)))
         (seed/with-seed 42 (seed/shuffle (range 20))))))

(deftest different-seeds-usually-differ
  (is (not= (seed/with-seed 1 (vec (repeatedly 10 seed/rand-double)))
            (seed/with-seed 2 (vec (repeatedly 10 seed/rand-double))))))

(deftest with-seed-covers-distributions
  (is (= (seed/with-seed 7 [(dist/uniform 0 1) (dist/normal 0 1) (dist/beta 2 5)])
         (seed/with-seed 7 [(dist/uniform 0 1) (dist/normal 0 1) (dist/beta 2 5)]))))

(deftest with-seed-covers-chance
  (is (= (seed/with-seed 7 [(chance/choose [1 2 3 4 5])
                             (r/weighted-coin 0.5)
                             (chance/weighted-choose {:a 1 :b 1})])
         (seed/with-seed 7 [(chance/choose [1 2 3 4 5])
                             (r/weighted-coin 0.5)
                             (chance/weighted-choose {:a 1 :b 1})]))))

(deftest with-seed-covers-rand-ns
  (is (= (seed/with-seed 7 [(r/int-range 0 100)
                             (r/triangular 0 1 0.5)
                             (seed/shuffle [1 2 3 4 5])])
         (seed/with-seed 7 [(r/int-range 0 100)
                             (r/triangular 0 1 0.5)
                             (seed/shuffle [1 2 3 4 5])]))))

(deftest unseeded-use-is-unaffected
  ;; outside with-seed, *rng* stays a fresh, unseeded Random -- ordinary
  ;; calls should still vary run to run, same as clojure.core/rand
  (is (not= (dist/uniform 0 1e12) (dist/uniform 0 1e12))))
