(ns ^:algo musics.algo.melodic.slonimsky-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.algo.melodic.slonimsky :as sl]))

(deftest the-single-forms-put-the-insertion-after-every-tone-but-the-last
  (doseq [f [sl/infra sl/inter sl/ultra]]
    (is (= [1 9 2 9 3] (f [1 2 3] [9])) "never before a tone, nothing after the last")))

(deftest fewer-than-2-tones-pass-through-unchanged
  (doseq [f [sl/infra sl/inter sl/ultra]]
    (is (= [1] (f [1] [9])))
    (is (= [] (f [] [9])))))

(deftest the-general-form-puts-infra-then-inter-then-ultra-after-a-tone
  (is (= [1 8 9 7 2 8 9 7 3] (sl/polation2 [1 2 3] [8] [9] [7]))))

(deftest a-layer-given-nil-or-empty-is-off
  (is (= [1 9 2 9 3] (sl/polation2 [1 2 3] nil [9] nil)))
  (is (= [1 9 2 9 3] (sl/polation2 [1 2 3] [9] [] [])))
  (is (= [1 2 3] (sl/polation2 [1 2 3])) "and with no insertions it is the identity"))

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

(deftest with-the-three-switches-off-it-is-the-primary-tones
  (doseq [d sl/divisors] (is (= (sl/primary d) (sl/polation d 0 0 0)))))

(deftest each-polation-follows-the-lower-tone-of-a-pair
  (is (= [60 61 66 67 72] (sl/polation 6 0 1 0)) "inter: [C4 C#4 F#4 G4 C5]")
  (is (= [60 67 66 73 72] (sl/polation 6 0 0 1)) "ultra: [C4 G4 F#4 D5& C5]")
  (is (= [60 59 66 65 72] (sl/polation 6 1 0 0)) "infra: [C4 B3 F#4 F4 C5]"))

(deftest the-polations-combine-in-the-order-infra-inter-ultra
  (is (= [60 59 61 66 65 67 72] (sl/polation 6 1 1 0)))
  (is (= [60 59 67 66 65 73 72] (sl/polation 6 1 0 1)))
  (is (= [60 61 67 66 67 73 72] (sl/polation 6 0 1 1)))
  (is (= [60 59 61 67 66 65 67 73 72] (sl/polation 6 1 1 1))))

(deftest the-switches-are-0-or-1
  (is (thrown? AssertionError (sl/polation 6 2 0 0)))
  (is (thrown? AssertionError (sl/polation 6 true false false))))

(deftest a-mask-is-the-three-switches-added-infra-1-inter-2-ultra-4
  (doseq [mask (range 8)]
    (is (= (sl/polation 6 (bit-and mask 1) (quot (bit-and mask 2) 2) (quot (bit-and mask 4) 4))
           (sl/polation 6 mask))
        (str mask)))
  (is (= [60 59 66 65 72] (sl/polation 6 1)))
  (is (= [60 61 66 67 72] (sl/polation 6 2)))
  (is (= [60 67 66 73 72] (sl/polation 6 4)))
  (is (= (sl/polation 6 1 0 1) (sl/polation 6 5)))
  (is (= (sl/polation 6 1 1 1) (sl/polation 6 7)))
  (is (thrown? AssertionError (sl/polation 6 8)))
  (is (thrown? AssertionError (sl/polation 6 -1))))

(deftest mu-takes-two-arguments-or-four-by-what-is-on-the-stack
  (let [arity (get-in (meta #'sl/polation) [:mu :arity])]
    (is (= 4 (arity [6 1 0 1])) "a divisor and three 0/1 ints")
    (is (= 4 (arity [9 3 8 0 0 0])) "with other things below")
    (is (= 2 (arity [6 5])) "a divisor and a mask")
    (is (= 2 (arity [6 1])) "a mask of 0 or 1 is a mask")
    (is (= 2 (arity [9 3 6 5])) "the third from the top is not 0 or 1")
    (is (= 2 (arity [5 0 1 7])) "the top is not 0 or 1")))

(deftest a-divisor-that-does-not-come-back-is-refused
  (is (thrown? AssertionError (sl/polation 7 0 0 0)) "7 would reach seven octaves up, past MIDI")
  (is (thrown? AssertionError (sl/polation 11 0 0 0)))
  (is (thrown? AssertionError (sl/polation 12 0 0 0))))
