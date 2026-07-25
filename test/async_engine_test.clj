(ns async-engine-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.engine.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.elements.music-elements :as el]))

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

(deftest bar-boundary-signal-fires-during-playback
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [meter (el/make-meter 4 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        n3    (d/leaf :n3 (c/context) 1/4 [64])
        n4    (d/leaf :n4 (c/context) 1/4 [65])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3 n4]}
        root  {:type :ROOT :id :ROOT
               ;; tempo cranked way up so the whole bar plays in a few ms
               :context (c/context-root {"tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          bar2 (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
      (conductor/schedule! 2 :enter :mark-bar2)
      (engine/play :verse)
      (is (= 2 (:id (deref bar2 2000 :timeout)))
          "advance-bar! signaled entering bar 2 once the four quarter notes filled bar 1 (4/4)"))))

(deftest bar-boundary-respects-a-non-default-meter
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [meter (el/make-meter 3 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        n3    (d/leaf :n3 (c/context) 1/4 [64])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          bar2 (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
      (conductor/schedule! 2 :enter :mark-bar2)
      (engine/play :verse)
      (is (= 2 (:id (deref bar2 2000 :timeout)))
          "three quarter notes exactly fill one 3/4 bar, so bar 2 starts right after"))))
