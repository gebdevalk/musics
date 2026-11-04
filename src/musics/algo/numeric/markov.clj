;; markov.clj

(ns musics.algo.numeric.markov
  "A Markov chain over a transition table, drawn from musics.algo.numeric.random."
  (:require [musics.algo.numeric.random :refer [weighted-choose]]))


;; ------------------------------------------------------------
;; MARKOV CHAIN + GENERATIVE PATCH
;; ------------------------------------------------------------

(defn markov-chain
  "Returns a function that walks through states using transition weights.
   transitions: {state {next-state weight, ...}, ...}
   Example: (markov-chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)"
  {:algo {:short :chain :pull {} :in [] :out :number
          :params {:transitions {:type :map :default {0 {4 2 3 1} 4 {0 1 5 1} 3 {0 1} 5 {4 1}} :doc "state -> {next weight}; numbers, e.g. scale degrees"}
                   :start-state {:type :int :min -128 :max 128 :default 0 :doc "first state"}}}}
  [transitions start-state]
  (let [state (atom start-state)]
    (fn []
      (let [next (weighted-choose (get transitions @state))]
        (reset! state next)
        next))))
