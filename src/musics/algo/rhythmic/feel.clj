(ns musics.algo.rhythmic.feel
  "How a rhythm is played: swing, pocket groove, and variation by EMI and Oblique Strategies."
  (:require [musics.algo.numeric.random :as rand]
            [musics.domain :as d]))

;; ============================================================
;; micro
;; ============================================================

;; Timing feel of a fixed pattern (swing, pocket), not its content. Unrelated to the :micro and
;; :humanization context keys, which delay a note as it plays.

(defn swing
  "Swung onset timings (in beat-duration units, not seconds) of
   pattern's own 1s: on-grid on downbeats, delayed by swing-ratio
   (0.5=straight, ~0.67=typical swing) on upbeats. subdivision is grid
   steps per beat (2 = classic eighth-note swing)."
  {:algo {:in [:pulse] :out :onset :arity 3
          :params {:swing-ratio {:type :double :min 0.0 :max 1.0 :default 0.6 :doc "0.5 straight, ~0.67 swung"}
                   :subdivision {:type :int :min 1 :max 8 :default 2 :doc "grid steps per beat"}}}}
  ([pattern] (swing pattern 0.6 2))
  ([pattern swing-ratio] (swing pattern swing-ratio 2))
  ([pattern swing-ratio subdivision]
   (let [beat-duration (/ 1.0 subdivision)]
     (vec (keep (fn [[i v]]
                  (when (= v 1)
                    (let [beat-position (mod i subdivision)
                          on-grid? (if (= subdivision 2) (zero? beat-position) (even? beat-position))]
                      (if on-grid?
                        (* i beat-duration)
                        (* (+ (dec i) (* 2 swing-ratio)) beat-duration)))))
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
  "A laid-back \"pocket\" groove from base-pattern: each 1 becomes an event delayed by pocket-depth seconds, scaled up for later beats in each 4-beat bar (factor 1 + 0.2*beat-in-bar), with an accent from accent-pattern (default 1 on every 4th beat, 0.5 elsewhere). Assumes 16th notes at 120 BPM (a 0.25 s grid). Returns {:time :accent :beat-position} maps."
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

(defn pocket
  "pocket-groove for notes: each note laid back by pocket-depth
   seconds, 20% more per sixteenth into its beat (a quarter), written
   as the note's own :micro (as c4\\micro:0.05 would). Rests pass
   as they are."
  {:algo {:short :pocket :in [:leaf] :out :leaf
          :params {:pocket-depth {:type :double :min 0.0 :max 0.5 :default 0.05 :doc "seconds laid back"}}}}
  [leaves pocket-depth]
  (let [onsets (reductions + 0 (map #(or (:duration %) 0) leaves))]
    (map (fn [leaf at]
           (if (d/rest? leaf)
             leaf
             (let [sixteenths (int (* 16 (mod at 1/4)))]
               (assoc-in leaf [:overrides :micro] (* pocket-depth (+ 1.0 (* 0.2 sixteenths)))))))
         leaves onsets)))

(comment
  (swing [1 0 1 0 1 0 1 0] 0.67)
  (humanize-rhythm [0.0 0.25 0.5 0.75])
  (pocket-groove [1 0 0 1 0 1 0 0])
  )

;; ============================================================
;; transform
;; ============================================================

;; Pattern variation: David Cope's EMI-style variation and Brian Eno's Oblique Strategies both change a
;; pattern's content.

(defn emi
  "Vary pattern in the style of David Cope's EMI: at each position, with probability (1 - similarity), apply a random mutation (flip this bit, swap it with the next, insert a random bit, or delete this position). similarity near 1.0 stays close to the original, near 0.0 varies heavily. The result is clamped to [half, double] the original length."
  {:algo {:in [:pulse] :out :pulse :arity 2
          :params {:similarity {:type :double :min 0.0 :max 1.0 :default 0.7 :doc "1 = unchanged"}}}}
  ([pattern] (emi pattern 0.7))
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
                                     (recur (inc i) (vec (concat (subvec v 0 i) [(rand/rand-int 0 2)] (subvec v i))))
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
   "mistakes"        (fn [p] (mapv (fn [v] (if (> (rand/rand-double) 0.2) v (- 1 v))) p))
   "silence"         (fn [p] (vec (repeat (count p) 0)))
   "double"          (fn [p] (vec (concat p p)))
   "mirror"          (fn [p] (vec (concat p (reverse p))))})

(defn oblique
  "Apply one of the named oblique-strategies transforms to pattern.
   strategy \"random\" (the default) picks one uniformly at random each
   call; an unrecognized strategy name is a no-op (returns pattern
   unchanged)."
  {:algo {:in [:pulse] :out :pulse :arity 2
          :params {:strategy {:type :string :default "random" :choices ["random" "reverse" "invert" "slowest" "fastest" "disconnect" "mistakes" "silence" "double" "mirror"] :doc "which transform"}}}}
  ([pattern] (oblique pattern "random"))
  ([pattern strategy]
   (let [strategy (if (= strategy "random") (rand/choose (keys oblique-strategies)) strategy)
         f (get oblique-strategies strategy identity)]
     (f pattern))))

(comment
  (emi [1 0 1 0 1 0 1 0] 0.8)
  (oblique [1 0 1 1 0] "reverse")
  )
