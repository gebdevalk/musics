(ns ^:repl musics.show-algos-test
  "show-algos (musics.core) -- the algo registry, printed by shelf."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [musics.core :as m]))

(deftest show-algos-prints-every-shelf-from-the-registry
  (let [printed (with-out-str (m/show-algos))]
    (is (< (str/index-of printed "--- pitch (half product) ---") (str/index-of printed "--- workshop (raw material) ---")) "half products first")
    (is (re-find #"\n  pitch/lorenz " printed) "specialised algos by their half product")
    (is (re-find #"\n  euclid " printed) "algos by their short name")
    (is (not (re-find #"euclidean-rhythm" printed)) "algos only, no full fn names")))

(deftest show-algos-one-shelf-prints-only-that-shelf
  (let [printed (with-out-str (m/show-algos "volume"))]
    (is (re-find #"--- volume \(half product\) ---" printed))
    (is (re-find #"volume/walk" printed))
    (is (not (re-find #"--- pitch" printed)))))

(deftest show-algos-one-algo-prints-doc-types-and-params
  (let [printed (with-out-str (m/show-algos :drums))]
    (is (str/starts-with? printed "A drum-kit groove"))
    (is (re-find #"- -> part" printed))
    (is (re-find #":bars +int +8 +\[1 \.\. 64\]" printed))))

(deftest show-algos-degrades-clearly-for-an-unknown-shelf-or-name
  (is (re-find #"Unknown shelf" (with-out-str (m/show-algos "not-a-real-shelf"))))
  (is (re-find #"Unknown algo" (with-out-str (m/show-algos :not-a-real-algo)))))

(deftest show-algos-says-how-a-bridge-works
  (is (re-find #"number -> duration +\(value by value" (with-out-str (m/show-algos :numbers->durations))))
  (is (re-find #"\(reads the whole stream" (with-out-str (m/show-algos :weights->volumes)))))
