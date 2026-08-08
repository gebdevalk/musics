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

(defn beat-probabilities
  "Softmax over a vector of indispensability ranks (or any weights),
   temperature-scaled by adherence -- higher adherence pushes probability
   mass toward the more indispensable (higher-ranked) pulses more
   sharply; adherence near 0 flattens toward uniform."
  [psi-vals adherence]
  (let [exps  (mapv #(Math/exp (* % adherence)) psi-vals)
        total (reduce + exps)]
    (mapv #(/ % total) exps)))

(comment
  (indispensability [2 2 3])       ;; => [11 0 4 8 2 6 10 1 5 9 3 7]
  (beat-probabilities (indispensability [2 2]) 0.5)
  )
