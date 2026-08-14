;; lorenz.clj
;; The real Edward Lorenz system -- the actual 3-equation chaotic
;; attractor "the butterfly effect" refers to. An earlier 1D map here
;; (lorenz-function, same closure shape as algo.random.logistic/
;; logistic-function but a different recurrence) was removed: confirmed
;; empirically, not assumed, it wasn't a reliable chaos source at all --
;; its default converged to a fixed point rather than chaotic output,
;; and every other parameter combination tried diverged to ##Inf/##NaN
;; within about 6-7 calls. lorenz-attractor below is a genuine, tested
;; replacement -- verified live to stay bounded and keep moving (no
;; fixed point, no ##Inf/##NaN) over hundreds of steps at its own
;; defaults.

(ns algo.random.lorenz)

(defn- lorenz-derivs
  "[dx dy dz] for the real Lorenz system at [x y z], given sigma/rho/beta."
  [[x y z] sigma rho beta]
  [(* sigma (- y x))
   (- (* x (- rho z)) y)
   (- (* x y) (* beta z))])

(defn- rk4-step
  "One classical 4th-order Runge-Kutta step of size dt for state, given
   derivs (a fn of state -> its own [dx dy dz]). Plain 3-vectors here
   (mapv + / mapv *), not a matrix library -- there are only ever three
   components, never worth the dependency."
  [state derivs dt]
  (let [add   (fn [a b] (mapv + a b))
        scale (fn [a s] (mapv #(* % s) a))
        k1 (derivs state)
        k2 (derivs (add state (scale k1 (/ dt 2))))
        k3 (derivs (add state (scale k2 (/ dt 2))))
        k4 (derivs (add state (scale k3 dt)))]
    (add state (scale (add (add k1 (scale k2 2)) (add (scale k3 2) k4)) (/ dt 6)))))

(defn lorenz-attractor
  "The real Edward Lorenz system:
     dx/dt = sigma*(y - x)
     dy/dt = x*(rho - z) - y
     dz/dt = x*y - beta*z
   These are continuous ODEs, not a discrete map like algo.random.
   logistic/logistic-function -- :value has to numerically integrate
   forward by dt each call, not just apply one algebraic step, so this
   uses classical 4th-order Runge-Kutta at a fixed dt (default 0.01,
   matching a typical from-scratch setup for this system -- e.g. 5000
   steps to cover t in [0, 50]).

   Returns a map of three closures sharing private params/state atoms:
   :value (0-arg -- advances one RK4 step of dt and returns the new
   [x y z] -- a 3-vector, NOT a single scalar the way logistic-
   function's own :value is), :params! (merges into {:sigma :rho
   :beta} -- pass a partial map to change only some of them), :state!
   (resets [x y z] directly, without advancing).

   sigma/rho/beta default to 10.0/28.0/(8.0/3.0) -- Lorenz's own
   canonical parameters, the classic two-winged 'butterfly' attractor;
   x0/y0/z0 default to [1.0 1.0 1.0]. Confirmed live, not assumed: RK4
   at these defaults stays bounded and keeps visibly moving (no fixed
   point, no ##Inf/##NaN) over hundreds of steps.

   (def lz (lorenz-attractor))
   ((:value lz))  ;; advance one integration step, get the next [x y z]

   Typical musical use: pick one axis per musical parameter, e.g.
   (let [[x y z] ((:value lz))] ...) -- x/y range roughly -20..20 and z
   roughly 0..50 at the classic parameters (not 0..1 the way
   logistic-function's output is), so scale/clamp before using one
   directly as a pitch/velocity/duration."
  ([] (lorenz-attractor 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0))
  ([sigma rho beta x0 y0 z0] (lorenz-attractor sigma rho beta x0 y0 z0 0.01))
  ([sigma rho beta x0 y0 z0 dt]
   (let [params (atom {:sigma sigma :rho rho :beta beta})
         state  (atom [x0 y0 z0])]
     {:params! (fn [m] (swap! params merge m))
      :state!  (fn [s] (reset! state s))
      :value   (fn []
                 (let [{:keys [sigma rho beta]} @params]
                   (reset! state (rk4-step @state
                                            (fn [s] (lorenz-derivs s sigma rho beta))
                                            dt))))})))
