(ns ^:engine minor-filters-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.reshape :as reshape]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- leaf [id pitches] (d/leaf id (c/context) 1/4 pitches))

;; ============================================================
;; pitch-class-filter
;; ============================================================

(deftest pitch-class-filter-keeps-only-allowed-pitch-classes
  ;; C major pcs: 0 2 4 5 7 9 11. 61 (C#) is pc 1 -- not allowed.
  (let [parts [(leaf :a [60]) (leaf :b [61]) (leaf :c [62])]
        out   (reshape/pitch-class-filter parts [0 2 4 5 7 9 11])]
    (is (= [60] (:pitches (nth out 0))))
    (is (d/rest? (nth out 1)) "61 is pc 1, not in C major")
    (is (= [62] (:pitches (nth out 2))))))

(deftest pitch-class-filter-is-octave-independent
  (let [parts [(leaf :a [60]) (leaf :b [72]) (leaf :c [84])]  ;; all pc 0
        out   (reshape/pitch-class-filter parts [0])]
    (is (every? #(not (d/rest? %)) out) "same pitch class across 3 octaves, all kept")))

(deftest pitch-class-filter-gates-a-chord-pitch-by-pitch
  (let [chord (leaf :c [60 61 62])  ;; pcs 0 1 2
        out   (first (reshape/pitch-class-filter [chord] [0 2]))]
    (is (= [60 62] (:pitches out)) "61 (pc 1) dropped from the chord, not the whole leaf")))

;; ============================================================
;; interval-filter
;; ============================================================

(deftest interval-filter-keeps-stepwise-motion-only
  ;; melody [60 62 64 67 69 70] -- intervals from raw previous: 2,2,3,2,1
  ;; allowed=[2] means only whole-step (2 semitone) motion survives
  (let [parts [(leaf :a [60]) (leaf :b [62]) (leaf :c [64])
               (leaf :d [67]) (leaf :e [69]) (leaf :f [70])]
        out   (reshape/interval-filter parts [2])]
    (is (not (d/rest? (nth out 0))) "first element always kept")
    (is (not (d/rest? (nth out 1))) "62-60=2, allowed")
    (is (not (d/rest? (nth out 2))) "64-62=2, allowed")
    (is (d/rest? (nth out 3)) "67-64=3, not allowed")
    (is (not (d/rest? (nth out 4))) "69-67=2, allowed (against RAW previous 67, not rested)")
    (is (d/rest? (nth out 5))) "70-69=1, not allowed"))

(deftest interval-filter-checks-against-the-raw-previous-not-the-last-kept
  ;; matches the source email's own zip-over-raw-sequence semantics --
  ;; a rejected element still counts as "the previous one" for the NEXT
  ;; element's own interval check, confirmed by the [67 69] case above
  ;; where 67 was itself rejected but 69's own interval is still checked
  ;; against IT (69-67=2), not against 64 (69-64=5, which would also
  ;; happen to be disallowed here, but that's not what's being tested)
  (let [parts [(leaf :a [60]) (leaf :b [65]) (leaf :c [67])]
        ;; 65-60=5 (rejected), 67-65=2 (allowed, checked against raw 65)
        out (reshape/interval-filter parts [2])]
    (is (not (d/rest? (nth out 0))))
    (is (d/rest? (nth out 1)))
    (is (not (d/rest? (nth out 2))) "67's own interval was checked against raw 65, not against kept 60")))

(deftest interval-filter-non-leaf-parts-dont-reset-the-previous-pitch
  (let [bar (d/bar 1)
        parts [(leaf :a [60]) bar (leaf :b [62])]
        out   (reshape/interval-filter parts [2])]
    (is (= bar (nth out 1)) "the Bar passes through untouched")
    (is (not (d/rest? (nth out 2))) "62's interval is still checked against 60 (60->62=2), not reset by the Bar")))

;; ============================================================
;; probability-filter
;; ============================================================

(deftest probability-filter-with-p=1-keeps-everything
  (let [parts (mapv #(leaf (keyword (str %)) [60]) (range 20))]
    (is (every? #(not (d/rest? %)) (reshape/probability-filter parts 1.0)))))

(deftest probability-filter-with-p=0-rests-everything
  (let [parts (mapv #(leaf (keyword (str %)) [60]) (range 20))]
    (is (every? d/rest? (reshape/probability-filter parts 0.0)))))

(deftest probability-filter-with-p=0.5-produces-a-real-mix-over-enough-trials
  (let [parts (mapv #(leaf (keyword (str %)) [60]) (range 200))
        out   (reshape/probability-filter parts 0.5)
        kept  (count (remove d/rest? out))]
    (is (< 50 kept 150) (str "expected roughly half of 200 kept, got " kept))))

(deftest probability-filter-non-leaf-parts-always-pass-through
  (let [bar (d/bar 1)]
    (is (= [bar] (reshape/probability-filter [bar] 0.0)))))

;; ============================================================
;; -algo factory wrappers
;; ============================================================

(deftest pitch-class-filter-algo-behaves-identically-to-the-pure-fn
  (let [algo-fn (reshape/pitch-class-filter-algo [0 4 7])
        parts   [(leaf :a [60]) (leaf :b [61])]]
    (is (= (reshape/pitch-class-filter parts [0 4 7]) (algo-fn parts [] nil)))))

(deftest interval-filter-algo-behaves-identically-to-the-pure-fn
  (let [algo-fn (reshape/interval-filter-algo [2])
        parts   [(leaf :a [60]) (leaf :b [62]) (leaf :c [67])]]
    (is (= (reshape/interval-filter parts [2]) (algo-fn parts [] nil)))))

(deftest probability-filter-algo-behaves-identically-in-shape
  (let [algo-fn (reshape/probability-filter-algo 1.0)
        parts   [(leaf :a [60]) (leaf :b [62])]]
    (is (every? #(not (d/rest? %)) (algo-fn parts [] nil)))))
