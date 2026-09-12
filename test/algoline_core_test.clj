(ns algoline-core-test
  "algoline.core -- see that ns's own docstring for the full design.
   Covers each step kind, dref/aref resolution (including the
   contains?-not-truthiness regression the original pasted code got
   wrong -- (get m k (throw ...)) always throws regardless of whether
   k is present, since Clojure evaluates a function's arguments eagerly
   before the function itself runs), nesting, and the safe/SafeStep
   throw-vs-degrade split."
  (:require [clojure.test :refer [deftest is]]
            [algoline.core :as a]))

;; ============================================================
;; step / FnStep
;; ============================================================

(deftest step-transforms-value-only-dynamics-untouched
  (is (= [6 {:x 1}] (a/execute (a/step inc) 5 {:x 1}))))

(deftest run-returns-only-the-final-value
  (is (= 6 (a/run (a/step inc) 5))))

;; ============================================================
;; dstep / DynamicStep -- plain literal args
;; ============================================================

(deftest dstep-with-plain-literal-args-applies-f-to-value-and-args
  (is (= 15 (a/run (a/dstep + 10) 5))))

;; ============================================================
;; dstep / dref
;; ============================================================

(deftest dref-resolves-a-named-value-from-dynamics
  (is (= 105 (a/run (a/dstep + (a/dref :amount)) 5 {:amount 100}))))

(deftest dref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing named dynamic"
        (a/run (a/dstep + (a/dref :amount)) 5 {}))))

(deftest dref-resolves-correctly-even-when-the-stored-value-is-nil-or-false
  ;; The specific regression this guards: (get m k (throw ...)) always
  ;; throws regardless of whether k is present, since Clojure evaluates
  ;; a function's arguments eagerly before the function runs -- fixed
  ;; with an explicit contains? check. A truthiness-based check (e.g.
  ;; (or (get m k) (throw ...))) would ALSO wrongly treat a genuinely
  ;; stored nil/false as "missing" -- confirmed here as its own case,
  ;; not assumed to be covered by the missing-key test above.
  (is (= [nil {:flag nil}] (a/execute (a/dstep (fn [_ v] v) (a/dref :flag)) :ignored {:flag nil})))
  (is (= [false {:flag false}] (a/execute (a/dstep (fn [_ v] v) (a/dref :flag)) :ignored {:flag false}))))

;; ============================================================
;; dstep / aref
;; ============================================================

(deftest aref-executes-the-referenced-step-and-inserts-its-own-output
  (let [sub (a/step #(* % 10))]
    (is (= 55 (a/run (a/dstep + (a/aref :sub)) 5 {:sub sub})))))

(deftest aref-throws-a-clear-error-for-a-missing-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing algoline dynamic"
        (a/run (a/dstep + (a/aref :sub)) 5 {}))))

(deftest aref-throws-a-clear-error-when-the-named-value-isnt-an-istep
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an IStep"
        (a/run (a/dstep + (a/aref :sub)) 5 {:sub :not-a-step}))))

;; ============================================================
;; context-step / ContextStep
;; ============================================================

(deftest context-step-sees-both-value-and-dynamics
  (is (= [15 {:mult 3}] (a/execute (a/context-step (fn [v d] (* v (:mult d)))) 5 {:mult 3}))))

