(ns algoline-intercepted.core
  "A SIMPLER rebuild of algoline's own idea (small, composable steps,
   threading shared state through an ordered pipeline), on Pedestal/
   re-frame's INTERCEPTOR shape -- one shared context map, not
   algoline.core's own IStep protocol + two channels + five step
   records + three reference-marker records. Explored alongside (not
   replacing) algoline.core -- see algo-composition.txt section 6 for
   the design discussion.

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
     dynamics) always, and returns EITHER a bare new value (dynamics
     unchanged) OR [new-value new-dynamics] (both updated). Ignore
     dynamics for a plain transform; read it for a parameterized one;
     return a pair to write it forward. There is no separate dstep/
     context-step/model-step anymore -- every one of the old four
     cases is just a different way of using this one function.

     dref/aref/iref are PLAIN FUNCTIONS now, not marker records
     resolved by a protocol's own cond dispatch. dynamics is an
     ordinary Clojure map -- (dref dynamics :amount) is just (get
     dynamics :amount) with a clear missing-key error; (aref dynamics
     :octave value) runs the interceptor stored under :octave against
     value (the calling step's own ambient value -- same double-
     counting risk as before, same reason); (iref dynamics :octave
     seed) runs it against seed instead, never the ambient value.
     Composers call these directly, inline, inside an ordinary (fn [v
     d] ...) -- there's no special argument-list-with-markers
     machinery to learn as its own concept.

   An interceptor is either {:enter (fn [ctx] -> ctx)} (a leaf) or
   {:steps [interceptor ...]} (a nested chain, what `algoline` builds)
   -- itself runnable, so one algoline nests inside another for free.
   :steps is read FRESH on every run (never captured in a closure),
   which is what makes swap-step a genuine, observable patch.

   safe, run/run-with-model, swap-step, root?/validate-root!,
   *attached*/attach!/detach!/active/active-all/run-active!/
   patch-active!, and *step-registry*/register-step!/build-step/steps/
   steps-of-category all keep the same reasoning algoline.core's own
   docstring already gives for each -- none of that machinery was ever
   part of the complaint (each one does something genuinely distinct;
   collapsing them would lose real capability, not ceremony).")

;;; ----------------------------------------------------------------------
;;; Core engine -- private. A composer never calls these directly; they
;;; only ever reach for step/algoline/run/attach!, below.
;;; ----------------------------------------------------------------------

(declare execute)

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

(defn- dynamics-of [ctx] (dissoc ctx :value))

;;; ----------------------------------------------------------------------
;;; The one step constructor
;;; ----------------------------------------------------------------------

