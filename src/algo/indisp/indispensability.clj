;; indispensability.clj
;; Barlow indispensability -- the canonical implementation. Moved here
;; from common/music_elements.clj (which now requires this ns and
;; delegates, rather than keeping its own copy) since this file's own
;; earlier psi/psi-fractions -- a direct port of pymusics src/algorithm/'s
;; indispensability.py/Indispensabilities.kt -- turned out to be a subtly
;; incomplete implementation of the same theory: it combines each level's
;; raw digit directly, skipping Barlow's own non-trivial substitution
;; table for factors 5 and 7 (indispensability-base-tables below). That
;; happens to not matter for factors 2 and 3 (their tables reduce to the
;; identity permutation, so psi agreed with this implementation there,
;; off by a constant +1/0-indexing difference) -- but for 5 and 7 it's a
;; real divergence, not an indexing artifact: e.g. psi's own [5] case
;; disagreed with the verified-correct [4 0 1 3 2] in its last two
;; positions. psi/psi-fractions were removed rather than kept alongside
;; the correct version once that was confirmed.

(ns algo.indisp.indispensability)

;; Indispensability for a single-level cycle of q pulses (0-indexed,
;; downbeat = q-1). q=2/q=3 are simple rotations; q=5/q=7 are Barlow's
;; real, non-trivial anacrusis-breaking pattern -- verified against a
;; known-correct reference, not derivable from the q=2/q=3 case by
;; extrapolation. Only these four are supported: real meters always
;; decompose additively into them (see common.music-elements/
;; default-subdivisions), so a genuine bare prime cycle beyond 7 never
;; actually arises.
(def ^:private indispensability-base-tables
  {2 [1 0]
   3 [2 0 1]
   5 [4 0 1 3 2]
   7 [6 0 1 3 5 2 4]})

(defn- indispensability-digit-fn
  "The base table for q, rotated left by one position so it aligns with
   the internal d = (n-1 mod Q) convention indispensability-at uses below.
   For q=2/3 this happens to reduce to the identity permutation (their
   base tables are pure rotations, (n-1) mod q); for q=5/7 it doesn't --
   that difference is exactly the non-trivial part of Barlow's theory."
  [q]
  (if-let [t (get indispensability-base-tables q)]
    (vec (concat (rest t) [(first t)]))
    (throw (ex-info (str "No indispensability base table for factor " q
                         " -- only 2, 3, 5, and 7 are supported.")
                    {:factor q}))))

(defn- pi-product
  "Product of subdivisions[start..stop), 1 if the range is empty."
  [subdivisions start stop]
  (reduce * 1 (subvec (vec subdivisions) start stop)))

