(ns musics.algo.rhythmic.pattern
  "Rhythm patterns from number theory and geometry: Euclid, Fibonacci, primes, L-systems, Markov chains; modular, binary and continued-fraction grids; Cantor, golden ratio, dragon curve, polygons, circle of fifths."
  (:require [clojure.string :as str]
            [musics.algo.numeric.random :as rand]))

;; ============================================================
;; pattern
;; ============================================================

;; Clojure port of pymusics src/algorithm/ — rhythm-pattern generators
;; (Euclidean, Fibonacci, prime, L-system, Markov).
;; Python/Kotlin sources: rhythm.py

;; ── Euclidean (Bjorklund) ────────────────────────────────────

(defn euclid
  "Distribute k beats evenly among n pulses (Bjorklund's algorithm, Toussaint's \"The Euclidean Algorithm Generates Traditional Musical Rhythms\"). :rotation rotates the onsets.
     (euclid 3 8) ;=> [1 0 0 1 0 0 1 0]  the tresillo
     (euclid 2 5) ;=> [1 0 1 0 0]
     (euclid 5 8) ;=> [1 0 1 1 0 1 1 0]"
  {:algo {:in [] :out :pulse
          :params {:k        {:type :int :min 0 :max 32 :default 3 :doc "onsets"}
                   :n        {:type :int :min 1 :max 32 :default 8 :doc "pulses"}
                   :rotation {:type :int :min 0 :max 32 :doc "steps to rotate the onsets"}}}}
  [k n & {:keys [rotation] :or {rotation 0}}]
  {:pre [(<= k n) (>= k 0) (pos? n)]}
  (let [result (if (zero? k)
                 (vec (repeat n 0))
                 (loop [a (vec (repeat k [1]))
                        b (vec (repeat (- n k) [0]))]
                   (if (<= (count b) 1)
                     (vec (mapcat identity (concat a b)))
                     (let [m         (min (count a) (count b))
                           merged    (mapv into (subvec a 0 m) (subvec b 0 m))
                           remainder (if (> (count a) m) (subvec a m) (subvec b m))]
                       (recur merged remainder)))))]
    (if (zero? rotation)
      result
      (let [rot (mod rotation (count result))]
        (vec (concat (drop rot result) (take rot result)))))))

;; ── Fibonacci ────────────────────────────────────────────────

