(ns ^:algo musics.algo.rhythmic.process-test
  "Tests for musics.algo.rhythmic.process."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.rhythmic.process :as p]
            [musics.algo.numeric.rng :as seed]))

;; ============================================================
;; process-test
;; ============================================================

(deftest random-rhythm-at-full-density-fires-every-beat
  (is (= [0.0 0.25 0.5 0.75] (p/random-onsets 0.25 4 1.0))))


(deftest random-rhythm-at-zero-density-fires-no-beats
  (is (= [] (p/random-onsets 0.25 4 0.0))))


(deftest poisson-events-stay-within-duration
  (doseq [t (p/poisson 4 8)]
    (is (< 0.0 t 8.0))))


(deftest poisson-events-are-strictly-increasing
  (let [events (p/poisson 4 8)]
    (is (apply < events) "onset times must be strictly increasing")))


(deftest poisson-events-average-count-tracks-rate
  ;; expected count = rate * duration; check it's in the right ballpark
  ;; across many draws rather than asserting an exact count
  (let [counts (repeatedly 200 #(count (p/poisson 4 8)))
        mean   (/ (reduce + counts) (count counts))]
    (is (< 20 mean 44) (str "mean event count " mean " far from expected 32"))))


(deftest poisson-events-at-zero-duration-is-empty
  (is (= [] (p/poisson 4 0))))


(deftest poisson-events-is-seedable
  (is (= (seed/with-seed 9 (p/poisson 4 8))
         (seed/with-seed 9 (p/poisson 4 8)))))

;; ============================================================
;; stochastic-test
;; ============================================================

(deftest uniform-stochastic-rhythm-hits-the-exact-density
  ;; uniform placement samples without replacement, so the count of ones
  ;; is always exactly (int (* length density)), not just approximate
  (is (= 8 (reduce + (p/stochastic "uniform" 16 0.5)))))

(deftest gaussian-stochastic-rhythm-clusters-near-the-mean
  (let [pattern (p/stochastic "gaussian" 16 0.9 :mean 8 :std 2)
        near-mean (reduce + (subvec pattern 5 11))
        far-from-mean (+ (reduce + (subvec pattern 0 3)) (reduce + (subvec pattern 13 16)))]
    (is (> near-mean far-from-mean)
        "high density near the mean should dominate the sparse tails")))

(deftest poisson-stochastic-rhythm-stays-in-bounds
  (let [pattern (p/stochastic "poisson" 16 0.5)]
    (is (= 16 (count pattern)))
    (is (every? #{0 1} pattern))))

(deftest exponential-stochastic-rhythm-front-loads
  (let [pattern (p/stochastic "exponential" 20 0.5 :decay 4.0)]
    (is (= 1 (first pattern)) "decay=4 at i=0 always fires (prob 1.0)")))

(deftest cloud-rhythm-returns-sorted-timings-within-duration
  (let [timings (p/cloud 8 3.0)]
    (is (= 8 (count timings)))
    (is (apply <= timings))
    (is (every? #(<= 0 % 3.0) timings))))

(deftest mutate-genome-only-flips-bits-with-probability-1
  (is (= [0 1 0] (p/mutate [1 0 1] 1.0))))

(deftest mutate-genome-never-flips-with-probability-0
  (is (= [1 0 1] (p/mutate [1 0 1] 0.0))))

(deftest crossover-genomes-splits-at-a-fixed-point
  (is (= [[1 1 1 1] [0 0 0 0]]
         (p/crossover-genomes [1 1 0 0] [0 0 1 1] 2))))

(deftest genetic-rhythm-finds-the-trivial-optimum
  ;; fitness = fraction of ones; an easy target the GA should reliably
  ;; converge on well within 15 generations
  (let [best (p/genetic 20 8 15 (fn [p] (/ (reduce + p) (count p))))]
    (is (= 8 (count best)))
    (is (every? #{0 1} best))
    (is (>= (reduce + best) 6) "should converge close to all-ones")))

(deftest markov-chain-rhythm-starts-with-the-initial-state
  (let [pattern (p/chain-rhythm
                 {[1 0] {[0 1] 1.0} [0 1] {[1 0] 1.0}}
                 [1 0] 8)]
    (is (= 8 (count pattern)))
    (is (= [1 0] (subvec pattern 0 2)))))

(deftest rnn-rhythm-extends-the-seed-deterministically
  (let [a (p/rnn [1 0 1] 10)
        b (p/rnn [1 0 1] 10)]
    (is (= a b) "no randomness -- same seed/weights always gives the same continuation")
    (is (= 13 (count a)))
    (is (= [1 0 1] (subvec a 0 3)))))

;; ============================================================
;; constraint-test
;; ============================================================

(deftest isorhythm-strength-cycles-talea-and-color-independently
  (is (= [1 0 1 2 1 0 1 2 1 0 1 2]
         (p/iso-strength [1 0 1 1] [0 1] 3))))

(deftest constraint-satisfaction-rhythm-is-fully-deterministic
  ;; the reference this ports draws a random initial pattern that
  ;; provably never affects the result -- confirmed live against the
  ;; actual Python source across 30 different seeds, always the same
  ;; answer -- so this port (no randomness at all) must match exactly
  (let [at-least-2-ones (fn [p] (>= (reduce + p) 2))
        no-two-consecutive (fn [p] (every? (fn [i] (not (and (= 1 (nth p i)) (= 1 (nth p (inc i))))))
                                            (range (dec (count p)))))]
    (is (= [0 0 0 1 0 1]
           (p/csp [at-least-2-ones no-two-consecutive] 6)))))

(deftest constraint-satisfaction-rhythm-returns-nil-when-impossible
  (is (nil? (p/csp [(fn [p] (> (reduce + p) (count p)))] 4))))

(deftest all-interval-rhythm-starts-with-a-beat-and-has-the-right-length
  (let [pattern (p/all-interval 12)]
    (is (= 12 (count pattern)))
    (is (= 1 (first pattern)))
    (is (every? #{0 1} pattern))))
