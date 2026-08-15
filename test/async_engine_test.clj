(ns ^:engine async-engine-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.music-elements :as el]))

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
;; schedule-tx! -- the primary use case, now per-voice (moved here from
;; core.conductor since it needs to know what a voice is)
;; ============================================================

(deftest schedule-tx-redirects-only-the-signaling-voice
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (repo/commit-node! :ROOT {:type :ROOT})
  (let [tx1     (repo/latest-tx)
        _       (repo/commit-node! :verse {:type :SEQ})
        tx2     (repo/latest-tx)
        voice-a {:tx (atom tx1)}
        voice-b {:tx (atom tx1)}]
    (engine/schedule-tx! :verse :exit tx2)
    (is (= tx1 @(:tx voice-a)) "scheduling alone doesn't move anything yet")
    (conductor/signal! {:id :verse :phase :exit :voice voice-a})
    (is (= tx2 @(:tx voice-a)) "the signaling voice's own tx moved")
    (is (= tx1 @(:tx voice-b))
        "a DIFFERENT voice's tx is untouched -- the whole point of making tx per-voice")))

(deftest schedule-tx-latest-resolves-at-fire-time-not-schedule-time
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (repo/commit-node! :ROOT {:type :ROOT})
  (let [voice {:tx (atom (repo/latest-tx))}]
    (engine/schedule-tx! :verse :exit :latest)
    (repo/commit-node! :verse {:type :SEQ})     ;; committed AFTER scheduling
    (let [latest (repo/latest-tx)]
      (conductor/signal! {:id :verse :phase :exit :voice voice})
      (is (= latest @(:tx voice))
          "resolved :latest against the tx current when it fired, not when scheduled"))))

(deftest schedule-tx-through-real-playback-only-moves-its-own-voice
  ;; End-to-end version of the two unit tests above: melody and bass
  ;; forked at :PAR are genuinely different voices (see fork-voice) --
  ;; scheduling a cutover on melody's own :exit must not touch bass's.
  (repo/reset-all!)
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (let [n1     (d/leaf :n1 (c/context) 1/16 [60])
        n2     (d/leaf :n2 (c/context) 1/16 [67])
        melody {:type :SEQ :id :melody :context (c/context) :children [n1]}
        bass   {:type :SEQ :id :bass :context (c/context) :children [n2]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 240 "volume" 80})
                :children [:melody :bass]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :melody melody)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [tx1              (repo/latest-tx)
          _                (repo/commit-node! :extra {:type :SEQ}) ;; unrelated commit
          tx2              (repo/latest-tx)
          eng              (engine/engine nil repo/play-tx :ROOT)
          action-id        (engine/schedule-tx! :melody :exit tx2)
          cut-over-fn      (get @conductor/action-registry action-id)
          melody-voice-box (promise)
          bass-voice-box   (promise)]
      (engine/set-engine! eng)
      ;; wrap the real cutover to also capture which voice it touched --
      ;; same technique pipeline-test uses, for the same reason (a real
      ;; ordering guarantee instead of a racy proxy)
      (conductor/register-action! action-id
                                   (fn [event]
                                     (cut-over-fn event)
                                     (deliver melody-voice-box (:voice event))))
      (conductor/register-action! :bass-seen (fn [event] (deliver bass-voice-box (:voice event))))
      (conductor/schedule! :bass :exit :bass-seen)
      (engine/play [:par :melody :bass])
      (let [melody-voice (deref melody-voice-box 2000 :timeout)
            bass-voice   (deref bass-voice-box 2000 :timeout)]
        (is (= tx2 @(:tx melody-voice)) "melody's own voice moved to the new tx")
        (is (= tx1 @(:tx bass-voice))
            "bass's own voice, a DIFFERENT voice, was never touched")))))

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

