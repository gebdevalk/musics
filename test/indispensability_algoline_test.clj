(ns indispensability-algoline-test
  "examples.indispensability-algoline -- see that ns's own docstring for
   why this exists (a staged, swappable version of algo.toolkit's own
   weighted-pulse-choice/weighted-density-grid, built on
   algo.algoline). Covers the default pipeline's parity with the
   plain-function toolkit version, and that swap-step genuinely
   changes what runs (not a silent no-op) for both the shaping and
   selection stages."
  (:require [clojure.test :refer [deftest is]]
            [examples.indispensability-algoline :as ex]
            [algo.algoline :as a]
            [algo.toolkit :as t]))

(deftest default-pipeline-produces-a-binary-onset-grid-of-the-right-length
  (let [out (a/run (ex/indispensability-pipeline) [2 2 3] {:adherence 0.8 :density 0.5})]
    (is (= 12 (count out)))
    (is (every? #{0 1} out))))

(deftest default-pipeline-matches-the-plain-function-toolkit-version
  ;; density-grid's own selection is deterministic given ranks/density
  ;; (a stable top-K sort, no randomness at all) -- both the staged
  ;; pipeline and algo.toolkit/weighted-density-grid wrap the exact
  ;; same three functions, so their outputs must be identical, not just
  ;; similarly-shaped.
  (is (= (t/weighted-density-grid [2 2 3] 0.8 0.5)
         (a/run (ex/indispensability-pipeline) [2 2 3] {:adherence 0.8 :density 0.5}))))

(deftest swap-step-on-the-shaping-stage-changes-the-intermediate-probabilities
  ;; Confirmed live before writing this: tilt-probabilities and power-
  ;; law-probabilities give genuinely different MAGNITUDES for the same
  ;; ranks/adherence, even though density-grid's own top-K-by-order
  ;; selection can't tell them apart downstream (both preserve the same
  ;; rank order for a given adherence sign) -- so this test checks the
  ;; swap's real effect at the point it actually differs: the shaping
  ;; stage's own output, not the final grid.
  (let [ranks (t/indispensability [2 2 3])
        tilt-probs (t/tilt-probabilities ranks 0.8)
        power-probs (t/power-law-probabilities ranks 0.8)]
    (is (not= tilt-probs power-probs))
    (is (= tilt-probs (a/run (a/algoline (ex/ranks-step) (ex/tilt-shape-step)) [2 2 3] {:adherence 0.8})))
    (is (= power-probs (a/run (a/algoline (ex/ranks-step) (ex/power-law-shape-step)) [2 2 3] {:adherence 0.8})))))

(deftest swap-step-on-the-selection-stage-genuinely-changes-what-runs
  (let [pipeline (ex/indispensability-pipeline)
        choice-pipeline (a/swap-step pipeline [:steps 2] (ex/choice-select-step))]
    (is (vector? (a/run pipeline [2 2 3] {:adherence 0.8 :density 0.5}))
        "original: a whole onset-grid vector")
    (is (int? (a/run choice-pipeline [2 2 3] {:adherence 0.8}))
        "swapped: a single chosen pulse index")
    (is (<= 0 (a/run choice-pipeline [2 2 3] {:adherence 0.8}) 11))))

(deftest swap-step-never-mutates-the-original-pipeline
  (let [pipeline (ex/indispensability-pipeline)
        _swapped (a/swap-step pipeline [:steps 1] (ex/power-law-shape-step))]
    (is (vector? (a/run pipeline [2 2 3] {:adherence 0.8 :density 0.5}))
        "original still runs its own default tilt-shape-step, untouched")))

;; ============================================================
;; GUI accessibility -- register-steps!/gui-pipeline demonstrate
;; step-origin/steps-of-category/declare-controls! against this real
;; pipeline, not just algoline-test's own toy steps.
;; ============================================================

(deftest gui-pipeline-stages-report-their-own-registered-origin
  (binding [a/*step-registry* (atom {})]
    (ex/register-steps!)
    (is (= [{:name :ranks :category nil}
            {:name :tilt-shape :category :shaping}
            {:name :density-select :category :selection}]
           (mapv a/step-origin (:steps (ex/gui-pipeline)))))))

(deftest steps-of-category-offers-the-real-swap-alternatives
  (binding [a/*step-registry* (atom {})]
    (ex/register-steps!)
    (is (= #{:tilt-shape :power-law-shape} (set (a/steps-of-category :shaping))))
    (is (= #{:density-select :choice-select} (set (a/steps-of-category :selection))))))

(deftest gui-pipeline-matches-indispensability-pipelines-own-output
  ;; registry-built and hand-built should behave identically -- the
  ;; origin stamp is invisible to execution, confirmed against a real
  ;; pipeline, not just a toy step.
  (binding [a/*step-registry* (atom {})]
    (ex/register-steps!)
    (is (= (a/run (ex/indispensability-pipeline) [2 2 3] {:adherence 0.8 :density 0.5})
           (a/run (ex/gui-pipeline) [2 2 3] {:adherence 0.8 :density 0.5})))))

(deftest a-leaf-producing-variant-is-attachable-and-gui-controllable
  (binding [a/*attached* (atom {}) a/*controls* (atom {}) a/*step-registry* (atom {})]
    (ex/register-steps!)
    (let [leaf-pipeline (a/then
                          (a/algoline (a/build-step :ranks) (a/build-step :tilt-shape)
                                      (a/build-step :choice-select))
                          (a/step (fn [pulse-idx _s] {:pitches [(+ 60 pulse-idx)] :duration 1/4})))]
      (is (true? (a/root? leaf-pipeline [2 2 3] {:adherence 0.8})))
      (a/attach! [:TAA] leaf-pipeline [2 2 3] {:adherence 0.8})
      (a/declare-controls! [:TAA] {:adherence {:label "Adherence" :min -1.0 :max 1.0 :default 0.8}})
      (is (= {:adherence {:label "Adherence" :min -1.0 :max 1.0 :default 0.8}}
             (a/controls-for [:TAA])))
      (is (= {:adherence 0.8} (a/current-state [:TAA])))
      (let [out (a/run-active! [:TAA] [2 2 3])]
        (is (contains? out :pitches))
        (is (= 1/4 (:duration out))))
      (a/patch-active! [:TAA] {:adherence -0.5})
      (is (= {:adherence -0.5} (a/current-state [:TAA]))
          "the GUI-style patch took effect immediately, no reattach needed"))))
