;; trig.clj
;; Clojure port of kotlin-reference/jl/trig.jl -- scaled, shifted
;; periodic samplers (Impromptu's own cosr/sinr/tanr): a deterministic
;; oscillator sampled at a beat/index rather than continuous time, a
;; periodic complement to algo.random's noise-based random-walk/
;; smooth-noise.

(ns algo.common.trig)

(defn cosr
  "Value at idx along a cosine wave scaled by amp, shifted to center,
   completing one full cycle every period idxs.

   (cosr 0 2 10 8) ;=> 12.0
   (cosr 2 2 10 8) ;=> 10.0
   (cosr 4 2 10 8) ;=> 8.0
   (cosr 6 2 10 8) ;=> 10.0
   (cosr 8 2 10 8) ;=> 12.0"
  [idx amp center period]
  (+ (* amp (Math/cos (/ (* 2 Math/PI idx) period))) center))

(defn sinr
  "Value at idx along a sine wave scaled by amp, shifted to center,
   completing one full cycle every period idxs.

   (sinr 0 2 10 8) ;=> 10.0
   (sinr 2 2 10 8) ;=> 12.0
   (sinr 4 2 10 8) ;=> 10.0
   (sinr 6 2 10 8) ;=> 8.0
   (sinr 8 2 10 8) ;=> 10.0"
  [idx amp center period]
  (+ (* amp (Math/sin (/ (* 2 Math/PI idx) period))) center))

(defn tanr
  "Value at idx along a tangent wave scaled by amp, shifted to center,
   completing one full cycle every period idxs. Like any tangent curve,
   this has genuine asymptotes -- an idx landing on (or very near) an
   odd multiple of period/4 blows up to an enormous (not infinite,
   floating-point-near-singularity) magnitude; steer clear of those
   indices rather than relying on the value there.

   (tanr 0 2 10 8) ;=> 10.0
   (tanr 4 2 10 8) ;=> 10.0
   (tanr 8 2 10 8) ;=> 10.0"
  [idx amp center period]
  (+ (* amp (Math/tan (/ (* 2 Math/PI idx) period))) center))

(comment
  (mapv #(cosr % 2 10 8) [0 2 4 6 8])
  (mapv #(sinr % 2 10 8) [0 2 4 6 8])
  (mapv #(tanr % 2 10 8) [0 4 8])
  )
