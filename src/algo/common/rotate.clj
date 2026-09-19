;; rotate.clj
;; Rotate a vector by i positions -- previously duplicated identically
;; (once as a private fn, twice more inlined) across algo.rhythmic.
;; necklace and algo.rhythmic.phase-sieve -- a real, confirmed
;; duplication found by a code-reuse audit (2026-09-05, same pattern as
;; algo.common.numeric's own gcd/lcm extraction), moved here as the one
;; shared copy every caller now requires.

(ns algo.common.rotate)

(defn rotate
  "Rotate pattern left by i positions (negative/oversized i wraps via
   mod, same as a cyclic pattern always would).

   (rotate [1 2 3 4] 1)  ;=> [2 3 4 1]
   (rotate [1 2 3 4] -1) ;=> [4 1 2 3]"
  [pattern i]
  (let [n (count pattern)
        i (mod i n)]
    (vec (concat (drop i pattern) (take i pattern)))))

(comment
  (rotate [1 2 3 4] 1)
  (rotate [1 2 3 4] -1)
  )