(deftest ramp-in-a-later-top-level-container-is-not-broken-by-earlier-material
  ;; Regression coverage: a container's own envelope is built at parse
  ;; time with LOCAL, zero-based time (flat-tree-walker's (duration
  ;; state)) -- correct in isolation, but wrong if queried with
  ;; structural-time that's already advanced past that local range,
  ;; which is exactly what happens once this ISN'T the first thing
  ;; played in its voice. build-chain used to prepend a container's own
  ;; context onto the ctx-chain unrebased -- fine for whatever plays
  ;; first, broken for anything after it. Two SEPARATE top-level
  ;; containers played together (same shape play-file!'s own play args
  ;; use -- (play :a :b), documented directly on play's own docstring)
  ;; reproduce it: block1 (two quarter notes) plays first, so by the
  ;; time block2's own leaves resolve, structural-time has already
  ;; passed block2's own locally-authored 0..1 ramp range entirely,
  ;; without core.domain.context/ctx-shift rebasing it first.
  (repo/reset-all!)
  (let [b1n1     (d/leaf :b1n1 (c/context) 1/4 [60])
        b1n2     (d/leaf :b1n2 (c/context) 1/4 [62])
        block1   {:type :SEQ :id :block1 :context (c/context) :children [b1n1 b1n2]}
        ramp-ctx (c/context)
        _        (c/ctx-append ramp-ctx :volume 0 30 :lin-up)
        _        (c/ctx-append ramp-ctx :volume 1 80 :fixed)
        b2n1     (d/leaf :b2n1 ramp-ctx 1/4 [64])
        b2n2     (d/leaf :b2n2 ramp-ctx 1/4 [65])
        b2n3     (d/leaf :b2n3 ramp-ctx 1/4 [67])
        b2n4     (d/leaf :b2n4 ramp-ctx 1/4 [69])
        block2   {:type :SEQ :id :block2 :context ramp-ctx :children [b2n1 b2n2 b2n3 b2n4]}
        root     {:type :ROOT :id :ROOT
                  :context (c/context-root {"Tempo" 120 "volume" 50})
                  :children [:block1 :block2]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :block1 block1)
    (repo/commit-node! :block2 block2)
    (repo/play-latest!)
    (let [steps (engine/display repo/play-tx :block1 :block2)]
      (is (= [50 50 30 43 55 68] (mapv :velocity steps))
          "block1's own two notes at root's default volume, then block2's
           ramp interpolating from its own local start (30) toward 80 --
           not [55 68 80 80], which is what block2's own local envelope
           would read back if queried at block1's-duration-plus-its-own
           local time instead of being rebased to start fresh at 0"))))

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

(deftest display-plays-an-already-resolved-leaf-directly
  ;; play-form/realize-form's d/part? branch -- a raw Leaf handed straight
  ;; to display/play (as ordinary seq functions like cycle/take/map would
  ;; produce from `sq`), not looked up by keyword.
  (repo/reset-all!)
  (let [n1   (d/leaf :n1 (c/context) 1/4 [60])
        root {:type :ROOT :id :ROOT
              :context (c/context-root {"Tempo" 120 "volume" 80})
              :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (is (= [60] (:pitches (first (engine/display repo/play-tx n1)))))))

(deftest display-accepts-a-plain-list-the-same-as-a-vector-group
  ;; sequential? (not vector?-only) -- a LazySeq/list group (as cycle/take
  ;; would produce) plays identically to the equivalent tagged vector.
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
    (is (= (engine/display repo/play-tx [:par :melody :bass])
           (engine/display repo/play-tx (list :par :melody :bass)))
        "a list group resolves identically to the same vector group")))

(deftest display-plays-a-cycled-take-of-resolved-children
  ;; The motivating end-to-end shape: (play (take n (cycle (sq id)))) --
  ;; here using a plain resolved-children vector directly (as `sq` itself
  ;; is just `children` plus metadata), fed through ordinary cycle/take.
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        n2     (d/leaf :n2 (c/context) 1/4 [62])
        n3     (d/leaf :n3 (c/context) 1/4 [64])
        verse  {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [children (d/children (repo/view (repo/latest-tx)) verse)
          steps    (engine/display repo/play-tx (take 5 (cycle children)))]
      (is (= [[60] [62] [64] [60] [62]] (mapv :pitches steps))))))

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

;; ============================================================
;; play -- a clean error for an id that doesn't resolve, not an NPE
;; ============================================================

(deftest play-throws-a-clear-error-for-an-unresolvable-id
  (repo/reset-all!)
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No part found for id :bogus"
            (engine/play :bogus)))
      (is (= 0 @(:generation eng))
          "a rejected play call never bumps :generation -- validate-ids!
           runs before generation/voice creation, so a typo'd id can't
           supersede whatever is already playing"))))

(deftest play-throws-when-id-committed-after-the-tx-play-points-at
  ;; The exact scenario found live in a real mu! session: commit! never
  ;; moves play-tx on its own (see core.repo's docstring) -- playing an
  ;; id committed after whatever tx play-tx currently points at used to
  ;; NPE deep inside core.repo/as-of (val on a nil rsubseq entry); now
  ;; it's a clean, actionable ex-info instead.
  (repo/reset-all!)
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      ;; :verse committed after play-tx was last pointed anywhere --
      ;; play-tx still points at the tx before :verse existed.
      (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
            verse {:type :SEQ :id :verse :context (c/context) :children [n1]}]
        (repo/commit-node! :verse verse))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No part found for id :verse"
            (engine/play :verse))))))
