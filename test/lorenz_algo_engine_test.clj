(ns ^:engine lorenz-algo-engine-test
  "Live end-to-end proof that lorenz-algo -- one line of glue over
   core.wall/stateful-generator (0863ac8), same as logistic-algo
   (2742acc) -- genuinely drives a real core.async-engine voice, forever,
   off a single placeholder note wrapped in a :count :infinite Iterator."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [algo.random.lorenz :as lorenz]))

(deftest lorenz-algo-drives-a-self-feeding-voice-forever-until-stopped
 (with-fresh-registries
  (lorenz/lorenz-algo ::lorenz-pitch {:sigma 10.0 :rho 28.0 :beta (/ 8.0 3.0) :x0 1.0 :y0 1.0 :z0 1.0})
  (let [placeholder (d/leaf :ph (c/context) 1/4 [0])
        source      {:type :SEQ :id :s1 :context (c/context) :children [placeholder]}
        iter        (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
        verse       {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root        {:type :ROOT :id :ROOT
                     :context (c/context-root {"Tempo" 6000 "volume" 80})
                     :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng  (engine/engine nil (repo/registry) :ROOT)
          bar5 (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark-bar5 (fn [event] (deliver bar5 event)))
        (conductor/schedule! 5 :enter :mark-bar5)
        (let [path (engine/play :verse :algo ::lorenz-pitch)]
          (is (not= :timeout (deref bar5 3000 :timeout))
              "the voice reached bar 5 -- driven entirely by the Lorenz
               system's own chaotic trajectory, re-firing on the SAME
               one-note placeholder Iterator every cycle")
          (engine/stop! eng)
          (Thread/sleep 50)
          (is (nil? (get @(:voices eng) path))
              "stop! reaches it within its normal ~20ms window, same as any
               ordinary voice")))))))
