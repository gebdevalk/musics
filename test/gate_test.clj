(ns ^:engine gate-test
  "algo.common.gate -- the general filter engine (select-fn + on-reject)
   plus its own registered criteria, replacing what used to be six
   bespoke filter functions in algo.common.reshape. See gate's own ns
   docstring for the full rationale and why this refactor was
   deliberately scoped to just the filters."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [algo.common.gate :as gate]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [core.wall :as wall]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]))

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
;; Criterion factories -- each a plain, unregistered function
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

;; ============================================================
;; core.wall criteria registry + resolve-criterion
;; ============================================================

(deftest criteria-registry-round-trips
  (with-fresh-registries
    (is (nil? (wall/criterion-fn ::my-crit)))
    (wall/register-criterion! ::my-crit gate/lo-criterion "lo-pass")
    (is (= gate/lo-criterion (wall/criterion-fn ::my-crit)))
    (is (= "lo-pass" (wall/criteria ::my-crit)))
    (is (contains? (wall/criteria) ::my-crit))
    (wall/unregister-criterion! ::my-crit)
    (is (nil? (wall/criterion-fn ::my-crit)))))

(deftest resolve-criterion-applies-registered-factory-args
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (let [select-fn (wall/resolve-criterion [::lo 65])]
      (is (true?  (select-fn (leaf :a 60) nil)))
      (is (false? (select-fn (leaf :a 70) nil))))))

(deftest resolve-criterion-unregistered-name-rejects-everything
  (with-fresh-registries
    (let [select-fn (wall/resolve-criterion [::nonexistent 1])]
      (is (false? (select-fn (leaf :a 60) nil))))))

;; ============================================================
;; gate-algo -- the factory
;; ============================================================

(deftest gate-algo-behaves-identically-to-calling-gate-directly
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (gate/gate-algo ::loGate [::lo 65] :remove)
    (let [algo-fn (wall/algo ::loGate)
          parts   [(leaf :a 60) (leaf :b 67)]]
      (is (= (gate/gate (gate/lo-criterion 65) :remove parts) (algo-fn parts [] nil))))))

;; ============================================================
;; Live engine proof -- prepare (build!) and perform (play)
;; ============================================================

(deftest gate-prepared-as-a-built-algo-and-performed-live
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (wall/register-factory! ::gate gate/gate-algo)
    (wall/build! ::loFilter ::gate [::lo 64] :remove)
    (let [n1 (d/leaf :n1 (c/context) 1/16 [60])
          n2 (d/leaf :n2 (c/context) 1/16 [67])
          n3 (d/leaf :n3 (c/context) 1/16 [72])
          verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
          root  {:type :ROOT :id :ROOT
                 :context (c/context-root {"Tempo" 240 "volume" 80})
                 :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (repo/play-latest!)
      (let [eng  (engine/engine nil repo/play-tx :ROOT)
            done (promise)
            seen (atom nil)]
        (binding [engine/*engine* eng]
          (let [base (wall/algo ::loFilter)]
            (wall/build-algo! ::recording
              (fn [nodes ctx voice]
                (let [out (base nodes ctx voice)]
                  (when (= 3 (count nodes)) (reset! seen (mapv (comp first :pitches) out)))
                  out))))
          (conductor/register-action! :done (fn [_] (deliver done true)))
          (conductor/schedule! :verse :exit :done)
          (engine/play :verse :algo ::recording)
          (is (not= :timeout (deref done 2000 :timeout))
              "the voice ran to completion even though 2 of its 3 children got dropped")
          (is (= [60] @seen)
              "prepared via build!, performed via play -- confirmed live"))))))
