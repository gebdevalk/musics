(ns ^:engine weighted-shuffle-test
  "algo.common.reshape/weighted-shuffle + weighted-shuffle-algo -- the
   concrete proof-of-concept for 'algorithm COMPOSITION specified
   declaratively, not just parameters': a wall-fn factory whose own arg
   names ANOTHER registered thing (a distribution) rather than a
   literal value, built directly from the 'repeat n times, reshuffled
   every cycle, weighted by lo-emph' scenario discussed in the session
   that produced it."
  (:require [clojure.test :refer [deftest is testing]]
            [test-support :refer [with-fresh-registries]]
            [algo.common.reshape :as reshape]
            [algo.random :as rnd]
            [core.wall :as wall]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

;; ============================================================
;; weighted-shuffle -- pure fn, no registry involved
;; ============================================================

(defn- avg-displacement [n dist-fn trials]
  (double (/ (reduce + (for [_ (range trials)]
                          (let [perm (reshape/weighted-shuffle (vec (range n)) dist-fn)]
                            (reduce + (map-indexed (fn [out-pos orig-i] (Math/abs (- out-pos orig-i))) perm)))))
             (* trials n))))

(deftest weighted-shuffle-always-returns-a-permutation-of-the-input
  (let [parts [:a :b :c :d :e]]
    (dotimes [_ 50]
      (is (= (set parts) (set (reshape/weighted-shuffle parts rnd/uniform)))))))

(deftest weighted-shuffle-handles-0-and-1-element-collections
  (is (= [] (reshape/weighted-shuffle [] rnd/uniform)))
  (is (= [:only] (reshape/weighted-shuffle [:only] rnd/lo-emph))))

(deftest weighted-shuffle-with-uniform-reduces-to-an-ordinary-shuffle
  ;; Confirmed live in a throwaway probe before writing this: pick-
  ;; from-remaining with a uniform distribution reduces to the SAME
  ;; permutation distribution plain Fisher-Yates produces (~1.94 average
  ;; displacement at n=6 either way). Loose bound here, not an exact
  ;; match -- this is a real random process, not a deterministic one.
  (let [avg (avg-displacement 6 rnd/uniform 4000)]
    (is (< 1.5 avg 2.3) (str "avg displacement " avg " far from an ordinary shuffle's ~1.94"))))

(deftest weighted-shuffle-with-lo-emph-preserves-order-more-than-uniform
  ;; The actual claim this whole mechanism rests on, checked with real
  ;; trials, not just derived: lo-emph (peaked toward the LOW end)
  ;; biases picks toward the FRONT of what's remaining each step, so
  ;; elements tend to stay close to their original position -- a
  ;; meaningfully WEAKER shuffle than uniform's, not statistically
  ;; identical to it (an earlier, rejected 'sort by an independent key
  ;; per element' construction WOULD have been statistically identical
  ;; regardless of distribution shape -- confirmed and discarded before
  ;; building this one; see weighted-shuffle's own docstring).
  (let [uniform-avg (avg-displacement 6 rnd/uniform 4000)
        lo-emph-avg (avg-displacement 6 rnd/lo-emph 4000)]
    (is (< lo-emph-avg (* 0.85 uniform-avg))
        (str "lo-emph avg " lo-emph-avg " not meaningfully below uniform avg " uniform-avg))))

;; ============================================================
;; core.wall's distribution registry
;; ============================================================

(deftest distribution-registry-round-trips
  (with-fresh-registries
    (is (nil? (wall/distribution-fn ::my-dist)))
    (wall/register-distribution! ::my-dist rnd/lo-emph "peaked low")
    (is (= rnd/lo-emph (wall/distribution-fn ::my-dist)))
    (is (= "peaked low" (wall/distributions ::my-dist)))
    (is (contains? (wall/distributions) ::my-dist))
    (wall/unregister-distribution! ::my-dist)
    (is (nil? (wall/distribution-fn ::my-dist)))))

;; ============================================================
;; weighted-shuffle-algo -- the factory, resolving a distribution BY NAME
;; ============================================================

(deftest weighted-shuffle-algo-falls-back-to-identity-for-an-unregistered-distribution
  (with-fresh-registries
    (let [algo-fn (reshape/weighted-shuffle-algo ::nonexistent-dist)
          nodes   [{:id :a} {:id :b} {:id :c}]]
      (is (= nodes (algo-fn nodes [] nil))
          "unregistered dist-name degrades to identity, doesn't throw"))))

(deftest weighted-shuffle-algo-actually-shuffles-when-the-distribution-is-registered
  (with-fresh-registries
    (wall/register-distribution! ::uniform rnd/uniform)
    (let [algo-fn (reshape/weighted-shuffle-algo ::uniform)
          nodes   (mapv (fn [i] {:id i}) (range 8))
          results (repeatedly 20 #(mapv :id (algo-fn nodes [] nil)))]
      (is (every? #(= (set (map :id nodes)) (set %)) results)
          "every result is still a permutation of the same ids")
      (is (some #(not= % (mapv :id nodes)) results)
          "at least one of 20 real-random shuffles actually differs from the original order"))))

;; ============================================================
;; Live engine proof -- the actual scenario: repeat n times, reshuffled
;; EVERY cycle, weighted by a named distribution
;; ============================================================

(deftest repeat-body-reshuffles-fresh-on-every-single-cycle-live
  ;; The actual scenario this was built for, run for real: a finite
  ;; \repeat's body, its voice assigned weighted-shuffle-algo over a
  ;; registered distribution, observed live -- not just reasoned about
  ;; from reading play-iterator/play-node's own source.
  (with-fresh-registries
    (wall/register-distribution! ::uniform rnd/uniform)
    (let [seen    (atom [])
          shuffle (reshape/weighted-shuffle-algo ::uniform)
          ;; Record each call's own resulting pitch order as a side
          ;; effect, registered under its own name so it's reachable
          ;; the ordinary way (play's :algo tag) -- observing what
          ;; ACTUALLY got produced on each cycle, not just trusting the
          ;; mechanism.
          ;; core.wall's own double-call contract: a wall-fn is called
          ;; once on the container's full sibling batch, then AGAIN per
          ;; already-produced node singleton-wrapped (see register-
          ;; algo!'s own docstring). Only the batch call (all 5 nodes
          ;; at once) is the real, meaningful "one repeat cycle" event
          ;; -- singleton calls are individual-leaf dispatch, not a
          ;; second reshuffle, and shuffling a 1-element seq is a no-op
          ;; regardless.
          recording-algo (fn [nodes ctx-chain voice]
                            (let [out (shuffle nodes ctx-chain voice)]
                              (when (= 5 (count nodes))
                                (swap! seen conj (mapv (comp first :pitches) out)))
                              out))
          _    (wall/register-algo! ::recording-shuffle recording-algo)
          n1   (d/leaf :n1 (c/context) 1/16 [60])
          n2   (d/leaf :n2 (c/context) 1/16 [62])
          n3   (d/leaf :n3 (c/context) 1/16 [64])
          n4   (d/leaf :n4 (c/context) 1/16 [65])
          n5   (d/leaf :n5 (c/context) 1/16 [67])
          source {:type :SEQ :id :s1 :context (c/context) :children [n1 n2 n3 n4 n5]}
          iter   (d/iterator :REPEAT :r1 (c/context) source {:count 8 :repeat-type :unfold})
          verse  {:type :SEQ :id :verse :context (c/context) :children [iter]}
          root   {:type :ROOT :id :ROOT
                  :context (c/context-root {"Tempo" 6000 "volume" 80})
                  :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (repo/play-latest!)
      (let [eng  (engine/engine nil repo/play-tx :ROOT)
            done (promise)]
        (binding [engine/*engine* eng]
          (conductor/register-action! :done (fn [_] (deliver done true)))
          (conductor/schedule! :verse :exit :done)
          (engine/play :verse :algo ::recording-shuffle)
          (is (not= :timeout (deref done 3000 :timeout))
              "the repeat actually ran to completion")
          (is (= 8 (count @seen))
              "the container was genuinely visited fresh 8 times -- once per repeat cycle")
          (is (every? #(= #{60 62 64 65 67} (set %)) @seen)
              "every cycle's own output is still the same 5 pitches, just reordered")
          (is (< 1 (count (distinct @seen)))
              (str "expected real variation across the 8 cycles, got only "
                   (count (distinct @seen)) " distinct order(s): " @seen "\n"
                   "-- NOT full pairwise distinctness across all 8: with only 5!=120
                    possible orderings and 8 draws, a birthday-paradox-style repeat
                    is genuinely common (~21% of runs), caught live by this exact
                    test failing on an earlier, over-strict (apply distinct? ...)
                    version before this fix -- what actually matters is that
                    reshuffling happens fresh per cycle at all, not that every
                    cycle's result is pairwise unique from every other")))))))
