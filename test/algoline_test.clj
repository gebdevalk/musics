(ns algoline-test
  "algo.algoline -- see that ns's own docstring for the full design
   (small, composable steps threading shared state through an ordered
   pipeline, collapsed onto one shared context map, ONE step
   constructor, and (as of 2026-09-14) ONE reference function -- ref/
   detached, replacing dref/aref/iref -- instead of the original
   algoline design's five step records + three reference-marker
   records, since removed). Mirrors that original design's own test
   coverage where the same behavior still applies, adapted throughout
   to the collapsed (step f)/(ref d k v)/(detached inner seed) API."
  (:require [clojure.test :refer [deftest is]]
            [algo.algoline :as a]))

;; ============================================================
;; step -- the one constructor, covering every old FnStep/DynamicStep/
;; ContextStep/ModelStep case
;; ============================================================

(deftest step-ignoring-state-is-a-plain-transform
  (is (= 6 (a/run (a/step (fn [v _] (inc v))) 5))))

(deftest step-reading-state-is-a-parameterized-transform
  (is (= 105 (a/run (a/step (fn [v d] (+ v (a/ref d :amount v)))) 5 {:amount 100}))))

(deftest step-returning-a-pair-writes-state-forward
  (is (= 2 (:count @(let [model (atom {})]
                      (a/run-with-state (a/step (fn [v d] [v (update d :count (fnil inc 0))])) 5 model)
                      (a/run-with-state (a/step (fn [v d] [v (update d :count (fnil inc 0))])) 5 model)
                      model)))))

(deftest step-returning-a-bare-value-leaves-state-unchanged
  (let [model (atom {:x 1})]
    (is (= 6 (a/run-with-state (a/step (fn [v _] (inc v))) 5 model)))
    (is (= {:x 1} @model))))

(deftest run-returns-only-the-final-value
  (is (= 6 (a/run (a/step (fn [v _] (inc v))) 5))))

;; ============================================================
;; ref -- ONE reference function, branching on what's actually stored
;; (2026-09-14 collapse of dref/aref/iref)
;; ============================================================

(deftest ref-resolves-a-plain-value-as-is
  (is (= 100 (a/ref {:amount 100} :amount :ignored-value))))

(deftest ref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing named state"
        (a/ref {} :amount :ignored-value))))

(deftest ref-resolves-correctly-even-when-the-stored-value-is-nil-or-false
  (is (= nil (a/ref {:flag nil} :flag :ignored-value)))
  (is (= false (a/ref {:flag false} :flag :ignored-value))))

(deftest ref-runs-an-ordinary-interceptor-against-the-given-value
  (let [sub (a/step (fn [v _] (* v 10)))]
    (is (= 50 (a/ref {:sub sub} :sub 5)))))

(deftest ref-throws-a-clear-error-when-a-detached-inner-isnt-an-interceptor
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an interceptor"
        (a/ref {:sub (a/detached :not-a-step)} :sub 5))))

;; ============================================================
;; The double-counting trap ref's plain (ordinary interceptor) branch
;; still has, and detached's fix -- same numbers as the original
;; algoline design (since removed, see algo-composition.txt section 6),
;; the earlier interceptor sketch (section 6), and this ns's own prior
;; aref/iref pass
;; ============================================================

(deftest ref-against-an-ordinary-interceptor-double-counts-the-ambient-value
  (let [transforms-value (a/step (fn [v _] (+ v 12)))]
    (is (= 152 (a/run (a/step (fn [v d] (+ v (a/ref d :octave v)))) 70 {:octave transforms-value})))))

