;; The random-function library for this project: basic primitives
;; (rand-double, rand-int, choose, weighted-choose, shuffle, markov),
;; continuous distributions, discrete/collection helpers and shaped
;; distributions (walks are in numeric/walk.clj, the Markov chain in
;; numeric/markov.clj). The basic primitives are thin wraps of
;; musics.algo.numeric.rng's pure rnd-*/step!/default-rng; everything else in
;; this file is built on those primitives as ordinary public calls,
;; not on musics.algo.numeric.rng directly.

(ns musics.algo.numeric.random
  (:refer-clojure :exclude [rand-int shuffle])
  (:require [musics.algo.numeric.rng :refer [rnd-double rnd-int rnd-choose rnd-weighted rnd-markov rnd-shuffle step! default-rng]]
            [musics.algo.numeric.spec :refer [num-spec pos-spec unit-spec lo hi bias shape scale-p dof sampler]]))


;; ------------------------------------------------------------
;; BASIC PRIMITIVES
;; ------------------------------------------------------------

(defn rand-double
  "Uniform double in [0,1), drawn from an RNG atom (default-rng if omitted)."
  ([] (rand-double default-rng))
  ([rng-atom] (step! rng-atom rnd-double)))


;; rand-int takes [lo hi], not clojure.core's [n], like every int-* function; it is built on the exact-
;; integer rnd-int machinery of numeric.rng.
(defn rand-int
  "Uniform integer in [lo,hi), drawn from an RNG atom (default-rng if omitted)."
  ([lo hi] (rand-int default-rng lo hi))
  ([rng-atom lo hi] (+ lo (step! rng-atom rnd-int (- hi lo)))))


(defn choose
  "Choose a random element from coll, drawn from an RNG atom (default-rng if omitted)."
  ([coll] (choose default-rng coll))
  ([rng-atom coll] (step! rng-atom rnd-choose (vec coll))))


(defn weighted-choose
  "An element of vals chosen with probability proportional to its weight in weights; weights need not sum to 1 (raw Markov counts work). Also takes one map of val -> weight. Drawn from an RNG atom (default-rng if omitted).
     (weighted-choose [1 2 3 4] [3 2 1 1])
     (weighted-choose {1 3, 2 2, 3 1, 4 1})"
  {:mu {:arity (fn [stack] (if (map? (peek stack)) 1 2))}}
  ([val-weight-map] (weighted-choose (keys val-weight-map) (vals val-weight-map)))
  ([vals weights] (weighted-choose default-rng vals weights))
  ([rng-atom vals weights] (step! rng-atom rnd-weighted (vec vals) (vec weights))))


(defn shuffle
  "Shuffle coll (Fisher-Yates), drawn from an RNG atom (default-rng if omitted)."
  ([coll] (shuffle default-rng coll))
  ([rng-atom coll] (step! rng-atom rnd-shuffle coll)))


(defn markov
  "Markov transition: table is {state {next-state prob}}. Single-step --
   caller tracks its own current state across calls (contrast
   markov-chain, below, which tracks state for you). Drawn from an RNG
   atom (default-rng if omitted)."
  ([table state] (markov default-rng table state))
  ([rng-atom table state] (step! rng-atom rnd-markov table state)))


;; ------------------------------------------------------------
;; CONTINUOUS DISTRIBUTIONS
;; ------------------------------------------------------------

(defn uniform
  "Uniform random sample from the interval (a, b)."
  {:algo (sampler {:a lo :b hi})}
  [a b]
  (+ a (* (rand-double) (- b a))))


(defn normal
  "Random sample from a normal (Gaussian) distribution, via Box-Muller."
  {:algo (sampler {:mean (assoc num-spec :default 0.0 :doc "centre") :stdev (assoc pos-spec :default 1.0 :doc "spread")})}
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
  {:algo (sampler {:mean (assoc pos-spec :default 1.0 :doc "mean")})}
  [mean]
  {:pre [(pos? mean)]}
  (* (- mean) (Math/log (rand-double))))


(defn gamma
  "Marsaglia & Tsang, 'A Simple Method for Generating Gamma Variables',
   ACM Transactions on Mathematical Software 26:3 (2000), 363-372."
  {:algo (sampler {:shape shape :scale scale-p})}
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
  "Chi-square distribution with dof degrees of freedom, a gamma with shape dof/2 and scale 2: positive, skewed right, the spread growing with dof. student-t is built on it. dof must be positive."
  {:algo (sampler {:dof dof})}
  [dof]
  {:pre [(pos? dof)]}
  (gamma (* 0.5 dof) 2.0))


(defn inverse-gamma
  "If X is gamma(shape, scale) then 1/X is inverse-gamma(shape, 1/scale)."
  {:algo (sampler {:shape (assoc shape :default 3.0) :scale scale-p})}
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (/ 1.0 (gamma shape (/ 1.0 scale))))


