(ns articulation-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.music-parser :as p]
            [core.domain.music-domain :as d]))

(defn leaves [text] (filter d/leaf? (:tokens (p/parse text))))
(defn articulation-of [text] (:articulation (first (leaves text))))
(defn dynamic-of [text] (:dynamic (first (leaves text))))

(deftest shorthand-articulations
  (testing "staccatissimo -!"
    (let [a (articulation-of "c4-!")]
      (is (= 0.25 (:duration a)))
      (is (= 0 (:dynamic a)))))
  (testing "staccato -."
    (let [a (articulation-of "c4-.")]
      (is (= 0.4 (:duration a)))
      (is (= 0 (:dynamic a)))))
  (testing "stopped -+"
    (let [a (articulation-of "c4-+")]
      (is (= 0.3 (:duration a)))
      (is (= 0 (:dynamic a)))))
  (testing "marcato -^"
    (let [a (articulation-of "c4-^")]
      (is (= 0.55 (:duration a)))
      (is (= 10 (:dynamic a)))))
  (testing "portato -_"
    (let [a (articulation-of "c4-_")]
      (is (= 0.8 (:duration a)))
      (is (= 0 (:dynamic a))))))

(deftest leaf-dynamic-field
  (testing "marcato sets leaf dynamic to 10"
    (is (= 10 (dynamic-of "c4-^"))))
  (testing "staccato sets leaf dynamic to 0"
    (is (= 0 (dynamic-of "c4-."))))
  (testing "no articulation means dynamic nil"
    (is (nil? (dynamic-of "c4")))))

(deftest named-articulations
  (let [resolve #'p/resolve-articulation]
    (testing "staccato by name"
      (is (= 0.4 (:duration (resolve "staccato")))))
    (testing "marcato by name"
      (is (= 0.55 (:duration (resolve "marcato"))))
      (is (= 10 (:dynamic (resolve "marcato")))))
    (testing "legato by name"
      (is (= 1.0 (:duration (resolve "legato")))))
    (testing "sfz by name"
      (is (nil? (:duration (resolve "sfz"))))
      (is (= 10 (:dynamic (resolve "sfz")))))
    (testing "fermata by name"
      (is (nil? (:duration (resolve "fermata"))))
      (is (= 0 (:dynamic (resolve "fermata")))))
    (testing "unknown returns as-is"
      (is (= "foo" (resolve "foo")))
      (is (nil? (resolve nil))))))
