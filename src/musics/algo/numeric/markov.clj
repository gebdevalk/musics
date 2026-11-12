(ns musics.algo.numeric.markov
  "A Markov chain over a transition table, drawn from musics.algo.numeric.random."
  (:require [musics.algo.numeric.counter :as counter]
            [musics.algo.numeric.random :refer [weighted-choose]]))


;; ------------------------------------------------------------
;; MARKOV CHAIN + GENERATIVE PATCH
;; ------------------------------------------------------------

(defn chain
  "Returns a function that walks through states using transition weights.
   transitions: {state {next-state weight, ...}, ...}
   Example: (chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)"
  {:algo {:pull {} :in [] :out :number
          :params {:transitions {:type :map :default {0 {4 2 3 1} 4 {0 1 5 1} 3 {0 1} 5 {4 1}} :doc "state -> {next weight}; numbers, e.g. scale degrees"}
                   :start-state {:type :int :min -128 :max 128 :default 0 :doc "first state"}}}}
  [transitions start-state]
  (let [state (atom start-state)]
    (fn []
      (let [next (weighted-choose (get transitions @state))]
        (reset! state next)
        next))))


(defn chain-seq
  "A Markov chain as a lazy seq of states, each drawn from the transition weights of the one
   before by counter-based random (musics.algo.numeric.counter): the same seed, table and start
   give the same stream, however many read it or from where.
     (take 6 (chain-seq 7 {0 {4 2, 3 1}, 4 {0 1}, 3 {0 1}} 0))"
  {:algo {:in [] :out :number
          :params {:seed        {:type :int :min 0 :max 1000000 :default 1 :doc "the stream"}
                   :transitions {:type :map :default {0 {4 2 3 1} 4 {0 1 5 1} 3 {0 1} 5 {4 1}} :doc "state -> {next weight}"}
                   :start-state {:type :int :min -128 :max 128 :default 0 :doc "state before the first"}}}}
  [seed transitions start-state]
  (map first
       (rest (iterate (fn [[state i]]
                        (let [t (get transitions state)]
                          [(counter/pick-weighted seed i (keys t) (vals t)) (inc i)]))
                      [start-state 0]))))
