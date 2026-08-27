;; poly.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 3-4 -- simultaneous rhythmic layers (polyrhythm/polymeter) and
;; Elliott Carter's metric modulation.

(ns algo.rithmic.poly)

(defn- gcd [a b] (if (zero? b) a (recur b (mod a b))))
(defn- lcm [a b] (/ (* a b) (gcd a b)))
(defn- lcm-multiple [ns] (reduce lcm 1 ns))

(defn polyrhythm
  "Multiple simultaneous rhythmic layers. layers is a seq of [beats
   divisions] pairs; length is the common grid length (smallest common
   subdivision) every layer's pattern is expressed against.

   (polyrhythm [[3 8] [2 8]] 24)  ;; 3 against 2 in 8th notes"
  [layers length]
  (mapv (fn [[beats divisions]]
          (let [scale-factor (max 1 (quot length divisions))
                pattern (vec (repeat length 0))]
            (reduce (fn [pat i]
                      (let [position (quot (* i divisions scale-factor) beats)]
                        (if (< position length) (assoc pat position 1) pat)))
                    pattern (range beats))))
        layers))

(defn polymeter
  "Multiple simultaneous meters. meters is a seq of [numerator
   denominator] time signatures; length is in beats, against a common
   grid (the LCM of all the denominators). Each output pattern marks 2
   on the strong (first) beat of its own measure, 1 on other downbeats
   for compound meters (numerator > 3), 0 elsewhere.

   (polymeter [[3 4] [4 4]] 12)  ;; 3/4 against 4/4"
  [meters length]
  (let [common-denom (lcm-multiple (map second meters))]
    (mapv (fn [[num denom]]
            (let [beats-per-measure (* num (quot common-denom denom))]
              (mapv (fn [i]
                      (let [beat-in-measure (mod i beats-per-measure)]
                        (cond
                          (zero? beat-in-measure) 2
                          (and (> num 3)
                               (zero? (mod beat-in-measure (quot beats-per-measure num)))) 1
                          :else 0)))
                    (range length))))
          meters)))

(defn metric-modulation
  "Onset timings (in seconds) of pattern's own beats after converting to
   a new tempo via ratio (new/old) -- Elliott Carter's technique of
   pivoting a shared subdivision to reinterpret it at a different speed.
   base-tempo is in BPM, subdivisions is how many grid steps per beat.

   (metric-modulation 120 3/2 [1 0 1 0])  ;; quarter=120 -> dotted-quarter=120"
  ([base-tempo ratio pattern] (metric-modulation base-tempo ratio pattern 4))
  ([base-tempo ratio pattern subdivisions]
   (let [beat-duration (/ 60.0 base-tempo)
         modulated-duration (* beat-duration (double ratio))
         step (/ modulated-duration subdivisions)]
     (loop [[v & more] pattern i 0 t 0.0 out []]
       (if (nil? v)
         out
         (recur more (inc i) (+ t step) (if (= v 1) (conj out t) out)))))))

(defn nested-tuplets
  "Recursively expand base-pattern's own beats into tuplet groups: each
   1 becomes [1 0 0 ... 0] ((numerator tuplet-ratio) slots total, one
   beat then rests), each 0 becomes (denominator tuplet-ratio) rests.
   Applied depth times (each pass re-expanding the previous result)."
  ([base-pattern tuplet-ratio] (nested-tuplets base-pattern tuplet-ratio 1))
  ([base-pattern tuplet-ratio depth]
   (if (zero? depth)
     (vec base-pattern)
     (let [num (numerator tuplet-ratio)
           den (denominator tuplet-ratio)
           expanded (vec (mapcat (fn [v]
                                    (if (= v 1)
                                      (into [1] (repeat (dec num) 0))
                                      (repeat den 0)))
                                  base-pattern))]
       (if (> depth 1)
         (recur expanded tuplet-ratio (dec depth))
         expanded)))))

(comment
  (polyrhythm [[3 8] [2 8]] 24)
  (polymeter [[3 4] [4 4]] 12)
  (metric-modulation 120 3/2 [1 0 1 0])
  (nested-tuplets [1 0 1] 3/2 1)
  (nested-tuplets [1 0] 3/2 2)
  )
