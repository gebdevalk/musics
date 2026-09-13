(ns algoline-intercepted-core-test
  "algoline-intercepted.core -- see that ns's own docstring for the full
   design (algoline's own idea, collapsed onto one shared context map
   and ONE step constructor, instead of algoline.core's five step
   records + three reference-marker records). Mirrors
   algoline_core_test.clj's own coverage where the same behavior still
   applies, adapted throughout to the collapsed (step f)/(dref d k)/
   (aref d k v)/(iref d k seed) API."
  (:require [clojure.test :refer [deftest is]]
            [algoline-intercepted.core :as a]))

;; ============================================================
;; step -- the one constructor, covering every old FnStep/DynamicStep/
;; ContextStep/ModelStep case
;; ============================================================

(deftest step-ignoring-dynamics-is-a-plain-transform
  (is (= 6 (a/run (a/step (fn [v _] (inc v))) 5))))

(deftest step-reading-dynamics-is-a-parameterized-transform
  (is (= 105 (a/run (a/step (fn [v d] (+ v (a/dref d :amount)))) 5 {:amount 100}))))

(deftest step-returning-a-pair-writes-dynamics-forward
  (is (= 2 (:count @(let [model (atom {})]
                      (a/run-with-model (a/step (fn [v d] [v (update d :count (fnil inc 0))])) 5 model)
                      (a/run-with-model (a/step (fn [v d] [v (update d :count (fnil inc 0))])) 5 model)
                      model)))))

(deftest step-returning-a-bare-value-leaves-dynamics-unchanged
  (let [model (atom {:x 1})]
    (is (= 6 (a/run-with-model (a/step (fn [v _] (inc v))) 5 model)))
    (is (= {:x 1} @model))))

(deftest run-returns-only-the-final-value
  (is (= 6 (a/run (a/step (fn [v _] (inc v))) 5))))

;; ============================================================
;; dref -- a plain function, not a marker record
;; ============================================================

(deftest dref-resolves-a-named-value-from-dynamics
  (is (= 100 (a/dref {:amount 100} :amount))))

(deftest dref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing named dynamic"
        (a/dref {} :amount))))

(deftest dref-resolves-correctly-even-when-the-stored-value-is-nil-or-false
  (is (= nil (a/dref {:flag nil} :flag)))
  (is (= false (a/dref {:flag false} :flag))))

;; ============================================================
;; aref -- runs a referenced step against the ambient value
;; ============================================================

(deftest aref-executes-the-referenced-step-and-returns-its-own-output
  (let [sub (a/step (fn [v _] (* v 10)))]
    (is (= 50 (a/aref {:sub sub} :sub 5)))))

(deftest aref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing named dynamic"
        (a/aref {} :sub 5))))

(deftest aref-throws-a-clear-error-when-the-named-value-isnt-an-interceptor
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an interceptor"
        (a/aref {:sub :not-a-step} :sub 5))))

;; ============================================================
;; aref's own double-counting trap, and iref's fix -- same numbers as
;; algoline.core and the earlier interceptor sketch (algo-composition.txt
;; section 6)
;; ============================================================

(deftest aref-referencing-a-value-transforming-step-double-counts-the-current-value
  (let [transforms-value (a/step (fn [v _] (+ v 12)))]
    (is (= 152 (a/run (a/step (fn [v d] (+ v (a/aref d :octave v)))) 70 {:octave transforms-value})))))

(deftest iref-referencing-a-value-transforming-step-does-not-double-count
  (let [transforms-value (a/step (fn [v _] (+ v 12)))]
    (is (= 82 (a/run (a/step (fn [v d] (+ v (a/iref d :octave 0)))) 70 {:octave transforms-value})))))

(deftest iref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing named dynamic"
        (a/iref {} :sub))))

;; ============================================================
;; algoline -- threading value+dynamics through each step, in order
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
                            (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
    (is (true? (a/root? melody 60 {:dur 1/4})))))

(deftest root?-false-when-running-doesnt-produce-that-shape
  (is (false? (a/root? (a/step (fn [v _] (inc v))) 5))))

(deftest validate-root!-returns-the-algoline-unchanged-when-root?
  (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
    (is (= melody (a/validate-root! melody 60 {:dur 1/4})))))

(deftest validate-root!-throws-a-clear-error-when-not-root?
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
        (a/validate-root! (a/step (fn [v _] (inc v))) 5))))

;; ============================================================
;; *attached*/attach!/detach!/active/active-all/run-active!
;; ============================================================

(deftest attach!-stores-under-path-active-reads-it-back
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
      (is (= [:TAA] (a/attach! [:TAA] melody 60 {:dur 1/4})))
      (is (= melody (:algoline (a/active [:TAA])))))))

(deftest attach!-rejects-a-non-root-shaped-algoline-loudly-before-storing-anything
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
          (a/attach! [:TAA] (a/step (fn [v _] (inc v))) 5)))
    (is (nil? (a/active [:TAA])))))

(deftest detach!-forgets-only-its-own-path
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (a/detach! [:TAA])
      (is (nil? (a/active [:TAA])))
      (is (some? (a/active [:TAB]))))))

(deftest active-all-lists-every-currently-attached-instance
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (is (= #{[:TAA] [:TAB]} (set (keys (a/active-all))))))))

(deftest two-paths-attaching-the-identical-algoline-value-never-share-live-dynamics
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline
                   (a/step (fn [v d] [v (update d :count (fnil inc 0))]))
                   (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
      (a/attach! [:V1] melody 60 {:dur 1/4})
      (a/attach! [:V2] melody 60 {:dur 1/4})
      (is (not= (:dynamics (a/active [:V1])) (:dynamics (a/active [:V2]))))
      (a/run-active! [:V1] 60)
      (a/run-active! [:V1] 60)
      (a/run-active! [:V2] 60)
      (is (= 2 (:count @(:dynamics (a/active [:V1])))))
      (is (= 1 (:count @(:dynamics (a/active [:V2]))))))))

(deftest run-active!-throws-a-clear-error-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/run-active! [:nowhere] 5)))))

;; ============================================================
;; patch-active!
;; ============================================================

(deftest patch-active!-merges-into-only-its-own-paths-dynamics
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/step (fn [v d] {:pitches [v] :duration (a/dref d :dur)})))]
      (a/attach! [:V1] melody 60 {:dur 1/4})
      (a/attach! [:V2] melody 60 {:dur 1/4})
      (a/patch-active! [:V1] {:dur 1/8})
      (is (= 1/8 (:dur @(:dynamics (a/active [:V1])))))
      (is (= 1/4 (:dur @(:dynamics (a/active [:V2]))))))))

(deftest patch-active!-throws-a-clear-error-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/patch-active! [:nowhere] {:dur 1/8})))))

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
