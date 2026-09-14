(ns algoline-intercepted.core
  "A SIMPLER rebuild of algoline's own idea (small, composable steps,
   threading shared state through an ordered pipeline), on Pedestal/
   re-frame's INTERCEPTOR shape -- one shared context map, not
   algoline.core's own IStep protocol + two channels + five step
   records + three reference-marker records. Originally explored
   alongside (not replacing) algoline.core -- see algo-composition.txt
   section 6 for the design discussion, and section 7e/verdict for why
   algoline.core was later removed from the tree entirely (2026-09-14):
   this namespace is now the ONE surviving composable-function
   mechanism, not one of two. Every reference to algoline.core below is
   historical -- describing the design this collapsed away from -- not
   a pointer to a file that still exists.

   THE POINT OF THIS FILE IS THE COLLAPSE, NOT JUST THE CONTEXT SHAPE.
   A first pass at this (2026-09-13) ported algoline.core's entire
   five-step-kind, three-reference-kind API 1:1 onto the interceptor
   shape and changed nothing about its PUBLIC surface -- a fair,
   direct complaint: that's an internal rewrite, not a simpler
   solution. This version actually acts on the fact (already true, and
   already stated, in that first pass) that FnStep/DynamicStep/
   ContextStep/ModelStep were NEVER structurally different -- only
   which parts of a single value each one happened to read or write:

     ONE step constructor, not four. (step f) -- f receives (value
     state) always, and returns EITHER a bare new value (state
     unchanged) OR [new-value new-state] (both updated). Ignore
     state for a plain transform; read it for a parameterized one;
     return a pair to write it forward. There is no separate dstep/
     context-step/model-step anymore -- every one of the old four
     cases is just a different way of using this one function.
     (Renamed from `dynamics` 2026-09-14: this is data genuinely
     THREADED THROUGH the algoline via reduce, read and potentially
     rewritten by every step in turn, not a fixed, loaded-once
     configuration -- `dynamics` also collided with this project's own
     unrelated musical-dynamics vocabulary (!mf/!ff/DynamicMark), and
     `context` was already core.domain.context's own name.)

     ONE reference function, not three, as of 2026-09-14 -- ref
     replaces dref/aref/iref (all now REMOVED, not kept alongside it).
     (ref state name value) looks up name in state and branches
     on WHAT'S STORED there, not on which of three functions the
     caller remembered to pick: a plain value is returned as-is (old
     dref); an ordinary interceptor is run against value, the calling
     step's own ambient value (old aref -- same double-counting risk
     this always had, for the same reason); a (detached ...)-wrapped
     interceptor is run against its OWN seed instead, never the
     ambient value (old iref). detached is a step-AUTHORING-time
     wrapper, the same additive pattern safe already uses in this file
     -- (detached (step (constantly 12))) marks a step as a fixed
     contribution ONCE, when it's written, rather than requiring every
     later (ref ...) call site to remember whether THIS particular
     reference needs the ambient-value form or the seeded form. This
     is what actually closes aref's own double-counting trap at its
     root: the original bug was a caller forgetting a per-call choice;
     moving that choice to the step's own author, made once, removes
     the repeated decision entirely -- iref's own fix (a second
     function alongside aref) only gave callers a correct OPTION, not
     a way to stop needing to remember it.

   An interceptor is either {:enter (fn [ctx] -> ctx)} (a leaf) or
   {:steps [interceptor ...]} (a nested chain, what `algoline` builds)
   -- itself runnable, so one algoline nests inside another for free.
   :steps is read FRESH on every run (never captured in a closure),
   which is what makes swap-step a genuine, observable patch.

   safe, run/run-with-state, swap-step, root?/validate-root!,
   *attached*/attach!/detach!/active/active-all/run-active!/
   patch-active!, and *step-registry*/register-step!/build-step/steps/
   steps-of-category all keep the same reasoning algoline.core's own
   docstring already gives for each -- none of that machinery was ever
   part of the complaint (each one does something genuinely distinct;
   collapsing them would lose real capability, not ceremony).

   GUI ACCESSIBILITY (added 2026-09-14, ahead of an actual GUI
   consuming any of it, on the strength of 'we are going to need it'):
   the live-instance machinery above already let a GUI READ/WRITE state
   (current-state/patch-active!) and LIST what's attached (active-all),
   but had two real gaps closed here, both scoped narrowly (declarative
   metadata only -- NOT the fuller, nested-pure-computation param-node
   idea discussed and deliberately deferred, since nothing yet needs
   it):
     current-state       -- the read-side symmetry patch-active! never
                             had: @(:state (active path)) without a GUI
                             needing to know state lives behind an atom.
     *controls*/          -- DECLARES which state keys a control panel
     declare-controls!/      should render, and how (:label/:min/:max/
     controls-for             :default/:doc) -- a schema layered ON TOP
                             of state, not a new storage mechanism;
                             state can hold undeclared keys (just not
                             offered as controls), a declaration can
                             exist for a key state doesn't currently
                             have. Cleaned up by detach!, so a later
                             attach! at the same path never inherits a
                             stale declaration.
     build-step/            -- build-step now stamps its own built
     step-origin               interceptor with WHICH registered name
                             built it (namespaced keys, invisible to
                             run-one/interceptor?) -- without this, a
                             live algoline's own :steps are anonymous
                             closures with no way for a GUI to label
                             'Stage 2: tilt-shape' or offer
                             steps-of-category's own alternatives as
                             swap-step targets for that position. A
                             bare (step f) built by hand still has no
                             origin (step-origin returns nil) -- this
                             only applies to steps actually built
                             through the registry.")

