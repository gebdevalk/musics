(ns musics.algo.rhythmic.process
  "Rhythms from a process: random events, clouds, genetic search, Markov and neural nets, constraint satisfaction."
  (:require [musics.algo.numeric.spec :refer [pos-spec unit-spec]]
            [musics.algo.numeric.random :as rand :refer [exponential weighted-coin]]))

;; ============================================================
;; process
;; ============================================================

;; ------------------------------------------------------------
;; EVENT/RHYTHM GENERATORS
;; ------------------------------------------------------------

(defn random-onsets
  "Generates a sequence of event times within num-beats.
   Each beat has density% chance of containing an event.
   Example: (random-onsets 0.25 16 0.3) → sparse 16th-note pattern"
  {:algo {:in [] :out :onset
          :params {:beat-duration (assoc pos-spec :default 0.25 :doc "time per tick")
                   :num-beats     {:type :int :min 1 :max 256 :default 16 :doc "ticks"}
                   :density       (assoc unit-spec :default 0.5 :doc "chance per tick")}}}
  [beat-duration num-beats density]
  (let [events (map #(when (weighted-coin density) (* beat-duration %))
                     (range num-beats))]
    (filter some? events)))


(defn poisson
  "Event onset times in [0, duration) of a Poisson process: inter-arrival gaps are drawn from an exponential distribution with the given rate (expected events per unit duration, as Xenakis used for event density in Achorripsis) and accumulated until the total would exceed duration. Real stochastic clustering, where random-onsets flips a coin per tick.
     (poisson 4 8) ;; a handful of onsets across 8 beats, about 4 per beat"
  {:algo {:in [] :out :onset
          :params {:rate     (assoc pos-spec :min 0.01 :default 2.0 :doc "events per unit")
                   :duration (assoc pos-spec :default 4.0 :doc "span")}}}
  [rate duration]
  (loop [t 0.0 events []]
    (let [gap (exponential (/ 1.0 rate))
          t'  (+ t gap)]
      (if (< t' duration)
        (recur t' (conj events t'))
        events))))

;; ============================================================
;; stochastic
;; ============================================================

;; Xenakis-style stochastic rhythm, genetic evolution, Markov and RNN generators. Every draw goes
;; through musics.algo.numeric.random, so a seed repeats it.

(defn stochastic
  "A binary pattern of the given length, its onsets placed by distribution
   (\"uniform\", \"gaussian\" or \"exponential\") at roughly the given
   density (0.0-1.0). gaussian reads mean and std (default length/2 and
   length/6), exponential decay (default 2.0); the others ignore them. For a
   Poisson process see poisson."
  {:algo {:in [] :out :pulse
          :params {:distribution {:type :string :default "gaussian" :doc "where onsets fall"
                                  :choices ["uniform" "gaussian" "exponential"]}
                   :length       {:type :int :min 1 :max 256 :default 16 :doc "pulses"}
                   :density      {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "how many onsets"}
                   :mean         {:type :any :default nil :doc "gaussian centre, nil = length/2"}
                   :std          {:type :any :default nil :doc "gaussian spread, nil = length/6"}
                   :decay        {:type :double :min 0.0 :max 100.0 :default 2.0 :doc "exponential fall-off"}}}}
  [distribution length density & {:keys [mean std decay] :or {decay 2.0}}]
  (case distribution
    "uniform"
    (let [num-ones (int (* length density))]
      (reduce #(assoc %1 %2 1) (vec (repeat length 0))
              (rand/choose-n num-ones (range length))))

    "gaussian"
    (let [mean  (or mean (/ length 2.0))
          std   (or std (/ length 6.0))
          probs (mapv #(Math/exp (* -0.5 (Math/pow (/ (- % mean) std) 2))) (range length))
          max-p (apply max probs)]
      (mapv (fn [p] (if (weighted-coin (* (/ p max-p) density)) 1 0)) probs))

    "exponential"
    (mapv (fn [i]
            (let [prob (Math/exp (/ (* (- decay) i) length))]
              (if (weighted-coin (* prob density 2)) 1 0)))
          (range length))

    (vec (repeat length 0))))

(defn cloud
  "A Xenakis-style granular \"cloud\" of num-events timings spread evenly
   across duration (seconds), each jittered by Gaussian noise (std
   time-std, scaled to the average inter-event gap), sorted ascending."
  {:algo {:in [] :out :onset :arity 3
          :params {:num-events {:type :int :min 1 :max 256 :default 16 :doc "events"}
                   :duration   {:type :double :min 0.0 :max ##Inf :default 4.0 :doc "span"}
                   :time-std   {:type :double :min 0.0 :max 1.0 :default 0.1 :doc "jitter, per gap"}}}}
  ([num-events duration] (cloud num-events duration 0.1))
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

(defn mutate
  "Flip each bit of pattern independently with probability mutation-rate."
  {:algo {:in [:pulse] :out :pulse :arity 2
          :params {:mutation-rate {:type :double :min 0.0 :max 1.0 :default 0.1 :doc "chance a bit flips"}}}}
  ([pattern] (mutate pattern 0.1))
  ([pattern mutation-rate]
   (mapv #(if (weighted-coin mutation-rate) (- 1 %) %) pattern)))

(defn crossover-genomes
  "Single-point crossover of two equal-length patterns -- returns
   [child1 child2]. crossover-point defaults to a random point strictly
   inside the pattern."
  ([a b] (crossover-genomes a b (rand/int-range 1 (count a))))
  ([a b crossover-point]
   {:pre [(= (count a) (count b))]}
   [(vec (concat (subvec (vec a) 0 crossover-point) (subvec (vec b) crossover-point)))
    (vec (concat (subvec (vec b) 0 crossover-point) (subvec (vec a) crossover-point)))]))

(defn crossover
  "One child of crossover-genomes: 0 starts as a and ends as b, 1 the
   other way round."
  {:algo {:in [:pulse :pulse]
          :out :pulse
          :params {:crossover-point {:type :int :min 0 :max 256 :default 4 :doc "where the parents swap"}
                   :child           {:type :int :min 0 :max 1 :default 0 :doc "which child"}}}}
  [a b crossover-point child]
  (let [n (min (count a) (count b))]
    (nth (crossover-genomes (take n a) (take n b) (min crossover-point n)) child)))

(defn genetic
  "Evolve a rhythmic pattern of pattern-length over generations, scored
   by fitness-fn (pattern -> higher-is-better number), via tournament
   selection + single-point crossover + per-bit mutation. elitism is
   the fraction of the fittest individuals carried over unchanged each
   generation. Returns the single best pattern found."
  {:algo {:in [] :out :pulse
          :params {:population-size {:type :int :min 2 :max 512 :default 20 :doc "patterns per generation"}
                   :pattern-length  {:type :int :min 1 :max 256 :default 16 :doc "pulses"}
                   :generations     {:type :int :min 0 :max 500 :default 20 :doc "generations"}
                   :fitness-fn      {:type :fn :default ##NaN :doc "pattern -> number, higher is fitter"}
                   :mutation-rate   {:type :double :min 0.0 :max 1.0 :default 0.1 :doc "chance a bit flips"}
                   :elitism         {:type :double :min 0.0 :max 1.0 :default 0.1 :doc "fraction kept unchanged"}}}}
  [population-size pattern-length generations fitness-fn
   & {:keys [mutation-rate elitism] :or {mutation-rate 0.1 elitism 0.1}}]
  (letfn [(rand-pattern [] (vec (repeatedly pattern-length #(rand/rand-int 0 2))))
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
                               c1 (mutate c1 mutation-rate)
                               c2 (mutate c2 mutation-rate)]
                           [(genome c1) (genome c2)]))
              filled (loop [pop elite]
                       (if (>= (count pop) population-size)
                         pop
                         (recur (into pop (children)))))]
          (recur (vec (take population-size filled)) (inc gen)))))))

;; ── Markov / RNN ─────────────────────────────────────────────

(defn chain-rhythm
  "Higher-order Markov chain rhythm generation: transitions maps a
   state (any value -- typically a vector of the last N beats) to a map
   of {next-state probability}. Walks from initial-state, appending the
   FIRST element of each new state to the output, until length beats
   are produced. A state with no entry in transitions falls back to a
   uniform-random binary next state."
  {:algo {:in [] :out :pulse
          :params {:transitions   {:type :map :default {[1 0] {[0 1] 0.7 [1 1] 0.3} [0 1] {[1 0] 0.8 [0 0] 0.2} [1 1] {[1 0] 1.0} [0 0] {[0 1] 1.0}} :doc "state -> {next-state prob}"}
                   :initial-state {:type :vector :default [1 0] :doc "first state"}
                   :length        {:type :int :min 1 :max 256 :default 16 :doc "pulses"}}}}
  [transitions initial-state length]
  (loop [pattern (vec initial-state) current-state initial-state]
    (if (>= (count pattern) length)
      (subvec pattern 0 length)
      (let [next-state (if (contains? transitions current-state)
                          (rand/weighted-choose (get transitions current-state))
                          (vec (repeatedly (count current-state) #(rand/rand-int 0 2))))]
        (recur (conj pattern (first next-state)) next-state)))))

(defn rnn
  "A deterministic toy-RNN continuation: seed-pattern is extended for iterations steps by running a small sigmoid-thresholded recurrent network (weights, a square matrix; default a fixed 3-neuron example) over its own previous state, appending the first neuron's binary output each step."
  {:algo {:in [:pulse] :out :pulse :arity 3
          :params {:weights    {:type :vector :default [[0.1 0.8 -0.3] [-0.2 0.5 0.7] [0.6 -0.1 0.4]] :doc "square matrix"}
                   :iterations {:type :int :min 0 :max 256 :default 8 :doc "pulses added"}}}}
  ([seed-pattern iterations] (rnn seed-pattern [[0.1 0.8 -0.3] [-0.2 0.5 0.7] [0.6 -0.1 0.4]] iterations))
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
  (stochastic "gaussian" {:mean 8 :std 2} 16 0.3)
  (cloud 5 2.0)
  (genetic 20 16 10 (fn [p] (/ (reduce + p) (count p))))
  (chain-rhythm {[1 0] {[0 1] 0.7 [1 1] 0.3}} [1 0] 8)
  (rnn [1 0 1] 10)
  )

;; ============================================================
;; constraint
;; ============================================================

;; Constraint-driven rhythm: all-interval rhythms, isorhythmic beat strength, backtracking constraint
;; satisfaction.

(defn all-interval
  "A binary pattern of the given length where the intervals between
   consecutive beats are (as much as possible) all different: starts
   with a beat at 0, then repeatedly jumps by a not-yet-used interval
   (falling back to any interval that still fits once every interval
   has been tried) until reaching the far end. A simplified approach,
   not a true all-interval series."
  {:algo {:in [] :out :pulse :arity 1
          :params {:length {:type :int :min 1 :max 256 :default 12 :doc "pulses"}}}}
  ([] (all-interval 12))
  ([length]
   (loop [pattern (assoc (vec (repeat length 0)) 0 1)
          used-intervals #{}
          current-pos 0]
     (if (>= current-pos (dec length))
       pattern
       (let [candidates (filter #(and (not (used-intervals %)) (< (+ current-pos %) length))
                                 (range 1 (inc (quot length 2))))
             candidates (if (seq candidates)
                          candidates
                          (filter #(< (+ current-pos %) length) (range 1 (inc (quot length 2)))))]
         (if (empty? candidates)
           pattern
           (let [interval (rand/choose (vec candidates))
                 pos' (+ current-pos interval)]
             (recur (if (< pos' length) (assoc pattern pos' 1) pattern)
                    (conj used-intervals interval)
                    pos'))))))))

(defn iso-strength
  "Isorhythmic beat-strength pattern: talea (a binary rhythm pattern)
   repeated repetitions times, each of its own 1s replaced by (color
   entry) + 1 -- so the result cycles talea's own ON/OFF shape against
   color's own strength values independently, the classic isorhythmic
   talea/color pairing but expressed as beat strength (1 or 2) rather
   than pitch."
  {:algo {:in [:pulse] :out :weight :arity 3
          :params {:color       {:type :vector :default [0 1] :doc "strength per onset, cycled"}
                   :repetitions {:type :int :min 1 :max 16 :default 3 :doc "talea repeats"}}}}
  ([talea color] (iso-strength talea color 3))
  ([talea color repetitions]
   (let [talea (vec talea) color (vec color) tn (count talea) cn (count color)]
     (vec (mapcat (fn [rep]
                     (map (fn [i]
                            (if (= 1 (nth talea i))
                              (inc (nth color (mod (+ (* rep tn) i) cn)))
                              0))
                          (range tn)))
                   (range repetitions))))))

(defn csp
  "A binary pattern of the given length that satisfies every predicate in constraints (each a fn from a full-length pattern to a boolean), by exhaustive depth-first backtracking (0 then 1 at each position). nil if none exists."
  {:algo {:in [] :out :pulse
          :params {:constraints {:type :vector :default [#(= 3 (reduce + %)) #(= 1 (first %))] :doc "fns pattern -> bool, all must hold"}
                   :length      {:type :int :min 1 :max 16 :default 8 :doc "pulses"}}}}
  [constraints length]
  (letfn [(satisfies-all? [pattern] (every? #(% pattern) constraints))
          (backtrack [pattern pos]
            (if (= pos length)
              (when (satisfies-all? pattern) pattern)
              (or (backtrack (assoc pattern pos 0) (inc pos))
                  (backtrack (assoc pattern pos 1) (inc pos)))))]
    (backtrack (vec (repeat length 0)) 0)))

(comment
  (iso-strength [1 0 1 1] [0 1] 3)
  (csp [#(>= (reduce + %) 2)] 6)
  )
