;; rand.clj
;; Stateful/composite random generators for generative composition --
;; random walks, biased ranges, a Markov chain walker, and a rhythm
;; generator. The plain-distribution primitives these build on
;; (rand-uniform, rand-normal) live in algo.random.distributions, and the
;; discrete/collection primitives (weighted-choose, weighted-coin) live in
;; algo.random.chance -- this file used to duplicate both under its own
;; names (rand-range, rand-item, rand-gaussian, weighted-item,
;; random-trigger); those were dropped in favor of requiring the
;; canonical version from wherever it actually lives, rather than keeping
;; four concepts under two names apiece across the directory.

(ns algo.random.rand
  (:require [clojure.math :as math]
            [algo.random.distributions :as dist]
            [algo.random.chance :as chance]))

;; ============================================================
;; 1. Basic utilities
;; ============================================================

(defn rand-int-range
  "Returns random integer between lo (inclusive) and hi (exclusive)"
  {:doc/format :int}
  [lo hi]
  (+ lo (rand-int (- hi lo))))

;; ============================================================
;; 2. Cyclic random sequence (each item once per cycle)
;; ============================================================

(defn shuffle-seq
  "Fisher-Yates shuffle of a sequence"
  {:doc/format :seq}
  [coll]
  (let [v (vec coll)]
    (loop [i (dec (count v)), v v]
      (if (zero? i)
        v
        (let [j (rand-int (inc i))]
          (recur (dec i) (assoc v i (v j) j (v i))))))))

(defn cyclic-random
  "Returns a function that yields random items from coll, reshuffling
   after exhausting all items. Perfect for arpeggios or drum fills."
  {:doc/format :fn}
  [coll]
  (let [state (atom {:pool (shuffle-seq coll) :idx 0})]
    (fn []
      (let [{:keys [pool idx]} @state]
        (when (= idx (count pool))
          (swap! state assoc :pool (shuffle-seq coll) :idx 0))
        (let [item (get pool idx)]
          (swap! state update :idx inc)
          item)))))

;; ============================================================
;; 3. Weighted random (triangular distribution)
;; ============================================================

(defn rand-triangular
  "Triangular distribution with peak at `mode`.
   Values cluster around mode, fewer at extremes.
   Great for natural note durations or velocities."
  {:doc/format :float}
  [lo hi mode]
  (let [u (rand)
        f (/ (- mode lo) (- hi lo))]
    (if (< u f)
      (+ lo (* (math/sqrt (* u (- hi lo) (- mode lo)))))
      (- hi (* (math/sqrt (* (- 1 u) (- hi lo) (- hi mode))))))))

;; ============================================================
;; 4. Random walk (Brownian motion)
;; ============================================================

(defn random-walk
  "Returns a function that moves randomly by at most `step-bound` each call.
   Optional clipping keeps values in range. Good for LFOs or gradual changes."
  {:doc/format :fn}
  [start step-bound & {:keys [clip-lo clip-hi]}]
  (let [state (atom start)]
    (fn []
      (let [next (+ @state (dist/rand-uniform (- step-bound) step-bound))]
        (reset! state (cond
                        (and clip-lo clip-hi) (-> next (max clip-lo) (min clip-hi))
                        clip-lo (max next clip-lo)
                        clip-hi (min next clip-hi)
                        :else next))))))

;; ============================================================
;; 5. Rising chance random (biased upward)
;; ============================================================

(defn rand-rising
  "Returns random float between lo and hi with upward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors high values.
   Use for crescendos, rising pitch lines, or increasing density."
  {:doc/format :float}
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (dist/rand-uniform lo hi)
      (+ lo (* (- hi lo) (math/pow (rand) (/ 1 (inc (* b 9)))))))))

;; ============================================================
;; 6. Falling chance random (biased downward)
;; ============================================================

(defn rand-falling
  "Returns random float between lo and hi with downward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors low values.
   Use for decrescendos, falling pitch lines, or fading effects."
  {:doc/format :float}
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (dist/rand-uniform lo hi)
      (- hi (* (- hi lo) (math/pow (rand) (/ 1 (inc (* b 9)))))))))

;; ============================================================
;; 7. Random rhythm generator
;; ============================================================

