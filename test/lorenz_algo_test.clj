(ns ^:engine lorenz-algo-test
  (:require [clojure.test :refer [deftest is]]
            [algo.random.lorenz :as lorenz]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest lorenz-algo-uses-the-real-lorenz-attractor-value-closure
  ;; [0 0 0] is a genuine fixed point of the Lorenz system -- all three
  ;; derivatives are exactly 0 there regardless of sigma/rho/beta (dx =
  ;; sigma*(0-0), dy = 0*(rho-0)-0, dz = 0*0-beta*0, all 0), so RK4
  ;; leaves the state at [0 0 0] forever. Deterministic, no need to
  ;; hand-verify a chaotic trajectory to confirm the WIRING (next-fn
  ;; really is lorenz-attractor's own :value, render-fn really gets
  ;; applied to its [x y z] result) is correct.
  (lorenz/lorenz-algo ::fixed-point 10.0 28.0 (/ 8.0 3.0) 0.0 0.0 0.0)
  (let [algofn (wall/algo ::fixed-point)
        out    (algofn [(placeholder :p1) (placeholder :p2)] [] nil)]
    (is (= [[66] [66]] (map :pitches out))
        "default render-fn: x=0 -> MIDI 66 (the midpoint of its 3-octave range)")
    (is (= [1/8 1/8] (map :duration out)))))

(deftest lorenz-algo-accepts-a-custom-render-fn
  (lorenz/lorenz-algo ::custom-render 10.0 28.0 (/ 8.0 3.0) 0.0 0.0 0.0
    (fn [[_x _y z]] {:pitches [(+ 40 (int z))] :duration 1/2}))
  (let [algofn (wall/algo ::custom-render)
        out    (algofn [(placeholder :p1)] [] nil)]
    (is (= [40] (:pitches (first out))) "z=0 at the origin fixed point")
    (is (= 1/2 (:duration (first out))))))

(deftest lorenz-algo-two-instances-dont-share-state
  (lorenz/lorenz-algo ::indep-a 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
  (let [algofn-a (wall/algo ::indep-a)
        _        (algofn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)]
    (lorenz/lorenz-algo ::indep-b1 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
    (lorenz/lorenz-algo ::indep-b2 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
    (let [algofn-b1 (wall/algo ::indep-b1)
          algofn-b2 (wall/algo ::indep-b2)]
      (is (= (:pitches (first (algofn-b1 [(placeholder :q1)] [] nil)))
             (:pitches (first (algofn-b2 [(placeholder :r1)] [] nil))))
          "two fresh, same-seeded instances agree on their own first output,
           regardless of how many times an UNRELATED instance was advanced"))))

(deftest lorenz-algo-param-keys-drives-sigma-from-context-overriding-the-fixed-construction-arg
  ;; sigma sampled as 0 from context makes dx/dt = 0*(y-x) = 0 at every
  ;; RK4 sub-stage, regardless of the current x/y/z -- so x stays
  ;; EXACTLY x0 after one step, however rho/beta move y/z. The real
  ;; fixed sigma=10.0 construction arg would instead give dx/dt =
  ;; 10*(0-5) = -50, a large first-step change -- confirming the
  ;; override actually took effect, not just that x happened to be
  ;; stable already.
  (lorenz/lorenz-algo ::context-sigma 10.0 28.0 (/ 8.0 3.0) 5.0 0.0 0.0
    (fn [[x _y _z]] {:pitches [(Math/round (double x))] :duration 1/8})
    {:sigma :chaosSigma})
  (let [ctx-chain [(c/context-root {:chaosSigma 0})]
        voice     {:structural (atom 0)}
        algofn    (wall/algo ::context-sigma)
        out       (doall (algofn [(placeholder :p1)] ctx-chain voice))]
    (is (= [5] (:pitches (first out)))
        "sigma sampled from context as 0 -> x doesn't move from x0=5.0")))

(deftest lorenz-algo-omitting-param-keys-never-touches-ctx-chain-or-voice
  (lorenz/lorenz-algo ::no-context 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
  (let [algofn (wall/algo ::no-context)
        out    (algofn [(placeholder :p1)] nil nil)]
    (is (some? (:pitches (first out))))))
