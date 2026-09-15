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

   THE ELEVEN DIMENSIONS, and why each earned its place (every one traces
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
     :dependency   -- :standalone (free of PROJECT-SPECIFIC coupling,
                       confirmed by reading the source, not the
                       filename -- whether that source has literally
                       zero requires, e.g. algo.indisp.indispensability,
                       or requires other non-project leaf namespaces,
                       e.g. algo.rhythmic.rhythm requiring algo.random/
                       clojure.string, makes no difference to this value:
                       neither ever pulls in anything that couples a
                       caller to THIS project's own domain model) /
                       :project-coupled (requires core.wall/
                       core.domain.flat-domain/core.async-engine/
                       common.music-elements or similar -- the one
                       distinction that has ever actually driven a real
                       decision here: whether something CAN go in
                       algo.toolkit at all, a binary question, not a
                       three-way one -- see FIXED 2026-09-14 below).
     :numeric-type -- ADDED 2026-09-15. For a :scalar :output
                       specifically: :int/:float (the actual Clojure
                       type returned), or :any when the output type is
                       genuinely determined by the INPUT, not fixed by
                       the function itself (choose/weighted-choose/
                       markov return whatever type was in the
                       collection they were given -- not numeric at
                       all, in general). Orthogonal to :output itself,
                       which only says 'a scalar,' not which kind --
                       see the 2026-09-15 note below for why this was
                       added.
     :distribution-shape -- ADDED 2026-09-15. For a :random :producer
                       with a :scalar :output specifically: does it
                       impose a shape WITHIN an explicit, caller-
                       supplied [lo hi] bound (:bounded-range --
                       uniform/triangular/linear/arcsine/lo-emph/mean-
                       emph/hi-emph/rising/falling/rand-int/int-range/
                       int-rising/int-falling), or is it a NAMED
                       statistical family parameterized by shape params
                       instead -- mean/stddev, rate, alpha/beta, degrees
                       of freedom -- with no literal bound given at all
                       (:named-family -- normal/exponential/gamma/chi-
                       square/inverse-gamma/weibull/cauchy/student-t/
                       laplace/log-normal/beta).

   Every dimension's own value SET was determined by first reading real
   code across algo.toolkit/algo.random/algo.common (this session's own
   earlier work) and, separately, algo.indisp/algo.metric/algo.rhythmic/
   algo.melodic (a live survey done specifically for this namespace) --
   not designed abstractly first and fitted to functions afterward.
   *profiles* below classifies algo.toolkit's own public API COMPLETELY
   (all 105 functions, confirmed by a direct count against (ns-publics
   'algo.toolkit) filtered to fn? values -- one non-function data
   constant, species-counterpoint-default-rules, is deliberately NOT
   classified, since this taxonomy describes function BEHAVIOR and a
   bare config map has none. See the toolkit-specific section further
   down), since that's the one namespace this taxonomy was built to serve
   directly (algoline's own reusable-building-blocks toolkit). Beyond
   toolkit, it's a representative, evidence-based sample across
   algo.indisp/algo.metric/algo.rhythmic/algo.melodic -- not every
   function in the REST of algo/ (that would just re-derive the whole
   tree in table form) -- enough to exercise every dimension's own
   value set at least once and to make compatible?'s own producer/
   consumer matching genuinely useful there too, not enough to become a
   second copy of the source that goes stale the way a hand-maintained
   doc table would.

   FIXED 2026-09-14: :dependency used to have a THIRD value,
   :leaf-dependent ('requires only other standalone leaf namespaces'),
   sitting between :standalone and :project-coupled. In practice it was
   applied inconsistently, not just imprecisely: ~90 already-registered
   toolkit profiles (rand-double, weighted-shuffle, every algo.random-
   backed distribution, and the algo.rhythmic-backed additions) were
   classified :standalone even though their own defining namespace
   requires algo.random (a real dependency, by the letter of
   :leaf-dependent's own original definition) -- while three entries
   registered under algo.rhythmic's OWN symbols (euclidean-rhythm/
   pendulum-rhythm/genetic-rhythm) were classified :leaf-dependent for
   the identical reason. Both readings were individually defensible;
   applying them to different entries in the same registry was not.
   Resolved by checking what the dimension was actually FOR: every real
   'can this go in algo.toolkit' decision made across this whole
   session was a binary question (does it couple to core.wall/
   core.domain.flat-domain/core.async-engine/common.music-elements, or
   not) -- the finer zero-requires-vs-depends-on-another-leaf
   distinction never once drove an actual decision. :leaf-dependent was
   removed rather than redefined a third time; its three prior holders
   were reclassified :standalone, matching the meaning that value has
   actually had ~95% of the time all along.

   ADDED 2026-09-15: :numeric-type and :distribution-shape, after
   duplicate-profiles (below) surfaced a real, previously invisible
   fact live -- 24 distinct algo.toolkit functions shared one identical
   profile ({:input :scalar :output :scalar :in-type :primitive
   :out-type :primitive :role :producer :statefulness :stateless
   :determinism :random :granularity :per-event :dependency
   :standalone}), not the 12 continuous distributions an existing test
   already named. This taxonomy had no way to distinguish rand-int
   (returns an int) from uniform (returns a float), or triangular
   (a caller-supplied [lo hi] bound with a shape imposed inside it)
   from normal (a named family with no literal bound at all, just
   mean/stddev). Both are real Clojure-level/domain-semantic facts, the
   same standard every other dimension here was held to -- not invented
   abstractly. :numeric-type additionally needed a genuine :any value
   (not just leaving the dimension unclassified) for choose/weighted-
   choose/markov: their own output type is whatever was in the INPUT
   collection, not fixed by the function at all -- and this taxonomy's
   own existing convention is that an unclassified dimension means 'not
   yet determined,' which would have been actively misleading here,
   not just incomplete. Splits the 24-member group into four real
   subgroups: :int/:bounded-range (4: rand-int/int-range/int-rising/
   int-falling), :float/:bounded-range (9: uniform/triangular/linear/
   arcsine/lo-emph/mean-emph/hi-emph/rising/falling), :float/:named-
   family (11: the original continuous-distribution list minus
   uniform), and a separate, still-single-member-free :any group
   (choose/weighted-choose/markov, which differ from the 24 on :role/
   :input anyway so were never actually PART of that duplicate).

   EXTENDED 2026-09-15 (same day, follow-up): the :int/:bounded-range
   split above also surfaced a real asymmetry in algo.toolkit ITSELF,
   not just its classification -- rising/falling already had explicit
   integer siblings (int-rising/int-falling), but triangular/linear/
   arcsine/lo-emph/mean-emph/hi-emph, the OTHER six :float/:bounded-
   range members, did not. Added int-triangular/int-linear/int-
   arcsine/int-lo-emph/int-mean-emph/int-hi-emph to algo.random (same
   floor-after-computing pattern int-rising/int-falling already use),
   forwarded through algo.toolkit, and classified into
   bounded-range-int-producer here -- growing :int/:bounded-range from
   4 members to 10. rand-int's own argument shape ([n], meaning [0,n))
   was initially left as-is rather than changed to match int-range's
   own [lo hi] -- two real reasons, not just inertia: it directly
   mirrors clojure.core/rand-int's own name and semantics (algo.random
   `:refer-clojure`-excludes rand-int specifically to replace it with a
   seedable drop-in), and int-range's OWN implementation called
   rand-int internally at the time, expecting exactly this [0,n)-width
   shape. The second reason was then removed on purpose (same day):
   int-range rewritten to (int (Math/floor (uniform lo hi))) --
   conceptually int-uniform, the same floor-a-float-sibling pattern
   every int-* fn already uses, with no dependency on rand-int left at
   all. rand-int's own clojure.core-mirroring shape was the one
   remaining reason it hadn't been changed to [lo hi] -- a genuine
   values tension (project-internal consistency vs. consistency with
   Clojure's own established primitive). RESOLVED the same day, in
   favor of project-internal consistency: rand-int's own signature
   changed from ([n]/[rng-atom n], meaning [0,n)) to ([lo hi]/
   [rng-atom lo hi]), still built on the exact same underlying
   rnd-int/step! machinery (mod-based, no floating-point rounding) --
   only the external argument shape changed, computing the width
   internally ((- hi lo)) before delegating. This DOES diverge from
   clojure.core/rand-int's own [n] convention despite the shared name
   and the `:refer-clojure` exclusion that exists specifically to
   shadow it -- an accepted, deliberate tradeoff, not an oversight.
   Three real call sites depending on the old [n] shape were updated
   alongside it: algo.rhythmic.stochastic's two binary-pattern
   generators and algo.rhythmic.transform's mutation operator (all
   three now pass an explicit 0 as lo). :int/:bounded-range's own
   members are unaffected by this -- rand-int's classification
   (bounded-range-int-producer) never depended on its exact argument
   names, only on its shape (:input :scalar, one random scalar out).")

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
   :dependency   {:doc "Whether this function's own namespace requires
anything PROJECT-specific (core.wall/core.domain.flat-domain/
core.async-engine/common.music-elements or similar) -- deliberately a
binary distinction, not a three-way one; see this ns's own docstring,
'FIXED 2026-09-14', for why a third :leaf-dependent value was tried and
removed."
                  :values #{:standalone :project-coupled}}
   :numeric-type {:doc "For a :scalar :output: the actual Clojure type
returned (:int/:float), or :any when it's genuinely determined by the
input rather than fixed by the function itself. See this ns's own
docstring, 'ADDED 2026-09-15', for why."
                  :values #{:int :float :any}}
   :distribution-shape {:doc "For a :random :producer with a :scalar
:output: :bounded-range (imposes a shape within an explicit, caller-
supplied [lo hi]) or :named-family (a statistical family parameterized
by shape params, no literal bound given). See this ns's own docstring,
'ADDED 2026-09-15', for why."
                        :values #{:bounded-range :named-family}}})

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
;;; profile-groups / duplicate-profiles -- a CONSISTENCY CHECK, not a
;;; performance structure. compatible?/producers-for/consumers-for all
;;; filter on ONE dimension at a time (or run compatible? pairwise
;;; against a single fixed sym) -- an index on the WHOLE profile
;;; doesn't speed any of that up, and isn't meant to. What it
;;; DOES surface, that nothing above can: two or more symbols
;;; classified IDENTICALLY across every dimension at once, worth a
;;; second look (are they genuinely interchangeable, or did one just
;;; get copy-pasted from the other's registration without checking
;;; whether that's actually true). Computed fresh from *profiles* on
;;; every call, same discipline profiles-of/producers-for/consumers-for
;;; already use -- never a separately maintained index register-
;;; profile!/unregister-profile! could let go stale.
;;; ----------------------------------------------------------------------

(defn profile-groups
  "Invert *profiles* into {profile -> #{syms}} -- every distinct
   whole profile actually in use, mapped to the set of symbols
   classified with EXACTLY that profile (every dimension a given
   profile happens to use at once, not one)."
  []
  (reduce-kv (fn [groups sym prof] (update groups prof (fnil conj #{}) sym))
             {}
             @*profiles*))

(defn duplicate-profiles
  "The subset of profile-groups shared by MORE THAN ONE symbol -- the
   actual consistency-check view: every group here is two or more
   functions this taxonomy currently cannot tell apart at all."
  []
  (into {} (filter (fn [[_ syms]] (> (count syms) 1))) (profile-groups)))

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

;; algo.rhythmic -- surveyed live 2026-09-13: the WHOLE directory has
;; zero project-specific requires, only algo.random/algo.common.rotate/
;; numeric -- :standalone (not :project-coupled), same as every other
;; algo.random-backed function; see this ns's own docstring, "FIXED
;; 2026-09-14", for why these three were originally (and inconsistently)
;; marked :leaf-dependent instead.

(register-profile! 'algo.rhythmic.rhythm/euclidean-rhythm
  {:input :scalar :output :onset-grid :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.rhythmic.physical/pendulum-rhythm
  {:input :scalar :output :onset-timing :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.rhythmic.stochastic/genetic-rhythm
  {:input :coll :output :onset-grid :in-type :primitive :out-type :primitive
   :role :hybrid :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

;; algo.melodic -- mixed: counterpoint/slonimsky's pure half are
;; standalone, melody.clj is transitively project-coupled
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
;;; Full classification of algo.toolkit (2026-09-14, extended 2026-09-15)
;;; -- every one of its 105 public functions now has a profile (one
;;; non-function data constant, species-counterpoint-default-rules,
;;; deliberately excluded), not just the 10-fn representative
;;; sample above. Grouped by the same section headers algo.toolkit.clj
;;; itself uses, for easy cross-reference. Shared shapes factored into
;;; small template maps (assoc/merge overrides for the exceptions)
;;; rather than repeating a map dozens of times -- e.g. all eleven
;;; named-family distributions share one identical shape, and
;;; uniform/the six shaped distributions/their six int siblings share
;;; another (bounds in, one random scalar out, differing only in
;;; :numeric-type/:distribution-shape from the named-family group --
;;; see 'ADDED 2026-09-15'/'EXTENDED 2026-09-15' above).
;;; ----------------------------------------------------------------------

(def ^:private random-scalar-producer
  "bounds/params in, one random scalar out -- every algo.random-backed
   distribution (uniform/normal/exponential/.../lo-emph/mean-emph/hi-
   emph/int-range/rising/falling/int-rising/int-falling) shares exactly
   this shape. Deliberately left WITHOUT :numeric-type/:distribution-
   shape of its own -- every real user is one of the three more
   specific templates just below, which each add the two dimensions
   that split this one shape into its actual meaningful subgroups (see
   this ns's own docstring, 'ADDED 2026-09-15')."
  {:input :scalar :output :scalar :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone})

(def ^:private bounded-range-float-producer
  "random-scalar-producer's own shape, further specialized: a caller-
   supplied [lo hi] bound with SOME probability shape imposed within
   it, float-valued -- uniform/triangular/linear/arcsine/lo-emph/mean-
   emph/hi-emph/rising/falling."
  (assoc random-scalar-producer :numeric-type :float :distribution-shape :bounded-range))

(def ^:private bounded-range-int-producer
  "bounded-range-float-producer's own shape, but integer-valued --
   rand-int/int-range/int-rising/int-falling."
  (assoc bounded-range-float-producer :numeric-type :int))

(def ^:private named-family-float-producer
  "random-scalar-producer's own shape, further specialized: a NAMED
   statistical family parameterized by shape params (mean/stddev, rate,
   alpha/beta, degrees of freedom...) rather than a literal bound --
   normal/exponential/gamma/chi-square/inverse-gamma/weibull/cauchy/
   student-t/laplace/log-normal/beta."
  (assoc random-scalar-producer :numeric-type :float :distribution-shape :named-family))

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
   way a full reordering (random-coll-transformer) is. :numeric-type
   :any -- NOT unclassified -- since the returned value's actual type
   is whatever was in the input collection, not fixed by the function
   itself at all (could be numeric, a keyword, anything); this
   taxonomy's own convention is that an unclassified dimension means
   'not yet determined,' which would misrepresent this as an oversight
   rather than a genuine, permanent fact about these three functions."
  {:input :coll :output :scalar :in-type :primitive :out-type :primitive
   :role :consumer :statefulness :stateless :determinism :random
   :granularity :per-event :dependency :standalone :numeric-type :any})

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

(register-profile! 'algo.toolkit/rand-int bounded-range-int-producer)
(register-profile! 'algo.toolkit/choose random-coll-scalar-consumer)
(register-profile! 'algo.toolkit/weighted-choose random-coll-scalar-consumer)
(register-profile! 'algo.toolkit/shuffle random-coll-transformer)
(register-profile! 'algo.toolkit/markov
  (assoc random-coll-scalar-consumer :input :table))

;; -- Continuous distributions -- uniform is bounded-range (an explicit
;; [a b], no shape params beyond that); the other eleven are all named
;; statistical families (mean/stddev, rate, alpha/beta, degrees of
;; freedom...), no literal bound given -- see 'ADDED 2026-09-15' above --

(register-profile! 'algo.toolkit/uniform bounded-range-float-producer)
(doseq [sym '[normal exponential gamma chi-square inverse-gamma
              weibull cauchy student-t laplace log-normal beta]]
  (register-profile! (symbol "algo.toolkit" (name sym)) named-family-float-producer))

;; -- Discrete/collection helpers --

(register-profile! 'algo.toolkit/choose-n random-coll-transformer)
(register-profile! 'algo.toolkit/deep-shuffle random-coll-transformer)
(register-profile! 'algo.toolkit/choose-from random-coll-transformer)
(register-profile! 'algo.toolkit/sputter random-coll-transformer)

;; -- Shaped/skewed distributions -- bounded-range, same as uniform,
;; float-valued -- plus their integer counterparts, added 2026-09-15
;; for symmetry with rising/falling's own int-rising/int-falling
;; (see this ns's own docstring, 'ADDED 2026-09-15') --

(doseq [sym '[triangular linear arcsine lo-emph mean-emph hi-emph]]
  (register-profile! (symbol "algo.toolkit" (name sym)) bounded-range-float-producer))
(doseq [sym '[int-triangular int-linear int-arcsine int-lo-emph int-mean-emph int-hi-emph]]
  (register-profile! (symbol "algo.toolkit" (name sym)) bounded-range-int-producer))

;; -- Walks & composite generators --

(register-profile! 'algo.toolkit/int-range bounded-range-int-producer)
(register-profile! 'algo.toolkit/random-walk stateful-fn0-generator)
(doseq [sym '[rising falling]]
  (register-profile! (symbol "algo.toolkit" (name sym)) bounded-range-float-producer))
(doseq [sym '[int-rising int-falling]]
  (register-profile! (symbol "algo.toolkit" (name sym)) bounded-range-int-producer))
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

;;; ----------------------------------------------------------------------
;;; algo.indisp/algo.metric/algo.rhythmic/algo.melodic additions to
;;; algo.toolkit (2026-09-14) -- pulled in directly from the wider algo/
;;; tree (see algo.toolkit's own ns docstring for the dependency-
;;; checking discipline behind each), classified here the same way as
;;; every other toolkit addition.
;;; ----------------------------------------------------------------------

(def ^:private deterministic-onset-grid-producer
  "scalar params in, a deterministic 0/1 onset grid out -- the metric-
   structure generators (binary-decomposition/continued-fraction/
   modular-rhythm) and the deterministic algo.rhythmic ones (euclidean/
   fibonacci/prime)."
  {:input :scalar :output :onset-grid :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :deterministic
   :granularity :whole-structure :dependency :standalone})

;; -- Barlow indispensability (algo.indisp.indispensability) --

(doseq [sym '[indispensability normalize-weights normalized-indispensability
              tilt-probabilities power-law-probabilities]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-coll-transformer))
(register-profile! 'algo.toolkit/density-grid
  (assoc deterministic-coll-transformer :output :onset-grid))

;; -- Metric-structure pulse generators (algo.metric.metric) --

(doseq [sym '[binary-decomposition-rhythm continued-fraction-rhythm modular-rhythm]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-onset-grid-producer))

;; -- Rhythm-pattern generators (algo.rhythmic.rhythm) --

(doseq [sym '[euclidean-rhythm fibonacci-rhythm prime-rhythm]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-onset-grid-producer))
(register-profile! 'algo.toolkit/lindenmayer-rhythm
  (assoc deterministic-onset-grid-producer :input :table))
(register-profile! 'algo.toolkit/markov-rhythm
  (assoc deterministic-onset-grid-producer :input :table :output :coll :determinism :random))

;; -- Slonimsky melodic interpolation (standalone port) --

(doseq [sym '[mixed-polations infrapolate ultrapolate interpolate]]
  (register-profile! (symbol "algo.toolkit" (name sym)) deterministic-coll-transformer))

;; -- Species counterpoint (algo.melodic.counterpoint) --
;; species-counterpoint-default-rules is a plain data constant, not a
;; function -- deliberately NOT profiled here (see algo_dimensions_test/
;; every-toolkit-public-function-is-classified's own docstring for why).

(register-profile! 'algo.toolkit/species-counterpoint
  {:input :coll :output :coll :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

;; -- Pre-composed combinations --

(register-profile! 'algo.toolkit/weighted-pulse-choice
  (assoc random-coll-scalar-consumer :input :coll))
(register-profile! 'algo.toolkit/shuffled-euclidean
  {:input :scalar :output :infseq :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})
(register-profile! 'algo.toolkit/weighted-density-grid
  ;; deterministic, not random: indispensability/tilt-probabilities/
  ;; density-grid are all pure deterministic math, no RNG draw anywhere
  ;; in the chain -- confirmed by reading each, not assumed from the
  ;; "weighted" name.
  (assoc deterministic-coll-transformer :input :coll :output :onset-grid))

;;; ----------------------------------------------------------------------
;;; "The rest of algo.random" -- random-rhythm/generative-patch, the two
;;; domain-specific functions deliberately excluded from algo.toolkit's
;;; own re-export (see that ns's own docstring) and therefore the only
;;; parts of algo.random genuinely unclassified anywhere until now.
;;; ----------------------------------------------------------------------

(register-profile! 'algo.random/random-rhythm
  {:input :scalar :output :onset-timing :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateless :determinism :random
   :granularity :whole-structure :dependency :standalone})

(register-profile! 'algo.random/generative-patch
  {:input :none :output :fn0 :in-type :primitive :out-type :primitive
   :role :producer :statefulness :stateful-closure :determinism :random
   :granularity :per-event :dependency :standalone})
