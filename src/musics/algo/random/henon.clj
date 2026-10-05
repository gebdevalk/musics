;; henon.clj
;; The Hénon map -- a 2D discrete chaotic system (Michel Hénon, 1976),
;; genuinely different from musics.algo.random.logistic's own 1D map and
;; musics.algo.random.lorenz's own continuous 3D system: a discrete 2D map,
;; iterated directly (no numerical integration needed, unlike lorenz-
;; attractor's own RK4 stepping). Ported from a real design email
;; (musics.core commit history/emails/messages/algorithm/MusicalGesture,
;; 2026-04-28) that already listed henon alongside logistic/lorenz as
;; sibling pitch-shape generators for the same "gesture" concept.

(ns musics.algo.random.henon
  (:require [musics.algo.common.scaling :as scaling]))

(defn- henon-step
  "[x' y'] for the classical Hénon map at [x y], given a/b:
     x' = 1 - a*x^2 + y
     y' = b*x"
  [[x y] a b]
  [(+ 1 (- (* a x x)) y) (* b x)])

(defn henon-attractor
  "The classical Hénon map:
     x' = 1 - a*x^2 + y
     y' = b*x
   A discrete 2D chaotic system -- unlike lorenz-attractor's own
   continuous ODEs, no numerical integration is needed here, :value
   just applies one algebraic step per call, same shape as musics.algo.random.
   logistic/logistic-function's own :value, but iterating a 2D point
   instead of a 1D one.

   Returns a map of three closures sharing private params/state atoms:
   :value (0-arg -- advances one step and returns the new [x y] --
   a 2-vector, NOT a single scalar), :params! (merges into {:a :b} --
   pass a partial map to change only one), :state! (resets [x y]
   directly, without advancing).

   a/b default to 1.4/0.3 -- Hénon's own canonical parameters, the
   classic chaotic regime (other values readily converge to a fixed
   point or diverge -- these are the values actually worth using, same
   caution logistic-function's own docstring already gives for its own
   r parameter); x0/y0 default to 0.1/0.1. Confirmed live, not assumed:
   at these defaults, x stays bounded within roughly [-1.28, 1.27]
   (never NaN/Inf) and keeps visibly moving -- 100 distinct values
   across 100 consecutive steps, no fixed point -- over 500 steps.

   (def hn (henon-attractor))
   ((:value hn))  ;; advance one step, get the next [x y]

   Typical musical use: x is the one usually mapped to a musical
   parameter (y is a simple scaled memory of x's own previous value,
   b*x, less independently interesting on its own)."
  {:algo {:short :henon :pull {:via :value} :in [] :out :point :arity 4
          :params {:a  {:type :double :min 0.0 :max 2.0 :default 1.4 :doc "chaotic at 1.4"}
                   :b  {:type :double :min 0.0 :max 1.0 :default 0.3 :doc "chaotic at 0.3"}
                   :x0 {:type :double :min -1.0 :max 1.0 :default 0.1 :doc "starting x"}
                   :y0 {:type :double :min -1.0 :max 1.0 :default 0.1 :doc "starting y"}}}}
  ([] (henon-attractor 1.4 0.3 0.1 0.1))
  ([a b x0 y0]
   (let [params (atom {:a a :b b})
         state  (atom [x0 y0])]
     {:params! (fn [m] (swap! params merge m))
      :state!  (fn [s] (reset! state s))
      :value   (fn []
                 (let [{:keys [a b]} @params]
                   (reset! state (henon-step @state a b))))})))