(defn random-rhythm
  "Generates a sequence of event times within num-beats.
   Each beat has density% chance of containing an event.
   Example: (random-rhythm 0.25 16 0.3) → sparse 16th-note pattern"
  {:doc/format :seq}
  [beat-duration num-beats density]
  (let [events (map #(when (chance/weighted-coin density) (* beat-duration %))
                     (range num-beats))]
    (filter some? events)))

;; ============================================================
;; 8. Markov chain
;; ============================================================

(defn markov-chain
  "Returns a function that walks through states using transition weights.
   transitions: {state {next-state weight, ...}, ...}
   Example: (markov-chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)"
  {:doc/format :fn}
  [transitions start-state]
  (let [state (atom start-state)]
    (fn []
      (let [next (chance/weighted-choose (get transitions @state))]
        (reset! state next)
        next))))

;; ============================================================
;; 9. Smooth walk (target-chasing with memory)
;; ============================================================

(defn smooth-walk
  "Returns a function that moves toward a target each call with inertia.
   inertia=0 → snaps to target, inertia=1 → ignores target.
   Perfect for portamento or filter envelope following."
  {:doc/format :fn}
  [initial inertia step]
  (let [state (atom initial)]
    (fn [target]
      (let [current @state
            next-val (+ current (* inertia (- target current))
                        (dist/rand-uniform (- step) step))]
        (reset! state next-val)
        next-val))))

;; ============================================================
;; 10. Random with rising/falling bias (integer version)
;; ============================================================

(defn rand-int-rising
  "Integer version of rand-rising. Returns int between lo and hi-1
   with upward bias. Great for choosing higher pitches more often."
  {:doc/format :int}
  [lo hi bias]
  (int (math/floor (rand-rising (double lo) (double hi) bias))))

(defn rand-int-falling
  "Integer version of rand-falling. Returns int between lo and hi-1
   with downward bias. Great for choosing lower pitches more often."
  {:doc/format :int}
  [lo hi bias]
  (int (math/floor (rand-falling (double lo) (double hi) bias))))

;; ============================================================
;; 11. Walk with rising/falling bias
;; ============================================================

(defn biased-walk
  "Like random-walk but with directional bias.
   bias > 0.5 trends upward, < 0.5 trends downward.
   Use for melodic lines with intentional contour."
  {:doc/format :fn}
  [start step-bound bias & {:keys [clip-lo clip-hi]}]
  (let [state (atom start)]
    (fn []
      (let [dir (if (< (rand) bias) 1 -1)
            step (* dir (dist/rand-uniform 0 step-bound))
            next (+ @state step)]
        (reset! state (cond
                        (and clip-lo clip-hi) (-> next (max clip-lo) (min clip-hi))
                        clip-lo (max next clip-lo)
                        clip-hi (min next clip-hi)
                        :else next))))))

;; ============================================================
;; Example: Complete generative music patch
;; ============================================================

(defn generative-patch
  "Returns a function that generates musical events with rising/falling tendencies."
  {:doc/format :fn}
  []
  (let [pitch-cycler (cyclic-random (range 60 72))
        pitch-bias (rand-rising 0 1 0.7) ;; 70% upward bias per step
        velocity-walk (biased-walk 80 15 0.4 :clip-lo 30 :clip-hi 127) ;; slight down bias
        rhythm-trigger #(chance/weighted-coin 0.3)
        duration-fn #(rand-falling 0.1 0.5 0.6)] ;; shorter durations favored

    (fn []
      (when (rhythm-trigger)
        {:pitch (pitch-cycler)
         :velocity (velocity-walk)
         :duration (duration-fn)
         :bend (* 2 (rand-rising -1 1 0.6))}))))

;; ============================================================
;; Interactive examples
;; ============================================================

(comment
  ;; Rising pitch bias - higher notes more common
  (repeatedly 10 #(rand-rising 60 80 0.8))
  ;; => (78.2 76.1 79.8 73.4 ...)

  ;; Falling duration - shorter notes favored
  (repeatedly 10 #(rand-falling 0.1 0.5 0.7))
  ;; => (0.12 0.18 0.15 0.32 ...)

  ;; Biased walk - melody with upward contour
  (def melody (biased-walk 60 2 0.7 :clip-lo 50 :clip-hi 80))
  (repeatedly 20 melody)

  ;; Integer rising for scale degrees
  (def scales [:I :II :III :IV :V :VI :VII])
  (nth scales (rand-int-rising 0 7 0.8)) ;; favors higher scale degrees
  )
