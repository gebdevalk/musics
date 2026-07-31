(ns ornaments-test
  "Tests for ornament expansion. Run: lein test ornaments-test"
  (:require [clojure.test :refer [deftest is]]
            [common.music-elements :as el]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.ornaments :as o]))

(defn test-leaf [pitch dur]
  (d/leaf "test" (c/context) dur [pitch]))

(deftest plain-test
  (let [leaf (test-leaf 60 1/4)
        result (o/plain leaf nil)]
    (is (= 1 (count result)))
    (is (= [60] (:pitches (first result))))
    (is (= 1/4 (:duration (first result))))))

(deftest trill-test
  (let [leaf (test-leaf 60 1/4)
        ks (el/key :C :major)
        result (o/trill leaf ks)]
    (is (= 6 (count result)))
    (is (= [62] (:pitches (first result))) "upper neighbor")
    (is (= [60] (:pitches (second result))) "back to main")))

(deftest prall-test
  (let [leaf (test-leaf 60 1/4)
        ks (el/key :C :major)
        result (o/prall leaf ks)]
    (is (= 3 (count result)))
    (is (= [62] (:pitches (first result))) "upper neighbor first")))

(deftest turn-test
  (let [leaf (test-leaf 60 1/4)
        ks (el/key :C :major)
        result (o/turn leaf ks)]
    (is (= 4 (count result)))
    (is (= [62] (:pitches (first result))) "upper")
    (is (= [60] (:pitches (second result))) "main")
    (is (= [59] (:pitches (nth result 2))) "lower")
    (is (= [60] (:pitches (nth result 3))) "main")))

(deftest mordent-test
  (let [leaf (test-leaf 60 1/4)
        ks (el/key :C :major)
        result (o/mordent leaf ks)]
    (is (= 3 (count result)))
    (is (= [60] (:pitches (first result))) "main")
    (is (= [59] (:pitches (second result))) "lower neighbor")))

(deftest fermata-test
  (let [leaf (test-leaf 60 1/4)]
    (is (= 3/8 (:duration (first (o/shortfermata leaf nil)))))
    (is (= 1/2 (:duration (first (o/fermata leaf nil)))))
    (is (= 3/4 (:duration (first (o/longfermata leaf nil)))))
    (is (= 1 (:duration (first (o/verylongfermata leaf nil)))))))