(defn step
  "The one step constructor. f receives (value dynamics) and returns
   EITHER a bare new value (dynamics unchanged) OR [new-value
   new-dynamics] (both updated) -- covers every one of algoline.core's
   old FnStep/DynamicStep/ContextStep/ModelStep cases:
     (step (fn [v _] (inc v)))                    ;; plain transform
     (step (fn [v d] (+ v (dref d :amount))))       ;; parameterized
     (step (fn [v d] [v (update d :count (fnil inc 0))]))  ;; writes state"
  [f]
  {:enter (fn [ctx]
            (let [result (f (:value ctx) (dynamics-of ctx))]
              (if (and (vector? result) (= 2 (count result)))
                (let [[v d] result] (assoc d :value v))
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
;;; Named references -- plain functions, not marker records + a
;;; protocol-level resolution pass. A composer calls these directly,
;;; inline, inside an ordinary (fn [v d] ...) -- there is no separate
;;; "reference" concept to learn beyond three small functions.
;;; ----------------------------------------------------------------------

(defn dref
  "Look up name directly in dynamics. Throws a clear error if missing
   -- checks contains?, not truthiness, so a genuinely stored nil/false
   is returned as-is, not mistaken for absence."
  [dynamics name]
  (if (contains? dynamics name)
    (get dynamics name)
    (throw (ex-info "Missing named dynamic" {:name name :available (keys dynamics)}))))

(defn- referenced-step [dynamics name]
  (let [p (dref dynamics name)]
    (when-not (interceptor? p)
      (throw (ex-info "Dynamic value is not an interceptor" {:name name :value p})))
    p))

(defn aref
  "Run the interceptor stored under name in dynamics AGAINST value --
   the SAME ambient value the calling step already has, not a detached
   computation. If the referenced interceptor itself transforms value,
   and the calling step then ALSO combines that result with its own
   value again, the value gets counted twice. Use iref, or a value-
   independent step ((step (fn [_ _] 12))), for a fixed contribution
   instead."
  [dynamics name value]
  (:value (run-one (assoc dynamics :value value) (referenced-step dynamics name))))

(defn iref
  "Run the interceptor stored under name in dynamics against seed
   (default nil), never the calling step's own ambient value -- the
   structural fix for aref's own double-counting trap. The default nil
   seed only suits an interceptor that ignores its own value entirely;
   one that actually reads it needs its own correct seed passed
   explicitly."
  ([dynamics name] (iref dynamics name nil))
  ([dynamics name seed]
   (:value (run-one (assoc dynamics :value seed) (referenced-step dynamics name)))))

;;; ----------------------------------------------------------------------
;;; Execution helpers
;;; ----------------------------------------------------------------------

(defn run
  "Execute an algoline with a static dynamics map. Returns only the
   final value."
  ([an-algoline initial] (run an-algoline initial {}))
  ([an-algoline initial dynamics]
   (:value (run-one (assoc dynamics :value initial) an-algoline))))

(defn run-with-model
  "Execute an algoline against a live model (atom). Updates the atom
   with any changes made by steps that write dynamics forward. Returns
   the final value."
  [an-algoline initial model-atom]
  (let [result (run-one (assoc @model-atom :value initial) an-algoline)]
    (reset! model-atom (dissoc result :value))
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
  "Does running an-algoline against sample-value/sample-dynamics (both
   optional, default nil/{}) produce resolved-leaf-shaped output -- a
   map carrying both :pitches and :duration?"
  ([an-algoline] (root? an-algoline nil {}))
  ([an-algoline sample-value] (root? an-algoline sample-value {}))
  ([an-algoline sample-value sample-dynamics]
   (let [out (run an-algoline sample-value sample-dynamics)]
     (and (map? out) (contains? out :pitches) (contains? out :duration)))))

(defn validate-root!
  "Throw a clear, loud ex-info if an-algoline isn't root? against
   sample-value/sample-dynamics, else return an-algoline unchanged."
  ([an-algoline] (validate-root! an-algoline nil {}))
  ([an-algoline sample-value] (validate-root! an-algoline sample-value {}))
  ([an-algoline sample-value sample-dynamics]
   (when-not (root? an-algoline sample-value sample-dynamics)
     (throw (ex-info "This algoline does not produce resolved leaves (:pitches/:duration) and cannot be used as a root"
                      {:algoline an-algoline})))
   an-algoline))

;;; ----------------------------------------------------------------------
;;; Live, per-path attached instances
;;; ----------------------------------------------------------------------

(defonce ^{:doc "path -> {:algoline an-algoline :dynamics dynamics-atom},
one entry per CURRENTLY-ATTACHED, live instance. ^:dynamic so a test can
bind a fresh, isolated instance for just its own extent."}
  ^:dynamic *attached* (atom {}))

(defn attach!
  "Register an-algoline under path with its OWN freshly-minted dynamics
   atom, seeded from initial-dynamics (default {}), validated loudly
   first against sample-value (default nil) via validate-root!. Returns
   path."
  ([path an-algoline] (attach! path an-algoline nil {}))
  ([path an-algoline sample-value] (attach! path an-algoline sample-value {}))
  ([path an-algoline sample-value initial-dynamics]
   (validate-root! an-algoline sample-value initial-dynamics)
   (swap! *attached* assoc path {:algoline an-algoline :dynamics (atom initial-dynamics)})
   path))

(defn detach!
  "Forget path's own attached instance -- never affects any OTHER
   path's own instance."
  [path]
  (swap! *attached* dissoc path)
  nil)

(defn active
  "path's own currently-attached {:algoline :dynamics} entry, or nil."
  [path]
  (get @*attached* path))

(defn active-all
  "The raw {path -> {:algoline :dynamics}} map of every currently-
   attached instance."
  []
  @*attached*)

(defn run-active!
  "Run path's own attached algoline against initial, threading and
   updating its OWN dynamics atom."
  [path initial]
  (let [{:keys [algoline dynamics]} (get @*attached* path)]
    (when-not algoline
      (throw (ex-info "No algoline attached at path" {:path path})))
    (run-with-model algoline initial dynamics)))

(defn patch-active!
  "Merge new-values into path's own currently-attached instance's live
   dynamics atom directly -- the GUI-facing symmetry."
  [path new-values]
  (let [{:keys [dynamics]} (get @*attached* path)]
    (when-not dynamics
      (throw (ex-info "No algoline attached at path" {:path path})))
    (swap! dynamics merge new-values)
    nil))

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
   if name isn't registered."
  ([name] (build-step name {}))
  ([name overrides]
   (if-let [{:keys [build defaults]} (get @*step-registry* name)]
     (build (merge defaults overrides))
     (throw (ex-info "algoline-intercepted: no step registered as" {:name name})))))

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