;;; ----------------------------------------------------------------------
;;; Core engine -- private. A composer never calls these directly; they
;;; only ever reach for step/algoline/run/attach!, below.
;;; ----------------------------------------------------------------------

(declare execute)
(declare ^:dynamic *controls*)

(defn- interceptor?
  [x]
  (and (map? x) (or (contains? x :enter) (contains? x :steps))))

(defn- run-one
  [ctx interceptor]
  (if (contains? interceptor :steps)
    (execute ctx (:steps interceptor))
    ((:enter interceptor) ctx)))

(defn- execute
  [ctx interceptors]
  (reduce run-one ctx interceptors))

(defn- state-of [ctx] (dissoc ctx :value))

;;; ----------------------------------------------------------------------
;;; The one step constructor
;;; ----------------------------------------------------------------------

(defn step
  "The one step constructor. f receives (value state) and returns
   EITHER a bare new value (state unchanged) OR [new-value
   new-state] (both updated) -- covers every one of algoline.core's
   old FnStep/DynamicStep/ContextStep/ModelStep cases:
     (step (fn [v _] (inc v)))                    ;; plain transform
     (step (fn [v s] (+ v (ref s :amount v))))      ;; parameterized
     (step (fn [v s] [v (update s :count (fnil inc 0))]))  ;; writes state"
  [f]
  {:enter (fn [ctx]
            (let [result (f (:value ctx) (state-of ctx))]
              (if (and (vector? result) (= 2 (count result)))
                (let [[v s] result] (assoc s :value v))
                (assoc ctx :value result))))})

(defn algoline
  "Create an algoline (a nested chain) from one or more steps."
  [& steps]
  {:steps (vec steps)})

(defn then
  "Append one or more steps. Returns a new algoline; the original is
   untouched (steps are plain immutable data)."
  [p & more-steps]
  {:steps (into (:steps p) more-steps)})

(defn safe
  "Wrap inner (any interceptor -- one step, or a whole algoline) so a
   thrown exception from it degrades to a console warning and ctx
   passed through UNCHANGED, rather than propagating -- a per-step
   opt-in, never a global switch (the right behavior depends on WHERE a
   step runs, not a shared preference)."
  [inner]
  {:enter (fn [ctx]
            (try
              (run-one ctx inner)
              (catch Exception e
                (println "algoline-intercepted: a step threw --" (.getMessage e)
                          "-- passing context through unchanged")
                ctx)))})

