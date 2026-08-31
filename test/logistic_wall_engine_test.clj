(ns ^:engine logistic-wall-engine-test
  "Live end-to-end proof that logistic-wall -- one line of glue over
   core.wall/stateful-generator (0863ac8) -- genuinely drives a real
   core.async-engine voice, forever, off a single placeholder note
   wrapped in a :count :infinite Iterator. Mirrors isorhythm-wall-
   engine-test's own proof for color-talea-wall and stateful-generator-
   engine-test's own proof for the shared helper itself."
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [algo.random.logistic :as logistic]))

(deftest logistic-wall-drives-a-self-feeding-voice-forever-until-stopped
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  (wall/unregister-wall! ::logistic-pitch)
  (wall/register-wall! ::logistic-pitch logistic/logistic-wall
                        "chaotic logistic-map pitch generator" :factory)
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
      (let [path (engine/play :verse :algo [::logistic-pitch 3.8 0.5])]
        (is (not= :timeout (deref bar5 3000 :timeout))
            "the voice reached bar 5 -- driven entirely by the logistic
             map's own chaotic sequence, re-firing on the SAME one-note
             placeholder Iterator every cycle")
        (engine/stop! eng)
        (Thread/sleep 50)
        (is (nil? (get @(:voices eng) path))
            "stop! reaches it within its normal ~20ms window, same as any
             ordinary voice")))))
