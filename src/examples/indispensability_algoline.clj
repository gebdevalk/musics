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
   dynamics map instead of baking them into the call.

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
  (a/step (fn [subdivisions _dynamics] (t/indispensability subdivisions))))

(defn tilt-shape-step
  "value: ranks -> tilt-probabilities-shaped probabilities, adherence
   read from dynamics. The default shaping strategy -- swap this out
   for power-law-shape-step (below) via swap-step to change strategy
   without touching any other stage."
  []
  (a/step (fn [ranks dynamics] (t/tilt-probabilities ranks (a/dref dynamics :adherence)))))

(defn power-law-shape-step
  "The alternate shaping strategy -- same slot tilt-shape-step fills:
   order-preserving (or -reversing) rather than softmax, and can assign
   exactly zero probability, unlike tilt-probabilities."
  []
  (a/step (fn [ranks dynamics] (t/power-law-probabilities ranks (a/dref dynamics :adherence)))))

(defn density-select-step
  "value: shaped probabilities -> a thinned binary onset grid, density
   read from dynamics. The default selection strategy -- swap this out
   for choice-select-step (below) via swap-step to pick ONE pulse
   instead of thinning to a whole grid."
  []
  (a/step (fn [probs dynamics] (t/density-grid probs (a/dref dynamics :density)))))

(defn choice-select-step
  "The alternate selection strategy -- picks ONE pulse index, weighted
   by the shaped probabilities, instead of thinning to a grid."
  []
  (a/step (fn [probs _dynamics] (t/weighted-choose (vec (range (count probs))) probs))))

(defn indispensability-pipeline
  "The default 3-stage pipeline: subdivisions -> ranks -> tilt-shaped
   probabilities -> a thinned density grid. adherence/density are read
   from dynamics at run time, not baked into the pipeline value itself
   -- the same dynamics map could be a live, GUI-patchable atom via
   algoline-intercepted.core/attach!+patch-active! if this were wired
   into a live voice."
  []
  (a/algoline (ranks-step) (tilt-shape-step) (density-select-step)))

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
  )
