(ns ^:domain reshape-test
  (:require [clojure.test :refer [deftest is]]
            [algorithm.reshape :as reshape]
            [core.domain.flat-domain :as d]))

(deftest retrograde-reverses-order
  (let [n1 (d/leaf :n1 nil 1/4 [60])
        n2 (d/leaf :n2 nil 1/4 [62])
        n3 (d/leaf :n3 nil 1/4 [64])]
    (is (= [n3 n2 n1] (reshape/retrograde [n1 n2 n3])))))

(deftest arpeggiate-splits-a-chord-into-single-note-leaves-ascending
  (let [chord (d/leaf :c nil 1/2 [67 60 64])
        run   (reshape/arpeggiate chord)]
    (is (= [[60] [64] [67]] (mapv :pitches run)))
    (is (every? #(= 1/6 (:duration %)) run) "1/2 split evenly across 3 notes")))

(deftest arpeggiate-accepts-a-custom-order-fn
  (let [chord (d/leaf :c nil 1/2 [60 64 67])
        run   (reshape/arpeggiate chord (comp reverse sort))]
    (is (= [[67] [64] [60]] (mapv :pitches run)))))

(deftest arpeggiate-is-a-no-op-on-fewer-than-two-pitches
  (let [n (d/leaf :n nil 1/4 [60])]
    (is (= [n] (reshape/arpeggiate n)))))

(deftest arpeggiate-is-a-no-op-on-a-part-with-no-pitches
  (let [r (d/rest* :r nil 1/4)]
    (is (= [r] (reshape/arpeggiate r)))))

(deftest hocket-interleaves-voices
  (let [a1 (d/leaf :a1 nil 1/4 [60]) a2 (d/leaf :a2 nil 1/4 [62])
        b1 (d/leaf :b1 nil 1/4 [67]) b2 (d/leaf :b2 nil 1/4 [69])]
    (is (= [a1 b1 a2 b2] (reshape/hocket [a1 a2] [b1 b2])))))
