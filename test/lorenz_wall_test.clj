(ns ^:engine lorenz-wall-test
  (:require [clojure.test :refer [deftest is]]
            [algo.random.lorenz :as lorenz]
            [core.domain.flat-domain :as d]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

(deftest lorenz-wall-uses-the-real-lorenz-attractor-value-closure
  ;; [0 0 0] is a genuine fixed point of the Lorenz system -- all three
  ;; derivatives are exactly 0 there regardless of sigma/rho/beta (dx =
  ;; sigma*(0-0), dy = 0*(rho-0)-0, dz = 0*0-beta*0, all 0), so RK4
  ;; leaves the state at [0 0 0] forever. Deterministic, no need to
  ;; hand-verify a chaotic trajectory to confirm the WIRING (next-fn
  ;; really is lorenz-attractor's own :value, render-fn really gets
  ;; applied to its [x y z] result) is correct.
  (let [wallfn (lorenz/lorenz-wall 10.0 28.0 (/ 8.0 3.0) 0.0 0.0 0.0)
        out    (wallfn [(placeholder :p1) (placeholder :p2)] [] nil)]
    (is (= [[66] [66]] (map :pitches out))
        "default render-fn: x=0 -> MIDI 66 (the midpoint of its 3-octave range)")
    (is (= [1/8 1/8] (map :duration out)))))

(deftest lorenz-wall-accepts-a-custom-render-fn
  (let [wallfn (lorenz/lorenz-wall 10.0 28.0 (/ 8.0 3.0) 0.0 0.0 0.0
                 (fn [[_x _y z]] {:pitches [(+ 40 (int z))] :duration 1/2}))
        out    (wallfn [(placeholder :p1)] [] nil)]
    (is (= [40] (:pitches (first out))) "z=0 at the origin fixed point")
    (is (= 1/2 (:duration (first out))))))

(deftest lorenz-wall-two-instances-dont-share-state
  (let [wallfn-a  (lorenz/lorenz-wall 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
        _         (wallfn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
        wallfn-b1 (lorenz/lorenz-wall 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)
        wallfn-b2 (lorenz/lorenz-wall 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)]
    (is (= (:pitches (first (wallfn-b1 [(placeholder :q1)] [] nil)))
           (:pitches (first (wallfn-b2 [(placeholder :r1)] [] nil))))
        "two fresh, same-seeded instances agree on their own first output,
         regardless of how many times an UNRELATED instance was advanced")))
