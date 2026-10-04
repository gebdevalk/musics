;; metric.clj
;; Clojure port of pymusics src/algorithm/ — numeric/metric-structure
;; pulse generators (modular arithmetic, binary decomposition, continued
;; fractions), as distinct from the pattern-shape generators in
;; algo.rhythmic.rhythm.
;; Python/Kotlin sources: rhythm.py

(ns algo.metric.metric)

;; ── Binary Decomposition ────────────────────────────────────

(defn binary-decomposition-rhythm
  "number's own binary digits as a 0/1 onset grid, LSB first (pulse 0 =
   the 2^0 bit -- Long/toBinaryString's own MSB-first digits, reversed)
   -- so 13 (binary 1101) becomes [1 0 1 1]. With no length, the grid's
   own size tracks number directly (as many bits as number needs,
   unpadded) rather than a fixed width. With length: a SHORT expansion
   is zero-padded at the end (extra high-order zero bits -- a genuine
   zero-extension); a LONG one is truncated via (subvec bits 0 length),
   which keeps the length LOW-order bits and discards the
   more-significant ones, not the other way around."
  {:algo {:short :bits :in [] :out :pulse
          :params {:number {:type :int :min 0 :max ##Inf :default 13 :doc "its bits are the onsets"}
                   :length {:type :int :min 1 :max 64 :default 8 :doc "pulses (zero-padded or cut)"}}}}
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
  {:algo {:short :cfrac :in [] :out :pulse
          :params {:fraction {:type :double :min 0.0 :max ##Inf :default 3.141592653589793
                              :doc "expanded as a continued fraction"}
                   :length   {:type :int :min 1 :max 256 :default 16 :doc "pulses"}}}}
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
  "An onset wherever (i*multiplier + offset) is a multiple of modulus."
  {:algo {:short :modular :in [] :out :pulse
          :params {:modulus    {:type :int :min 1 :max 64 :default 7 :doc "onset when divisible by this"}
                   :multiplier {:type :int :min 0 :max 64 :default 3 :doc "step per pulse"}
                   :length     {:type :int :min 1 :max 256 :default 21 :doc "pulses"}
                   :offset     {:type :int :min 0 :max 64 :default 0 :doc "added to every step"}}}}
  [modulus multiplier length offset]
  (mapv #(if (zero? (mod (+ (* % multiplier) offset) modulus)) 1 0)
        (range length)))

(comment
  (modular-rhythm 7 3 21 0)
  )
