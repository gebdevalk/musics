;; random.clj
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
            [musics.common.music-data :refer [quantity]]
            [musics.algo.numeric.spec :refer [num-spec pos-spec unit-spec lo hi ilo ihi bias shape scale-p dof sampler]]))


;; ------------------------------------------------------------
;; BASIC PRIMITIVES
;; ------------------------------------------------------------

(defn rand-double
  "Uniform double in [0,1), drawn from an RNG atom (default-rng if omitted)."
  ([] (rand-double default-rng))
  ([rng-atom] (step! rng-atom rnd-double)))


;; Signature changed 2026-09-15, [n] (meaning [0,n)) -> [lo hi] -- was
;; the one remaining inconsistency with int-range/every other int-*
;; sibling's own [lo hi] shape (see musics.algo.dimensions' own docstring,
;; 'EXTENDED 2026-09-15', for the fuller history and the tradeoff this
;; accepts: diverging from clojure.core/rand-int's own [n] convention,
;; despite this fn's name and :refer-clojure exclusion both mirroring
;; it). Still built on the exact-integer rnd-int/step! machinery below
;; (mod-based, no floating-point rounding) -- only the external shape
;; changed, not the underlying seeded-RNG mechanism, and every OTHER
;; caller of this same rnd-int primitive (choose/weighted-choose/
;; shuffle/markov, all via musics.algo.numeric.rng) is untouched by this.
(defn rand-int
  "Uniform integer in [lo,hi), drawn from an RNG atom (default-rng if omitted)."
  ([lo hi] (rand-int default-rng lo hi))
  ([rng-atom lo hi] (+ lo (step! rng-atom rnd-int (- hi lo)))))


(defn choose
  "Choose a random element from coll, drawn from an RNG atom (default-rng if omitted)."
  ([coll] (choose default-rng coll))
  ([rng-atom coll] (step! rng-atom rnd-choose (vec coll))))


