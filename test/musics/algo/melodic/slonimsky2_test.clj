(ns ^:algo musics.algo.melodic.slonimsky2-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.algo.melodic.slonimsky2 :as sl]))

(deftest the-primary-tones-are-the-divisor-stacked-until-a-c-comes-back
  (is (= [60 66 72] (sl/primary 6)) "C4 F#4 C5")
  (is (= [60 62 64 66 68 70 72] (sl/primary 2)))
  (is (= [60 63 66 69 72] (sl/primary 3)))
  (is (= [60 64 68 72] (sl/primary 4)))
  (testing "more than an octave: lcm(d, 12)"
    (is (= [60 68 76 84] (sl/primary 8)) "two octaves")
    (is (= [60 69 78 87 96] (sl/primary 9)) "three octaves")
    (is (= 120 (peek (sl/primary 5))) "five octaves, twelve steps")
    (is (= 13 (count (sl/primary 5))))
    (is (= [60 70 80 90 100 110 120] (sl/primary 10))))
  (testing "every divisor lands on a C"
    (doseq [d sl/divisors] (is (zero? (mod (- (peek (sl/primary d)) 60) 12)) (str d)))))

(deftest with-the-three_booleans_off-it-is-the-primary-tones
  (doseq [d sl/divisors] (is (= (sl/primary d) (sl/slonimsky2 d false false false)))))

(deftest each-polation-follows-the-lower-tone-of-a-pair
  (is (= [60 61 66 67 72] (sl/slonimsky2 6 false true false)) "inter: [C4 C#4 F#4 G4 C5]")
  (is (= [60 67 66 73 72] (sl/slonimsky2 6 false false true)) "ultra: [C4 G4 F#4 D5& C5]")
  (is (= [60 59 66 65 72] (sl/slonimsky2 6 true false false)) "infra: [C4 B3 F#4 F4 C5]"))

(deftest the-polations-combine-in-the-order-infra-inter-ultra
  (is (= [60 59 61 66 65 67 72] (sl/slonimsky2 6 true true false)))
  (is (= [60 59 67 66 65 73 72] (sl/slonimsky2 6 true false true)))
  (is (= [60 61 67 66 67 73 72] (sl/slonimsky2 6 false true true)))
  (is (= [60 59 61 67 66 65 67 73 72] (sl/slonimsky2 6 true true true))))

(deftest a-divisor-that-does-not-come-back-is-refused
  (is (thrown? AssertionError (sl/slonimsky2 7 false false false)) "7 would reach seven octaves up, past MIDI")
  (is (thrown? AssertionError (sl/slonimsky2 11 false false false)))
  (is (thrown? AssertionError (sl/slonimsky2 12 false false false))))
