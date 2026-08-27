;; farey.clj
;; Clojure port of kotlin-reference/Farey.kt -- best rational
;; approximation of a real number, via a Stern-Brocot mediant search
;; (the reference file's own commented-out attribution traces it to
;; John D. Cook's C++ original).

(ns algo.common.farey)

(defn farey
  "Best rational approximation of x (a value in [0,1)) with denominator
   at most N, found by a Stern-Brocot mediant search: starting from the
   bounding fractions 0/1 and 1/1, repeatedly replace whichever bound x
   falls on the far side of with the mediant of the two, until one
   side's denominator would exceed N. Returns a native Clojure ratio,
   already in lowest terms (a plain integer when that ratio reduces to
   one).

   (farey 0.75 4)         ;=> 3/4
   (farey (/ 1.0 3) 10)   ;=> 1/3"
  [x N]
  (loop [a 0 b 1 c 1 d 1]
    (if (and (<= b N) (<= d N))
      (let [mediant (/ (+ a c) (double (+ b d)))]
        (cond
          (== x mediant)
          (cond
            (<= (+ b d) N) (/ (+ a c) (+ b d))
            (> d b) (/ c d)
            :else (/ a b))

          (> x mediant) (recur (+ a c) (+ b d) c d)
          :else (recur a b (+ a c) (+ b d))))
      (if (> b N) (/ c d) (/ a b)))))

(comment
  (farey 0.75 4)
  (farey (/ 1.0 3) 10)
  )
