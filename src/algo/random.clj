;; random.clj
;; The higher-level random-function library for this project --
;; continuous distributions, discrete/collection helpers, shaped
;; distributions, walks/composite generators, and event/rhythm
;; generators, all built on algo.random.core's basic primitives
;; (rand-double, rand-int, choose, weighted-choose, shuffle) rather
;; than drawing from the RNG directly -- this namespace has no access
;; to (and no need for) algo.random.core's own private pure core.

(ns algo.random
  (:refer-clojure :exclude [rand-int shuffle])
  (:require [algo.random.core :refer [rand-double rand-int choose weighted-choose shuffle]]))

;; ------------------------------------------------------------
;; CONTINUOUS DISTRIBUTIONS
;; ------------------------------------------------------------

(defn uniform
  "Uniform random sample from the interval (a, b)."
  [a b]
  (+ a (* (rand-double) (- b a))))

(defn normal
  "Random sample from a normal (Gaussian) distribution, via Box-Muller."
  [mean stdev]
  {:pre [(pos? stdev)]}
  (let [u1    (rand-double)
        u2    (rand-double)
        r     (Math/sqrt (* -2.0 (Math/log u1)))
        theta (* 2.0 Math/PI u2)]
    (+ mean (* stdev r (Math/sin theta)))))

(defn exponential
  "Random sample from an exponential distribution with the given mean
   (not rate -- rate = 1/mean; poisson-events passes 1/rate here for
   exactly that reason). Models the gap between independent events
   happening at a constant average rate: mostly short gaps, occasionally
   a long one, never negative. mean must be positive."
  [mean]
  {:pre [(pos? mean)]}
  (* (- mean) (Math/log (rand-double))))

(defn gamma
  "Marsaglia & Tsang, 'A Simple Method for Generating Gamma Variables',
   ACM Transactions on Mathematical Software 26:3 (2000), 363-372."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (if (>= shape 1.0)
    (let [d (- shape (/ 1.0 3.0))
          c (/ 1.0 (Math/sqrt (* 9.0 d)))]
      (loop []
        (let [x   (loop [x (normal 0 1)]
                    (if (<= (+ 1.0 (* c x)) 0.0)
                      (recur (normal 0 1))
                      x))
              v0  (+ 1.0 (* c x))
              v   (* v0 v0 v0)
              u   (rand-double)
              xsq (* x x)]
          (if (or (< u (- 1.0 (* 0.0331 xsq xsq)))
                  (< (Math/log u) (+ (* 0.5 xsq) (* d (+ (- 1.0 v) (Math/log v))))))
            (* scale d v)
            (recur)))))
    (let [g (gamma (+ shape 1.0) 1.0)
          w (rand-double)]
      (* scale g (Math/pow w (/ 1.0 shape))))))

(defn chi-square
  "Chi-square distribution with dof degrees of freedom -- a special case
   of gamma (shape = dof/2, scale = 2). Always positive, skewed
   right, with the spread growing as dof grows. Mainly a building block
   here (student-t is defined in terms of it) rather than something
   reached for directly, but usable on its own for a value that should
   skew toward small/positive with a long right tail. dof must be
   positive."
  [dof]
  {:pre [(pos? dof)]}
  (gamma (* 0.5 dof) 2.0))

(defn inverse-gamma
  "If X is gamma(shape, scale) then 1/X is inverse-gamma(shape, 1/scale)."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (/ 1.0 (gamma shape (/ 1.0 scale))))

