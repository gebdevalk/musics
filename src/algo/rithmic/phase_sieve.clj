;; phase_sieve.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 1-2 -- Steve Reich's phase-shifting technique ("Clapping Music") and
;; Xenakis' sieve theory.

(ns algo.rithmic.phase-sieve)

(defn clapping-music-phases
  "Phase-shifting patterns in the style of Reich's Clapping Music: total
   phases rotations of pattern, each shifted one place further than the
   last. total-phases defaults to (count pattern)."
  ([pattern] (clapping-music-phases pattern (count pattern)))
  ([pattern total-phases]
   (if (empty? pattern)
     []
     (let [n (count pattern)]
       (mapv (fn [i] (vec (concat (drop (mod i n) pattern) (take (mod i n) pattern))))
             (range total-phases))))))

(defn clapping-music-duet
  "The two parts of Clapping Music: pattern unchanged, and pattern phase
   shifted by phase places. Returns [static-part shifted-part]."
  ([pattern] (clapping-music-duet pattern 0))
  ([pattern phase]
   (if (empty? pattern)
     [[] []]
     (let [phase (mod phase (count pattern))]
       [pattern (vec (concat (drop phase pattern) (take phase pattern)))]))))

(defn xenakis-sieve
  "Xenakis sieve: a binary pattern of the given length where position i
   is 1 iff (i mod m) is in the matching residue list, for at least one
   (modulus, residues) pair in moduli/residues (parallel vectors).

   (xenakis-sieve [3 4] [[0 1] [2]] 12)
   ;=> position in the sieve when (i mod 3) is 0 or 1, OR (i mod 4) is 2"
  [moduli residues length]
  {:pre [(= (count moduli) (count residues))]}
  (mapv (fn [i]
          (if (some (fn [[m rs]] (contains? (set rs) (mod i m)))
                    (map vector moduli residues))
            1 0))
        (range length)))

(defn sieve-from-intervals
  "A pattern with beats at the cumulative sums of intervals, cycling
   through intervals as many times as needed to reach length."
  [intervals length]
  (loop [pattern (vec (repeat length 0)) position 0]
    (if (>= position length)
      pattern
      (let [idx (if (seq intervals) (mod position (count intervals)) 0)
            step (if (seq intervals) (nth intervals idx) 1)]
        (recur (assoc pattern position 1) (+ position step))))))

(comment
  (clapping-music-phases [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (clapping-music-duet [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (xenakis-sieve [3 4] [[0 1] [2]] 12)
  (sieve-from-intervals [2 3] 10)
  )
