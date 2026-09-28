(ns algo.tree.lib
  "Ready-made algos for algo.tree trees. Each name here is a node
   constructor; (algo.tree/algos) lists them all with their params.

     (def riff (notes (gate (euclid) (cycled (scale)))))
     (algo.tree/run riff {:k 5})

   Exposed from algo/ (their own :algo metadata):
     euclid       :k :n :rotation      Bjorklund onsets -> :grid
     indisp       :subdivisions        Barlow ranks -> :weights
     tilt, power  :adherence           :weights -> reshaped :weights
     density      :density             :weights -> the top-:density :grid
     color-talea  :periods             :pitches :durations -> :pairs
   Defined here:
     scale        :root :intervals     -> :pitches (root + offsets)
     cycled, shuffled                  repeat forever / reshuffle every pass
     head         :len                 first :len items
     gate                              :grid :pitches -> a pitch per onset, nil (rest) elsewhere
     transpose    :semitones           pitches, chords, rests or notes; (transpose :nodes) is a live transform
     stretch      :factor              durations or notes, each duration times :factor
     pick                              :weights -> one weighted :index
     notes        :dur                 :pitches -> Leaf/Rest maps (lazy)
     pair-notes                        :pairs -> Leaf/Rest maps"
  (:require [algo.tree :refer [defalgo expose]]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.common.isorhythm :as iso]
            [algo.indisp.indispensability :as indisp]
            [algo.random :as random]
            [core.domain.flat-domain :as d]))

(expose rhythm/euclidean-rhythm
        indisp/indispensability
        indisp/tilt-probabilities
        indisp/power-law-probabilities
        indisp/density-grid
        iso/color-talea)

(defn ->part
  "nil -> Rest, an int -> single-pitch Leaf, a collection -> chord Leaf.
   A Leaf/Rest map passes through unchanged."
  [pitch dur]
  (cond
    (nil? pitch)                     (d/rest* nil nil dur)
    (and (map? pitch) (:type pitch)) pitch
    (coll? pitch)                    (d/leaf nil nil dur (vec pitch))
    :else                            (d/leaf nil nil dur [pitch])))

(defn- gate-seq [grid pitches]
  (lazy-seq
   (when-let [[g & gs] (seq grid)]
     (if (and g (not= 0 g))
       (when-let [[p & ps] (seq pitches)]
         (cons p (gate-seq gs ps)))
       (cons nil (gate-seq gs pitches))))))

(defn- shift [n x]
  (cond (number? x) (+ x n)
        (map? x)    (cond-> x (:pitches x) (update :pitches (partial mapv #(+ % n))))
        (coll? x)   (mapv #(+ % n) x)
        :else       x))

(defalgo scale "Root plus offsets, as absolute pitches."
  {:algo {:in [] :out :pitches
          :params {:root      {:type :int :min 24 :max 96 :default 60 :doc "lowest pitch"}
                   :intervals {:type :vector :default [0 2 4 7 9] :doc "semitones above root"}}}}
  [root intervals] (mapv #(+ root %) intervals))

(defalgo cycled "Its child, repeated forever (lazy)."
  {:algo {:in [:any] :out :same}}
  [xs] (cycle xs))

(defalgo shuffled "Its child, reshuffled on every pass, forever (lazy)."
  {:algo {:in [:any] :out :same}}
  [xs] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))

(defalgo head "The first :len items of its child."
  {:algo {:in [:any] :out :same
          :params {:len {:type :int :min 0 :max 256 :default 16 :doc "items kept"}}}}
  [xs len] (vec (take len xs)))

(defalgo gate "A pitch on each onset of the grid, nil (a rest) elsewhere."
  {:algo {:in [:grid :pitches] :out :pitches}}
  [grid pitches] (gate-seq grid pitches))

(defalgo transpose "Shift pitches, chords or notes; rests stay."
  {:algo {:in [:any] :out :same
          :params {:semitones {:type :int :min -48 :max 48 :default 0 :doc "shift"}}}}
  [xs semitones] (map (partial shift semitones) xs))

(defn- stretched [factor x]
  (cond (number? x)   (* factor x)
        (:duration x) (update x :duration #(* factor %))
        :else         x))

(defalgo stretch "Every duration times :factor -- numbers, or notes' :duration; anything else stays."
  {:algo {:in [:any] :out :same
          :params {:factor {:type :ratio :min 1/16 :max 16 :default 1 :doc "duration multiplier"}}}}
  [xs factor] (map (partial stretched factor) xs))

(defalgo pick "One index, drawn with the child's weights."
  {:algo {:in [:weights] :out :index}}
  [ws] (random/weighted-choose (vec (range (count ws))) ws))

(defalgo notes "Pitches as Leaf/Rest maps of :dur (lazy)."
  {:algo {:in [:pitches] :out :notes
          :params {:dur {:type :ratio :min 1/64 :max 4 :default 1/8 :doc "note length"}}}}
  [pitches dur] (map #(->part % dur) pitches))

(defalgo pair-notes "[pitch dur] pairs as Leaf/Rest maps."
  {:algo {:in [:pairs] :out :notes}}
  [pairs] (map (fn [[p dur]] (->part p dur)) pairs))
