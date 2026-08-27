;; core.clj
;; The low-level RNG engine for this project: a private, pure
;; xorshift32 core (rnd-new/rnd-step/rnd-double/rnd-int/rnd-choose/
;; rnd-weighted/rnd-markov/rnd-shuffle, each taking/returning [value
;; rng']), atom-backed seeding/state (default-rng, seed!, with-seed)
;; instead of the JVM's unseedable Math/random(), and the handful of
;; basic public primitives (rand-double, rand-int, choose,
;; weighted-choose, shuffle, markov) that wrap the private core
;; directly via step! -- they have to live here, in the same
;; namespace as the private fns they call. Deliberately an atom, not
;; a dynamic var/binding: this project's engine runs everything
;; through core.async go-blocks, and a dynamic binding isn't reliably
;; conveyed across a parked goroutine resuming on a different pool
;; thread -- these fns need to work identically whether called
;; synchronously (material prep before play) or from inside a
;; core.wall algorithm (running per-voice, inside a go-block).
;;
;; Everything built ON TOP of these primitives (continuous
;; distributions, discrete/collection helpers, shaped distributions,
;; walks/composite generators, event/rhythm generators) lives in
;; algo.random, which requires this namespace rather than duplicating any
;; of it. The sibling logistic.clj and lorenz.clj are NOT part of
;; either -- chaotic maps, deterministic given their own explicit
;; state, no PRNG involved at all.

(ns algo.random.core
  (:refer-clojure :exclude [rand-int shuffle]))

;; ------------------------------------------------------------
;; RNG OBJECT
;; ------------------------------------------------------------

(defn- rnd-new
  "Create a new RNG with a 32-bit seed."
  [seed]
  {:type :rng
   :seed seed
   :state (bit-and seed 0xFFFFFFFF)})

;; ------------------------------------------------------------
;; CORE STEP (Xorshift32)
;; ------------------------------------------------------------

(defn- rnd-step
  "Return [next-int updated-rng]."
  [{:keys [state] :as rng}]
  (let [x (-> state
              (bit-xor (bit-shift-left state 13))
              (bit-xor (bit-shift-right state 17))
              (bit-xor (bit-shift-left state 5))
              (bit-and 0xFFFFFFFF))]
    [x (assoc rng :state x)]))

;; ------------------------------------------------------------
;; DERIVED VALUES
;; ------------------------------------------------------------

(defn- rnd-double
  "Uniform double in [0,1)."
  [rng]
  (let [[i rng2] (rnd-step rng)]
    [(/ i (double 0xFFFFFFFF)) rng2]))

(defn- rnd-int
  "Uniform integer in [0,n)."
  [rng n]
  (let [[i rng2] (rnd-step rng)]
    [(mod i n) rng2]))

;; ------------------------------------------------------------
;; ALEATORY OPERATORS
;; ------------------------------------------------------------

(defn- rnd-choose
  "Uniform choice from items."
  [rng items]
  (let [[i rng2] (rnd-int rng (count items))]
    [(nth items i) rng2]))

(defn- rnd-weighted
  "Weighted choice (prnd). Weights need not sum to 1 -- normalized
   against their own total."
  [rng items weights]
  (let [[u rng2] (rnd-double rng)
        total (reduce + weights)
        r (* total u)
        cum (reductions + weights)
        idx (count (take-while #(<= % r) cum))]
    [(nth items idx) rng2]))

(defn- rnd-markov
  "Markov transition: table is {state {next-state prob}}."
  [rng table state]
  (let [transitions (table state)
        items (keys transitions)
        weights (vals transitions)]
    (rnd-weighted rng items weights)))

(defn- rnd-shuffle
  "Deterministic shuffle."
  [rng coll]
  (loop [rng rng
         xs (vec coll)
         i (dec (count xs))]
    (if (neg? i)
      [xs rng]
      (let [[j rng2] (rnd-int rng (inc i))
            xs2 (assoc xs
                  i (xs j)
                  j (xs i))]
        (recur rng2 xs2 (dec i))))))

;; ------------------------------------------------------------
;; DEFAULT RNG STATE + SEEDING
;;
;; Everything above is pure ([value rng'] in, [value rng'] out) --
;; useful when a caller genuinely wants to carry its own RNG (e.g. an
;; isolated, reproducible stream). Everything below holds the current
;; RNG state in an atom and does the swap for you, which is what every
;; ordinary call site actually wants.
;; ------------------------------------------------------------

(defonce ^{:doc "Default RNG state, used by every fn below when no explicit atom is passed."}
  default-rng (atom (rnd-new (System/currentTimeMillis))))

(defn seed!
  "Reset an RNG atom (default-rng if omitted) to a fresh state from seed."
  ([seed] (seed! default-rng seed))
  ([rng-atom seed] (reset! rng-atom (rnd-new seed))))

(defmacro with-seed
  "Runs body with default-rng pinned to a deterministic sequence seeded
   by seed -- same seed, same output, every run. Restores whatever RNG
   state was there before once body completes, so this composes/nests.
   Safe to reach from anywhere, including from inside a core.wall
   algorithm running in a core.async voice -- default-rng is a plain
   atom, not a dynamic var, so its state is visible from any thread,
   not just the one that called with-seed.

   (with-seed 42 (repeatedly 5 #(rand-int 100)))"
  [seed & body]
  `(let [old# (deref default-rng)]
     (seed! default-rng ~seed)
     (try ~@body
          (finally (reset! default-rng old#)))))

(defn- step!
  [rng-atom f & args]
  (let [[result rng'] (apply f @rng-atom args)]
    (reset! rng-atom rng')
    result))

;; ------------------------------------------------------------
;; BASIC PRIMITIVES
;; ------------------------------------------------------------

(defn rand-double
  "Uniform double in [0,1), drawn from an RNG atom (default-rng if omitted)."
  ([] (rand-double default-rng))
  ([rng-atom] (step! rng-atom rnd-double)))

(defn rand-int
  "Uniform integer in [0,n), drawn from an RNG atom (default-rng if omitted)."
  ([n] (rand-int default-rng n))
  ([rng-atom n] (step! rng-atom rnd-int n)))

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
