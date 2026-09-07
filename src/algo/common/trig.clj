;; trig.clj
;; Clojure port of kotlin-reference/jl/trig.jl -- scaled, shifted
;; periodic samplers (Impromptu's own cosr/sinr/tanr): a deterministic
;; oscillator sampled at a beat/index rather than continuous time, a
;; periodic complement to algo.random's noise-based random-walk/
;; smooth-noise.

(ns algo.common.trig)

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
  "Value at idx along a triangle wave scaled by amp, shifted to base,
   completing one full cycle every period idxs -- the standard
   arcsin(sin(...)) closed form for a triangle wave (odd harmonics,
   amplitude falling off as 1/n^2 -- softer than square/saw), rather
   than summing that harmonic series directly: exact, O(1), and there's
   no aliasing concern to band-limit against at control-rate. The
   composed arcsin(sin(...)) can accumulate more floating-point error
   than a bare sin/cos alone -- confirmed live: two idxs a full period
   apart aren't always EXACTLY equal (off by ~1e-15 at some period/amp
   combinations), so compare with a small tolerance if you need to
   check periodicity, not `=`.

   (trianglr 0 2 10 8) ;=> 10.0
   (trianglr 2 2 10 8) ;=> 12.0
   (trianglr 4 2 10 8) ;=> 10.0
   (trianglr 6 2 10 8) ;=> 8.0
   (trianglr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (+ (* amp (/ 2 Math/PI) (Math/asin (Math/sin (/ (* 2 Math/PI idx) period)))) base))

(defn squarr
  "Value at idx along a square wave scaled by amp, shifted to base,
   completing one full cycle every period idxs -- amp/-amp plateaus via
   sign(sin(...)), the closed form for the odd-harmonics-at-1/n Fourier
   square wave (summing that series directly isn't needed for the same
   reason trianglr doesn't: exact, O(1), no band-limiting concern at
   control-rate).
   An idx landing EXACTLY on a zero-crossing (a multiple of period/2)
   is a genuine discontinuity in a square wave, not just a rounding
   inconvenience -- confirmed live, not just reasoned about: at
   period=8, idx=0 lands on sign(0.0)=0.0 exactly (=base, neither
   plateau), while idx=4 and idx=8 -- also exact zero-crossings --
   come back as the amp and -amp plateaus RESPECTIVELY, purely from
   which direction floating-point noise happened to push sin's own
   near-zero result there (Math/sin of exactly Math/PI is a tiny
   POSITIVE number, not 0.0; Math/sin of exactly 2*Math/PI is a tiny
   NEGATIVE one) -- not anything meaningful about the wave's true value
   at either point. Steer example idxs off those multiples, same
   spirit as tanr's own asymptote note.

   (squarr 1 2 10 8) ;=> 12.0
   (squarr 3 2 10 8) ;=> 12.0
   (squarr 5 2 10 8) ;=> 8.0
   (squarr 7 2 10 8) ;=> 8.0"
  [idx amp base period]
  (+ (* amp (Math/signum (Math/sin (/ (* 2 Math/PI idx) period)))) base))

(defn sawr
  "Value at idx along a sawtooth wave scaled by amp, shifted to base,
   completing one full cycle every period idxs -- the non-trig closed
   form (all harmonics, amplitude falling off as 1/n, brightest/buzziest
   of the three new waves here), a linear ramp via floor rather than
   summing the alternating-sign Fourier series directly (exact, O(1),
   no band-limiting concern at control-rate, same reasoning as
   trianglr/squarr above). Ramps from -amp up to +amp across each
   period, centered so idx=0 sits at the MIDPOINT of that ramp (=base)
   rather than at either end -- unlike squarr, this wave's one
   discontinuity per period falls at idx=period/2 exactly (a genuine
   reset, confirmed live: idx=4 at period=8 lands past the wrap, on the
   SAME plateau idx=5..7 approach, not the one idx=0..3 approach), so
   steer example idxs off that one point, same spirit as squarr/tanr's
   own notes above.

   (sawr 0 2 10 8) ;=> 10.0
   (sawr 2 2 10 8) ;=> 11.0
   (sawr 6 2 10 8) ;=> 9.0
   (sawr 8 2 10 8) ;=> 10.0"
  [idx amp base period]
  (let [ft (/ (double idx) period)]
    (+ (* 2 amp (- ft (Math/floor (+ ft 0.5)))) base)))

(defn tanr
  "Value at idx along a tangent wave scaled by amp, shifted to base,
   completing one full cycle every period idxs. Like any tangent curve,
   this has genuine asymptotes -- an idx landing on (or very near) an
   odd multiple of period/4 blows up to an enormous (not infinite,
   floating-point-near-singularity) magnitude; steer clear of those
   indices rather than relying on the value there.

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
