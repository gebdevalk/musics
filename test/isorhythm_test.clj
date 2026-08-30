(ns ^:algo isorhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.isorhythm :as iso]
            [core.domain.flat-domain :as d]))

;; ---- color-talea (bare pitch/duration pairing) ----

(deftest color-talea-one-period-is-lcm-of-the-two-counts
  (let [events (iso/color-talea [60 62 64] [1/4 1/8])]
    (is (= 6 (count events)) "lcm(3,2) = 6 events for one full period")
    (is (= [[60 1/4] [62 1/8] [64 1/4] [60 1/8] [62 1/4] [64 1/8]] events))))

(deftest color-talea-n-periods-is-n-copies-back-to-back
  (let [one (iso/color-talea [60 62] [1/4 1/8 1/16])
        two (iso/color-talea [60 62] [1/4 1/8 1/16] 2)]
    (is (= 6 (count one)))
    (is (= (into one one) two))))

;; ---- color-talea-wall (the generator-as-wall-fn adapter) ----

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest color-talea-wall-substitutes-real-content-for-placeholders
  (let [wallfn (iso/color-talea-wall [60 62 64] [1/4 1/8])
        out    (wallfn [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)]
    (is (= [[60] [62] [64]] (map :pitches out)))
    (is (= [1/4 1/8 1/4] (map :duration out))
        "duration cycles from the talea, independently of the color")
    (is (every? #(= :LEAF (:type %)) out))))

(deftest color-talea-wall-continues-across-successive-calls
  ;; Simulates what the engine actually does: the SAME resolved fn is
  ;; called again on the next batch of placeholders once the first
  ;; batch has fully played -- the isorhythmic position must continue,
  ;; not reset.
  (let [wallfn (iso/color-talea-wall [60 62 64] [1/4 1/8])
        batch1 (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)
        batch2 (wallfn [(placeholder :p3) (placeholder :p4)] [] nil)]
    (is (= [[60] [62]] (map :pitches batch1)))
    (is (= [[64] [60]] (map :pitches batch2))
        "position 2 and 3 of the color cycle, continuing from batch1's own 0 and 1")))

(deftest color-talea-wall-is-idempotent-on-an-already-tagged-node
  ;; core.wall's own double-call contract: a container's full sibling
  ;; batch, then again per already-produced node singleton-wrapped. The
  ;; second (singleton) call must be a no-op, or the counter would
  ;; double-advance and desync from what was actually played.
  (let [wallfn  (iso/color-talea-wall [60 62 64] [1/4])
        batch   (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)
        resung  (wallfn [(first batch)] [] nil)]
    (is (= (first batch) (first resung))
        "re-running an already-produced node through the same fn changes nothing")
    ;; a genuinely NEW placeholder singleton-called next still advances
    ;; from where batch left off (index 2), not from wherever resung's
    ;; own no-op call landed.
    (let [next (wallfn [(placeholder :p3)] [] nil)]
      (is (= [64] (:pitches (first next)))))))

(deftest color-talea-wall-passes-non-leaf-nodes-through-untouched
  (let [wallfn (iso/color-talea-wall [60 62] [1/4])
        bar    (d/bar 3)
        out    (wallfn [bar (placeholder :p1)] [] nil)]
    (is (= bar (first out)) "a Bar consumes no isorhythmic step")
    (is (= [60] (:pitches (second out)))
        "the leaf still gets step 0 -- the Bar didn't advance the counter")))

(deftest color-talea-wall-two-resolutions-of-the-same-factory-dont-share-state
  (let [wallfn-a (iso/color-talea-wall [60 62] [1/4])
        wallfn-b (iso/color-talea-wall [60 62] [1/4])]
    (wallfn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
    (let [b-out (wallfn-b [(placeholder :q1)] [] nil)]
      (is (= [60] (:pitches (first b-out)))
          "b's own count starts fresh at 0, unaffected by a already being 3 steps in"))))
