(ns examples.indispensability-algoline
  "A staged, SWAPPABLE version of algo.toolkit's own weighted-pulse-
   choice/weighted-density-grid combinators, built on
   algoline-intercepted.core instead of plain function composition.

   Those toolkit combinators are the right choice for the common, fixed
   case -- a single pipeline, nothing a composer would want to swap
   independently. This file is what you reach for once that stops being
   true: swap ONE stage (which probability-shaping strategy: tilt's
   softmax vs power-law's order-preserving reshaping; which selection
   strategy: thin to a grid vs pick one pulse) without touching any
   other stage, or read adherence/density from a live, patchable
   state map instead of baking them into the call.

   Every stage is a plain algoline-intercepted step wrapping an ordinary
   algo.toolkit function -- no new algorithm here, just a different
   composition shape around the same three already-tested functions
   (indispensability/tilt-probabilities (or power-law-probabilities)/
   density-grid (or weighted-choose))."
  (:require [algoline-intercepted.core :as a]
            [algo.toolkit :as t]))

(defn ranks-step
  "value: subdivisions (e.g. [2 2 3]) -> Barlow indispensability ranks."
  []
  (a/step (fn [subdivisions _state] (t/indispensability subdivisions))))

(defn tilt-shape-step
  "value: ranks -> tilt-probabilities-shaped probabilities, adherence
   read from state. The default shaping strategy -- swap this out
   for power-law-shape-step (below) via swap-step to change strategy
   without touching any other stage."
  []
  (a/step (fn [ranks state] (t/tilt-probabilities ranks (a/ref state :adherence ranks)))))

(defn power-law-shape-step
  "The alternate shaping strategy -- same slot tilt-shape-step fills:
   order-preserving (or -reversing) rather than softmax, and can assign
   exactly zero probability, unlike tilt-probabilities."
  []
  (a/step (fn [ranks state] (t/power-law-probabilities ranks (a/ref state :adherence ranks)))))

(defn density-select-step
  "value: shaped probabilities -> a thinned binary onset grid, density
   read from state. The default selection strategy -- swap this out
   for choice-select-step (below) via swap-step to pick ONE pulse
   instead of thinning to a whole grid."
  []
  (a/step (fn [probs state] (t/density-grid probs (a/ref state :density probs)))))

(defn choice-select-step
  "The alternate selection strategy -- picks ONE pulse index, weighted
   by the shaped probabilities, instead of thinning to a grid."
  []
  (a/step (fn [probs _state] (t/weighted-choose (vec (range (count probs))) probs))))

(defn indispensability-pipeline
  "The default 3-stage pipeline: subdivisions -> ranks -> tilt-shaped
   probabilities -> a thinned density grid. adherence/density are read
   from state at run time, not baked into the pipeline value itself
   -- the same state map could be a live, GUI-patchable atom via
   algoline-intercepted.core/attach!+patch-active! if this were wired
   into a live voice."
  []
  (a/algoline (ranks-step) (tilt-shape-step) (density-select-step)))

;;; ----------------------------------------------------------------------
;;; GUI accessibility -- a step built by calling tilt-shape-step/etc.
;;; directly (as indispensability-pipeline above does) carries no
;;; step-origin at all: a live algoline's own :steps are anonymous
;;; closures unless they were built THROUGH the registry. register-
;;; steps!/gui-pipeline exist specifically to demonstrate the scenario
;;; step-origin/steps-of-category/declare-controls! were built for --
;;; see the (comment ...) block below for the full worked example.
;;; ----------------------------------------------------------------------

(defn register-steps!
  "Register this file's own four stage-builders under algoline-
   intercepted.core's own step registry, so a GUI can discover
   alternatives by category (steps-of-category :shaping/:selection)
   and label a live pipeline's own stages (step-origin) -- neither
   works for a step built by calling ranks-step/tilt-shape-step/etc.
   directly, only for one built via build-step afterward. Safe to call
   more than once (register-step! always overwrites)."
  []
  (a/register-step! :ranks (fn [_] (ranks-step))
    {:doc "subdivisions -> Barlow indispensability ranks"})
  (a/register-step! :tilt-shape (fn [_] (tilt-shape-step))
    {:category :shaping :doc "softmax-shaped probabilities (tilt-probabilities)"})
  (a/register-step! :power-law-shape (fn [_] (power-law-shape-step))
    {:category :shaping :doc "order-preserving power-law-shaped probabilities"})
  (a/register-step! :density-select (fn [_] (density-select-step))
    {:category :selection :doc "thin to a binary onset grid (density-grid)"})
  (a/register-step! :choice-select (fn [_] (choice-select-step))
    {:category :selection :doc "pick ONE pulse index (weighted-choose)"}))

(defn gui-pipeline
  "Same 3-stage pipeline as indispensability-pipeline, but built
   through the step registry (register-steps! must be called first) --
   every stage now carries its own step-origin, so a GUI can label
   each position and offer steps-of-category's own alternatives as
   swap targets, neither of which a bare (step f) can do."
  []
  (a/algoline (a/build-step :ranks) (a/build-step :tilt-shape) (a/build-step :density-select)))