(defn weibull
  "Weibull distribution: a generalised exponential whose shape sets the skew. shape 1 is the exponential (most mass near zero); above 1 the mode leaves zero for a soft peak (note or rest durations, say); below 1 it is more front-loaded still, with a long thin tail. scale stretches it. Both must be positive."
  {:algo (sampler {:shape (assoc shape :default 1.5) :scale scale-p})}
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (* scale (Math/pow (- (Math/log (rand-double))) (/ 1.0 shape))))


(defn cauchy
  "Cauchy distribution centred at median, spread by scale: values cluster near median like a normal's peak, but the tails are so heavy that outliers far from median are a normal part of the output. For a centred value (a pitch, a pan position) that should now and then leap wildly. scale must be positive."
  {:algo (sampler {:median (assoc num-spec :default 0.0 :doc "centre") :scale scale-p})}
  [median scale]
  {:pre [(pos? scale)]}
  (+ median (* scale (Math/tan (* Math/PI (- (rand-double) 0.5))))))


(defn student-t
  "Knuth, Seminumerical Algorithms."
  {:algo (sampler {:dof (assoc dof :default 5)})}
  [dof]
  {:pre [(pos? dof)]}
  (let [y1 (normal 0 1)
        y2 (chi-square dof)]
    (/ y1 (Math/sqrt (/ y2 dof)))))


(defn laplace
  "The Laplace distribution, also known as the double exponential
   distribution."
  {:algo (sampler {:mean (assoc num-spec :default 0.0 :doc "centre") :scale scale-p})}
  [mean scale]
  {:pre [(pos? scale)]}
  (let [u (rand-double)]
    (if (< u 0.5)
      (+ mean (* scale (Math/log (* 2.0 u))))
      (- mean (* scale (Math/log (* 2.0 (- 1.0 u))))))))


(defn log-normal
  "Log-normal distribution: exp of a normal(mu, sigma) draw, so always positive and skewed right. For duration- or amplitude-like values. mu and sigma are the underlying normal's mean and standard deviation, not the output's."
  {:algo (sampler {:mu (assoc num-spec :default 0.0 :doc "mean of the log") :sigma (assoc pos-spec :default 0.5 :doc "spread of the log")})}
  [mu sigma]
  (Math/exp (normal mu sigma)))


(defn beta
  "Beta distribution on (0, 1), shaped by a and b: a = b = 1 is uniform, larger equal values peak at 0.5, a > b skews towards 1, a < b towards 0, and a shape below 1 pulls extra mass to its own end. A controllable random value already in 0-1 (a probability for weighted-coin, a velocity, a pan). a and b must be positive."
  {:algo (sampler {:a (assoc pos-spec :min 0.01 :default 2.0 :doc "pull toward 1") :b (assoc pos-spec :min 0.01 :default 2.0 :doc "pull toward 0")})}
  [a b]
  {:pre [(pos? a) (pos? b)]}
  (let [u (gamma a 1.0)
        v (gamma b 1.0)]
    (/ u (+ u v))))


;; ------------------------------------------------------------
;; DISCRETE/COLLECTION HELPERS
;; ------------------------------------------------------------

(defn choose-n
  "Choose how-many random elements from coll, without replacement."
  {:algo {:in [:any] :out :same :children [:coll]
          :params {:how-many {:type :int :min 0 :max 256 :default 4 :doc "elements drawn"}}}}
  [how-many coll]
  (vec (take how-many (shuffle coll))))


(defn deep-shuffle
  "Shuffle coll's order, then each element that is itself sequential, down to depth levels (omitted or nil: every level). Anything not sequential (a number, a string, a Leaf) stays as it is. depth 1 shuffles only coll; depth 0 or negative returns coll unchanged.
     (deep-shuffle [[1 2] [3 4 5] [6]])    ;; every level shuffled
     (deep-shuffle [[1 2] [3 4 5] [6]] 1)  ;; only the order of the three seqs changes"
  {:algo {:in [:any] :out :same :arity 2
          :params {:depth {:type :int :min 0 :max 16 :default 16 :doc "levels shuffled"}}}}
  ([coll] (deep-shuffle coll nil))
  ([coll depth]
   (if (and depth (<= depth 0))
     (vec coll)
     (let [shuffled (vec (shuffle coll))]
       (if (and depth (<= depth 1))
         shuffled
         (mapv (fn [x] (if (sequential? x) (deep-shuffle x (when depth (dec depth))) x))
               shuffled))))))


(defn choose-from
  "Return a vector of (count coll) random elements from coll, with
   replacement."
  {:algo {:in [:any] :out :same}}
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
  {:algo {:in [:any] :out :same :arity 2
          :params {:notes {:type :vector :default [0 2 4] :doc "indexes kept, in this order"}}}}
  ([phrase notes] (only phrase notes []))
  ([phrase notes result]
   (if notes
     (recur phrase
            (next notes)
            (conj result (get phrase (first notes))))
     result)))


