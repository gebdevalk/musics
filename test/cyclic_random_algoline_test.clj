(ns cyclic-random-algoline-test
  "examples.cyclic-random-algoline -- see that ns's own docstring for
   why this exists (a second implementation of algo.random/cyclic-
   random, built on algoline-intercepted.core instead of a closure-
   over-an-atom). Covers correctness parity with the original (never
   nil across many exhaustion boundaries, every full pass a
   permutation), the empty-collection guard, and the live/GUI-bindable
   path via cyclic-random-leaf'/attach!/patch-active!."
  (:require [clojure.test :refer [deftest is]]
            [examples.cyclic-random-algoline :as ex]
            [algoline-intercepted.core :as a]))

(deftest cyclic-random-step-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (ex/cyclic-random-step []))))

(deftest cyclic-random'-never-returns-nil-across-many-exhaustion-boundaries
  ;; Direct parity check against the exact regression algo.random/
  ;; cyclic-random needed (see rand_test.clj) -- driven through 50 full
  ;; passes to hit the exhaustion boundary repeatedly, not just once.
  (let [coll (range 60 72)
        gen  (ex/cyclic-random' coll)
        st   (atom {})]
    (dotimes [_ (* 50 (count coll))]
      (let [item (a/run-with-state gen nil st)]
        (is (some? item) "never nil, even right at a reshuffle boundary")
        (is (contains? (set coll) item))))))

(deftest cyclic-random'-every-pass-is-a-permutation-of-coll
  (let [coll (vec (range 5))
        gen  (ex/cyclic-random' coll)
        st   (atom {})
        xs   (repeatedly (* 10 (count coll)) #(a/run-with-state gen nil st))]
    (is (every? #(= (set coll) (set %)) (partition (count coll) xs)))))

(deftest cyclic-random'-state-is-visible-and-inspectable-mid-run
  ;; The one real difference from the closure-based original: nothing
  ;; about algo.random/cyclic-random lets a caller see :pool/:idx at
  ;; all -- here it's just the state atom's own current value.
  (let [coll (vec (range 5))
        gen  (ex/cyclic-random' coll)
        st   (atom {})]
    (a/run-with-state gen nil st)
    (a/run-with-state gen nil st)
    (is (= 2 (:idx @st)))
    (is (= (set coll) (set (:pool @st))))))

;; ============================================================
;; cyclic-random-leaf' -- root?-eligible, live/GUI-bindable
;; ============================================================

(deftest cyclic-random-leaf'-is-root-shaped
  (is (true? (a/root? (ex/cyclic-random-leaf' [60 62 64]) 60 {:dur 1/4}))))

(deftest cyclic-random-leaf'-attaches-runs-and-can-be-patched-live
  (binding [a/*attached* (atom {})]
    (let [gen (ex/cyclic-random-leaf' [60 62 64])]
      (a/attach! [:demo] gen 60 {:dur 1/4})
      (let [out (a/run-active! [:demo] 60)]
        (is (contains? #{60 62 64} (first (:pitches out))))
        (is (= 1/4 (:duration out))))
      (a/patch-active! [:demo] {:dur 1/8})
      (let [out2 (a/run-active! [:demo] 60)]
        (is (= 1/8 (:duration out2))
            "a live, GUI-style patch changes the very next draw's own
             duration -- no equivalent exists for the plain closure
             version, short of discarding it and building a new one")))))
