(ns async-engine-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.engine.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.elements.music-elements :as el]))

(deftest section-boundary-signals-fire-during-playback
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng     (engine/engine nil repo/play-tx :ROOT)
          entered (promise)
          exited  (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-enter (fn [_] (deliver entered true)))
      (conductor/register-action! :mark-exit (fn [_] (deliver exited true)))
      (conductor/schedule! :verse :enter :mark-enter)
      (conductor/schedule! :verse :exit :mark-exit)
      (engine/play :verse)
      (is (= true (deref entered 2000 :timeout))
          "play-node signaled :verse's :enter before playing its child")
      (is (= true (deref exited 2000 :timeout))
          "play-node signaled :verse's :exit once its single leaf finished"))))

(deftest bar-boundary-signal-fires-during-playback
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [meter (el/make-meter 4 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        n3    (d/leaf :n3 (c/context) 1/4 [64])
        n4    (d/leaf :n4 (c/context) 1/4 [65])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3 n4]}
        root  {:type :ROOT :id :ROOT
               ;; tempo cranked way up so the whole bar plays in a few ms
               :context (c/context-root {"Tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          bar2 (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
      (conductor/schedule! 2 :enter :mark-bar2)
      (engine/play :verse)
      (is (= 2 (:id (deref bar2 2000 :timeout)))
          "advance-bar! signaled entering bar 2 once the four quarter notes filled bar 1 (4/4)"))))

(deftest bar-boundary-respects-a-non-default-meter
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [meter (el/make-meter 3 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        n3    (d/leaf :n3 (c/context) 1/4 [64])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          bar2 (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
      (conductor/schedule! 2 :enter :mark-bar2)
      (engine/play :verse)
      (is (= 2 (:id (deref bar2 2000 :timeout)))
          "three quarter notes exactly fill one 3/4 bar, so bar 2 starts right after"))))

(deftest mark-signal-fires-for-a-barline
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        n2    (d/leaf :n2 (c/context) 1/16 [62])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [n1 (d/bar 1) n2]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng    (engine/engine nil repo/play-tx :ROOT)
          marked (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark1 (fn [event] (deliver marked event)))
      (conductor/schedule! [:mark 1 1] :enter :mark1)
      (engine/play :verse)
      (let [event (deref marked 2000 :timeout)]
        (is (= [:mark 1 1] (:id event)))
        (is (= 1 (:count event)))
        (is (= :mark (:kind event)))))))

(deftest mark-signal-does-not-advance-bar-position
  ;; A BarLine has zero duration -- it must never itself trigger a :bar
  ;; crossing, only the notes around it can.
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [meter (el/make-meter 4 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [(d/bar 1) (d/bar 1) (d/bar 1) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng      (engine/engine nil repo/play-tx :ROOT)
          finished (promise)
          bar2?    (atom false)]
      (engine/set-engine! eng)
      (conductor/register-action! :done (fn [_] (deliver finished true)))
      (conductor/register-action! :mark-bar2 (fn [_] (reset! bar2? true)))
      (conductor/schedule! :verse :exit :done)
      (conductor/schedule! 2 :enter :mark-bar2)
      (engine/play :verse)
      (deref finished 2000 :timeout)
      (is (false? @bar2?)
          "three bare BarLines plus one quarter note never fill a 4/4 bar"))))

(deftest mark-signal-counts-per-strength-independently
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [(d/bar 1) (d/bar 2) (d/bar 1) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng           (engine/engine nil repo/play-tx :ROOT)
          second-single (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :second-single (fn [event] (deliver second-single event)))
      (conductor/schedule! [:mark 1 2] :enter :second-single)
      (engine/play :verse)
      (is (= [:mark 1 2] (:id (deref second-single 2000 :timeout)))
          "the double bar-line in between doesn't consume a slot in the single-bar-line count"))))

;; ============================================================
;; display -- greedy, synchronous realization (no core.async, no engine)
;; ============================================================

(deftest display-resolves-a-simple-sequence
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [steps (engine/display repo/play-tx :verse)]
      (is (= 2 (count steps)))
      (is (= [[60] [62]] (mapv :pitches steps)))
      (is (= 0.0 (:onset (first steps))))
      (is (= (:dur-secs (first steps)) (:onset (second steps)))
          "second note's onset is right after the first's full duration"))))

(deftest display-needs-no-connect-or-live-engine
  ;; confirms display works directly against a plain repo/atom, with no
  ;; (connect)/(set-engine! ...) call at all -- it's pure data, no MIDI.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (is (= [60] (:pitches (first (engine/display repo/play-tx :verse)))))))

(deftest display-forks-a-par-into-a-voices-marker
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        n2     (d/leaf :n2 (c/context) 1/4 [67])
        melody {:type :SEQ :id :melody :context (c/context) :children [n1]}
        bass   {:type :SEQ :id :bass :context (c/context) :children [n2]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:melody :bass]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :melody melody)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [steps    (engine/display repo/play-tx [:par :melody :bass])
          par-step (first steps)]
      (is (= 1 (count steps)))
      (is (= :par (:kind par-step)))
      (is (= 2 (count (:voices par-step))))
      (is (= [60] (:pitches (first (first (:voices par-step))))))
      (is (= [67] (:pitches (first (second (:voices par-step)))))))))

(deftest display-includes-mark-steps-for-barlines
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [(d/bar 2) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [steps (engine/display repo/play-tx :verse)]
      (is (= {:kind :mark :count 2} (first steps)))
      (is (= [60] (:pitches (second steps)))))))

(deftest display-expands-a-finite-iterator
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        source {:type :SEQ :id :s1 :context (c/context) :children [n1]}
        iter   (d/iterator :REPEAT :r1 (c/context) source {:count 3})
        verse  {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [steps (engine/display repo/play-tx :verse)]
      (is (= 3 (count steps)))
      (is (apply < (map :onset steps))
          "each pass starts strictly after the previous one finished"))))

(deftest display-throws-on-infinite-iterator
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        source {:type :SEQ :id :s1 :context (c/context) :children [n1]}
        iter   (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
        verse  {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (is (thrown? clojure.lang.ExceptionInfo (engine/display repo/play-tx :verse)))))

(deftest display-reproduces-par-not-advancing-parent-clock
  ;; Documented, deliberate: a :SEQ sibling right after a :PAR starts at
  ;; the SAME onset the :PAR's own children did, matching play-par's
  ;; actual current behavior (it never advances the parent voice's own
  ;; clock/structural-time past what its forked children took).
  (repo/reset-all!)
  (let [a     (d/leaf :a (c/context) 1/4 [60])
        x     (d/leaf :x (c/context) 1/4 [64])
        y     (d/leaf :y (c/context) 1/4 [67])
        b     (d/leaf :b (c/context) 1/4 [72])
        par   {:type :PAR :id :xy :context (c/context) :children [x y]}
        verse {:type :SEQ :id :verse :context (c/context) :children [a :xy b]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :xy par)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [[a-step par-step b-step] (engine/display repo/play-tx :verse)
          x-onset (:onset (first (first (:voices par-step))))]
      (is (= [60] (:pitches a-step)))
      (is (= :par (:kind par-step)))
      (is (= [72] (:pitches b-step)))
      (is (= x-onset (:onset b-step))
          "b starts at the same onset x/y did, not after them"))))