(defn sputter
  "A list where some elements are repeated: each element, with probability p (default 0.25), repeats, and repetitions may repeat in turn. The result has at most max-length elements (default 100).
     (sputter [1 2 3 4])        ;=> [1 1 2 3 3 4]
     (sputter [1 2 3 4] 0.7 5)  ;=> [1 1 1 2 3]
     (sputter [1 2 3 4] 0.8 10) ;=> [1 2 2 2 2 2 2 2 3 3]
     (sputter [1 2 3 4] 1 10)   ;=> [1 1 1 1 1 1 1 1 1 1]"
  {:algo {:in [:any] :out :same :arity 3
          :params {:prob (assoc unit-spec :default 0.25 :doc "chance of a repeat")
                   :max-length {:type :int :min 1 :max 1024 :default 100 :doc "longest result"}}}}
  ([lst]          (sputter lst 0.25))
  ([lst prob]     (sputter lst prob 100))
  ([lst prob max-length] (sputter lst prob max-length []))
  ([[head & tail] prob max-length result]
   (if (and head (< (count result) max-length))
     (if (< (rand-double) prob)
       (recur (cons head tail) prob max-length (conj result head))
       (recur tail prob max-length (conj result head)))
     result)))


;; ------------------------------------------------------------
;; SHAPED/SKEWED DISTRIBUTIONS
;; ------------------------------------------------------------

(defn triangular
  "Triangular distribution with peak at `mode`.
   Values cluster around mode, fewer at extremes.
   Great for natural note durations or velocities."
  {:algo (sampler {:lo lo :hi hi :mode (assoc num-spec :default 0.5 :doc "peak")})}
  [lo hi mode]
  (let [u (rand-double)
        f (/ (- mode lo) (- hi lo))]
    (if (< u f)
      (+ lo (Math/sqrt (* u (- hi lo) (- mode lo))))
      (- hi (Math/sqrt (* (- 1 u) (- hi lo) (- hi mode)))))))


(defn linear
  "Linear-density distribution over [lo, hi] (Xenakis): the density ramps from one bound to the other instead of peaking at a mode. rising? true (default): density grows towards hi; false: towards lo."
  {:algo (assoc (sampler {:lo lo :hi hi :rising? {:type :bool :default true :doc "density ramps up"}}) :arity 3)}
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
  {:algo (sampler {:lo lo :hi hi})}
  [lo hi]
  (let [s (Math/sin (* Math/PI (/ (rand-double) 2)))]
    (+ lo (* (- hi lo) s s))))


(defn lo-emph
  "Triangular distribution peaked at low end of [lo,hi] -- shorthand for
   (triangular lo hi lo)."
  {:algo (sampler {:lo lo :hi hi})}
  [lo hi]
  (triangular lo hi lo))


(defn mean-emph
  "Symmetric triangular distribution peaked at midpoint -- shorthand for
   (triangular lo hi (/ (+ lo hi) 2))."
  {:algo (sampler {:lo lo :hi hi})}
  [lo hi]
  (triangular lo hi (/ (+ lo hi) 2)))


(defn hi-emph
  "Triangular distribution peaked at high end of [lo,hi] -- shorthand for
   (triangular lo hi hi)."
  {:algo (sampler {:lo lo :hi hi})}
  [lo hi]
  (triangular lo hi hi))


;; -- Integer counterparts: the floor of the float draw, plain functions. The
;; algos for integer pitches are pitch/<shape> (the shape, then a bridge).

(defn int-triangular
  "Integer version of triangular. Returns int between lo and hi-1
   peaked at mode."
  [lo hi mode]
  (int (Math/floor (triangular (double lo) (double hi) (double mode)))))


(defn int-linear
  "Integer version of linear. Returns int between lo and hi-1."
  ([lo hi] (int-linear lo hi true))
  ([lo hi rising?]
   (int (Math/floor (linear (double lo) (double hi) rising?)))))


(defn int-arcsine
  "Integer version of arcsine. Returns int between lo and hi-1."
  [lo hi]
  (int (Math/floor (arcsine (double lo) (double hi)))))


(defn int-lo-emph
  "Integer version of lo-emph -- shorthand for (int-triangular lo hi lo)."
  [lo hi]
  (int-triangular lo hi lo))


(defn int-mean-emph
  "Integer version of mean-emph -- shorthand for
   (int-triangular lo hi (/ (+ lo hi) 2))."
  [lo hi]
  (int-triangular lo hi (/ (+ lo hi) 2)))


(defn int-hi-emph
  "Integer version of hi-emph -- shorthand for (int-triangular lo hi hi)."
  [lo hi]
  (int-triangular lo hi hi))


;; ------------------------------------------------------------
;; WALKS & COMPOSITE GENERATORS
;; ------------------------------------------------------------

(defn int-range
  "Returns random integer between lo (inclusive) and hi (exclusive)"
  [lo hi]
  (int (Math/floor (uniform lo hi))))


(defn rising
  "Returns random float between lo and hi with upward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors high values.
   Use for crescendos, rising pitch seqs, or increasing density."
  {:algo (sampler {:lo lo :hi hi :bias bias})}
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (uniform lo hi)
      (+ lo (* (- hi lo) (Math/pow (rand-double) (/ 1 (inc (* b 9)))))))))


(defn falling
  "Returns random float between lo and hi with downward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors low values.
   Use for decrescendos, falling pitch seqs, or fading effects."
  {:algo (sampler {:lo lo :hi hi :bias bias})}
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
