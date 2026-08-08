;; chance.clj
;; Clojure port of kotlin-reference/jl/chance.jl -- discrete/collection
;; probability helpers. weighted-choose/only/sputter were already drafted
;; in Clojure inside that file's own trailing comment block; cleaned up
;; and wired in here rather than rewritten from scratch. chosen-from and
;; weighted-coin fix two bugs found in the Julia source (see their
;; docstrings); rand-int wasn't ported at all -- clojure.core/rand-int
;; already does exactly what it did.

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

(defn ranged-rand
  "Returns a random value within the range [min, max)."
  [mn mx]
  (+ (* (rand) (- mx mn)) mn))

(defn weighted-choose
  "Returns an element from vals based on the corresponding probabilities
   list. The length of vals and probabilities should be similar and the
   sum of all the probabilities should be 1. It is also possible to pass
   a map of val -> prob pairs as a param.

   The following will return one of the following vals with the
   corresponding probabilities:
   1 -> 50%
   2 -> 30%
   3 -> 12.5%
   4 -> 7.5%
   (weighted-choose [1 2 3 4] [0.5 0.3 0.125 0.075])
   (weighted-choose {1 0.5, 2 0.3, 3 0.125, 4 0.075})"
  ([val-prob-map] (weighted-choose (keys val-prob-map) (vals val-prob-map)))
  ([vals probabilities]
   (when-not (= (count vals) (count probabilities))
     (throw (IllegalArgumentException.
             (str "Size of vals and probabilities don't match. Got "
                  (count vals) " and " (count probabilities)))))
   (when-not (== (reduce + probabilities) 1.0)
     (throw (IllegalArgumentException. "The sum of your probabilities is not 1.0")))
   (let [paired (map vector probabilities vals)
         sorted (sort #(< (first %1) (first %2)) paired)
         summed (loop [todo sorted done [] cumulative 0]
                  (if (empty? todo)
                    done
                    (let [f-prob     (ffirst todo)
                          f-val      (second (first todo))
                          cumulative (+ cumulative f-prob)]
                      (recur (rest todo)
                             (conj done [cumulative f-val])
                             cumulative))))
         rand-num (rand)]
     (loop [summed summed]
       (when (empty? summed)
         (throw (Exception. "Error, reached end of weighted choice options")))
       (if (< rand-num (ffirst summed))
         (second (first summed))
         (recur (rest summed)))))))

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
  (ranged-rand 0.1 0.2)
  (weighted-choose [1 2 3 4] [0.5 0.3 0.125 0.075])
  (sputter [1 2 3 4])
  )
