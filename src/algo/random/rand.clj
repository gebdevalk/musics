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
  (:refer-clojure :exclude [rand rand-int])
  (:require [clojure.math :as math]
            [algo.random.seed :refer [rand rand-int]]
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
;; 3a. Linear distribution
;; ============================================================

(defn rand-linear
  "Linear-density distribution over [lo, hi]: probability density ramps
   monotonically from one bound to the other, rather than peaking at a
   single mode (rand-triangular) -- Xenakis's own \"linear distribution,\"
   used alongside uniform/exponential/Cauchy in his own stochastic
   pieces (see Roads, The Computer Music Tutorial). rising? true
   (the default) means density increases toward hi -- values near hi
   are more likely; false means density increases toward lo instead."
  {:doc/format :float}
  ([lo hi] (rand-linear lo hi true))
  ([lo hi rising?]
   (let [u (rand)]
     (if rising?
       (+ lo (* (- hi lo) (math/sqrt u)))
       (- hi (* (- hi lo) (math/sqrt u)))))))

;; ============================================================
;; 3b. Arcsine distribution
;; ============================================================

(defn rand-arcsine
  "Arcsine distribution over [lo, hi]: density is HIGHEST at the two
   extremes and lowest in the middle -- the mirror image of
   rand-triangular's peaked-at-the-mode shape, not just \"triangular
   flipped\" but a real, separately-named distribution, from the same
   Xenakis-derived toolkit rand-linear comes from."
  {:doc/format :float}
  [lo hi]
  (let [s (math/sin (* math/PI (/ (rand) 2)))]
    (+ lo (* (- hi lo) s s))))

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
;; 7a. Poisson-process event generator
;; ============================================================

(defn poisson-events
  "Event onset times within [0, duration), Poisson-process style: keeps
   drawing inter-arrival gaps from an exponential distribution with the
   given rate (expected events per unit duration -- the same rate
   parameter Xenakis used to control event density in Achorripsis, via
   dist/rand-exponential's own mean = 1/rate) and accumulating them,
   until the running total would exceed duration -- genuine stochastic
   clustering/spacing, rather than random-rhythm's per-tick coin-flip
   approximation of the same idea.

   (poisson-events 4 8) → a handful of onsets across an 8-beat phrase,
   averaging 4 per beat"
  {:doc/format :seq}
  [rate duration]
  (loop [t 0.0 events []]
    (let [gap (dist/rand-exponential (/ 1.0 rate))
          t'  (+ t gap)]
      (if (< t' duration)
        (recur t' (conj events t'))
        events))))

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
;; 9a. Smooth noise (a continuous curve, not a step-by-step generator)
;; ============================================================

(defn smooth-noise
  "Build a smooth, continuous noise curve over [0, n-1] from n randomly
   seeded lattice points, blended with Perlin's own quintic ease
   (6t^5 - 15t^4 + 10t^3, zero first AND second derivative at each
   lattice point) so consecutive segments meet with no visible seam --
   value noise, not true gradient/Perlin noise, but plenty smooth for
   parameter automation. Unlike random-walk/biased-walk, the result is
   a pure function of t: call it with ANY real t in range, in any
   order, as many times as you like, and it always returns the same
   value -- the same way any other context envelope gets sampled at a
   structural time, not \"give me the next tick.\" t outside [0, n-1]
   clamps to the nearest end rather than extrapolating or wrapping.

   ((smooth-noise 8) 3.5)        ;; this curve's value 3.5 steps in
   ((smooth-noise 8 20 80) 0)    ;; ranged to e.g. MIDI velocity 20-80

   To turn it into a \"just give me the next value\" generator instead
   of tracking t yourself, wrap it: (let [t (atom 0.0) curve (smooth-noise 8)]
   (fn [] (let [v (curve @t)] (swap! t + 0.1) v)))."
  {:doc/format :fn}
  ([n] (smooth-noise n 0.0 1.0))
  ([n lo hi]
   {:pre [(pos? n)]}
   (let [lattice (vec (repeatedly n #(dist/rand-uniform lo hi)))]
     (if (= n 1)
       (constantly (first lattice))
       (let [max-t (double (dec n))]
         (fn [t]
           (let [t     (-> (double t) (max 0.0) (min max-t))
                 i0    (min (long t) (- n 2))
                 frac  (- t i0)
                 fade  (* frac frac frac (+ (* frac (- (* frac 6) 15)) 10))
                 a     (nth lattice i0)
                 b     (nth lattice (inc i0))]
             (+ a (* fade (- b a))))))))))

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

  ;; Smooth noise - a gradually drifting filter cutoff over an 8-beat phrase
  (def cutoff-curve (smooth-noise 8 200 4000))
  (map cutoff-curve (range 0 8 0.25)) ;; sampled every 16th note

  ;; Linear distribution - dynamics that skew loud, rising density toward ff
  (repeatedly 10 #(rand-linear 40 100))
  ;; => values increasingly likely as they approach 100

  ;; Arcsine distribution - notes cluster at the register's extremes
  (repeatedly 10 #(rand-arcsine 48 84))
  ;; => values pile up near 48 and 84, sparse in the middle

  ;; Poisson-process events - a sparse, Xenakis-style cloud of onsets
  (poisson-events 4 8) ;; ~4 events/beat expected, across an 8-beat phrase
  )
