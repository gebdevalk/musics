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
   value. Nothing here integrates with core.wall/core.async-engine yet
   -- what would actually invoke an Algoline during live playback (the
   same open question the tree mechanism's own 'what drives a tick'
   is) is deliberately out of scope for now.")

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
   its own value argument) when aref's result is meant to be a fixed
   contribution rather than a further transform of the current value."
  [name]
  (->AlgolineRef name))

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
   - AlgolineRef -- executes the IStep stored in dynamics and inserts its result"}
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
