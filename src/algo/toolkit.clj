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
   than duplicating any of it: every fn here is a thin (apply
   algo.random/name args) forward, so there is exactly one real
   implementation of each, still reachable at algo.random/name too.
   Only the domain-specific ones were left out -- random-rhythm (takes
   a beat-duration/num-beats, inherently a musical-timing concept) and
   generative-patch (hardcodes :pitch/:velocity/:duration/:bend) -- both
   still directly available via algo.random if ever wanted here later.
   requiring algo.random pulls in only algo.random.core (the pure
   xorshift32 engine) and algo.common.scaling underneath it, both
   themselves leaf namespaces with no further requires -- this doesn't
   pull core.wall, core.async-engine, or anything else project-specific
   in behind it."
  (:require [algo.random :as random]))

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
   unshuffled, then n-1 further reshuffled passes. A plain convenience
   over (vec (take (* n (count v)) (cycle-shuffle v))); v's own
   non-empty requirement (cycle-shuffle's own, confirmed-live hang
   guard) is inherited, not re-checked here."
  [n v]
  (vec (take (* n (count v)) (cycle-shuffle v))))

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
  (vec (take (* n (count v)) (cycle-weighted-shuffle v))))

;; ------------------------------------------------------------
;; BASIC PRIMITIVES (algo.random) -- see that ns's own docstrings for
;; the full explanation of each; these are thin forwards, not copies.
;; ------------------------------------------------------------

(defn rand-double
  "Uniform double in [0,1). See algo.random/rand-double."
  [& args] (apply random/rand-double args))

(defn rand-int
  "Uniform integer in [0,n). See algo.random/rand-int."
  [& args] (apply random/rand-int args))

(defn choose
  "A random element from coll. See algo.random/choose."
  [& args] (apply random/choose args))

(defn weighted-choose
  "An element with probability proportional to its own weight.
   See algo.random/weighted-choose."
  [& args] (apply random/weighted-choose args))

(defn shuffle
  "Shuffle coll (Fisher-Yates), from this project's own seedable RNG,
   not the JVM's unseedable one. See algo.random/shuffle."
  [& args] (apply random/shuffle args))

(defn markov
  "Single-step Markov transition. See algo.random/markov."
  [& args] (apply random/markov args))

;; ------------------------------------------------------------
;; CONTINUOUS DISTRIBUTIONS (algo.random)
;; ------------------------------------------------------------

(defn uniform
  "Uniform sample from (a, b). See algo.random/uniform."
  [& args] (apply random/uniform args))

(defn normal
  "Normal (Gaussian) sample, via Box-Muller. See algo.random/normal."
  [& args] (apply random/normal args))

(defn exponential
  "Exponential sample with the given mean. See algo.random/exponential."
  [& args] (apply random/exponential args))

(defn gamma
  "Gamma-distributed sample. See algo.random/gamma."
  [& args] (apply random/gamma args))

(defn chi-square
  "Chi-square sample with dof degrees of freedom. See algo.random/chi-square."
  [& args] (apply random/chi-square args))

(defn inverse-gamma
  "Inverse-gamma sample. See algo.random/inverse-gamma."
  [& args] (apply random/inverse-gamma args))

(defn weibull
  "Weibull-distributed sample. See algo.random/weibull."
  [& args] (apply random/weibull args))

(defn cauchy
  "Cauchy-distributed sample -- heavy-tailed, occasional wild outliers.
   See algo.random/cauchy."
  [& args] (apply random/cauchy args))

(defn student-t
  "Student's t-distributed sample. See algo.random/student-t."
  [& args] (apply random/student-t args))

(defn laplace
  "Laplace (double exponential) sample. See algo.random/laplace."
  [& args] (apply random/laplace args))

(defn log-normal
  "Log-normal sample -- always positive, right-skewed.
   See algo.random/log-normal."
  [& args] (apply random/log-normal args))

(defn beta
  "Beta-distributed sample on (0, 1). See algo.random/beta."
  [& args] (apply random/beta args))

;; ------------------------------------------------------------
;; DISCRETE/COLLECTION HELPERS (algo.random)
;; ------------------------------------------------------------

