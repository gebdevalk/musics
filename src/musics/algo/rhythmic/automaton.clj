(ns musics.algo.rhythmic.automaton
  "Elementary cellular automata (Wolfram) as rhythm: a row of cells, each
   generation the next from each cell and its two neighbours by the
   rule's bits, the rows one after another as pulses -- a bar per
   generation."
  (:require [musics.algo.random :as random]))

(defn- step
  "The next generation of `cells` under `rule`, the row's ends wrapping."
  [rule cells]
  (let [n (count cells)]
    (mapv (fn [i]
            (let [l (cells (mod (dec i) n)) c (cells i) r (cells (mod (inc i) n))]
              (if (bit-test rule (+ (* 4 l) (* 2 c) r)) 1 0)))
          (range n))))

(defn automaton
  "An elementary cellular automaton's generations as pulses: :width cells
   a bar, :steps bars, the first a single live cell in the middle or
   :random. Rule 90 draws Sierpinski's triangle, 30 chaos, 110 structure."
  {:algo {:short :ca :in [] :out :pulse
          :params {:rule  {:type :int :min 0 :max 255 :default 90 :doc "Wolfram's rule number"}
                   :width {:type :int :min 3 :max 64 :default 16 :doc "cells, pulses a bar"}
                   :steps {:type :int :min 1 :max 64 :default 8 :doc "generations, bars"}
                   :start {:type :keyword :choices [:single :random] :default :single :doc "the first generation"}}}}
  [rule width steps start]
  (let [first-gen (if (= :random start)
                    (vec (repeatedly width #(if (< (random/rand-double) 0.5) 1 0)))
                    (assoc (vec (repeat width 0)) (quot width 2) 1))]
    (vec (apply concat (take steps (iterate (partial step rule) first-gen))))))
