;; stochastic.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 7-9 -- Xenakis-style stochastic rhythm, genetic-algorithm evolution,
;; and Markov/RNN-style generators. Every random draw goes through
;; algo.random (seedable, wall-safe) rather than raw clojure.core/rand.

(ns algo.rithmic.stochastic
  (:require [algo.random :as rand]))

(defn stochastic-rhythm
  "Binary pattern of the given length, placed according to distribution
   ('uniform', 'gaussian', 'poisson', or 'exponential') at roughly the
   given density (0.0-1.0). params is a map of distribution-specific
   keys: :mean/:std for 'gaussian' (default length/2, length/6), :rate
   for 'poisson' (default density*10), :decay for 'exponential'
   (default 2.0)."
  [distribution params length density]
  (case distribution
    "uniform"
    (let [num-ones (int (* length density))]
      (reduce #(assoc %1 %2 1) (vec (repeat length 0))
              (rand/choose-n num-ones (range length))))

    "gaussian"
    (let [mean  (get params :mean (/ length 2.0))
          std   (get params :std (/ length 6.0))
          probs (mapv #(Math/exp (* -0.5 (Math/pow (/ (- % mean) std) 2))) (range length))
          max-p (apply max probs)]
      (mapv (fn [p] (if (rand/weighted-coin (* (/ p max-p) density)) 1 0)) probs))

    "poisson"
    (let [rate (get params :rate (* density 10))]
      (loop [pattern (vec (repeat length 0)) current 0]
        (if (>= current length)
          pattern
          (let [wait (rand/exponential (/ 1.0 rate))
                current' (+ current (int (/ (* wait length) 10)))]
            (if (< current' length)
              (recur (assoc pattern current' 1) current')
              pattern)))))

    "exponential"
    (let [decay (get params :decay 2.0)]
      (mapv (fn [i]
              (let [prob (Math/exp (/ (* (- decay) i) length))]
                (if (rand/weighted-coin (* prob density 2)) 1 0)))
            (range length)))

    (vec (repeat length 0))))

(defn cloud-rhythm
  "A Xenakis-style granular \"cloud\" of num-events timings spread evenly
   across duration (seconds), each jittered by Gaussian noise (std
   time-std, scaled to the average inter-event gap), sorted ascending."
  ([num-events duration] (cloud-rhythm num-events duration 0.1))
  ([num-events duration time-std]
   (let [gap (/ duration num-events)]
     (->> (range num-events)
          (map (fn [i]
                 (let [base (* i gap)
                       jitter (rand/normal 0 (* time-std gap))]
                   (-> (+ base jitter) (max 0) (min duration)))))
          sort
          vec))))

;; ── Genetic rhythm ───────────────────────────────────────────

(defn mutate-genome
  "Flip each bit of pattern independently with probability mutation-rate."
  ([pattern] (mutate-genome pattern 0.1))
  ([pattern mutation-rate]
   (mapv #(if (rand/weighted-coin mutation-rate) (- 1 %) %) pattern)))

(defn crossover-genomes
  "Single-point crossover of two equal-length patterns -- returns
   [child1 child2]. crossover-point defaults to a random point strictly
   inside the pattern."
  ([a b] (crossover-genomes a b (rand/int-range 1 (count a))))
  ([a b crossover-point]
   {:pre [(= (count a) (count b))]}
   [(vec (concat (subvec (vec a) 0 crossover-point) (subvec (vec b) crossover-point)))
    (vec (concat (subvec (vec b) 0 crossover-point) (subvec (vec a) crossover-point)))]))

(defn genetic-rhythm
  "Evolve a rhythmic pattern of pattern-length over generations, scored
   by fitness-fn (pattern -> higher-is-better number), via tournament
   selection + single-point crossover + per-bit mutation. elitism is
   the fraction of the fittest individuals carried over unchanged each
   generation. Returns the single best pattern found."
  [population-size pattern-length generations fitness-fn
   & {:keys [mutation-rate elitism] :or {mutation-rate 0.1 elitism 0.1}}]
  (letfn [(rand-pattern [] (vec (repeatedly pattern-length #(rand/rand-int 2))))
          (genome [pattern] {:pattern pattern :fitness (fitness-fn pattern)})
          (tournament [population]
            (apply max-key :fitness (rand/choose-n 3 population)))]
    (loop [population (mapv genome (repeatedly population-size rand-pattern))
           gen 0]
      (if (= gen generations)
        (:pattern (apply max-key :fitness population))
        (let [sorted (vec (sort-by :fitness > population))
              elite-count (int (* population-size elitism))
              elite (subvec sorted 0 elite-count)
              children (fn []
                         (let [p1 (tournament population)
                               p2 (tournament population)
                               [c1 c2] (crossover-genomes (:pattern p1) (:pattern p2))
                               c1 (mutate-genome c1 mutation-rate)
                               c2 (mutate-genome c2 mutation-rate)]
                           [(genome c1) (genome c2)]))
              filled (loop [pop elite]
                       (if (>= (count pop) population-size)
                         pop
                         (recur (into pop (children)))))]
          (recur (vec (take population-size filled)) (inc gen)))))))

;; ── Markov / RNN ─────────────────────────────────────────────

(defn markov-chain-rhythm
  "Higher-order Markov chain rhythm generation: transitions maps a
   state (any value -- typically a vector of the last N beats) to a map
   of {next-state probability}. Walks from initial-state, appending the
   FIRST element of each new state to the output, until length beats
   are produced. A state with no entry in transitions falls back to a
   uniform-random binary next state."
  [transitions initial-state length]
  (loop [pattern (vec initial-state) current-state initial-state]
    (if (>= (count pattern) length)
      (subvec pattern 0 length)
      (let [next-state (if (contains? transitions current-state)
                          (rand/weighted-choose (get transitions current-state))
                          (vec (repeatedly (count current-state) #(rand/rand-int 2))))]
        (recur (conj pattern (first next-state)) next-state)))))

(defn rnn-rhythm
  "Deterministic toy-RNN rhythm continuation: seed-pattern is extended
   for iterations steps by repeatedly running a tiny sigmoid-thresholded
   recurrent network (weights, a square matrix -- default a fixed
   3-neuron example) over its own previous state, appending the first
   neuron's binary output each step."
  ([seed-pattern iterations] (rnn-rhythm seed-pattern [[0.1 0.8 -0.3] [-0.2 0.5 0.7] [0.6 -0.1 0.4]] iterations))
  ([seed-pattern weights iterations]
   (let [n (count weights)]
     (loop [pattern (vec seed-pattern)
            state (mapv double (take n seed-pattern))
            i 0]
       (if (= i iterations)
         pattern
         (let [new-state (mapv (fn [row]
                                  (let [net (reduce + (map * state row))
                                        activation (/ 1.0 (+ 1.0 (Math/exp (- net))))]
                                    (if (> activation 0.5) 1 0)))
                                weights)]
           (recur (conj pattern (first new-state)) (mapv double new-state) (inc i))))))))

(comment
  (stochastic-rhythm "gaussian" {:mean 8 :std 2} 16 0.3)
  (cloud-rhythm 5 2.0)
  (genetic-rhythm 20 16 10 (fn [p] (/ (reduce + p) (count p))))
  (markov-chain-rhythm {[1 0] {[0 1] 0.7 [1 1] 0.3}} [1 0] 8)
  (rnn-rhythm [1 0 1] 10)
  )
