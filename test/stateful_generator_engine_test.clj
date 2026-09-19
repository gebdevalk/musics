(ns ^:engine stateful-generator-engine-test
  "Live end-to-end proof that core.wall/stateful-generator (not just
   algo.common.isorhythm/color-talea-algo's own hand-rolled version of
   the same idempotency dance) genuinely drives a real core.async-engine
   voice, forever, off a single placeholder note wrapped in a :count
   :infinite Iterator -- mirroring isorhythm-algo-engine-test's own
   proof for color-talea-algo."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- rising-pitch-generator []
  (let [i* (atom 59)]
    (wall/stateful-generator (fn [] (swap! i* inc))
                              (fn [pitch] {:pitches [pitch] :duration 1/4}))))

(deftest stateful-generator-drives-a-self-feeding-voice-forever-until-stopped
 (with-fresh-registries
  (wall/build-algo! ::rising (rising-pitch-generator)
                     "rising-pitch generator over stateful-generator")
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
        (let [path (engine/play :verse :algo ::rising)]
          (is (not= :timeout (deref bar5 3000 :timeout))
              "the voice reached bar 5 -- ~20 synthesized notes' worth -- driven
               entirely by stateful-generator re-firing on the SAME one-note
               placeholder Iterator every cycle")
          (engine/stop! eng)
          (Thread/sleep 50)
          (is (nil? (get @(:voices eng) path))
              "stop! reaches it within its normal ~20ms window, same as any
               ordinary voice")))))))
