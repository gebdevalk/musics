(ns ^:domain musics.domain.ornaments-test
  "Tests for ornament expansion. Run: lein test musics.domain.ornaments-test"
  (:require [clojure.test :refer [deftest is]]
            [musics.common.music-elements :as el]
            [musics.domain.context :as c]
            [musics.domain :as d]
            [musics.domain.ornaments :as o]))

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

(deftest neighbors-outside-c-major
  ;; a Key's :pitches run past 11 (G major: 7 9 11 12 14 16 18)
  (let [G (el/key :G :major) F (el/key :F :major)]
    (is (= 67 (o/upper G 66)) "f# -> g")
    (is (= 64 (o/lower G 66)) "f# -> e")
    (is (= 69 (o/upper G 67)) "g -> a, same octave")
    (is (= 66 (o/lower G 67)) "g -> f#")
    (is (= 72 (o/upper G 71)) "b -> c across the octave")
    (is (= 70 (o/upper F 69)) "a -> bb in F")
    (is (= 62 (o/upper G 61)) "a chromatic pitch's nearest scale note above")))
