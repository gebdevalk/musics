(ns algo.toolkit
  "A growing collection of general-purpose, reusable functions meant as
   building blocks for algoline.core -- the 'toolkit' tracked as future
   work in this project's own memory (project_composable_functions_
   exploration.md) once algoline itself existed. Deliberately plain
   Clojure functions, NOT pre-wrapped IStep values: a composer wraps
   whichever of these they need via algoline.core/step, dstep,
   context-step, or model-step at the point of use, the same way any
   other Clojure fn becomes a step -- this namespace has no dependency
   on algoline.core at all, and doesn't need one.

   The random-function section below re-exports algo.random's own
   general-purpose subset (this project's existing, already-tested
   random-function library -- see that ns's own header comment) rather
   than duplicating any of it: every var here is (def name random/name),
   the SAME function object as algo.random/name, not a wrapping closure
   -- so there is exactly one real implementation of each, still
   reachable at algo.random/name too. Deliberately NOT (defn name [&
   args] (apply random/name args)): that style re-resolves random/name
   FRESH on every call (redefining it at the REPL is picked up
   immediately, no reload needed), which a plain def to its CURRENT
   value gives up -- a real, considered tradeoff for this REPL-driven
   project, not an oversight (see algo-matrix.txt for the fuller
   discussion of both options and why this one was chosen anyway: the
   ceremony reduction across ~40 near-identical forwards was judged
   worth it). Only the domain-specific ones were left out -- random-rhythm (takes
   a beat-duration/num-beats, inherently a musical-timing concept) and
   generative-patch (hardcodes :pitch/:velocity/:duration/:bend) -- both
   still directly available via algo.random if ever wanted here later.
   requiring algo.random pulls in only algo.random.core (the pure
   xorshift32 engine) and algo.common.scaling underneath it, both
   themselves leaf namespaces with no further requires -- this doesn't
   pull core.wall, core.async-engine, or anything else project-specific
   in behind it.

   comp, deliberately, only shows up in ONE place (realize-n-cycles,
   below) -- the one spot in this whole namespace that's actually a
   straight-line, two-step pipeline (take N elements, then realize as a
   vector) rather than something else in disguise. Everything else here
   was checked against the same question (see algo-matrix.txt for the
   full survey) and genuinely doesn't fit comp's own shape: cycle-
   shuffle/cycle-weighted-shuffle/cycle-deep-shuffle are self-
   referential recursion (each pass calls itself again on a freshly
   reshuffled input), not a fixed chain of named steps comp can express;
   weighted-shuffle is an accumulating loop over a shrinking pool, not a
   linear value-in/value-out pipeline; and every (def name random/name)
   forward below is a single re-export, not a composition of anything
   -- comp of exactly one function is just that function again, nothing
   to gain by wrapping it.

   The MATH/SPLIT/ISORHYTHM/Z-FILTER sections below re-export
   algo.common's own general-purpose functions, same discipline as the
   algo.random section above (checked, individually, for dependencies --
   see this project's own algo/ dedup audit and Meter/wall docs for what
   each algo.common file actually needs):
     - algo.common.numeric/rotate/scaling/trig/farey have ZERO requires
       of their own -- direct (def name ns-alias/name) forwards, same
       shape as the algo.random section, one real implementation each.
     - algo.common.split ALSO has zero requires (split/split-leafs/
       split-leaf-voice operate on bare [pitch duration] pairs or plain
       maps with :pitches/:duration keys -- no Leaf/Rest/Drum record
       type import needed to use them) -- direct forwards too.
     - algo.common.isorhythm's color-talea/zip-parts and
       algo.common.zfilter's z-filter/smooth/momentum/memory/
       smooth-intervals/interval-gain/pc-smooth are each individually
       dependency-free (confirmed by reading their own bodies -- neither
       touches core.wall/core.domain.flat-domain at all), but their OWN
       source files also define OTHER functions (a wall-fn factory in
       each case) that DO require core.wall/core.domain.flat-domain --
       requiring either namespace directly would pull those in behind
       it. STANDALONE PORTS instead (copied bodies, not requires), same
       precedent weighted-shuffle above already set for
       algo.common.reshape -- toolkit stays free of anything
       project-specific. color-talea/zip-parts still reuse
       algo.common.numeric/lcm via a direct require, since that ns is
       itself a genuine dependency-free leaf.

   The INDISPENSABILITY/METRIC/RHYTHM/SLONIMSKY/COUNTERPOINT sections
   below extend the same re-export discipline out past algo.common,
   into the wider generative algo/ tree -- algo.indisp.indispensability
   and algo.metric.metric have zero requires of their own (direct
   forwards); algo.rhythmic.rhythm requires only clojure.string and
   algo.random (both already fine -- algo.random is already toolkit's
   own first dependency) -- direct forwards too; algo.melodic.slonimsky's
   own mixed-polations/infrapolate/ultrapolate/interpolate are
   dependency-free but their source file also defines a wall-fn factory
   requiring core.wall -- STANDALONE PORTS, same precedent as
   weighted-shuffle/color-talea/the z-filter family; algo.melodic.
   counterpoint requires only algo.random -- a direct forward, renamed
   from its own source's generic 'generate'/'default-rules' to
   species-counterpoint/species-counterpoint-default-rules, since a
   bare 'generate' is too generic a name for this flat namespace.
   A handful of PRE-COMPOSED combinations follow the raw re-exports --
   weighted-pulse-choice/shuffled-euclidean/weighted-density-grid --
   built directly from algo.dimensions/compatible? confirming which
   pairs genuinely combine (see that ns for the full producer/consumer
   matching); see examples.indispensability-algoline for a further,
   STAGED/swappable version of the same idea, built on
   algoline-intercepted.core instead of plain function composition,
   for the case where a composer actually wants to swap one stage
   (e.g. the probability-shaping strategy) independently of the rest."
  (:require [algo.random :as random]
            [algo.common.numeric :as numeric]
            [algo.common.rotate :as rotate-src]
            [algo.common.scaling :as scaling]
            [algo.common.trig :as trig]
            [algo.common.farey :as farey-src]
            [algo.common.split :as split-src]
            [algo.indisp.indispensability :as indisp]
            [algo.metric.metric :as metric]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.melodic.counterpoint :as counterpoint]))