;;; ----------------------------------------------------------------------
;;; Named references -- ONE function, not three (2026-09-14 collapse of
;;; dref/aref/iref). A composer calls ref directly, inline, inside an
;;; ordinary (fn [v d] ...) -- what it does depends on WHAT'S STORED
;;; under the name, decided once by whoever built that value (plain vs.
;;; interceptor vs. detached-wrapped interceptor), never by the caller
;;; picking the right function out of several.
;;; ----------------------------------------------------------------------

(defn detached
  "Wrap inner (any interceptor) so ref (below) runs it against its own
   seed (default nil) instead of the calling step's own ambient value
   -- the AUTHOR-TIME fix for the double-counting trap a value-
   transforming interceptor has when referenced against an ambient
   value it wasn't meant to see: (+ ambient (run inner ambient)) counts
   the current value twice if inner ALSO transforms it. Wrap the step
   itself, once, when it's written -- (detached (step (constantly
   12))) -- rather than leaving every future (ref ...) call site to
   remember whether this particular reference needs the ambient-value
   form or the seeded form. The default nil seed only suits an
   interceptor that ignores its own value entirely; one that actually
   reads it needs its own correct seed passed explicitly (detached
   inner seed)."
  ([inner] (detached inner nil))
  ([inner seed] {::detached {:inner inner :seed seed}}))

(defn- detached-info [v]
  (when (and (map? v) (contains? v ::detached))
    (::detached v)))

(defn ref
  "Look up name in state and branch on what's actually stored there:
     - a plain value                       -> returned as-is
     - an ordinary interceptor             -> run against value, the
                                               calling step's own
                                               ambient value (the same
                                               double-counting risk a
                                               value-transforming
                                               interceptor always had
                                               this way -- wrap it in
                                               detached, once, at the
                                               point it's built, if
                                               that's not wanted)
     - a (detached ...)-wrapped interceptor -> run against its OWN
                                               seed, never value
   Throws a clear error if name is missing -- checks contains?, not
   truthiness, so a genuinely stored nil/false is returned as-is, not
   mistaken for absence."
  [state name value]
  (if-not (contains? state name)
    (throw (ex-info "Missing named state" {:name name :available (keys state)}))
    (let [v (get state name)]
      (if-let [{:keys [inner seed]} (detached-info v)]
        (do (when-not (interceptor? inner)
              (throw (ex-info "State value is not an interceptor" {:name name :value inner})))
            (:value (run-one (assoc state :value seed) inner)))
        (if (interceptor? v)
          (:value (run-one (assoc state :value value) v))
          v)))))

;;; ----------------------------------------------------------------------
;;; Execution helpers
;;; ----------------------------------------------------------------------

(defn run
  "Execute an algoline with a static state map. Returns only the
   final value."
  ([an-algoline initial] (run an-algoline initial {}))
  ([an-algoline initial state]
   (:value (run-one (assoc state :value initial) an-algoline))))

(defn run-with-state
  "Execute an algoline against a live state (atom). Updates the atom
   with any changes made by steps that write state forward. Returns
   the final value."
  [an-algoline initial state-atom]
  (let [result (run-one (assoc @state-atom :value initial) an-algoline)]
    (reset! state-atom (dissoc result :value))
    (:value result)))

;;; ----------------------------------------------------------------------
;;; Same-type swap -- a plain replace, not a reconciling merge (a step is
;;; an ordinary closure, not a named, inspectable model -- nothing to
;;; reconcile)
;;; ----------------------------------------------------------------------

(defn swap-step
  "PURE: return a new algoline with the step at path replaced by
   new-step. path is an ordinary assoc-in-style key sequence -- [:steps
   0] replaces this algoline's own first step. Works correctly (not
   silently as a no-op) because a nested algoline's own :steps is read
   FRESH on every execution, never captured in a closure."
  [an-algoline path new-step]
  (assoc-in an-algoline path new-step))

;;; ----------------------------------------------------------------------
;;; Root-eligibility
;;; ----------------------------------------------------------------------

(defn root?
  "Does running an-algoline against sample-value/sample-state (both
   optional, default nil/{}) produce resolved-leaf-shaped output -- a
   map carrying both :pitches and :duration?"
  ([an-algoline] (root? an-algoline nil {}))
  ([an-algoline sample-value] (root? an-algoline sample-value {}))
  ([an-algoline sample-value sample-state]
   (let [out (run an-algoline sample-value sample-state)]
     (and (map? out) (contains? out :pitches) (contains? out :duration)))))