(deftest ref-against-a-detached-interceptor-does-not-double-count
  (let [transforms-value (a/step (fn [v _] (+ v 12)))]
    (is (= 82 (a/run (a/step (fn [v d] (+ v (a/ref d :octave v))))
                      70 {:octave (a/detached transforms-value 0)}))
        "the SAME transforms-value step, wrapped in detached ONCE where
         :octave is built, needs no special call at the ref call site
         at all -- ref itself never chose aref-vs-iref behavior, the
         stored value's own shape decided it")))

(deftest detached-with-no-explicit-seed-defaults-to-nil
  (let [ignores-its-value (a/step (fn [_ _] 12))]
    (is (= 82 (a/run (a/step (fn [v d] (+ v (a/ref d :octave v))))
                      70 {:octave (a/detached ignores-its-value)})))))

;; ============================================================
;; algoline -- threading value+state through each step, in order
;; ============================================================

(deftest algoline-threads-value-through-each-step-in-order
  (is (= 12 (a/run (a/algoline (a/step (fn [v _] (inc v))) (a/step (fn [v _] (* v 2)))) 5))
      "(5 + 1) * 2 = 12"))

(deftest algoline-reversed-order-produces-a-different-result
  (is (= 11 (a/run (a/algoline (a/step (fn [v _] (* v 2))) (a/step (fn [v _] (inc v)))) 5))))

(deftest then-appends-steps-without-mutating-the-original
  (let [base     (a/algoline (a/step (fn [v _] (inc v))))
        extended (a/then base (a/step (fn [v _] (* v 2))))]
    (is (= 6 (a/run base 5)) "original algoline is untouched")
    (is (= 12 (a/run extended 5)) "the new one has both steps")))

(deftest an-algoline-can-be-embedded-directly-as-a-bare-step-in-another
  (let [inner (a/algoline (a/step (fn [v _] (inc v))) (a/step (fn [v _] (inc v))))
        outer (a/algoline inner (a/step (fn [v _] (* v 10))))]
    (is (= 70 (a/run outer 5)) "(5 + 1 + 1) * 10 = 70")))

;; ============================================================
;; safe -- the explicit degrade-instead-of-throw wrapper
;; ============================================================

(deftest unwrapped-step-still-throws-eagerly-by-default
  (is (thrown? clojure.lang.ExceptionInfo
        (a/run (a/step (fn [_ _] (throw (ex-info "boom" {})))) 5))))

(deftest safe-degrades-a-throwing-step-to-a-console-warning-and-passes-through-unchanged
  (let [throwing (a/step (fn [_ _] (throw (ex-info "boom" {}))))
        printed  (with-out-str
                   (is (= 5 (a/run (a/safe throwing) 5 {:x 1}))))]
    (is (re-find #"a step threw" printed))))

(deftest safe-has-no-effect-on-a-step-that-doesnt-throw
  (is (= 6 (a/run (a/safe (a/step (fn [v _] (inc v)))) 5))))

(deftest safe-wrapping-a-whole-algoline-degrades-the-whole-thing-on-any-inner-failure
  (let [whole (a/algoline (a/step (fn [v _] (inc v)))
                           (a/step (fn [_ _] (throw (ex-info "boom" {}))))
                           (a/step (fn [v _] (inc v))))]
    (is (= 5 (a/run (a/algoline (a/safe whole)) 5))
        "the WHOLE wrapped algoline's contribution is a no-op")))

;; ============================================================
;; swap-step -- a plain positional replace, and a genuine patch (not a
;; silent no-op), because :steps is read fresh on every run
;; ============================================================

(deftest swap-step-replaces-the-step-at-a-top-level-index
  (let [line    (a/algoline (a/step (fn [v _] (inc v))) (a/step (fn [v _] (* v 2))))
        swapped (a/swap-step line [:steps 1] (a/step (fn [v _] (* v 100))))]
    (is (= 12 (a/run line 5)) "original untouched")
    (is (= 600 (a/run swapped 5)) "(5 + 1) * 100 = 600")))

(deftest swap-step-reaches-a-step-nested-inside-another-algoline
  (let [inner   (a/algoline (a/step (fn [v _] (inc v))) (a/step (fn [v _] (inc v))))
        outer   (a/algoline inner (a/step (fn [v _] (* v 10))))
        swapped (a/swap-step outer [:steps 0 :steps 1] (a/step (fn [v _] (* v 100))))]
    (is (= 70 (a/run outer 5)) "original untouched")
    (is (= 6000 (a/run swapped 5)) "(5 + 1) * 100 = 600, then * 10 = 6000")))

;; ============================================================
;; root? / validate-root!
;; ============================================================

(deftest root?-true-when-running-produces-pitches-and-duration
  (let [melody (a/algoline (a/step (fn [v _] (+ v 7)))
                            (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
    (is (true? (a/root? melody 60 {:dur 1/4})))))

(deftest root?-false-when-running-doesnt-produce-that-shape
  (is (false? (a/root? (a/step (fn [v _] (inc v))) 5))))

(deftest validate-root!-returns-the-algoline-unchanged-when-root?
  (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
    (is (= melody (a/validate-root! melody 60 {:dur 1/4})))))

(deftest validate-root!-throws-a-clear-error-when-not-root?
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
        (a/validate-root! (a/step (fn [v _] (inc v))) 5))))

;; ============================================================
;; *attached*/attach!/detach!/active/active-all/run-active!
;; ============================================================

(deftest attach!-stores-under-path-active-reads-it-back
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (is (= [:TAA] (a/attach! [:TAA] melody 60 {:dur 1/4})))
      (is (= melody (:algoline (a/active [:TAA])))))))

(deftest attach!-rejects-a-non-root-shaped-algoline-loudly-before-storing-anything
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
          (a/attach! [:TAA] (a/step (fn [v _] (inc v))) 5)))
    (is (nil? (a/active [:TAA])))))

(deftest detach!-forgets-only-its-own-path
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (a/detach! [:TAA])
      (is (nil? (a/active [:TAA])))
      (is (some? (a/active [:TAB]))))))

(deftest active-all-lists-every-currently-attached-instance
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (is (= #{[:TAA] [:TAB]} (set (keys (a/active-all))))))))

(deftest two-paths-attaching-the-identical-algoline-value-never-share-live-state
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline
                   (a/step (fn [v d] [v (update d :count (fnil inc 0))]))
                   (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (a/attach! [:V1] melody 60 {:dur 1/4})
      (a/attach! [:V2] melody 60 {:dur 1/4})
      (is (not= (:state (a/active [:V1])) (:state (a/active [:V2]))))
      (a/run-active! [:V1] 60)
      (a/run-active! [:V1] 60)
      (a/run-active! [:V2] 60)
      (is (= 2 (:count @(:state (a/active [:V1])))))
      (is (= 1 (:count @(:state (a/active [:V2]))))))))

(deftest run-active!-throws-a-clear-error-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/run-active! [:nowhere] 5)))))