(defn- realize-n-cycles
  "The shared tail every take-cycle-* fn needs: take exactly n cycles'
   worth of elements from an infinite cycle-* lazy seq and realize it as
   a vector. Built with comp because this genuinely IS a two-step,
   straight-line pipeline -- (take k), then vec -- unlike cycle-shuffle/
   weighted-shuffle themselves (see this ns's own docstring for why
   comp doesn't fit those at all)."
  [n v cycle-seq]
  ((comp vec (partial take (* n (count v)))) cycle-seq))

(defn cycle-shuffle
  "Returns a lazy sequence that yields the elements of `v` in order,
   then shuffles `v` (via this project's own seeded algo.random/shuffle,
   not clojure.core/shuffle's unseedable one -- so wrapping a call to
   this in algo.random.core/with-seed makes the whole reshuffled
   sequence reproducible, seed in, same output every run) and repeats
   the process forever.

   v must be non-empty -- confirmed live, not assumed: with an empty
   v, realizing any prefix of the result (even (take 1 ...)) hangs
   forever rather than returning an empty seq. This isn't a performance
   quirk to tolerate; concat/lazy-seq have to keep looking for a first
   real element to know whether the sequence has one at all, and an
   empty v can never produce one, so nothing ever calls a halt. Fails
   immediately and loudly here instead, matching this project's
   consistent 'never hang silently, fail fast and clearly' discipline
   (see e.g. core.async-engine/validate-algo-name!) -- an unbounded
   hang is a worse failure than a thrown exception, not a gentler one."
  [v]
  (when (empty? v)
    (throw (ex-info "cycle-shuffle: v must not be empty -- realizing
any prefix of the result would hang forever, never producing a first
element" {:v v})))
  (lazy-seq
    (concat v (cycle-shuffle (random/shuffle v)))))

(defn take-cycle-shuffle
  "A REALIZED (not lazy-infinite) vector of exactly n full cycles
   through v via cycle-shuffle -- (count v) * n elements: v itself
   unshuffled, then n-1 further reshuffled passes. v's own non-empty
   requirement (cycle-shuffle's own, confirmed-live hang guard) is
   inherited, not re-checked here."
  [n v]
  (realize-n-cycles n v (cycle-shuffle v)))

(defn weighted-shuffle
  "Shuffle coll by repeatedly drawing the next output element from
   whatever's still remaining, at an index sampled from
   algo.random/lo-emph (0, n) -- n the CURRENT remaining count -- rather
   than plain Fisher-Yates. lo-emph is peaked toward the LOW end of
   the remaining pool on every draw, so elements tend to keep close to
   their original relative order: a weaker, order-preserving shuffle,
   not a uniform one.

   A standalone port of algo.common.reshape/weighted-shuffle's own
   algorithm (confirmed there, with a live 20000-trial probe, that this
   index-per-draw construction is what actually makes dist-fn's own
   shape matter -- sorting by an independent draw per element was tried
   first and rejected, since ranks of i.i.d. draws are uniform over
   permutations regardless of their own marginal distribution), fixed
   to lo-emph specifically rather than taking an arbitrary dist-fn --
   NOT a require of algo.common.reshape itself, which depends on
   core.wall; toolkit stays free of anything project-specific."
  [coll]
  (loop [remaining (vec coll) result []]
    (if (empty? remaining)
      result
      (let [n   (count remaining)
            idx (-> (random/lo-emph 0 n) Math/floor long (max 0) (min (dec n)))]
        (recur (into (subvec remaining 0 idx) (subvec remaining (inc idx) n))
               (conj result (nth remaining idx)))))))

(defn cycle-weighted-shuffle
  "Like cycle-shuffle, but reshuffling each pass via weighted-shuffle
   (lo-emph-biased) instead of a uniform algo.random/shuffle -- yields
   v in order, then a weighted-shuffled v, then a weighted-shuffle of
   THAT, forever, lazily. Each successive pass stays weakly biased
   toward its own immediately-preceding order (weighted-shuffle's own
   documented effect), rather than every reshuffle being an independent
   uniform draw the way cycle-shuffle's own passes are.

   v must be non-empty -- same confirmed-live reasoning as cycle-shuffle:
   realizing any prefix of the result for an empty v hangs forever,
   concat/lazy-seq never finding a first element to stop looking for."
  [v]
  (when (empty? v)
    (throw (ex-info "cycle-weighted-shuffle: v must not be empty -- realizing
any prefix of the result would hang forever, never producing a first
element" {:v v})))
  (lazy-seq
    (concat v (cycle-weighted-shuffle (weighted-shuffle v)))))

(defn take-cycle-weighted-shuffle
  "A REALIZED (not lazy-infinite) vector of exactly n full cycles
   through v via cycle-weighted-shuffle -- the lo-emph-weighted
   counterpart to take-cycle-shuffle, same shape otherwise: (count v)
   * n elements, v's own non-empty requirement inherited from
   cycle-weighted-shuffle, not re-checked here."
  [n v]
  (realize-n-cycles n v (cycle-weighted-shuffle v)))

;; ------------------------------------------------------------
;; BASIC PRIMITIVES (algo.random) -- see that ns's own docstrings for
;; the full explanation of each; these are thin forwards, not copies.
;; ------------------------------------------------------------

(def rand-double
  "Uniform double in [0,1). See algo.random/rand-double."
  random/rand-double)

(def rand-int
  "Uniform integer in [0,n). See algo.random/rand-int."
  random/rand-int)

(def choose
  "A random element from coll. See algo.random/choose."
  random/choose)

(def weighted-choose
  "An element with probability proportional to its own weight.
   See algo.random/weighted-choose."
  random/weighted-choose)

(def shuffle
  "Shuffle coll (Fisher-Yates), from this project's own seedable RNG,
   not the JVM's unseedable one. See algo.random/shuffle."
  random/shuffle)

(def markov
  "Single-step Markov transition. See algo.random/markov."
  random/markov)

;; ------------------------------------------------------------
;; CONTINUOUS DISTRIBUTIONS (algo.random)
;; ------------------------------------------------------------

(def uniform
  "Uniform sample from (a, b). See algo.random/uniform."
  random/uniform)

(def normal
  "Normal (Gaussian) sample, via Box-Muller. See algo.random/normal."
  random/normal)

(def exponential
  "Exponential sample with the given mean. See algo.random/exponential."
  random/exponential)

(def gamma
  "Gamma-distributed sample. See algo.random/gamma."
  random/gamma)

(def chi-square
  "Chi-square sample with dof degrees of freedom. See algo.random/chi-square."
  random/chi-square)

(def inverse-gamma
  "Inverse-gamma sample. See algo.random/inverse-gamma."
  random/inverse-gamma)

(def weibull
  "Weibull-distributed sample. See algo.random/weibull."
  random/weibull)

(def cauchy
  "Cauchy-distributed sample -- heavy-tailed, occasional wild outliers.
   See algo.random/cauchy."
  random/cauchy)

(def student-t
  "Student's t-distributed sample. See algo.random/student-t."
  random/student-t)

(def laplace
  "Laplace (double exponential) sample. See algo.random/laplace."
  random/laplace)

(def log-normal
  "Log-normal sample -- always positive, right-skewed.
   See algo.random/log-normal."
  random/log-normal)

(def beta
  "Beta-distributed sample on (0, 1). See algo.random/beta."
  random/beta)

;; ------------------------------------------------------------
;; DISCRETE/COLLECTION HELPERS (algo.random)
;; ------------------------------------------------------------

(def choose-n
  "n random elements from coll, without replacement. See algo.random/choose-n."
  random/choose-n)

(def deep-shuffle
  "Shuffle coll at every nesting level, down to an optional depth.
   See algo.random/deep-shuffle."
  random/deep-shuffle)

(defn cycle-deep-shuffle
  "Like cycle-shuffle, but reshuffling each pass via deep-shuffle
   instead of a flat algo.random/shuffle -- yields v in order, then a
   deep-shuffled v (every nesting level reordered, down to the same
   optional depth every pass), then a deep-shuffle of THAT, forever,
   lazily. The one other toolkit fn that reorders a whole given
   collection the same way shuffle/weighted-shuffle do (everything
   sampled/subset-drawing/walk-generating -- choose/choose-n/
   choose-from/random-walk/etc. -- doesn't fit this same 'yield every
   element exactly once per pass' shape, so none of those get a cycle
   counterpart).

   v must be non-empty -- same confirmed-live reasoning as cycle-shuffle:
   realizing any prefix of the result for an empty v hangs forever."
  ([v] (cycle-deep-shuffle v nil))
  ([v depth]
   (when (empty? v)
     (throw (ex-info "cycle-deep-shuffle: v must not be empty -- realizing
any prefix of the result would hang forever, never producing a first
element" {:v v})))
   (lazy-seq
     (concat v (cycle-deep-shuffle (deep-shuffle v depth) depth)))))

(defn take-cycle-deep-shuffle
  "A REALIZED (not lazy-infinite) vector of exactly n full cycles
   through v via cycle-deep-shuffle -- the deep-shuffle counterpart to
   take-cycle-shuffle, same shape otherwise."
  ([n v] (take-cycle-deep-shuffle n v nil))
  ([n v depth]
   (realize-n-cycles n v (cycle-deep-shuffle v depth))))

(def choose-from
  "(count coll) random elements from coll, with replacement.
   See algo.random/choose-from."
  random/choose-from)

(def weighted-coin
  "true with probability n (clamped to [0,1]). See algo.random/weighted-coin."
  random/weighted-coin)

(def only
  "The elements of coll at the given indices, in order.
   See algo.random/only."
  random/only)

(def sputter
  "coll with some elements probabilistically repeated. See algo.random/sputter."
  random/sputter)

;; ------------------------------------------------------------
;; SHAPED/SKEWED DISTRIBUTIONS (algo.random)
;; ------------------------------------------------------------

(def triangular
  "Triangular distribution peaked at mode. See algo.random/triangular."
  random/triangular)

(def linear
  "Linear-density distribution over [lo, hi]. See algo.random/linear."
  random/linear)

(def arcsine
  "Arcsine distribution -- density highest at the extremes.
   See algo.random/arcsine."
  random/arcsine)

(def lo-emph
  "Triangular distribution peaked at the low end. See algo.random/lo-emph."
  random/lo-emph)

(def mean-emph
  "Symmetric triangular distribution peaked at the midpoint.
   See algo.random/mean-emph."
  random/mean-emph)

(def hi-emph
  "Triangular distribution peaked at the high end. See algo.random/hi-emph."
  random/hi-emph)

;; ------------------------------------------------------------
;; WALKS & COMPOSITE GENERATORS (algo.random)
;; ------------------------------------------------------------

(def int-range
  "Random integer in [lo, hi). See algo.random/int-range."
  random/int-range)

(def cyclic-random
  "A 0-arg fn yielding random items from coll, reshuffling once
   exhausted. See algo.random/cyclic-random."
  random/cyclic-random)

(def random-walk
  "A 0-arg fn that moves randomly by at most step-bound each call.
   See algo.random/random-walk."
  random/random-walk)

(def rising
  "Random float in [lo, hi] with upward bias. See algo.random/rising."
  random/rising)

(def falling
  "Random float in [lo, hi] with downward bias. See algo.random/falling."
  random/falling)

(def int-rising
  "Integer version of rising. See algo.random/int-rising."
  random/int-rising)

(def int-falling
  "Integer version of falling. See algo.random/int-falling."
  random/int-falling)

(def biased-walk
  "Like random-walk, with directional bias. See algo.random/biased-walk."
  random/biased-walk)

(def smooth-walk
  "A fn that moves toward a target each call, with inertia.
   See algo.random/smooth-walk."
  random/smooth-walk)

(def smooth-noise
  "A smooth, continuous noise curve over [0, n-1], sampled at any t.
   See algo.random/smooth-noise."
  random/smooth-noise)

;; ------------------------------------------------------------
;; EVENT GENERATION + MARKOV CHAIN (algo.random)
;; ------------------------------------------------------------

(def poisson-events
  "Event onset times within [0, duration), Poisson-process style.
   See algo.random/poisson-events."
  random/poisson-events)

(def markov-chain
  "A 0-arg fn that walks through states using transition weights.
   See algo.random/markov-chain."
  random/markov-chain)

;; ------------------------------------------------------------
;; MATH/NUMBER UTILITIES (algo.common.numeric/rotate/scaling/trig/farey)
;; -- all five source namespaces have zero requires of their own; direct
;; forwards, same shape as the algo.random section above.
;; ------------------------------------------------------------

(def gcd
  "Greatest common divisor of a and b, via the Euclidean algorithm.
   See algo.common.numeric/gcd."
  numeric/gcd)

(def lcm
  "Least common multiple of a and b. See algo.common.numeric/lcm."
  numeric/lcm)

(def lcm-multiple
  "Least common multiple of every number in ns. See
   algo.common.numeric/lcm-multiple."
  numeric/lcm-multiple)

(def rotate
  "Rotate pattern left by i positions (negative/oversized i wraps via
   mod). See algo.common.rotate/rotate."
  rotate-src/rotate)

(def clamp
  "Clamp v into [lo hi]. See algo.common.scaling/clamp."
  scaling/clamp)

(def clamp-optional
  "Clamp v into [clip-lo clip-hi], where EITHER bound may be nil (no
   limit on that side). See algo.common.scaling/clamp-optional."
  scaling/clamp-optional)

(def closest-to
  "Whichever of low/hi is numerically closer to n.
   See algo.common.scaling/closest-to."
  scaling/closest-to)

(def round-to
  "Round n to the nearest multiple of div. See algo.common.scaling/round-to."
  scaling/round-to)

(def scale-range
  "Linearly remaps x from [inmin,inmax] to [outmin,outmax].
   See algo.common.scaling/scale-range."
  scaling/scale-range)

(def cosr
  "Value at idx along a cosine wave scaled by amp, shifted to base,
   completing one full cycle every period idxs. See algo.common.trig/cosr."
  trig/cosr)

(def sinr
  "Value at idx along a sine wave scaled by amp, shifted to base,
   completing one full cycle every period idxs. See algo.common.trig/sinr."
  trig/sinr)

(def trianglr
  "Value at idx along a triangle wave scaled by amp, shifted to base.
   See algo.common.trig/trianglr."
  trig/trianglr)

(def squarr
  "Value at idx along a square wave scaled by amp, shifted to base.
   See algo.common.trig/squarr."
  trig/squarr)

(def sawr
  "Value at idx along a sawtooth wave scaled by amp, shifted to base.
   See algo.common.trig/sawr."
  trig/sawr)

(def tanr
  "Value at idx along a tangent wave scaled by amp, shifted to base --
   has genuine asymptotes near odd multiples of period/4.
   See algo.common.trig/tanr."
  trig/tanr)

(def farey
  "Best rational approximation of x (a value in [0,1)) with denominator
   at most N, via a Stern-Brocot mediant search. See algo.common.farey/farey."
  farey-src/farey)

;; ------------------------------------------------------------
;; VOICE-SPLITTING CANON (algo.common.split) -- zero requires of its
;; own; direct forwards.
;; ------------------------------------------------------------

(def split
  "melody: a seq of [pitch duration] pairs, the original low/slow line.
   n: how many times to split a new voice off the current highest one.
   Returns a vector of (inc n) voices. See algo.common.split/split."
  split-src/split)

(def split-leafs
  "Same recipe as split, one level later: leafs is a seq of real
   Leaf/Rest/Drum-shaped maps (or anything with :pitches/:duration keys)
   rather than bare [pitch duration] pairs. See algo.common.split/split-leafs."
  split-src/split-leafs)

(def split-leaf-voice
  "ONE layer's worth of a split-leafs canon, by voice-index (0..n,
   defaulting to n itself, the final/fastest/highest split-off).
   See algo.common.split/split-leaf-voice."
  split-src/split-leaf-voice)

;; ------------------------------------------------------------
;; ISORHYTHM -- standalone port of algo.common.isorhythm's dependency-
;; free functions (color-talea/zip-parts), NOT a require: that ns also
;; defines a wall-fn factory requiring core.wall/core.domain.flat-domain,
;; which toolkit stays free of. Reuses algo.common.numeric/lcm (already
;; required above, itself a genuine dependency-free leaf).
;; ------------------------------------------------------------

(defn color-talea
  "Combine a color (pitch sequence) and a talea (duration sequence) into
   the classic isorhythmic color-talea pairing: event i's pitch is (nth
   color (mod i (count color))), its duration is (nth talea (mod i
   (count talea))) -- the two cycle completely independently. periods
   counts how many *full periods* (lcm(count color, count talea) events
   each) to generate. Returns a vector of [pitch duration] pairs.
   Standalone port of algo.common.isorhythm/color-talea's own
   dependency-free core -- see this ns's own docstring for why."
  ([color talea] (color-talea color talea 1))
  ([color talea periods]
   (let [color  (vec color)
         talea  (vec talea)
         cn     (count color)
         tn     (count talea)
         period (numeric/lcm cn tn)
         total  (* periods period)]
     (mapv (fn [i] [(nth color (mod i cn)) (nth talea (mod i tn))])
           (range total)))))

(defn zip-parts
  "Generalizes color-talea past its own fixed pitch+duration pair: any
   number of independently-cycling raw value streams, keyed by name --
   e.g. {:pitch [60 62 64] :duration [1/4 1/8] :dynamic [:mf :ff]}. Each
   stream cycles independently at its OWN length; the combined period is
   lcm of EVERY stream's own count. periods (default 1) counts how many
   *full periods* to generate. streams must be non-empty. Standalone
   port of algo.common.isorhythm/zip-parts's own dependency-free core --
   see this ns's own docstring for why.
     (zip-parts {:pitch [60 62 64] :duration [1/4 1/8]})
     ;; => [{:pitch 60 :duration 1/4} {:pitch 62 :duration 1/8} ...]"
  ([streams] (zip-parts streams 1))
  ([streams periods]
   (when (empty? streams)
     (throw (ex-info "zip-parts: streams must not be empty" {:streams streams})))
   (let [streams (into {} (map (fn [[k v]] [k (vec v)])) streams)
         period  (reduce numeric/lcm 1 (map count (vals streams)))
         total   (* periods period)]
     (mapv (fn [i]
             (into {} (map (fn [[k v]] [k (nth v (mod i (count v)))])) streams))
           (range total)))))

;; ------------------------------------------------------------
;; Z-FILTER RECURRENCE -- standalone port of algo.common.zfilter's
;; dependency-free core, NOT a require: that ns also defines a wall-fn
;; factory requiring core.wall/core.domain.flat-domain.
;; ------------------------------------------------------------

(defn z-filter
  "The generic recurrence filter -- b (feedforward coefficients) and a
   (feedback coefficients, a[0] must be 1) define:
     y[n] = sum(b[k] * x[n-k] for k in 0..) - sum(a[k] * y[n-k] for k in 1..)
   over xs (a plain seq of numbers), returning y as a vector, same
   length as xs. Standalone port of algo.common.zfilter/z-filter -- see
   this ns's own docstring for why."
  [b a xs]
  (when (not= 1 (first a))
    (throw (ex-info "z-filter: (first a) must be 1" {:a a})))
  (let [xs (vec xs)
        n  (count xs)
        y  (object-array n)]
    (dotimes [i n]
      (let [ff (reduce + (map-indexed
                            (fn [k bk] (if (>= (- i k) 0) (* bk (nth xs (- i k))) 0))
                            b))
            fb (reduce + (map-indexed
                            (fn [k ak] (if (and (pos? k) (>= (- i k) 0))
                                         (* ak (aget y (- i k)))
                                         0))
                            a))]
        (aset y i (- ff fb))))
    (vec y)))

(defn smooth
  "One-pole smoothing: y[n] = (1-alpha)*x[n] + alpha*y[n-1]. alpha in
   [0,1) -- higher alpha means more smoothing. See
   algo.common.zfilter/smooth."
  [alpha xs]
  (z-filter [(- 1 alpha)] [1 (- alpha)] xs))

(defn momentum
  "Momentum/inertia: y[n] = x[n] + beta*(y[n-1] - x[n-1]) -- the
   filtered sequence tends to keep moving in whatever direction it was
   already heading. See algo.common.zfilter/momentum."
  [beta xs]
  (z-filter [(+ 1 beta) (- beta)] [1 (- beta)] xs))

(defn memory
  "Decay/memory: y[n] = x[n] + decay*y[n-1] -- each value's own
   influence lingers, decaying geometrically. See algo.common.zfilter/memory."
  [decay xs]
  (z-filter [1] [1 (- decay)] xs))

(defn- interval-transform
  "Shared skeleton behind smooth-intervals/interval-gain below: a
   sequence of fewer than 2 values passes through unchanged (nothing to
   take an interval between), otherwise diff xs into consecutive
   intervals, run xform over them, then reconstruct a sequence from the
   transformed intervals starting back at xs's own first value."
  [xform xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      xs
      (reduce (fn [acc iv] (conj acc (+ (peek acc) iv)))
              [(first xs)]
              (xform (mapv - (rest xs) xs))))))

(defn smooth-intervals
  "Smooth the INTERVALS between consecutive values (not the absolute
   values themselves), then reconstruct the sequence from the smoothed
   intervals -- softens sudden melodic leaps while keeping the overall
   contour direction. A sequence of fewer than 2 values passes through
   unchanged. See algo.common.zfilter/smooth-intervals."
  [alpha xs]
  (interval-transform #(smooth alpha %) xs))

(defn interval-gain
  "Multiply every interval between consecutive values by factor --
   factor > 1 exaggerates the contour, factor < 1 compresses it toward a
   flat line, factor = 1 is a no-op, negative factor inverts the
   contour. A sequence of fewer than 2 values passes through unchanged.
   See algo.common.zfilter/interval-gain."
  [factor xs]
  (interval-transform #(mapv * % (repeat factor)) xs))

(defn pc-smooth
  "Smooth pitch CLASSES (mod 12) rather than absolute pitch -- useful
   for smoothing harmonic/pitch-class motion independent of octave.
   See algo.common.zfilter/pc-smooth."
  [alpha xs]
  (smooth alpha (mapv #(mod % 12) xs)))

;; ------------------------------------------------------------
;; BARLOW INDISPENSABILITY (algo.indisp.indispensability) -- zero
;; requires of its own; direct forwards.
;; ------------------------------------------------------------

(def indispensability
  "subdivisions (an ordered factor sequence, e.g. [2 2 3]) -> a vector
   of Barlow indispensability ranks, one per pulse, downbeat always
   highest. See algo.indisp.indispensability/indispensability."
  indisp/indispensability)

(def normalize-weights
  "Divide weights by their own max, landing them in [0,1].
   See algo.indisp.indispensability/normalize-weights."
  indisp/normalize-weights)

(def normalized-indispensability
  "indispensability + normalize-weights in one step: subdivisions ->
   normalized [0,1] float ranks, ready to feed an ordinary seq
   transform (reverse/shuffle/rotate) before density-grid or a pulse
   grid. See algo.indisp.indispensability/normalized-indispensability."
  indisp/normalized-indispensability)

(def tilt-probabilities
  "Softmax over indispensability ranks (or any weights), temperature-
   scaled by adherence -- higher adherence pushes probability mass
   toward the more indispensable pulses more sharply; never produces an
   exact tie. See algo.indisp.indispensability/tilt-probabilities."
  indisp/tilt-probabilities)

(def power-law-probabilities
  "Power-law reshaping over indispensability ranks (or any weights) --
   always order-preserving (or -reversing for negative adherence),
   unlike tilt-probabilities' softmax, and CAN assign exactly zero
   probability. See algo.indisp.indispensability/power-law-probabilities."
  indisp/power-law-probabilities)

(def density-grid
  "Binary onset grid retaining exactly the most indispensable fraction
   of pulses (density, 0.0-1.0) -- deterministic, same subset every
   call for a given ranks/density pair.
   See algo.indisp.indispensability/density-grid."
  indisp/density-grid)

;; ------------------------------------------------------------
;; METRIC-STRUCTURE PULSE GENERATORS (algo.metric.metric) -- zero
;; requires of its own; direct forwards.
;; ------------------------------------------------------------

(def binary-decomposition-rhythm
  "number's own binary digits as a 0/1 onset grid, LSB first.
   See algo.metric.metric/binary-decomposition-rhythm."
  metric/binary-decomposition-rhythm)

(def continued-fraction-rhythm
  "Continued-fraction expansion of fraction as a 0/1 onset grid, up to
   length pulses. See algo.metric.metric/continued-fraction-rhythm."
  metric/continued-fraction-rhythm)

(def modular-rhythm
  "Onset grid marking every position where (% * multiplier + offset)
   is a multiple of modulus. See algo.metric.metric/modular-rhythm."
  metric/modular-rhythm)

;; ------------------------------------------------------------
;; RHYTHM-PATTERN GENERATORS (algo.rhythmic.rhythm) -- requires only
;; clojure.string and algo.random (already toolkit's own first
;; dependency); direct forwards.
;; ------------------------------------------------------------

(def euclidean-rhythm
  "Distribute k beats evenly among n pulses (Bjorklund's algorithm) --
   a 0/1 onset grid. See algo.rhythmic.rhythm/euclidean-rhythm."
  rhythm/euclidean-rhythm)

(def fibonacci-rhythm
  "0/1 onset grid of length length, onsets at Fibonacci-numbered
   positions. See algo.rhythmic.rhythm/fibonacci-rhythm."
  rhythm/fibonacci-rhythm)

(def prime-rhythm
  "0/1 onset grid of length length, onsets at prime-numbered positions.
   See algo.rhythmic.rhythm/prime-rhythm."
  rhythm/prime-rhythm)

(def lindenmayer-rhythm
  "Expand axiom through an L-system's own rules for iterations
   generations, mapping each character to a 0/1 onset, up to length
   pulses. See algo.rhythmic.rhythm/lindenmayer-rhythm."
  rhythm/lindenmayer-rhythm)

(def markov-rhythm
  "length onset values, each state's own numeric value, walking
   transition-matrix from initial-state (a {state {next-state prob}}
   table, drawn via algo.random/markov). See
   algo.rhythmic.rhythm/markov-rhythm."
  rhythm/markov-rhythm)

;; ------------------------------------------------------------
;; SLONIMSKY MELODIC INTERPOLATION -- standalone port of
;; algo.melodic.slonimsky's dependency-free functions (mixed-polations/
;; infrapolate/ultrapolate/interpolate), NOT a require: that ns also
;; defines a wall-fn factory requiring core.wall.
;; ------------------------------------------------------------

(defn mixed-polations
  "The fully general Slonimsky interpolation form -- infra/inter/ultra
   are each independently optional (nil/empty disables that layer): for
   each principal tone p, in order, emit infra (if any), then p itself,
   then ultra (if any, unless p is the LAST tone and ultra-after-last?
   is false), then inter (if any, but never after the last tone).
   Standalone port of algo.melodic.slonimsky/mixed-polations -- see
   this ns's own docstring for why."
  ([principal] (mixed-polations principal nil nil nil false))
  ([principal infra inter ultra] (mixed-polations principal infra inter ultra false))
  ([principal infra inter ultra ultra-after-last?]
   (let [infra (or infra [])
         inter (or inter [])
         ultra (or ultra [])
         n     (count principal)]
     (vec
       (mapcat
         (fn [i p]
           (concat
             infra
             [p]
             (when (or ultra-after-last? (< i (dec n))) ultra)
             (when (< i (dec n)) inter)))
         (range)
         principal)))))

(defn infrapolate
  "Insert insertion BEFORE each tone in principal. Standalone port of
   algo.melodic.slonimsky/infrapolate."
  [principal insertion]
  (mixed-polations principal insertion nil nil false))

(defn ultrapolate
  "Insert insertion AFTER each tone in principal (including the last).
   Standalone port of algo.melodic.slonimsky/ultrapolate."
  [principal insertion]
  (mixed-polations principal nil nil insertion true))

(defn interpolate
  "Insert insertion BETWEEN each pair of consecutive tones in principal
   -- fewer than 2 tones passes through unchanged. Standalone port of
   algo.melodic.slonimsky/interpolate."
  [principal insertion]
  (if (< (count principal) 2)
    (vec principal)
    (mixed-polations principal nil insertion nil false)))

;; ------------------------------------------------------------
;; SPECIES COUNTERPOINT (algo.melodic.counterpoint) -- requires only
;; algo.random; direct forwards, renamed from the source's own generic
;; generate/default-rules (too generic for this flat namespace).
;; ------------------------------------------------------------

(def species-counterpoint-default-rules
  "No parallel fifths/octaves, consonance required on every beat.
   See algo.melodic.counterpoint/default-rules."
  counterpoint/default-rules)

(def species-counterpoint
  "Generate n voices of [pitch duration] pairs from a scale,
   pitch-range, beat-durations, and one voice-spec map per voice --
   loose motivic imitation (pass 1) then species-counterpoint rule
   enforcement against every earlier voice (pass 2). Rules are a
   best-effort filter, not a hard guarantee -- falls back to the
   nearest allowed pitch when no candidate satisfies every rule at
   once. See algo.melodic.counterpoint/generate for the full voice-spec
   shape and worked example."
  counterpoint/generate)

;; ------------------------------------------------------------
;; PRE-COMPOSED COMBINATIONS -- built directly from algo.dimensions/
;; compatible? confirming which pairs genuinely combine (each one
;; below IS a :direct or :glue match in that ns's own registry, not
;; just a plausible-looking pairing). Plain function composition, no
;; algoline needed -- each is a fixed, one-pass pipeline with nothing
;; a composer would want to swap independently; see
;; examples.indispensability-algoline for the staged/swappable version
;; of the same underlying idea.
;; ------------------------------------------------------------

(defn weighted-pulse-choice
  "Pick ONE pulse index from a meter of subdivisions, weighted by its
   own Barlow indispensability (softened by adherence via
   tilt-probabilities) -- 'pick a likely-important beat to accent.'
   indispensability's own :coll output feeds tilt-probabilities'
   :coll input directly; tilt-probabilities' own :coll output feeds
   weighted-choose's own weights argument directly -- both :direct
   matches per algo.dimensions/compatible?."
  [subdivisions adherence]
  (let [ranks (indispensability subdivisions)]
    (weighted-choose (range (count ranks)) (tilt-probabilities ranks adherence))))

(defn shuffled-euclidean
  "An infinite, reshuffled stream of a Euclidean rhythm's own onset
   grid -- euclidean-rhythm's own :coll output feeds cycle-shuffle's
   :coll input directly (:direct per algo.dimensions/compatible?)."
  [k n]
  (cycle-shuffle (euclidean-rhythm k n)))

(defn weighted-density-grid
  "Thin a meter down to its density fraction of pulses, chosen by
   ADHERENCE-SHAPED probability rather than raw indispensability rank
   -- density-grid's own docstring already documents it works on 'ranks
   (or any weights),' and tilt-probabilities' own :coll output is
   exactly that: a :direct match per algo.dimensions/compatible?."
  [subdivisions adherence density]
  (density-grid (tilt-probabilities (indispensability subdivisions) adherence) density))
