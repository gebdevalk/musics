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

(defn split-decompose
  "Splits `duration` into `depth` pieces, taking a `ratio`-sized bite off
   the remaining duration at each step and continuing to split what's
   left. The final piece always absorbs whatever remains, so the output
   sums exactly to `duration` regardless of depth or ratio -- this is
   the tuplet ratio itself doing the dividing (2/3, 1/3, whatever's
   handed in), not a base-N digit search the way binary-decompose is,
   so it always terminates in exactly `depth` steps for ANY ratio and
   starting duration -- no representability constraint to trip over.

   duration may be a bare number (returns bare numbers) OR a leaf/part
   map with a :duration key (returns that many COPIES of the leaf, each
   with :duration replaced by its own piece and every other field --
   pitches, articulation, ... -- carried over unchanged) -- same dual-
   mode policy algo.common.transient-ops/times already uses, so a real
   tuplet split can be handed a whole leaf directly instead of the
   caller splitting the bare number and rebuilding leaves by hand.

   depth <= 1 returns [duration] unsplit -- the original value, untouched.

   ratio = 1/2 -> even halving (Zeno-style)
   ratio = 2/3 -> long-short (long piece kept first)
   ratio = 1/3 -> short-long (short piece kept first)

   e.g. (split-decompose 1 0 1/2) => [1]
        (split-decompose 1 1 1/2) => [1]
        (split-decompose 1 4 1/2) => [1/2 1/4 1/8 1/8]
        (split-decompose 1 3 2/3) => [2/3 2/9 1/9]           ;; whole note, long-short
        (split-decompose {:pitches [60] :duration 1} 3 2/3)
          => [{:pitches [60] :duration 2/3}
              {:pitches [60] :duration 2/9}
              {:pitches [60] :duration 1/9}]"
  [duration depth ratio]
  (if (:duration duration)
    (mapv #(assoc duration :duration %) (split-decompose (:duration duration) depth ratio))
    (loop [remaining duration
           n depth
           acc []]
      (if (<= n 1)
        (conj acc remaining)
        (let [piece (* remaining ratio)]
          (recur (- remaining piece) (dec n) (conj acc piece)))))))