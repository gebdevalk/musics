;; The Hénon map: a discrete 2D chaotic system iterated directly (no integration, unlike lorenz's RK4),
;; beside logistic (1D) and lorenz (continuous 3D).

(ns musics.algo.numeric.chaos.henon)

(defn- henon-step
  "[x' y'] for the classical Hénon map at [x y], given a/b:
     x' = 1 - a*x^2 + y
     y' = b*x"
  [[x y] a b]
  [(+ 1 (- (* a x x)) y) (* b x)])

(defn henon
  "The Hénon map: x' = 1 - a*x^2 + y, y' = b*x, one algebraic step per call. a and b default to 1.4 and 0.3, the classic chaotic regime (other values often settle to a fixed point or diverge); x0 and y0 to 0.1.
   Returns {:value :params! :state!}: :value advances one step and returns the new [x y] (a 2-vector), :params! merges a partial {:a :b}, :state! resets [x y] without advancing. x is the one usually mapped to a musical parameter.
     (def hn (henon))
     ((:value hn))  ;; the next [x y]"
  {:algo {:pull {:via :value} :in [] :out :point :arity 4
          :params {:a  {:type :double :min 0.0 :max 2.0 :default 1.4 :doc "chaotic at 1.4"}
                   :b  {:type :double :min 0.0 :max 1.0 :default 0.3 :doc "chaotic at 0.3"}
                   :x0 {:type :double :min -1.0 :max 1.0 :default 0.1 :doc "starting x"}
                   :y0 {:type :double :min -1.0 :max 1.0 :default 0.1 :doc "starting y"}}}}
  ([] (henon 1.4 0.3 0.1 0.1))
  ([a b x0 y0]
   (let [params (atom {:a a :b b})
         state  (atom [x0 y0])]
     {:params! (fn [m] (swap! params merge m))
      :state!  (fn [s] (reset! state s))
      :value   (fn []
                 (let [{:keys [a b]} @params]
                   (reset! state (henon-step @state a b))))})))

