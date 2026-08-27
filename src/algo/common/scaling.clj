;; scaling.clj
;; Clojure port of kotlin-reference/decorator/Scaling.kt and
;; kotlin-reference/jl/scaling.jl (identical fns under two different
;; names) -- small number-rounding/range-remapping utilities.

(ns algo.common.scaling)

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
  (closest-to 4.7 4 6)
  (round-to 4.7 2)
  (scale-range 5 0 10 50 150)
  )