;; ============================================================
;; patch-active!
;; ============================================================

(deftest patch-active!-merges-into-only-its-own-paths-state
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (a/attach! [:V1] melody 60 {:dur 1/4})
      (a/attach! [:V2] melody 60 {:dur 1/4})
      (a/patch-active! [:V1] {:dur 1/8})
      (is (= 1/8 (:dur @(:state (a/active [:V1])))))
      (is (= 1/4 (:dur @(:state (a/active [:V2]))))))))

(deftest patch-active!-throws-a-clear-error-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/patch-active! [:nowhere] {:dur 1/8})))))

;; ============================================================
;; current-state -- the read-side symmetry to patch-active!
;; ============================================================

(deftest current-state-reads-the-live-value-not-the-atom
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)})))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (is (= {:dur 1/4} (a/current-state [:TAA])))
      (a/patch-active! [:TAA] {:dur 1/8})
      (is (= {:dur 1/8} (a/current-state [:TAA]))
          "reflects the patch immediately, same atom active itself reads"))))

(deftest current-state-is-nil-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (nil? (a/current-state [:nowhere])))))

;; ============================================================
;; *controls*/declare-controls!/controls-for -- GUI control schema,
;; layered on top of state, not a new storage mechanism
;; ============================================================

(deftest declare-controls!-then-controls-for-round-trips
  (binding [a/*attached* (atom {}) a/*controls* (atom {})]
    (a/attach! [:TAA] (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)}))) 60 {:dur 1/4})
    (a/declare-controls! [:TAA] {:dur {:label "Duration" :min 0.0 :max 1.0}})
    (is (= {:dur {:label "Duration" :min 0.0 :max 1.0}} (a/controls-for [:TAA])))))

(deftest controls-for-is-empty-map-not-nil-when-nothing-declared
  (binding [a/*controls* (atom {})]
    (is (= {} (a/controls-for [:nowhere])))))

(deftest declare-controls!-throws-for-an-unattached-path
  (binding [a/*attached* (atom {}) a/*controls* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/declare-controls! [:nowhere] {:dur {}})))))

(deftest declare-controls!-replaces-wholesale-not-merges
  (binding [a/*attached* (atom {}) a/*controls* (atom {})]
    (a/attach! [:TAA] (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)}))) 60 {:dur 1/4})
    (a/declare-controls! [:TAA] {:a {} :b {}})
    (a/declare-controls! [:TAA] {:c {}})
    (is (= {:c {}} (a/controls-for [:TAA]))
        "the second call REPLACES, :a/:b are gone, not merged alongside :c")))

(deftest detach!-forgets-that-paths-own-declared-controls-too
  (binding [a/*attached* (atom {}) a/*controls* (atom {})]
    (a/attach! [:TAA] (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/ref d :dur v)}))) 60 {:dur 1/4})
    (a/declare-controls! [:TAA] {:dur {}})
    (a/detach! [:TAA])
    (is (= {} (a/controls-for [:TAA]))
        "a later attach! at the same path must not inherit a stale declaration")))

;; ============================================================
;; *step-registry*/register-step!/build-step/steps/steps-of-category
;; ============================================================

(deftest build-step-merges-registered-defaults-with-overrides
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :add (fn [{:keys [n]}] (a/step (fn [v _] (+ v n)))) {:defaults {:n 5}})
    (is (= 10 (a/run (a/build-step :add) 5)))
    (is (= 8 (a/run (a/build-step :add {:n 3}) 5)))))

(deftest build-step-throws-a-clear-error-for-an-unregistered-name
  (binding [a/*step-registry* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no step registered"
          (a/build-step :nope)))))

(deftest unregister-step!-doesnt-affect-a-step-already-built
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :add (fn [{:keys [n]}] (a/step (fn [v _] (+ v n)))) {:defaults {:n 5}})
    (let [built (a/build-step :add)]
      (a/unregister-step! :add)
      (is (= 10 (a/run built 5))))))

(deftest steps-of-category-finds-only-matching-names
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :bump7 (fn [_] (a/step (fn [v _] (+ v 7)))) {:category :shape})
    (a/register-step! :bump3 (fn [_] (a/step (fn [v _] (+ v 3)))) {:category :shape})
    (a/register-step! :toLeaf (fn [_] (a/step (fn [v _] v))))
    (is (= #{:bump7 :bump3} (set (a/steps-of-category :shape))))))

(deftest steps-lists-every-registered-name-and-doc
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :add (fn [{:keys [n]}] (a/step (fn [v _] (+ v n)))) {:defaults {:n 5} :doc "adds n"})
    (is (= {:add "adds n"} (a/steps)))
    (is (= "adds n" (a/steps :add)))))

;; ============================================================
;; step-origin -- the GUI-facing "which registered name built this
;; step" stamp build-step now leaves behind
;; ============================================================

(deftest step-origin-of-a-registry-built-step-reports-name-and-category
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :bump7 (fn [_] (a/step (fn [v _] (+ v 7)))) {:category :shape})
    (is (= {:name :bump7 :category :shape} (a/step-origin (a/build-step :bump7))))))

(deftest step-origin-of-a-hand-built-step-is-nil
  (is (nil? (a/step-origin (a/step (fn [v _] v))))))

(deftest a-registry-built-step-still-runs-normally-despite-the-origin-stamp
  ;; the stamp lives under namespaced keys run-one/interceptor? never
  ;; look at -- confirms it's genuinely invisible to execution, not
  ;; just "happens not to break these two tests."
  (binding [a/*step-registry* (atom {})]
    (a/register-step! :add7 (fn [_] (a/step (fn [v _] (+ v 7)))) {})
    (is (= 12 (a/run (a/build-step :add7) 5)))))
