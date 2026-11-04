;; Barlow indispensability; musics.common.music-elements/meter-indispensability delegates here. Factors
;; 5 and 7 need Barlow's substitution table (indispensability-base-tables); 2 and 3 reduce to plain
;; rotations.

(ns musics.algo.rhythmic.indispensability)

;; Indispensability of a single-level cycle of q pulses (0-indexed, downbeat = q-1). q = 2 and 3 are
;; simple rotations; 5 and 7 are Barlow's non-trivial anacrusis-breaking patterns. Only these four are
;; supported: real meters decompose additively into them (musics.common.music-elements/default-
;; subdivisions).
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
  "The indispensability of pulse n (any integer, reduced mod Q) in a cycle of subdivisions (an ordered factor sequence, e.g. [2 2 3]), Q their product. Each level's base-table rank (indispensability-digit-fn) is recombined by the place values of the pulse index, so the ranks are a permutation of 0..Q-1 with the downbeat (n=0) at Q-1."
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

(defn indisp
  "Barlow indispensability for a meter whose beats decompose into
   subdivisions (an ordered factor sequence, e.g. [2 2 3] for 12/8's
   default grouping -- see musics.common.music-elements/default-subdivisions/
   Meter). Returns a vector of N ranks (0..N-1, downbeat pulse always
   N-1), one per pulse position 0..N-1, where N is the product of
   subdivisions. Each factor must be 2, 3, 5, or 7 (see
   indispensability-digit-fn)."
  {:algo {:in [] :out :weight
          :params {:subdivisions {:type :vector :default [2 2 3]
                                  :doc "meter's factor grouping, each 2, 3, 5 or 7"}}}}
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
  "indispensability and normalize-weights in one step: subdivisions -> ranks as [0,1] floats. The starting point for chaining ordinary seq transforms (reverse, shuffle, rotate) before grid->pulses (any weighted range) or density-grid (a thinned binary grid).
     (-> (normalized-indispensability [2 2 3])
         reverse
         (musics.algo.common.rotate/rotate 3)
         musics.algo.common.pulse/grid->pulses)"
  [subdivisions]
  (normalize-weights (indisp subdivisions)))

;; The golden ratio -- an arbitrary but conventional choice of
;; irrational constant (any irrational works; this one is a common
;; choice for anti-collision/quasi-random nudges elsewhere too), scaled
;; down to a magnitude far below anything musically audible.
(def ^:private tie-break-phi (* 1e-6 (/ (+ 1 (Math/sqrt 5)) 2)))

(defn tilt
  "Softmax over indispensability ranks (or any weights), temperature-scaled by adherence: higher adherence pushes probability towards the more indispensable pulses. Weights are first normalised to [0,1] by their max, so an adherence means the same for any meter. No two pulses tie, even at adherence 0: a tiny irrational position term (tie-break-phi * i) is added inside the exponent, outside adherence's scaling, so at 0 the probabilities still rise with position."
  {:algo {:in [:weight] :out :weight
          :params {:adherence {:type :double :min -1.0 :max 1.0 :default 0.5
                               :doc "how strongly rank predicts sounding (negative inverts)"}}}}
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

(defn power
  "Power-law reshaping of indispensability ranks (or any weights): the normalised weight raised to an exponent driven by adherence. It never re-ranks: adherence >= 0 steepens towards the more indispensable positions as it approaches 1; adherence < 0 raises the complement (1 - weight) and steepens towards the less indispensable ones, a continuous inversion. At 0 the weight is the normalised rank itself, distinct for every pulse. Unlike tilt, which never gives a probability of exactly 0, power gives 0 to the position whose base is 0: the least indispensable pulse when adherence >= 0, the downbeat when < 0."
  {:algo {:in [:weight] :out :weight
          :params {:adherence {:type :double :min -1.0 :max 1.0 :default 0.5
                               :doc "how strongly rank predicts sounding (negative inverts)"}}}}
  [psi-vals adherence]
  (let [norm    (normalize-weights psi-vals)
        k       (+ 1.0 (* power-law-max-exponent (Math/abs (double adherence))))
        base    (if (neg? adherence) (mapv #(- 1.0 %) norm) norm)
        weights (mapv #(Math/pow % k) base)
        total   (reduce + weights)]
    (mapv #(/ % total) weights)))

(defn density-grid
  "A binary onset grid (1 keep, 0 silent) keeping exactly (Math/round (* (count ranks) density)) of the most indispensable positions: deterministic, the same subset on every call, ties broken by position. ranks: indispensability ranks or any weights; density: 0.0-1.0, the fraction of pulses kept. Feeds grid->pulses like any binary grid. The tree algo is musics.algo.bridge/weights->pulses."
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
  (indisp [2 2 3])       ;; => [11 0 4 8 2 6 10 1 5 9 3 7]
  (tilt (indisp [2 2]) 0.5)
  (tilt (indisp [2 2]) 0.0)   ;; distinct, not [.25 .25 .25 .25]
  (power (indisp [2 2]) 0.8)
  (power (indisp [2 2]) -0.8)
  (density-grid (indisp [2 2]) 0.5)   ;; => [1 0 1 0]
  (normalized-indispensability [2 2])   ;; => [1.0 0.0 0.6666... 0.3333...]
  )
