(ns ^:algo transform-rhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.rhythmic.transform :as tr]))

(deftest emi-style-variation-never-crashes-and-stays-in-bounds
  ;; the reference this ports throws IndexError in ~27% of runs at
  ;; similarity=0.3 -- this port must never do that, and must stay
  ;; within [len/2, len*2]
  (dotimes [_ 50]
    (let [pattern [1 0 1 0 1 0 1 0]
          varied (tr/emi-style-variation pattern 0.3)]
      (is (<= (quot (count pattern) 2) (count varied) (* (count pattern) 2))))))

(deftest emi-style-variation-at-similarity-1-never-mutates
  (is (= [1 0 1 0 1 0 1 0] (tr/emi-style-variation [1 0 1 0 1 0 1 0] 1.0))))

(deftest oblique-strategies-cover-every-deterministic-transform
  (let [pattern [1 0 1 1 0]]
    (is (= [0 1 1 0 1] (tr/oblique-strategies-transform pattern "reverse")))
    (is (= [0 1 0 0 1] (tr/oblique-strategies-transform pattern "invert")))
    (is (= [1 1 1 0 0 0 1 1 1 1 1 1 0 0 0] (tr/oblique-strategies-transform pattern "slowest")))
    (is (= [1 1 0] (tr/oblique-strategies-transform pattern "fastest")))
    (is (= [1 0 1 0 0] (tr/oblique-strategies-transform pattern "disconnect")))
    (is (= [1 0 1 0 0] (tr/oblique-strategies-transform pattern "only_essentials")))
    (is (= [0 0 0 0 0] (tr/oblique-strategies-transform pattern "silence")))
    (is (= [1 0 1 1 0 1 0 1 1 0] (tr/oblique-strategies-transform pattern "double")))
    (is (= [1 0 1 1 0 0 1 1 0 1] (tr/oblique-strategies-transform pattern "mirror")))))

(deftest oblique-strategies-unknown-name-is-a-no-op
  (is (= [1 0 1] (tr/oblique-strategies-transform [1 0 1] "not-a-real-strategy"))))
