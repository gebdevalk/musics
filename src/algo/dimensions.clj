(ns algo.dimensions
  "A multi-dimensional taxonomy classifying functions across the whole
   algo/ tree -- not just algo.toolkit -- by what they actually take,
   produce, whether they hold state, whether they're random, how much
   they operate on at once, how project-coupled they are, and what role
   they play in a producer/consumer chain. Formalizes, as real data +
   code, the ad-hoc category work done by hand in algo-matrix.txt
   (toolkit only) -- generalized here after a live survey across
   algo.indisp/algo.metric/algo.rhythmic/algo.melodic confirmed the
   same shapes recur project-wide, plus surfaced two genuinely new ones
   neither algo-matrix.txt nor algo.txt's own earlier 7-dimension
   glossary had: an onset-TIMING-list output (real-valued seconds/beats,
   e.g. algo.rhythmic.physical/pendulum-rhythm) is NOT interchangeable
   with an onset-GRID output (a slot-indexed 0/1 vector, e.g.
   algo.metric.metric/modular-rhythm) without an explicit conversion
   step; and a :model output (a small, named config map that only makes
   sense as a SPECIFIC other function's required input, e.g.
   algo.melodic.melody/markov-train's {:order :transitions} feeding
   markov-generate) is a genuinely different shape from a plain
   Table/{state {next-state weight}} map (algo.random/markov-chain
   takes a bare table directly, no separate 'train' step at all).

   THE NINE DIMENSIONS, and why each earned its place (every one traces
   to a real function, not an invented category):
     :input        -- the Clojure-level shape a function's main
                       argument(s) take: :none/:scalar/:coll/:pattern/
                       :pair/:table/:model/:fn/:atom.
     :output       -- the Clojure-level shape it returns:
                       :scalar/:bool/:coll/:onset-grid/:onset-timing/
                       :infseq/:fn0/:fn1/:model.
     :in-type      -- the DOMAIN-SEMANTIC level of the input data,
                       orthogonal to :input's structural shape (two
                       functions can both take :coll yet mean something
                       completely different by it -- algo.toolkit/
                       weighted-shuffle's coll is bare primitives,
                       algo.common.split/split-leafs's coll is real
                       Leaf-shaped maps): :primitive/:coll/:leaf/:seq/
                       :par/:atom/:ctx-chain.
     :out-type     -- the same domain-semantic axis, for the output.
     :role         -- producer (needs no upstream data, just params) /
                       transformer (coll-like in, coll-like out) /
                       consumer (reduces to a terminal value nothing
                       else here consumes further, e.g. weighted-coin's
                       own bool) / hybrid (both, e.g. markov-generate
                       consumes a :model AND produces a fresh melody).
     :statefulness -- :stateless (no memory between calls) /
                       :stateful-closure (returns a closure holding its
                       own atom, e.g. cyclic-random/random-walk) /
                       :stateful-external (reads/writes a caller-
                       supplied atom/model directly).
     :determinism  -- :deterministic (same input, same output, always)
                       / :random (draws from algo.random, varies run to
                       run unless seeded).
     :granularity  -- :per-event (one value per call, e.g. rand-double,
                       or the returned fn of a :fn0/:fn1 producer) /
                       :whole-structure (one whole pattern/melody/
                       sequence per call, the overwhelming majority of
                       algo/rhythmic|melodic|indisp|metric) /
                       :whole-container (a project-specific seq-in/
                       seq-out batch, core.wall's own wall-fn contract).
     :dependency   -- :standalone (zero project-specific requires,
                       confirmed by reading the source, not the
                       filename) / :leaf-dependent (requires only OTHER
                       standalone leaf namespaces, e.g. all of
                       algo.rhythmic/ requires nothing but algo.random/
                       algo.common.rotate/numeric) / :project-coupled
                       (requires core.wall/core.domain.flat-domain/
                       common.music-elements or similar).

   Every dimension's own value SET was determined by first reading real
   code across algo.toolkit/algo.random/algo.common (this session's own
   earlier work) and, separately, algo.indisp/algo.metric/algo.rhythmic/
   algo.melodic (a live survey done specifically for this namespace) --
   not designed abstractly first and fitted to functions afterward.
   *profiles* below classifies algo.toolkit's own public API COMPLETELY
   (all 77 vars, confirmed by a direct count against (ns-publics
   'algo.toolkit) -- see the toolkit-specific section further down),
   since that's the one namespace this taxonomy was built to serve
   directly (algoline's own reusable-building-blocks toolkit). Beyond
   toolkit, it's a representative, evidence-based sample across
   algo.indisp/algo.metric/algo.rhythmic/algo.melodic -- not every
   function in the REST of algo/ (that would just re-derive the whole
   tree in table form) -- enough to exercise every dimension's own
   value set at least once and to make compatible?'s own producer/
   consumer matching genuinely useful there too, not enough to become a
   second copy of the source that goes stale the way a hand-maintained
   doc table would.")

;;; ----------------------------------------------------------------------
;;; The dimension space itself -- declarative, extensible
;;; ----------------------------------------------------------------------

(def dimension-space
  "dimension-key -> {:doc \"...\" :values #{...}}. The one place that
   declares what a valid profile is allowed to say -- register-profile!
   validates against this before accepting anything."
  {:input        {:doc "The Clojure-level shape of a function's main argument(s)."
                  :values #{:none :scalar :coll :pattern :pair :table :model :fn :atom}}
   :output       {:doc "The Clojure-level shape of a function's return value."
                  :values #{:scalar :bool :coll :onset-grid :onset-timing :infseq :fn0 :fn1 :model}}
   :in-type      {:doc "The domain-semantic level of the input data (orthogonal to :input)."
                  :values #{:primitive :coll :leaf :seq :par :atom :ctx-chain}}
   :out-type     {:doc "The domain-semantic level of the output data (orthogonal to :output)."
                  :values #{:primitive :coll :leaf :seq :par :atom :ctx-chain}}
   :role         {:doc "This function's place in a producer/consumer chain."
                  :values #{:producer :transformer :consumer :hybrid}}
   :statefulness {:doc "Whether/how this function carries state across calls."
                  :values #{:stateless :stateful-closure :stateful-external}}
   :determinism  {:doc "Same input, same output, always -- or does it draw from algo.random?"
                  :values #{:deterministic :random}}
   :granularity  {:doc "How much one call actually operates over."
                  :values #{:per-event :whole-structure :whole-container}}
   :dependency   {:doc "How project-coupled this function's own namespace is."
                  :values #{:standalone :leaf-dependent :project-coupled}}})

(defn dimension-names [] (set (keys dimension-space)))

(defn valid-value?
  "true if v is a legal value for dim -- false for an unknown dim too,
   not just an unknown value, so a typo'd dimension key never silently
   passes validation."
  [dim v]
  (contains? (get-in dimension-space [dim :values]) v))

(defn validate-profile!
  "Throw a clear ex-info if profile uses an unknown dimension, or a
   known dimension with a value outside its own declared set -- fail
   loudly at registration time, not silently later when a query quietly
   returns nothing for a mistyped keyword."
  [profile]
  (doseq [[dim v] profile]
    (when-not (contains? dimension-space dim)
      (throw (ex-info "algo.dimensions: unknown dimension" {:dimension dim :profile profile})))
    (when-not (valid-value? dim v)
      (throw (ex-info "algo.dimensions: invalid value for dimension"
                       {:dimension dim :value v :valid (get-in dimension-space [dim :values]) :profile profile}))))
  profile)

;;; ----------------------------------------------------------------------
;;; The profile registry -- fully-qualified symbol -> profile map
;;; ----------------------------------------------------------------------

(defonce ^{:doc "sym -> profile map, one entry per classified function.
^:dynamic so a test can bind a fresh, isolated registry for just its
own extent."}
  ^:dynamic *profiles* (atom {}))

(defn register-profile!
  "Classify sym (a fully-qualified symbol, e.g. 'algo.toolkit/shuffle)
   with profile (a map from dimension-key to one of its declared
   values -- need not cover every dimension; an unclassified dimension
   just means 'not yet determined for this fn', not 'not applicable').
   Validated against dimension-space before being accepted."
  [sym profile]
  (validate-profile! profile)
  (swap! *profiles* assoc sym profile)
  sym)

(defn unregister-profile! [sym] (swap! *profiles* dissoc sym) nil)

(defn profile
  "sym's own registered profile, or nil if unclassified."
  [sym]
  (get @*profiles* sym))

(defn profiles
  "The raw {sym -> profile} map of everything currently classified."
  []
  @*profiles*)

(defn profiles-of
  "Every classified sym whose own dim equals v, as a plain vector --
   e.g. (profiles-of :statefulness :stateful-closure) lists every
   generator that hides state in a closure, regardless of which
   algo/ directory it actually lives in."
  [dim v]
  (into [] (comp (filter (fn [[_ p]] (= v (get p dim)))) (map key)) @*profiles*))

;;; ----------------------------------------------------------------------
;;; Producer/consumer compatibility -- the compatible? relation
;;; ----------------------------------------------------------------------

(def ^:private direct-shape-matches
  "output value -> set of input values it feeds with NO glue at all --
   the producer's return value is directly usable as the whole
   argument the consumer expects."
  {:coll         #{:coll}
   :onset-grid   #{:coll}
   :onset-timing #{:coll}
   :infseq       #{:coll}
   :scalar       #{:scalar}
   :model        #{:model}})

(def ^:private glue-shape-matches
  "output value -> {input value -> one-line description of the
   standard glue needed} -- combinable, but not a bare pass-through."
  {:fn0 {:coll "wrap with (repeatedly n producer)"}
   :fn1 {:coll "wrap with (map producer (range n)) or (map producer targets)"}})

(defn shape-compatible
  "How (if at all) an output shape feeds an input shape: :direct (no
   glue needed), :glue (needs the standard piece named in glue-shape-
   matches), or nil (doesn't combine as a direct data pipe at all --
   may still matter to a caller's own control flow, e.g. a :bool)."
  [out-shape in-shape]
  (cond
    (contains? (get direct-shape-matches out-shape #{}) in-shape) :direct
    (contains? (get glue-shape-matches out-shape {}) in-shape) :glue
    :else nil))

(defn compatible?
  "Can producer-sym's own output feed consumer-sym's own input? Checks
   BOTH axes -- the Clojure-level shape (shape-compatible, above) AND
   the domain-semantic level (:out-type must equal :in-type -- a :coll
   of real Leaf-shaped maps and a :coll of bare pitch integers are both
   'shape :coll' but are NOT interchangeable data). Returns the same
   :direct/:glue/nil shape-compatible does, or nil immediately if
   either sym is unclassified or the domain types don't match."
  [producer-sym consumer-sym]
  (when-let [p (profile producer-sym)]
    (when-let [c (profile consumer-sym)]
      (when (= (:out-type p) (:in-type c))
        (shape-compatible (:output p) (:input c))))))

(defn producers-for
  "Every classified sym that can feed consumer-sym directly or via
   glue, as a vector of [sym match-kind] pairs."
  [consumer-sym]
  (into [] (keep (fn [sym] (when-let [m (compatible? sym consumer-sym)] [sym m])))
        (keys (profiles))))

(defn consumers-for
  "Every classified sym that producer-sym can feed directly or via
   glue, as a vector of [sym match-kind] pairs."
  [producer-sym]
  (into [] (keep (fn [sym] (when-let [m (compatible? producer-sym sym)] [sym m])))
        (keys (profiles))))

;;; ----------------------------------------------------------------------
;;; Seed data -- a representative, evidence-based sample across every
;;; algo/ directory surveyed, not an exhaustive catalog of the whole tree
;;; ----------------------------------------------------------------------

;; algo.toolkit -- general-purpose building blocks (this session's own
;; earlier, deep survey; see algo-matrix.txt for the full category work
;; this generalizes)

(register-profile! 'algo.toolkit/weighted-shuffle
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.toolkit/rand-double
  {:input :none :output :scalar :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(register-profile! 'algo.toolkit/cyclic-random
  {:input :coll :output :fn0 :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateful-closure :determinism :random
   :granularity :per-event :dependency :standalone})

(register-profile! 'algo.toolkit/smooth-noise
  {:input :scalar :output :fn1 :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(register-profile! 'algo.toolkit/weighted-coin
  {:input :scalar :output :bool :in-type :primitive :out-type :primitive
   :role :consumer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(register-profile! 'algo.toolkit/only
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.toolkit/poisson-events
  {:input :scalar :output :onset-timing :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.toolkit/smooth
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.toolkit/color-talea
  {:input :pair :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.toolkit/split-leafs
  {:input :coll :output :coll :in-type :leaf :out-type :leaf
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

;; algo.indisp -- pure math, zero requires, whole-structure-at-once

(register-profile! 'algo.indisp.indispensability/indispensability
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.indisp.indispensability/density-grid
  {:input :coll :output :onset-grid :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

;; algo.metric -- same shape family as indisp, zero requires

(register-profile! 'algo.metric.metric/modular-rhythm
  {:input :scalar :output :onset-grid :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

;; algo.rhythmic -- surveyed live (2026-09-14): the WHOLE directory has
;; zero project-specific requires, only algo.random/algo.common.rotate/
;; numeric -- leaf-dependent, not standalone, but never project-coupled

(register-profile! 'algo.rhythmic.rhythm/euclidean-rhythm
  {:input :scalar :output :onset-grid :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :leaf-dependent})

(register-profile! 'algo.rhythmic.physical/pendulum-rhythm
  {:input :scalar :output :onset-timing :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :leaf-dependent})

(register-profile! 'algo.rhythmic.stochastic/genetic-rhythm
  {:input :coll :output :onset-grid :in-type :primitive :out-type :primitive
   :role :hybrid :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :leaf-dependent})

;; algo.melodic -- mixed: counterpoint/slonimsky's pure half are leaf-
;; dependent/standalone, melody.clj is transitively project-coupled
;; (algo.common.pitch -> common.music-elements)

(register-profile! 'algo.melodic.melody/markov-train
  {:input :coll :output :model :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :project-coupled})

(register-profile! 'algo.melodic.melody/markov-generate
  {:input :model :output :coll :in-type :primitive :out-type :primitive
   :role :hybrid :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :project-coupled})

(register-profile! 'algo.melodic.slonimsky/interpolate
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

;;; ----------------------------------------------------------------------
;;; Full classification of algo.toolkit (2026-09-14) -- every one of its
;;; 77 public vars now has a profile, not just the 10-fn representative
;;; sample above. Grouped by the same section headers algo.toolkit.clj
;;; itself uses, for easy cross-reference. Shared shapes factored into
;;; small template maps (assoc/merge overrides for the exceptions)
;;; rather than repeating an 8-key map dozens of times -- e.g. all 12
;;; continuous distributions and all 6 shaped distributions are the
;;; identical shape (bounds in, one random scalar out).
;;; ----------------------------------------------------------------------

(def ^:private random-scalar-producer
  "bounds/params in, one random scalar out -- every algo.random-backed
   distribution (uniform/normal/exponential/.../lo-emph/mean-emph/hi-
   emph/int-range/rising/falling/int-rising/int-falling) shares exactly
   this shape."
  {:input :scalar :output :scalar :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(def ^:private deterministic-scalar-transformer
  "scalar(s) in, one deterministically-derived scalar out -- the pure
   math utilities (gcd/lcm/clamp/round-to/farey/the trig family/...)."
  {:input :scalar :output :scalar :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :deterministic
   :granularity :per-event :dependency :standalone})

(def ^:private deterministic-scalar-producer
  "Like random-scalar-producer's own shape, but deterministic -- the
   periodic samplers (cosr/sinr/trianglr/squarr/sawr/tanr): given the
   same idx/amp/base/period, always the same value, no randomness at
   all, yet still 'generate a value from parameters alone' the way a
   producer does rather than transforming given data."
  (assoc random-scalar-producer :role :producer :determinism :deterministic))

(def ^:private random-coll-transformer
  "a collection in, a related/reordered/resampled collection out,
   drawing randomness -- shuffle/choose-n/deep-shuffle/choose-from/
   sputter and friends."
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :transformer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

(def ^:private deterministic-coll-transformer
  "Like random-coll-transformer's own shape, but deterministic -- the
   z-filter family (smooth/momentum/memory/smooth-intervals/interval-
   gain/pc-smooth/z-filter itself) and rotate."
  (assoc random-coll-transformer :determinism :deterministic))

(def ^:private random-coll-scalar-consumer
  "a collection (+ params) in, ONE terminal scalar out, drawing
   randomness -- choose/weighted-choose/markov: the picked/transitioned
   value is meant for a caller's own use, not a further data pipe the
   way a full reordering (random-coll-transformer) is."
  {:input :coll :output :scalar :in-type :primitive :out-type :primitive
   :role :consumer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(def ^:private stateful-fn0-generator
  "params in, a 0-arg STATEFUL generator closure out -- random-walk/
   biased-walk/markov-chain (cyclic-random itself already classified
   above, same shape)."
  {:output :fn0 :in-type :primitive :out-type :primitive :role :producer
   :statefulness :stateful-closure :determinism :random
   :granularity :per-event :dependency :standalone})

;; -- Shuffle family (cycle-*/take-cycle-*) --

(register-profile! 'algo.toolkit/cycle-shuffle
  (assoc random-coll-transformer :output :infseq))
(register-profile! 'algo.toolkit/take-cycle-shuffle
  (assoc random-coll-transformer :input :coll))
(register-profile! 'algo.toolkit/cycle-weighted-shuffle
  (assoc random-coll-transformer :output :infseq))
(register-profile! 'algo.toolkit/take-cycle-weighted-shuffle
  random-coll-transformer)
(register-profile! 'algo.toolkit/cycle-deep-shuffle
  (assoc random-coll-transformer :output :infseq))
(register-profile! 'algo.toolkit/take-cycle-deep-shuffle
  random-coll-transformer)

;; -- Basic primitives --

(register-profile! 'algo.toolkit/rand-int random-scalar-producer)
(register-profile! 'algo.toolkit/choose random-coll-scalar-consumer)
(register-profile! 'algo.toolkit/weighted-choose random-coll-scalar-consumer)
(register-profile! 'algo.toolkit/shuffle random-coll-transformer)
(register-profile! 'algo.toolkit/markov
  (assoc random-coll-scalar-consumer :input :table))

;; -- Continuous distributions -- all 12 the identical shape --

(doseq [sym '[uniform normal exponential gamma chi-square inverse-gamma
              weibull cauchy student-t laplace log-normal beta]]
  (register-profile! (symbol "algo.toolkit" (name sym)) random-scalar-producer))

;; -- Discrete/collection helpers --

(register-profile! 'algo.toolkit/choose-n random-coll-transformer)
(register-profile! 'algo.toolkit/deep-shuffle random-coll-transformer)
(register-profile! 'algo.toolkit/choose-from random-coll-transformer)
(register-profile! 'algo.toolkit/sputter random-coll-transformer)

;; -- Shaped/skewed distributions -- same shape as continuous --

(doseq [sym '[triangular linear arcsine lo-emph mean-emph hi-emph]]
  (register-profile! (symbol "algo.toolkit" (name sym)) random-scalar-producer))

;; -- Walks & composite generators --

(register-profile! 'algo.toolkit/int-range random-scalar-producer)
(register-profile! 'algo.toolkit/random-walk stateful-fn0-generator)
(doseq [sym '[rising falling int-rising int-falling]]
  (register-profile! (symbol "algo.toolkit" (name sym)) random-scalar-producer))
(register-profile! 'algo.toolkit/biased-walk stateful-fn0-generator)
(register-profile! 'algo.toolkit/smooth-walk
  (assoc stateful-fn0-generator :output :fn1))

;; -- Event generation + Markov chain --

(register-profile! 'algo.toolkit/markov-chain
  (assoc stateful-fn0-generator :input :table))

;; -- Math/number utilities --

(register-profile! 'algo.toolkit/gcd deterministic-scalar-transformer)
(register-profile! 'algo.toolkit/lcm deterministic-scalar-transformer)
(register-profile! 'algo.toolkit/lcm-multiple
  (assoc deterministic-scalar-transformer :input :coll))
(register-profile! 'algo.toolkit/rotate
  (assoc deterministic-coll-transformer :input :coll))
(doseq [sym '[clamp clamp-optional closest-to round-to scale-range]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-scalar-transformer))
(doseq [sym '[cosr sinr trianglr squarr sawr tanr]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-scalar-producer))
(register-profile! 'algo.toolkit/farey deterministic-scalar-transformer)

;; -- Voice-splitting canon --

(register-profile! 'algo.toolkit/split
  (assoc deterministic-coll-transformer :input :coll))
(register-profile! 'algo.toolkit/split-leaf-voice
  (assoc deterministic-coll-transformer :in-type :leaf :out-type :leaf))

;; -- Isorhythm --

(register-profile! 'algo.toolkit/zip-parts
  (assoc deterministic-coll-transformer :input :model))

;; -- Z-filter recurrence --

(doseq [sym '[z-filter momentum memory smooth-intervals interval-gain pc-smooth]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-coll-transformer))
