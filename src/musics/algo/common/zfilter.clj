(ns musics.algo.common.zfilter
  "Recurrence filters over a stream of numbers: a generic IIR-style engine (z-filter, after the z-transform) and named instances built on it (smooth, momentum, memory, interval-gain ...).

   Unlike reshape and gate, which look at one part at a time or at the sequence's static shape, a filter carries state forward note to note: y[n] depends on y[n-1] and further back. It smooths pitches, velocities or durations so consecutive values pull toward each other, or exaggerates the difference between them (interval-gain), instead of reordering or thresholding a fixed set of values.")

;; ============================================================
;; Core z-filter engine
;; ============================================================

(defn z-filter
  "Recurrence filter over xs (numbers): y[n] = sum(b[k]·x[n-k]) - sum(a[k]·y[n-k], k >= 1), with a[0] = 1. Indexes before the start are skipped (read as 0). Returns a vector as long as xs."
  [b a xs]
  (when (not= 1 (first a))
    (throw (ex-info "z-filter: (first a) must be 1" {:a a})))
  (let [xs (vec xs)
        n  (count xs)
        y  (object-array n)]
    (dotimes [i n]
      (let [ff (reduce + (map-indexed
                            (fn [k bk] (if (>= (- i k) 0) (* bk (nth xs (- i k))) 0))
                            b))
            fb (reduce + (map-indexed
                            (fn [k ak] (if (and (pos? k) (>= (- i k) 0))
                                         (* ak (aget y (- i k)))
                                         0))
                            a))]
        (aset y i (- ff fb))))
    (vec y)))

;; ============================================================
;; Named convenience instances -- each just picks b/a for a musically
;; meaningful shape, exactly as the source email's own constructors do
;; ============================================================

(defn smooth
  "One-pole smoothing: y[n] = (1-alpha)*x[n] + alpha*y[n-1]. alpha in
   [0,1) -- higher alpha means more smoothing (more of the previous
   value carried forward, less of the raw new one)."
  [alpha xs]
  (z-filter [(- 1 alpha)] [1 (- alpha)] xs))

(defn momentum
  "Momentum/inertia: y[n] = x[n] + beta*(y[n-1] - x[n-1]) -- the
   filtered sequence tends to keep moving in whatever direction it was
   already heading, resisting sudden reversals."
  [beta xs]
  (z-filter [(+ 1 beta) (- beta)] [1 (- beta)] xs))

(defn memory
  "Decay/memory: y[n] = x[n] + decay*y[n-1] -- each value's own
   influence lingers, decaying geometrically, rather than being fully
   replaced by the next one."
  [decay xs]
  (z-filter [1] [1 (- decay)] xs))

;; ============================================================
;; Interval-based recurrence -- smooth/exaggerate melodic CONTOUR
;; (the differences between consecutive values) rather than the
;; absolute values themselves
;; ============================================================

(defn- interval-transform
  "Shared skeleton of smooth-intervals and interval-gain: fewer than 2 values pass through; otherwise xs is diffed into intervals, xform runs over them, and the sequence is rebuilt from xs's first value."
  [xform xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      xs
      (reduce (fn [acc iv] (conj acc (+ (peek acc) iv)))
              [(first xs)]
              (xform (mapv - (rest xs) xs))))))

(defn smooth-intervals
  "Smooth the INTERVALS between consecutive values (not the absolute
   values themselves), then reconstruct the sequence from the smoothed
   intervals -- softens sudden melodic leaps while keeping the overall
   contour direction. A sequence of fewer than 2 values passes through
   unchanged (nothing to take an interval between)."
  [alpha xs]
  (interval-transform #(smooth alpha %) xs))

(defn interval-gain
  "Multiply every interval between consecutive values by factor --
   factor > 1 exaggerates the melodic contour (bigger leaps, same
   overall shape), factor < 1 compresses it toward a flat seq, factor
   = 1 is a no-op, negative factor inverts the contour. A sequence of
   fewer than 2 values passes through unchanged."
  [factor xs]
  (interval-transform #(mapv * % (repeat factor)) xs))

;; ============================================================
;; Pitch-class recurrence
;; ============================================================

(defn pc-smooth
  "Smooth pitch CLASSES (mod 12) rather than absolute pitch -- useful
   for smoothing harmonic/pitch-class motion independent of octave."
  [alpha xs]
  (smooth alpha (mapv #(mod % 12) xs)))