(deftest context-step-never-writes-dynamics-even-if-its-own-fn-tries-to
  (is (= [5 {:mult 3}]
         (a/execute (a/context-step (fn [v d] (assoc d :mult 999) v)) 5 {:mult 3}))
      "f's own local mutation of its copy of d is invisible -- ContextStep's
       own execute always returns the ORIGINAL dynamics unchanged"))

;; ============================================================
;; model-step / ModelStep
;; ============================================================

(deftest model-step-bare-return-leaves-dynamics-unchanged
  (is (= [6 {:x 1}] (a/execute (a/model-step (fn [v _d] (inc v))) 5 {:x 1}))))

(deftest model-step-pair-return-updates-both-value-and-dynamics
  (is (= [6 {:count 1}]
         (a/execute (a/model-step (fn [v d] [(inc v) (update d :count (fnil inc 0))])) 5 {}))))

;; ============================================================
;; algoline / Algoline -- threading both channels, in order
;; ============================================================

(deftest algoline-threads-value-through-each-step-in-order
  (is (= 12 (a/run (a/algoline (a/step inc) (a/step #(* % 2))) 5))
      "(5 + 1) * 2 = 12 -- order matters"))

(deftest algoline-reversed-order-produces-a-different-result
  (is (= 11 (a/run (a/algoline (a/step #(* % 2)) (a/step inc)) 5))))

(deftest algoline-threads-dynamics-through-every-step-too
  (is (= [3 {:count 2}]
         (a/execute (a/algoline (a/model-step (fn [v d] [v (update d :count (fnil inc 0))]))
                                 (a/model-step (fn [v d] [v (update d :count (fnil inc 0))])))
                     3 {}))))

(deftest then-appends-steps-without-mutating-the-original
  (let [base     (a/algoline (a/step inc))
        extended (a/then base (a/step #(* % 2)))]
    (is (= 6 (a/run base 5)) "original algoline is untouched")
    (is (= 12 (a/run extended 5)) "the new one has both steps")))

(deftest an-algoline-can-be-embedded-directly-as-a-bare-step-in-another
  ;; No aref needed at all -- Algoline is itself an IStep, so it can
  ;; simply sit in another Algoline's own :steps vector.
  (let [inner (a/algoline (a/step inc) (a/step inc))
        outer (a/algoline inner (a/step #(* % 10)))]
    (is (= 70 (a/run outer 5)) "(5 + 1 + 1) * 10 = 70")))

;; ============================================================
;; safe / SafeStep -- the explicit degrade-instead-of-throw wrapper
;; ============================================================

(deftest unwrapped-step-still-throws-eagerly-by-default
  (is (thrown? clojure.lang.ExceptionInfo
        (a/run (a/step (fn [_] (throw (ex-info "boom" {})))) 5))))

(deftest safe-degrades-a-throwing-step-to-a-console-warning-and-passes-through-unchanged
  (let [throwing (a/step (fn [_] (throw (ex-info "boom" {}))))
        printed  (with-out-str
                   (is (= [5 {:x 1}] (a/execute (a/safe throwing) 5 {:x 1}))
                       "value and dynamics both pass through exactly as given"))]
    (is (re-find #"a step threw" printed))))

(deftest safe-has-no-effect-on-a-step-that-doesnt-throw
  (is (= [6 {}] (a/execute (a/safe (a/step inc)) 5 {}))))

(deftest safe-wrapping-a-whole-algoline-degrades-the-whole-thing-on-any-inner-failure
  (let [whole (a/algoline (a/step inc)
                           (a/step (fn [_] (throw (ex-info "boom" {}))))
                           (a/step inc))]
    (is (= 5 (a/run (a/algoline (a/safe whole)) 5))
        "the WHOLE wrapped algoline's contribution is a no-op once anything
         inside it throws -- not a partial result up to the failure point")))

;; ============================================================
;; aref -- executed against the SAME value/dynamics as the calling
;; step, not a detached computation. Confirmed live, not just reasoned
;; about, before writing the caution into aref's own docstring.
;; ============================================================

(deftest aref-referencing-a-value-transforming-step-double-counts-the-current-value
  (let [transforms-value (a/step #(+ % 12))]
    (is (= 152 (a/run (a/dstep + (a/aref :octave)) 70 {:octave transforms-value}))
        "70 combined with (+ 70 12)=82 via + gives 152, not the 82 a fixed
         +12 contribution would -- the referenced step saw the SAME 70
         the calling dstep already had, not a detached input")))

(deftest aref-referencing-a-value-independent-step-gives-a-clean-fixed-contribution
  (let [fixed-contribution (a/step (constantly 12))]
    (is (= 82 (a/run (a/dstep + (a/aref :octave)) 70 {:octave fixed-contribution}))
        "ignoring its own value argument is what makes aref's result a
         genuinely fixed +12, not a further transform of 70")))

;; ============================================================
;; swap-step -- a plain positional replace, not a reconciling merge
;; ============================================================

(deftest swap-step-replaces-the-step-at-a-top-level-index
  (let [line    (a/algoline (a/step inc) (a/step #(* % 2)))
        swapped (a/swap-step line [:steps 1] (a/step #(* % 100)))]
    (is (= 12 (a/run line 5)) "original untouched")
    (is (= 600 (a/run swapped 5)) "(5 + 1) * 100 = 600")))

(deftest swap-step-reaches-a-step-nested-inside-another-algoline
  (let [inner   (a/algoline (a/step inc) (a/step inc))
        outer   (a/algoline inner (a/step #(* % 10)))
        swapped (a/swap-step outer [:steps 0 :steps 1] (a/step #(* % 100)))]
    (is (= 70 (a/run outer 5)) "(5 + 1 + 1) * 10 = 70, original untouched")
    (is (= 6000 (a/run swapped 5)) "(5 + 1) * 100 = 600, then * 10 = 6000")))

;; ============================================================
;; root? / validate-root! -- checked against a representative sample
;; input, since an algoline's own steps carry no data of their own
;; ============================================================

(deftest root?-true-when-running-produces-pitches-and-duration
  (let [melody (a/algoline (a/step #(+ % 7))
                            (a/dstep (fn [v d] {:pitches [v] :duration d}) (a/dref :dur)))]
    (is (true? (a/root? melody 60 {:dur 1/4})))))

(deftest root?-false-when-running-doesnt-produce-that-shape
  (is (false? (a/root? (a/step inc) 5))))

(deftest validate-root!-returns-the-algoline-unchanged-when-root?
  (let [melody (a/algoline (a/dstep (fn [v d] {:pitches [v] :duration d}) (a/dref :dur)))]
    (is (= melody (a/validate-root! melody 60 {:dur 1/4})))))

(deftest validate-root!-throws-a-clear-error-when-not-root?
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
        (a/validate-root! (a/step inc) 5))))

;; ============================================================
;; *attached*/attach!/detach!/active/active-all/run-active! -- live,
;; per-path instances, never sharing a caller-supplied atom
;; ============================================================

(deftest attach!-stores-under-path-active-reads-it-back
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/dstep (fn [v d] {:pitches [v] :duration d}) (a/dref :dur)))]
      (is (= [:TAA] (a/attach! [:TAA] melody 60 {:dur 1/4})) "returns path")
      (is (= melody (:algoline (a/active [:TAA])))))))

(deftest attach!-rejects-a-non-root-shaped-algoline-loudly-before-storing-anything
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be used as a root"
          (a/attach! [:TAA] (a/step inc) 5)))
    (is (nil? (a/active [:TAA])) "the rejected attach never touched the registry at all")))

(deftest detach!-forgets-only-its-own-path
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/dstep (fn [v d] {:pitches [v] :duration d}) (a/dref :dur)))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (a/detach! [:TAA])
      (is (nil? (a/active [:TAA])))
      (is (some? (a/active [:TAB])) "the other path's own instance is untouched"))))

(deftest active-all-lists-every-currently-attached-instance
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/dstep (fn [v d] {:pitches [v] :duration d}) (a/dref :dur)))]
      (a/attach! [:TAA] melody 60 {:dur 1/4})
      (a/attach! [:TAB] melody 64 {:dur 1/4})
      (is (= #{[:TAA] [:TAB]} (set (keys (a/active-all))))))))

(deftest two-paths-attaching-the-identical-algoline-value-never-share-live-dynamics
  ;; The core guarantee this mechanism exists for, mirroring
  ;; *active-algo-trees*'s own test on the tree branch: patching ONE
  ;; path's own dynamics must never be visible through a DIFFERENT
  ;; path, even when both started from the exact same algoline value
  ;; and the exact same initial dynamics.
  (binding [a/*attached* (atom {})]
    (let [melody (a/algoline (a/model-step (fn [v d] [v (update d :count (fnil inc 0))]))
                              (a/context-step (fn [v d] {:pitches [v] :duration (:dur d)})))]
      (a/attach! [:V1] melody 60 {:dur 1/4})
      (a/attach! [:V2] melody 60 {:dur 1/4})
      (is (not= (:dynamics (a/active [:V1])) (:dynamics (a/active [:V2])))
          "genuinely different atom objects, not the same atom stored twice")
      (a/run-active! [:V1] 60)
      (a/run-active! [:V1] 60)
      (a/run-active! [:V2] 60)
      (is (= 2 (:count @(:dynamics (a/active [:V1])))))
      (is (= 1 (:count @(:dynamics (a/active [:V2]))))
          ":V2's own count is unaffected by :V1's two runs, even though both
           attached the identical algoline value with the identical initial dynamics"))))

(deftest run-active!-throws-a-clear-error-for-an-unattached-path
  (binding [a/*attached* (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No algoline attached"
          (a/run-active! [:nowhere] 5)))))
