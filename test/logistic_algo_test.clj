(ns ^:engine logistic-algo-test
  (:require [clojure.test :refer [deftest is]]
            [algo.random.logistic :as logistic]
            [core.domain.flat-domain :as d]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest logistic-algo-uses-the-real-logistic-function-value-closure
  ;; r=0 makes the map fully degenerate (x_next = 0*x*(1-x) = 0 no
  ;; matter what x was), which is exactly why it's a good correctness
  ;; check: every call, from the very first, returns precisely 0 --
  ;; deterministic, no need to hand-verify a chaotic sequence to confirm
  ;; the WIRING (next-fn really is logistic-function's own :value,
  ;; render-fn really gets applied) is correct.
  (let [algofn (logistic/logistic-algo 0 0.5)
        out    (algofn [(placeholder :p1) (placeholder :p2)] [] nil)]
    (is (= [[48] [48]] (map :pitches out))
        "default render-fn: x=0 -> MIDI 48 (the low end of its 2-octave range)")
    (is (= [1/8 1/8] (map :duration out)))))

(deftest logistic-algo-accepts-a-custom-render-fn
  (let [algofn (logistic/logistic-algo 0 0.5
                 (fn [x] {:pitches [(+ 60 (int (* x 12)))] :duration 1/2}))
        out    (algofn [(placeholder :p1)] [] nil)]
    (is (= [60] (:pitches (first out))))
    (is (= 1/2 (:duration (first out))))))

(deftest logistic-algo-two-instances-dont-share-state
  ;; Confirms logistic-algo builds a FRESH logistic-function instance
  ;; per call, not reusing algo.random.logistic's own shared top-level
  ;; logistic/factor!/seed!/value bindings -- if it did, advancing one
  ;; instance several steps would shift what a second, same-seeded
  ;; instance produces on its own very first call.
  (let [algofn-a  (logistic/logistic-algo 3.8 0.5)
        _         (algofn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
        algofn-b1 (logistic/logistic-algo 3.8 0.5)
        algofn-b2 (logistic/logistic-algo 3.8 0.5)]
    (is (= (:pitches (first (algofn-b1 [(placeholder :q1)] [] nil)))
           (:pitches (first (algofn-b2 [(placeholder :r1)] [] nil))))
        "two fresh, same-seeded instances agree on their own first output,
         regardless of how many times an UNRELATED instance was advanced")))
