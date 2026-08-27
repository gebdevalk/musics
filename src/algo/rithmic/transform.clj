;; transform.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 13-14 -- pattern-transforming (rather than pattern-generating)
;; techniques: David Cope's EMI-style variation, Brian Eno's Oblique
;; Strategies, and microtiming/groove (swing, humanize, pocket).

(ns algo.rithmic.transform
  (:require [algo.random :as rand]))

(defn emi-style-variation
  "Vary pattern in the style of David Cope's EMI: independently, for
   each position, with probability (1 - similarity) apply a random
   mutation (flip this bit, swap it with the next, insert a random bit
   here, or delete this position). similarity close to 1.0 stays close
   to the original; close to 0.0 varies heavily. The result is then
   clamped back to within [half, double] the original length.

   The reference this ports iterates a FIXED number of times (the
   ORIGINAL pattern length, captured once via Python's `range(len(...))`
   before the loop starts) while insert/delete keep resizing the
   working list underneath it -- confirmed live, this throws IndexError
   in ~27% of runs at similarity=0.3, a genuine bug, not a quirk worth
   reproducing. This port instead re-checks the CURRENT length before
   every step, so it always terminates cleanly regardless of how many
   inserts/deletes have happened so far."
  ([pattern] (emi-style-variation pattern 0.7))
  ([pattern similarity]
   (if (empty? pattern)
     []
     (let [n (count pattern)
           varied (loop [i 0 v (vec pattern)]
                    (if (>= i (count v))
                      v
                      (if (> (rand/rand-double) similarity)
                        (case (rand/choose ["flip" "swap" "insert" "delete"])
                          "flip" (recur (inc i) (update v i #(- 1 %)))
                          "swap" (if (< i (dec (count v)))
                                   (recur (inc i) (assoc v i (nth v (inc i)) (inc i) (nth v i)))
                                   (recur (inc i) v))
                          "insert" (if (< (rand/rand-double) 0.3)
                                     (recur (inc i) (vec (concat (subvec v 0 i) [(rand/rand-int 2)] (subvec v i))))
                                     (recur (inc i) v))
                          "delete" (if (> (count v) 1)
                                     (recur i (vec (concat (subvec v 0 i) (subvec v (inc i)))))
                                     (recur (inc i) v)))
                        (recur (inc i) v))))]
       (cond
         (> (count varied) (* n 2)) (subvec varied 0 (* n 2))
         (< (count varied) (quot n 2)) (vec (concat varied (repeat (- (quot n 2) (count varied)) 0)))
         :else varied)))))

(def oblique-strategies
  "Name -> transform fn, Brian Eno's Oblique Strategies applied to a
   binary rhythm pattern."
  {"reverse"         (fn [p] (vec (reverse p)))
   "invert"          (fn [p] (mapv #(- 1 %) p))
   "slowest"         (fn [p] (vec (mapcat #(repeat 3 %) p)))
   "fastest"         (fn [p] (if (> (count p) 1) (vec (take-nth 2 p)) (vec p)))
   "disconnect"      (fn [p] (vec (map-indexed (fn [i v] (if (even? i) v 0)) p)))
   "only_essentials" (fn [p] (vec (map-indexed (fn [i v] (if (and (= v 1) (even? i)) 1 0)) p)))
   "mistakes"        (fn [p] (mapv (fn [v] (if (> (rand/rand-double) 0.2) v (- 1 v))) p))
   "silence"         (fn [p] (vec (repeat (count p) 0)))
   "double"          (fn [p] (vec (concat p p)))
   "mirror"          (fn [p] (vec (concat p (reverse p))))})

(defn oblique-strategies-transform
  "Apply one of the named oblique-strategies transforms to pattern.
   strategy \"random\" (the default) picks one uniformly at random each
   call; an unrecognized strategy name is a no-op (returns pattern
   unchanged)."
  ([pattern] (oblique-strategies-transform pattern "random"))
  ([pattern strategy]
   (let [strategy (if (= strategy "random") (rand/choose (keys oblique-strategies)) strategy)
         f (get oblique-strategies strategy identity)]
     (f pattern))))

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
  (emi-style-variation [1 0 1 0 1 0 1 0] 0.8)
  (oblique-strategies-transform [1 0 1 1 0] "reverse")
  (swing-quantization [1 0 1 0 1 0 1 0] 0.67)
  (humanize-rhythm [0.0 0.25 0.5 0.75])
  (pocket-groove [1 0 0 1 0 1 0 0])
  )
