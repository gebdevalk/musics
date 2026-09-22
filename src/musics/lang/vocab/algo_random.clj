(ns musics.lang.vocab.algo-random
  "musics.lang's own `algo-random` vocabulary -- a mechanical bridge of
   algo.random's own basic primitives/distributions/walks (this
   project's existing, seedable RNG library), plus algo.random.core's
   own pure rng-atom-threading fns and algo.random.henon/logistic/
   lorenz's chaotic-map generators/core.wall factories.

   Every algo.random fn that takes an OPTIONAL leading rng-atom arg
   (choose/rand-double/rand-int/markov/weighted-choose/shuffle/...) is
   bridged at its DEFAULT-rng arity here, not the rng-atom-threading
   one -- what a musics.lang composer overwhelmingly wants day to day;
   algo.random.core's own rnd-* fns below cover the explicit-rng-atom
   case directly for whoever actually needs it (seed!/step! too).
   algo.random/shuffle is skipped entirely -- it's the exact algorithm
   already reachable as plain `shuffle` in the `musics` vocabulary
   (musics.core/shuffle is built directly on it), so bridging both
   would just be two names for one implementation."
  (:require [algo.random :as rnd]
            [algo.random.core :as core]
            [algo.random.henon :as henon]
            [algo.random.logistic :as logistic]
            [algo.random.lorenz :as lorenz]
            [musics.lang.runtime :refer [push! pop! builtin]]))

