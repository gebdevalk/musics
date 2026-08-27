;; core.clj
;; The low-level, pure RNG engine for this project: xorshift32
;; (rnd-new/rnd-step/rnd-double/rnd-int/rnd-choose/rnd-weighted/
;; rnd-markov/rnd-shuffle, each taking/returning [value rng']),
;; atom-backed seeding/state (default-rng, seed!, with-seed) instead
;; of the JVM's unseedable Math/random(), and a generic step! helper
;; that draws once from an RNG atom and returns just the value.
;; Deliberately an atom, not a dynamic var/binding: this project's
;; engine runs everything through core.async go-blocks, and a dynamic
;; binding isn't reliably conveyed across a parked goroutine resuming
;; on a different pool thread -- these fns need to work identically
;; whether called synchronously (material prep before play) or from
;; inside a core.wall algorithm (running per-voice, inside a
;; go-block).
;;
;; algo.random builds its own basic primitives (rand-double, rand-int,
;; choose, weighted-choose, shuffle, markov) and everything on top of
;; them directly on this namespace's public rnd-*/step!/default-rng --
;; nothing here is private to make room for that; only rnd-new/rnd-step
;; (never called from outside this namespace) stay defn-. The sibling
;; logistic.clj and lorenz.clj are NOT part of either -- chaotic maps,
;; deterministic given their own explicit state, no PRNG involved at
;; all.

(ns algo.random.core)

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

(defn rnd-double
  "Uniform double in [0,1)."
  [rng]
  (let [[i rng2] (rnd-step rng)]
    [(/ i (double 0xFFFFFFFF)) rng2]))

(defn rnd-int
  "Uniform integer in [0,n)."
  [rng n]
  (let [[i rng2] (rnd-step rng)]
    [(mod i n) rng2]))

;; ------------------------------------------------------------
;; ALEATORY OPERATORS
;; ------------------------------------------------------------

(defn rnd-choose
  "Uniform choice from items."
  [rng items]
  (let [[i rng2] (rnd-int rng (count items))]
    [(nth items i) rng2]))

(defn rnd-weighted
  "Weighted choice (prnd). Weights need not sum to 1 -- normalized
   against their own total."
  [rng items weights]
  (let [[u rng2] (rnd-double rng)
        total (reduce + weights)
        r (* total u)
        cum (reductions + weights)
        idx (count (take-while #(<= % r) cum))]
    [(nth items idx) rng2]))

(defn rnd-markov
  "Markov transition: table is {state {next-state prob}}."
  [rng table state]
  (let [transitions (table state)
        items (keys transitions)
        weights (vals transitions)]
    (rnd-weighted rng items weights)))

(defn rnd-shuffle
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

   (with-seed 42 (repeatedly 5 #(step! default-rng rnd-int 100)))"
  [seed & body]
  `(let [old# (deref default-rng)]
     (seed! default-rng ~seed)
     (try ~@body
          (finally (reset! default-rng old#)))))

(defn step!
  "Draw once from rng-atom via f (a pure [rng args...] -> [value rng']
   fn, e.g. rnd-double/rnd-int/rnd-choose/rnd-weighted/rnd-markov/
   rnd-shuffle), swap the atom to the resulting state, and return just
   the value."
  [rng-atom f & args]
  (let [[result rng'] (apply f @rng-atom args)]
    (reset! rng-atom rng')
    result))