(defn choose-n
  "n random elements from coll, without replacement. See algo.random/choose-n."
  [& args] (apply random/choose-n args))

(defn deep-shuffle
  "Shuffle coll at every nesting level, down to an optional depth.
   See algo.random/deep-shuffle."
  [& args] (apply random/deep-shuffle args))

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
   (vec (take (* n (count v)) (cycle-deep-shuffle v depth)))))

(defn choose-from
  "(count coll) random elements from coll, with replacement.
   See algo.random/choose-from."
  [& args] (apply random/choose-from args))

(defn weighted-coin
  "true with probability n (clamped to [0,1]). See algo.random/weighted-coin."
  [& args] (apply random/weighted-coin args))

(defn only
  "The elements of coll at the given indices, in order.
   See algo.random/only."
  [& args] (apply random/only args))

(defn sputter
  "coll with some elements probabilistically repeated. See algo.random/sputter."
  [& args] (apply random/sputter args))

;; ------------------------------------------------------------
;; SHAPED/SKEWED DISTRIBUTIONS (algo.random)
;; ------------------------------------------------------------

(defn triangular
  "Triangular distribution peaked at mode. See algo.random/triangular."
  [& args] (apply random/triangular args))

(defn linear
  "Linear-density distribution over [lo, hi]. See algo.random/linear."
  [& args] (apply random/linear args))

(defn arcsine
  "Arcsine distribution -- density highest at the extremes.
   See algo.random/arcsine."
  [& args] (apply random/arcsine args))

(defn lo-emph
  "Triangular distribution peaked at the low end. See algo.random/lo-emph."
  [& args] (apply random/lo-emph args))

(defn mean-emph
  "Symmetric triangular distribution peaked at the midpoint.
   See algo.random/mean-emph."
  [& args] (apply random/mean-emph args))

(defn hi-emph
  "Triangular distribution peaked at the high end. See algo.random/hi-emph."
  [& args] (apply random/hi-emph args))

;; ------------------------------------------------------------
;; WALKS & COMPOSITE GENERATORS (algo.random)
;; ------------------------------------------------------------

(defn int-range
  "Random integer in [lo, hi). See algo.random/int-range."
  [& args] (apply random/int-range args))

(defn cyclic-random
  "A 0-arg fn yielding random items from coll, reshuffling once
   exhausted. See algo.random/cyclic-random."
  [& args] (apply random/cyclic-random args))

(defn random-walk
  "A 0-arg fn that moves randomly by at most step-bound each call.
   See algo.random/random-walk."
  [& args] (apply random/random-walk args))

(defn rising
  "Random float in [lo, hi] with upward bias. See algo.random/rising."
  [& args] (apply random/rising args))

(defn falling
  "Random float in [lo, hi] with downward bias. See algo.random/falling."
  [& args] (apply random/falling args))

(defn int-rising
  "Integer version of rising. See algo.random/int-rising."
  [& args] (apply random/int-rising args))

(defn int-falling
  "Integer version of falling. See algo.random/int-falling."
  [& args] (apply random/int-falling args))

(defn biased-walk
  "Like random-walk, with directional bias. See algo.random/biased-walk."
  [& args] (apply random/biased-walk args))

(defn smooth-walk
  "A fn that moves toward a target each call, with inertia.
   See algo.random/smooth-walk."
  [& args] (apply random/smooth-walk args))

(defn smooth-noise
  "A smooth, continuous noise curve over [0, n-1], sampled at any t.
   See algo.random/smooth-noise."
  [& args] (apply random/smooth-noise args))

;; ------------------------------------------------------------
;; EVENT GENERATION + MARKOV CHAIN (algo.random)
;; ------------------------------------------------------------

(defn poisson-events
  "Event onset times within [0, duration), Poisson-process style.
   See algo.random/poisson-events."
  [& args] (apply random/poisson-events args))

(defn markov-chain
  "A 0-arg fn that walks through states using transition weights.
   See algo.random/markov-chain."
  [& args] (apply random/markov-chain args))
