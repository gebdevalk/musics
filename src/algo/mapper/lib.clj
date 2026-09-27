(ns algo.mapper.lib
  "Ready-made mappers (see algo.mapper) lifting this project's own algo/
   fns, plus `notes`, the terminal mapper turning :data into Leaf/Rest
   maps that musics.core/play walks directly:

     (play (m/run (notes (gate (euclid) (cycled (scale))))
                  {:k 3 :n 8 :root 60 :intervals [0 2 4 7 9]}))

   Param keys (all bare -- none collide within this form):
     euclid      :k :n          Bjorklund onsets, a 0/1 grid
     scale       :root :intervals   root + offsets (absolute, not wrapped)
     color-talea :periods       children: color, talea -> [pitch dur] pairs
     cycled      --             child repeated forever (lazy)
     head        :len           first :len items of its child
     gate        --             children: grid, pitches -> a 1 takes the
                                next pitch, a 0 becomes nil (a rest)
     notes       :dur           children: pitches [durations]; :dur
                                (default 1/8) when no durations child.
                                Forces: its pitches must be finite
     pair-notes  --             child of [pitch dur] pairs (color-talea)"
  (:require [algo.mapper :as m :refer [defalgos]]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.common.isorhythm :as iso]
            [core.domain.flat-domain :as d]))

(defn- gate* [grid pitches]
  (lazy-seq
   (when-let [[g & gs] (seq grid)]
     (if (and g (not= 0 g))
       (when-let [[p & ps] (seq pitches)]
         (cons p (gate* gs ps)))
       (cons nil (gate* gs pitches))))))

(defn- ->part
  "nil -> Rest, an int -> single-pitch Leaf, a collection -> chord Leaf."
  [pitch dur]
  (cond
    (nil? pitch)  (d/rest* nil nil dur)
    (coll? pitch) (d/leaf nil nil dur (vec pitch))
    :else         (d/leaf nil nil dur [pitch])))

(defalgos
  euclid      [rhythm/euclidean-rhythm k n]
  scale       (fn [_ root intervals] (mapv #(+ root %) intervals))
  color-talea (fn [[color talea] periods] (iso/color-talea color talea (or periods 1)))
  cycled      (fn [[xs]] (cycle xs))
  head        (fn [[xs] len] (vec (take len xs)))
  gate        (fn [[grid pitches]] (gate* grid pitches))
  notes       (fn [[ps ds] dur]
                (mapv ->part ps (or ds (repeat (or dur 1/8)))))
  pair-notes  (fn [[pairs]] (mapv (fn [[p dur]] (->part p dur)) pairs)))
