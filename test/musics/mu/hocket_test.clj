(ns ^:repl musics.mu.hocket-test
  "mus/hocket.mus: the same piece as hocket2, but one word with a late branch; the values it
   calculates are worked out on the stack (dup, swap), its inputs are locals."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [musics.domain.context :as c]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.live :as live]
            [musics.mu.print :as p]
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

(defn- at
  "text run as a voice runs it, at onset on: the tctx's keywords answered."
  [on text]
  (let [g (c/getter [live/tctx] on)
        r (e/with-lookup (fn [k] (if (= k :onset) on (g k))) #(run text))]
    (or (some-> (:ok r) (->> (mapv p/show))) (:error r))))

;; the pitch, instrument and volume of a pulse are drawn at random between two numbers
(def ^:private note16 #"\[[A-G]#?[0-9]/16\\instrument:1[1-3]\\panning:-1\\volume:\d+\]")

(deftest the-leaves-and-the-pulse-it-is-on
  (let [r (with-out-str (run (slurp (io/file "mus/hocket.mus"))))]
    (is (not (re-find #"can't read|error" r)) r))
  (is (re-matches note16 (first (at 0 "0 -1 pulse"))) "pulse 0: a note of a sixteenth, with its parts")
  (is (= ["[r16]"] (at 0 "1 -1 pulse")) "pulse 1 of the first pass: a rest")
  (is (= ["[r8]"] (at 0 "0 -1 1 hoke")) "the late voice waits the lag: a rest of it")
  (is (re-matches note16 (first (at 1/8 "1/8 -1 1 hoke"))) "and then plays pulse 0")
  (is (= [2] (:ok (run "3/16 1/16 pulse-at"))) "pulse-at: the pulse the onset is on, late by the lag")
  (is (= ["^{ !Tempo:90 }"] (mapv p/show (:ok (run "90 tempo-ctx")))) "tempo-ctx: the tempo as a context"))

(deftest start-starts-a-and-b
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (with-out-str (run (slurp (io/file "mus/hocket.mus"))))
    (binding [engine/*engine* eng]
      (with-redefs [engine/receiver (atom :silent)]
        (let [r (run "start")]
          (is (nil? (:error r)) (:error r))
          (is (some? (engine/voice-at :A)))
          (is (some? (engine/voice-at :B)))
          (is (empty? (:ok r)) "and leaves nothing on the stack")
          (run ":A stop :B stop"))))))