(defn validate-root!
  "Throw a clear, loud ex-info if an-algoline isn't root? against
   sample-value/sample-state, else return an-algoline unchanged."
  ([an-algoline] (validate-root! an-algoline nil {}))
  ([an-algoline sample-value] (validate-root! an-algoline sample-value {}))
  ([an-algoline sample-value sample-state]
   (when-not (root? an-algoline sample-value sample-state)
     (throw (ex-info "This algoline does not produce resolved leaves (:pitches/:duration) and cannot be used as a root"
                      {:algoline an-algoline})))
   an-algoline))

;;; ----------------------------------------------------------------------
;;; Live, per-path attached instances
;;; ----------------------------------------------------------------------

(defonce ^{:doc "path -> {:algoline an-algoline :state state-atom},
one entry per CURRENTLY-ATTACHED, live instance. ^:dynamic so a test can
bind a fresh, isolated instance for just its own extent."}
  ^:dynamic *attached* (atom {}))

(defn attach!
  "Register an-algoline under path with its OWN freshly-minted state
   atom, seeded from initial-state (default {}), validated loudly
   first against sample-value (default nil) via validate-root!. Returns
   path."
  ([path an-algoline] (attach! path an-algoline nil {}))
  ([path an-algoline sample-value] (attach! path an-algoline sample-value {}))
  ([path an-algoline sample-value initial-state]
   (validate-root! an-algoline sample-value initial-state)
   (swap! *attached* assoc path {:algoline an-algoline :state (atom initial-state)})
   path))

(defn detach!
  "Forget path's own attached instance -- never affects any OTHER
   path's own instance. Also forgets any GUI controls declared for
   path (declare-controls!, below), so a later attach! at the SAME
   path never inherits a stale declaration meant for whatever used to
   live there."
  [path]
  (swap! *attached* dissoc path)
  (swap! *controls* dissoc path)
  nil)

(defn active
  "path's own currently-attached {:algoline :state} entry, or nil."
  [path]
  (get @*attached* path))

(defn active-all
  "The raw {path -> {:algoline :state}} map of every currently-
   attached instance."
  []
  @*attached*)

(defn current-state
  "path's own currently-attached instance's LIVE state value (not the
   atom itself) -- @(:state (active path)), or nil if nothing's
   attached there. The GUI-facing read-side symmetry to patch-active!'s
   own write side: a control panel can poll this directly without
   knowing state lives behind an atom at all."
  [path]
  (some-> (active path) :state deref))

(defn run-active!
  "Run path's own attached algoline against initial, threading and
   updating its OWN state atom."
  [path initial]
  (let [{:keys [algoline state]} (get @*attached* path)]
    (when-not algoline
      (throw (ex-info "No algoline attached at path" {:path path})))
    (run-with-state algoline initial state)))

(defn patch-active!
  "Merge new-values into path's own currently-attached instance's live
   state atom directly -- the GUI-facing symmetry."
  [path new-values]
  (let [{:keys [state]} (get @*attached* path)]
    (when-not state
      (throw (ex-info "No algoline attached at path" {:path path})))
    (swap! state merge new-values)
    nil))

;;; ----------------------------------------------------------------------
;;; GUI-facing control schema -- DECLARING which state keys a control
;;; panel should render, and how, without dictating what any actual GUI
;;; toolkit does with that declaration
;;; ----------------------------------------------------------------------

