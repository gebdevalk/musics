(ns musics.lang.vocab.algo-indisp
  "musics.lang's own `algo-indisp` vocabulary -- a mechanical bridge of
   algo.indisp.indispensability's own public API (Barlow
   indispensability and its adherence-driven reshaping layer -- see
   that ns's own docstring)."
  (:require [algo.indisp.indispensability :as indisp]
            [musics.lang.runtime :refer [push! pop! builtin]])
  (:refer-clojure :exclude [pop!]))

(defn vocab []
  (merge
    (builtin "indispensability" (fn [ctx] (push! ctx (indisp/indispensability (pop! ctx))))
             "( subdivisions -- ranks )" "Barlow indispensability rank per pulse, downbeat always highest")
    (builtin "normalize-weights" (fn [ctx] (push! ctx (indisp/normalize-weights (pop! ctx))))
             "( weights -- weights' )" "divides weights by their own max, landing them in [0,1]")
    (builtin "normalized-indispensability" (fn [ctx] (push! ctx (indisp/normalized-indispensability (pop! ctx))))
             "( subdivisions -- weights )" "indispensability + normalize-weights in one step")
    (builtin "tilt-probabilities" (fn [ctx] (let [adherence (pop! ctx) psi-vals (pop! ctx)]
                                                (push! ctx (indisp/tilt-probabilities psi-vals adherence))))
             "( psi-vals adherence -- probs )" "softmax over ranks/weights, temperature-scaled by adherence")
    (builtin "power-law-probabilities" (fn [ctx] (let [adherence (pop! ctx) psi-vals (pop! ctx)]
                                                     (push! ctx (indisp/power-law-probabilities psi-vals adherence))))
             "( psi-vals adherence -- probs )" "order-preserving power-law reshaping over ranks/weights by adherence")
    (builtin "density-grid" (fn [ctx] (let [density (pop! ctx) ranks (pop! ctx)]
                                          (push! ctx (indisp/density-grid ranks density))))
             "( ranks density -- grid )" "binary onset grid retaining the most indispensable density-fraction of pulses")))
