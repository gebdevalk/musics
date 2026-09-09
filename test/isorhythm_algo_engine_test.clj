(ns ^:engine isorhythm-algo-engine-test
  "Live end-to-end proof of the 'engine feeds itself' pattern discussed
   alongside algo.common.isorhythm/color-talea-algo: a voice whose repo
   material is a single, musically inert placeholder note wrapped in a
   :count :infinite Iterator, with a real generator (color-talea) doing
   all the actual composing at playback time via core.wall. Confirms
   two things color-talea-algo's own unit tests (isorhythm-test) can't:
   that this genuinely drives a live core.async voice far past the ONE
   note the repo itself contains, and that stop! still reaches it just
   as fast as any ordinary voice -- a self-feeding voice isn't a
   special, harder-to-kill case."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [algo.common.isorhythm :as iso]))

(deftest isorhythm-algo-drives-a-self-feeding-voice-forever-until-stopped
 (with-fresh-registries
  (wall/register-factory! ::color-talea iso/color-talea-algo
                           "isorhythmic generator -- ignores its own placeholder input")
  (iso/color-talea-algo ::coloredVerse [60 62 64] [1/4 1/8])
  (let [placeholder (d/leaf :ph (c/context) 1/4 [0])
        source      {:type :SEQ :id :s1 :context (c/context) :children [placeholder]}
        iter        (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
        verse       {:type :SEQ :id :verse :context (c/context) :children [iter]}
        ;; tempo cranked way up, same trick bar-boundary-signal-fires-
        ;; during-playback already uses, so several bars pass in a few ms
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
        (let [path (engine/play :verse :algo ::coloredVerse)]
          (is (not= :timeout (deref bar5 3000 :timeout))
              "the voice reached bar 5 -- ~20 synthesized notes' worth -- driven
               entirely by color-talea-algo re-firing on the SAME one-note
               placeholder Iterator every cycle, not by any material actually
               committed to the repo")
          (engine/stop! eng)
          (Thread/sleep 50)
          (is (nil? (get @(:voices eng) path))
              "stop! reaches a self-feeding voice within its normal ~20ms window,
               same as any ordinary one -- it is not a special, harder-to-kill
               case just because its sounding content is synthesized rather than
               committed material")))))))
