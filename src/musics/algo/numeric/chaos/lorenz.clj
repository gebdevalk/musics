;; lorenz.clj
;; The real Edward Lorenz system -- the actual 3-equation chaotic
;; attractor "the butterfly effect" refers to. An earlier 1D map here
;; (lorenz-function, same closure shape as musics.algo.numeric.chaos.logistic/
;; logistic but a different recurrence) was removed: confirmed
;; empirically, not assumed, it wasn't a reliable chaos source at all --
;; its default converged to a fixed point rather than chaotic output,
;; and every other parameter combination tried diverged to ##Inf/##NaN
;; within about 6-7 calls. lorenz below is a genuine, tested
;; replacement -- verified live to stay bounded and keep moving (no
;; fixed point, no ##Inf/##NaN) over hundreds of steps at its own
;; defaults.

(ns musics.algo.numeric.chaos.lorenz)

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

(defn lorenz
  "The Lorenz system, dx/dt = sigma*(y - x), dy/dt = x*(rho - z) - y, dz/dt = x*y - beta*z, integrated by fourth-order Runge-Kutta at a fixed dt (default 0.01). sigma, rho and beta default to 10, 28 and 8/3 (the two-winged butterfly); x0, y0, z0 to [1 1 1].
   Returns {:value :params! :state!}: :value advances one step and returns the new [x y z] (a 3-vector), :params! merges a partial {:sigma :rho :beta}, :state! resets [x y z] without advancing. At the defaults x and y range about -20..20 and z 0..50, so scale before using one as a pitch, velocity or duration.
     (def lz (lorenz))
     ((:value lz))  ;; the next [x y z]"
  {:algo {:pull {:via :value} :in [] :out :point :arity 7
          :params {:sigma {:type :double :min 0.0 :max 50.0 :default 10.0 :doc "classic 10"}
                   :rho   {:type :double :min 0.0 :max 100.0 :default 28.0 :doc "classic 28"}
                   :beta  {:type :double :min 0.0 :max 10.0 :default 2.6666666666666665 :doc "classic 8/3"}
                   :x0    {:type :double :min -50.0 :max 50.0 :default 1.0 :doc "starting x"}
                   :y0    {:type :double :min -50.0 :max 50.0 :default 1.0 :doc "starting y"}
                   :z0    {:type :double :min 0.0 :max 100.0 :default 1.0 :doc "starting z"}
                   :dt    {:type :double :min 0.001 :max 0.1 :default 0.01 :doc "time per step"}}}}
  ([] (lorenz 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0))
  ([sigma rho beta x0 y0 z0] (lorenz sigma rho beta x0 y0 z0 0.01))
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

