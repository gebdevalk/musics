(ns ^:algo weighted-shuffle-test
  "musics.algo.common.reshape/weighted-shuffle: a shuffle whose draws follow a
   distribution fn (uniform, lo-emph, ...)."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.common.reshape :as reshape]
            [musics.algo.random :as rnd]))

;; ============================================================
;; weighted-shuffle -- pure fn, no registry involved
;; ============================================================

(defn- avg-displacement [n dist-fn trials]
  (double (/ (reduce + (for [_ (range trials)]
                          (let [perm (reshape/weighted-shuffle (vec (range n)) dist-fn)]
                            (reduce + (map-indexed (fn [out-pos orig-i] (Math/abs (- out-pos orig-i))) perm)))))
             (* trials n))))

(deftest weighted-shuffle-always-returns-a-permutation-of-the-input
  (let [parts [:a :b :c :d :e]]
    (dotimes [_ 50]
      (is (= (set parts) (set (reshape/weighted-shuffle parts rnd/uniform)))))))

(deftest weighted-shuffle-handles-0-and-1-element-collections
  (is (= [] (reshape/weighted-shuffle [] rnd/uniform)))
  (is (= [:only] (reshape/weighted-shuffle [:only] rnd/lo-emph))))

(deftest weighted-shuffle-with-uniform-reduces-to-an-ordinary-shuffle
  ;; Confirmed live in a throwaway probe before writing this: pick-
  ;; from-remaining with a uniform distribution reduces to the SAME
  ;; permutation distribution plain Fisher-Yates produces (~1.94 average
  ;; displacement at n=6 either way). Loose bound here, not an exact
  ;; match -- this is a real random process, not a deterministic one.
  (let [avg (avg-displacement 6 rnd/uniform 4000)]
    (is (< 1.5 avg 2.3) (str "avg displacement " avg " far from an ordinary shuffle's ~1.94"))))

(deftest weighted-shuffle-with-lo-emph-preserves-order-more-than-uniform
  ;; The actual claim this whole mechanism rests on, checked with real
  ;; trials, not just derived: lo-emph (peaked toward the LOW end)
  ;; biases picks toward the FRONT of what's remaining each step, so
  ;; elements tend to stay close to their original position -- a
  ;; meaningfully WEAKER shuffle than uniform's, not statistically
  ;; identical to it (an earlier, rejected 'sort by an independent key
  ;; per element' construction WOULD have been statistically identical
  ;; regardless of distribution shape -- confirmed and discarded before
  ;; building this one; see weighted-shuffle's own docstring).
  (let [uniform-avg (avg-displacement 6 rnd/uniform 4000)
        lo-emph-avg (avg-displacement 6 rnd/lo-emph 4000)]
    (is (< lo-emph-avg (* 0.85 uniform-avg))
        (str "lo-emph avg " lo-emph-avg " not meaningfully below uniform avg " uniform-avg))))
