(ns ^:repl musics.mu.gamelan2-test
  "mus/gamelan2.mus: layers of the harmonic series, each a division of four bars, over a
   window of the series that climbs; voices that are functions of the onset."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
               (doseq [v [:l0 :l1 :l2 :l3 :l4 :l5 :l6 :l7]] (inputs/clear! v))
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [text] (swap! e/state assoc :stack []) (e/eval-input text))

(defn- at
  "text run as a voice runs it at onset on, the tctx's keywords answered; what it leaves, printed."
  [on text]
  (let [g (c/getter [live/tctx] on)
        r (e/with-lookup (fn [k] (if (= k :onset) on (g k))) #(run text))]
    (or (some-> (:ok r) (->> (mapv p/show))) (:error r))))

(defn- load! []
  (let [r (with-out-str (run (slurp (io/file "mus/gamelan2.mus"))))]
    (is (not (re-find #"can't read|error" r)) r)))

(deftest the-series
  (load!)
  (is (= ["'[36 48 55 60 64 67 70 72]"] (at 0 "36 8 harmonics")) "harmonics of C2")
  (is (= ["'[24 36 43 48 52 55 58 60]"] (at 0 "24 8 harmonics")) "of C1: 24 36 43 48 52 55 58 60")
  (let [s (first (at 0 ":series T@@"))]
    (is (re-find #"^'\[24 36 43 48 52 55 58 60 " s))
    (is (= (count (re-seq #"\d+" s)) (count (set (re-seq #"\d+" s)))) "each pitch once")))

(deftest the-window-climbs
  (load!)
  (is (= ["'[36 43 48]"] (at 0 ":window T@@")) "octave 2, three pitches")
  (is (= ["72"] (at 96 ":lo T@@")) "the lower edge reaches the top after the climb: 24 cycles of 4")
  (is (= 45 (read-string (first (at 168 ":lo T@@")))) "and falls back: at cycle 42 the height is 6 of 24")
  (is (= ["36"] (at 192 ":lo T@@")) "all the way down after 48 cycles")
  (is (= ["36"] (at 0 ":lo T@@")))
  (is (= [0 12 24 12 0 12] (mapv #(Integer/parseInt (first (at (* 4 %) ":height T@@"))) [0 12 24 36 48 60])) "a triangle over 2*:climb cycles")
  (is (= ["36"] (at 0 ":lo T@@")))
  (let [w (first (at 96 ":window T@@"))]
    (is (re-find #"^'\[72 " w) w))
  (run "2 :width T!")
  (is (= 2 (count (re-seq #"\d+" (first (at 0 ":window T@@"))))) "two at least")
  (run "1 :width T!")
  (is (= 2 (count (re-seq #"\d+" (first (at 0 ":window T@@"))))) "never fewer than two"))

(deftest the-layers-divide-four-bars
  (load!)
  (is (= [["2"] ["1"] ["1/2"] ["1/4"] ["1/8"] ["1/16"]] (mapv #(at 0 (str % " layer-length")) (range 6)))
      "[2 2 2 2 2 2]")
  (run "'[2 3 2 2] :divisions T!")
  (is (= [["2"] ["2/3"] ["1/3"] ["1/6"]] (mapv #(at 0 (str % " layer-length")) (range 4))) "[2 3 2 2]"))

(deftest a-layer-gives-a-note-a-rest-before-its-entry-and-keeps-its-grid
  (load!)
  (let [note (fn [on k] (first (at on (str on " " k " layer"))))]
    (is (re-find #"^\[C2/1\*2/1\\instrument:14" (note 0 0)) "layer 0 at the start: a note of two whole notes, the window's first pitch")
    (is (= "[r1]" (note 0 1)) "layer 1 enters two cycles (8 whole notes) later: a rest of its length first")
    (is (re-find #"^\[G2/1\\instrument:11" (note 8 1)) "and then a note, the window's second pitch (layer 1 starts one in)")
    (is (= "[r16]" (note 31 5)) "a layer is silent before its entry")
    (is (re-find #"^\[[A-G]#?[0-9]/16\\instrument:10" (note 40 5)) "layer 5, a sixteenth, after it")
    (testing "it breaks down again: the layers leave in the opposite order, and the build-up returns"
      (is (re-find #"^\[[A-G]" (note 100 5)) "layer 5 (entry at height 10) sounds at cycle 25, height 23")
      (is (= "[r16]" (note 160 5)) "but not on the way down at cycle 40: height 8")
      (is (re-find #"^\[[A-G]" (note 184 1)) "layer 1 (entry at height 2) sounds at cycle 46, height 2")
      (is (= "[r1]" (note 188 1)) "and has left at cycle 47: height 1")
      (is (= "[r1]" (note 192 1)) "the build-up begins again at cycle 48: nothing but layer 0")
      (is (re-find #"^\[[A-G]" (note 200 1)) "and layer 1 enters at cycle 50")
      (is (re-find #"^\[[A-G]" (note 188 0)) "layer 0 sounds throughout"))
    (testing "a change of the divisions keeps the grid: a note lasts to the next point of it"
      (run "'[2 2 2 2 2 2] :divisions T!")
      (is (= "[r8]" (note 5/8 3)) "layer 3, a quarter, not yet entered: 5/8 is 1/8 into the cell 1/2..3/4, so a rest of the 1/8 left")
      (run "0 :entry T!")
      (is (re-find #"^\[[A-G]#?[0-9]/8\\" (note 5/8 3)) "entered at once: a note of the 1/8 that is left of the cell"))))

(deftest it-starts-the-layers-there-are-divisions-for
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (load!)
    (binding [engine/*engine* eng]
      (with-redefs [engine/receiver (atom :silent)]
        (let [r (run "start")]
          (is (nil? (:error r)) (:error r))
          (is (every? some? (map engine/voice-at [:l0 :l1 :l2 :l3 :l4 :l5])) "six divisions, six layers")
          (is (nil? (engine/voice-at :l6)))
          (run "stop"))))
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (with-redefs [engine/receiver (atom :silent)]
          (run "'[2 3 2 2] :divisions T!")
          (let [r (run "start")]
            (is (nil? (:error r)) (:error r))
            (is (every? some? (map engine/voice-at [:l0 :l1 :l2 :l3])) "four divisions, four layers")
            (is (nil? (engine/voice-at :l4)))
            (run "stop")))))))
