(ns musics.version-test
  (:require [clojure.test :refer [deftest is]]
            [musics.version :refer [version]]))

(deftest the-version-counts-merges-into-main-and-commits-on-develop
  (is (re-matches #"0\.3\.\d+\.\d+" @version) "in the repo git gives both counts"))
