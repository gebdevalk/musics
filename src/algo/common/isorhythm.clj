(ns algo.common.isorhythm
  "Isorhythmic combinators -- cycling two (or more) independent sequences
   of unequal length together, medieval motet technique still used
   generatively today. Distinct from algo.common.reshape: reshape's own
   functions (invert/retrograde/arpeggiate/hocket) all operate on already-
   resolved domain material (parts/leaves produced by musics.clj/sq).
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
   events, not 7 or 4."
  )

(defn- gcd
  [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn- lcm
  [a b]
  (/ (* a b) (gcd a b)))

(defn color-talea
  "Combine a color (pitch sequence) and a talea (duration sequence) into
   the classic isorhythmic color-talea pairing: event i's pitch is
   (nth color (mod i (count color))), its duration is (nth talea (mod i
   (count talea))) -- the two cycle completely independently. Since the
   combined pairing only repeats once every full period --
   lcm(count color, count talea) events -- `periods` counts how many
   *full periods* to generate (not a raw event count), so
   (color-talea color talea 1) always covers exactly one complete
   isorhythmic cycle and (color-talea color talea n) is just n copies of
   it back to back. Returns a vector of [pitch duration] pairs, in
   event order, ready to be rendered into Leaf-shaped text/records by
   the caller (this fn never builds domain records itself -- it only
   computes the pairing)."
  ([color talea] (color-talea color talea 1))
  ([color talea periods]
   (let [color  (vec color)
         talea  (vec talea)
         cn     (count color)
         tn     (count talea)
         period (lcm cn tn)
         total  (* periods period)]
     (mapv (fn [i] [(nth color (mod i cn)) (nth talea (mod i tn))])
           (range total)))))
