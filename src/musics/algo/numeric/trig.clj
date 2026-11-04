;; Scaled, shifted periodic samplers (cosr, sinr, tanr, trianglr, squarr, sawr): a deterministic
;; oscillator sampled at an index, the periodic counterpart of walk and smooth-noise.

(ns musics.algo.numeric.trig)

(defn cosr
  "Value at idx along a cosine wave scaled by amp, shifted to base,
   completing one full cycle every period idxs.

   (cosr 0 2 10 8) ;=> 12.0
   (cosr 2 2 10 8) ;=> 10.0
   (cosr 4 2 10 8) ;=> 8.0
   (cosr 6 2 10 8) ;=> 10.0
   (cosr 8 2 10 8) ;=> 12.0"
  [idx amp base period]
  (+ (* amp (Math/cos (/ (* 2 Math/PI idx) period))) base))

(defn sinr
  "Value at idx along a sine wave scaled by amp, shifted to base,
   completing one full cycle every period idxs.

   (sinr 0 2 10 8) ;=> 10.0
   (sinr 2 2 10 8) ;=> 12.0
   (sinr 4 2 10 8) ;=> 10.0
   (sinr 6 2 10 8) ;=> 8.0
   (sinr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (+ (* amp (Math/sin (/ (* 2 Math/PI idx) period))) base))

(defn trianglr
  "The value at idx of a triangle wave scaled by amp, shifted to base, one full cycle every period idxs (closed form, exact, O(1)). Floating-point error can leave two idxs a full period apart off by about 1e-15: compare with a tolerance, not `=`.
     (trianglr 0 2 10 8) ;=> 10.0
     (trianglr 2 2 10 8) ;=> 12.0
     (trianglr 4 2 10 8) ;=> 10.0
     (trianglr 6 2 10 8) ;=> 8.0
     (trianglr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (+ (* amp (/ 2 Math/PI) (Math/asin (Math/sin (/ (* 2 Math/PI idx) period)))) base))

(defn squarr
  "The value at idx of a square wave: plateaus at base +/- amp, one full cycle every period idxs (sign of a sine, exact, O(1)). At an exact zero-crossing (a multiple of period/2) the value is a discontinuity: idx 0 gives base, idx period/2 and period give either plateau depending on floating-point noise. Avoid those idxs.
     (squarr 1 2 10 8) ;=> 12.0
     (squarr 3 2 10 8) ;=> 12.0
     (squarr 5 2 10 8) ;=> 8.0
     (squarr 7 2 10 8) ;=> 8.0"
  [idx amp base period]
  (+ (* amp (Math/signum (Math/sin (/ (* 2 Math/PI idx) period)))) base))

(defn sawr
  "The value at idx of a sawtooth wave: a linear ramp from base - amp to base + amp over each period of idxs, centred so idx 0 is at base. The one discontinuity per period is at idx period/2, where the ramp resets; avoid that idx.
     (sawr 0 2 10 8) ;=> 10.0
     (sawr 2 2 10 8) ;=> 11.0
     (sawr 6 2 10 8) ;=> 9.0
     (sawr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (let [ft (/ (double idx) period)]
    (+ (* 2 amp (- ft (Math/floor (+ ft 0.5)))) base)))

(defn tanr
  "The value at idx of a tangent wave scaled by amp, shifted to base, one full cycle every period idxs. It has real asymptotes: an idx at or near an odd multiple of period/4 gives an enormous value; avoid those idxs.
     (tanr 0 2 10 8) ;=> 10.0
     (tanr 4 2 10 8) ;=> 10.0
     (tanr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (+ (* amp (Math/tan (/ (* 2 Math/PI idx) period))) base))

(comment
  (mapv #(cosr % 2 10 8) [0 2 4 6 8])
  (mapv #(sinr % 2 10 8) [0 2 4 6 8])
  (mapv #(tanr % 2 10 8) [0 4 8])
  )
