(ns ^:repl show-algos-test
  "show-algos (musics.core) -- the algo registry, printed by category."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [musics.core :as m]))

(deftest show-algos-prints-every-category-from-the-registry
  (let [printed (with-out-str (m/show-algos))]
    (is (re-find #"--- rhythmic ---" printed))
    (is (re-find #"\n  euclid " printed) "algos by their short name")
    (is (not (re-find #"euclidean-rhythm" printed)) "algos only, no full fn names")))

(deftest show-algos-one-category-prints-only-that-category
  (let [printed (with-out-str (m/show-algos "metric"))]
    (is (re-find #"--- metric ---" printed))
    (is (not (re-find #"--- rhythmic ---" printed)))))

(deftest show-algos-one-algo-prints-doc-types-and-params
  (let [printed (with-out-str (m/show-algos :drums))]
    (is (str/starts-with? printed "A drum-kit groove"))
    (is (re-find #"- -> part" printed))
    (is (re-find #":bars +int +8 +\[1 \.\. 64\]" printed))))

(deftest show-algos-degrades-clearly-for-an-unknown-category-or-name
  (is (re-find #"Unknown category" (with-out-str (m/show-algos "not-a-real-category"))))
  (is (re-find #"Unknown algo" (with-out-str (m/show-algos :not-a-real-algo)))))
