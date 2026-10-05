(ns ^:algo gate-test
  "musics.algo.common.gate -- the general filter engine (select-fn + on-reject)
   plus its criterion constructors, replacing what used to be six
   bespoke filter functions in musics.algo.common.reshape. See gate's own ns
   docstring for the full rationale and why this refactor was
   deliberately scoped to just the filters."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.common.gate :as gate]
            [musics.domain :as d]
            [musics.domain.context :as c]))

(defn- leaf [id p] (d/leaf id (c/context) 1/4 [p]))

;; ============================================================
;; gate -- the pure engine, on-reject strategies
;; ============================================================

(deftest gate-remove-drops-rejected-parts
  (let [out (gate/gate (gate/lo-criterion 65) :remove [(leaf :a 60) (leaf :b 67) (leaf :c 50)])]
    (is (= [60 50] (mapv (comp first :pitches) out)))))

(deftest gate-rest-replaces-rejected-parts-with-a-rest-of-the-same-duration
  (let [n (d/leaf :b (c/context) 3/8 [67])
        out (gate/gate (gate/lo-criterion 65) :rest [(leaf :a 60) n])]
    (is (d/rest? (second out)))
    (is (= :b (:id (second out))))
    (is (= 3/8 (:duration (second out))))))

(deftest gate-hold-ties-to-the-last-sounding-pitch-and-chains-through-repeats
  ;; 60 kept, 67/72 both rejected (hold onto 60, chained -- 72's own
  ;; hold ties to 60, NOT re-anchored to 67's own hold-replacement),
  ;; 50 kept fresh, not tied.
  (let [out (gate/gate (gate/lo-criterion 65) :hold
                        [(leaf :a 60) (leaf :b 67) (leaf :c 72) (leaf :d 50)])]
    (is (= [60 60 60 50] (mapv (comp first :pitches) out)))
    (is (= [false true true false] (mapv (comp boolean :tied) out)))
    (is (= 4 (count out)) ":hold never drops -- every input Leaf produces one output")))

(deftest gate-hold-falls-back-to-rest-when-nothing-has-sounded-yet
  (let [out (gate/gate (gate/lo-criterion 10) :hold [(leaf :a 60)])]
    (is (d/rest? (first out)))))

(deftest gate-accepts-a-custom-on-reject-fn
  (let [out (gate/gate (gate/lo-criterion 65) (fn [part _last] (assoc part :pitches [999]))
                        [(leaf :a 60) (leaf :b 67)])]
    (is (= [60 999] (mapv (comp first :pitches) out)))))

(deftest gate-rejects-an-unrecognized-on-reject-keyword
  (is (thrown? clojure.lang.ExceptionInfo (gate/gate (gate/lo-criterion 65) :bogus [(leaf :a 60)]))))

(deftest gate-non-leaf-parts-always-pass-through-and-dont-reset-raw-prev
  (let [bar (d/bar 1)
        a   (leaf :a 60)
        b   (leaf :b 62)
        out (gate/gate (gate/interval-criterion [2]) :remove [a bar b])]
    (is (= [a bar b] out)
        "60->62 (interval 2, allowed) still checked correctly across the Bar")))

;; ============================================================
;; Criterion constructors -- each a plain function
;; ============================================================

(deftest lo-criterion-keeps-at-or-below-cutoff
  (is (true?  ((gate/lo-criterion 67) (leaf :a 60) nil)))
  (is (true?  ((gate/lo-criterion 67) (leaf :a 67) nil)))
  (is (false? ((gate/lo-criterion 67) (leaf :a 68) nil))))

(deftest hi-criterion-keeps-at-or-above-cutoff
  (is (false? ((gate/hi-criterion 67) (leaf :a 60) nil)))
  (is (true?  ((gate/hi-criterion 67) (leaf :a 67) nil))))

(deftest window-criterion-keeps-inside-the-inclusive-range
  (is (false? ((gate/window-criterion 60 72) (leaf :a 59) nil)))
  (is (true?  ((gate/window-criterion 60 72) (leaf :a 60) nil)))
  (is (true?  ((gate/window-criterion 60 72) (leaf :a 72) nil)))
  (is (false? ((gate/window-criterion 60 72) (leaf :a 73) nil))))

(deftest pitch-class-criterion-is-octave-independent
  (let [c (gate/pitch-class-criterion [0 4 7])]
    (is (true?  (c (leaf :a 60) nil)))
    (is (true?  (c (leaf :a 72) nil)))
    (is (false? (c (leaf :a 61) nil)))))

(deftest interval-criterion-first-note-always-passes
  (is (true? ((gate/interval-criterion [2]) (leaf :a 60) nil))))

(deftest interval-criterion-checks-against-raw-prev
  (let [c (gate/interval-criterion [2])]
    (is (true?  (c (leaf :b 62) (leaf :a 60))))
    (is (false? (c (leaf :b 65) (leaf :a 60))))))

(deftest probability-criterion-p=1-always-passes-p=0-never-passes
  (is (true?  ((gate/probability-criterion 1.0) (leaf :a 60) nil)))
  (is (false? ((gate/probability-criterion 0.0) (leaf :a 60) nil))))
