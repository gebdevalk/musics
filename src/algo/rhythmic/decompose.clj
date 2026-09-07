(ns algo.rhythmic.decompose)

(defn binary-decompose
  "Greedily decompose `duration` into powers of two (largest first), then
   for each term x, expand it into `depth` finer pieces by repeated
   halving -- except the LAST piece of each expansion takes whatever
   remains, rather than being halved away. This guarantees the full
   output always sums exactly back to `duration`, regardless of depth.

   e.g. (binary-decompose 3/4 2)
        base pass: 1/2, 1/4
        expand each: 1/4, 1/4 (from 1/2 -- last piece absorbs the rest)
                       1/8, 1/8 (from 1/4)
        => [1/4 1/4 1/8 1/8] (sums to 3/4)"
  [duration depth]
  (let [base (loop [remaining duration
                    power 1
                    acc []]
               (cond
                 (zero? remaining) acc
                 (>= remaining power) (recur (- remaining power) (/ power 2) (conj acc power))
                 :else (recur remaining (/ power 2) acc)))
        expand (fn [x depth]
                 (loop [remaining x
                        power (/ x 2)
                        n depth
                        acc []]
                   (if (= n 1)
                     (conj acc remaining) ;; last piece: takes the rest exactly
                     (recur (- remaining power) (/ power 2) (dec n) (conj acc power)))))]
    (vec (mapcat #(expand % depth) base))))

(defn ternary-decompose
  "Like binary-decompose, but in base three -- with a real difference,
   not just a relabeled base: a ternary digit can be 0, 1, OR 2 (not
   just 0/1 the way a binary digit is), so the base pass reuses the
   SAME power of three while it still fits the remainder, moving to the
   next smaller power only once it no longer does -- unlike
   binary-decompose's own base pass, which can move on unconditionally
   after every use because a binary digit never needs a second one at
   the same power. Each term x is then expanded into `depth` finer
   pieces by repeated thirding, same shape as binary-decompose -- the
   LAST piece of each expansion takes whatever remains rather than
   being thirded away, so the full output always sums exactly back to
   `duration`.

   Terminates only when duration's FRACTIONAL part has a finite base-3
   expansion -- i.e. reduces to a denominator that's a power of 3
   (thirds, ninths, 27ths, ...). This is a far narrower practical
   domain than binary-decompose's, which covers essentially every
   ordinary note duration (all dyadic: halves, quarters, eighths, ...):
   a duration whose reduced denominator carries any OTHER prime factor
   (2, 5, ...) never terminates in base 3, no matter how deep the
   search goes -- confirmed live, not a hypothetical edge case: 3/4
   alone hangs the naive greedy pass forever. So this is meant for
   genuinely triplet-derived durations, not ordinary rhythm -- and
   guards the non-terminating case with a bounded search that throws,
   rather than hanging.

   e.g. (ternary-decompose 2/3 2)
        base pass: 1/3, 1/3                 (digit 2 at the 1/3 place)
        expand each: 1/9, 2/9 (from the first 1/3 -- last piece absorbs the rest)
                       1/9, 2/9 (from the second 1/3)
        => [1/9 2/9 1/9 2/9] (sums to 2/3)"
  [duration depth]
  (let [max-steps 64
        base (loop [remaining duration
                    power 1
                    acc []
                    steps 0]
               (cond
                 (zero? remaining) acc
                 (> steps max-steps)
                 (throw (ex-info (str "ternary-decompose: " duration
                                      " has no finite base-3 expansion -- only"
                                      " durations whose reduced fractional"
                                      " denominator is a power of 3 terminate")
                                 {:duration duration}))
                 (>= remaining power) (recur (- remaining power) power (conj acc power) (inc steps))
                 :else (recur remaining (/ power 3) acc (inc steps))))
        expand (fn [x depth]
                 (loop [remaining x
                        power (/ x 3)
                        n depth
                        acc []]
                   (if (= n 1)
                     (conj acc remaining) ;; last piece: takes the rest exactly
                     (recur (- remaining power) (/ power 3) (dec n) (conj acc power)))))]
    (vec (mapcat #(expand % depth) base))))

(defn split-decompose
  "Splits `duration` into `depth` pieces, taking a `ratio`-sized bite off
   the remaining duration at each step and continuing to split what's
   left. The final piece always absorbs whatever remains, so the output
   sums exactly to `duration` regardless of depth or ratio.

   depth <= 1 returns [duration] unsplit -- the original value, untouched.

   ratio = 1/2 -> even halving (Zeno-style)
   ratio = 2/3 -> long-short (long piece kept first)
   ratio = 1/3 -> short-long (short piece kept first)

   e.g. (split-decompose 1 0 1/2) => [1]
        (split-decompose 1 1 1/2) => [1]
        (split-decompose 1 4 1/2) => [1/2 1/4 1/8 1/8]"
  [duration depth ratio]
  (loop [remaining duration
         n depth
         acc []]
    (if (<= n 1)
      (conj acc remaining)
      (let [piece (* remaining ratio)]
        (recur (- remaining piece) (dec n) (conj acc piece))))))