(defonce ^{:doc "path -> {state-key -> {:label :min :max :default :doc}},
GUI-facing control metadata for a live, attached instance's own state
keys. Declares what a GUI SHOULD offer as a control, not what state
actually holds right now -- state can hold keys with no declaration
here at all (they're simply not offered as controls), and a
declaration can exist for a key state doesn't currently have (nothing
requires patch-active! and declare-controls! to be called in lock
step). ^:dynamic so a test can bind a fresh, isolated registry for
just its own extent."}
  ^:dynamic *controls* (atom {}))

(defn declare-controls!
  "Declare path's own GUI-facing controls -- a map of state-key ->
   {:label :min :max :default :doc} (every key inside that inner map
   is optional; an empty {} still marks the state-key as controllable,
   just undescribed). REPLACES any previously-declared controls for
   path wholesale, not merged -- call it once, right after attach!,
   with the FULL set this instance wants exposed. Throws if nothing's
   attached at path yet, same discipline patch-active!/run-active!
   already have -- declaring controls for an instance that doesn't
   exist is almost certainly a mistake, not a legitimate 'prepare
   ahead of time' use.
     (attach! [:TAA] pipeline 60 {:adherence 0.8 :density 0.5})
     (declare-controls! [:TAA]
       {:adherence {:label \"Adherence\" :min -1.0 :max 1.0 :default 0.8}
        :density   {:label \"Density\"   :min 0.0 :max 1.0 :default 0.5}})"
  [path controls]
  (when-not (active path)
    (throw (ex-info "No algoline attached at path" {:path path})))
  (swap! *controls* assoc path controls)
  path)

(defn controls-for
  "path's own declared controls, or {} if none were declared -- never
   nil, always safe to iterate ((doseq [[k spec] (controls-for path)]
   ...) works whether or not declare-controls! was ever called)."
  [path]
  (get @*controls* path {}))

;;; ----------------------------------------------------------------------
;;; Step registry
;;; ----------------------------------------------------------------------

(defonce ^{:doc "name -> {:build (fn [opts] -> interceptor) :defaults
{...} :category kw :doc doc}. ^:dynamic so a test can bind a fresh,
isolated registry for just its own extent."}
  ^:dynamic *step-registry* (atom {}))

(defn register-step!
  "Park build-fn (a function of one arg, opts -- a plain map -- that
   returns a real interceptor) under name, usable thereafter via
   build-step. opts (all optional): :defaults (merged with a caller's
   own overrides at BUILD time), :category, :doc."
  ([name build-fn] (register-step! name build-fn {}))
  ([name build-fn {:keys [defaults category doc]}]
   (swap! *step-registry* assoc name
          {:build build-fn :defaults (or defaults {}) :category category :doc doc})
   name))

(defn unregister-step!
  "Forget name's parked step builder. Any interceptor already built by
   calling build-step with name keeps existing as-is."
  [name]
  (swap! *step-registry* dissoc name)
  nil)

(defn build-step
  "Build a real interceptor from name's registered build-fn, called
   with name's own registered :defaults merged with overrides. Throws
   if name isn't registered. The built interceptor is stamped with its
   OWN name/category (under namespaced keys, invisible to run-one/
   interceptor?, which only ever look at :enter/:steps) -- see
   step-origin, below -- a plain (step f) built by hand carries no
   such stamp, since it was never built from a registered name at all."
  ([name] (build-step name {}))
  ([name overrides]
   (if-let [{:keys [build defaults category]} (get @*step-registry* name)]
     (assoc (build (merge defaults overrides)) ::built-from name ::category category)
     (throw (ex-info "algoline-intercepted: no step registered as" {:name name})))))

(defn step-origin
  "The {:name :category} a-step was built FROM via build-step, or nil
   if it wasn't (a bare (step f)/(safe ...)/etc. built by hand, never
   going through the registry at all). The GUI-facing piece a swap-
   step-based control panel actually needs: given a live algoline's own
   :steps, map step-origin over them to label each position (\"Stage 2:
   tilt-shape\"), then offer steps-of-category's own results as that
   position's own swap alternatives -- nothing about :steps itself
   otherwise carries a name at all, since an interceptor is normally
   just an anonymous {:enter fn}."
  [a-step]
  (when-let [n (::built-from a-step)]
    {:name n :category (::category a-step)}))

(defn steps
  "With no arg: {name -> doc} for every registered step builder. With
   name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @*step-registry*))
  ([name] (:doc (get @*step-registry* name))))

(defn steps-of-category
  "Every registered step-builder name whose own :category is exactly
   category, as a plain vector."
  [category]
  (into [] (comp (filter (fn [[_ v]] (= category (:category v)))) (map key))
        @*step-registry*))
