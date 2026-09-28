(ns ^:algo isorhythm-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.isorhythm :as iso]))

;; ---- color-talea (bare pitch/duration pairing) ----

(deftest color-talea-one-period-is-lcm-of-the-two-counts
  (let [events (iso/color-talea [60 62 64] [1/4 1/8])]
    (is (= 6 (count events)) "lcm(3,2) = 6 events for one full period")
    (is (= [[60 1/4] [62 1/8] [64 1/4] [60 1/8] [62 1/4] [64 1/8]] events))))

(deftest color-talea-n-periods-is-n-copies-back-to-back
  (let [one (iso/color-talea [60 62] [1/4 1/8 1/16])
        two (iso/color-talea [60 62] [1/4 1/8 1/16] 2)]
    (is (= 6 (count one)))
    (is (= (into one one) two))))

;; ---- zip-parts (N-way generalization of color-talea) ----

(deftest zip-parts-two-streams-matches-color-talea-as-maps
  (is (= [{:pitch 60 :duration 1/4} {:pitch 62 :duration 1/8}
          {:pitch 64 :duration 1/4} {:pitch 60 :duration 1/8}
          {:pitch 62 :duration 1/4} {:pitch 64 :duration 1/8}]
         (iso/zip-parts {:pitch [60 62 64] :duration [1/4 1/8]}))))

(deftest zip-parts-three-streams-period-is-lcm-of-all-three
  (is (= 12 (count (iso/zip-parts {:a [1 2 3] :b [1 2] :c [1 2 3 4]})))
      "lcm(3,2,4) = 12, not just lcm of the first two"))

(deftest zip-parts-single-stream-period-is-its-own-count
  (is (= 5 (count (iso/zip-parts {:a [1 2 3 4 5]})))))

(deftest zip-parts-n-periods-is-n-copies-back-to-back
  (let [one (iso/zip-parts {:a [1 2]} 1)
        two (iso/zip-parts {:a [1 2]} 2)]
    (is (= (into one one) two))))

(deftest zip-parts-every-event-carries-every-given-key
  (let [events (iso/zip-parts {:pitch [60] :duration [1/4] :dynamic [:mf :ff]})]
    (is (every? #(= #{:pitch :duration :dynamic} (set (keys %))) events))))

(deftest zip-parts-rejects-empty-streams
  (is (thrown? clojure.lang.ExceptionInfo (iso/zip-parts {}))))
