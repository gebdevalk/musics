(ns ^:engine henon-algo-engine-test
  "Live end-to-end proof that henon-algo -- one line of glue over
   core.wall/stateful-generator, same as logistic-algo/lorenz-algo --
   genuinely drives a real core.async-engine voice, forever, off a
   single placeholder note wrapped in a :count :infinite Iterator."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [algo.random.henon :as henon]))

(deftest henon-algo-drives-a-self-feeding-voice-forever-until-stopped
 (with-fresh-registries
  (henon/henon-algo ::henon-pitch {:a 1.4 :b 0.3 :x0 0.1 :y0 0.1})
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
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark-bar5 (fn [event] (deliver bar5 event)))
        (conductor/schedule! 5 :enter :mark-bar5)
        (let [path (engine/play :verse :algo ::henon-pitch)]
          (is (not= :timeout (deref bar5 3000 :timeout))
              "the voice reached bar 5 -- driven entirely by the Hénon
               map's own chaotic trajectory, re-firing on the SAME
               one-note placeholder Iterator every cycle")
          (engine/stop! eng)
          (Thread/sleep 50)
          (is (nil? (get @(:voices eng) path))
              "stop! reaches it within its normal ~20ms window, same as any
               ordinary voice")))))))