(defn- indispensability-at
  "Indispensability of pulse n (any integer, reduced mod Q) in a cycle
   built from subdivisions (an ordered factor sequence, e.g. [2 2 3]),
   Q = product of subdivisions. Recombines each level's own base-table
   rank (via indispensability-digit-fn) using the same place-value
   structure as the pulse index itself, so the result is guaranteed a
   permutation of 0..Q-1 with the downbeat (n=0) always mapping to Q-1."
  [n Q subdivisions]
  (let [n (rem n Q)
        q (count subdivisions)
        d (mod (+ (dec n) Q) Q)]
    (loop [i 0 r 0]
      (if (< i q)
        (let [i'     (- q i 1)
              a      (pi-product subdivisions 0 i')
              b      (pi-product subdivisions (- q i) q)
              c      (nth subdivisions i')
              digit  (mod (quot d b) c)
              digit' (nth (indispensability-digit-fn c) digit)]
          (recur (inc i) (+ r (* a digit'))))
        r))))

(defn indispensability
  "Barlow indispensability for a meter whose beats decompose into
   subdivisions (an ordered factor sequence, e.g. [2 2 3] for 12/8's
   default grouping -- see common.music-elements/default-subdivisions/
   Meter). Returns a vector of N ranks (0..N-1, downbeat pulse always
   N-1), one per pulse position 0..N-1, where N is the product of
   subdivisions. Each factor must be 2, 3, 5, or 7 (see
   indispensability-digit-fn)."
  [subdivisions]
  (let [Q (reduce * 1 subdivisions)]
    (mapv #(indispensability-at % Q subdivisions) (range Q))))

(defn normalize-weights
  "Divide weights by their own max, landing them in [0,1] regardless of
   how many there are or how large the raw values are. Shared by
   tilt-probabilities/power-law-probabilities so adherence means the
   same thing in both regardless of meter size -- also public and
   general-purpose on its own, for normalizing any already-reshaped
   weight vector (see normalized-indispensability below)."
  [weights]
  (let [mx (double (apply max weights))]
    (mapv #(/ % mx) weights)))

(defn normalized-indispensability
  "indispensability + normalize-weights in one step: subdivisions ->
   raw integer ranks -> normalized [0,1] floats. The natural starting
   point for chaining ordinary seq transforms on top -- reverse,
   algo.random/shuffle, algo.common.rotate/rotate (a 'shift'), or
   anything else -- before finally handing the result to
   algo.common.pulse/grid->pulses (any weighted range, not just 0/1) or
   density-grid (for a thinned binary grid). Plain data in, plain
   vector out -- normalize-weights doesn't care whether its input came
   straight from indispensability or was already reshaped by something
   else first, so this is just the common case pre-wired, not a new
   mechanism of its own.
     (-> (normalized-indispensability [2 2 3])
         reverse
         (algo.common.rotate/rotate 3)
         algo.common.pulse/grid->pulses)"
  [subdivisions]
  (normalize-weights (indispensability subdivisions)))

;; The golden ratio -- an arbitrary but conventional choice of
;; irrational constant (any irrational works; this one is a common
;; choice for anti-collision/quasi-random nudges elsewhere too), scaled
;; down to a magnitude far below anything musically audible.
(def ^:private tie-break-phi (* 1e-6 (/ (+ 1 (Math/sqrt 5)) 2)))

(defn tilt-probabilities
  "Softmax over a vector of indispensability ranks (or any weights),
   temperature-scaled by adherence -- higher adherence pushes probability
   mass toward the more indispensable (higher-ranked) pulses more
   sharply. Never produces an exact tie, even at adherence=0 (where a
   plain softmax would collapse every pulse to identical probability,
   since every exponent becomes exp(0)=1): a tiny, IRRATIONAL,
   POSITION-based term (tie-break-phi * i, i the pulse's own index) is
   added INSIDE the exponent, deliberately outside adherence's own
   scaling, so it survives no matter what adherence is. At adherence=0
   only that term is left, still strictly increasing in position --
   never flat. Being irrational, two pulses can only ever tie at an
   irrational value of adherence, practically unreachable by any real
   input -- see doc/decisions.md's 2026-09-06 entry. Weights are
   normalized to [0,1] by their own max first (not indispensability's
   own job -- see its docstring -- since its raw ranks must stay an
   exact, reference-table-verified 0..N-1 permutation), so the same
   adherence value means the same thing regardless of how many pulses
   the meter has."
  [psi-vals adherence]
  (let [norm  (normalize-weights psi-vals)
        exps  (map-indexed (fn [i v] (Math/exp (+ (* v adherence) (* tie-break-phi i))))
                            norm)
        total (reduce + exps)]
    (mapv #(/ % total) exps)))

;; Exponent at |adherence|=1 -- deliberately chosen, not rigorously
;; derived (same spirit as async-engine's own humanize-max-jitter-secs):
;; steep enough that the single strongest pulse dominates almost
;; completely, without the risk of overflow an unbounded mapping
;; (e.g. 1/(1-|adherence|), infinite right at the edge) would have.
(def ^:private power-law-max-exponent 8.0)

(defn power-law-probabilities
  "Power-law reshaping over a vector of indispensability ranks (or any
   weights): raises normalized weights to an exponent driven by
   adherence, always ORDER-PRESERVING (or, for negative adherence,
   order-REVERSING) -- unlike tilt-probabilities' softmax, this never
   re-ranks anything by blending; it only changes how steeply
   probability mass falls off between the existing strong/weak
   positions.

   adherence >= 0 exponentiates the normalized weight directly (steepens
   toward the MORE indispensable positions as adherence -> 1);
   adherence < 0 exponentiates its COMPLEMENT, (1 - normalized weight),
   instead (steepens toward the LESS indispensable positions as
   adherence -> -1 -- a genuine continuous inversion, not just a flatten-
   toward-uniform). Both branches agree exactly at adherence=0 (exponent
   1, so weight = the raw normalized rank itself, distinct for every
   pulse -- no collapse, and no irrational tie-break hack needed here,
   unlike tilt-probabilities: an exponent of exactly 0 is the only value
   that could ever tie two distinct positive bases together, and this
   fn's exponent never goes below 1).

   A genuinely different consequence from tilt-probabilities, not just a
   different curve shape: exp(anything) is always > 0, so softmax never
   assigns a pulse literal zero probability, however extreme adherence
   gets -- power-law does, for whichever position's own base is exactly
   0 (the least-indispensable pulse when adherence >= 0, the downbeat
   itself when adherence < 0), at every adherence including 0."
  [psi-vals adherence]
  (let [norm    (normalize-weights psi-vals)
        k       (+ 1.0 (* power-law-max-exponent (Math/abs (double adherence))))
        base    (if (neg? adherence) (mapv #(- 1.0 %) norm) norm)
        weights (mapv #(Math/pow % k) base)
        total   (reduce + weights)]
    (mapv #(/ % total) weights)))

(defn density-grid
  "Binary onset grid (1=keep, 0=silent), retaining exactly the
   (Math/round (* (count ranks) density)) most indispensable positions
   -- deterministic, the SAME subset every call for a given ranks/
   density pair (a fixed metric 'skeleton' thinning, not a per-call
   random draw -- ties broken by original position order, via a stable
   sort). ranks: indispensability ranks (or any weights, same
   generality as tilt-probabilities); density: 0.0-1.0, fraction of
   pulses to keep. Feeds algo.common.pulse/grid->pulses directly, same
   as any other binary rhythm-generator grid."
  [ranks density]
  (let [n    (count ranks)
        k    (Math/round (* n (double density)))
        keep (->> (map-indexed vector ranks)
                  (sort-by second >)
                  (take k)
                  (map first)
                  set)]
    (mapv #(if (contains? keep %) 1 0) (range n))))

(comment
  (indispensability [2 2 3])       ;; => [11 0 4 8 2 6 10 1 5 9 3 7]
  (tilt-probabilities (indispensability [2 2]) 0.5)
  (tilt-probabilities (indispensability [2 2]) 0.0)   ;; distinct, not [.25 .25 .25 .25]
  (power-law-probabilities (indispensability [2 2]) 0.8)
  (power-law-probabilities (indispensability [2 2]) -0.8)
  (density-grid (indispensability [2 2]) 0.5)   ;; => [1 0 1 0]
  (normalized-indispensability [2 2])   ;; => [1.0 0.0 0.6666... 0.3333...]
  )
