(ns ^:engine chain-algo-test
  "algo.common.reshape/chain-algo -- composing several NAMED algos into
   one, the answer to 'a flexible, simple way to prepare and perform a
   composite algorithm, without a text grammar': plain Clojure data (a
   vector of Name specs, the SAME shape assign-algo! already accepts)
   resolved via the newly-extracted core.wall/resolve-name, PREPARED
   via configure-preset! and PERFORMED via assign-algo!/play's own
   :algo tag -- both already-existing mechanisms, nothing new needed
   for prepare/perform themselves."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [algo.common.reshape :as reshape]
            [algo.common.gate :as gate]
            [algo.random :as rnd]
            [core.wall :as wall]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

;; ============================================================
;; core.wall/resolve-name -- the extracted resolution logic itself,
;; confirmed to behave IDENTICALLY to core.async-engine's own former
;; private copy (async_engine_test.clj already covers every one of
;; these cases through assign-algo!/play -- this is the direct,
;; extraction-level confirmation).
;; ============================================================

(deftest resolve-name-nil-is-identity
  (with-fresh-registries
    (is (= wall/identity-algo (wall/resolve-name nil)))))

(deftest resolve-name-bare-registered-name-resolves-to-its-fn
  (with-fresh-registries
    (let [f (fn [nodes _ _] nodes)]
      (wall/register-algo! ::plain f)
      (is (= f (wall/resolve-name ::plain))))))

(deftest resolve-name-unregistered-bare-name-degrades-to-identity
  (with-fresh-registries
    (is (= wall/identity-algo (wall/resolve-name ::nonexistent)))))

(deftest resolve-name-vector-form-applies-the-factory
  (with-fresh-registries
    (wall/register-algo! ::stamp (fn [n] (fn [nodes _ _] (map #(assoc % :n n) nodes))) nil :factory)
    (let [resolved (wall/resolve-name [::stamp 7])]
      (is (= [{:n 7}] (resolved [{}] [] nil))))))

(deftest resolve-name-a-bare-reference-to-a-declared-factory-degrades-to-identity
  (with-fresh-registries
    (wall/register-algo! ::factory-only (fn [n] (fn [nodes _ _] nodes)) nil :factory)
    (is (= wall/identity-algo (wall/resolve-name ::factory-only))
        "a bare name declared :kind :factory can't be used without args")))

(deftest resolve-name-checks-presets-before-the-plain-algo-registry
  (with-fresh-registries
    (wall/register-algo! ::stamp2 (fn [n] (fn [nodes _ _] (map #(assoc % :n n) nodes))) nil :factory)
    (wall/configure-preset! ::myPreset ::stamp2 42)
    (is (= [{:n 42}] ((wall/resolve-name ::myPreset) [{}] [] nil)))))

;; ============================================================
;; chain-algo -- pure composition, threading nodes through each spec
;; ============================================================

(deftest chain-algo-threads-nodes-through-each-spec-in-order
  (with-fresh-registries
    (wall/register-algo! ::add-a (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :a) nodes)))
    (wall/register-algo! ::add-b (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :b) nodes)))
    (let [chained (reshape/chain-algo ::add-a ::add-b)
          out     (chained [{:tags []}] [] nil)]
      (is (= [[:a :b]] (mapv :tags out))
          "add-a ran FIRST, its output fed into add-b -- order matters"))))

(deftest chain-algo-reversed-order-produces-a-different-result
  (with-fresh-registries
    (wall/register-algo! ::add-x (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :x) nodes)))
    (wall/register-algo! ::add-y (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :y) nodes)))
    (is (= [[:x :y]] (mapv :tags ((reshape/chain-algo ::add-x ::add-y) [{:tags []}] [] nil))))
    (is (= [[:y :x]] (mapv :tags ((reshape/chain-algo ::add-y ::add-x) [{:tags []}] [] nil))))))

(deftest chain-algo-with-real-filter-and-shuffle-factories
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (wall/register-algo! ::gate gate/gate-algo nil :factory)
    (wall/register-distribution! ::uniform rnd/uniform)
    (wall/register-algo! ::weightedShuffle reshape/weighted-shuffle-algo nil :factory)
    (let [chained (reshape/chain-algo [::gate [::lo 70] :remove] [::weightedShuffle ::uniform])
          n1 (d/leaf :n1 nil 1/4 [60])
          n2 (d/leaf :n2 nil 1/4 [67])
          n3 (d/leaf :n3 nil 1/4 [72])
          out (chained [n1 n2 n3] [] nil)]
      (is (= 2 (count out))
          "the filter stage ran -- 72 (above 70) was DROPPED, not rested, so
           the shuffle stage received only 2 parts, not 3")
      (is (every? #(<= (first (:pitches %)) 70) out)
          "nothing above 70 survives as a pitch")
      (is (= #{60 67} (set (map (comp first :pitches) out)))
          "the shuffle stage ran on the filter's OWN (already-shrunk) output --
           still the same 2 surviving pitches, just possibly reordered"))))

(deftest chain-algo-an-unregistered-mid-chain-spec-degrades-just-that-step
  (with-fresh-registries
    (wall/register-algo! ::add-tag (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :tagged) nodes)))
    (let [chained (reshape/chain-algo ::nonexistent-spec ::add-tag)
          out     (chained [{:tags []}] [] nil)]
      (is (= [[:tagged]] (mapv :tags out))
          "the bad spec degraded to identity (a no-op), the REST of the chain still ran"))))

;; ============================================================
;; Live engine proof -- prepare (configure-preset!) and perform (play)
;; via a real composite chain
;; ============================================================

(deftest chain-algo-prepared-as-a-preset-and-performed-live
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (wall/register-algo! ::gate gate/gate-algo nil :factory)
    (wall/register-distribution! ::uniform rnd/uniform)
    (wall/register-algo! ::weightedShuffle reshape/weighted-shuffle-algo nil :factory)
    (wall/register-algo! ::chain reshape/chain-algo nil :factory)
    ;; PREPARE: a named, reusable composite -- filter then shuffle
    (wall/configure-preset! ::morning ::chain [::gate [::lo 64] :remove] [::weightedShuffle ::uniform])
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
            done (promise)]
        (binding [engine/*engine* eng]
          (conductor/register-action! :done (fn [_] (deliver done true)))
          (conductor/schedule! :verse :exit :done)
          ;; PERFORM: reference the prepared preset by name, same as
          ;; any other algorithm
          (engine/play :verse :algo ::morning)
          (is (not= :timeout (deref done 2000 :timeout))
              "a real voice, running a real prepared composite, ran to completion"))))))
