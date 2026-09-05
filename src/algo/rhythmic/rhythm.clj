;; rhythm.clj
;; Clojure port of pymusics src/algorithm/ — rhythm-pattern generators
;; (Euclidean, Fibonacci, prime, L-system, Markov).
;; Python/Kotlin sources: rhythm.py

(ns algo.rhythmic.rhythm
  (:require [clojure.string :as str]))

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
  "Expand axiom through rules for iterations generations, mapping each
   character to a 1 (A) or 0 (B, or anything else) pulse, up to length
   pulses -- zero-padded at the END if the expanded string comes up
   shorter than length. (Fixed 2026-09-03: the zero-padding used to be
   concatenated BEFORE the real values, so the final take only ever
   returned zeros regardless of axiom/rules/iterations -- confirmed
   live, a real bug, not a hypothetical one.)"
  [axiom rules iterations length]
  (let [expanded (nth (iterate (fn [s]
                                 (str/join (map #(get rules (str %) (str %)) s)))
                               axiom)
                      iterations)
        values   (for [c (take length expanded)] (case c \A 1 \B 0 0))]
    (vec (take length (concat values (repeat 0))))))

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

(comment
  (euclidean-rhythm 3 8)        ;; => [1 0 0 1 0 0 1 0]
  (fibonacci-rhythm 13)         ;; beats at 0,1,2,3,5,8
  (prime-rhythm 20)
  )
