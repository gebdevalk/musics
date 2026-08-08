(ns ^:domain chance-test
  "Tests for discrete/collection probability helpers.
   Run: lein test chance-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.chance :as c]))

(deftest choose-picks-a-member
  (is (contains? (set [1 2 3 4 5 6]) (c/choose [1 2 3 4 5 6]))))

(deftest choose-n-picks-without-replacement
  (let [picked (c/choose-n 3 [1 2 3 4 5 6])]
    (is (= 3 (count picked)))
    (is (= 3 (count (distinct picked))))
    (is (every? (set [1 2 3 4 5 6]) picked))))

(deftest chosen-from-returns-elements-not-indices
  (let [coll   [10 20 30]
        picked (c/chosen-from coll)]
    (is (= 3 (count picked)))
    (is (every? (set coll) picked))))

(deftest weighted-coin-boundaries
  (is (not (c/weighted-coin 0.0)))
  (is (c/weighted-coin 1.0))
  (is (c/weighted-coin 2.0)) ;; clamps to 1.0, not the Julia source's 0.1
  )

(deftest ranged-rand-stays-in-range
  (dotimes [_ 200]
    (is (<= 1.0 (c/ranged-rand 1.0 2.0) 2.0))))

(deftest weighted-choose-respects-a-100-percent-bucket
  (is (= :always (c/weighted-choose [:always :never] [1.0 0.0]))))

(deftest weighted-choose-accepts-a-map
  (is (= :always (c/weighted-choose {:always 1.0 :never 0.0}))))

(deftest only-picks-by-index
  (is (= [:b :d] (c/only [:a :b :c :d] [1 3]))))

(deftest sputter-never-exceeds-max
  (is (<= (count (c/sputter [1 2 3 4] 1.0 10)) 10)))

(deftest sputter-at-zero-probability-is-a-no-op
  (is (= [1 2 3 4] (c/sputter [1 2 3 4] 0.0 10))))