(defn vocab []
  (merge
    (builtin "arcsine" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/arcsine lo hi)))) "( lo hi -- x )" "arcsine distribution, density highest at the two extremes")
    (builtin "beta" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (rnd/beta a b)))) "( a b -- x )" "beta distribution on (0,1), shaped by a and b")
    (builtin "biased-walk" (fn [ctx] (let [bias (pop! ctx) step-bound (pop! ctx) start (pop! ctx)] (push! ctx (rnd/biased-walk start step-bound bias)))) "( start step-bound bias -- walk-fn )" "like random-walk, with directional bias")
    (builtin "cauchy" (fn [ctx] (let [scale (pop! ctx) median (pop! ctx)] (push! ctx (rnd/cauchy median scale)))) "( median scale -- x )" "cauchy distribution, heavy-tailed, occasional wild outliers")
    (builtin "chi-square" (fn [ctx] (push! ctx (rnd/chi-square (pop! ctx)))) "( dof -- x )" "chi-square distribution with dof degrees of freedom")
    (builtin "choose" (fn [ctx] (push! ctx (rnd/choose (pop! ctx)))) "( coll -- elt )" "a random element from coll")
    (builtin "choose-from" (fn [ctx] (push! ctx (rnd/choose-from (pop! ctx)))) "( coll -- coll' )" "(count coll) random elements from coll, with replacement")
    (builtin "choose-n" (fn [ctx] (let [coll (pop! ctx) n (pop! ctx)] (push! ctx (rnd/choose-n n coll)))) "( n coll -- coll' )" "n random elements from coll, without replacement")
    (builtin "cyclic-random" (fn [ctx] (push! ctx (rnd/cyclic-random (pop! ctx)))) "( coll -- fn )" "a 0-arg fn yielding random items from coll, reshuffling once exhausted")
    (builtin "deep-shuffle" (fn [ctx] (let [depth (pop! ctx) coll (pop! ctx)] (push! ctx (rnd/deep-shuffle coll depth)))) "( coll depth -- coll' )" "shuffles coll at every nesting level, down to depth")
    (builtin "exponential" (fn [ctx] (push! ctx (rnd/exponential (pop! ctx)))) "( mean -- x )" "exponential distribution with the given mean")
    (builtin "falling" (fn [ctx] (let [bias (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/falling lo hi bias)))) "( lo hi bias -- x )" "random float in [lo,hi] with downward bias")
    (builtin "gamma" (fn [ctx] (let [scale (pop! ctx) shape (pop! ctx)] (push! ctx (rnd/gamma shape scale)))) "( shape scale -- x )" "gamma-distributed sample")
    (builtin "generative-patch" (fn [ctx] (push! ctx (rnd/generative-patch))) "( -- fn )" "a fn generating musical events with rising/falling tendencies")
    (builtin "hi-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/hi-emph lo hi)))) "( lo hi -- x )" "triangular distribution peaked at the high end")
    (builtin "int-arcsine" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-arcsine lo hi)))) "( lo hi -- n )" "integer arcsine, in [lo,hi)")
    (builtin "int-falling" (fn [ctx] (let [bias (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-falling lo hi bias)))) "( lo hi bias -- n )" "integer falling, in [lo,hi)")
    (builtin "int-hi-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-hi-emph lo hi)))) "( lo hi -- n )" "integer hi-emph, in [lo,hi)")
    (builtin "int-linear" (fn [ctx] (let [rising? (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-linear lo hi rising?)))) "( lo hi rising? -- n )" "integer linear-density distribution over [lo,hi)")
    (builtin "int-lo-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-lo-emph lo hi)))) "( lo hi -- n )" "integer lo-emph, in [lo,hi)")
    (builtin "int-mean-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-mean-emph lo hi)))) "( lo hi -- n )" "integer mean-emph, in [lo,hi)")
    (builtin "int-range" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-range lo hi)))) "( lo hi -- n )" "random integer in [lo,hi)")
    (builtin "int-rising" (fn [ctx] (let [bias (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-rising lo hi bias)))) "( lo hi bias -- n )" "integer rising, in [lo,hi)")
    (builtin "int-triangular" (fn [ctx] (let [mode (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/int-triangular lo hi mode)))) "( lo hi mode -- n )" "integer triangular, in [lo,hi)")
    (builtin "inverse-gamma" (fn [ctx] (let [scale (pop! ctx) shape (pop! ctx)] (push! ctx (rnd/inverse-gamma shape scale)))) "( shape scale -- x )" "inverse-gamma distribution")
    (builtin "laplace" (fn [ctx] (let [scale (pop! ctx) mean (pop! ctx)] (push! ctx (rnd/laplace mean scale)))) "( mean scale -- x )" "laplace (double exponential) distribution")
    (builtin "linear" (fn [ctx] (let [rising? (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/linear lo hi rising?)))) "( lo hi rising? -- x )" "linear-density distribution over [lo,hi]")
    (builtin "lo-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/lo-emph lo hi)))) "( lo hi -- x )" "triangular distribution peaked at the low end")
    (builtin "log-normal" (fn [ctx] (let [sigma (pop! ctx) mu (pop! ctx)] (push! ctx (rnd/log-normal mu sigma)))) "( mu sigma -- x )" "log-normal distribution, always positive, right-skewed")
    (builtin "markov" (fn [ctx] (let [state (pop! ctx) table (pop! ctx)] (push! ctx (rnd/markov table state)))) "( table state -- next-state )" "single-step Markov transition")
    (builtin "markov-chain" (fn [ctx] (let [start-state (pop! ctx) transitions (pop! ctx)] (push! ctx (rnd/markov-chain transitions start-state)))) "( transitions start-state -- fn )" "a 0-arg fn walking through states using transition weights")
    (builtin "mean-emph" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/mean-emph lo hi)))) "( lo hi -- x )" "symmetric triangular distribution peaked at the midpoint")
    (builtin "normal" (fn [ctx] (let [stdev (pop! ctx) mean (pop! ctx)] (push! ctx (rnd/normal mean stdev)))) "( mean stdev -- x )" "normal (Gaussian) distribution, via Box-Muller")
    (builtin "only" (fn [ctx] (let [notes (pop! ctx) phrase (pop! ctx)] (push! ctx (rnd/only phrase notes)))) "( phrase notes -- notes' )" "takes only the specified notes (indices/keys) from phrase")
    (builtin "poisson-events" (fn [ctx] (let [duration (pop! ctx) rate (pop! ctx)] (push! ctx (rnd/poisson-events rate duration)))) "( rate duration -- onsets )" "Poisson-process event onset times within [0,duration)")
    (builtin "rand-double" (fn [ctx] (push! ctx (rnd/rand-double))) "( -- x )" "uniform double in [0,1)")
    (builtin "rand-int" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/rand-int lo hi)))) "( lo hi -- n )" "uniform integer in [lo,hi)")
    (builtin "random-rhythm" (fn [ctx] (let [density (pop! ctx) num-beats (pop! ctx) beat-duration (pop! ctx)] (push! ctx (rnd/random-rhythm beat-duration num-beats density)))) "( beat-duration num-beats density -- onsets )" "a sequence of event times within num-beats")
    (builtin "random-walk" (fn [ctx] (let [step-bound (pop! ctx) start (pop! ctx)] (push! ctx (rnd/random-walk start step-bound)))) "( start step-bound -- fn )" "a fn moving randomly by at most step-bound each call")
    (builtin "rising" (fn [ctx] (let [bias (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/rising lo hi bias)))) "( lo hi bias -- x )" "random float in [lo,hi] with upward bias")
    (builtin "smooth-noise" (fn [ctx] (let [hi (pop! ctx) lo (pop! ctx) n (pop! ctx)] (push! ctx (rnd/smooth-noise n lo hi)))) "( n lo hi -- fn )" "a smooth, continuous noise curve over [0,n-1]")
    (builtin "smooth-walk" (fn [ctx] (let [step (pop! ctx) inertia (pop! ctx) initial (pop! ctx)] (push! ctx (rnd/smooth-walk initial inertia step)))) "( initial inertia step -- fn )" "a fn moving toward a target each call, with inertia")
    (builtin "sputter" (fn [ctx] (let [max (pop! ctx) prob (pop! ctx) lst (pop! ctx)] (push! ctx (rnd/sputter lst prob max)))) "( lst prob max -- lst' )" "lst with some elements probabilistically repeated")
    (builtin "student-t" (fn [ctx] (push! ctx (rnd/student-t (pop! ctx)))) "( dof -- x )" "Student's t-distributed sample")
    (builtin "triangular" (fn [ctx] (let [mode (pop! ctx) hi (pop! ctx) lo (pop! ctx)] (push! ctx (rnd/triangular lo hi mode)))) "( lo hi mode -- x )" "triangular distribution with peak at mode")
    (builtin "uniform" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (rnd/uniform a b)))) "( a b -- x )" "uniform random sample from (a,b)")
    (builtin "weibull" (fn [ctx] (let [scale (pop! ctx) shape (pop! ctx)] (push! ctx (rnd/weibull shape scale)))) "( shape scale -- x )" "weibull distribution, a generalized exponential")
    (builtin "weighted-choose" (fn [ctx] (let [weights (pop! ctx) vals (pop! ctx)] (push! ctx (rnd/weighted-choose vals weights)))) "( vals weights -- elt )" "an element with probability proportional to its own weight")
    (builtin "weighted-coin" (fn [ctx] (push! ctx (rnd/weighted-coin (pop! ctx)))) "( n -- ? )" "true with probability n, clamped to [0,1]")

    (builtin "default-rng" (fn [ctx] (push! ctx core/default-rng)) "( -- rng-atom )" "the default RNG state atom every fn above uses when no explicit atom is passed")
    (builtin "seed!" (fn [ctx] (let [seed (pop! ctx) rng-atom (pop! ctx)] (push! ctx (core/seed! rng-atom seed)))) "( rng-atom seed -- rng )" "resets an RNG atom to a fresh state from seed")
    (builtin "step!" (fn [ctx] (let [args (pop! ctx) f (pop! ctx) rng-atom (pop! ctx)] (push! ctx (apply core/step! rng-atom f args)))) "( rng-atom f args -- value )" "draws once from rng-atom via f (args a plain vector of f's own extra args)")
    (builtin "rnd-double" (fn [ctx] (push! ctx (core/rnd-double (pop! ctx)))) "( rng -- [value rng'] )" "pure uniform double in [0,1)")
    (builtin "rnd-int" (fn [ctx] (let [n (pop! ctx) rng (pop! ctx)] (push! ctx (core/rnd-int rng n)))) "( rng n -- [value rng'] )" "pure uniform integer in [0,n)")
    (builtin "rnd-choose" (fn [ctx] (let [items (pop! ctx) rng (pop! ctx)] (push! ctx (core/rnd-choose rng items)))) "( rng items -- [value rng'] )" "pure uniform choice from items")
    (builtin "rnd-shuffle" (fn [ctx] (let [coll (pop! ctx) rng (pop! ctx)] (push! ctx (core/rnd-shuffle rng coll)))) "( rng coll -- [coll' rng'] )" "pure deterministic shuffle")
    (builtin "rnd-markov" (fn [ctx] (let [state (pop! ctx) table (pop! ctx) rng (pop! ctx)] (push! ctx (core/rnd-markov rng table state)))) "( rng table state -- [next-state rng'] )" "pure single-step Markov transition")
    (builtin "rnd-weighted" (fn [ctx] (let [weights (pop! ctx) items (pop! ctx) rng (pop! ctx)] (push! ctx (core/rnd-weighted rng items weights)))) "( rng items weights -- [value rng'] )" "pure weighted choice, weights need not sum to 1")

    (builtin "henon-attractor" (fn [ctx] (let [y0 (pop! ctx) x0 (pop! ctx) b (pop! ctx) a (pop! ctx)] (push! ctx (henon/henon-attractor a b x0 y0)))) "( a b x0 y0 -- fn )" "the classical Henon chaotic map")
    (builtin "henon-algo" (fn [ctx] (let [params (pop! ctx) name (pop! ctx)] (push! ctx (henon/henon-algo name params)))) "( name params -- name )" "a core.wall factory driving pitch/duration from the Henon map")
    (builtin "logistic-function" (fn [ctx] (let [x (pop! ctx) r (pop! ctx)] (push! ctx (logistic/logistic-function r x)))) "( r x -- fn )" "the classic discrete chaotic logistic map generator")
    (builtin "logistic-algo" (fn [ctx] (let [params (pop! ctx) name (pop! ctx)] (push! ctx (logistic/logistic-algo name params)))) "( name params -- name )" "a core.wall factory driving pitch/duration from the logistic map")
    (builtin "lorenz-attractor" (fn [ctx] (let [dt (pop! ctx) z0 (pop! ctx) y0 (pop! ctx) x0 (pop! ctx) beta (pop! ctx) rho (pop! ctx) sigma (pop! ctx)]
                                              (push! ctx (lorenz/lorenz-attractor sigma rho beta x0 y0 z0 dt))))
             "( sigma rho beta x0 y0 z0 dt -- fn )" "the real Edward Lorenz chaotic system")
    (builtin "lorenz-algo" (fn [ctx] (let [params (pop! ctx) name (pop! ctx)] (push! ctx (lorenz/lorenz-algo name params)))) "( name params -- name )" "a core.wall factory driving pitch/duration from the Lorenz system")))
