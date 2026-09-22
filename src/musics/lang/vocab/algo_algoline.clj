(ns musics.lang.vocab.algo-algoline
  "musics.lang's own `algo-algoline` vocabulary -- a mechanical bridge
   of algo.algoline's own public API (an interceptor-shaped composable-
   step pipeline -- see that ns's own docstring). The three raw dynamic
   vars (*attached*/*controls*/*step-registry*) are plain atoms, not
   callable functions, and are skipped -- active/active-all/steps and
   friends already surface what they hold. `steps` is split into
   `steps`/`steps?` (no-arg listing / one name's own detail), the same
   pairing `algos`/`algos?` and `factories`/`factories?` already use in
   the `algorithms` vocabulary, rather than folding both into one
   arity."
  (:require [algo.algoline :as algoline]
            [musics.lang.runtime :refer [push! pop-val! builtin callable->fn]]))

(defn vocab []
  (merge
    (builtin "algoline" (fn [ctx] (push! ctx (apply algoline/algoline (pop-val! ctx)))) "( steps -- an-algoline )" "creates an algoline (a nested chain) from a vector of steps")
    (builtin "step" (fn [ctx] (push! ctx (algoline/step (callable->fn ctx (pop-val! ctx))))) "( f -- a-step )" "the one step constructor; f receives (value state), returns [value' state']")
    (builtin "then" (fn [ctx] (let [more-steps (pop-val! ctx) p (pop-val! ctx)] (push! ctx (apply algoline/then p more-steps))))
             "( p more-steps -- an-algoline )" "appends one or more steps; returns a new algoline")
    (builtin "swap-step" (fn [ctx] (let [new-step (pop-val! ctx) path (pop-val! ctx) an-algoline (pop-val! ctx)] (push! ctx (algoline/swap-step an-algoline path new-step))))
             "( an-algoline path new-step -- an-algoline' )" "pure: a new algoline with the step at path replaced")
    (builtin "safe" (fn [ctx] (push! ctx (algoline/safe (pop-val! ctx)))) "( inner -- wrapped )" "wraps inner so a thrown exception is caught, not fatal")
    (builtin "detached" (fn [ctx] (let [seed (pop-val! ctx) inner (pop-val! ctx)] (push! ctx (algoline/detached inner seed))))
             "( inner seed -- wrapped )" "wraps inner so dref runs it against its own private, seeded state")
    (builtin "dref" (fn [ctx] (let [value (pop-val! ctx) name (pop-val! ctx) state (pop-val! ctx)] (push! ctx (algoline/dref state name value))))
             "( state name value -- value' )" "looks up name in state and branches on what's stored there")

    (builtin "run" (fn [ctx] (let [state (pop-val! ctx) initial (pop-val! ctx) an-algoline (pop-val! ctx)] (push! ctx (algoline/run an-algoline initial state))))
             "( an-algoline initial state -- value )" "executes an algoline with a static state map")
    (builtin "run-with-state" (fn [ctx] (let [state-atom (pop-val! ctx) initial (pop-val! ctx) an-algoline (pop-val! ctx)] (push! ctx (algoline/run-with-state an-algoline initial state-atom))))
             "( an-algoline initial state-atom -- value )" "executes an algoline against a live (atom) state, updating it in place")
    (builtin "root?" (fn [ctx] (let [sample-state (pop-val! ctx) sample-value (pop-val! ctx) an-algoline (pop-val! ctx)] (push! ctx (algoline/root? an-algoline sample-value sample-state))))
             "( an-algoline sample-value sample-state -- ? )" "does running an-algoline against sample-value/state leave the shape a root needs")
    (builtin "validate-root!" (fn [ctx] (let [sample-state (pop-val! ctx) sample-value (pop-val! ctx) an-algoline (pop-val! ctx)] (push! ctx (algoline/validate-root! an-algoline sample-value sample-state))))
             "( an-algoline sample-value sample-state -- )" "throws a clear ex-info if an-algoline isn't root? against the samples")

    (builtin "attach!" (fn [ctx] (let [initial-state (pop-val! ctx) sample-value (pop-val! ctx) an-algoline (pop-val! ctx) path (pop-val! ctx)]
                                     (push! ctx (algoline/attach! path an-algoline sample-value initial-state))))
             "( path an-algoline sample-value initial-state -- )" "registers an-algoline under path with its own freshly-minted state")
    (builtin "detach!" (fn [ctx] (push! ctx (algoline/detach! (pop-val! ctx)))) "( path -- )" "forgets path's own attached instance")
    (builtin "active" (fn [ctx] (push! ctx (algoline/active (pop-val! ctx)))) "( path -- entry/nil )" "path's own currently-attached {:algoline :state} entry")
    (builtin "active-all" (fn [ctx] (push! ctx (algoline/active-all))) "( -- map )" "the raw {path -> {:algoline :state}} map of every attached instance")
    (builtin "current-state" (fn [ctx] (push! ctx (algoline/current-state (pop-val! ctx)))) "( path -- state )" "path's own currently-attached instance's LIVE state value")
    (builtin "patch-active!" (fn [ctx] (let [new-values (pop-val! ctx) path (pop-val! ctx)] (push! ctx (algoline/patch-active! path new-values))))
             "( path new-values -- )" "merges new-values into path's own currently-attached instance's live state")
    (builtin "run-active!" (fn [ctx] (let [initial (pop-val! ctx) path (pop-val! ctx)] (push! ctx (algoline/run-active! path initial))))
             "( path initial -- value )" "runs path's own attached algoline against initial, threading and saving state")
    (builtin "declare-controls!" (fn [ctx] (let [controls (pop-val! ctx) path (pop-val! ctx)] (push! ctx (algoline/declare-controls! path controls))))
             "( path controls -- )" "declares path's own GUI-facing controls, a map of state-key -> {:label :min :max :default :doc}")
    (builtin "controls-for" (fn [ctx] (push! ctx (algoline/controls-for (pop-val! ctx)))) "( path -- controls )" "path's own declared controls, or {} if none")

    (builtin "register-step!" (fn [ctx] (let [opts (pop-val! ctx) build-fn (callable->fn ctx (pop-val! ctx)) name (pop-val! ctx)]
                                            (push! ctx (algoline/register-step! name build-fn opts))))
             "( name build-fn opts -- )" "parks build-fn under name (opts: :defaults :category :doc)")
    (builtin "unregister-step!" (fn [ctx] (push! ctx (algoline/unregister-step! (pop-val! ctx)))) "( name -- )" "forgets name's parked step builder")
    (builtin "build-step" (fn [ctx] (let [overrides (pop-val! ctx) name (pop-val! ctx)] (push! ctx (algoline/build-step name overrides))))
             "( name overrides -- a-step )" "builds a real interceptor from name's registered build-fn")
    (builtin "step-origin" (fn [ctx] (push! ctx (algoline/step-origin (pop-val! ctx)))) "( a-step -- {:name :category}/nil )" "what build-step built a-step from, if any")
    (builtin "steps" (fn [ctx] (push! ctx (algoline/steps))) "( -- map )" "every registered step builder's own name -> doc")
    (builtin "steps?" (fn [ctx] (push! ctx (algoline/steps (pop-val! ctx)))) "( name -- doc )" "one registered step builder's own doc")
    (builtin "steps-of-category" (fn [ctx] (push! ctx (algoline/steps-of-category (pop-val! ctx)))) "( category -- names )" "every registered step-builder name in category")))
