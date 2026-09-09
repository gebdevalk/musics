(ns ^:engine henon-algo-test
  (:require [clojure.test :refer [deftest is]]
            [algo.random.henon :as henon]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest henon-algo-uses-the-real-henon-attractor-value-closure
  ;; The Hénon map has no clean RATIONAL fixed point at its own classic
  ;; parameters (a=1.4, b=0.3) the way Lorenz's origin is one -- solving
  ;; a*x^2 + (1-b)*x - 1 = 0 gives an irrational x, not something to
  ;; hand-verify cleanly. Instead: [0 0] isn't a fixed point, but its
  ;; own FIRST step from there is exactly computable by hand (x' = 1 -
  ;; 1.4*0*0 + 0 = 1, y' = 0.3*0 = 0), which is enough to confirm the
  ;; WIRING (next-fn really is henon-attractor's own :value, render-fn
  ;; really gets applied to its [x y] result) without needing a
  ;; multi-call fixed point at all.
  (henon/henon-algo ::first-step 1.4 0.3 0.0 0.0)
  (let [algofn (wall/algo ::first-step)
        out    (algofn [(placeholder :p1)] [] nil)]
    (is (= [78] (:pitches (first out)))
        "x=1 (the exact first step from [0 0]) -> MIDI 78 via the default render-fn")
    (is (= 1/8 (:duration (first out))))))

(deftest henon-algo-accepts-a-custom-render-fn
  (henon/henon-algo ::custom-render 1.4 0.3 0.0 0.0
    (fn [[x y]] {:pitches [(+ 40 (int (* 10 y)))] :duration 1/2}))
  (let [algofn (wall/algo ::custom-render)
        out    (algofn [(placeholder :p1)] [] nil)]
    (is (= [40] (:pitches (first out))) "y=0 after the first step from [0 0]")
    (is (= 1/2 (:duration (first out))))))

(deftest henon-algo-two-instances-dont-share-state
  (henon/henon-algo ::indep-a 1.4 0.3 0.1 0.1)
  (let [algofn-a (wall/algo ::indep-a)
        _        (algofn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)]
    (henon/henon-algo ::indep-b1 1.4 0.3 0.1 0.1)
    (henon/henon-algo ::indep-b2 1.4 0.3 0.1 0.1)
    (let [algofn-b1 (wall/algo ::indep-b1)
          algofn-b2 (wall/algo ::indep-b2)]
      (is (= (:pitches (first (algofn-b1 [(placeholder :q1)] [] nil)))
             (:pitches (first (algofn-b2 [(placeholder :r1)] [] nil))))
          "two fresh, same-seeded instances agree on their own first output,
           regardless of how many times an UNRELATED instance was advanced"))))

(deftest henon-attractor-stays-bounded-and-keeps-moving
  ;; Confirmed live in a throwaway probe before building this (500
  ;; steps, min -1.28/max 1.27, no NaN/Inf, 100 distinct values across
  ;; the last 100 steps) -- locked in as a real, run assertion here,
  ;; not just a comment.
  (let [hn (henon/henon-attractor)
        xs (repeatedly 500 #(first ((:value hn))))]
    (is (every? #(<= -2.0 % 2.0) xs) "stays bounded, well within [-2, 2]")
    (is (not-any? #(or (Double/isNaN %) (Double/isInfinite %)) xs))
    (is (< 50 (count (distinct (take-last 100 xs))))
        "genuinely still moving chaotically, not stuck at a fixed point")))

(deftest henon-algo-param-keys-drives-a-from-context-overriding-the-fixed-construction-arg
  ;; x0=1.0, real fixed a=1.4 would give x' = 1 - 1.4*1 + 0 = -0.4; b is
  ;; left un-wired so y' = 0.3*1 = 0.3 either way -- only a is overridden.
  (henon/henon-algo ::context-a 1.4 0.3 1.0 0.0
    (fn [[x _y]] {:pitches [(long x)] :duration 1/8})
    {:a :chaosA})
  (let [ctx-chain [(c/context-root {:chaosA 0})]
        voice     {:structural (atom 0)}
        algofn    (wall/algo ::context-a)
        out       (doall (algofn [(placeholder :p1)] ctx-chain voice))]
    (is (= [1] (:pitches (first out)))
        "a sampled from context as 0 -> x' = 1 - 0*x^2 + y = 1, not -0.4")))

(deftest henon-algo-omitting-param-keys-never-touches-ctx-chain-or-voice
  (henon/henon-algo ::no-context 1.4 0.3 0.0 0.0)
  (let [algofn (wall/algo ::no-context)
        out    (algofn [(placeholder :p1)] nil nil)]
    (is (some? (:pitches (first out))))))
