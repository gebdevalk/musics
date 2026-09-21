(ns ^:engine registries-test
  "Proves the actual point of core.registries: a test can (binding
   [...] ...) itself a completely fresh, isolated instance of
   core.repo/core.wall/core.conductor's state -- including through a
   real async playback session -- with zero leakage in either
   direction. Not just unit tests of the individual vars; the second
   deftest below runs a genuine commit -> play -> wall algo -> conductor
   signal pipeline entirely inside one binding form, which is also the
   live confirmation that core.async's go blocks actually see bindings
   established before they were created (a real, checkable claim, not
   an assumed one -- see core.registries' own ns docstring).

   Deliberately keeps its own INNER binding forms spelled out by hand
   (not test-support/with-fresh-registries) -- that IS what this file
   is demonstrating/proving, the raw mechanism the shared macro is
   itself built from, not just a test that happens to need isolation.
   Only the OUTER setup (giving each test its own clean baseline,
   isolated from whatever any OTHER test namespace left behind) uses
   with-fresh-registries."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.wall :as wall]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(deftest binding-a-fresh-registry-set-never-touches-the-real-one
 (with-fresh-registries
  (wall/build-algo! ::outer-marker (fn [nodes _ctx _voice] nodes))
  (let [outer-wall-before @reg/*algo-registry*]
    (binding [reg/*algo-registry*               (atom {})
              reg/*conductor-action-registry*   (atom {})
              reg/*conductor-schedule*          (atom {})
              reg/*conductor-repeating*         (atom {})]
      (is (nil? (wall/algo ::outer-marker))
          "the outer registration is invisible inside the fresh, bound registry")
      (wall/build-algo! ::inner-marker (fn [nodes _ctx _voice] nodes))
      (is (some? (wall/algo ::inner-marker))
          "a registration made INSIDE the binding is visible inside it"))
    (is (= outer-wall-before @reg/*algo-registry*)
        "the real registry is byte-for-byte unchanged by anything done inside the binding")
    (is (nil? (wall/algo ::inner-marker))
        "the inner-only registration never leaked out once the binding form exited"))))

(deftest a-real-play-call-fully-isolated-by-binding-including-across-go-blocks
 ;; The stronger claim: an entire async playback session -- commit,
 ;; play, a wall algorithm doing real work, a conductor signal firing
 ;; -- run genuinely isolated inside one binding form, proving
 ;; core.async's go blocks actually carry the bound values through
 ;; their own suspend/resume lifecycle (they're created inside the
 ;; binding's dynamic extent but keep running after this whole deftest
 ;; body has, in wall-clock terms, moved on to derefing a promise).
 (with-fresh-registries
  (let [marked (atom [])
        mark!  (fn [nodes _ctx _voice] (swap! marked conj (count nodes)) nodes)]
    (binding [reg/*repo-registry*              (atom {})
              reg/*algo-registry*               (atom {})
              reg/*conductor-action-registry*   (atom {})
              reg/*conductor-schedule*          (atom {})
              reg/*conductor-repeating*         (atom {})]
      (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
            verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
            root  {:type :ROOT :id :ROOT
                   :context (c/context-root {"Tempo" 6000 "volume" 80})
                   :children [:verse]}]
        (repo/commit-node! :ROOT root)
        (repo/commit-node! :verse verse)
        (let [eng  (engine/engine nil (repo/registry) :ROOT)
              done (promise)]
          (binding [engine/*engine* eng]
            (wall/build-algo! ::isolated-mark mark!)
            (conductor/register-action! :done (fn [_] (deliver done true)))
            (conductor/schedule! :verse :exit :done)
            (engine/play :verse :algo ::isolated-mark)
            (is (= true (deref done 2000 :timeout))
                "playback -- entirely inside a bound, isolated registry set,
                 including its own go-blocks -- actually ran to completion")
            (is (pos? (count @marked))
                "the wall algorithm registered INSIDE the binding actually
                 ran, proving the voice's go-block resolved :algo against
                 the bound *algo-registry*, not the real global one")))))
    ;; Back outside the inner binding (but still inside the outer
    ;; with-fresh-registries): none of this ever happened as far as
    ;; THAT state is concerned.
    (is (nil? (repo/current :ROOT))
        "the outer, isolated core.repo never saw :ROOT/:verse get committed at all")
    (is (nil? (wall/algo ::isolated-mark))
        "the outer, isolated core.wall never saw ::isolated-mark get registered")
    (is (nil? (conductor/scheduled :verse :exit))
        "the outer, isolated core.conductor never saw the :verse :exit schedule entry"))))
