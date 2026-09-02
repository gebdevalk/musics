(ns ^:engine pitch-filter-test
  "algo.common.reshape/pitch-filter + lo-filter/hi-filter/window-filter,
   and their own -algo factory wrappers -- the audio low-pass/high-pass/
   band-pass analogy, gating pitch instead of frequency. Ordinary
   parameterized factories (unlike weighted-shuffle-algo, no distribution
   registry involved -- a literal cutoff/range is enough).
   A rejected part is DROPPED from the result, not rested -- see
   pitch-filter's own docstring."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [algo.common.reshape :as reshape]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]))

;; ============================================================
;; pitch-filter / lo-filter / hi-filter / window-filter -- pure fns
;; ============================================================

(defn- leaf [id pitches] (d/leaf id (c/context) 1/4 pitches))

(deftest lo-filter-keeps-pitches-at-or-below-cutoff
  (let [parts [(leaf :a [60]) (leaf :b [67]) (leaf :c [72])]
        out   (reshape/lo-filter parts 67)]
    (is (= [[60] [67]] (mapv :pitches out))
        "72 is above cutoff -- dropped entirely, result is shorter than parts")))

(deftest hi-filter-keeps-pitches-at-or-above-cutoff
  (let [parts [(leaf :a [60]) (leaf :b [67]) (leaf :c [72])]
        out   (reshape/hi-filter parts 67)]
    (is (= [[67] [72]] (mapv :pitches out))
        "60 is below cutoff -- dropped entirely")))

(deftest window-filter-keeps-pitches-inside-the-range-inclusive
  (let [parts [(leaf :a [59]) (leaf :b [60]) (leaf :c [72]) (leaf :d [73])]
        out   (reshape/window-filter parts 60 72)]
    (is (= [[60] [72]] (mapv :pitches out))
        "59 and 73 are outside the window -- both dropped")))

(deftest a-dropped-leaf-vanishes-from-the-result-entirely
  (let [n   (d/leaf :n1 (c/context) 3/8 [80])
        out (reshape/lo-filter [n] 60)]
    (is (= [] out) "the sequence is genuinely shorter, not padded with a rest")))

(deftest a-chord-is-filtered-pitch-by-pitch-not-kept-or-dropped-wholesale
  (let [chord (leaf :c [60 67 72 76])
        out   (first (reshape/lo-filter [chord] 70))]
    (is (some? out) "at least one pitch (60, 67) survives -- stays a Leaf")
    (is (= [60 67] (:pitches out)) "72 and 76 dropped from the chord, not the whole leaf"))
  (is (= 1 (count (reshape/lo-filter [(leaf :c [60 67 72 76])] 70)))))

(deftest a-chord-whose-every-pitch-fails-drops-the-whole-leaf
  (let [chord (leaf :c [80 84 88])
        out   (reshape/lo-filter [chord] 60)]
    (is (= [] out))))

(deftest rest-and-drum-and-non-leaf-nodes-pass-through-untouched
  (let [r (d/rest* :r1 (c/context) 1/4)
        dr (d/drum :d1 (c/context) 1/4 36)
        bar (d/bar 3)
        out (reshape/lo-filter [r dr bar] 60)]
    (is (= [r dr bar] out) "nothing here has a :pitches to gate -- all pass through as-is")))

;; ============================================================
;; The -algo factory wrappers -- resolve cleanly, no registry involved
;; ============================================================

(deftest lo-filter-algo-behaves-identically-to-the-pure-fn
  (let [algo-fn (reshape/lo-filter-algo 67)
        parts   [(leaf :a [60]) (leaf :b [72])]]
    (is (= (reshape/lo-filter parts 67) (algo-fn parts [] nil)))))

(deftest hi-filter-algo-behaves-identically-to-the-pure-fn
  (let [algo-fn (reshape/hi-filter-algo 67)
        parts   [(leaf :a [60]) (leaf :b [72])]]
    (is (= (reshape/hi-filter parts 67) (algo-fn parts [] nil)))))

(deftest window-filter-algo-behaves-identically-to-the-pure-fn
  (let [algo-fn (reshape/window-filter-algo 60 72)
        parts   [(leaf :a [59]) (leaf :b [66]) (leaf :c [80])]]
    (is (= (reshape/window-filter parts 60 72) (algo-fn parts [] nil)))))

;; ============================================================
;; Live engine proof -- a real voice, a real cutoff, confirmed via play
;; (NOT display -- display never applies :algo tags at all, a real gap
;; hit and confirmed live while writing this project's own usage docs
;; for weighted-shuffle-algo). Also the specific thing worth confirming
;; live for DROP semantics: a container whose wall-fn drops some (or
;; even all) of its own children still runs to completion -- play-leaves
;; guards on (seq xs), resolve-ornaments is a plain mapcat, both true
;; no-ops on empty input, and a :section :exit signal is tied to the
;; container's own structural boundary, not to how many of its children
;; actually survived filtering.
;; ============================================================

(deftest lo-filter-actually-drops-pitches-in-a-real-live-voice
  (with-fresh-registries
    (let [seen (atom [])
          base (reshape/lo-filter-algo 64)
          recording-algo (fn [nodes ctx-chain voice]
                            (let [out (base nodes ctx-chain voice)]
                              (when (= 3 (count nodes))
                                (swap! seen conj (mapv :pitches out)))
                              out))
          _    (wall/register-algo! ::recording-lo-filter recording-algo)
          n1   (d/leaf :n1 (c/context) 1/16 [60])
          n2   (d/leaf :n2 (c/context) 1/16 [67])
          n3   (d/leaf :n3 (c/context) 1/16 [72])
          verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
          root  {:type :ROOT :id :ROOT
                 :context (c/context-root {"Tempo" 240 "volume" 80})
                 :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (repo/play-latest!)
      (let [eng  (engine/engine nil repo/play-tx :ROOT)
            done (promise)]
        (binding [engine/*engine* eng]
          (conductor/register-action! :done (fn [_] (deliver done true)))
          (conductor/schedule! :verse :exit :done)
          (engine/play :verse :algo ::recording-lo-filter)
          (is (not= :timeout (deref done 2000 :timeout))
              "the voice ran to completion even though 2 of its 3 children got dropped")
          (is (= 1 (count @seen)) "the container was visited once, as a real live voice")
          (is (= [[60]] (first @seen))
              "60 (<=64) survives; 67 and 72 (>64) are genuinely gone from the
               result, not rested -- confirmed live, not just reasoned about
               from the pure fn's own unit tests"))))))