(comment
  (a/run (indispensability-pipeline) [2 2 3] {:adherence 0.8 :density 0.5})
  ;; => a thinned 0/1 onset grid -- same numbers algo.toolkit/
  ;;    weighted-density-grid gives for the identical inputs.

  ;; Swap the SHAPING strategy without touching anything else in the
  ;; pipeline -- [:steps 1] is tilt-shape-step's own position. Confirmed
  ;; live, worth being precise about: tilt-probabilities and power-law-
  ;; probabilities produce genuinely DIFFERENT probability MAGNITUDES
  ;; for the same ranks/adherence (e.g. adherence 0.8 on [2 2 3]: tilt
  ;; gives a comparatively flat ~[.12 .05 .07 ...], power-law gives a
  ;; sharply peaked ~[.54 0 0 .05 ...] with several exact zeros) -- but
  ;; BOTH preserve the same rank ORDER for a given adherence sign, and
  ;; density-select-step's own density-grid only ever looks at relative
  ;; order (top-K by weight), so swapping the shaping stage here alone
  ;; produces the SAME final grid despite genuinely different
  ;; intermediate probabilities. The swap is real and does what it
  ;; says; this particular downstream consumer just isn't sensitive to
  ;; the difference. Swap density-select-step for choice-select-step
  ;; (below) instead, or inspect tilt-shape-step's/power-law-shape-
  ;; step's own output directly, to see the magnitude difference
  ;; actually matter.
  (def power-law-pipeline
    (a/swap-step (indispensability-pipeline) [:steps 1] (power-law-shape-step)))
  (a/run power-law-pipeline [2 2 3] {:adherence 0.8 :density 0.5})

  ;; Swap the SELECTION strategy instead -- pick one pulse, not a grid
  ;; ([:steps 2] is density-select-step's own position); :density is
  ;; simply unused by choice-select-step, no error, nothing to remove.
  ;; weighted-choose IS sensitive to probability magnitude (not just
  ;; order), so this is where the tilt/power-law shaping difference
  ;; would actually show up in a real draw distribution:
  (def choice-pipeline
    (a/swap-step (indispensability-pipeline) [:steps 2] (choice-select-step)))
  (a/run choice-pipeline [2 2 3] {:adherence 0.8})

  ;; GUI ACCESSIBILITY -- labeling stages and offering swap alternatives,
  ;; the exact scenario step-origin/steps-of-category/declare-controls!
  ;; exist for. Run for real, not just reasoned about, before writing
  ;; this down (2026-09-14):
  (register-steps!)
  (mapv a/step-origin (:steps (gui-pipeline)))
  ;; => [{:name :ranks :category nil}
  ;;     {:name :tilt-shape :category :shaping}
  ;;     {:name :density-select :category :selection}]
  ;;    a GUI reading this can label each position and, for the ones
  ;;    with a :category, offer steps-of-category's own results as
  ;;    that position's own swap dropdown:
  (a/steps-of-category :shaping)    ;; => [:tilt-shape :power-law-shape]
  (a/steps-of-category :selection)  ;; => [:density-select :choice-select]

  ;; density-grid's own onset-grid output isn't itself root?-shaped
  ;; (validate-root! wants ONE resolved leaf, {:pitches :duration}, not
  ;; a whole grid) -- attach! needs a trailing step that resolves to
  ;; one leaf, same discipline examples.cyclic-random-algoline's own
  ;; cyclic-random-leaf' already established. Reusing choice-select-step
  ;; (which already narrows a whole grid down to ONE pulse index) plus
  ;; one small wrap-to-leaf step:
  (def leaf-pipeline
    (a/then
      (a/algoline (a/build-step :ranks) (a/build-step :tilt-shape) (a/build-step :choice-select))
      (a/step (fn [pulse-idx _s] {:pitches [(+ 60 pulse-idx)] :duration 1/4}))))
  (a/root? leaf-pipeline [2 2 3] {:adherence 0.8})   ;; => true

  (a/attach! [:TAA] leaf-pipeline [2 2 3] {:adherence 0.8})
  (a/declare-controls! [:TAA]
    {:adherence {:label "Adherence" :min -1.0 :max 1.0 :default 0.8}})
  (a/controls-for [:TAA])
  ;; => {:adherence {:label "Adherence" :min -1.0 :max 1.0 :default 0.8}}
  (a/current-state [:TAA])          ;; => {:adherence 0.8}
  (a/run-active! [:TAA] [2 2 3])    ;; => {:pitches [...] :duration 1/4}
  (a/patch-active! [:TAA] {:adherence -0.5})   ;; a GUI slider move
  (a/run-active! [:TAA] [2 2 3])    ;; picks up the change immediately,
                                     ;; no reattach needed
  )
