;; henon.clj
;; The Hénon map -- a 2D discrete chaotic system (Michel Hénon, 1976),
;; genuinely different from algo.random.logistic's own 1D map and
;; algo.random.lorenz's own continuous 3D system: a discrete 2D map,
;; iterated directly (no numerical integration needed, unlike lorenz-
;; attractor's own RK4 stepping). Ported from a real design email
;; (musics.clj commit history/emails/messages/algorithm/MusicalGesture,
;; 2026-04-28) that already listed henon alongside logistic/lorenz as
;; sibling pitch-shape generators for the same "gesture" concept.

(ns algo.random.henon
  (:require [core.wall :as wall]))

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
   just applies one algebraic step per call, same shape as algo.random.
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
   b*x, less independently interesting on its own) -- see henon-algo
   below for exactly that mapping, ready to register as a wall
   algorithm."
  ([] (henon-attractor 1.4 0.3 0.1 0.1))
  ([a b x0 y0]
   (let [params (atom {:a a :b b})
         state  (atom [x0 y0])]
     {:params! (fn [m] (swap! params merge m))
      :state!  (fn [s] (reset! state s))
      :value   (fn []
                 (let [{:keys [a b]} @params]
                   (reset! state (henon-step @state a b))))})))

(defn- clamp [lo hi v] (max lo (min hi v)))

(defn- default-henon-render-fn
  "[x y] -> pitch, using x only (see henon-attractor's own docstring for
   why) -- clamped to the map's own real range (roughly -1.5..1.5, with
   margin) then linear-scaled onto MIDI 48-84, three octaves, same range
   lorenz-wall's own default render-fn uses."
  [[x _y]]
  {:pitches [(+ 48 (int (* (/ (- (clamp -1.5 1.5 x) -1.5) 3.0) 36)))]
   :duration 1/8})

(defn henon-algo
  "A core.wall FACTORY -- built on core.wall/stateful-generator, the
   exact same shared boilerplate algo.random.logistic/logistic-algo and
   algo.random.lorenz/lorenz-algo already use -- wrapping henon-attractor
   as a live generator: the wall fn this returns ignores its own
   placeholder nodes and substitutes the Hénon map's own next [x y],
   mapped through render-fn, in their place instead.

   render-fn ([x y] -> {:pitches [...] :duration r}) defaults to
   default-henon-render-fn (x only, see its own docstring) -- pass your
   own for anything else. a/b/x0/y0 mean exactly what henon-attractor's
   own docstring says.

   param-keys (optional 6th arg, a map like {:a :chaosA :b :chaosB} or
   nil) lets a and/or b themselves be driven LIVE by a committed context
   envelope instead of staying fixed for the whole voice -- built on
   core.wall/context-params-pre-step-fn, sampling each given key against
   ctx-chain at the voice's own real elapsed structural time, once per
   generated step, and merging the result straight into henon-
   attractor's own :params! setter (already a map-merge setter, no
   adapting needed, unlike logistic-algo's single-scalar :r!). A partial
   map (just {:a :chaosA}, b left out) drives only a, leaving b exactly
   the fixed value this factory was called with -- x0/y0 (the map's own
   running STATE, not a fixed parameter) are never context-driven this
   way, same reasoning as logistic-algo's own x. Omitting param-keys (or
   passing nil) leaves a/b exactly the fixed values this factory was
   called with, same behavior as before this argument existed.

   Pair with a :count :infinite Iterator as the placeholder source, same
   as any stateful-generator use:
     (register-algo! :henonPitch (henon-algo 1.4 0.3 0.1 0.1))
     (play :verse :algo :henonPitch)"
  ([a b x0 y0]
   (henon-algo a b x0 y0 default-henon-render-fn nil))
  ([a b x0 y0 render-fn]
   (henon-algo a b x0 y0 render-fn nil))
  ([a b x0 y0 render-fn param-keys]
   (let [gen (henon-attractor a b x0 y0)]
     (wall/stateful-generator
       (:value gen)
       render-fn
       (when (seq param-keys)
         (wall/context-params-pre-step-fn param-keys (:params! gen)))))))
