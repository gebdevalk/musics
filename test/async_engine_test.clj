(ns async-engine-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.engine.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(deftest section-boundary-signals-fire-during-playback
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng     (engine/engine nil repo/play-tx :ROOT)
          entered (promise)
          exited  (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-enter (fn [_] (deliver entered true)))
      (conductor/register-action! :mark-exit (fn [_] (deliver exited true)))
      (conductor/schedule! :verse :enter :mark-enter)
      (conductor/schedule! :verse :exit :mark-exit)
      (engine/play :verse)
      (is (= true (deref entered 2000 :timeout))
          "play-node signaled :verse's :enter before playing its child")
      (is (= true (deref exited 2000 :timeout))
          "play-node signaled :verse's :exit once its single leaf finished"))))
