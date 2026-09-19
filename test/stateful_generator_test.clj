(ns ^:engine stateful-generator-test
  (:require [clojure.test :refer [deftest is]]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- placeholder [id] (d/leaf id nil 1/4 [0]))

;; A tiny, deterministic next-fn/render-fn pair -- an atom-backed
;; counter, same self-contained-state shape logistic-function/
;; lorenz-attractor's own :value closures already have.
(defn- counting-next-fn []
  (let [i* (atom -1)]
    (fn [] (swap! i* inc))))

(defn- pitch-render-fn [i] {:pitches [(+ 60 i)] :duration 1/4})

(deftest stateful-generator-substitutes-content-for-placeholders
  (let [algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        out    (algofn [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)]
    (is (= [[60] [61] [62]] (map :pitches out)))
    (is (every? #(= :LEAF (:type %)) out))))

(deftest stateful-generator-continues-across-successive-calls
  (let [algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        batch1 (algofn [(placeholder :p1) (placeholder :p2)] [] nil)
        batch2 (algofn [(placeholder :p3) (placeholder :p4)] [] nil)]
    (is (= [[60] [61]] (map :pitches batch1)))
    (is (= [[62] [63]] (map :pitches batch2))
        "next-fn's own state continues from where batch1 left off")))

(deftest stateful-generator-is-idempotent-on-an-already-tagged-node
  ;; core.wall's own double-call contract: a container's full sibling
  ;; batch, then again per already-produced node singleton-wrapped. The
  ;; second (singleton) call must NOT call next-fn again, or the
  ;; underlying state would double-advance and desync from what
  ;; actually played.
  (let [algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        batch  (algofn [(placeholder :p1) (placeholder :p2)] [] nil)
        resung (algofn [(first batch)] [] nil)]
    (is (= (first batch) (first resung))
        "re-running an already-produced node through the same fn changes nothing")
    (let [next (algofn [(placeholder :p3)] [] nil)]
      (is (= [62] (:pitches (first next)))
          "a genuinely NEW placeholder still advances from where batch left off (index 2)"))))

(deftest stateful-generator-passes-non-leaf-nodes-through-untouched
  (let [algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        bar    (d/bar 3)
        out    (algofn [bar (placeholder :p1)] [] nil)]
    (is (= bar (first out)) "a Bar consumes no step")
    (is (= [60] (:pitches (second out)))
        "the leaf still gets index 0 -- the Bar didn't advance next-fn")))

(deftest stateful-generator-two-instances-dont-share-state
  (let [algofn-a (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        algofn-b (wall/stateful-generator (counting-next-fn) pitch-render-fn)]
    (algofn-a [(placeholder :p1) (placeholder :p2) (placeholder :p3)] [] nil)
    (let [b-out (algofn-b [(placeholder :q1)] [] nil)]
      (is (= [60] (:pitches (first b-out)))
          "b's own next-fn starts fresh, unaffected by a already being 3 steps in"))))

(deftest stateful-generator-pre-step-fn-samples-ctx-chain-and-structural-time
  (let [calls   (atom [])
        voice   {:structural (atom 5/4)}
        pre-fn  (fn [ctx-chain t] (swap! calls conj [ctx-chain t]))
        algofn  (wall/stateful-generator (counting-next-fn) pitch-render-fn pre-fn)
        chain   [:fake-context]]
    (doall (algofn [(placeholder :p1) (placeholder :p2)] chain voice))
    (is (= [[chain 5/4] [chain 5/4]] @calls)
        "called once per genuinely new placeholder, with ctx-chain and the
         voice's own current @(:structural voice), NOT some internal counter")))

(deftest stateful-generator-pre-step-fn-does-not-refire-on-an-already-tagged-node
  (let [calls  (atom 0)
        voice  {:structural (atom 0)}
        pre-fn (fn [_chain _t] (swap! calls inc))
        algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn pre-fn)
        batch  (doall (algofn [(placeholder :p1)] [] voice))]
    (is (= 1 @calls))
    (doall (algofn [(first batch)] [] voice))
    (is (= 1 @calls)
        "re-running an already-produced node never calls pre-step-fn a second time")))

(deftest context-params-pre-step-fn-samples-each-key-and-calls-setter
  (let [ctx-chain [(c/context-root {:chaosA 1.4 :chaosB 0.3})]
        calls     (atom [])
        setter    (fn [m] (swap! calls conj m))
        pre-fn    (wall/context-params-pre-step-fn {:a :chaosA :b :chaosB} setter)]
    (pre-fn ctx-chain 0)
    (is (= [{:a 1.4 :b 0.3}] @calls))))

(deftest context-params-pre-step-fn-only-samples-keys-actually-given
  (let [ctx-chain [(c/context-root {:chaosR 3.8 :unrelated 99})]
        calls     (atom [])
        pre-fn    (wall/context-params-pre-step-fn {:r :chaosR} (fn [m] (swap! calls conj m)))]
    (pre-fn ctx-chain 0)
    (is (= [{:r 3.8}] @calls) "only :r is sampled/passed, :unrelated is never touched")))

(deftest stateful-generator-omitting-pre-step-fn-never-touches-voice
  ;; The 2-arg form must stay completely unchanged for every existing
  ;; caller (logistic-algo/lorenz-algo/henon-algo) -- confirmed here with
  ;; voice itself nil, which a pre-step-fn dereferencing :structural would
  ;; NPE on if it were ever invoked.
  (let [algofn (wall/stateful-generator (counting-next-fn) pitch-render-fn)
        out    (algofn [(placeholder :p1)] [] nil)]
    (is (= [60] (:pitches (first out))))))
