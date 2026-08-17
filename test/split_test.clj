(ns ^:domain split-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.split :as split]
            [core.domain.flat-domain :as d]))

(def melody [[60 1/4] [62 1/4] [64 1/2]])

(deftest split-with-zero-splitoffs-returns-just-the-original
  (is (= [melody] (split/split melody 0))))

(deftest split-off-goes-an-octave-up-halves-durations-and-repeats-twice
  (let [voices (split/split melody 1)
        v1     (nth voices 1)]
    (is (= 2 (count voices)))
    (is (= melody (nth voices 0)) "original stays as-is")
    (is (= [[72 1/8] [74 1/8] [76 1/4]
            [72 1/8] [74 1/8] [76 1/4]]
           v1))))

(deftest each-voice-is-built-from-the-previous-highest-voice
  (let [voices (split/split melody 2)
        v1     (nth voices 1)
        v2     (nth voices 2)]
    ;; v2 is v1 (not the original) split again: another octave up,
    ;; durations halved again, repeated twice.
    (is (= (into (mapv (fn [[p d]] [(+ p 12) (/ d 2)]) v1)
                 (mapv (fn [[p d]] [(+ p 12) (/ d 2)]) v1))
           v2))))

(deftest every-voice-spans-the-same-total-duration-as-the-original
  (let [voices    (split/split melody 3)
        total-dur (fn [voice] (reduce + (map second voice)))
        original-dur (total-dur melody)]
    (is (every? #(= original-dur (total-dur %)) voices))))

;; ---- Leaf-based (split-leafs/split-leaf-voice), @{ } ElementAlgo's entry point ----

(def leaf-melody
  [(d/leaf :n1 nil 1/4 [60])
   (d/leaf :n2 nil 1/4 [62 65]) ;; a chord -- every pitch should move together
   (d/leaf :n3 nil 1/2 [64] :staccato :mf [] true)])

(deftest split-leafs-transposes-every-pitch-in-a-chord-together
  (let [v1 (nth (split/split-leafs leaf-melody 1) 1)]
    (is (= [[72] [74 77] [76]] (mapv :pitches (take 3 v1))))
    (is (= [1/8 1/8 1/4] (mapv :duration (take 3 v1))))
    (is (= v1 (into (vec (take 3 v1)) (take 3 v1))) "repeated twice")))

(deftest split-leafs-preserves-every-other-field-unchanged
  (let [v1   (nth (split/split-leafs leaf-melody 1) 1)
        note (nth v1 2)]
    (is (= :staccato (:articulation note)))
    (is (= :mf (:dynamic note)))
    (is (true? (:tied note)))))

(deftest split-leaf-voice-defaults-to-the-final-highest-voice
  (is (= (last (split/split-leafs leaf-melody 2))
         (split/split-leaf-voice 2 leaf-melody))))

(deftest split-leaf-voice-picks-a-specific-layer-by-index
  (is (= leaf-melody (split/split-leaf-voice 2 0 leaf-melody)))
  (is (= (nth (split/split-leafs leaf-melody 2) 1) (split/split-leaf-voice 2 1 leaf-melody))))
