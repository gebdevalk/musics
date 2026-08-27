;; necklace.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 5-6 -- rhythm necklaces/bracelets (rotation/reversal equivalence
;; classes of a binary pattern) and rhythmic tiling canons (Vuza).

(ns algo.rithmic.necklace)

(defn- rotate [pattern i]
  (let [n (count pattern)]
    (vec (concat (drop (mod i n) pattern) (take (mod i n) pattern)))))

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
  "Try to tile length positions with copies of pattern-a and pattern-b
   laid end-to-end (each repeated from position 0 as many times as
   fits). Returns [combined tiling?] -- combined is the resulting
   binary pattern (0 wherever nothing landed, regardless of whether a
   real overlap-free tiling was achieved), tiling? is true only if the
   two patterns cover every position exactly once with no overlap."
  [pattern-a pattern-b length]
  (let [after-a (place-tiles (vec (repeat length 0)) pattern-a length)]
    (if (= after-a ::overlap)
      [(vec (repeat length 0)) false]
      (let [after-b (place-tiles after-a pattern-b length)]
        (if (= after-b ::overlap)
          [after-a false]
          [after-b (every? #(= 1 %) after-b)])))))

(defn vuza-canon
  "Candidate Vuza canon pairs for length n: for every divisor pair
   (a-len, b-len = n/a-len), a simple single-beat pattern of each
   length that tiles n with no overlap. A simplified search (real Vuza
   canon generation is a much harder combinatorial problem) -- may
   return an empty list for many n, same as the reference this ports."
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
