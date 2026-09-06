;; metric.clj
;; Clojure port of pymusics src/algorithm/ — numeric/metric-structure
;; pulse generators (modular arithmetic, binary decomposition, continued
;; fractions), as distinct from the pattern-shape generators in
;; algo.rhythmic.rhythm.
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
  "Continued-fraction expansion of fraction, each term contributing (min
   term 3) pulses of (mod term 2), up to length pulses -- zero-padded
   at the END if the expansion comes up shorter than length. (Fixed
   2026-09-03: the zero-padding used to be concatenated BEFORE the real
   values -- (repeat 0), unbounded -- so the final take only ever
   returned zeros regardless of fraction/length -- confirmed live, a
   real bug, not a hypothetical one.)"
  [fraction length]
  (let [cf (loop [rem fraction result []]
             (if (or (zero? rem) (>= (count result) length))
               result
               (let [whole (long (Math/floor rem))
                     frac (- rem whole)]
                 (if (zero? frac)
                   (conj result whole)
                   (recur (/ 1.0 frac) (conj result whole))))))
        values (mapcat #(repeat (min % 3) (mod % 2)) cf)]
    (vec (take length (concat values (repeat 0))))))

;; ── Modular ──────────────────────────────────────────────────

(defn modular-rhythm
  [modulus multiplier length offset]
  (mapv #(if (zero? (mod (+ (* % multiplier) offset) modulus)) 1 0)
        (range length)))

(comment
  (modular-rhythm 7 3 21 0)
  )
