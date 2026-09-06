;; micro.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py section 14
;; -- microtiming/groove techniques (swing, humanize, pocket). Split out
;; of algo.rhythmic.transform (2026-09-05): "transform" bundled two
;; genuinely distinct concerns under one too-wide name -- EMI-style/
;; Oblique-Strategies PATTERN VARIATION (content mutation, stayed in
;; transform.clj) versus these three, which adjust onset TIMING FEEL of
;; an already-fixed pattern, not its content. Unrelated to core.async-
;; engine's own :micro/:humanization context keys (per-note MIDI onset
;; delay, a domain/engine feature) -- same word, different namespace,
;; different concern; see that feature's own micro_timing_test.clj.

(ns algo.rhythmic.micro
  (:require [algo.random :as rand]))

(defn swing-quantization
  "Swung onset timings (in beat-duration units, not seconds) of
   pattern's own 1s: on-grid on downbeats, delayed by swing-ratio
   (0.5=straight, ~0.67=typical swing) on upbeats. subdivision is grid
   steps per beat (2 = classic eighth-note swing)."
  ([pattern] (swing-quantization pattern 0.6 2))
  ([pattern swing-ratio] (swing-quantization pattern swing-ratio 2))
  ([pattern swing-ratio subdivision]
   (let [beat-duration (/ 1.0 subdivision)]
     (vec (keep (fn [[i v]]
                  (when (= v 1)
                    (let [beat-position (mod i subdivision)
                          on-grid? (if (= subdivision 2) (zero? beat-position) (even? beat-position))]
                      (if on-grid? (* i beat-duration) (* i beat-duration swing-ratio)))))
                (map-indexed vector pattern))))))

(defn humanize-rhythm
  "Add human-like imperfections to timings: each gets random timing
   jitter (+-timing-variance seconds, 20% larger on upbeats/odd
   indices) and a velocity in [0.1,1.0] (base 0.7 on downbeats, 0.6 on
   upbeats, +-velocity-variance). Returns a seq of {:time :velocity
   :original-time} maps, sorted by (jittered) time."
  ([timings] (humanize-rhythm timings 0.02 0.1))
  ([timings timing-variance velocity-variance]
   (->> timings
        (map-indexed
         (fn [i timing]
           (let [upbeat? (odd? i)
                 jitter-factor (if upbeat? 1.2 1.0)
                 jitter (* (rand/uniform (- timing-variance) timing-variance) jitter-factor)
                 base-velocity (if upbeat? 0.6 0.7)
                 velocity (-> base-velocity (+ (rand/uniform (- velocity-variance) velocity-variance))
                              (max 0.1) (min 1.0))]
             {:time (+ timing jitter) :velocity velocity :original-time timing})))
        (sort-by :time)
        vec)))

(defn pocket-groove
  "A \"pocket\" (laid-back) groove from base-pattern: each 1 becomes an
   event delayed by pocket-depth seconds, scaled up progressively for
   later beats within each 4-beat bar (pocket-factor = 1 + 0.2*beat-in-
   bar), carrying an accent value from accent-pattern (default: 1 on
   every 4th beat, 0.5 elsewhere). Assumes 16th notes at 120 BPM (0.25s
   grid). Returns a seq of {:time :accent :beat-position} maps."
  ([base-pattern] (pocket-groove base-pattern 0.05 nil))
  ([base-pattern pocket-depth accent-pattern]
   (let [n (count base-pattern)
         accent-pattern (or accent-pattern (mapv #(if (zero? (mod % 4)) 1 0.5) (range n)))
         beat-duration 0.25]
     (->> (map-indexed vector base-pattern)
          (keep (fn [[i v]]
                  (when (= v 1)
                    (let [beat-in-bar (mod i 4)
                          pocket-factor (+ 1.0 (* beat-in-bar 0.2))
                          timing (+ (* i beat-duration) (* pocket-depth pocket-factor))
                          accent (if (< i (count accent-pattern)) (nth accent-pattern i) 0.5)]
                      {:time timing :accent accent :beat-position beat-in-bar}))))
          vec))))

(comment
  (swing-quantization [1 0 1 0 1 0 1 0] 0.67)
  (humanize-rhythm [0.0 0.25 0.5 0.75])
  (pocket-groove [1 0 0 1 0 1 0 0])
  )
