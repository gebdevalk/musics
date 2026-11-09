(ns musics.algo.common.isorhythm
  "Isorhythmic combinators -- cycling two (or more) independent sequences
   of unequal length together, medieval motet technique still used
   generatively today. Distinct from musics.algo.common.reshape: reshape's own
   functions (invert/retrograde/arpeggiate/hocket) all operate on already-
   resolved domain material (parts/leaves produced by musics.compose/sq).
   color-talea below works one level earlier -- on bare pitch/duration
   values, before anything has been built into a Leaf at all -- so it
   lives in its own file rather than stretching reshape's documented
   scope.

   A 'color' is a repeating sequence of pitches with no rhythm of its
   own; a 'talea' is a repeating sequence of durations with no pitch of
   its own (musics.ebnf's BareDuration atom, '/4 /8 /8 /4' inside a Data
   container, is exactly this: a talea authored as pure data, independent
   of any color). The two cycle independently against each other, so the
   combined (pitch, duration) pairing only repeats once every
   lcm(count color, count talea) events -- one full isorhythmic period,
   e.g. a 7-pitch color against a 4-duration talea repeats every 28
   events, not 7 or 4.

   zip-parts below is the N-way generalization -- any number of named,
   independently-cycling streams (not just a fixed pitch+duration
   pair), combined the same lcm-of-all-lengths way."
  (:require [musics.algo.numeric.integer :as num]))

(defn color-talea
  "Pair a color (pitches) with a talea (durations), each cycling on its own: event i has pitch (nth color (mod i (count color))) and duration (nth talea (mod i (count talea))). periods counts full periods of lcm(count color, count talea) events, so (color-talea color talea 1) is one isorhythmic cycle. Returns [pitch duration] pairs in event order; builds no domain records."
  ([color talea] (color-talea color talea 1))
  ([color talea periods]
   (let [color  (vec color)
         talea  (vec talea)
         cn     (count color)
         tn     (count talea)
         period (num/lcm cn tn)
         total  (* periods period)]
     (mapv (fn [i] [(nth color (mod i cn)) (nth talea (mod i tn))])
           (range total)))))

(defn zip-parts
  "color-talea for any number of named streams: each cycles on its own, a period is the lcm of all their lengths, periods (default 1) counts full periods. Returns one map per event holding every stream's current value; streams must not be empty.
     (zip-parts {:pitch [60 62 64] :duration [1/4 1/8]})
     ;; => [{:pitch 60 :duration 1/4} {:pitch 62 :duration 1/8}
     ;;     {:pitch 64 :duration 1/4} {:pitch 60 :duration 1/8}
     ;;     {:pitch 62 :duration 1/4} {:pitch 64 :duration 1/8}]"
  ([streams] (zip-parts streams 1))
  ([streams periods]
   (when (empty? streams)
     (throw (ex-info "zip-parts: streams must be non-empty -- nothing to zip together" {})))
   (let [streams (into {} (map (fn [[k s]] [k (vec s)])) streams)
         period  (num/lcm-multiple (map count (vals streams)))
         total   (* periods period)]
     (mapv (fn [i]
             (into {} (map (fn [[k s]] [k (nth s (mod i (count s)))])) streams))
           (range total)))))

