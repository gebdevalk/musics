;; chance.clj
;; Clojure port of kotlin-reference/jl/chance.jl -- discrete/collection
;; probability helpers. weighted-choose/only/sputter were already drafted
;; in Clojure inside that file's own trailing comment block; cleaned up
;; and wired in here rather than rewritten from scratch. chosen-from and
;; weighted-coin fix two bugs found in the Julia source (see their
;; docstrings); rand-int wasn't ported at all -- clojure.core/rand-int
;; already does exactly what it did. ranged-rand was dropped in favor of
;; algo.random.distributions/rand-uniform (the same sampler, ported
;; separately from random.jl); weighted-choose absorbed algo.random.rand's
;; weighted-item, the more permissive of the two near-duplicates -- see
;; its own docstring.

(ns algo.random.chance)

(defn choose
  "Choose a random element from coll."
  [coll]
  (rand-nth (vec coll)))

(defn choose-n
  "Choose n random elements from coll, without replacement."
  [n coll]
  (vec (take n (shuffle coll))))

(defn chosen-from
  "Return a vector of (count coll) random elements from coll, with
   replacement. (The Julia source's own body returned random indices,
   not elements, despite its docstring promising 'random notes from
   coll' -- this port matches the docstring instead.)"
  [coll]
  (let [v (vec coll)
        n (count v)]
    (vec (repeatedly n #(rand-nth v)))))

(defn weighted-coin
  "Returns true or false; probability of true is n, clamped to [0, 1].
   (The Julia source clamped its upper bound to .1 instead of 1.0 --
   read as a decimal-point typo against its own docstring, which
   promises the range 0-1; fixed here.)"
  [n]
  (let [n (cond (< n 0.0) 0.0 (> n 1.0) 1.0 :else n)]
    (< (rand) n)))

(defn weighted-choose
  "Returns an element from vals with probability proportional to its
   corresponding weight in weights. Weights need not sum to 1 -- they're
   normalized against their own total, so raw/unnormalized weights (e.g.
   Markov transition counts) work directly. It's also possible to pass a
   single map of val -> weight as a param.

   (weighted-choose [1 2 3 4] [3 2 1 1])
   (weighted-choose {1 3, 2 2, 3 1, 4 1})"
  ([val-weight-map] (weighted-choose (keys val-weight-map) (vals val-weight-map)))
  ([vals weights]
   (let [total (reduce + weights)
         r     (* total (rand))]
     (loop [w weights, vs vals, acc 0]
       (if (<= (+ acc (first w)) r)
         (recur (rest w) (rest vs) (+ acc (first w)))
         (first vs))))))

(defn only
  "Take only the specified notes from the given phrase."
  ([phrase notes] (only phrase notes []))
  ([phrase notes result]
   (if notes
     (recur phrase
            (next notes)
            (conj result (get phrase (first notes))))
     result)))

(defn sputter
  "Returns a list where some elements may have been repeated.

   Repetition is based on probability (defaulting to 0.25), therefore,
   for each element in the original list, there's a chance that it will
   be repeated. (The repetitions themselves are also subject to further
   repetition). The size of the resulting list can be constrained to max
   elements (defaulting to 100).

  (sputter [1 2 3 4])        ;=> [1 1 2 3 3 4]
  (sputter [1 2 3 4] 0.7 5)  ;=> [1 1 1 2 3]
  (sputter [1 2 3 4] 0.8 10) ;=> [1 2 2 2 2 2 2 2 3 3]
  (sputter [1 2 3 4] 1 10)   ;=> [1 1 1 1 1 1 1 1 1 1]
  "
  ([lst]          (sputter lst 0.25))
  ([lst prob]     (sputter lst prob 100))
  ([lst prob max] (sputter lst prob max []))
  ([[head & tail] prob max result]
   (if (and head (< (count result) max))
     (if (< (rand) prob)
       (recur (cons head tail) prob max (conj result head))
       (recur tail prob max (conj result head)))
     result)))

(comment
  (choose [1 2 3 4 5 6])
  (choose-n 5 [1 2 3 4 5 6])
  (chosen-from [1 2 3 4 5 6 7])
  (weighted-coin 0.5)
  (weighted-choose [1 2 3 4] [3 2 1 1])
  (sputter [1 2 3 4])
  )
