(ns algo.tree.lib
  "Ready-made algos (see algo.tree) lifting this project's own algo/
   fns, plus `notes`/`pair-notes`, the terminal algos turning :data
   into Leaf/Rest maps that musics.core/play walks directly:

     (play (tr/run (notes (gate (euclid) (cycled (scale))))
                  {:k 3 :n 8 :root 60 :intervals [0 2 4 7 9]}))

   Every key is bare (adherence is ^:shared by tilt and power, so either
   one fits the same slot without renaming a param). (tr/params tree)
   lists what a given tree reads, with defaults and ranges.

   Sources (no children):
     euclid      :k :n              Bjorklund onsets, a 0/1 grid
     scale       :root :intervals   root + offsets (absolute, not wrapped)
   Sequence shaping (one child unless noted):
     cycled      --                 repeated forever (lazy)
     shuffled    --                 reshuffled on every pass, forever (lazy)
     head        :len               first :len items
     color-talea :periods (1)       children: color, talea -> [pitch dur] pairs
     gate        --                 children: grid, pitches -> a 1 takes the
                                    next pitch, a 0 becomes nil (a rest)
     transpose   :semitones (0)     child: pitches, chords, nil rests or
                                    Leaf/Rest maps -- so (transpose :nodes)
                                    works as a live transform
   Indispensability (algo.indisp.indispensability):
     indisp      --                 child: subdivisions -> Barlow ranks
     tilt        :adherence         child: weights -> softmax probabilities
     power       :adherence         child: weights -> power-law probabilities
     density     :density           child: weights -> the top-:density 0/1 grid
     pick        --                 child: weights -> one index, weighted
   Terminal:
     notes       :dur (1/8)         children: pitches [durations] -> Leaf/Rest
                                    maps, lazily (an infinite source stays
                                    infinite -- head it before a plain play)
     pair-notes  --                 child: [pitch dur] pairs -> Leaf/Rest maps"
  (:require [algo.tree :as tr :refer [defalgos]]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.common.isorhythm :as iso]
            [algo.indisp.indispensability :as indisp]
            [algo.random :as random]
            [core.domain.flat-domain :as d]))

(defn- gate* [grid pitches]
  (lazy-seq
   (when-let [[g & gs] (seq grid)]
     (if (and g (not= 0 g))
       (when-let [[p & ps] (seq pitches)]
         (cons p (gate* gs ps)))
       (cons nil (gate* gs pitches))))))

(defn ->part
  "nil -> Rest, an int -> single-pitch Leaf, a collection -> chord Leaf.
   A Leaf/Rest map passes through unchanged."
  [pitch dur]
  (cond
    (nil? pitch)                  (d/rest* nil nil dur)
    (and (map? pitch) (:type pitch)) pitch
    (coll? pitch)                 (d/leaf nil nil dur (vec pitch))
    :else                         (d/leaf nil nil dur [pitch])))

(defn- transpose* [n x]
  (cond (number? x) (+ x n)
        (map? x)    (cond-> x (:pitches x) (update :pitches (partial mapv #(+ % n))))
        (coll? x)   (mapv #(+ % n) x)
        :else       x))

(defalgos
  euclid      [rhythm/euclidean-rhythm ^{:min 0 :doc "onsets"} k ^{:min 1 :doc "pulses"} n]
  scale       (fn [_ root intervals] (mapv #(+ root %) intervals))
  cycled      (fn [[xs]] (cycle xs))
  shuffled    (fn [[xs]] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))
  head        (fn [[xs] ^{:min 0} len] (vec (take len xs)))
  color-talea (fn [[color talea] ^{:default 1 :min 1} periods] (iso/color-talea color talea periods))
  gate        (fn [[grid pitches]] (gate* grid pitches))
  transpose   (fn [[xs] ^{:default 0} semitones] (map (partial transpose* semitones) xs))
  indisp      (fn [[subdivisions]] (indisp/indispensability subdivisions))
  tilt        (fn [[ws] ^{:shared true :min -1.0 :max 1.0} adherence] (indisp/tilt-probabilities ws adherence))
  power       (fn [[ws] ^{:shared true :min -1.0 :max 1.0} adherence] (indisp/power-law-probabilities ws adherence))
  density     (fn [[ws] ^{:min 0.0 :max 1.0} density] (indisp/density-grid ws density))
  pick        (fn [[ws]] (random/weighted-choose (vec (range (count ws))) ws))
  notes       (fn [[ps ds] ^{:default 1/8} dur] (map ->part ps (or ds (repeat dur))))
  pair-notes  (fn [[pairs]] (map (fn [[p dur]] (->part p dur)) pairs)))
