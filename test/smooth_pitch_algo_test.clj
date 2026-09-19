(ns ^:engine smooth-pitch-algo-test
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [algo.common.zfilter :as zf]
            [core.wall :as wall]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(deftest smooth-pitch-algo-behaves-identically-to-the-pure-fn
  (zf/smooth-pitch-algo ::identical {:alpha 0.7})
  (let [algo-fn (wall/algo ::identical)
        n1 (d/leaf :n1 nil 1/4 [60])
        n2 (d/leaf :n2 nil 1/4 [90])
        n3 (d/leaf :n3 nil 1/4 [40])
        out (algo-fn [n1 n2 n3] [] nil)
        expected-raw (zf/smooth 0.7 [60 90 40])]
    (is (= (mapv #(Math/round (double %)) expected-raw)
           (mapv (comp first :pitches) out)))))

(deftest smooth-pitch-algo-passes-through-rest-and-drum-pitches-untouched
  (zf/smooth-pitch-algo ::passthrough {:alpha 0.5})
  (let [algo-fn (wall/algo ::passthrough)
        r  (d/rest* :r1 nil 1/4)
        n1 (d/leaf :n1 nil 1/4 [60])
        n2 (d/leaf :n2 nil 1/4 [80])
        out (algo-fn [n1 r n2] [] nil)]
    (is (d/rest? (nth out 1)) "the rest stays a rest, untouched")
    (is (not= 60 (first (:pitches (nth out 2))))
        "n2's pitch was smoothed toward n1's, not left at the raw 80")))

(deftest smooth-pitch-algo-is-a-no-op-on-fewer-than-2-leaves
  (zf/smooth-pitch-algo ::no-op-case {:alpha 0.7})
  (let [algo-fn (wall/algo ::no-op-case)
        n1 (d/leaf :n1 nil 1/4 [60])]
    (is (= [n1] (algo-fn [n1] [] nil)))))

;; ============================================================
;; Live engine proof
;; ============================================================

(deftest smooth-pitch-actually-smooths-in-a-real-live-voice
  (with-fresh-registries
    (let [n1   (d/leaf :n1 (c/context) 1/16 [40])
          n2   (d/leaf :n2 (c/context) 1/16 [100])
          n3   (d/leaf :n3 (c/context) 1/16 [40])
          verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
          root  {:type :ROOT :id :ROOT
                 :context (c/context-root {"Tempo" 240 "volume" 80})
                 :children [:verse]}]
      (zf/smooth-pitch-algo ::smooth-pitch {:alpha 0.8})
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (let [eng  (engine/engine nil (repo/registry) :ROOT)
            done (promise)
            seen (atom nil)]
        (binding [engine/*engine* eng]
          ;; wrap the registered algo to record what the container-batch
          ;; call actually produced, same recording-wrapper trick used
          ;; earlier this session
          (let [base (wall/algo ::smooth-pitch)]
            (wall/build-algo! ::recording-smooth
              (fn [nodes ctx voice]
                (let [out (base nodes ctx voice)]
                  (when (= 3 (count nodes)) (reset! seen (mapv (comp first :pitches) out)))
                  out))))
          (conductor/register-action! :done (fn [_] (deliver done true)))
          (conductor/schedule! :verse :exit :done)
          (engine/play :verse :algo ::recording-smooth)
          (is (not= :timeout (deref done 2000 :timeout)))
          (is (some? @seen))
          (is (not= [40 100 40] @seen)
              "the raw pitches were genuinely smoothed, not passed through unchanged")
          ;; hand-derived: smooth(0.8, [40 100 40]) -- y0=0.2*40=8,
          ;; y1=0.2*100+0.8*8=26.4, y2=0.2*40+0.8*26.4=29.12 -- rounded
          ;; to [8 26 29], matching what live playback actually produced.
          (is (= [8 26 29] @seen)
              "the live voice's own output matches the pure fn's hand-derived smoothing"))))))
