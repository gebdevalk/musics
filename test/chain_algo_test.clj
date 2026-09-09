(ns ^:engine chain-algo-test
  "algo.common.reshape/chain-algo -- composing several already-built,
   NAMED algos into one, the answer to 'a flexible, simple way to
   prepare and perform a composite algorithm, without a text grammar':
   plain Clojure data (a vector of names) resolved via core.wall/
   resolve-name, PREPARED via core.wall/build! and PERFORMED via
   assign-algo!/play's own :algo tag -- both already-existing
   mechanisms, nothing new needed for prepare/perform themselves."
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
;; core.wall/resolve-name -- the resolution logic itself, confirmed to
;; behave identically to what core.async-engine's own assign-algo!
;; uses internally.
;; ============================================================

(deftest resolve-name-nil-is-identity
  (with-fresh-registries
    (is (= wall/identity-algo (wall/resolve-name nil)))))

(deftest resolve-name-bare-registered-name-resolves-to-its-fn
  (with-fresh-registries
    (let [f (fn [nodes _ _] nodes)]
      (wall/build-algo! ::plain f)
      (is (= f (wall/resolve-name ::plain))))))

(deftest resolve-name-unregistered-bare-name-degrades-to-identity
  (with-fresh-registries
    (is (= wall/identity-algo (wall/resolve-name ::nonexistent)))))

;; ============================================================
;; chain-algo -- pure composition, threading nodes through each step
;; ============================================================

(deftest chain-algo-threads-nodes-through-each-step-in-order
  (with-fresh-registries
    (wall/build-algo! ::add-a (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :a) nodes)))
    (wall/build-algo! ::add-b (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :b) nodes)))
    (reshape/chain-algo ::chained ::add-a ::add-b)
    (let [out ((wall/algo ::chained) [{:tags []}] [] nil)]
      (is (= [[:a :b]] (mapv :tags out))
          "add-a ran FIRST, its output fed into add-b -- order matters"))))

(deftest chain-algo-reversed-order-produces-a-different-result
  (with-fresh-registries
    (wall/build-algo! ::add-x (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :x) nodes)))
    (wall/build-algo! ::add-y (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :y) nodes)))
    (reshape/chain-algo ::xy ::add-x ::add-y)
    (reshape/chain-algo ::yx ::add-y ::add-x)
    (is (= [[:x :y]] (mapv :tags ((wall/algo ::xy) [{:tags []}] [] nil))))
    (is (= [[:y :x]] (mapv :tags ((wall/algo ::yx) [{:tags []}] [] nil))))))

(deftest chain-algo-with-real-filter-and-shuffle-factories
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (gate/gate-algo ::loFilter [::lo 70] :remove)
    (wall/register-distribution! ::uniform rnd/uniform)
    (reshape/weighted-shuffle-algo ::shuffled ::uniform)
    (reshape/chain-algo ::chained ::loFilter ::shuffled)
    (let [chained (wall/algo ::chained)
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

(deftest chain-algo-an-unregistered-mid-chain-step-degrades-just-that-step
  (with-fresh-registries
    (wall/build-algo! ::add-tag (fn [nodes _ _] (mapv #(update % :tags (fnil conj []) :tagged) nodes)))
    (reshape/chain-algo ::chained ::nonexistent-step ::add-tag)
    (let [out ((wall/algo ::chained) [{:tags []}] [] nil)]
      (is (= [[:tagged]] (mapv :tags out))
          "the bad step degraded to identity (a no-op), the REST of the chain still ran"))))

;; ============================================================
;; Live engine proof -- prepare (build!/calling factories directly) and
;; perform (play) via a real composite chain
;; ============================================================

(deftest chain-algo-prepared-and-performed-live
  (with-fresh-registries
    (wall/register-criterion! ::lo gate/lo-criterion)
    (wall/register-distribution! ::uniform rnd/uniform)
    ;; PREPARE: a named, reusable composite -- filter then shuffle
    (gate/gate-algo ::loFilter64 [::lo 64] :remove)
    (reshape/weighted-shuffle-algo ::shuffled ::uniform)
    (reshape/chain-algo ::morning ::loFilter64 ::shuffled)
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
          ;; PERFORM: reference the prepared, built algo by name, same
          ;; as any other algorithm
          (engine/play :verse :algo ::morning)
          (is (not= :timeout (deref done 2000 :timeout))
              "a real voice, running a real prepared composite, ran to completion"))))))
