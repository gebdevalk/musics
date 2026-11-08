;; Scaled, shifted periodic samplers (cosr, sinr, tanr, trianglr, squarr, sawr): a deterministic
;; oscillator sampled at an index, the periodic counterpart of walk and smooth-noise.
;; The same waves between two values, shifted by a phase (sine, cosine, triangle, square, saw).

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

;; ---- the waves between lo and hi -------------------------------------------
;; Period is always the last argument; what is left out is 1 (a period, an
;; amplitude) or 0 (a phase), and the wave swings between -amp and +amp unless
;; lo and hi are given:
;;   (w idx)                      period 1, amplitude 1
;;   (w idx period)               amplitude 1
;;   (w idx amp period)           between -amp and amp
;;   (w idx lo hi period)
;;   (w idx lo hi phase period)   started phase of a cycle late (0 .. 1)
;; A phase of 0.25 is a quarter of a cycle ahead: a sine at 0.25 begins at hi.
;; Every wave starts as its sinr, cosr, .. does: sine, triangle and saw halfway
;; and rising, cosine at hi and falling, square at hi for the first half cycle.

(defn- between
  "wave (one of sinr .. sawr) at idx, shifted by phase of a cycle, between lo and hi."
  [wave idx lo hi phase period]
  (wave (+ idx (* phase period)) (/ (- hi lo) 2.0) (/ (+ hi lo) 2.0) period))

(defn sine
  "A sine wave at idx. period (one by default) idxs make a cycle, the amplitude is one unless amp is given or lo and hi, between which it swings; phase (0 .. 1, of a cycle) shifts it. At phase 0 it starts halfway and rises.

   (sine 2 8) ;=> 1.0
   (sine 2 3 8) ;=> 3.0
   (sine 2 60 90 8) ;=> 90.0
   (sine 0 60 90 0.25 8) ;=> 90.0"
  {:algo {:arity 5}}
  ([idx] (sine idx 1 1))
  ([idx period] (sine idx 1 period))
  ([idx amp period] (sine idx (- amp) amp 0 period))
  ([idx lo hi period] (sine idx lo hi 0 period))
  ([idx lo hi phase period] (between sinr idx lo hi phase period)))

(defn cosine
  "A cosine wave at idx; the arguments are sine's. At phase 0 it starts at hi and falls.

   (cosine 0 60 90 8) ;=> 90.0
   (cosine 4 60 90 8) ;=> 60.0
   (cosine 0 60 90 0.5 8) ;=> 60.0"
  {:algo {:arity 5}}
  ([idx] (cosine idx 1 1))
  ([idx period] (cosine idx 1 period))
  ([idx amp period] (cosine idx (- amp) amp 0 period))
  ([idx lo hi period] (cosine idx lo hi 0 period))
  ([idx lo hi phase period] (between cosr idx lo hi phase period)))

(defn triangle
  "A triangle wave at idx; the arguments are sine's. At phase 0 it starts halfway and rises. Within about 1e-15 of exact.

   (triangle 2 60 90 8) ;=> 90.0
   (triangle 6 60 90 8) ;=> 60.0"
  {:algo {:arity 5}}
  ([idx] (triangle idx 1 1))
  ([idx period] (triangle idx 1 period))
  ([idx amp period] (triangle idx (- amp) amp 0 period))
  ([idx lo hi period] (triangle idx lo hi 0 period))
  ([idx lo hi phase period] (between trianglr idx lo hi phase period)))

(defn square
  "A square wave at idx: hi for the first half cycle, lo for the second; the arguments are sine's. Avoid the idxs where it jumps (multiples of period/2, less the phase).

   (square 1 60 90 8) ;=> 90.0
   (square 5 60 90 8) ;=> 60.0"
  {:algo {:arity 5}}
  ([idx] (square idx 1 1))
  ([idx period] (square idx 1 period))
  ([idx amp period] (square idx (- amp) amp 0 period))
  ([idx lo hi period] (square idx lo hi 0 period))
  ([idx lo hi phase period] (between squarr idx lo hi phase period)))

(defn saw
  "A sawtooth wave at idx: a ramp up over each cycle; the arguments are sine's. At phase 0 it starts halfway. Avoid the idx where it resets (period/2, less the phase).

   (saw 0 60 90 8) ;=> 75.0
   (saw 2 60 90 8) ;=> 82.5
   (saw 6 60 90 8) ;=> 67.5"
  {:algo {:arity 5}}
  ([idx] (saw idx 1 1))
  ([idx period] (saw idx 1 period))
  ([idx amp period] (saw idx (- amp) amp 0 period))
  ([idx lo hi period] (saw idx lo hi 0 period))
  ([idx lo hi phase period] (between sawr idx lo hi phase period)))

(comment
  (mapv #(cosr % 2 10 8) [0 2 4 6 8])
  (mapv #(sinr % 2 10 8) [0 2 4 6 8])
  (mapv #(tanr % 2 10 8) [0 4 8])
  )