(defn weighted-choose
  "Returns an element from vals with probability proportional to its
   corresponding weight in weights. Weights need not sum to 1 -- they're
   normalized against their own total, so raw/unnormalized weights (e.g.
   Markov transition counts) work directly. It's also possible to pass a
   single map of val -> weight as a param. Drawn from an RNG atom
   (default-rng if omitted).

   (weighted-choose [1 2 3 4] [3 2 1 1])
   (weighted-choose {1 3, 2 2, 3 1, 4 1})"
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
  "Chi-square distribution with dof degrees of freedom -- a special case
   of gamma (shape = dof/2, scale = 2). Always positive, skewed
   right, with the spread growing as dof grows. Mainly a building block
   here (student-t is defined in terms of it) rather than something
   reached for directly, but usable on its own for a value that should
   skew toward small/positive with a long right tail. dof must be
   positive."
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
  "Weibull distribution -- a generalized exponential where shape
   controls the skew: shape = 1 reduces to exactly exponential's
   own shape (front-loaded, most mass near zero); shape > 1 pulls the
   mode away from zero into a soft peak (values cluster around a
   typical size but occasionally run long -- note/rest durations, say);
   shape < 1 front-loads even harder than the exponential case, mostly
   very small values with a long thin tail of rare large ones. scale
   stretches the whole distribution. Both args must be positive."
  {:algo (sampler {:shape (assoc shape :default 1.5) :scale scale-p})}
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
  "Log-normal distribution: exp of a normal(mu, sigma) sample, so
   the result is always positive and right-skewed (a long tail toward
   large values, most mass bunched toward zero) -- useful for a
   duration/amplitude-like value that can't go negative and shouldn't
   have normal's own symmetric tail. mu/sigma are the UNDERLYING
   normal distribution's mean/stdev, not the log-normal output's own
   (which are different, and less intuitive to reason about directly)."
  {:algo (sampler {:mu (assoc num-spec :default 0.0 :doc "mean of the log") :sigma (assoc pos-spec :default 0.5 :doc "spread of the log")})}
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
  "Choose n random elements from coll, without replacement."
  {:algo {:in [:any] :out :same :children [:coll]
          :params {:n {:type :int :min 0 :max 256 :default 4 :doc "elements drawn"}}}}
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
  {:algo {:in [:any] :out :same :arity 3
          :params {:prob (assoc unit-spec :default 0.25 :doc "chance of a repeat")
                   :max  {:type :int :min 1 :max 1024 :default 100 :doc "longest result"}}}}
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
  {:algo (sampler {:lo lo :hi hi :mode (assoc num-spec :default 0.5 :doc "peak")})}
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


;; -- Integer counterparts, added 2026-09-15 for symmetry with
;; rising/falling's own int-rising/int-falling below: every OTHER
;; bounded-range shaped distribution already had a float-only sibling
;; with no integer counterpart, an asymmetry musics.algo.dimensions' own
;; taxonomy work made visible. Same floor-after-computing pattern
;; int-rising/int-falling already use.

(defn int-triangular
  "Integer version of triangular. Returns int between lo and hi-1
   peaked at mode."
  {:algo (sampler :pitch {:lo ilo :hi ihi :mode (quantity :pitch {:default 66 :doc "peak"})})}
  [lo hi mode]
  (int (Math/floor (triangular (double lo) (double hi) (double mode)))))


(defn int-linear
  "Integer version of linear. Returns int between lo and hi-1."
  {:algo (assoc (sampler :pitch {:lo ilo :hi ihi :rising? {:type :bool :default true :doc "density ramps up"}}) :arity 3)}
  ([lo hi] (int-linear lo hi true))
  ([lo hi rising?]
   (int (Math/floor (linear (double lo) (double hi) rising?)))))


(defn int-arcsine
  "Integer version of arcsine. Returns int between lo and hi-1."
  {:algo (sampler :pitch {:lo ilo :hi ihi})}
  [lo hi]
  (int (Math/floor (arcsine (double lo) (double hi)))))


(defn int-lo-emph
  "Integer version of lo-emph -- shorthand for (int-triangular lo hi lo)."
  {:algo (sampler :pitch {:lo ilo :hi ihi})}
  [lo hi]
  (int-triangular lo hi lo))


(defn int-mean-emph
  "Integer version of mean-emph -- shorthand for
   (int-triangular lo hi (/ (+ lo hi) 2))."
  {:algo (sampler :pitch {:lo ilo :hi ihi})}
  [lo hi]
  (int-triangular lo hi (/ (+ lo hi) 2)))


(defn int-hi-emph
  "Integer version of hi-emph -- shorthand for (int-triangular lo hi hi)."
  {:algo (sampler :pitch {:lo ilo :hi ihi})}
  [lo hi]
  (int-triangular lo hi hi))


;; ------------------------------------------------------------
;; WALKS & COMPOSITE GENERATORS
;; ------------------------------------------------------------

;; Rewritten 2026-09-15 (was (+ lo (rand-int (- hi lo)))): conceptually
;; int-uniform, and now implemented that way -- floor of a uniform
;; float draw over [lo,hi), the exact same pattern every other int-*
;; sibling (int-rising/int-falling/int-triangular/...) already uses.
;; Dropped its own former dependency on rand-int deliberately -- rand-
;; int keeps its own, different [n] (meaning [0,n)) argument shape to
;; stay a drop-in seedable replacement for clojure.core/rand-int, so
;; int-range calling it internally was the one thing standing in the
;; way of giving rand-int a [lo hi] shape to match this fn, if that's
;; still wanted. Docstring deliberately kept ONE LINE, not folding this
;; explanation in -- show_algos_test.clj relies on int-range's own
;; docstring staying short (the same role euclid's docstring
;; used to fill, before it genuinely grew multi-line 2026-09-14).
(defn int-range
  "Returns random integer between lo (inclusive) and hi (exclusive)"
  {:algo (sampler :pitch {:lo ilo :hi ihi})}
  [lo hi]
  (int (Math/floor (uniform lo hi))))


(defn rising
  "Returns random float between lo and hi with upward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors high values.
   Use for crescendos, rising pitch lines, or increasing density."
  {:algo (sampler {:lo lo :hi hi :bias bias})}
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (uniform lo hi)
      (+ lo (* (- hi lo) (Math/pow (rand-double) (/ 1 (inc (* b 9)))))))))


(defn falling
  "Returns random float between lo and hi with downward bias.
   bias=0.0 → uniform, bias=1.0 → strongly favors low values.
   Use for decrescendos, falling pitch lines, or fading effects."
  {:algo (sampler {:lo lo :hi hi :bias bias})}
  [lo hi bias]
  (let [b (max 0 (min 1 bias))]
    (if (zero? b)
      (uniform lo hi)
      (- hi (* (- hi lo) (Math/pow (rand-double) (/ 1 (inc (* b 9)))))))))


(defn int-rising
  "Integer version of rising. Returns int between lo and hi-1
   with upward bias. Great for choosing higher pitches more often."
  {:algo (sampler :pitch {:lo ilo :hi ihi :bias bias})}
  [lo hi bias]
  (int (Math/floor (rising (double lo) (double hi) bias))))


(defn int-falling
  "Integer version of falling. Returns int between lo and hi-1
   with downward bias. Great for choosing lower pitches more often."
  {:algo (sampler :pitch {:lo ilo :hi ihi :bias bias})}
  [lo hi bias]
  (int (Math/floor (falling (double lo) (double hi) bias))))
