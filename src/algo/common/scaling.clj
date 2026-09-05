;; scaling.clj
;; Clojure port of kotlin-reference/decorator/Scaling.kt and
;; kotlin-reference/jl/scaling.jl (identical fns under two different
;; names) -- small number-rounding/range-remapping utilities.
;;
;; clamp was previously duplicated identically (private, same 1-line
;; body) in algo.random.henon and algo.random.lorenz -- a real,
;; confirmed duplication found by a code-reuse audit (2026-09-05, same
;; pattern as algo.common.numeric's own gcd/lcm extraction) -- moved
;; here as the one shared copy both now require.

(ns algo.common.scaling)

(defn clamp
  "Clamp v into [lo hi].

   (clamp 0 10 15) ;=> 10
   (clamp 0 10 -3) ;=> 0"
  [lo hi v]
  (max lo (min hi v)))

(defn closest-to
  "Whichever of low/hi is numerically closer to n.

   (closest-to 4.7 4 6) ;=> 4"
  [n low hi]
  (if (< (- n low) (- hi n)) low hi))

(defn round-to
  "Round n to the nearest multiple of div.

   (round-to 4.7 1) ;=> 5
   (round-to 4.7 2) ;=> 4"
  [n div]
  (let [r  (rem n div)
        lo (- n r)
        hi (+ lo div)]
    (closest-to n lo hi)))

(defn scale-range
  "Linearly remaps x from [inmin,inmax] to [outmin,outmax].

   (scale-range 5 0 10 50 150) ;=> 100"
  [x inmin inmax outmin outmax]
  (+ (/ (* (- outmax outmin) (- x inmin)) (- inmax inmin)) outmin))

(comment
  (clamp 0 10 15)
  (closest-to 4.7 4 6)
  (round-to 4.7 2)
  (scale-range 5 0 10 50 150)
  )
