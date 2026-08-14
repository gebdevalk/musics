(ns algo.random.lorentz)

(defn lorentz-function
  ([] (lorentz-function 0.6 0.125))
  ([r x]
   (let [r (atom r)
         x (atom x)]
     {:r!    (fn [f] (reset! r f))
      :x!    (fn [f] (reset! x f))
      :value (fn []
               (reset! x (* @r (- (* 3 @x) (Math/pow (* 4 @x) 3)))))})))

(def lorentz (lorentz-function))
(def factor! (:r! lorentz))
(def seed!   (:x! lorentz))
(def value   (:value lorentz))

;; (factor! 3.6)
;; (seed! 0.5)

(defn main [n]
  (dotimes [_ n]
    (println (value))))

;; (main 10)