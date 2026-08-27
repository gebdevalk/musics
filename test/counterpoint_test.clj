(ns ^:domain counterpoint-test
  (:require [clojure.test :refer [deftest is testing]]
            [algo.melodic.counterpoint :as cp]))

(def c-major [60 62 64 65 67 69 71 72])
(def motif {:steps [0 2 -1 2] :durations [0.5 0.5 0.5 0.5]})
(def beat-durations [0.5 0.5 1.0 0.5 0.5 1.0 0.5 0.5])

(defn- wide-scale
  "Several octaves of a major scale -- a large enough candidate pool
   that consonant options genuinely exist almost everywhere, unlike a
   single octave (see the mechanism-works-with-a-wide-scale test)."
  []
  (vec (mapcat (fn [oct] (map #(+ % (* 12 oct)) [0 2 4 5 7 9 11])) (range 2 7))))

(defn- two-voice-specs
  ([] (two-voice-specs {}))
  ([extra]
   [(merge {:motif motif :delay-beats 0.0 :strictness 0.8 :transpose 0
            :density-envelope (vec (repeat 8 1)) :tolerance 2} (get extra 0 {}))
    (merge {:motif motif :delay-beats 1.0 :strictness 0.8 :transpose 4
            :density-envelope (vec (repeat 8 1)) :tolerance 12} (get extra 1 {}))]))

(deftest randomize-beat-subdurations-matches-the-reference
  (let [f #'cp/randomize-beat-subdurations]
    (is (= [0.3657142857142857 0.2742857142857143 0.2057142857142857 0.15428571428571428]
           (f 0.25 4 {:long-first-bias 0.5})))
    (is (= [0.7653061224489797 0.45918367346938777 0.2755102040816327]
           (f 0.5 3 {:long-first-bias 0.8})))))

(deftest randomize-beat-subdurations-is-even-with-no-bias
  (let [f #'cp/randomize-beat-subdurations]
    (is (= [0.25 0.25 0.25 0.25] (f 0.25 4 {})))
    (is (= [0.25 0.25 0.25 0.25] (f 0.25 4 {:long-first-bias 0.0})))))

(deftest generate-produces-one-voice-per-spec-with-matching-sub-note-counts
  (let [result (cp/generate c-major [55 84] beat-durations (two-voice-specs))]
    (is (= 2 (count result)))
    (is (= (count (first result)) (count (second result))))))

(deftest generate-each-voices-own-durations-sum-to-the-total-beats
  (let [result (cp/generate c-major [55 84] beat-durations (two-voice-specs))
        total-beats (reduce + beat-durations)]
    (doseq [voice result]
      (is (< (Math/abs (- total-beats (reduce + (map second voice)))) 1e-9)))))

(deftest generate-keeps-every-pitch-within-range
  (let [result (cp/generate c-major [55 84] beat-durations (two-voice-specs))]
    (doseq [voice result [pitch _] voice]
      (is (<= 55 pitch 84)))))

(deftest generate-mechanism-works-with-a-wide-scale
  ;; with a genuinely adequate candidate pool, the consonance/no-
  ;; parallel-motion rules should actually hold throughout -- unlike a
  ;; single-octave scale, where the reference this ports (confirmed
  ;; live, not assumed) can and does fall back to a rule-violating
  ;; nearest pitch when no candidate satisfies every rule at once
  (let [result (cp/generate (wide-scale) [40 100] beat-durations
                             (two-voice-specs {0 {:tolerance 12} 1 {:tolerance 12}}))
        pitches (mapv #(mapv first %) result)
        [v0 v1] pitches]
    (testing "consonance against the first voice, every beat"
      (dotimes [i (count v0)]
        (is (#{3 4 8 9} (mod (Math/abs (- (nth v1 i) (nth v0 i))) 12)))))
    (testing "no parallel fifths or octaves between consecutive beats"
      (dotimes [i (dec (count v0))]
        (let [interval (mod (Math/abs (- (nth v1 i) (nth v0 i))) 12)
              next-interval (mod (Math/abs (- (nth v1 (inc i)) (nth v0 (inc i)))) 12)]
          (is (not (and (= interval next-interval) (#{0 7} interval)))))))))

(deftest generate-rejects-mismatched-density-envelope-length
  (is (thrown? Exception
               (cp/generate c-major [55 84] beat-durations
                            [{:shape (vec (repeat 8 0)) :density-envelope [1 1 1]}]))))

(deftest generate-rejects-a-voice-with-neither-shape-nor-motif
  (is (thrown? Exception
               (cp/generate c-major [55 84] beat-durations [{:density-envelope (vec (repeat 8 1))}]))))

(deftest generate-rejects-a-non-integer-motif-duration
  (is (thrown? Exception
               (cp/generate c-major [55 84] beat-durations
                            [{:motif {:steps [0 1] :durations [0.5 0.7]}
                              :density-envelope (vec (repeat 8 1))}]))))

(deftest generate-rejects-non-positive-density
  (is (thrown? Exception
               (cp/generate c-major [55 84] [1.0] [{:shape [0] :density-envelope [0]}]))))

(deftest generate-rejects-voices-with-different-total-sub-notes
  (is (thrown? Exception
               (cp/generate c-major [55 84] beat-durations
                            [{:shape (vec (repeat 8 0)) :density-envelope (vec (repeat 8 1))}
                             {:shape (vec (repeat 8 0)) :density-envelope (vec (repeat 8 2))}]))))

(deftest generate-accepts-a-plain-shape-envelope-with-no-motif
  (let [result (cp/generate c-major [55 84] [1.0 1.0 1.0 1.0]
                             [{:shape [0 1 -1 2] :density-envelope [1 1 1 1] :tolerance 2}])]
    (is (= 4 (count (first result))))))
