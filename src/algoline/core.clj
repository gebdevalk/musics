(ns algoline.core)

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
  "Named algoline reference. Looks up an IStep under `name` and executes it."
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
