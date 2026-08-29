(ns ^:engine async-engine-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.music-elements :as el]))

(deftest section-boundary-signals-fire-during-playback
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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
          cut-over-fn      (get @reg/*conductor-action-registry* action-id)
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
      (engine/play #{:melody :bass})
      (let [melody-voice (deref melody-voice-box 2000 :timeout)
            bass-voice   (deref bass-voice-box 2000 :timeout)]
        (is (= tx2 @(:tx melody-voice)) "melody's own voice moved to the new tx")
        (is (= tx1 @(:tx bass-voice))
            "bass's own voice, a DIFFERENT voice, was never touched")))))

(deftest schedule-tx-redirects-every-voice-crossing-the-same-bar
  ;; Regression test: unlike :section (id keyed by container id, normally
  ;; distinct per part) or :melody/:bass above, a :bar id is a bare
  ;; integer shared by EVERY voice in the piece -- two :PAR siblings that
  ;; both happen to cross the same bar number signal the exact same
  ;; [2 :enter] pair (a fresh voice starts already "in" bar 1, so its
  ;; first crossing signals bar 2 -- see bar-boundary-signal-fires-
  ;; during-playback above). Before schedule-tx! re-armed itself,
  ;; core.conductor's plain one-shot schedule entry was consumed by
  ;; whichever voice got there first; the other voice found nothing
  ;; scheduled anymore and kept playing on its old :tx, un-redirected,
  ;; with no error at all -- a real race, not a hypothetical one, for
  ;; any piece with more than one simultaneous part.
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  ;; No Meter set -> bar-length falls back to 1 whole note (see
  ;; core.async-engine/bar-length) -- each voice's own single whole-note
  ;; leaf exactly fills its first bar, so both cross into bar 2 on their
  ;; very first (and only) note.
  (let [n1     (d/leaf :n1 (c/context) 1 [60])
        n2     (d/leaf :n2 (c/context) 1 [67])
        melody {:type :SEQ :id :melody :context (c/context) :children [n1]}
        bass   {:type :SEQ :id :bass :context (c/context) :children [n2]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 6000 "volume" 80})
                :children [:melody :bass]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :melody melody)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [tx1         (repo/latest-tx)
          _           (repo/commit-node! :extra {:type :SEQ}) ;; unrelated commit
          tx2         (repo/latest-tx)
          eng         (engine/engine nil repo/play-tx :ROOT)
          action-id   (engine/schedule-tx! 2 :enter tx2)
          cut-over-fn (get @reg/*conductor-action-registry* action-id)
          seen        (atom [])
          both-seen   (promise)]
      (engine/set-engine! eng)
      ;; wrap the real cutover to also record which voices it touched --
      ;; same technique the test above uses, for the same reason (a real
      ;; ordering guarantee instead of a racy proxy on eng's own state).
      (conductor/register-action!
        action-id
        (fn [event]
          (cut-over-fn event)
          (let [voices (swap! seen conj (:voice event))]
            (when (= 2 (count voices)) (deliver both-seen true)))))
      (engine/play #{:melody :bass})
      (is (= true (deref both-seen 2000 :timeout))
          "both voices signaled crossing bar 2, not just whichever got there first")
      (is (= 2 (count (distinct (map :path @seen))))
          "the two signals came from two genuinely different voices")
      (doseq [voice @seen]
        (is (= tx2 @(:tx voice))
            "every voice that crossed bar 2 was redirected, not just the first")))))

;; ============================================================
;; MIDI channel pool -- exhaustion behavior (16+ simultaneous distinct
;; timbres, only 15 non-percussion channels available). Exercised
;; directly against the private helpers (#'engine/...), same technique
;; form-tag+items uses above, since driving a real 16-voice playback
;; session just to hit this deterministically would be slow and timing-
;; dependent for no extra coverage.
;; ============================================================

(deftest claim-channel-returns-nil-rather-than-stealing-when-the-pool-is-full
  ;; Regression test: claim-channel! used to fall back to forcing channel
  ;; 0 when the pool was exhausted, silently stealing whatever
  ;; still-active voice already held it (and never even recording the
  ;; theft in claims-atom -- see that fn's own docstring). It must
  ;; instead report "no channel available" and leave every existing
  ;; claim completely untouched.
  (let [claims (atom {})
        keys   (map (fn [i] [i {}]) (range 15))     ;; 15 distinct chan-keys
        claimed (mapv (fn [k] (#'engine/claim-channel! claims k)) keys)]
    (is (every? (fn [[ch fresh?]] (and (some? ch) (true? fresh?))) claimed)
        "all 15 non-percussion channels (0-15 excluding 9) are claimable")
    (is (= 15 (count (distinct (map first claimed))))
        "each of the 15 claims landed on a genuinely distinct channel")
    (let [claims-before @claims
          sixteenth     (#'engine/claim-channel! claims [:a-16th-distinct-timbre {}])]
      (is (nil? sixteenth) "the 16th distinct chan-key gets no channel at all")
      (is (= claims-before @claims)
          "a failed claim must not mutate claims-atom -- no channel silently stolen"))))

(deftest resolve-voice-channel-goes-silent-not-corrupting-when-pool-exhausted
  (let [claims (atom {})
        _      (dotimes [i 15] (#'engine/claim-channel! claims [i {}]))
        claims-before @claims
        voice  {:eng {:channel-claims claims} :channel (atom nil) :chan-key (atom nil)}
        [channel fresh?] (#'engine/resolve-voice-channel! voice :a-16th-distinct-timbre {})]
    (is (nil? channel) "no MIDI channel assigned -- send-midi-on!/off! already treat nil as silent")
    (is (false? fresh?))
    (is (= claims-before @claims)
        "an exhausted voice's own claim attempt must not disturb the 15 real claims")
    (is (nil? @(:channel voice)))
    (is (nil? @(:chan-key voice))
        "chan-key reset to nil (not left holding the wanted-but-unclaimed key) so the next note retries claiming instead of assuming it already matches")))

(deftest resolve-voice-channel-self-heals-once-a-channel-frees-up
  (let [claims (atom {})
        used-keys (mapv (fn [i] [i {}]) (range 15))
        _      (doseq [k used-keys] (#'engine/claim-channel! claims k))
        voice  {:eng {:channel-claims claims} :channel (atom nil) :chan-key (atom nil)}
        exhausted (#'engine/resolve-voice-channel! voice :still-locked-out {})]
    (is (nil? (first exhausted)) "pool is genuinely full, first attempt goes silent")
    ;; free up one of the 15 real claims (as if that voice finished/moved on)
    (#'engine/release-channel! claims (ffirst used-keys))
    (let [[channel fresh?] (#'engine/resolve-voice-channel! voice :still-locked-out {})]
      (is (some? channel)
          "the exhausted voice's very next note retries claiming and succeeds now that a channel is free")
      (is (true? fresh?))
      (is (= channel @(:channel voice)))
      (is (= [:still-locked-out {}] @(:chan-key voice))))))

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
      (is (= [64 64 38 54 70 86] (mapv :velocity steps))
          "block1's own two notes at root's default volume, then block2's
           ramp interpolating from its own local start (30) toward 80 --
           velocities rescaled via common.defaults/volume->midi from
           those raw 0-100-scale volumes --
           not [55 68 80 80], which is what block2's own local envelope
           would read back if queried at block1's-duration-plus-its-own
           local time instead of being rebased to start fresh at 0"))))

(deftest display-forks-a-par-into-a-voices-marker
  ;; melody/bass carry real, hand-baked :pitch-sum/:pitch-n (what
  ;; flat-core-builder/pop-container would bake at real parse time) --
  ;; #{} has no order of its own for realize-form-par to just preserve
  ;; the way a literal, ordered [:par ...] vector used to, so a real
  ;; mean-pitch-rank input is required here for the low-to-high voice
  ;; order this test asserts to be deterministic at all.
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        n2     (d/leaf :n2 (c/context) 1/4 [67])
        melody {:type :SEQ :id :melody :context (c/context) :children [n1]
                :pitch-sum 60 :pitch-n 1}
        bass   {:type :SEQ :id :bass :context (c/context) :children [n2]
                :pitch-sum 67 :pitch-n 1}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:melody :bass]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :melody melody)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [steps    (engine/display repo/play-tx #{:melody :bass})
          par-step (first steps)]
      (is (= 1 (count steps)))
      (is (= :par (:kind par-step)))
      (is (= 2 (count (:voices par-step))))
      (is (= [60] (:pitches (first (first (:voices par-step))))))
      (is (= [67] (:pitches (first (second (:voices par-step)))))))))

(deftest display-honors-parallel-metadata-on-a-bare-seq
  ;; sq (musics.clj) tags its own children-of-a-:PAR result {:parallel?
  ;; true} via metadata, since turning a container into a plain seq
  ;; (mapv'd children) leaves no data-level place left to carry a
  ;; :par/:seq tag the way a literal #{...} group has one. display/play
  ;; have to consult that metadata (form-tag+items) FIRST, ahead of the
  ;; vector-vs-set default -- otherwise a genuinely parallel container
  ;; silently plays back sequentially the moment it's passed through sq
  ;; (sq's own output is always a plain vector, never a set). Confirmed
  ;; live before this test existed: (engine/display tx (m/sq :chorale))
  ;; used to come back [:seq ...], not [:par ...] -- and this must keep
  ;; working exactly the same after the []=seq/#{}=par redesign, since
  ;; sq's own metadata answer is untouched by it.
  (repo/reset-all!)
  (let [n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [67])
        sop     {:type :SEQ :id :sop :context (c/context) :children [n1]}
        bass    {:type :SEQ :id :bass :context (c/context) :children [n2]}
        chorale {:type :PAR :id :chorale :context (c/context) :children [:sop :bass]}
        root    {:type :ROOT :id :ROOT
                 :context (c/context-root {"Tempo" 120 "volume" 80})
                 :children [:chorale]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :chorale chorale)
    (repo/commit-node! :sop sop)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [children (d/children (repo/view (repo/latest-tx)) chorale)
          tagged   (with-meta children {:parallel? true})
          steps    (engine/display repo/play-tx tagged)]
      (is (= 1 (count steps)))
      (is (= :par (:kind (first steps)))
          "chorale's own :PAR-ness must survive being carried only as
           sq-style seq metadata, with no literal :par/:seq tag in the
           data itself")
      (is (= 2 (count (:voices (first steps))))))))

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
  ;; would produce) plays identically to the equivalent vector -- both
  ;; default to :seq with no metadata now, whether vector or list, so
  ;; there's no longer a vector-vs-list DEFAULT distinction the way there
  ;; used to be (a literal [:par ...] vector doesn't exist anymore --
  ;; :par is spelled #{...} now, a shape a plain list can't produce).
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
    (let [via-vector (engine/display repo/play-tx [:melody :bass])
          via-list   (engine/display repo/play-tx (list :melody :bass))]
      (is (= via-vector via-list)
          "a list group resolves identically to the same vector group")
      (is (= [[60] [67]] (mapv :pitches via-vector))
          "both play :melody then :bass IN ORDER, sequentially -- not forked"))))

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
      (swap! (:voices eng) assoc [:already-playing] {:birth-token :sentinel})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No part found for id :bogus"
            (engine/play :bogus)))
      (is (= {:birth-token :sentinel} (get @(:voices eng) [:already-playing]))
          "a rejected play call never wipes eng's :voices registry --
           validate-ids! runs before play's own pre-fn (the '(reset!
           (:voices eng) {})' that implements 'flush everything'), so a
           typo'd id can't supersede whatever is already playing"))))

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

(deftest play-throws-a-clear-error-for-a-nonsense-form
  ;; validate-ids! -- not play-form's own analogous :else branch, which
  ;; runs inside a go block and can't usefully throw (see its own
  ;; comment: a throw there is swallowed by core.async, confirmed live
  ;; -- (<!! ch) on a go block that throws just returns nil) -- is what
  ;; has to reject nil specifically. nil is the concrete, real-world
  ;; case: sq (musics.clj) returns nil for an id that doesn't resolve
  ;; to a container at all (a typo, or an id that's a leaf rather than
  ;; a composite). Used to silently no-op -- no sound, no error --
  ;; confirmed live before this test existed. Deliberately narrower
  ;; than "reject anything non-keyword/non-sequential" (an earlier,
  ;; broader version of this guard did that, and broke real material
  ;; containing an inline :assignment node -- see
  ;; display-tolerates-an-inline-assignment-node-in-bare-material).
  (repo/reset-all!)
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"don't know how to play"
            (engine/play nil))))))

(deftest play-tolerates-an-inline-assignment-node-in-bare-material
  ;; The exact scenario reported live: (play (times N (sq :verse))) on
  ;; material containing an inline !instrument:/!tempo:/!mf-style
  ;; :assignment node used to throw at validate-ids! (an earlier,
  ;; too-broad version of the nonsense-form guard), even though
  ;; (play :verse) directly never did. validate-ids! must let this
  ;; through, same as it always let a real id's own children through.
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        assign {:type :assignment :key :i :val 32 :raw "!i:32"}
        root   {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (is (keyword? (engine/play (with-meta [assign n1] {:parallel? false})))
          "no throw -- returns a fresh track id, same as play always does
           on success; the assignment node is silently tolerated, same as
           play-node already tolerates one during an ordinary container walk"))))

(deftest display-throws-a-clear-error-for-a-nonsense-form
  ;; display has no validate-ids! pass of its own (fully synchronous,
  ;; no go block involved at all) -- realize-form's own :else has to
  ;; carry this instead, and can, since nothing here runs async.
  (repo/reset-all!)
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"don't know how to play"
          (engine/display repo/play-tx nil)))))

(deftest display-tolerates-an-inline-assignment-node-in-bare-material
  ;; Real regression, caught live: sq (musics.clj) hands back a
  ;; container's :children verbatim, which includes inline :assignment
  ;; nodes (the walker's own record of a written !tempo:/!mf/etc.
  ;; instruction -- its real effect already landed on its siblings'
  ;; shared context back at parse/walk time, same as play-node itself
  ;; already tolerates one during an ordinary container walk, silently
  ;; no-op'ing via its own :else). An earlier, too-broad version of the
  ;; nonsense-form guard rejected ANY non-keyword/non-sequential/non-
  ;; part shape, not just nil -- so (play (times N (sq :verse))) on
  ;; material containing an inline instruction node threw, even though
  ;; (play :verse) directly (no sq involved) never did. Only nil (sq
  ;; failing to resolve an id at all) should be rejected; a real,
  ;; recognized-but-inert node shape must pass through untouched.
  (repo/reset-all!)
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        assign {:type :assignment :key :i :val 32 :raw "!i:32"}
        verse  {:type :SEQ :id :verse :context (c/context) :children [assign n1]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [material (with-meta [assign n1] {:parallel? false :id :verse})
          steps    (engine/display repo/play-tx material)]
      (is (= 1 (count steps)) "the assignment node contributes no step of its own")
      (is (= [60] (:pitches (first steps)))))))

;; ============================================================
;; assign-algo!/algo-assignments/play/play-add
;; ============================================================

(deftest assign-algo-and-algo-assignments-round-trip
  (repo/reset-all!)
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (wall/register-wall! ::retro (fn [nodes _ctx _voice] nodes))
      (engine/assign-algo! eng :bass ::retro)
      (is (= {[:bass] ::retro} (engine/algo-assignments eng))
          "a bare keyword path reads back wrapped the same way voice-at/->path treat it")
      (engine/assign-algo! eng :bass nil)
      (is (= {[:bass] nil} (engine/algo-assignments eng))
          "nil clears an assignment back to identity, not to 'unassigned'"))))

(deftest play-mints-a-short-track-id-and-assigns-the-algorithm
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (wall/register-wall! ::retro2 (fn [nodes _ctx _voice] nodes))
      (let [id (engine/play :verse :algo ::retro2)]
        (is (= :TAA id) "the first minted track id, deterministically")
        (is (some? (engine/voice-at eng id))
            "the voice is registered synchronously, before play returns")
        (is (= {[:TAA] ::retro2} (engine/algo-assignments eng))
            "the algorithm is assigned before the voice's first node runs")))))

(deftest play-with-no-algo-marker-defaults-to-identity-not-whatever-was-there
  ;; The important correctness case: play always mints the SAME id
  ;; (:TAA) right after its own flush, so a call with no trailing :algo
  ;; has to actively clear that path back to identity -- if it simply
  ;; skipped the assign-algo! call when no tag was found, a PRIOR play
  ;; call's own algorithm would silently keep applying to every later,
  ;; unrelated play call that happens to reuse :TAA.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (wall/register-wall! ::retro2c (fn [nodes _ctx _voice] nodes))
      (engine/play :verse :algo ::retro2c)
      (is (= {[:TAA] ::retro2c} (engine/algo-assignments eng)))
      (engine/play :verse)
      (is (= {[:TAA] nil} (engine/algo-assignments eng))
          "no :algo on this call -- explicitly cleared to identity, not left as ::retro2c"))))

(deftest play-untagged-single-item-vector-is-an-ordinary-one-item-seq-group
  ;; A plain 1-element vector is unambiguously an ordinary [] sequential
  ;; group now -- vector is ALWAYS :seq, never subject to any
  ;; :algo-marker guessing -- unlike the old scheme, where a bare
  ;; single-item vector had to be deliberately distinguished from an
  ;; [:algo name] marker. tagged-form? requires exactly 3 elements with
  ;; :algo at index 1, so a 1-element vector was never even a candidate.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (engine/play [:verse])
      (is (= {[:TAA] nil} (engine/algo-assignments eng))
          "[:verse] is ordinary play material -- :TAA stays identity"))))

(deftest play-flushes-everything-first
  ;; A voice already registered anywhere (even at a path play never
  ;; touches directly) is gone after play runs; and since the flush
  ;; always runs first, a solo call deterministically lands on :TAA --
  ;; there is never anything left over from a PRIOR play call to skip.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (swap! (:voices eng) assoc [:some-other-path] {:birth-token :sentinel})
      (let [id (engine/play :verse)]
        (is (= :TAA id) "the flush ran before minting, so :TAA was free")
        (is (nil? (get @(:voices eng) [:some-other-path]))
            "whatever was already registered got wiped, same as play's own flush")))))

;; ============================================================
;; play-add -- play's own never-flushes alternative
;; ============================================================

(deftest play-add-mints-a-short-track-id-and-assigns-the-algorithm
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (wall/register-wall! ::retro3 (fn [nodes _ctx _voice] nodes))
      (let [id (engine/play-add :verse :algo ::retro3)]
        (is (= :TAA id) "the first minted track id, deterministically")
        (is (some? (engine/voice-at eng id))
            "the voice is registered synchronously, before play-add returns")
        (is (= {[:TAA] ::retro3} (engine/algo-assignments eng))
            "the algorithm is assigned before the voice's first node runs")))))

(deftest play-add-does-not-flush-other-voices
  ;; The opposite of play's own flush test: whatever's already
  ;; registered survives a play-add call untouched, and a SECOND
  ;; play-add call (unlike a second play call) does NOT reuse :TAA,
  ;; since the first one is still occupying it.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (swap! (:voices eng) assoc [:some-other-path] {:birth-token :sentinel})
      (let [id1 (engine/play-add :verse)
            id2 (engine/play-add :verse)]
        (is (= :TAA id1))
        (is (= :TAB id2) "TAA is still occupied by the first voice, so a fresh id is minted")
        (is (some? (get @(:voices eng) [:some-other-path]))
            "the unrelated voice was never touched")))))

;; ============================================================
;; :PAR children get mean-pitch-ranked track-id path segments
;; ============================================================

(deftest par-children-get-mean-pitch-ranked-track-ids
  ;; :verse lists :high BEFORE :low -- proving the ranking is by pitch,
  ;; not by written/positional order (the old child-segment behavior
  ;; this replaces would have put :high at index 0, :low at index 1).
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  (let [hi    (d/leaf :hi (c/context) 1/4 [80])
        lo    (d/leaf :lo (c/context) 1/4 [40])
        ;; Hand-built fixtures bypass the real parser, so :pitch-sum/
        ;; :pitch-n (normally baked at pop-container time, see
        ;; flat-core-builder) have to be baked here too, the same way,
        ;; via the real d/pitch-stats/set-container-pitch-stats -- a
        ;; container with neither key defaults to "no pitched content"
        ;; (part-pitch-stats' own (get part :pitch-sum 0)), which is
        ;; NOT what either fixture actually means.
        high0 {:type :SEQ :id :high :context (c/context) :children [hi]}
        low0  {:type :SEQ :id :low :context (c/context) :children [lo]}
        high  (d/set-container-pitch-stats high0 (d/pitch-stats nil high0))
        low   (d/set-container-pitch-stats low0 (d/pitch-stats nil low0))
        par   {:type :PAR :id :verse :context (c/context) :children [:high :low]}
        root {:type :ROOT :id :ROOT
              :context (c/context-root {"Tempo" 240 "volume" 80})
              :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :high high)
    (repo/commit-node! :low low)
    (repo/commit-node! :verse par)
    (repo/play-latest!)
    (let [eng   (engine/engine nil repo/play-tx :ROOT)
          hi-p  (promise)
          lo-p  (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! :mark-hi (fn [event] (deliver hi-p event)))
      (conductor/register-action! :mark-lo (fn [event] (deliver lo-p event)))
      (conductor/schedule! :high :enter :mark-hi)
      (conductor/schedule! :low :enter :mark-lo)
      (engine/play :verse)
      (let [hi-event (deref hi-p 2000 :timeout)
            lo-event (deref lo-p 2000 :timeout)]
        (is (not= :timeout hi-event) "the :high section's :enter fired")
        (is (not= :timeout lo-event) "the :low section's :enter fired")
        (is (= [:TAA :TAB] (:path (:voice hi-event)))
            "higher pitch, listed FIRST, still gets the LATER track id --
             nested under :TAA, play's own minted top-level id for this call")
        (is (= [:TAA :TAA] (:path (:voice lo-event)))
            "lower pitch, listed SECOND, gets :TAA -- the lowest")))))

;; ============================================================
;; New play-arg mini-language -- []=seq/#{}=par, [Form :algo Name] tags,
;; recursive #{}-mirroring return shape
;; ============================================================

(deftest par-branches-get-their-own-individual-algo-tags
  ;; #{[:high :algo ...] [:low :algo ...]} -- each branch's own tag is
  ;; assign-algo!'d onto ITS OWN freshly-forked (here: freshly-minted
  ;; top-level) path before that voice's first node runs -- the
  ;; motivating case for tagging in the first place.
  (repo/reset-all!)
  (let [hi    (d/leaf :hi (c/context) 1/4 [80])
        lo    (d/leaf :lo (c/context) 1/4 [40])
        high0 {:type :SEQ :id :high :context (c/context) :children [hi]}
        low0  {:type :SEQ :id :low :context (c/context) :children [lo]}
        high  (d/set-container-pitch-stats high0 (d/pitch-stats nil high0))
        low   (d/set-container-pitch-stats low0 (d/pitch-stats nil low0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:high :low]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :high high)
    (repo/commit-node! :low low)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (wall/register-wall! ::hi-algo (fn [nodes _ctx _voice] nodes))
      (wall/register-wall! ::lo-algo (fn [nodes _ctx _voice] nodes))
      (let [ids (engine/play #{[:high :algo ::hi-algo] [:low :algo ::lo-algo]})]
        (is (= #{:TAA :TAB} ids) "one flat id per branch, no wrapping parent")
        (is (= ::lo-algo (get (engine/algo-assignments eng) [:TAA]))
            "lowest pitch lands on :TAA, and keeps ITS OWN tag -- :low's, not :high's")
        (is (= ::hi-algo (get (engine/algo-assignments eng) [:TAB]))
            "highest pitch lands on :TAB, with ITS OWN tag")))))

;; ============================================================
;; par -- a #{}-equivalent that also accepts the same Form more than
;; once, since it's a vector tagged :parallel? in its own metadata
;; rather than a literal Clojure set (which can't hold two = values at
;; all -- a genuine reader error, not just discouraged).
;; ============================================================

(deftest par-of-distinct-forms-behaves-identically-to-a-literal-set
  (repo/reset-all!)
  (let [hi    (d/leaf :hi (c/context) 1/4 [80])
        lo    (d/leaf :lo (c/context) 1/4 [40])
        high0 {:type :SEQ :id :high :context (c/context) :children [hi]}
        low0  {:type :SEQ :id :low :context (c/context) :children [lo]}
        high  (d/set-container-pitch-stats high0 (d/pitch-stats nil high0))
        low   (d/set-container-pitch-stats low0 (d/pitch-stats nil low0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:high :low]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :high high)
    (repo/commit-node! :low low)
    (repo/play-latest!)
    (is (= (engine/display repo/play-tx #{:high :low})
           (engine/display repo/play-tx (engine/par :high :low)))
        "par with genuinely distinct branches previews identically to the
         equivalent literal #{...} -- par doesn't change anything about
         the common case, it only adds what #{} structurally can't do")))

(deftest par-mints-two-real-voices-for-the-same-id-written-twice
  ;; #{:melody :melody} is a reader error before this code even runs --
  ;; (par :melody :melody) is a plain vector, no such restriction.
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse0 {:type :SEQ :id :verse :context (c/context) :children [n1]}
        verse  (d/set-container-pitch-stats verse0 (d/pitch-stats nil verse0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (let [ids (engine/play (engine/par :verse :verse))]
        (is (= #{:TAA :TAB} ids)
            "two genuinely distinct voices minted, both playing the SAME
             underlying :verse content, at two different track ids")))))

(deftest par-branches-can-share-the-identical-algo-tag
  ;; #{[:verse :algo ::same] [:verse :algo ::same]} is ALSO a reader
  ;; error -- two structurally-identical tag vectors are = to each
  ;; other, so even distinguishing branches by algo doesn't save a
  ;; literal set when the algo itself is meant to be the same on both
  ;; (e.g. two offset copies of one phrase running the same transform --
  ;; the real motivating case, not a contrived one).
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse0 {:type :SEQ :id :verse :context (c/context) :children [n1]}
        verse  (d/set-container-pitch-stats verse0 (d/pitch-stats nil verse0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (wall/register-wall! ::same-algo (fn [nodes _ctx _voice] nodes))
      (let [ids (engine/play (engine/par [:verse :algo ::same-algo]
                                          [:verse :algo ::same-algo]))]
        (is (= #{:TAA :TAB} ids) "two distinct voices, not collapsed into one")
        (is (= ::same-algo (get (engine/algo-assignments eng) [:TAA])))
        (is (= ::same-algo (get (engine/algo-assignments eng) [:TAB]))
            "both really did get the identical algo -- the whole point")))))

(deftest nested-par-flattens-away-its-own-wrapping-voice
  ;; #{:melody #{:a :b}} -> #{:TAA #{:TAB :TAC}} -- the nested #{}
  ;; branch has nothing of its own to play (immediately just another
  ;; #{}), so it never gets an intermediate wrapping voice: its own
  ;; children pull ids from the SAME shared, occupancy-checked pool the
  ;; outer level does, not an independent range under a wasted parent.
  ;; melody (a measurable pitch) always ranks ahead of the nested #{}
  ;; (unmeasurable -- form-pitch-source sorts a group last), regardless
  ;; of melody's own raw pitch value -- that's why melody lands on :TAA
  ;; even though 80 > both a's and b's pitches.
  (repo/reset-all!)
  (let [mk     (fn [id pitch]
                 (let [c0 {:type :SEQ :id id :context (c/context)
                           :children [(d/leaf (keyword (str (name id) "-n")) (c/context) 1/4 [pitch])]}]
                   (d/set-container-pitch-stats c0 (d/pitch-stats nil c0))))
        melody (mk :melody 80)
        a      (mk :a 40)
        b      (mk :b 50)
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 240 "volume" 80})
                :children [:melody :a :b]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :melody melody)
    (repo/commit-node! :a a)
    (repo/commit-node! :b b)
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)
      (let [ids (engine/play #{:melody #{:a :b}})]
        (is (= #{:TAA #{:TAB :TAC}} ids)
            "every voice/track gets an id, not subparts -- no id spent on
             the nested #{}'s own wrapping")
        (is (every? #(some? (engine/voice-at eng %)) [:TAA :TAB :TAC])
            "all three ids are real, independently addressable top-level voices")))))

(deftest tagged-vector-member-pushes-then-restores-to-the-enclosing-algo
  ;; A tag inside an ongoing [] walk (play-form-tagged's non-#{} branch)
  ;; temporarily reassigns the CURRENT voice's own path, then restores
  ;; whatever was there before -- not unconditionally to identity -- so
  ;; nesting composes: a tag nested inside an already-tagged outer span
  ;; falls back to the OUTER tag afterward, not identity.
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  (let [log    (atom [])
        before {:type :SEQ :id :before :context (c/context) :children [(d/leaf :b1 (c/context) 1/32 [60])]}
        middle {:type :SEQ :id :middle :context (c/context) :children [(d/leaf :m1 (c/context) 1/32 [62])]}
        after  {:type :SEQ :id :after  :context (c/context) :children [(d/leaf :a1 (c/context) 1/32 [64])]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 240 "volume" 80})
                :children [:before :middle :after]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :before before)
    (repo/commit-node! :middle middle)
    (repo/commit-node! :after after)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          done (promise)]
      (engine/set-engine! eng)
      (wall/register-wall! ::outer-log (fn [nodes _ctx _voice] (swap! log conj :outer) nodes))
      (wall/register-wall! ::inner-log (fn [nodes _ctx _voice] (swap! log conj :inner) nodes))
      (conductor/register-action! :after-exit (fn [_] (deliver done true)))
      (conductor/schedule! :after :exit :after-exit)
      (engine/play [:before [:middle :algo ::inner-log] :after] :algo ::outer-log)
      (deref done 2000 :timeout)
      (is (= [:outer :inner :outer]
             (map first (partition-by identity @log)))
          "before -> outer, middle -> inner (tagged), after -> outer again
           (restored, not identity) -- consecutive repeats within one
           container-then-leaf apply-wall pass collapsed via partition-by,
           order/identity is what's under test, not call count"))))

(defn- verse-fixture!
  "Shared one-part fixture (:verse, a single 1/32 leaf) for the
   parameterized-algo tests below -- none of them care about the
   material itself, only what ends up in :algo-assignments."
  [eng]
  (repo/reset-all!)
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (repo/play-latest!))
  (engine/set-engine! eng))

(deftest inline-parameterized-algo-applies-the-given-args
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (wall/register-wall! ::mark-n (fn [n] (fn [nodes _ctx _voice] (map #(assoc % :marked n) nodes))))
    (engine/play :verse :algo [::mark-n 5])
    (let [resolved (:fn (get @(:algo-assignments eng) [:TAA]))]
      (is (fn? resolved) "a [name args...] tag resolves to a real fn, not the raw factory")
      (is (= [{:marked 5}] (resolved [{}] [] nil))
          "the factory's own args (5) were actually baked into the resolved wall fn"))))

(deftest doubling-wall-fn-invoked-exactly-three-times-not-unboundedly
  ;; Regression test for the safety property play-leaves' own docstring
  ;; describes (and core.wall's ns docstring/register-wall!'s docstring
  ;; now explain to a wall-fn author, not just this internal comment): a
  ;; 1-to-N expanding wall fn assigned to a voice is called at most
  ;; twice per authored note -- once on the container's own sibling
  ;; list, once more per node THAT call produced -- and its own output
  ;; is never threaded back through the wall a third time. Before this,
  ;; that "exactly 3 calls, not unbounded" claim was only ever verified
  ;; once, by hand, per a comment -- this locks it in as a real,
  ;; run assertion instead.
  ;;
  ;; Deliberately NOT verse-fixture!/a bare :done action-id: this test
  ;; waits on real playback actually finishing (:exit), unlike its
  ;; verse-fixture!-using neighbors above, which only ever check
  ;; :algo-assignments synchronously right after play (set at minting
  ;; time, before any voice's own go-block runs) and never actually wait
  ;; on completion at all. core.conductor's tables are process-wide
  ;; globals (deliberately, see core.repo/core.wall/core.conductor's own
  ;; single-session design) -- a still-unwinding voice left over from
  ;; a DIFFERENT, already-finished test, one that also happened to use
  ;; the common :verse/:done names, can otherwise deliver this test's
  ;; own `done` promise early. Confirmed live: this exact test flaked
  ;; against verse-fixture!/:done when run as part of the full suite,
  ;; passing in isolation -- a uniquely namespaced container id and
  ;; action-id removes the collision instead of chasing the timing.
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (let [n1     (d/leaf :n1 (c/context) 1/32 [60])
        verse  {:type :SEQ :id ::doubler-verse :context (c/context) :children [n1]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 240 "volume" 80})
                :children [::doubler-verse]}
        eng    (engine/engine nil repo/play-tx :ROOT)
        calls  (atom [])
        double (fn [nodes _ctx _voice]
                 (swap! calls conj (count nodes))
                 (mapcat (fn [n] [n n]) nodes))]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! ::doubler-verse verse)
    (repo/play-latest!)
    (engine/set-engine! eng)
    (wall/register-wall! ::doubler double)
    (let [done (promise)]
      (conductor/register-action! ::doubler-done (fn [_] (deliver done true)))
      (conductor/schedule! ::doubler-verse :exit ::doubler-done)
      (engine/play ::doubler-verse :algo ::doubler)
      (is (= true (deref done 2000 :timeout))
          "the whole call completed -- an unbounded redispatch would spawn
           goroutines forever and never reach the container's own :exit")
      ;; Was a fixed (= [1 1 1] @calls) before look-ahead existed: exactly
      ;; 3 invocations, the batch-level call on the container's one-leaf
      ;; sibling list plus one singleton call per leaf that call's own
      ;; doubling produced -- deterministic because resolve-algo only had
      ;; ONE real invocation site per visit back then. Look-ahead's own
      ;; speculative dry-walk (see async_engine.clj's own "Look-ahead"
      ;; section header comment) is a SECOND, independently-timed reason
      ;; for a wall fn to be called -- accepted and documented there as a
      ;; real consequence for a side-effecting wall fn, not a bug -- so
      ;; the exact count is no longer a fixed constant, only bounded and
      ;; timing-dependent. What this test still needs to protect against
      ;; (the actual regression it exists for) is unchanged: no call is
      ;; ever fed already-expanded output back in (every recorded count
      ;; is exactly 1, never 2+), and growth stays small, never runaway.
      (is (every? #(= 1 %) @calls)
          "every call was given exactly ONE node -- doubled output is
           never threaded back through the wall a second time, look-ahead
           included (its own dry walk mirrors the same singleton-per-leaf
           shape, never re-feeding its own output either)")
      (is (<= 3 (count @calls) 9)
          "bounded, not unbounded -- 3 from the real walk (see the old
           comment above) plus at most a small, timing-dependent number
           more from look-ahead's own independently-scheduled speculative
           pass over the same tiny amount of material; nowhere near what
           a genuine unbounded-redispatch regression would produce"))))

;; register-wall!'s OPTIONAL :kind (:fn/:factory) -- entirely opt-in, so
;; these tests cover both halves: what improves when a registerer
;; declares it, and (deliberately, to keep the fix honest) that nothing
;; changes at all when they don't.

(deftest undeclared-factory-used-bare-still-silently-hands-back-the-raw-closure
  ;; Documents the boundary of the :kind fix, and independently confirms
  ;; the danger it closes is real, not hypothetical: with no :kind
  ;; declared (register-wall!'s old 2-arg shape, still the common case),
  ;; a bare reference to a genuine factory has ALWAYS silently returned
  ;; the raw, unapplied factory closure -- not identity-wall, not an
  ;; error -- which async-engine would later invoke as (factory nodes
  ;; ctx-chain voice) instead of the factory's own real arg shape. Here
  ;; the factory's own arity (3) happens to coincidentally match a wall
  ;; fn's, so calling it that way doesn't even throw -- it just returns
  ;; another fn where processed node material was expected, silent type
  ;; confusion rather than a loud arity exception.
  (wall/register-wall! ::undeclared-factory (fn [a b c] (fn [nodes _ctx _voice] (cons [a b c] nodes))))
  (let [resolved (#'engine/resolve-algo-name ::undeclared-factory)]
    (is (= (wall/wall-fn ::undeclared-factory) resolved)
        "the raw factory itself comes back, not identity-wall and not an error")
    ;; apply-wall would call resolved AS a wall fn: (resolved nodes ctx-chain voice).
    ;; Since the factory's own arity (3) happens to match, that "succeeds" without
    ;; throwing -- but returns ANOTHER function (the factory's real return value)
    ;; where a processed node seq was expected: silent type confusion, not a crash.
    (is (fn? (resolved [{}] [] nil))
        "invoking the raw factory as if it were a wall fn returns a function, not
         processed node material -- the exact danger this fix closes when :kind IS declared")))

(deftest declared-factory-used-bare-falls-back-to-identity-instead
  (wall/register-wall! ::declared-factory (fn [a b c] (fn [nodes _ctx _voice] (cons [a b c] nodes))) nil :factory)
  (is (= wall/identity-wall (#'engine/resolve-algo-name ::declared-factory))
      "kind :factory declared -- a bare reference is rejected, never hands back the raw closure"))

(deftest bare-declared-factory-tag-throws-before-playing
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (wall/register-wall! ::declared-factory2 (fn [a] (fn [nodes _ctx _voice] nodes)) nil :factory)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is registered as a factory, not a"
          (engine/play :verse :algo ::declared-factory2)))
    (is (not (contains? @(:algo-assignments eng) [:TAA])))))

(deftest declared-plain-fn-used-inline-gets-a-specific-message-not-a-bare-arity-exception
  (wall/register-wall! ::declared-plain (fn [nodes _ctx _voice] nodes) nil :fn)
  (is (nil? (wall/apply-factory ::declared-plain [1 2]))
      "apply-factory refuses to call a declared :fn as a factory at all")
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed to resolve to a usable algorithm"
          (engine/play :verse :algo [::declared-plain 1 2])))))

(deftest configure-wall-re-tags-the-resolved-fn-as-fn-not-still-factory
  ;; configure-wall! must re-register with :kind :fn explicitly -- once
  ;; it runs, location genuinely holds a plain, already-resolved wall fn,
  ;; not the factory anymore, so a later BARE reference must succeed, not
  ;; get rejected by the same check declared-factory-used-bare-falls-
  ;; back-to-identity-instead just exercised.
  (wall/register-wall! ::reconfigurable (fn [n] (fn [nodes _ctx _voice] (map #(assoc % :marked n) nodes)))
                        nil :factory)
  (wall/configure-wall! ::reconfigurable 9)
  (is (= :fn (wall/wall-kind ::reconfigurable)))
  (let [resolved (#'engine/resolve-algo-name ::reconfigurable)]
    (is (not= wall/identity-wall resolved)
        "a bare reference after configure-wall! is NOT rejected as 'still a factory'")
    (is (= [{:marked 9}] (resolved [{}] [] nil)))))

;; A bad :algo tag on a `play` call now throws immediately, at the call
;; itself, before any voice starts -- matching play's own long-standing
;; treatment of a bad id (see play-throws-a-clear-error-for-an-
;; unresolvable-id above). resolve-algo-name/assign-algo! THEMSELVES
;; still degrade silently to identity-wall (a call reached from inside
;; an already-running voice's own go-block, e.g. a tag nested mid-[]
;; via play-form-tagged, can't safely throw -- see that fn's own
;; docstring) -- these three tests cover the NEW pre-flight guard
;; (validate-algo-name!, called from validate-ids!/play-top-level!),
;; which is what actually gives a mistyped play-call-level :algo tag a
;; loud, immediate failure instead of a console-only warning.

(deftest inline-unregistered-algo-name-throws-before-playing
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (swap! (:voices eng) assoc [:already-playing] {:birth-token :sentinel})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed to resolve to a usable algorithm"
          (engine/play :verse :algo [::totally-unregistered 1 2])))
    (is (= {:birth-token :sentinel} (get @(:voices eng) [:already-playing]))
        "a rejected :algo tag never wipes eng's :voices registry, same
         invariant a rejected id already has -- validate-algo-name! runs
         before play's own pre-fn")
    (is (not (contains? @(:algo-assignments eng) [:TAA]))
        "no algo-assignments entry left behind for a call that never actually played")))

(deftest inline-factory-that-throws-blocks-play-instead-of-silently-degrading
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (wall/register-wall! ::boom (fn [_] (throw (ex-info "nope" {}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed to resolve to a usable algorithm"
          (engine/play :verse :algo [::boom 1])))
    (is (not (contains? @(:algo-assignments eng) [:TAA]))
        "a factory that throws applying its args blocks the play call outright,
         not a crashed-but-still-started performance")))

(deftest bare-unregistered-algo-name-throws-before-playing
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered name"
          (engine/play :verse :algo ::still-totally-unregistered)))
    (is (not (contains? @(:algo-assignments eng) [:TAA])))))

(deftest assign-algo-directly-still-degrades-silently-to-identity
  ;; assign-algo! called DIRECTLY (not via a play call's own :algo tag)
  ;; is unchanged -- it's also the mechanism play-form-tagged/
  ;; play-form-par call from inside an already-running voice's own
  ;; go-block (mid-performance, after validate-algo-name! already
  ;; passed once at play time), where throwing isn't safe. Reassigning
  ;; an already-playing voice by hand at the REPL with a typo'd name
  ;; still degrades to identity-wall with a console warning, not an
  ;; exception -- this fn is the safety net validate-algo-name! sits in
  ;; front of, not something it replaces.
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (engine/play :verse)
    (engine/assign-algo! eng [:TAA] ::yet-another-unregistered-name)
    (is (= wall/identity-wall (:fn (get @(:algo-assignments eng) [:TAA]))))))

(deftest configure-wall-install-then-configure-then-bare-reference
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (verse-fixture! eng)
    (wall/register-wall! ::verse-color
                          (fn [n] (fn [nodes _ctx _voice] (map #(assoc % :marked n) nodes)))
                          "marks every node with n")
    (wall/configure-wall! ::verse-color 7)
    (engine/play :verse :algo ::verse-color)
    (let [resolved (:fn (get @(:algo-assignments eng) [:TAA]))]
      (is (= [{:marked 7}] (resolved [{}] [] nil))
          "a plain bare-name reference picks up whatever configure-wall! most recently fed it")
      (is (= ::verse-color (get (engine/algo-assignments eng) [:TAA]))
          "configure-wall! re-registers under the SAME name -- assign-algo! stored
           ::verse-color as the assignment's own :name, read back directly")
      (is (= "marks every node with n" (wall/walls ::verse-color))
          "reconfiguring preserves the name's existing doc rather than blanking it"))))

(deftest configure-wall-reconfigure-needs-the-factory-re-registered-first
  ;; ONE store, deliberately: after configure-wall! runs once, the name
  ;; holds a concrete fn, not the factory anymore -- reconfiguring again
  ;; without re-registering the factory first can't work (the "factory"
  ;; apply-factory would try to apply args to is now a plain 3-arg wall
  ;; fn), and should leave the PRIOR configuration untouched rather than
  ;; silently breaking it.
  (let [eng (engine/engine nil repo/play-tx :ROOT)
        mk  (fn [] (fn [n] (fn [nodes _ctx _voice] (map #(assoc % :marked n) nodes))))]
    (verse-fixture! eng)
    (wall/register-wall! ::loc (mk))
    (wall/configure-wall! ::loc 1)
    (engine/play :verse :algo ::loc)
    (is (= [{:marked 1}] ((:fn (get @(:algo-assignments eng) [:TAA])) [{}] [] nil)))
    (wall/configure-wall! ::loc 2)
    (engine/play :verse :algo ::loc)
    (is (= [{:marked 1}] ((:fn (get @(:algo-assignments eng) [:TAA])) [{}] [] nil))
        "without re-registering the factory, configure-wall! left :loc's prior config untouched")
    (wall/register-wall! ::loc (mk))
    (wall/configure-wall! ::loc 2)
    (engine/play :verse :algo ::loc)
    (is (= [{:marked 2}] ((:fn (get @(:algo-assignments eng) [:TAA])) [{}] [] nil))
        "after re-registering the factory, reconfiguring replaces the effective algorithm")))

(deftest sq-parallel-metadata-still-wins-over-a-plain-untagged-vector
  ;; Regression check: sq's own {:parallel? true/false} metadata must
  ;; keep winning FIRST in form-tag+items, untouched by the []=seq/
  ;; #{}=par redesign -- sq's own output is always a plain vector, never
  ;; a set, so if the metadata branch were ever skipped a genuinely
  ;; parallel container would silently play back sequentially (vectors
  ;; are always :seq now with no metadata present).
  (repo/reset-all!)
  (let [n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [67])
        sop     {:type :SEQ :id :sop :context (c/context) :children [n1]}
        bass    {:type :SEQ :id :bass :context (c/context) :children [n2]}
        chorale {:type :PAR :id :chorale :context (c/context) :children [:sop :bass]}
        root    {:type :ROOT :id :ROOT
                 :context (c/context-root {"Tempo" 120 "volume" 80})
                 :children [:chorale]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :chorale chorale)
    (repo/commit-node! :sop sop)
    (repo/commit-node! :bass bass)
    (repo/play-latest!)
    (let [children (d/children (repo/view (repo/latest-tx)) chorale)
          sq-like  (with-meta children {:parallel? true})]
      (is (vector? sq-like) "sq's own output shape -- a plain vector, never a set")
      (let [[tag _] (#'engine/form-tag+items sq-like)]
        (is (= :par tag)
            "metadata wins over the vector's own now-always-:seq default")))))

;; ============================================================
;; Look-ahead -- exercised directly against the private helpers
;; (#'engine/...), same technique the channel-pool/form-tag+items tests
;; above already use, plus one real end-to-end play through it. See
;; doc/decisions.md and async_engine.clj's own "Look-ahead" section
;; header comment for the design this locks in.
;; ============================================================

(defn- test-voice
  "Minimal voice map for exercising look-ahead's own private fns
   directly -- only the keys any of them actually read (:eng's own
   :algo-assignments, :path, :tx, :structural, :lookahead), not a real
   engine/fork-voice-built voice."
  [path tx]
  {:eng {:algo-assignments (atom {})}
   :path path
   :tx (atom tx)
   :structural (atom 0)
   :lookahead (#'engine/fresh-lookahead)})

;; ---- lookahead-children (the dry walk) ----

(deftest lookahead-children-walks-flat-seq-of-leaves
  (let [voice   (test-voice [:v1] 1)
        n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [62])
        entries (doall (#'engine/lookahead-children voice {} [n1 n2] [] 0))]
    (is (= [:n1 :n2] (map :orig-id entries)))
    (is (= [[60] [62]] (map (comp :pitches :midi) entries)))))

(deftest lookahead-children-recurses-into-nested-seq
  ;; children is always ALREADY d/children-resolved by the time this fn
  ;; sees it (see this fn's own docstring) -- inner sits here as the
  ;; real resolved container map, never a bare keyword, matching
  ;; exactly what maybe-prefetch-lookahead!/play-node's own container
  ;; branch actually hand it. This is the shape that caught a real bug
  ;; while writing this fn: an earlier version checked keyword? here,
  ;; which is always false for an already-resolved child, so a nested
  ;; :SEQ was silently skipped instead of recursed into.
  (let [voice    (test-voice [:v1] 1)
        n1       (d/leaf :n1 (c/context) 1/4 [60])
        n2       (d/leaf :n2 (c/context) 1/4 [62])
        n3       (d/leaf :n3 (c/context) 1/4 [64])
        inner    {:type :SEQ :id :inner :context (c/context) :children [n2]}
        entries  (doall (#'engine/lookahead-children voice {} [n1 inner n3] [] 0))]
    (is (= [:n1 :n2 :n3] (map :orig-id entries))
        "walked straight through the nested :SEQ -- n1, then n2 (inside
         :inner), then n3, resuming the outer list correctly afterward")))

(deftest lookahead-children-stops-at-par
  (let [voice   (test-voice [:v1] 1)
        n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [62])
        par     {:type :PAR :id :fork :context (c/context) :children [:a :b]}
        entries (doall (#'engine/lookahead-children voice {} [n1 par n2] [] 0))]
    (is (= [:n1] (map :orig-id entries))
        "n2, sitting past the :PAR fork, is never reached -- look-ahead
         doesn't model forking, see this fn's own docstring")))

(deftest lookahead-children-stops-at-iterator
  (let [voice   (test-voice [:v1] 1)
        n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [62])
        src     {:type :SEQ :id :src :context (c/context) :children [n2]}
        iter    (d/iterator :REPEAT :rep (c/context) src {:count 2})
        n3      (d/leaf :n3 (c/context) 1/4 [64])
        entries (doall (#'engine/lookahead-children voice {} [n1 iter n3] [] 0))]
    (is (= [:n1] (map :orig-id entries))
        "an Iterator is the other structural boundary this walk doesn't
         model -- stops there too, same as :PAR")))

(deftest lookahead-children-skips-bar-and-assignment-nodes
  (let [voice   (test-voice [:v1] 1)
        n1      (d/leaf :n1 (c/context) 1/4 [60])
        bar     (d/bar 1)
        assign  {:type :assignment :key :Tempo :val 100}
        n2      (d/leaf :n2 (c/context) 1/4 [62])
        entries (doall (#'engine/lookahead-children voice {} [n1 bar assign n2] [] 0))]
    (is (= [:n1 :n2] (map :orig-id entries))
        "zero-duration structural/instruction markers are passed over,
         not treated as a stop condition -- same tolerance play-node's
         own :else branch already has")))

(deftest lookahead-children-applies-registered-wall-algorithm
  (let [voice  (test-voice [:v1] 1)
        n1     (d/leaf :n1 (c/context) 1/4 [60])
        double (fn [nodes _ctx _voice] (mapcat (fn [n] [n n]) nodes))]
    (wall/register-wall! ::lookahead-test-doubler double)
    (engine/assign-algo! (:eng voice) [:v1] ::lookahead-test-doubler)
    (let [entries (doall (#'engine/lookahead-children voice {} [n1] [] 0))]
      (is (= 2 (count entries))
          "the dry walk runs the SAME resolve-algo call the real walk would")
      (is (every? #(= :n1 (:orig-id %)) entries)
          "both of the doubled outputs still trace back to the ONE
           original leaf -- consume-time grouping by :orig-id, and the
           double-call-per-authored-note count regression test above,
           both depend on this staying true"))
    (wall/unregister-wall! ::lookahead-test-doubler)))

;; ---- lookahead-take-one ----

(deftest lookahead-take-one-returns-just-the-next-leafs-entries
  (let [voice   (test-voice [:v1] 1)
        n1      (d/leaf :n1 (c/context) 1/4 [60])
        n2      (d/leaf :n2 (c/context) 1/4 [62])
        cursor  (#'engine/lookahead-children voice {} [n1 n2] [] 0)
        entries (#'engine/lookahead-take-one cursor)]
    (is (= [:n1] (map :orig-id entries))
        "only n1's own entry comes back -- n2 stays in the cursor,
         untouched, for whenever it's actually needed (one leaf ahead,
         never a whole bar -- see this file's own header comment)")))

(deftest lookahead-take-one-returns-the-whole-expanded-group
  (let [voice  (test-voice [:v1] 1)
        n1     (d/leaf :n1 (c/context) 1/4 [60])
        double (fn [nodes _ctx _voice] (mapcat (fn [n] [n n]) nodes))]
    (wall/register-wall! ::take-one-doubler double)
    (engine/assign-algo! (:eng voice) [:v1] ::take-one-doubler)
    (let [cursor  (#'engine/lookahead-children voice {} [n1] [] 0)
          entries (#'engine/lookahead-take-one cursor)]
      (is (= 2 (count entries))
          "both of n1's own doubled outputs come back together, not
           just the first")
      (is (every? #(= :n1 (:orig-id %)) entries)))
    (wall/unregister-wall! ::take-one-doubler)))

(deftest lookahead-take-one-returns-nil-when-cursor-is-empty
  (is (nil? (#'engine/lookahead-take-one nil)))
  (is (nil? (#'engine/lookahead-take-one
              (#'engine/lookahead-children (test-voice [:v1] 1) {} [] [] 0)))))

;; ---- maybe-prefetch-lookahead! ----

(defn- wait-until-not-inflight!
  "Polls la until :inflight? goes false or ms elapses -- the background
   thread maybe-prefetch-lookahead! dispatches has no other externally
   observable completion signal, so tests wait on this instead of
   guessing a fixed sleep."
  [la ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (while (and (:inflight? @la) (< (System/currentTimeMillis) deadline))
      (Thread/sleep 2))))

(deftest maybe-prefetch-lookahead-noop-without-lookahead-state
  (let [voice {:tx (atom 1) :structural (atom 0)}] ;; no :lookahead key at all
    (is (nil? (#'engine/maybe-prefetch-lookahead! voice [:anything] [])))))

(deftest maybe-prefetch-lookahead-noop-when-slot-already-occupied
  (let [voice  (test-voice [:v1] 1)
        la     (:lookahead voice)
        n1     (d/leaf :n1 (c/context) 1/4 [60])
        n2     (d/leaf :n2 (c/context) 1/4 [62])
        marker {:orig-id :already-there :tx 1 :algo-entry nil :entries []}]
    (swap! la assoc :slot marker)
    (#'engine/maybe-prefetch-lookahead! voice [n1 n2] [])
    (is (false? (:inflight? @la)) "never dispatched -- slot wasn't empty")
    (is (= marker (:slot @la)) "left completely untouched")))

(deftest maybe-prefetch-lookahead-noop-when-already-inflight
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])]
    (swap! la assoc :inflight? true)
    (#'engine/maybe-prefetch-lookahead! voice [n1 n2] [])
    (is (nil? (:slot @la)) "no second dispatch happened on top of the existing one")))

(deftest maybe-prefetch-lookahead-noop-when-nothing-comes-after
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])]
    (#'engine/maybe-prefetch-lookahead! voice [n1] []) ;; (rest [n1]) is empty
    (is (false? (:inflight? @la)) "nothing to prefetch, nothing dispatched")))

(deftest maybe-prefetch-lookahead-fills-the-slot
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])]
    (#'engine/maybe-prefetch-lookahead! voice [n1 n2] [])
    (wait-until-not-inflight! la 1000)
    (let [slot (:slot @la)]
      (is (= :n2 (:orig-id slot)) "prefetched whatever comes AFTER n1 -- n2")
      (is (= 1 (:tx slot)))
      (is (= [62] (:pitches (:midi (first (:entries slot)))))))))

;; ---- try-consume-lookahead! ----

(deftest try-consume-lookahead-returns-nil-when-slot-empty
  (let [voice (test-voice [:v1] 1)
        n1    (d/leaf :n1 (c/context) 1/4 [60])]
    (is (nil? (#'engine/try-consume-lookahead! voice n1)))))

(deftest try-consume-lookahead-rejects-tx-mismatch
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        entries [{:orig-id :n1 :part n1 :midi {:dur-secs 0.5}}]]
    (swap! la assoc :slot {:orig-id :n1 :tx 99 :algo-entry nil :entries entries})
    (is (nil? (#'engine/try-consume-lookahead! voice n1))
        "slot was computed against tx 99, voice's own :tx is 1 -- must not be trusted")
    (is (nil? (:slot @la)) "a mismatch always empties the slot too")))

(deftest try-consume-lookahead-rejects-algo-assignment-mismatch
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        entries [{:orig-id :n1 :part n1 :midi {:dur-secs 0.5}}]]
    (swap! la assoc :slot {:orig-id :n1 :tx 1 :algo-entry {:name :old :fn identity} :entries entries})
    ;; simulates a live assign-algo! landing on this voice's own path
    ;; since the slot was precomputed
    (swap! (:algo-assignments (:eng voice)) assoc [:v1] {:name :new :fn identity})
    (is (nil? (#'engine/try-consume-lookahead! voice n1))
        "the slot was computed against a since-superseded algorithm assignment")
    (is (nil? (:slot @la)))))

(deftest try-consume-lookahead-rejects-orig-id-mismatch
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        entries [{:orig-id :n2 :part n2 :midi {:dur-secs 0.5}}]]
    (swap! la assoc :slot {:orig-id :n2 :tx 1 :algo-entry nil :entries entries})
    (is (nil? (#'engine/try-consume-lookahead! voice n1))
        "the slot holds a DIFFERENT leaf's own prefetch")
    (is (nil? (:slot @la))
        "emptied regardless, so a stale slot never blocks a future prefetch")))

(deftest try-consume-lookahead-consumes-a-matching-slot
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        entries [{:orig-id :n1 :part n1 :midi {:dur-secs 0.5 :pitches [60]}}]]
    (swap! la assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries entries})
    (let [pre (#'engine/try-consume-lookahead! voice n1)]
      (is (= entries pre) "the matching entries are returned for firing")
      (is (nil? (:slot @la)) "consumed -- slot empty again"))))

(deftest try-consume-lookahead-consumes-a-whole-expanded-group
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        e1a   {:orig-id :n1 :part n1 :midi {:dur-secs 0.25}}
        e1b   {:orig-id :n1 :part n1 :midi {:dur-secs 0.25}}] ;; e.g. an ornament/algo-expanded 2nd node
    (swap! la assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries [e1a e1b]})
    (let [pre (#'engine/try-consume-lookahead! voice n1)]
      (is (= [e1a e1b] pre) "both entries for the one expanded leaf come back together")
      (is (nil? (:slot @la))))))

;; ---- watch-lookahead-tx! -- eager, push-based invalidation on redirect ----

(deftest watch-lookahead-tx-empties-slot-on-redirect
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)]
    (#'engine/watch-lookahead-tx! voice)
    (swap! la assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries []})
    (reset! (:tx voice) 2)
    (is (nil? (:slot @la))
        "a live redirect empties the slot immediately, without waiting
         for a consume attempt to discover the tx no longer matches")))

(deftest watch-lookahead-tx-ignores-a-reset-to-the-same-value
  (let [voice (test-voice [:v1] 1)
        la    (:lookahead voice)]
    (#'engine/watch-lookahead-tx! voice)
    (swap! la assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries []})
    (reset! (:tx voice) 1) ;; same value -- not a real redirect
    (is (some? (:slot @la)) "no real change, nothing to invalidate")))

;; ---- engine-level :algo-assignments watch, wired in the engine constructor ----

(deftest algo-assignments-watch-empties-only-the-affected-voices-slot
  (let [eng     (engine/engine nil repo/play-tx :ROOT)
        path-a  [:a]
        path-b  [:b]
        voice-a {:eng eng :path path-a :lookahead (#'engine/fresh-lookahead)}
        voice-b {:eng eng :path path-b :lookahead (#'engine/fresh-lookahead)}]
    (swap! (:voices eng) assoc path-a voice-a path-b voice-b)
    (swap! (:lookahead voice-a) assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries []})
    (swap! (:lookahead voice-b) assoc :slot {:orig-id :n1 :tx 1 :algo-entry nil :entries []})
    (engine/assign-algo! eng path-a ::algo-assignments-watch-test-name)
    (is (nil? (:slot @(:lookahead voice-a)))
        "voice-a's own path changed -- its slot is emptied")
    (is (some? (:slot @(:lookahead voice-b)))
        "voice-b's own assignment never changed -- untouched")))

;; ---- End-to-end: playback with real per-voice prefetch running ----

(deftest playback-with-active-lookahead-completes-correctly-through-nested-seq
  ;; Confirms real playback through a nested-container piece still
  ;; completes and reaches the correct final bar with look-ahead's own
  ;; prefetch genuinely triggering (via play-seq's own loop, see
  ;; maybe-prefetch-lookahead!) the whole time, not just that each
  ;; private piece works in isolation above. Moderate tempo (not maxed
  ;; out) deliberately gives each prefetch's own background thread real
  ;; time to complete before it's needed, rather than the whole piece
  ;; finishing before any prefetch gets a chance to land.
  (repo/reset-all!)
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
  ;; Uniquely namespaced container/action ids throughout (::lookahead-e2e-*),
  ;; not the generic :verse/:bar1/:bar2/:done this test used at first --
  ;; see doubling-wall-fn-invoked-exactly-three-times-not-unboundedly's own
  ;; comment above for exactly why: core.conductor's tables are process-wide
  ;; globals, so a still-unwinding voice left over from a DIFFERENT,
  ;; already-finished test that also happened to use a common name can
  ;; otherwise deliver THIS test's own `done` promise early. Confirmed live
  ;; as the actual cause of an apparent engine bug that looked identical
  ;; across two completely different look-ahead implementations (a batch/
  ;; coordinator design and this file's own single-slot one) -- passing in
  ;; isolation every time, failing under the full suite every time, which
  ;; is exactly the signature of this same collision, not of either
  ;; implementation actually being wrong.
  (let [meter (el/make-meter 4 4)
        mk    (fn [id p] (d/leaf id (c/context) 1/4 [p]))
        bar1  {:type :SEQ :id ::lookahead-e2e-bar1 :context (c/context)
               :children [(mk :n1 60) (mk :n2 62) (mk :n3 64) (mk :n4 65)]}
        bar2  {:type :SEQ :id ::lookahead-e2e-bar2 :context (c/context)
               :children [(mk :n5 67) (mk :n6 69) (mk :n7 71) (mk :n8 72)]}
        verse {:type :SEQ :id ::lookahead-e2e-verse :context (c/context)
               :children [::lookahead-e2e-bar1 ::lookahead-e2e-bar2]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 1200 "volume" 80 "Meter" meter})
               :children [::lookahead-e2e-verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! ::lookahead-e2e-verse verse)
    (repo/commit-node! ::lookahead-e2e-bar1 bar1)
    (repo/commit-node! ::lookahead-e2e-bar2 bar2)
    (repo/play-latest!)
    (let [eng  (engine/engine nil repo/play-tx :ROOT)
          done (promise)]
      (engine/set-engine! eng)
      (conductor/register-action! ::lookahead-e2e-done (fn [event] (deliver done event)))
      (conductor/schedule! ::lookahead-e2e-verse :exit ::lookahead-e2e-done)
      (engine/play ::lookahead-e2e-verse)
      (let [event (deref done 2000 :timeout)]
        (is (map? event)
            "played through both nested bars to the verse's own :exit
             signal, look-ahead's own prefetch triggering the whole time")
        (is (= 3 @(:bar (:voice event)))
            "advanced through bars 1 and 2 (8 quarters, 4/4) into bar
             3 -- correct regardless of whether any given note came
             from the slot or was computed fresh")))))
