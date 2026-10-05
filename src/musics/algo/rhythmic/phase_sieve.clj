;; phase_sieve.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 1-2 -- Steve Reich's phase-shifting technique ("Clapping Music") and
;; Xenakis' sieve theory.

(ns musics.algo.rhythmic.phase-sieve
  (:require [musics.algo.common.rotate :refer [rotate]]))

(defn clapping-music-phases
  "Phase-shifting patterns in the style of Reich's Clapping Music: total
   phases rotations of pattern, each shifted one place further than the
   last. total-phases defaults to (count pattern)."
  {:algo {:short :phases :in [:pulse] :out :layer :arity 2
          :params {:total-phases {:type :int :min 1 :max 64 :default 12 :doc "rotations"}}}}
  ([pattern] (clapping-music-phases pattern (count pattern)))
  ([pattern total-phases]
   (if (empty? pattern)
     []
     (mapv #(rotate pattern %) (range total-phases)))))

(defn clapping-music-duet
  "The two parts of Clapping Music: pattern unchanged, and pattern phase
   shifted by phase places. Returns [static-part shifted-part]."
  {:algo {:short :duet :in [:pulse] :out :layer :arity 2
          :params {:phase {:type :int :min 0 :max 64 :default 1 :doc "places shifted"}}}}
  ([pattern] (clapping-music-duet pattern 0))
  ([pattern phase]
   (if (empty? pattern)
     [[] []]
     [pattern (rotate pattern phase)])))

(defn xenakis-sieve
  "Xenakis sieve: a binary pattern of the given length where position i
   is 1 iff (i mod m) is in the matching residue list, for at least one
   (modulus, residues) pair in moduli/residues (parallel vectors).

   (xenakis-sieve [3 4] [[0 1] [2]] 12)
   ;=> position in the sieve when (i mod 3) is 0 or 1, OR (i mod 4) is 2"
  {:algo {:short :sieve :in [] :out :pulse
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

(defn sieve-from-intervals
  "A pattern with beats at the cumulative sums of intervals, cycling
   through intervals as many times as needed to reach length."
  {:algo {:short :interval-sieve :in [] :out :pulse
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
  (clapping-music-phases [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (clapping-music-duet [1 1 1 0 1 1 0 1 0 1 1 0] 3)
  (xenakis-sieve [3 4] [[0 1] [2]] 12)
  (sieve-from-intervals [2 3] 10)
  )
