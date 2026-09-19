(ns ^:algo zfilter-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.zfilter :as zf]))

;; ============================================================
;; z-filter / smooth / momentum / memory -- each value hand-derived
;; independently before writing this test, not just eyeballed against
;; the fn's own output (see the fn's own docstring for the recurrence).
;; ============================================================

(deftest smooth-matches-hand-derived-values
  ;; y[n] = 0.3*x[n] + 0.7*y[n-1]:
  ;;   y0=0.3*1=0.3
  ;;   y1=0.3*2+0.7*0.3=0.81
  ;;   y2=0.3*3+0.7*0.81=1.467
  ;;   y3=0.3*10+0.7*1.467=4.0269
  ;;   y4=0.3*20+0.7*4.0269=8.81883
  (let [out (zf/smooth 0.7 [1 2 3 10 20])]
    (is (= 5 (count out)))
    (doseq [[expected actual] (map vector [0.3 0.81 1.467 4.0269 8.81883] out)]
      (is (< (Math/abs (- expected actual)) 1e-9)))))

(deftest momentum-matches-hand-derived-values
  ;; y[n] = 1.5*x[n] - 0.5*x[n-1] + 0.5*y[n-1]:
  ;;   y0=1.5*1=1.5
  ;;   y1=1.5*2-0.5*1+0.5*1.5=3.25
  ;;   y2=1.5*3-0.5*2+0.5*3.25=5.125
  (let [out (zf/momentum 0.5 [1 2 3 10 20])]
    (doseq [[expected actual] (map vector [1.5 3.25 5.125] out)]
      (is (< (Math/abs (- expected actual)) 1e-9)))))

(deftest z-filter-rejects-a-non-1-leading-feedback-coefficient
  (is (thrown? clojure.lang.ExceptionInfo (zf/z-filter [1] [2 -0.5] [1 2 3]))))

(deftest smooth-with-alpha-0-is-the-identity
  (is (= [1 2 3] (zf/smooth 0 [1 2 3]))))

;; ============================================================
;; smooth-intervals / interval-gain -- contour transforms
;; ============================================================

(deftest smooth-intervals-matches-hand-derived-values
  ;; intervals of [60 62 65 70] are [2 3 5]; smooth(0.5, [2 3 5]) =
  ;; [1.0 2.0 3.5]; reconstructed: [60, 61.0, 63.0, 66.5]
  (let [out (zf/smooth-intervals 0.5 [60 62 65 70])]
    (doseq [[expected actual] (map vector [60 61.0 63.0 66.5] out)]
      (is (< (Math/abs (- expected actual)) 1e-9)))))

(deftest interval-gain-2x-exaggerates-the-contour
  ;; intervals [2 3 5] * 2 = [4 6 10]; reconstructed [60 64 70 80]
  (is (= [60 64.0 70.0 80.0] (zf/interval-gain 2.0 [60 62 65 70]))))

(deftest interval-gain-1x-is-the-identity
  (is (= [60 62.0 65.0 70.0] (zf/interval-gain 1.0 [60 62 65 70]))))

(deftest interval-gain-and-smooth-intervals-pass-through-short-sequences
  (is (= [] (zf/interval-gain 2.0 [])))
  (is (= [60] (zf/interval-gain 2.0 [60])))
  (is (= [] (zf/smooth-intervals 0.5 [])))
  (is (= [60] (zf/smooth-intervals 0.5 [60]))))

;; ============================================================
;; pc-smooth
;; ============================================================

(deftest pc-smooth-operates-on-pitch-classes-mod-12
  ;; [60 61 62 63 64] mod 12 = [0 1 2 3 4] -- same recurrence as
  ;; smooth(0.7) on that reduced sequence
  (let [out (zf/pc-smooth 0.7 [60 61 62 63 64])]
    (is (< (Math/abs (- 0.0 (nth out 0))) 1e-9))
    (is (< (Math/abs (- 0.3 (nth out 1))) 1e-9))))
