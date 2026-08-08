;; indispensability.clj
;; Clojure port of pymusics src/algorithm/ — Barlow's psi.
;; Python/Kotlin sources: indispensability.py, Indispensabilities.kt

(ns algo.indisp.indispensability)

(defn- prod [coll] (reduce * 1 coll))

(defn psi
  "Barlow's indispensability for position n given quotients qs."
  [n qs]
  (let [Q  (prod qs)
        d  (mod (+ n (dec Q)) Q)
        sz (count qs)]
    (loop [i 0 acc 0]
      (if (< i sz)
        (let [i' (- sz i 1)
              a  (prod (subvec qs 0 i'))
              b  (prod (subvec qs (- sz i) sz))
              c  (nth qs i')
              w  (* a (mod (quot d b) c))]
          (recur (inc i) (+ acc w)))
        (inc acc)))))

(defn psi-fractions [qs]
  (let [Q (prod qs)]
    (mapv #(/ (psi % qs) Q) (range Q))))

(defn beat-probabilities [psi-vals adherence]
  (let [exps  (mapv #(Math/exp (* % adherence)) psi-vals)
        total (reduce + exps)]
    (mapv #(/ % total) exps)))

(comment
  (psi 0 [2 3 2])               ;; => 1
  (psi-fractions [2 2])         ;; => [1/4 3/4 2/4 4/4]
  )