(defn weibull
  "Weibull distribution -- a generalized exponential where shape
   controls the skew: shape = 1 reduces to exactly exponential's
   own shape (front-loaded, most mass near zero); shape > 1 pulls the
   mode away from zero into a soft peak (values cluster around a
   typical size but occasionally run long -- note/rest durations, say);
   shape < 1 front-loads even harder than the exponential case, mostly
   very small values with a long thin tail of rare large ones. scale
   stretches the whole distribution. Both args must be positive."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (* scale (Math/pow (- (Math/log (rand-double))) (/ 1.0 shape))))

(defn cauchy
  "Cauchy distribution centered at median, spread by scale -- most
   values cluster near median the way a normal distribution's peak
   does, but the tails are so heavy the distribution has no finite
   mean or variance: occasional extreme outliers, arbitrarily far from
   median, are a real and expected part of its output, not a rare edge
   case the way normal's own thin tail makes them. Useful for an
   otherwise-centered value (a pitch, a panning position) that should
   occasionally leap wildly rather than taper off smoothly. scale must
   be positive."
  [median scale]
  {:pre [(pos? scale)]}
  (+ median (* scale (Math/tan (* Math/PI (- (rand-double) 0.5))))))

(defn student-t
  "Knuth, Seminumerical Algorithms."
  [dof]
  {:pre [(pos? dof)]}
  (let [y1 (normal 0 1)
        y2 (chi-square dof)]
    (/ y1 (Math/sqrt (/ y2 dof)))))

(defn laplace
  "The Laplace distribution, also known as the double exponential
   distribution."
  [mean scale]
  {:pre [(pos? scale)]}
  (let [u (rand-double)]
    (if (< u 0.5)
      (+ mean (* scale (Math/log (* 2.0 u))))
      (- mean (* scale (Math/log (* 2.0 (- 1.0 u))))))))

(defn log-normal
  "Log-normal distribution: exp of a normal(mu, sigma) sample, so
   the result is always positive and right-skewed (a long tail toward
   large values, most mass bunched toward zero) -- useful for a
   duration/amplitude-like value that can't go negative and shouldn't
   have normal's own symmetric tail. mu/sigma are the UNDERLYING
   normal distribution's mean/stdev, not the log-normal output's own
   (which are different, and less intuitive to reason about directly)."
  [mu sigma]
  (Math/exp (normal mu sigma)))

(defn beta
  "Beta distribution on (0, 1) -- shaped by a and b: a = b = 1 is
   uniform, larger equal a/b peaks in the middle (values cluster around
   0.5), a > b skews toward 1, a < b skews toward 0, and either shape
   parameter dropping below 1 pulls extra mass toward its own end (0
   for a < 1, 1 for b < 1). Useful directly as a controllable-shape
   random value already in 0-1 range -- a note's own probability of
   firing (weighted-coin's input), a normalized velocity/panning value
   -- without clamping an unbounded distribution into range afterward.
   a/b must be positive."
  [a b]
  {:pre [(pos? a) (pos? b)]}
  (let [u (gamma a 1.0)
        v (gamma b 1.0)]
    (/ u (+ u v))))

;; ------------------------------------------------------------
;; DISCRETE/COLLECTION HELPERS
;; ------------------------------------------------------------

(defn choose-n
  "Choose n random elements from coll, without replacement."
  [n coll]
  (vec (take n (shuffle coll))))

(defn deep-shuffle
  "Shuffle coll's own top-level order, then recurse into every element
   that's itself a sequential collection and shuffle IT too, down to
   depth levels total -- depth omitted (or nil) means every level, all
   the way down to the leaves. A leaf (anything sequential? says no to
   -- a number, a string, a domain Leaf record, ...) is left as-is;
   nothing to shuffle once you're not looking at a collection anymore.

   depth 1 shuffles only coll's own order and stops there -- every
   nested collection inside it keeps its original order untouched, not
   just its own top level but everything inside it too. depth 0 (or a
   negative depth) is a no-op, returning coll unchanged.

   For \"a seq of seqs of leafs\":
   (deep-shuffle [[1 2] [3 4 5] [6]])    ;; every level shuffled
   (deep-shuffle [[1 2] [3 4 5] [6]] 1)  ;; only which seq comes first/
                                         ;; second/third changes -- each
                                         ;; seq's own [1 2]/[3 4 5]/[6]
                                         ;; order is untouched"
  ([coll] (deep-shuffle coll nil))
  ([coll depth]
   (if (and depth (<= depth 0))
     (vec coll)
     (let [shuffled (vec (shuffle coll))]
       (if (and depth (<= depth 1))
         shuffled
         (mapv (fn [x] (if (sequential? x) (deep-shuffle x (when depth (dec depth))) x))
               shuffled))))))

(defn chosen-from
  "Return a vector of (count coll) random elements from coll, with
   replacement."
  [coll]
  (let [v (vec coll)
        n (count v)]
    (vec (repeatedly n #(choose v)))))

(defn weighted-coin
  "Returns true or false; probability of true is n, clamped to [0, 1]."
  [n]
  (let [n (cond (< n 0.0) 0.0 (> n 1.0) 1.0 :else n)]
    (< (rand-double) n)))

(defn only
  "Take only the specified notes from the given phrase."
  ([phrase notes] (only phrase notes []))
  ([phrase notes result]
   (if notes
     (recur phrase
            (next notes)
            (conj result (get phrase (first notes))))
     result)))

(defn sputter
  "Returns a list where some elements may have been repeated.

   Repetition is based on probability (defaulting to 0.25), therefore,
   for each element in the original list, there's a chance that it will
   be repeated. (The repetitions themselves are also subject to further
   repetition). The size of the resulting list can be constrained to max
   elements (defaulting to 100).

  (sputter [1 2 3 4])        ;=> [1 1 2 3 3 4]
  (sputter [1 2 3 4] 0.7 5)  ;=> [1 1 1 2 3]
  (sputter [1 2 3 4] 0.8 10) ;=> [1 2 2 2 2 2 2 2 3 3]
  (sputter [1 2 3 4] 1 10)   ;=> [1 1 1 1 1 1 1 1 1 1]
  "
  ([lst]          (sputter lst 0.25))
  ([lst prob]     (sputter lst prob 100))
  ([lst prob max] (sputter lst prob max []))
  ([[head & tail] prob max result]
   (if (and head (< (count result) max))
     (if (< (rand-double) prob)
       (recur (cons head tail) prob max (conj result head))
       (recur tail prob max (conj result head)))
     result)))

;; ------------------------------------------------------------
;; SHAPED/SKEWED DISTRIBUTIONS
;; ------------------------------------------------------------

(defn triangular
  "Triangular distribution with peak at `mode`.
   Values cluster around mode, fewer at extremes.
   Great for natural note durations or velocities."
  [lo hi mode]
  (let [u (rand-double)
        f (/ (- mode lo) (- hi lo))]
    (if (< u f)
      (+ lo (Math/sqrt (* u (- hi lo) (- mode lo))))
      (- hi (Math/sqrt (* (- 1 u) (- hi lo) (- hi mode)))))))

(defn linear
  "Linear-density distribution over [lo, hi]: probability density ramps
   monotonically from one bound to the other, rather than peaking at a
   single mode (triangular) -- Xenakis's own \"linear distribution,\"
   used alongside uniform/exponential/Cauchy in his own stochastic
   pieces (see Roads, The Computer Music Tutorial). rising? true
   (the default) means density increases toward hi -- values near hi
   are more likely; false means density increases toward lo instead."
  ([lo hi] (linear lo hi true))
  ([lo hi rising?]
   (let [u (rand-double)]
     (if rising?
       (+ lo (* (- hi lo) (Math/sqrt u)))
       (- hi (* (- hi lo) (Math/sqrt u)))))))

(defn arcsine
  "Arcsine distribution over [lo, hi]: density is HIGHEST at the two
   extremes and lowest in the middle -- the mirror image of
   triangular's peaked-at-the-mode shape, not just \"triangular
   flipped\" but a real, separately-named distribution, from the same
   Xenakis-derived toolkit linear comes from."
  [lo hi]
  (let [s (Math/sin (* Math/PI (/ (rand-double) 2)))]
    (+ lo (* (- hi lo) s s))))

(defn lo-emph
  "Triangular distribution peaked at low end of [lo,hi] -- shorthand for
   (triangular lo hi lo)."
  [lo hi]
  (triangular lo hi lo))

(defn mean-emph
  "Symmetric triangular distribution peaked at midpoint -- shorthand for
   (triangular lo hi (/ (+ lo hi) 2))."
  [lo hi]
  (triangular lo hi (/ (+ lo hi) 2)))

(defn hi-emph
  "Triangular distribution peaked at high end of [lo,hi] -- shorthand for
   (triangular lo hi hi)."
  [lo hi]
  (triangular lo hi hi))

;; ------------------------------------------------------------
;; WALKS & COMPOSITE GENERATORS
;; ------------------------------------------------------------

(defn int-range
  "Returns random integer between lo (inclusive) and hi (exclusive)"
  [lo hi]
  (+ lo (rand-int (- hi lo))))

(defn cyclic-random
  "Returns a function that yields random items from coll, reshuffling
   after exhausting all items. Perfect for arpeggios or drum fills."
  [coll]
  (let [state (atom {:pool (shuffle coll) :idx 0})]
    (fn []
      (let [{:keys [pool idx]} @state]
        (when (= idx (count pool))
          (swap! state assoc :pool (shuffle coll) :idx 0))
        (let [item (get pool idx)]
          (swap! state update :idx inc)
          item)))))

(defn random-walk
  "Returns a function that moves randomly by at most `step-bound` each call.
   Optional clipping keeps values in range. Good for LFOs or gradual changes."
  [start step-bound & {:keys [clip-lo clip-hi]}]
  (let [state (atom start)]
    (fn []
      (let [next (+ @state (uniform (- step-bound) step-bound))]
        (reset! state (cond
                        (and clip-lo clip-hi) (-> next (max clip-lo) (min clip-hi))
                        clip-lo (max next clip-lo)
                        clip-hi (min next clip-hi)
                        :else next))))))

(defn rising
  "Returns random float between lo and hi with upward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors high values.
   Use for crescendos, rising pitch lines, or increasing density."
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (uniform lo hi)
      (+ lo (* (- hi lo) (Math/pow (rand-double) (/ 1 (inc (* b 9)))))))))

(defn falling
  "Returns random float between lo and hi with downward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors low values.
   Use for decrescendos, falling pitch lines, or fading effects."
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (uniform lo hi)
      (- hi (* (- hi lo) (Math/pow (rand-double) (/ 1 (inc (* b 9)))))))))

(defn int-rising
  "Integer version of rising. Returns int between lo and hi-1
   with upward bias. Great for choosing higher pitches more often."
  [lo hi bias]
  (int (Math/floor (rising (double lo) (double hi) bias))))

(defn int-falling
  "Integer version of falling. Returns int between lo and hi-1
   with downward bias. Great for choosing lower pitches more often."
  [lo hi bias]
  (int (Math/floor (falling (double lo) (double hi) bias))))

(defn biased-walk
  "Like random-walk but with directional bias.
   bias > 0.5 trends upward, < 0.5 trends downward.
   Use for melodic lines with intentional contour."
  [start step-bound bias & {:keys [clip-lo clip-hi]}]
  (let [state (atom start)]
    (fn []
      (let [dir (if (< (rand-double) bias) 1 -1)
            step (* dir (uniform 0 step-bound))
            next (+ @state step)]
        (reset! state (cond
                        (and clip-lo clip-hi) (-> next (max clip-lo) (min clip-hi))
                        clip-lo (max next clip-lo)
                        clip-hi (min next clip-hi)
                        :else next))))))

(defn smooth-walk
  "Returns a function that moves toward a target each call with inertia.
   inertia=0 → snaps to target, inertia=1 → ignores target.
   Perfect for portamento or filter envelope following."
  [initial inertia step]
  (let [state (atom initial)]
    (fn [target]
      (let [current @state
            next-val (+ current (* inertia (- target current))
                        (uniform (- step) step))]
        (reset! state next-val)
        next-val))))

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
  ([n] (smooth-noise n 0.0 1.0))
  ([n lo hi]
   {:pre [(pos? n)]}
   (let [lattice (vec (repeatedly n #(uniform lo hi)))]
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

;; ------------------------------------------------------------
;; EVENT/RHYTHM GENERATORS
;; ------------------------------------------------------------

(defn random-rhythm
  "Generates a sequence of event times within num-beats.
   Each beat has density% chance of containing an event.
   Example: (random-rhythm 0.25 16 0.3) → sparse 16th-note pattern"
  [beat-duration num-beats density]
  (let [events (map #(when (weighted-coin density) (* beat-duration %))
                     (range num-beats))]
    (filter some? events)))

(defn poisson-events
  "Event onset times within [0, duration), Poisson-process style: keeps
   drawing inter-arrival gaps from an exponential distribution with the
   given rate (expected events per unit duration -- the same rate
   parameter Xenakis used to control event density in Achorripsis, via
   exponential's own mean = 1/rate) and accumulating them, until
   the running total would exceed duration -- genuine stochastic
   clustering/spacing, rather than random-rhythm's per-tick coin-flip
   approximation of the same idea.

   (poisson-events 4 8) → a handful of onsets across an 8-beat phrase,
   averaging 4 per beat"
  [rate duration]
  (loop [t 0.0 events []]
    (let [gap (exponential (/ 1.0 rate))
          t'  (+ t gap)]
      (if (< t' duration)
        (recur t' (conj events t'))
        events))))

;; ------------------------------------------------------------
;; MARKOV CHAIN + GENERATIVE PATCH
;; ------------------------------------------------------------

(defn markov-chain
  "Returns a function that walks through states using transition weights.
   transitions: {state {next-state weight, ...}, ...}
   Example: (markov-chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)"
  [transitions start-state]
  (let [state (atom start-state)]
    (fn []
      (let [next (weighted-choose (get transitions @state))]
        (reset! state next)
        next))))

(defn generative-patch
  "Returns a function that generates musical events with rising/falling tendencies."
  []
  (let [pitch-cycler (cyclic-random (range 60 72))
        pitch-bias (rising 0 1 0.7) ;; 70% upward bias per step
        velocity-walk (biased-walk 80 15 0.4 :clip-lo 30 :clip-hi 127) ;; slight down bias
        rhythm-trigger #(weighted-coin 0.3)
        duration-fn #(falling 0.1 0.5 0.6)] ;; shorter durations favored

    (fn []
      (when (rhythm-trigger)
        {:pitch (pitch-cycler)
         :velocity (velocity-walk)
         :duration (duration-fn)
         :bend (* 2 (rising -1 1 0.6))}))))
