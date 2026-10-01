(ns ^:engine micro-timing-test
  "Live proof that the :micro/:humanization context keys -- registered
   in common.context-keys for a long time already (per emails/messages/
   algorithm/Micro timing's own design, which already imported
   core.domain.context/Envelope and sampled these as context values,
   not raw arguments) but never actually applied anywhere -- now
   genuinely delay a note's real wall-clock onset, not just its
   :onset field in the resolved event map."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]
            [core.engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [core.domain.resolve :as r]
            [common.music-data :as data])
  (:import [javax.sound.midi Receiver ShortMessage]))

(defn- fake-receiver
  "A javax.sound.midi.Receiver that just records (System/nanoTime) into
   an atom every time a NOTE_ON message is sent -- enough to measure a
   real wall-clock onset gap without needing actual audio hardware."
  [onsets*]
  (reify Receiver
    (send [_ msg _]
      (when (and (instance? ShortMessage msg)
                 (= ShortMessage/NOTE_ON (.getCommand ^ShortMessage msg)))
        (swap! onsets* conj (System/nanoTime))))
    (close [_] nil)))

(defn- play-one-note-and-capture-first-onset-gap-ms
  "Build a single-note :verse, optionally with a root-level micro value,
   play it, and return the elapsed ms between calling play and the
   note's own real note-on landing at the fake receiver."
  [micro-value]
  (with-fresh-registries
    (let [onsets*  (atom [])
          n1       (d/leaf :n1 (c/context) 1/4 [60])
          verse    {:type :SEQ :id :verse :context (c/context) :children [n1]}
          root-ctx (cond-> {"Tempo" 6000 "volume" 80}
                     micro-value (assoc "micro" micro-value))
          root     {:type :ROOT :id :ROOT :context (c/context-root root-ctx) :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (let [eng   (engine/engine (fake-receiver onsets*) (repo/registry) :ROOT)
            start (System/nanoTime)]
        (binding [engine/*engine* eng]
          (engine/play :verse)
          ;; poll briefly for the note-on to land -- generous window,
          ;; this is real wall-clock timing, not a promise/signal
          (loop [waited 0]
            (when (and (empty? @onsets*) (< waited 2000))
              (Thread/sleep 10)
              (recur (+ waited 10))))
          (engine/stop! eng)
          (if (seq @onsets*)
            (/ (- (first @onsets*) start) 1e6)
            :never-fired))))))

(deftest micro-context-key-genuinely-delays-real-wall-clock-onset
  (let [without-ms (play-one-note-and-capture-first-onset-gap-ms nil)
        with-ms    (play-one-note-and-capture-first-onset-gap-ms 0.2)]
    (is (number? without-ms) "the plain note actually fired")
    (is (number? with-ms) "the micro-delayed note actually fired")
    (is (< without-ms 100.0) "with no :micro set, onset is essentially immediate")
    (is (> with-ms 150.0)
        (str "with micro=0.2s, onset should land roughly 200ms later -- got " with-ms "ms"))))

(deftest resolve-event-carries-micro-and-humanization-through-for-a-leaf
  (let [n1  (d/leaf :n1 (c/context) 1/4 [60])
        ctx (c/context-root {"micro" 0.3 "humanization" 0.5})
        ev  (r/resolve-event {:part n1 :ctx-chain [ctx]} 0 0.0 0)]
    (is (= 0.3 (:micro ev)))
    (is (= 0.5 (:humanization ev)))))

(deftest resolve-event-defaults-micro-and-humanization-to-0-when-unset
  (let [n1  (d/leaf :n1 (c/context) 1/4 [60])
        ctx (c/context-root {})
        ev  (r/resolve-event {:part n1 :ctx-chain [ctx]} 0 0.0 0)]
    (is (= 0.0 (:micro ev)))
    (is (= 0.0 (:humanization ev)))))

(deftest humanize-moves-onset-and-velocity-both-ways-within-bounds
  (let [{:keys [secs] max-vel :velocity} (:spread (data/quantity :humanization))
        e    {:micro 0.1 :humanization 1.0 :velocity 64}
        rng  (java.util.Random. 7)
        outs (repeatedly 500 #(r/humanize e (fn [] (.nextDouble rng))))
        offs (map first outs)
        vels (map second outs)]
    (is (every? #(<= (- 0.1 secs) % (+ 0.1 secs)) offs))
    (is (and (some #(< % 0.1) offs) (some #(> % 0.1) offs)) "early as well as late")
    (is (every? #(<= (- 64 max-vel) % (+ 64 max-vel)) vels))
    (is (and (some #(< % 64) vels) (some #(> % 64) vels)) "softer as well as louder")))

(deftest humanize-at-zero-leaves-the-note-alone
  (is (= [0.0 64] (r/humanize {:humanization 0.0 :velocity 64} #(throw (Exception. "no draw"))))))

(deftest humanize-keeps-velocity-in-midi-range
  (is (= [1 127] (map #(second (r/humanize {:humanization 1.0 :velocity %} (constantly %2)) )
                      [1 127] [0.0 0.9999]))))
