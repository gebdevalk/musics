;; distributions.clj
;; Clojure port of kotlin-reference/jl/random.jl -- continuous-distribution
;; random variate generators.

(ns algo.random.distributions
  (:refer-clojure :exclude [rand])
  (:require [algo.random.seed :refer [rand]]))

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
  "Random sample from an exponential distribution with the given mean
   (not rate -- rate = 1/mean; algo.random.rand/poisson-events passes
   1/rate here for exactly that reason). Models the gap between
   independent events happening at a constant average rate: mostly
   short gaps, occasionally a long one, never negative. mean must be
   positive."
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
  "Chi-square distribution with dof degrees of freedom -- a special case
   of rand-gamma (shape = dof/2, scale = 2). Always positive, skewed
   right, with the spread growing as dof grows. Mainly a building block
   here (rand-student-t is defined in terms of it) rather than something
   reached for directly, but usable on its own for a value that should
   skew toward small/positive with a long right tail. dof must be
   positive."
  [dof]
  {:pre [(pos? dof)]}
  (rand-gamma (* 0.5 dof) 2.0))

(defn rand-inverse-gamma
  "If X is gamma(shape, scale) then 1/X is inverse-gamma(shape, 1/scale)."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (/ 1.0 (rand-gamma shape (/ 1.0 scale))))

(defn rand-weibull
  "Weibull distribution -- a generalized rand-exponential where shape
   controls the skew: shape = 1 reduces to exactly rand-exponential's
   own shape (front-loaded, most mass near zero); shape > 1 pulls the
   mode away from zero into a soft peak (values cluster around a
   typical size but occasionally run long -- note/rest durations, say);
   shape < 1 front-loads even harder than the exponential case, mostly
   very small values with a long thin tail of rare large ones. scale
   stretches the whole distribution. Both args must be positive."
  [shape scale]
  {:pre [(pos? shape) (pos? scale)]}
  (* scale (Math/pow (- (Math/log (rand))) (/ 1.0 shape))))

(defn rand-cauchy
  "Cauchy distribution centered at median, spread by scale -- most
   values cluster near median the way a normal distribution's peak
   does, but the tails are so heavy the distribution has no finite
   mean or variance: occasional extreme outliers, arbitrarily far from
   median, are a real and expected part of its output, not a rare edge
   case the way rand-normal's own thin tail makes them. Useful for an
   otherwise-centered value (a pitch, a panning position) that should
   occasionally leap wildly rather than taper off smoothly. scale must
   be positive."
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
  "Log-normal distribution: exp of a rand-normal(mu, sigma) sample, so
   the result is always positive and right-skewed (a long tail toward
   large values, most mass bunched toward zero) -- useful for a
   duration/amplitude-like value that can't go negative and shouldn't
   have rand-normal's own symmetric tail. mu/sigma are the UNDERLYING
   normal distribution's mean/stdev, not the log-normal output's own
   (which are different, and less intuitive to reason about directly)."
  [mu sigma]
  (Math/exp (rand-normal mu sigma)))

(defn rand-beta
  "Beta distribution on (0, 1) -- shaped by a and b: a = b = 1 is
   uniform, larger equal a/b peaks in the middle (values cluster around
   0.5), a > b skews toward 1, a < b skews toward 0, and either shape
   parameter dropping below 1 pulls extra mass toward its own end (0
   for a < 1, 1 for b < 1). Useful directly as a controllable-shape
   random value already in 0-1 range -- a note's own probability of
   firing (algo.random.chance/weighted-coin's input), a normalized
   velocity/panning value -- without clamping an unbounded distribution
   into range afterward. a/b must be positive."
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
