(ns algo.common.zfilter
  "Symbolic recurrence filters for algorithmic composition -- ported from
   a real design email (emails/messages/algorithm/Z filters, 2026-03-11)
   that had already worked out the general shape: a generic IIR-style
   recurrence engine (z-filter, named for the z-transform this mirrors)
   plus a handful of named convenience instances built on it.

   Genuinely distinct from everything else in algo.common: reshape's own
   invert/retrograde/arpeggiate/hocket/weighted-shuffle/lo-filter/
   hi-filter/window-filter all look at EITHER one part at a time OR the
   whole sequence's own static shape (an axis, a fixed cutoff) -- none
   of them carry state FORWARD note-to-note the way a real filter does.
   z-filter's own y[n] depends on y[n-1] (and further back, depending on
   how many feedback coefficients are given) -- smoothing a stream of
   pitches/velocities/durations so consecutive values pull toward each
   other, or exaggerating the difference between them (interval-gain),
   rather than reordering or thresholding a fixed set of values."
  (:require [core.domain.flat-domain :as d]
            [core.wall :as wall]))

;; ============================================================
;; Core z-filter engine
;; ============================================================

(defn z-filter
  "The generic recurrence filter -- b (feedforward coefficients) and a
   (feedback coefficients, a[0] must be 1, matching the source email's
   own convention) define:
     y[n] = sum(b[k] * x[n-k] for k in 0..) - sum(a[k] * y[n-k] for k in 1..)
   over xs (a plain seq of numbers), returning y as a vector, same
   length as xs. b/a index past the start of the sequence are simply
   skipped (treated as 0), matching the source's own 'if n-k >= 0'
   guard -- no wraparound, no assumed history before the sequence
   starts."
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

(defn smooth-intervals
  "Smooth the INTERVALS between consecutive values (not the absolute
   values themselves), then reconstruct the sequence from the smoothed
   intervals -- softens sudden melodic leaps while keeping the overall
   contour direction. A sequence of fewer than 2 values passes through
   unchanged (nothing to take an interval between)."
  [alpha xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      xs
      (let [intervals (mapv - (rest xs) xs)
            smoothed  (smooth alpha intervals)]
        (reduce (fn [acc iv] (conj acc (+ (peek acc) iv)))
                [(first xs)]
                smoothed)))))

(defn interval-gain
  "Multiply every interval between consecutive values by factor --
   factor > 1 exaggerates the melodic contour (bigger leaps, same
   overall shape), factor < 1 compresses it toward a flat line, factor
   = 1 is a no-op, negative factor inverts the contour. A sequence of
   fewer than 2 values passes through unchanged."
  [factor xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      xs
      (let [intervals (mapv - (rest xs) xs)
            scaled    (mapv * intervals (repeat factor))]
        (reduce (fn [acc iv] (conj acc (+ (peek acc) iv)))
                [(first xs)]
                scaled)))))

;; ============================================================
;; Pitch-class recurrence
;; ============================================================

(defn pc-smooth
  "Smooth pitch CLASSES (mod 12) rather than absolute pitch -- useful
   for smoothing harmonic/pitch-class motion independent of octave."
  [alpha xs]
  (smooth alpha (mapv #(mod % 12) xs)))

;; ============================================================
;; wall-fn factories -- applying a z-filter-based transform to a
;; container's own SIBLING BATCH of already-resolved parts (see
;; core.wall's own docstring: a wall-fn is called once per container
;; visit with the FULL sibling list, and again per already-produced
;; node singleton-wrapped -- a singleton call is a genuine no-op here,
;; since there's nothing to smooth across a single value).
;; ============================================================

(defn- pitches-of [part] (first (:pitches part)))

(defn smooth-pitch-algo
  "A core.wall FACTORY -- (fn [alpha] -> wall-fn) -- smoothing the
   PITCH stream of whatever container-batch of Leaf/Rest/Drum nodes
   it's handed, via the smooth filter above. Only Leaf nodes contribute
   a value to smooth (Rest/Drum pass through with their own pitch
   untouched, since neither has one); a batch with fewer than 2 Leafs
   is a no-op (nothing to smooth against).
     (register-algo! :smoothPitch smooth-pitch-algo nil :factory)
     (play :verse :algo [:smoothPitch 0.6])"
  [alpha]
  (fn [nodes _ctx-chain _voice]
    (let [leaf-idxs (keep-indexed (fn [i n] (when (d/leaf? n) i)) nodes)]
      (if (< (count leaf-idxs) 2)
        nodes
        (let [nodes-v    (vec nodes)
              raw        (mapv #(pitches-of (nth nodes-v %)) leaf-idxs)
              smoothed   (smooth alpha raw)
              rounded    (mapv #(Math/round (double %)) smoothed)]
          (reduce (fn [acc [idx new-pitch]]
                    (update acc idx assoc :pitches [new-pitch]))
                  nodes-v
                  (map vector leaf-idxs rounded)))))))
