(ns ^:domain stochastic-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rithmic.stochastic :as st]))

(deftest uniform-stochastic-rhythm-hits-the-exact-density
  ;; uniform placement samples without replacement, so the count of ones
  ;; is always exactly (int (* length density)), not just approximate
  (is (= 8 (reduce + (st/stochastic-rhythm "uniform" {} 16 0.5)))))

(deftest gaussian-stochastic-rhythm-clusters-near-the-mean
  (let [pattern (st/stochastic-rhythm "gaussian" {:mean 8 :std 2} 16 0.9)
        near-mean (reduce + (subvec pattern 5 11))
        far-from-mean (+ (reduce + (subvec pattern 0 3)) (reduce + (subvec pattern 13 16)))]
    (is (> near-mean far-from-mean)
        "high density near the mean should dominate the sparse tails")))

(deftest poisson-stochastic-rhythm-stays-in-bounds
  (let [pattern (st/stochastic-rhythm "poisson" {} 16 0.5)]
    (is (= 16 (count pattern)))
    (is (every? #{0 1} pattern))))

(deftest exponential-stochastic-rhythm-front-loads
  (let [pattern (st/stochastic-rhythm "exponential" {:decay 4.0} 20 0.5)]
    (is (= 1 (first pattern)) "decay=4 at i=0 always fires (prob 1.0)")))

(deftest cloud-rhythm-returns-sorted-timings-within-duration
  (let [timings (st/cloud-rhythm 8 3.0)]
    (is (= 8 (count timings)))
    (is (apply <= timings))
    (is (every? #(<= 0 % 3.0) timings))))

(deftest mutate-genome-only-flips-bits-with-probability-1
  (is (= [0 1 0] (st/mutate-genome [1 0 1] 1.0))))

(deftest mutate-genome-never-flips-with-probability-0
  (is (= [1 0 1] (st/mutate-genome [1 0 1] 0.0))))

(deftest crossover-genomes-splits-at-a-fixed-point
  (is (= [[1 1 1 1] [0 0 0 0]]
         (st/crossover-genomes [1 1 0 0] [0 0 1 1] 2))))

(deftest genetic-rhythm-finds-the-trivial-optimum
  ;; fitness = fraction of ones; an easy target the GA should reliably
  ;; converge on well within 15 generations
  (let [best (st/genetic-rhythm 20 8 15 (fn [p] (/ (reduce + p) (count p))))]
    (is (= 8 (count best)))
    (is (every? #{0 1} best))
    (is (>= (reduce + best) 6) "should converge close to all-ones")))

(deftest markov-chain-rhythm-starts-with-the-initial-state
  (let [pattern (st/markov-chain-rhythm
                 {[1 0] {[0 1] 1.0} [0 1] {[1 0] 1.0}}
                 [1 0] 8)]
    (is (= 8 (count pattern)))
    (is (= [1 0] (subvec pattern 0 2)))))

(deftest rnn-rhythm-extends-the-seed-deterministically
  (let [a (st/rnn-rhythm [1 0 1] 10)
        b (st/rnn-rhythm [1 0 1] 10)]
    (is (= a b) "no randomness -- same seed/weights always gives the same continuation")
    (is (= 13 (count a)))
    (is (= [1 0 1] (subvec a 0 3)))))
