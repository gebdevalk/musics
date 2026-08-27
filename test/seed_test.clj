(ns ^:domain seed-test
  "Tests for algo.random.core's seedable rand-double/rand-int/choose/shuffle
   and with-seed. Run: lein test seed-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.core :as seed]
            [algo.random.core :as dist]
            [algo.random.core :as chance]
            [algo.random.core :as r]))

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
  (is (= (seed/with-seed 7 [(dist/rand-uniform 0 1) (dist/rand-normal 0 1) (dist/rand-beta 2 5)])
         (seed/with-seed 7 [(dist/rand-uniform 0 1) (dist/rand-normal 0 1) (dist/rand-beta 2 5)]))))

(deftest with-seed-covers-chance
  (is (= (seed/with-seed 7 [(chance/choose [1 2 3 4 5])
                             (chance/weighted-coin 0.5)
                             (chance/weighted-choose {:a 1 :b 1})])
         (seed/with-seed 7 [(chance/choose [1 2 3 4 5])
                             (chance/weighted-coin 0.5)
                             (chance/weighted-choose {:a 1 :b 1})]))))

(deftest with-seed-covers-rand-ns
  (is (= (seed/with-seed 7 [(r/rand-int-range 0 100)
                             (r/rand-triangular 0 1 0.5)
                             (r/shuffle [1 2 3 4 5])])
         (seed/with-seed 7 [(r/rand-int-range 0 100)
                             (r/rand-triangular 0 1 0.5)
                             (r/shuffle [1 2 3 4 5])]))))

(deftest unseeded-use-is-unaffected
  ;; outside with-seed, *rng* stays a fresh, unseeded Random -- ordinary
  ;; calls should still vary run to run, same as clojure.core/rand
  (is (not= (dist/rand-uniform 0 1e12) (dist/rand-uniform 0 1e12))))