(defn fibonacci
  "An onset at every Fibonacci number below length."
  {:algo {:in [] :out :pulse :arity 1
          :params {:length {:type :int :min 1 :max 256 :default 16 :doc "pulses"}}}}
  ([length] (fibonacci length [0 1]))
  ([length [a b]]
   (let [fibs (take-while #(< % length)
                          (map first (iterate (fn [[x y]] [y (+ x y)]) [a b])))
         pos  (set fibs)]
     (mapv #(if (pos %) 1 0) (range length)))))

;; ── Prime ────────────────────────────────────────────────────

(defn- prime? [n]
  (and (>= n 2) (not-any? #(zero? (mod n %)) (range 2 (inc (long (Math/sqrt n)))))))

(defn primes
  "An onset at every prime below length (and at 1, unless include-one? is false)."
  {:algo {:in [] :out :pulse
          :params {:length       {:type :int :min 1 :max 256 :default 16 :doc "pulses"}
                   :include-one? {:type :bool :default true :doc "1 counts"}}}}
  [length & {:keys [include-one?] :or {include-one? true}}]
  (let [primes (set (for [i (range length)
                          :when (or (and (= i 1) include-one?)
                                   (and (> i 1) (prime? i)))]
                      i))]
    (mapv #(if (primes %) 1 0) (range length))))

;; ── L-System ─────────────────────────────────────────────────

(defn lsys-rhythm
  "Expand axiom through rules for iterations generations, each character a pulse (A is 1; B or anything else is 0), up to length pulses, zero-padded at the end when the expansion is shorter."
  {:algo {:in [] :out :pulse
          :params {:axiom      {:type :string :default "A" :doc "starting string"}
                   :rules      {:type :map :default {"A" "AB" "B" "A"} :doc "character -> replacement; A = onset"}
                   :iterations {:type :int :min 0 :max 12 :default 5 :doc "generations"}
                   :length     {:type :int :min 1 :max 256 :default 16 :doc "pulses"}}}}
  [axiom rules iterations length]
  (let [expanded (nth (iterate (fn [s]
                                 (str/join (map #(get rules (str %) (str %)) s)))
                               axiom)
                      iterations)
        values   (for [c (take length expanded)] (case c \A 1 \B 0 0))]
    (vec (take length (concat values (repeat 0))))))

;; ── Markov ───────────────────────────────────────────────────

(defn markov-rhythm
  "A pulse pattern from a Markov chain: length steps from initial-state through transition-matrix ({state {next-state probability}}, normalised, so any weights work), each state's output value looked up in states. Draws from musics.algo.numeric.random/markov, so a seed repeats it."
  {:algo {:in [] :out :pulse
          :params {:length            {:type :int :min 1 :max 256 :default 16 :doc "pulses"}
                   :transition-matrix {:type :map :default {"0" {"0" 0.3 "1" 0.7} "1" {"0" 0.6 "1" 0.4}} :doc "state -> {next prob}"}
                   :initial-state     {:type :any :default "0" :doc "first state"}
                   :states            {:type :map :default {"0" 0 "1" 1} :doc "state -> output value"}}}}
  [length transition-matrix & {:keys [initial-state states]
                               :or {initial-state "0" states {"0" 0 "1" 1}}}]
  (loop [i 0 result [] state initial-state]
    (if (= i length) result
        (let [next-state (rand/markov transition-matrix state)]
          (recur (inc i) (conj result (get states state))
                 (or next-state state))))))

(comment
  (euclid 3 8)        ;; => [1 0 0 1 0 0 1 0]
  (fibonacci 13)         ;; beats at 0,1,2,3,5,8
  (primes 20)
  )

;; ============================================================
;; metric
;; ============================================================

;; Clojure port of pymusics src/algorithm/ — numeric/metric-structure
;; pulse generators (modular arithmetic, binary decomposition, continued
;; fractions), as distinct from the pattern-shape generators in
;; musics.algo.rhythmic.pattern.
;; Python/Kotlin sources: rhythm.py

;; ── Binary Decomposition ────────────────────────────────────

(defn bits
  "A number's binary digits as a 0/1 onset grid, least significant bit first: 13 (1101) is [1 0 1 1]. Without length the grid has as many bits as the number needs. With length, a short expansion is zero-padded at the end and a long one keeps the length low-order bits."
  {:algo {:in [] :out :pulse
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

(defn cfrac
  "The continued-fraction expansion of fraction: each term contributes (min term 3) pulses of value (mod term 2), up to length pulses, zero-padded at the end when the expansion is shorter."
  {:algo {:in [] :out :pulse
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

(defn modular
  "An onset wherever (i*multiplier + offset) is a multiple of modulus."
  {:algo {:in [] :out :pulse
          :params {:modulus    {:type :int :min 1 :max 64 :default 7 :doc "onset when divisible by this"}
                   :multiplier {:type :int :min 0 :max 64 :default 3 :doc "step per pulse"}
                   :length     {:type :int :min 1 :max 256 :default 21 :doc "pulses"}
                   :offset     {:type :int :min 0 :max 64 :default 0 :doc "added to every step"}}}}
  [modulus multiplier length offset]
  (mapv #(if (zero? (mod (+ (* % multiplier) offset) modulus)) 1 0)
        (range length)))

(comment
  (modular 7 3 21 0)
  )

;; ============================================================
;; fractal-geometric
;; ============================================================

;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 17-18 -- rhythms from fractals (Cantor set, dragon curve) and
;; geometry (polygon rotation, circle of fifths, golden ratio). L-system
;; rhythms (also ported here originally, as l-system-rhythm) were
;; removed 2026-09-10 -- a confirmed duplicate of musics.algo.rhythmic.pattern/
;; lindenmayer-rhythm, independently reimplemented from a different
;; porting pass; use that one instead (string-keyed rules, not
;; char-keyed, and takes an explicit output length rather than just
;; whatever the raw expansion produces).
;; Fully deterministic, no randomness.

(defn cantor
  "The Cantor set as a rhythm: start with every position on (1), then
   recursively remove the middle third of each remaining run,
   iterations times."
  {:algo {:in [] :out :pulse :arity 2
          :params {:iterations {:type :int :min 0 :max 8 :default 3 :doc "middle thirds removed"}
                   :length     {:type :int :min 1 :max 729 :default 27 :doc "pulses"}}}}
  ([] (cantor 3 27))
  ([iterations length]
   (let [pattern (atom (vec (repeat length 1)))]
     (letfn [(remove-middle [start end level]
               (when (and (< level iterations) (>= (- end start) 3))
                 (let [third (quot (- end start) 3)]
                   (doseq [i (range (+ start third) (+ start (* 2 third)))]
                     (when (< i length) (swap! pattern assoc i 0)))
                   (remove-middle start (+ start third) (inc level))
                   (remove-middle (+ start (* 2 third)) end (inc level)))))]
       (remove-middle 0 length 0)
       @pattern))))

(defn dragon
  "The dragon-curve folding sequence as a binary rhythm: start with a
   single beat, then each iteration appends a beat (the fold) followed
   by the pattern-so-far reversed and bit-flipped."
  {:algo {:in [] :out :pulse :arity 1
          :params {:iterations {:type :int :min 0 :max 10 :default 4 :doc "folds; 2^(n+1)-1 pulses"}}}}
  ([] (dragon 4))
  ([iterations]
   (loop [pattern [1] i 0]
     (if (= i iterations)
       pattern
       (let [flipped-reversed (mapv #(- 1 %) (rseq pattern))]
         (recur (vec (concat pattern [1] flipped-reversed)) (inc i)))))))

(defn polygon
  "A beat wherever a rotating point comes close (within 0.1 of a full
   revolution's worth) to landing exactly on one of an n-sided polygon's
   own vertices, sampled at rotations evenly-spaced steps around one
   full turn starting at offset (a fraction of a turn)."
  {:algo {:in [] :out :pulse :arity 3
          :params {:sides     {:type :int :min 1 :max 32 :default 5 :doc "polygon sides"}
                   :rotations {:type :int :min 1 :max 256 :default 8 :doc "pulses"}
                   :offset    {:type :double :min 0.0 :max 1.0 :default 0.0 :doc "fraction of a turn"}}}}
  ([] (polygon 5 8 0.0))
  ([sides rotations offset]
   (mapv (fn [step]
           (let [angle (* (+ (/ (double step) rotations) offset) 2 Math/PI)
                 vertex-angle (/ (* angle sides) (* 2 Math/PI))
                 distance (Math/abs (- vertex-angle (Math/round vertex-angle)))]
             (if (< distance 0.1) 1 0)))
         (range rotations))))

(defn fifths-rhythm
  "A beat every (pattern-length / notes) steps, at a position stepping
   round the circle of fifths (+7 semitones mod notes) each time --
   notes defaults to 12 (the chromatic circle), so the classic circle
   of fifths (C G D A E B F# C# G# D# A# F)."
  {:algo {:in [] :out :pulse :arity 2
          :params {:notes          {:type :int :min 1 :max 48 :default 12 :doc "circle size"}
                   :pattern-length {:type :int :min 1 :max 256 :default 24 :doc "pulses"}}}}
  ([] (fifths-rhythm 12 24))
  ([notes pattern-length]
   (let [step (quot pattern-length notes)]
     (loop [pattern (vec (repeat pattern-length 0)) current 0 i 0]
       (if (= i pattern-length)
         pattern
         (if (zero? (mod i step))
           (recur (assoc pattern (mod current pattern-length) 1) (mod (+ current 7) notes) (inc i))
           (recur pattern current (inc i))))))))

(defn golden
  "A beat at each position reached by repeatedly stepping forward by
   phi * (length / (beat-count + 1)) -- an irrational, ever-shrinking
   step that spreads beats increasingly evenly (a low-discrepancy
   sequence), continuing until the position runs past length."
  {:algo {:in [] :out :pulse :arity 2
          :params {:length {:type :int :min 1 :max 256 :default 34 :doc "pulses"}
                   :phi    {:type :double :min 1.0 :max 3.0 :default 1.61803398875 :doc "step ratio"}}}}
  ([] (golden 34 1.61803398875))
  ([length] (golden length 1.61803398875))
  ([length phi]
   (loop [pattern (vec (repeat length 0)) position 0.0 beat-count 0]
     (if (>= (int position) length)
       pattern
       (let [idx (int position)
             pattern' (if (< idx length) (assoc pattern idx 1) pattern)
             beat-count' (if (< idx length) (inc beat-count) beat-count)]
         (recur pattern' (+ position (* phi (/ length (inc beat-count')))) beat-count'))))))

(comment
  (cantor 3 27)
  (dragon 4)
  (polygon 5 8 0.0)
  (fifths-rhythm 12 24)
  (golden 21)
  )
