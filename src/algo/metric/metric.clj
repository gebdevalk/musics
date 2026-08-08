;; metric.clj
;; Clojure port of pymusics src/algorithm/ — numeric/metric-structure
;; pulse generators (modular arithmetic, binary decomposition, continued
;; fractions), as distinct from the pattern-shape generators in
;; algo.rithmic.rhythm.
;; Python/Kotlin sources: rhythm.py

(ns algo.metric.metric)

;; ── Binary Decomposition ────────────────────────────────────

(defn binary-decomposition-rhythm
  [number & {:keys [length]}]
  (let [bits (->> (Long/toBinaryString number)
                  (map #(Character/digit % 10)) reverse vec)]
    (if length
      (if (< (count bits) length)
        (into bits (repeat (- length (count bits)) 0))
        (subvec bits 0 length))
      bits)))

;; ── Continued Fraction ──────────────────────────────────────

(defn continued-fraction-rhythm
  [fraction length]
  (let [cf (loop [rem fraction result []]
             (if (or (zero? rem) (>= (count result) length))
               result
               (let [whole (long (Math/floor rem))
                     frac (- rem whole)]
                 (if (zero? frac)
                   (conj result whole)
                   (recur (/ 1.0 frac) (conj result whole))))))]
    (->> (mapcat #(repeat (min % 3) (mod % 2)) cf)
         (take length) (concat (repeat 0)) (take length) vec)))

;; ── Modular ──────────────────────────────────────────────────

(defn modular-rhythm
  [modulus multiplier length offset]
  (mapv #(if (zero? (mod (+ (* % multiplier) offset) modulus)) 1 0)
        (range length)))

(comment
  (modular-rhythm 7 3 21 0)
  )
