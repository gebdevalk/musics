(ns musics.lang.vocab.algo-toolkit
  "musics.lang's own `algo-toolkit` vocabulary -- algo.toolkit's own
   GENUINELY NEW combinators only. Most of that namespace is either a
   pure `(def name algo.random/name)` re-export (same function object,
   already reachable as plain `name` in `algo-random`) or an explicitly
   documented 'standalone port' duplicate of an algorithm already
   bridged elsewhere (color-talea/zip-parts duplicate algo-common's
   own; z-filter/smooth/momentum/memory/smooth-intervals/interval-gain/
   pc-smooth duplicate algo-common's own; infrapolate/interpolate/
   ultrapolate/mixed-polations duplicate algo-melodic's own) -- all of
   those are skipped here rather than bridged twice under one name.
   `weighted-shuffle` is renamed `weighted-shuffle-lo` -- toolkit's own
   version is a genuinely DIFFERENT algorithm from algo-common's own
   weighted-shuffle (fixed to a lo-emph-biased draw, single-arg, no
   dist-fn parameter), not a duplicate, so it needs its own name rather
   than colliding."
  (:require [algo.toolkit :as toolkit]
            [musics.lang.runtime :refer [push! pop! builtin]])
  (:refer-clojure :exclude [pop!]))

(defn vocab []
  (merge
    (builtin "cycle-shuffle" (fn [ctx] (push! ctx (toolkit/cycle-shuffle (pop! ctx)))) "( v -- lazy-seq )" "v in order, then a shuffled v, then another, forever (v must be non-empty)")
    (builtin "take-cycle-shuffle" (fn [ctx] (let [v (pop! ctx) n (pop! ctx)] (push! ctx (toolkit/take-cycle-shuffle n v))))
             "( n v -- coll )" "a realized vector of exactly n full cycle-shuffle passes")
    (builtin "weighted-shuffle-lo" (fn [ctx] (push! ctx (toolkit/weighted-shuffle (pop! ctx)))) "( coll -- coll' )" "shuffles coll via a lo-emph-biased draw, a weaker order-preserving shuffle")
    (builtin "cycle-weighted-shuffle" (fn [ctx] (push! ctx (toolkit/cycle-weighted-shuffle (pop! ctx)))) "( v -- lazy-seq )" "like cycle-shuffle, reshuffling via weighted-shuffle-lo instead")
    (builtin "take-cycle-weighted-shuffle" (fn [ctx] (let [v (pop! ctx) n (pop! ctx)] (push! ctx (toolkit/take-cycle-weighted-shuffle n v))))
             "( n v -- coll )" "a realized vector of exactly n full cycle-weighted-shuffle passes")
    (builtin "cycle-deep-shuffle" (fn [ctx] (let [depth (pop! ctx) v (pop! ctx)] (push! ctx (toolkit/cycle-deep-shuffle v depth))))
             "( v depth -- lazy-seq )" "like cycle-shuffle, reshuffling each pass via deep-shuffle to depth")
    (builtin "take-cycle-deep-shuffle" (fn [ctx] (let [depth (pop! ctx) v (pop! ctx) n (pop! ctx)] (push! ctx (toolkit/take-cycle-deep-shuffle n v depth))))
             "( n v depth -- coll )" "a realized vector of exactly n full cycle-deep-shuffle passes")

    (builtin "weighted-pulse-choice" (fn [ctx] (let [adherence (pop! ctx) subdivisions (pop! ctx)] (push! ctx (toolkit/weighted-pulse-choice subdivisions adherence))))
             "( subdivisions adherence -- pulse-idx )" "picks one pulse index, weighted by Barlow indispensability softened by adherence")
    (builtin "shuffled-euclidean" (fn [ctx] (let [n (pop! ctx) k (pop! ctx)] (push! ctx (toolkit/shuffled-euclidean k n))))
             "( k n -- lazy-seq )" "an infinite, reshuffled stream of a Euclidean rhythm's own onset grid")
    (builtin "weighted-density-grid" (fn [ctx] (let [density (pop! ctx) adherence (pop! ctx) subdivisions (pop! ctx)]
                                                   (push! ctx (toolkit/weighted-density-grid subdivisions adherence density))))
             "( subdivisions adherence density -- grid )" "thins a meter to its density fraction of pulses, chosen by adherence-shaped probability")))
