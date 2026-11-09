;; Small integer helpers: gcd and lcm.

(ns musics.algo.numeric.integer)

(defn gcd
  "Greatest common divisor of a and b, via the Euclidean algorithm.

   (gcd 12 18) ;=> 6"
  [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn lcm
  "Least common multiple of a and b.

   (lcm 4 6) ;=> 12"
  [a b]
  (/ (* a b) (gcd a b)))

(defn lcm-multiple
  "Least common multiple of every number in ns.

   (lcm-multiple [2 3 4]) ;=> 12"
  [ns]
  (reduce lcm 1 ns))

(comment
  (gcd 12 18)
  (lcm 4 6)
  (lcm-multiple [2 3 4])
  )
