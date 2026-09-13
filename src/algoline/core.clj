(ns algoline.core
  "A protocol-based composable-step mechanism -- an alternative design
   explored alongside (not a replacement for) the tree-shaped algo
   mechanism on the `algo` branch (core.compose/node/eval-node,
   core.wall's *pure-fn-registry*/*active-algo-trees*; see that
   branch's own CLAUDE.md 'Algo trees' section for the full design).
   Both explore the same underlying need -- small, reusable
   algorithms, composable into larger ones -- from opposite directions:
   that mechanism builds a TREE as the primary shape, with chaining
   (algo.common.reshape/chain-algo's own {:steps [...]}) assembled on
   top of nesting; this one builds a linear PIPELINE (Algoline) as the
   primary shape, with tree-like nesting available on top of it
   (AlgolineRef/aref, or embedding an Algoline directly as another
   one's own step, since Algoline is itself an IStep). Neither has
   superseded the other; this file is the `algoline` branch's own,
   separate exploration. As of 2026-09-12, no comparison verdict has
   been reached and no toolkit of reusable steps has been built yet
   (see this project's own memory: project_composable_functions_
   exploration.md) -- this file is the mechanism alone.

   IStep is the one abstraction everything else is built from:
   (execute step value dynamics) -> [new-value new-dynamics]. Two
   channels, not one -- `value` is whatever's actually flowing through
   the pipeline (the thing being transformed), `dynamics` is a
   separate, explicitly-threaded map (named parameters, or a running
   state a step can read AND write). This is a genuinely different
   split from the tree mechanism's own single `:model` map: there,
   state that evolves step-to-step has no designated home (existing
   generators stay closure-based specifically because that gap was
   never closed) -- here, `dynamics` IS that home, and ModelStep (below)
   is the step kind built specifically to read and write it, the same
   way a GUI's own live model would.

   Five step kinds, one protocol, chosen by what a step actually needs
   to see:
     FnStep       (step f)          -- (f value), dynamics untouched.
                                       The common case: a plain, single-
                                       value transform.
     DynamicStep  (dstep f & args)  -- (apply f value resolved-args),
                                       args resolved against dynamics
                                       first: dref pulls a named value
                                       straight out, aref pulls a named
                                       IStep out and EXECUTES it (its own
                                       output substituted in, not the
                                       IStep itself) -- this is how one
                                       step references another named
                                       thing, or a whole named sub-
                                       algoline, without hand-nesting.
     ContextStep  (context-step f)  -- (f value dynamics), full access
                                       to both, read-only in effect
                                       (its own return value becomes
                                       the new `value`; dynamics passes
                                       through unchanged regardless of
                                       what f does with its own copy).
     ModelStep    (model-step f)    -- (f value dynamics), f returns
                                       EITHER a bare new value (dynamics
                                       unchanged) OR [new-value new-
                                       dynamics] (both updated) -- the
                                       one step kind that can actually
                                       WRITE dynamics, not just read it.
     Algoline     (algoline & steps)-- a plain, ordered vector of steps,
                                       itself an IStep (so it nests),
                                       threading BOTH value and dynamics
                                       through each step in turn via a
                                       single reduce. `then` appends
                                       more steps, returning a new
                                       Algoline (steps are plain
                                       immutable data, not mutated).

   Failure policy: every step kind above THROWS eagerly on a genuine
   problem (a missing dref/aref target, a dynamic value that isn't
   actually an IStep, or whatever a step's own f itself throws) --
   right for composing/testing an algoline synchronously, where an
   immediate, loud failure beats a silently wrong result, the same way
   core.async-engine/validate-algo-name! throws at a play call itself,
   before anything starts. This is deliberately NOT switchable via a
   global flag (considered and rejected -- see SafeStep's own
   docstring for the full reasoning: the right behavior depends on
   WHERE a step runs, not a shared preference, and a live voice's own
   goroutine can be active at the same time as a synchronous REPL
   call, so one flag can't correctly serve both). Instead, `safe` wraps
   any IStep (one step, or a whole Algoline) so IT specifically
   degrades to a console warning and [value dynamics] passed through
   unchanged if it throws -- the local, per-step (or per-algoline)
   opt-in this needs, matching how core.wall/resolve-name (always
   degrades) and validate-algo-name! (always throws) already coexist
   as two separate functions in the sibling mechanism, never one
   function with a runtime-toggled mode.

   run/run-with-model are the two execution entry points: run against
   a static dynamics map (or none, defaulting to {}), returning just
   the final value; run-with-model against a live atom, threading and
   writing back whatever a ModelStep updated, returning the final
   value.

   The rest of this file (2026-09-12) closes the gaps found by pushing
   algoline through the same hard questions the tree mechanism already
   answered on the `algo` branch:
     root?/validate-root!  -- root-eligibility as a checked property
                               (produces {:pitches :duration}?), not a
                               declared flag -- adapted from the tree
                               side's own version, which needs no
                               external input since a node is self-
                               contained; an algoline's steps carry no
                               data of their own, so this needs a
                               representative sample value/dynamics to
                               run against.
     swap-step             -- a plain positional replace at a path, NOT
                               the tree side's own reconciling merge --
                               an algoline step is an ordinary closure,
                               not a named, inspectable :model map, so
                               there's no shared configuration to
                               reconcile between an old step and a new
                               one.
     *attached*/attach!/    -- live, per-path instances, mirroring
     detach!/active/           *active-algo-trees*'s own guarantee:
     active-all/run-active!    attach! ALWAYS mints its own fresh
                               dynamics atom, never accepting one from
                               the caller, so two different paths can
                               never alias the same live state even
                               when attached with the identical
                               algoline value.

   Added 2026-09-12, closing gaps found while directly comparing this
   mechanism against the tree side's own now-populated registry/GUI
   story:
     iref                   -- a value-INDEPENDENT sibling of aref,
                                executing the referenced step against a
                                fixed seed (default nil) instead of the
                                calling step's own ambient value -- the
                                structural fix for aref's own documented
                                double-counting trap, for the (common)
                                case where a fixed contribution, not a
                                context-dependent one, is what's wanted.
     patch-active!          -- merges values straight into path's own
                                live dynamics atom, the GUI-facing
                                symmetry counterpart to core.compose/
                                patch-active-tree! on the `algo` branch.
     *step-registry*/       -- name -> {:build :defaults :category
     register-step!/           :doc}, mirroring core.wall/
     build-step/steps/         *pure-fn-registry*'s shape as closely as
     steps-of-category         this mechanism's own shape allows --
                                see register-step!'s own docstring for
                                exactly where the analogy holds (step
                                CONSTRUCTION) and where it doesn't (no
                                swap-fn-style reconciliation, since a
                                step has no persistent :model to
                                reconcile).

   Still deliberately out of scope: what would actually invoke an
   Algoline during live playback -- wiring any of the above into
   core.wall/core.async-engine (the same open question the tree
   mechanism's own 'what drives a tick' is).")

;;; ----------------------------------------------------------------------
;;; Protocol
;;; ----------------------------------------------------------------------

(defprotocol IStep
  "A single step in an algoline."
  (execute [this value dynamics]
    "Run the step.
     - value : current flowing value
     - dynamics : map of named dynamic slots (can serve as a GUI model)
     Returns [new-value new-dynamics]"))

;;; ----------------------------------------------------------------------
;;; Markers for named dynamics
;;; ----------------------------------------------------------------------

(defrecord DynamicRef [name])

(defn dref
  "Named dynamic reference. Inserts the value stored under `name` as data."
  [name]
  (->DynamicRef name))

(defrecord AlgolineRef [name])

(defn aref
  "Named algoline reference. Looks up an IStep under `name` and executes
   it -- AGAINST THE SAME value/dynamics the calling step currently has,
   not a detached, value-independent computation. Confirmed live, worth
   being deliberate about: if the referenced step itself TRANSFORMS
   value (e.g. (step #(+ % 12))), and the calling dstep then ALSO
   combines that output with the current value (e.g. (dstep + (aref
   :octave))), the current value gets counted twice -- 70 and (+ 70 12)
   combined via + gives 152, not the 82 a fixed +12 contribution would.
   Use a value-INDEPENDENT step (e.g. (step (constantly 12)), ignoring
   its own value argument), or use iref instead (below), when aref's
   result is meant to be a fixed contribution rather than a further
   transform of the current value."
  [name]
  (->AlgolineRef name))

(defrecord IsolatedRef [name seed])

(defn iref
  "Named, value-INDEPENDENT algoline reference: looks up an IStep under
   `name` in dynamics and executes it against `seed` (default nil) --
   NOT the calling step's own ambient value the way aref does. This is
   the structural fix for aref's own documented double-counting trap
   (see aref's own docstring): a value-TRANSFORMING step referenced
   this way no longer needs to be rewritten into a value-independent
   one (the (step (constantly x)) workaround) to be safely used as a
   fixed contribution -- iref guarantees that regardless of what the
   referenced step actually does with its own value argument, since it
   never sees the calling step's ambient value at all.

   This doesn't replace aref -- it closes the specific failure mode
   (a composer forgetting the workaround and silently double-counting)
   by giving the two genuinely different needs two different, explicit
   names instead of one primitive quietly used for both:
     aref  -- the referenced step genuinely needs to see the calling
               step's own current value (a context-dependent
               sub-computation, e.g. a step that scales relative to
               whatever's currently flowing).
     iref  -- the referenced step's result should be a self-contained,
               fixed contribution, independent of the calling step's
               own value -- the common case for a named constant/offset
               stashed in dynamics for hot-swapping.
   (dstep + (iref :octave 0)) with :octave bound to (step #(+ % 12))
   correctly yields a fixed +12 contribution (70 -> 82), not 152 --
   confirmed live, not just reasoned about (see algoline-core-test).
   Note the explicit seed (0, not the default nil): iref removes the
   AMBIENT-VALUE double-counting, it doesn't make an arbitrary step
   safe to call with no meaningful input at all -- (+ nil 12) throws
   the same NullPointerException calling any fn with input it wasn't
   designed for would, seed or no seed. The default nil seed suits a
   step that ignores its own value entirely (e.g. (step (constantly
   12))); anything that actually reads its argument needs seed to be
   whatever THAT step's own contract expects."
  ([name] (->IsolatedRef name nil))
  ([name seed] (->IsolatedRef name seed)))

;;; ----------------------------------------------------------------------
;;; Step implementations
;;; ----------------------------------------------------------------------

(defrecord ^{:doc "Ordinary 1-arg function step. Only sees the flowing value."}
  FnStep [f]
  IStep
  (execute [_ value dynamics]
    [(f value) dynamics]))

(defrecord ^{:doc "Calls (apply f value resolved-args).

   - DynamicRef -- inserts the raw value from dynamics
   - AlgolineRef -- executes the IStep stored in dynamics AGAINST THE
     CALLING STEP'S OWN AMBIENT VALUE and inserts its result (see
     aref's own docstring for the double-counting trap this implies)
   - IsolatedRef -- executes the IStep stored in dynamics against its
     OWN seed value (default nil), never the ambient value, and inserts
     its result (see iref's own docstring -- the structural fix for
     that trap when a fixed contribution is what's actually wanted)"}
  DynamicStep [f fixed-args]
  IStep
  (execute [_ value dynamics]
    (let [resolved
          (mapv (fn [a]
                  (cond
                    (instance? DynamicRef a)
                    (if (contains? dynamics (:name a))
                      (get dynamics (:name a))
                      (throw (ex-info "Missing named dynamic"
                                       {:name (:name a)
                                        :available (keys dynamics)})))

                    (instance? AlgolineRef a)
                    (let [p (if (contains? dynamics (:name a))
                              (get dynamics (:name a))
                              (throw (ex-info "Missing algoline dynamic"
                                               {:name (:name a)
                                                :available (keys dynamics)})))]
                      (when-not (satisfies? IStep p)
                        (throw (ex-info "Dynamic value is not an IStep"
                                        {:name (:name a) :value p})))
                      (first (execute p value dynamics)))

                    (instance? IsolatedRef a)
                    (let [p (if (contains? dynamics (:name a))
                              (get dynamics (:name a))
                              (throw (ex-info "Missing algoline dynamic"
                                               {:name (:name a)
                                                :available (keys dynamics)})))]
                      (when-not (satisfies? IStep p)
                        (throw (ex-info "Dynamic value is not an IStep"
                                        {:name (:name a) :value p})))
                      (first (execute p (:seed a) dynamics)))

                    :else a))
                fixed-args)]
      [(apply f value resolved) dynamics])))

(defrecord ^{:doc "Calls (f value dynamics).
   Gives the function full access to both the flowing value and the dynamics map."}
  ContextStep [f]
  IStep
  (execute [_ value dynamics]
    [(f value dynamics) dynamics]))

(defrecord ^{:doc "Calls (f value dynamics).
   f may return either:
     - a new value -- dynamics stay the same
     - [new-value new-dynamics] -- both are updated
   Intended for cases where dynamics is used as a GUI model."}
  ModelStep [f]
  IStep
  (execute [_ value dynamics]
    (let [result (f value dynamics)]
      (if (and (vector? result) (= 2 (count result)))
        result
        [result dynamics]))))

(defrecord ^{:doc "A sequence of steps. Itself an IStep, so algolines can be nested."}
  Algoline [steps]
  IStep
  (execute [_ value dynamics]
    (reduce (fn [[v dyns] step]
              (execute step v dyns))
            [value dynamics]
            steps)))

(defrecord ^{:doc "Wraps inner (any IStep -- one step, or a whole Algoline, since
   Algoline is itself an IStep) so a thrown exception from it degrades to a
   console warning and [value dynamics] passed through UNCHANGED, rather than
   propagating -- the counterpart to core.wall/identity-algo's own no-op
   default for a wall fn that fails to resolve. Deliberately a WRAPPER, not a
   global switch: every other step type here throws eagerly by default,
   which is exactly right for composing/testing an algoline synchronously (a
   typo surfaces immediately, loudly, the same way core.async-engine/
   validate-algo-name! throws at a play call itself) -- but wrong for
   anything actually running inside a live voice's own core.async goroutine,
   where a thrown exception is silently swallowed rather than surfaced
   (confirmed elsewhere in this exact project, not hypothetical), which is a
   worse failure than degrading. A single mutable flag toggling between
   these two behaviors was considered and rejected: the right behavior
   depends on WHERE a step is actually running, not on a shared, global
   preference -- a synchronous, REPL-level call and a live voice's own
   goroutine can both be active at once, and one shared atom can't be
   correctly set for both simultaneously. Wrapping specific steps (or a
   whole algoline) in `safe` keeps the choice local to wherever it's
   actually needed, the same way core.wall/resolve-name (always degrades)
   and validate-algo-name! (always throws) are two separate functions, never
   one function with a runtime-toggled mode."}
  SafeStep [inner]
  IStep
  (execute [_ value dynamics]
    (try
      (execute inner value dynamics)
      (catch Exception e
        (println "algoline: a step threw --" (.getMessage e)
                  "-- passing [value dynamics] through unchanged")
        [value dynamics]))))

;;; ----------------------------------------------------------------------
;;; Construction helpers
;;; ----------------------------------------------------------------------

(defn step
  "Normal 1-arg function step."
  [f]
  (->FnStep f))

(defn dstep
  "Step that can inject named dynamic values (or nested algolines) as arguments.
   Examples:
     (dstep assoc :label (dref :label))
     (dstep + (aref :sub-algoline) 10)"
  [f & args]
  (->DynamicStep f (vec args)))

(defn context-step
  "Step whose function receives (value dynamics)."
  [f]
  (->ContextStep f))

(defn model-step
  "Step that may update the dynamics/model.
   f should return either a new value or [new-value new-dynamics]."
  [f]
  (->ModelStep f))

(defn algoline
  "Create an algoline from one or more steps."
  [& steps]
  (->Algoline (vec steps)))

(defn then
  "Append one or more steps. Returns a new Algoline."
  [p & more-steps]
  (->Algoline (into (:steps p) more-steps)))

(defn safe
  "Wrap inner (any IStep) so it degrades to a console warning and
   [value dynamics] passed through unchanged if it throws, instead of
   propagating -- see SafeStep's own docstring for the full reasoning.
   Use this for anything that will run inside a live voice's own
   goroutine; leave everything else throwing eagerly, same as today,
   for REPL/authoring-time use where an immediate, loud failure is
   exactly what's wanted. Wrapping a whole Algoline degrades the WHOLE
   thing to a no-op if ANY step inside throws -- wrap individual steps
   instead for per-step isolation."
  [inner]
  (->SafeStep inner))

;;; ----------------------------------------------------------------------
;;; Execution helpers
;;; ----------------------------------------------------------------------

(defn run
  "Execute an algoline with a static dynamics map.
   Returns only the final value."
  ([p initial]
   (run p initial {}))
  ([p initial dynamics]
   (first (execute p initial dynamics))))

(defn run-with-model
  "Execute an algoline against a live model (atom).
   Updates the atom with any changes made by model-steps.
   Returns the final flowing value."
  [p initial model-atom]
  (let [[new-value new-model] (execute p initial @model-atom)]
    (reset! model-atom new-model)
    new-value))

;;; ----------------------------------------------------------------------
;;; Same-type swap -- a plain replace, not a reconciling merge
;;; ----------------------------------------------------------------------

(defn swap-step
  "PURE: return a new Algoline with the step at path replaced by
   new-step. path is an ordinary assoc-in-style key sequence -- [:steps
   0] replaces this Algoline's own first step; [:steps 0 :steps 2]
   reaches the third step of a nested Algoline sitting as this
   Algoline's own first step, since a nested Algoline is just another
   plain value with its own :steps field.

   Deliberately NOT the tree mechanism's own swap-fn: that reconciles
   :model (keep whatever keys still apply, fill the rest from the new
   fn's own registered :defaults) because a tree node's configuration
   is a named, inspectable map. An algoline step is an ordinary Clojure
   closure/record -- there's no shared, named configuration to
   reconcile between the old step and the new one, so this is a plain
   replace. If the new step needs some of the old step's own captured
   state, thread it through dynamics explicitly (dref) rather than
   expecting it to be recovered automatically."
  [an-algoline path new-step]
  (assoc-in an-algoline path new-step))

;;; ----------------------------------------------------------------------
;;; Root-eligibility -- a checked property, not a declared flag
;;; ----------------------------------------------------------------------

(defn root?
  "Does running an-algoline against sample-value/sample-dynamics (both
   optional, default nil/{}) produce resolved-leaf-shaped output -- a
   map carrying both :pitches and :duration?

   Unlike the tree mechanism's own root? (checked against a SELF-
   CONTAINED node that needs no external input, since all its data
   already lives in :model), an algoline's own steps don't carry their
   own data -- value is always supplied fresh per run -- so checking
   root-eligibility here needs a REPRESENTATIVE sample input; there's
   nothing else to run it against. Same as the tree side's own root?,
   this genuinely RUNS an-algoline to check -- any real side effect a
   step has (a model-step's own dynamics write, a safe-wrapped step's
   console warning) fires once, for real, as a consequence of checking,
   not a hypothetical -- exactly the same tradeoff the tree mechanism
   already accepts for its own root?, not a new one introduced here."
  ([an-algoline] (root? an-algoline nil {}))
  ([an-algoline sample-value] (root? an-algoline sample-value {}))
  ([an-algoline sample-value sample-dynamics]
   (let [out (run an-algoline sample-value sample-dynamics)]
     (and (map? out) (contains? out :pitches) (contains? out :duration)))))

(defn validate-root!
  "Throw a clear, loud ex-info if an-algoline isn't root? against
   sample-value/sample-dynamics, else return an-algoline unchanged --
   the pre-flight check, mirroring core.async-engine/
   validate-algo-name!'s own discipline: fail immediately, before
   anything is attached/played, not silently later as wrong-shaped
   output somewhere downstream."
  ([an-algoline] (validate-root! an-algoline nil {}))
  ([an-algoline sample-value] (validate-root! an-algoline sample-value {}))
  ([an-algoline sample-value sample-dynamics]
   (when-not (root? an-algoline sample-value sample-dynamics)
     (throw (ex-info "This algoline does not produce resolved leaves (:pitches/:duration) and cannot be used as a root"
                      {:algoline an-algoline})))
   an-algoline))

;;; ----------------------------------------------------------------------
;;; Live, per-path attached instances -- never a shared/caller-supplied
;;; atom, so two different paths can never alias the same live dynamics
;;; ----------------------------------------------------------------------

(defonce ^{:doc "path -> {:algoline an-algoline :dynamics dynamics-atom},
one entry per CURRENTLY-ATTACHED, live instance, keyed by an opaque
caller-supplied path (a voice path, or whatever a future integration
uses as a stable per-instance identifier) -- NEVER a composer-chosen
name multiple callers might reuse. attach! ALWAYS mints its own fresh
atom here; it never accepts one from the caller, which is what
guarantees two different paths can never alias the same live dynamics,
even when attached with the identical algoline value and identical
initial dynamics -- the same guarantee core.compose/*active-algo-trees*
gives the tree mechanism, adapted to algoline's own shape (a
dynamics ATOM per instance, not a plain immutable tree value, since
dynamics here is the part that evolves in place across repeated runs).
^:dynamic so a test can bind a fresh, isolated instance for just its
own extent, same reasoning as core.registries' own vars."}
  ^:dynamic *attached* (atom {}))

(defn attach!
  "Register an-algoline under path with its OWN freshly-minted dynamics
   atom, seeded from initial-dynamics (default {}), validated loudly
   first against sample-value (default nil) via validate-root! -- a
   non-root-shaped algoline is rejected right here, before anything is
   stored. Returns path."
  ([path an-algoline] (attach! path an-algoline nil {}))
  ([path an-algoline sample-value] (attach! path an-algoline sample-value {}))
  ([path an-algoline sample-value initial-dynamics]
   (validate-root! an-algoline sample-value initial-dynamics)
   (swap! *attached* assoc path {:algoline an-algoline :dynamics (atom initial-dynamics)})
   path))

(defn detach!
  "Forget path's own attached instance -- never affects any OTHER
   path's own instance, even one built from the identical algoline
   value."
  [path]
  (swap! *attached* dissoc path)
  nil)

(defn active
  "path's own currently-attached {:algoline :dynamics} entry, or nil if
   nothing is attached there. :dynamics is the live atom itself --
   @(:dynamics (active path)) reads its current value."
  [path]
  (get @*attached* path))

(defn active-all
  "The raw {path -> {:algoline :dynamics}} map of every currently-
   attached instance, for a caller that genuinely needs all of them at
   once (a GUI listing everything currently running)."
  []
  @*attached*)

(defn run-active!
  "Run path's own attached algoline against initial, threading and
   updating its OWN dynamics atom -- affects ONLY this path's own
   instance, never any other, even one running the identical algoline
   value, since each lives under its own freshly-minted atom."
  [path initial]
  (let [{:keys [algoline dynamics]} (get @*attached* path)]
    (when-not algoline
      (throw (ex-info "No algoline attached at path" {:path path})))
    (run-with-model algoline initial dynamics)))

(defn patch-active!
  "Merge new-values into path's own currently-attached instance's live
   dynamics atom directly -- affects ONLY path's own instance, never
   any other, even one running the identical algoline value, since each
   lives under its own freshly-minted atom (same guarantee attach!
   itself gives). The GUI-facing symmetry counterpart to
   core.compose/patch-active-tree! on the `algo` branch: a slider/
   control bound to one named dynamics key can write straight through
   this rather than reaching into (:dynamics (active path)) by hand."
  [path new-values]
  (let [{:keys [dynamics]} (get @*attached* path)]
    (when-not dynamics
      (throw (ex-info "No algoline attached at path" {:path path})))
    (swap! dynamics merge new-values)
    nil))

;;; ----------------------------------------------------------------------
;;; Step registry -- organizing defaults for NAMED step constructors
;;; ----------------------------------------------------------------------

(defonce ^{:doc "name -> {:build (fn [opts] -> IStep) :defaults {...}
:category kw :doc doc}, mirroring core.wall/*pure-fn-registry* on the
`algo` branch as closely as this mechanism's own shape allows -- see
register-step!'s own docstring for exactly where the analogy holds and
where it doesn't (an algoline step has no persistent, inspectable
:model of its own the way a tree node does, so 'defaults' attach to
STEP CONSTRUCTION and to attach!'s own initial-dynamics seeding, not to
a swap-step reconciliation the way the tree's swap-fn works).
^:dynamic so a test can bind a fresh, isolated registry for just its
own extent, same reasoning as *attached* above."}
  ^:dynamic *step-registry* (atom {}))

(defn register-step!
  "Park build-fn (a function of one arg, opts -- a plain map -- that
   returns a real IStep, e.g. #(step (partial + (:n %)))) under name,
   usable thereafter via build-step. opts (all optional): :defaults (a
   map merged with a caller's own overrides at BUILD time, same role
   core.wall/register-pure-fn!'s own :defaults plays for a tree node's
   :model -- see build-step), :category (a keyword, e.g. :shape --
   what makes 'swap this step for another of the same kind' queryable
   at all, see steps-of-category), :doc.

   Genuinely narrower than the tree side's own registry, not just a
   port of it: a tree node's :model IS its own persistent, named
   configuration, so registered :defaults fill gaps in that SAME map
   forever (swap-fn reconciles it later). An algoline step is an
   ordinary closure/record with no persistent named configuration of
   its own -- whatever `opts` a named step was built with are baked
   into the closure at construction time and never inspectable again
   (see build-step). :defaults here fills gaps in `opts` at THAT one
   construction moment only; nothing analogous to swap-fn's later
   reconciliation exists for algoline (see swap-step's own docstring
   for why a plain replace is correct there instead)."
  ([name build-fn] (register-step! name build-fn {}))
  ([name build-fn {:keys [defaults category doc]}]
   (swap! *step-registry* assoc name
          {:build build-fn :defaults (or defaults {}) :category category :doc doc})
   name))

(defn unregister-step!
  "Forget name's parked step builder. Any step already built by calling
   build-step with name keeps existing as-is (build-step already
   returned a real, independent IStep value at the time it was
   called -- nothing about an already-built step references name
   again afterward), same 'unregistering only affects a LATER
   reference' invariant core.wall's own registries already have."
  [name]
  (swap! *step-registry* dissoc name)
  nil)

(defn step-defaults
  "The registered :defaults map for name, or {} if nothing's registered
   under it (never nil -- always safe to merge)."
  [name]
  (or (:defaults (get @*step-registry* name)) {}))

(defn build-step
  "Build a real IStep from name's registered build-fn, called with
   name's own registered :defaults merged with overrides (a plain map,
   defaults to {}) -- overrides win over defaults for any key both
   supply, same merge direction core.compose/node uses for a tree
   node's own :model. Throws if name isn't registered -- construction
   is exactly the moment a typo'd/unregistered name should surface
   loudly, matching this mechanism's own default fail-fast policy."
  ([name] (build-step name {}))
  ([name overrides]
   (if-let [{:keys [build defaults]} (get @*step-registry* name)]
     (build (merge defaults overrides))
     (throw (ex-info "algoline: no step registered as" {:name name})))))

(defn steps
  "With no arg: {name -> doc} for every registered step builder. With
   name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @*step-registry*))
  ([name] (:doc (get @*step-registry* name))))

(defn steps-of-category
  "Every registered step-builder name whose own :category is exactly
   category, as a plain vector -- what a same-type swap (GUI or REPL)
   offers as candidates for 'what else could this step be', the same
   role core.wall/pure-fns-of-category plays for a tree node."
  [category]
  (into [] (comp (filter (fn [[_ v]] (= category (:category v)))) (map key))
        @*step-registry*))
