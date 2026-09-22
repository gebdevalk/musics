(ns musics.lang.vocab.algo-metric
  "musics.lang's own `algo-metric` vocabulary -- a mechanical bridge of
   algo.metric.metric's own public API (modular/binary/continued-
   fraction pulse generators)."
  (:require [algo.metric.metric :as metric]
            [musics.lang.runtime :refer [push! pop! builtin]])
  (:refer-clojure :exclude [pop!]))

(defn vocab []
  (merge
    (builtin "binary-decomposition-rhythm" (fn [ctx] (let [length (pop! ctx) number (pop! ctx)]
                                                          (push! ctx (metric/binary-decomposition-rhythm number :length length))))
             "( number length -- grid )" "number's own binary digits as a 0/1 onset grid, LSB first")
    (builtin "continued-fraction-rhythm" (fn [ctx] (let [length (pop! ctx) fraction (pop! ctx)]
                                                       (push! ctx (metric/continued-fraction-rhythm fraction length))))
             "( fraction length -- grid )" "continued-fraction expansion of fraction as a 0/1 onset grid")
    (builtin "modular-rhythm" (fn [ctx] (let [offset (pop! ctx) length (pop! ctx) multiplier (pop! ctx) modulus (pop! ctx)]
                                            (push! ctx (metric/modular-rhythm modulus multiplier length offset))))
             "( modulus multiplier length offset -- grid )" "onset grid marking every position where (i*multiplier+offset) mod modulus is 0")))
