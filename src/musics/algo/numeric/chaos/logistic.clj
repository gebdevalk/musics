;; The logistic map x = r*x*(1-x), a discrete chaotic system.

(ns musics.algo.numeric.chaos.logistic)

(defn logistic
  "The logistic map (Verhulst) x = r*x*(1-x), one step per call. Returns {:value :r! :x!}: :value advances and returns the new x in (0,1), :r! resets the growth rate, :x! reseeds x without advancing.
   r sets the character: below about 3.0 it settles to a fixed point, up to about 3.57 it cycles through period-doubling orbits, from 3.57 to 4.0 it is chaotic. The default r of 3.0 is the first bifurcation and only oscillates slowly between two values; pick r between 3.57 and 4.0 (3.8 and 3.9 work well).
     (def lg (logistic 3.8 0.5))
     ((:value lg))  ;; the next x"
  {:algo {:pull {:via :value} :in [] :out :number :arity 2
          :params {:r {:type :double :min 0.0 :max 4.0 :default 3.8 :doc "growth rate; chaotic above ~3.57"}
                   :x {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "starting value"}}}}
  ([] (logistic 3.0 0.6486168175923613))
  ([r x]
   (let [r (atom r)
         x (atom x)]
     {:r!    (fn [f] (reset! r f))
      :x!    (fn [f] (reset! x f))
      :value (fn []
               (reset! x (* @r @x (- 1 @x))))})))


(defn logistic-seq
  "The logistic map as a lazy seq of its values: x1 = r*x0*(1-x0), x2 … No state, no seed:
   the same r and x0 give the same stream, and it can be read from the start by any number of voices.
     (take 4 (logistic-seq 3.8 0.5))"
  {:algo {:in [] :out :number
          :params {:r {:type :double :min 0.0 :max 4.0 :default 3.8 :doc "growth rate; chaotic above ~3.57"}
                   :x {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "starting value"}}}}
  [r x]
  (rest (iterate #(* r % (- 1 %)) x)))
