;; process.clj

(ns musics.algo.rhythmic.process
  "Rhythms drawn from a random process: weighted coin flips and Poisson events."
  (:require [musics.algo.numeric.spec :refer [pos-spec unit-spec]]
            [musics.algo.numeric.random :refer [exponential weighted-coin]]))


;; ------------------------------------------------------------
;; EVENT/RHYTHM GENERATORS
;; ------------------------------------------------------------

(defn random-rhythm
  "Generates a sequence of event times within num-beats.
   Each beat has density% chance of containing an event.
   Example: (random-rhythm 0.25 16 0.3) → sparse 16th-note pattern"
  {:algo {:short :random-onsets :in [] :out :onset
          :params {:beat-duration (assoc pos-spec :default 0.25 :doc "time per tick")
                   :num-beats     {:type :int :min 1 :max 256 :default 16 :doc "ticks"}
                   :density       (assoc unit-spec :default 0.5 :doc "chance per tick")}}}
  [beat-duration num-beats density]
  (let [events (map #(when (weighted-coin density) (* beat-duration %))
                     (range num-beats))]
    (filter some? events)))


(defn poisson-events
  "Event onset times within [0, duration), Poisson-process style: keeps
   drawing inter-arrival gaps from an exponential distribution with the
   given rate (expected events per unit duration -- the same rate
   parameter Xenakis used to control event density in Achorripsis, via
   exponential's own mean = 1/rate) and accumulating them, until
   the running total would exceed duration -- genuine stochastic
   clustering/spacing, rather than random-rhythm's per-tick coin-flip
   approximation of the same idea.

   (poisson-events 4 8) → a handful of onsets across an 8-beat phrase,
   averaging 4 per beat"
  {:algo {:short :poisson :in [] :out :onset
          :params {:rate     (assoc pos-spec :min 0.01 :default 2.0 :doc "events per unit")
                   :duration (assoc pos-spec :default 4.0 :doc "span")}}}
  [rate duration]
  (loop [t 0.0 events []]
    (let [gap (exponential (/ 1.0 rate))
          t'  (+ t gap)]
      (if (< t' duration)
        (recur t' (conj events t'))
        events))))
