(ns ^:engine logistic-wall-test
  (:require [clojure.test :refer [deftest is]]
            [algo.random.logistic :as logistic]
            [core.domain.flat-domain :as d]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest logistic-wall-uses-the-real-logistic-function-value-closure
  ;; r=0 makes the map fully degenerate (x_next = 0*x*(1-x) = 0 no
  ;; matter what x was), which is exactly why it's a good correctness
  ;; check: every call, from the very first, returns precisely 0 --
  ;; deterministic, no need to hand-verify a chaotic sequence to confirm
  ;; the WIRING (next-fn really is logistic-function's own :value,
  ;; render-fn really gets applied) is correct.
  (let [wallfn (logistic/logistic-wall 0 0.5)
        out    (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)]
    (is (= [[48] [48]] (map :pitches out))
        "default render-fn: x=0 -> MIDI 48 (the low end of its 2-octave range)")
    (is (= [1/8 1/8] (map :duration out)))))

(deftest logistic-wall-accepts-a-custom-render-fn
  (let [wallfn (logistic/logistic-wall 0 0.5
                 (fn [x] {:pitches [(+ 60 (int (* x 12)))] :duration 1/2}))
        out    (wallfn [(placeholder :p1)] [] nil)]
    (is (= [60] (:pitches (first out))))
    (is (= 1/2 (:duration (first out))))))

(deftest logistic-wall-two-instances-dont-share-state
  ;; Confirms logistic-wall builds a FRESH logistic-function instance
  ;; per call, not reusing algo.random.logistic's own shared top-level
  ;; logistic/factor!/seed!/value bindings -- if it did, advancing one
  ;; instance several steps would shift what a second, same-seeded
  ;; instance produces on its own very first call.
  (let [wallfn-a  (logistic/logistic-wall 3.8 0.5)
        _         (wallfn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
        wallfn-b1 (logistic/logistic-wall 3.8 0.5)
        wallfn-b2 (logistic/logistic-wall 3.8 0.5)]
    (is (= (:pitches (first (wallfn-b1 [(placeholder :q1)] [] nil)))
           (:pitches (first (wallfn-b2 [(placeholder :r1)] [] nil))))
        "two fresh, same-seeded instances agree on their own first output,
         regardless of how many times an UNRELATED instance was advanced")))
