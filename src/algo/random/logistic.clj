;; logistic.clj
;; Clojure port of kotlin-reference/jl/bifurcation.jl and verhulst.jl --
;; identical files under two different names for the same logistic map
;; (x = r*x*(1-x)), same closure-over-mutable-state shape as lorentz.clj.
;; Their coroutine rewrite, resumable_bifurcation.jl, was left unported:
;; it destructures three return values from a Julia @resumable generator
;; that only ever yields one, so it doesn't actually run as written.

(ns algo.random.logistic)

(defn logistic-function
  ([] (logistic-function 3.0 0.6486168175923613))
  ([r x]
   (let [r (atom r)
         x (atom x)]
     {:r!    (fn [f] (reset! r f))
      :x!    (fn [f] (reset! x f))
      :value (fn []
               (reset! x (* @r @x (- 1 @x))))})))

(def logistic (logistic-function))
(def factor! (:r! logistic))
(def seed!   (:x! logistic))
(def value   (:value logistic))

;; (factor! 3.6)
;; (seed! 0.5)

(defn main [n]
  (dotimes [_ n]
    (println (value))))

;; (main 10)
