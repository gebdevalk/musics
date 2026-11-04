(ns musics.algo.rhythmic.sieve
  "Rhythms from combinatorics and sieves: Xenakis sieves, clapping-music phases, cellular automata, necklaces, bracelets and tilings."
  (:require [musics.algo.common.rotate :refer [rotate]]
            [musics.algo.numeric.random :as random]))

;; ============================================================
;; phase-sieve
;; ============================================================

;; Steve Reich's phase shifting (Clapping Music) and Xenakis' sieves.

(defn phases
  "Phase-shifting patterns in the style of Reich's Clapping Music: total
   phases rotations of pattern, each shifted one place further than the
   last. total-phases defaults to (count pattern)."
  {:algo {:in [:pulse] :out :layer :arity 2
          :params {:total-phases {:type :int :min 1 :max 64 :default 12 :doc "rotations"}}}}
  ([pattern] (phases pattern (count pattern)))
  ([pattern total-phases]
   (if (empty? pattern)
     []
     (mapv #(rotate pattern %) (range total-phases)))))

(defn duet
  "The two parts of Clapping Music: pattern unchanged, and pattern phase
   shifted by phase places. Returns [static-part shifted-part]."
  {:algo {:in [:pulse] :out :layer :arity 2
          :params {:phase {:type :int :min 0 :max 64 :default 1 :doc "places shifted"}}}}
  ([pattern] (duet pattern 0))
  ([pattern phase]
   (if (empty? pattern)
     [[] []]
     [pattern (rotate pattern phase)])))

(defn sieve
  "Xenakis sieve: a binary pattern of the given length where position i
   is 1 iff (i mod m) is in the matching residue list, for at least one
   (modulus, residues) pair in moduli/residues (parallel vectors).

   (sieve [3 4] [[0 1] [2]] 12)
   ;=> position in the sieve when (i mod 3) is 0 or 1, OR (i mod 4) is 2"
  {:algo {:in [] :out :pulse
          :params {:moduli   {:type :vector :default [3 4] :doc "one per residue list"}
                   :residues {:type :vector :default [[0 1] [2]] :doc "onset when i mod m is one of these"}
                   :length   {:type :int :min 1 :max 256 :default 12 :doc "pulses"}}}}
  [moduli residues length]
  {:pre [(= (count moduli) (count residues))]}
  (mapv (fn [i]
          (if (some (fn [[m rs]] (contains? (set rs) (mod i m)))
                    (map vector moduli residues))
            1 0))
        (range length)))

(defn interval-sieve
  "A pattern with beats at the cumulative sums of intervals, cycling
   through intervals as many times as needed to reach length."
  {:algo {:in [] :out :pulse
          :params {:intervals {:type :vector :default [2 3] :doc "gaps between onsets, cycled"}
                   :length    {:type :int :min 1 :max 256 :default 16 :doc "pulses"}}}}
  [intervals length]
  (loop [pattern (vec (repeat length 0)) position 0]
    (if (>= position length)
      pattern
      (let [idx (if (seq intervals) (mod position (count intervals)) 0)
            step (if (seq intervals) (nth intervals idx) 1)]
        (recur (assoc pattern position 1) (+ position step))))))

(comment
  (phases [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (duet [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (sieve [3 4] [[0 1] [2]] 12)
  (interval-sieve [2 3] 10)
  )

;; ============================================================
;; automaton
;; ============================================================

(defn- step
  "The next generation of `cells` under `rule`, the row's ends wrapping."
  [rule cells]
  (let [n (count cells)]
    (mapv (fn [i]
            (let [l (cells (mod (dec i) n)) c (cells i) r (cells (mod (inc i) n))]
              (if (bit-test rule (+ (* 4 l) (* 2 c) r)) 1 0)))
          (range n))))

(defn ca
  "An elementary cellular automaton's generations as pulses: :width cells
   a bar, :steps bars, the first a single live cell in the middle or
   :random. Rule 90 draws Sierpinski's triangle, 30 chaos, 110 structure."
  {:algo {:in [] :out :pulse
          :params {:rule  {:type :int :min 0 :max 255 :default 90 :doc "Wolfram's rule number"}
                   :width {:type :int :min 3 :max 64 :default 16 :doc "cells, pulses a bar"}
                   :steps {:type :int :min 1 :max 64 :default 8 :doc "generations, bars"}
                   :start {:type :keyword :choices [:single :random] :default :single :doc "the first generation"}}}}
  [rule width steps start]
  (let [first-gen (if (= :random start)
                    (vec (repeatedly width #(if (< (random/rand-double) 0.5) 1 0)))
                    (assoc (vec (repeat width 0)) (quot width 2) 1))]
    (vec (apply concat (take steps (iterate (partial step rule) first-gen))))))

;; ============================================================
;; necklace
;; ============================================================

;; Rhythm necklaces (patterns equal under rotation) and bracelets (also under reflection), and rhythmic
;; tiling canons (Vuza).

(defn rhythm-necklace
  "All unique rotations of pattern -- its necklace equivalence class."
  [pattern]
  (if (empty? pattern)
    []
    (reduce (fn [necklaces i]
              (let [rotated (rotate pattern i)]
                (if (some #(= rotated %) necklaces) necklaces (conj necklaces rotated))))
            []
            (range (count pattern)))))

(defn rhythm-bracelet
  "All unique rotations AND reversals of pattern -- its bracelet
   equivalence class (a superset of rhythm-necklace)."
  [pattern]
  (if (empty? pattern)
    []
    (reduce (fn [bracelets necklace]
              (let [bracelets' (if (some #(= necklace %) bracelets) bracelets (conj bracelets necklace))
                    reversed'  (vec (reverse necklace))]
                (if (some #(= reversed' %) bracelets') bracelets' (conj bracelets' reversed'))))
            []
            (rhythm-necklace pattern))))

(defn- all-binary-patterns
  "Every binary vector of length n, in ascending order when read as a
   binary number (bit 0 = leftmost)."
  [n]
  (for [i (range (bit-shift-left 1 n))]
    (mapv #(if (bit-test i %) 1 0) (range n))))

(defn all-binary-necklaces
  "One representative pattern per rotation-equivalence class among all
   binary vectors of length n -- optionally restricted to patterns with
   exactly k ones."
  ([n] (all-binary-necklaces n nil))
  ([n k]
   (let [patterns (cond->> (all-binary-patterns n)
                    k (filter #(= k (reduce + %))))]
     (loop [[pattern & more] patterns seen #{} necklaces []]
       (if (nil? pattern)
         necklaces
         (let [rotations (mapv #(rotate pattern %) (range n))]
           (if (contains? seen pattern)
             (recur more seen necklaces)
             (recur more (into seen rotations) (conj necklaces pattern)))))))))

(defn- nth-wrapping [xs index] (if (seq xs) (nth xs (mod index (count xs))) []))

(defn bracelet
  "The index-th of pattern's unique rotations and reversals
   (rhythm-bracelet), wrapping."
  {:algo {:in [:pulse] :out :pulse
          :params {:index {:type :int :min 0 :max 63 :default 1 :doc "which rotation or reversal"}}}}
  [pattern index]
  (nth-wrapping (rhythm-bracelet pattern) index))

(defn necklaces
  "The index-th rotation class of n pulses with k onsets
   (all-binary-necklaces), wrapping."
  {:algo {:in [] :out :pulse
          :params {:n     {:type :int :min 1 :max 16 :default 8 :doc "pulses"}
                   :k     {:type :int :min 0 :max 16 :default 3 :doc "onsets"}
                   :index {:type :int :min 0 :max 1023 :default 0 :doc "which class"}}}}
  [n k index]
  (nth-wrapping (all-binary-necklaces n k) index))

(defn- place-tiles
  "Lay pattern down repeatedly, starting at every multiple of its own
   length, into combined (a length-n vector of 0/1) -- ::overlap if any
   tile would land a 1 on a position already 1."
  [combined pattern length]
  (let [positions (for [i (range 0 length (count pattern))
                         [j v] (map-indexed vector pattern)
                         :let [pos (+ i j)]
                         :when (and (< pos length) (= 1 v))]
                     pos)]
    (reduce (fn [c pos]
              (if (= 1 (nth c pos))
                (reduced ::overlap)
                (assoc c pos 1)))
            combined positions)))

(defn rhythmic-tiling
  "Try to tile length positions with pattern-a and pattern-b, each repeated from position 0 as often as fits. Returns [combined tiling?]: combined is the binary pattern (0 where nothing landed), tiling? is true only when the two cover every position exactly once without overlap."
  [pattern-a pattern-b length]
  (let [after-a (place-tiles (vec (repeat length 0)) pattern-a length)]
    (if (= after-a ::overlap)
      [(vec (repeat length 0)) false]
      (let [after-b (place-tiles after-a pattern-b length)]
        (if (= after-b ::overlap)
          [after-a false]
          [after-b (every? #(= 1 %) after-b)])))))

(defn tiling
  "The grid rhythmic-tiling lays from pattern-a and pattern-b over
   length pulses (whether or not they tile it exactly)."
  {:algo {:in [:pulse :pulse] :out :pulse
          :params {:length {:type :int :min 1 :max 256 :default 12 :doc "pulses"}}}}
  [pattern-a pattern-b length]
  (first (rhythmic-tiling pattern-a pattern-b length)))

(defn vuza-canon
  "Candidate Vuza canon pairs for length n: for each divisor pair (a-len, b-len = n/a-len), a single-beat pattern of each length that tiles n without overlap. A simplified search (real Vuza canons are a much harder combinatorial problem), so it may return an empty list for many n."
  [n]
  (let [divisors (filter #(zero? (mod n %)) (range 1 n))]
    (reduce (fn [candidates a-len]
              (let [b-len (quot n a-len)
                    pattern-a (into [1] (repeat (dec a-len) 0))
                    pattern-b (into [1] (repeat (dec b-len) 0))
                    [_ tiling?] (rhythmic-tiling pattern-a pattern-b n)]
                (if tiling? (conj candidates [pattern-a pattern-b]) candidates)))
            [] divisors)))

(comment
  (rhythm-necklace [1 0 1 0 0])
  (rhythm-bracelet [1 0 1 0 0])
  (all-binary-necklaces 4 2)
  (rhythmic-tiling [1 0 0] [0 1 0] 9)
  (vuza-canon 9)
  )
