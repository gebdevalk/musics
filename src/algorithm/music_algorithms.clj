;; music_algorithms.clj
;; Clojure port of pymusics src/algorithm/ — algorithmic composition core.
;;
;; Sections: Indispensability, Rhythm generators, Melodic algorithms
;; Python/Kotlin sources: indispensability.py, Indispensabilities.kt,
;;   rhythm.py, rule_based_melodic_algorithms.py

(ns algorithm.music-algorithms
  (:require [clojure.string :as str]))

;; ============================================================
;; 1. INDISPENSABILITY (Barlow's psi)
;; ============================================================

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

;; ============================================================
;; 2. RHYTHM GENERATORS
;; ============================================================

;; ── Euclidean (Bjorklund) ────────────────────────────────────

(defn euclidean-rhythm
  "Distribute k beats evenly among n pulses."
  [k n & {:keys [rotation] :or {rotation 0}}]
  {:pre [(<= k n) (>= k 0) (pos? n)]}
  (if (zero? k)
    (vec (repeat n 0))
    (let [pattern (vec (concat (repeat k [1]) (repeat (- n k) [0])))]
      (loop [pat pattern]
        (let [min-len  (apply min (map count pat))
              min-idxs (keep-indexed #(when (= min-len (count %2)) %1) pat)]
          (if (= (count min-idxs) (count pat))
            (let [result (mapcat identity pat)]
              (if (zero? rotation)
                (vec result)
                (let [rot (mod rotation (count result))]
                  (vec (concat (drop rot result) (take rot result))))))
            (let [new-pat
                  (loop [i 0 res pat]
                    (if (< i (count min-idxs))
                      (let [ti (- (count res) 1 i)
                            vi (nth res (nth min-idxs i))]
                        (recur (inc i) (update res ti #(concat % vi))))
                      res))]
              (recur (vec (keep-indexed
                           #(when-not (some #{ %1} min-idxs) %2)
                           new-pat))))))))))

;; ── Fibonacci ────────────────────────────────────────────────

(defn fibonacci-rhythm
  ([length] (fibonacci-rhythm length [0 1]))
  ([length [a b]]
   (let [fibs (take-while #(< % length)
                          (map first (iterate (fn [[x y]] [y (+ x y)]) [a b])))
         pos  (set fibs)]
     (mapv #(if (pos %) 1 0) (range length)))))

;; ── Prime ────────────────────────────────────────────────────

(defn- prime? [n]
  (and (>= n 2) (not-any? #(zero? (mod n %)) (range 2 (inc (long (Math/sqrt n)))))))

(defn prime-rhythm
  [length & {:keys [include-one?] :or {include-one? true}}]
  (let [primes (set (for [i (range length)
                          :when (or (and (= i 1) include-one?)
                                   (and (> i 1) (prime? i)))]
                      i))]
    (mapv #(if (primes %) 1 0) (range length))))

;; ── L-System ─────────────────────────────────────────────────

(defn lindenmayer-rhythm
  [axiom rules iterations length]
  (let [expanded (nth (iterate (fn [s]
                                 (str/join (map #(get rules (str %) (str %)) s)))
                               axiom)
                      iterations)]
    (->> (for [c (take length expanded)] (case c \A 1 \B 0 0))
         (concat (repeat length 0)) (take length) vec)))

;; ── Markov ───────────────────────────────────────────────────

(defn markov-rhythm
  [length transition-matrix & {:keys [initial-state states]
                               :or {initial-state "0" states {"0" 0 "1" 1}}}]
  (loop [i 0 result [] state initial-state]
    (if (= i length) result
        (let [transitions (get transition-matrix state)
              r (rand)
              next-state (loop [[[ns prob] & more] (seq transitions) cum 0.0]
                           (let [c (+ cum prob)]
                             (if (<= r c) ns (recur more c))))]
          (recur (inc i) (conj result (get states state))
                 (or next-state state))))))

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

;; ============================================================
;; 3. MELODIC ALGORITHMS
;; ============================================================

(def chromatic-notes ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn build-scale [root intervals]
  (let [idx (.indexOf (vec chromatic-notes) root)]
    (mapv #(nth chromatic-notes (mod (+ idx %) 12)) intervals)))

(def c-major      (build-scale "C" [0 2 4 5 7 9 11]))
(def a-minor      (build-scale "A" [0 2 3 5 7 8 10]))
(def c-pentatonic (build-scale "C" [0 2 4 7 9]))

;; ── Markov Chain Melody ─────────────────────────────────────

(defn markov-train [melody order]
  (let [pairs (for [i (range (- (count melody) order))]
                [(vec (subvec melody i (+ i order)))
                 (nth melody (+ i order))])]
    {:order order
     :transitions (reduce (fn [m [s nxt]]
                            (update m s #(conj (or % []) nxt)))
                          {} pairs)}))

(defn markov-generate [model length & {:keys [seed]}]
  (let [{:keys [order transitions]} model
        seed (or seed (first (shuffle (keys transitions))))]
    (loop [melody (vec seed) state seed]
      (if (>= (count melody) length)
        (vec (take length melody))
        (if-let [choices (seq (get transitions state))]
          (let [nxt (rand-nth choices)]
            (recur (conj melody nxt)
                   (vec (take-last order (conj melody nxt)))))
          (let [ns (rand-nth (vec (keys transitions)))]
            (recur (conj melody (first ns)) ns)))))))

;; ── L-System Melody ─────────────────────────────────────────

(defn lsystem-melody
  [axiom rules note-map iterations max-notes]
  (let [expanded (nth (iterate (fn [s]
                                 (str/join (map #(get rules (str %) (str %)) s)))
                               axiom)
                      iterations)]
    (->> (keep (fn [c] (get note-map c)) expanded)
         (take max-notes) vec)))

;; ── Generative Grammar ──────────────────────────────────────

(defn grammar-generate
  [rules terminals & {:keys [start max-depth remove-rests?]
                      :or {start 'S max-depth 10 remove-rests? true}}]
  (letfn [(expand [sym depth]
            (if (or (terminals sym) (>= depth max-depth))
              [sym]
              (when-let [prods (seq (get rules sym))]
                (let [chosen (rand-nth prods)]
                  (mapcat #(expand % (inc depth)) chosen)))))]
    (let [raw (expand start 0)]
      (if remove-rests?
        (vec (remove #(= % "REST") raw))
        (vec raw)))))

;; ── Constraint Satisfaction ─────────────────────────────────

(defn constraint-melody
  [scale length constraints & {:keys [start]}]
  (let [first-note (or start (rand-nth scale))]
    (loop [melody [first-note]]
      (if (>= (count melody) length)
        (vec melody)
        (let [valid (filter (fn [note]
                              (every? (fn [c] (c melody note)) constraints))
                            scale)
              candidates (if (empty? valid) scale valid)]
          (recur (conj melody (rand-nth candidates))))))))

(defn max-leap-constraint [scale max-degrees]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (empty? melody) true
          (let [pi (.indexOf sv (last melody))
                ni (.indexOf sv note)]
            (<= (abs (- ni pi)) max-degrees))))))

(defn no-repeat-constraint [melody note]
  (or (empty? melody) (not= (last melody) note)))

(defn direction-limit-constraint [scale max-consecutive]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (< (count melody) 2) true
          (let [ni (.indexOf sv note)
                pi (.indexOf sv (last melody))]
            (if (or (neg? ni) (neg? pi) (= ni pi)) true
                (let [new-dir (if (> ni pi) 1 -1)]
                  (loop [i (dec (count melody)) cnt 0]
                    (if (< i 0) true
                        (let [a (.indexOf sv (nth melody i))
                              b (.indexOf sv (nth melody (dec i)))]
                          (if (or (neg? a) (neg? b) (= a b)) true
                              (if (and (= (if (> a b) 1 -1) new-dir)
                                       (>= cnt max-consecutive))
                                false
                                (recur (dec i) (inc cnt))))))))))))))

(defn cadence-constraint [target-length cadence-note]
  (fn [melody note]
    (if (= (count melody) (dec target-length))
      (= note cadence-note) true)))

;; ============================================================
;; REPL tests
;; ============================================================

(comment
  (psi 0 [2 3 2])               ;; => 1
  (psi-fractions [2 2])         ;; => [1/4 3/4 2/4 4/4]
  (euclidean-rhythm 3 8)        ;; => [1 0 0 1 0 0 1 0]
  (fibonacci-rhythm 13)         ;; beats at 0,1,2,3,5,8
  (prime-rhythm 20)
  (modular-rhythm 7 3 21 0)
  (constraint-melody c-major 16
    [(max-leap-constraint c-major 2) no-repeat-constraint
     (cadence-constraint 16 "C")])
  )
