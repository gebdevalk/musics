;; distributions.clj
;; Clojure port of kotlin-reference/jl/random.jl -- continuous-distribution
;; random variate generators.

(ns algo.random.distributions)

(defn rand-uniform
  "Uniform random sample from the interval (a, b)."
  [a b]
  (+ a (* (rand) (- b a))))

(defn rand-normal
  "Random sample from a normal (Gaussian) distribution, via Box-Muller."
  [mean stdev]
  {:pre [(pos? stdev)]}
  (let [u1    (rand)
        u2    (rand)
        r     (Math/sqrt (* -2.0 (Math/log u1)))
        theta (* 2.0 Math/PI u2)]
    (+ mean (* stdev r (Math/sin theta)))))

(defn rand-exponential
  [mean]
  {:pre [(pos? mean)]}
  (* (- mean) (Math/log (rand))))

(defn rand-gamma
  "Marsaglia & Tsang, 'A Simple Method for Generating Gamma Variables',
   ACM Transactions on Mathematical Software 26:3 (2000), 363-372."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (if (>= shape 1.0)
    (let [d (- shape (/ 1.0 3.0))
          c (/ 1.0 (Math/sqrt (* 9.0 d)))]
      (loop []
        (let [x   (loop [x (rand-normal 0 1)]
                    (if (<= (+ 1.0 (* c x)) 0.0)
                      (recur (rand-normal 0 1))
                      x))
              v0  (+ 1.0 (* c x))
              v   (* v0 v0 v0)
              u   (rand)
              xsq (* x x)]
          (if (or (< u (- 1.0 (* 0.0331 xsq xsq)))
                  (< (Math/log u) (+ (* 0.5 xsq) (* d (+ (- 1.0 v) (Math/log v))))))
            (* scale d v)
            (recur)))))
    (let [g (rand-gamma (+ shape 1.0) 1.0)
          w (rand)]
      (* scale g (Math/pow w (/ 1.0 shape))))))

(defn rand-chi-square
  [dof]
  {:pre [(pos? dof)]}
  (rand-gamma (* 0.5 dof) 2.0))

(defn rand-inverse-gamma
  "If X is gamma(shape, scale) then 1/X is inverse-gamma(shape, 1/scale)."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (/ 1.0 (rand-gamma shape (/ 1.0 scale))))

(defn rand-weibull
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (* scale (Math/pow (- (Math/log (rand))) (/ 1.0 shape))))

(defn rand-cauchy
  [median scale]
  {:pre [(pos? scale)]}
  (+ median (* scale (Math/tan (* Math/PI (- (rand) 0.5))))))

(defn rand-student-t
  "Knuth, Seminumerical Algorithms."
  [dof]
  {:pre [(pos? dof)]}
  (let [y1 (rand-normal 0 1)
        y2 (rand-chi-square dof)]
    (/ y1 (Math/sqrt (/ y2 dof)))))

(defn rand-laplace
  "The Laplace distribution, also known as the double exponential
   distribution."
  [mean scale]
  {:pre [(pos? scale)]}
  (let [u (rand)]
    (if (< u 0.5)
      (+ mean (* scale (Math/log (* 2.0 u))))
      (- mean (* scale (Math/log (* 2.0 (- 1.0 u))))))))

(defn rand-log-normal
  [mu sigma]
  (Math/exp (rand-normal mu sigma)))

(defn rand-beta
  [a b]
  {:pre [(pos? a) (pos? b)]}
  (let [u (rand-gamma a 1.0)
        v (rand-gamma b 1.0)]
    (/ u (+ u v))))

(comment
  (rand-uniform 0.1 0.2)
  (rand-normal 0 1)
  (rand-gamma 2.0 1.0)
  (rand-beta 2.0 5.0)
  )
