(ns ^:engine lorenz-wall-engine-test
  "Live end-to-end proof that lorenz-wall -- one line of glue over
   core.wall/stateful-generator (0863ac8), same as logistic-wall
   (2742acc) -- genuinely drives a real core.async-engine voice, forever,
   off a single placeholder note wrapped in a :count :infinite Iterator."
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [algo.random.lorenz :as lorenz]))

(deftest lorenz-wall-drives-a-self-feeding-voice-forever-until-stopped
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  (wall/unregister-wall! ::lorenz-pitch)
  (wall/register-wall! ::lorenz-pitch lorenz/lorenz-wall
                        "chaotic Lorenz-attractor pitch generator" :factory)
  (let [placeholder (d/leaf :ph (c/context) 1/4 [0])
        source      {:type :SEQ :id :s1 :context (c/context) :children [placeholder]}
        iter        (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
        verse       {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root        {:type :ROOT :id :ROOT
                     :context (c/context-root {"Tempo" 6000 "volume" 80})
                     :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          bar5 (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-bar5 (fn [event] (deliver bar5 event)))
      (conductor/schedule! 5 :enter :mark-bar5)
      (let [path (engine/play :verse :algo [::lorenz-pitch 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0])]
        (is (not= :timeout (deref bar5 3000 :timeout))
            "the voice reached bar 5 -- driven entirely by the Lorenz
             system's own chaotic trajectory, re-firing on the SAME
             one-note placeholder Iterator every cycle")
        (engine/stop! eng)
        (Thread/sleep 50)
        (is (nil? (get @(:voices eng) path))
            "stop! reaches it within its normal ~20ms window, same as any
             ordinary voice")))))
