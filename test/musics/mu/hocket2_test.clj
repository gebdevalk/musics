(ns ^:repl musics.mu.hocket2-test
  "mus/hocket2.mus: one voice word, started twice, B late (later): B asks for onset 0 when it
   starts, so it plays what A played, a lag after (the two voices' time 0 are a lag apart)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.live :as live]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "play" "music" "tool" "arith" "compare"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (reset! (:envelopes-atom live/tctx) {})
        (t))
      (finally (reset! (:envelopes-atom live/tctx) {})
               (doseq [v [:A :B]] (inputs/clear! v))
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [text] (swap! e/state assoc :stack []) (e/eval-input text))

(deftest b-starts-a-lag-after-a-and-plays-what-a-played
  (let [r (with-out-str (run (slurp (io/file "mus/hocket2.mus"))))]
    (is (not (re-find #"can't read|error" r)) r))
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (binding [engine/*engine* eng]
      (with-redefs [engine/receiver (atom :silent)]
        (let [t0 (System/nanoTime)
              r  (run "start")]
          (is (nil? (:error r)) (:error r))
          (let [origin #(:origin (get @(:streams eng) [%]))
                lag    (/ (- (origin :B) (origin :A)) 1e9)]
            (is (some? (engine/voice-at :A)))
            (is (some? (engine/voice-at :B)))
            (is (< 0.2999 lag 0.3001) "1/8 of a whole note at 100: 0.3 s after A, however long starting A took")
            (is (<= (- (origin :A) t0) 5e7) "and A starts at once")
            (run ":A stop :B stop")))))))
