(ns ^:engine stateful-generator-test
  (:require [clojure.test :refer [deftest is]]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

;; A tiny, deterministic next-fn/render-fn pair -- an atom-backed
;; counter, same self-contained-state shape logistic-function/
;; lorenz-attractor's own :value closures already have.
(defn- counting-next-fn []
  (let [i* (atom -1)]
    (fn [] (swap! i* inc))))

(defn- pitch-render-fn [i] {:pitches [(+ 60 i)] :duration 1/4})

(deftest stateful-generator-substitutes-content-for-placeholders
  (let [wallfn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        out    (wallfn [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)]
    (is (= [[60] [61] [62]] (map :pitches out)))
    (is (every? #(= :LEAF (:type %)) out))))

(deftest stateful-generator-continues-across-successive-calls
  (let [wallfn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        batch1 (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)
        batch2 (wallfn [(placeholder :p3) (placeholder :p4)] [] nil)]
    (is (= [[60] [61]] (map :pitches batch1)))
    (is (= [[62] [63]] (map :pitches batch2))
        "next-fn's own state continues from where batch1 left off")))

(deftest stateful-generator-is-idempotent-on-an-already-tagged-node
  ;; core.wall's own double-call contract: a container's full sibling
  ;; batch, then again per already-produced node singleton-wrapped. The
  ;; second (singleton) call must NOT call next-fn again, or the
  ;; underlying state would double-advance and desync from what
  ;; actually played.
  (let [wallfn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        batch  (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)
        resung (wallfn [(first batch)] [] nil)]
    (is (= (first batch) (first resung))
        "re-running an already-produced node through the same fn changes nothing")
    (let [next (wallfn [(placeholder :p3)] [] nil)]
      (is (= [62] (:pitches (first next)))
          "a genuinely NEW placeholder still advances from where batch left off (index 2)"))))

(deftest stateful-generator-passes-non-leaf-nodes-through-untouched
  (let [wallfn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        bar    (d/bar 3)
        out    (wallfn [bar (placeholder :p1)] [] nil)]
    (is (= bar (first out)) "a Bar consumes no step")
    (is (= [60] (:pitches (second out)))
        "the leaf still gets index 0 -- the Bar didn't advance next-fn")))

(deftest stateful-generator-two-instances-dont-share-state
  (let [wallfn-a (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        wallfn-b (wall/stateful-generator (counting-next-fn) pitch-render-fn)]
    (wallfn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
    (let [b-out (wallfn-b [(placeholder :q1)] [] nil)]
      (is (= [60] (:pitches (first b-out)))
          "b's own next-fn starts fresh, unaffected by a already being 3 steps in"))))
