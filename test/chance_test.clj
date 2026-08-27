(ns ^:domain chance-test
  "Tests for discrete/collection probability helpers.
   Run: lein test chance-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random :as c]
            [algo.random.core :as core]
            [algo.random.core :as seed]))

(deftest choose-picks-a-member
  (is (contains? (set [1 2 3 4 5 6]) (core/choose [1 2 3 4 5 6]))))

(deftest choose-n-picks-without-replacement
  (let [picked (c/choose-n 3 [1 2 3 4 5 6])]
    (is (= 3 (count picked)))
    (is (= 3 (count (distinct picked))))
    (is (every? (set [1 2 3 4 5 6]) picked))))

(deftest deep-shuffle-depth-zero-is-a-no-op
  (is (= [[1 2] [3 4 5] [6]] (c/deep-shuffle [[1 2] [3 4 5] [6]] 0))))

(deftest deep-shuffle-depth-one-only-reorders-the-top-level
  ;; each inner seq's own order must survive exactly -- only which one
  ;; comes first/second/third can change
  (let [coll     [[1 2] [3 4 5] [6]]
        shuffled (c/deep-shuffle coll 1)]
    (is (= (set coll) (set shuffled))
        "same three inner seqs present, each one's own order untouched")))

(deftest deep-shuffle-preserves-every-leaf-regardless-of-depth
  (let [coll [[1 2] [3 4 5] [6]]]
    (doseq [depth [0 1 2 nil]]
      (is (= (sort (flatten coll)) (sort (flatten (c/deep-shuffle coll depth))))
          (str "depth " depth " must preserve every leaf")))))

(deftest deep-shuffle-leaves-non-sequential-elements-alone
  (let [mixed (c/deep-shuffle [:x [1 2 3] :y [4 5 6]] 1)]
    (is (= #{:x :y [1 2 3] [4 5 6]} (set mixed))
        "at depth 1, leaves and nested seqs alike keep their own contents intact")))

(deftest deep-shuffle-full-depth-can-reorder-inner-seqs-too
  ;; unlike depth 1, full depth (no depth arg) is free to reorder INSIDE
  ;; each nested seq as well -- across enough trials, at least one must
  ;; differ from the original order
  (let [coll [[1 2 3 4 5] [6 7 8 9 10] [11 12 13 14 15]]]
    (is (some true? (repeatedly 20 #(not= coll (c/deep-shuffle coll)))))))

(deftest deep-shuffle-is-seedable
  (let [coll [[1 2 3] [4 5 6] [7 8 9]]]
    (is (= (seed/with-seed 42 (c/deep-shuffle coll))
           (seed/with-seed 42 (c/deep-shuffle coll))))))

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

(deftest weighted-choose-respects-a-100-percent-bucket
  (is (= :always (core/weighted-choose [:always :never] [1.0 0.0]))))

(deftest weighted-choose-accepts-a-map
  (is (= :always (core/weighted-choose {:always 1.0 :never 0.0}))))

(deftest weighted-choose-does-not-require-weights-to-sum-to-one
  ;; unnormalized weights (e.g. raw Markov transition counts) must work
  ;; directly -- this is why weighted-choose absorbed weighted-item
  ;; instead of the other way around
  (is (= :often (core/weighted-choose [:often :never] [5 0]))))

(deftest only-picks-by-index
  (is (= [:b :d] (c/only [:a :b :c :d] [1 3]))))

(deftest sputter-never-exceeds-max
  (is (<= (count (c/sputter [1 2 3 4] 1.0 10)) 10)))

(deftest sputter-at-zero-probability-is-a-no-op
  (is (= [1 2 3 4] (c/sputter [1 2 3 4] 0.0 10))))
