(ns ^:engine musics.engine-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [musics.test-support :refer [with-fresh-registries]]
            [musics.repo :as repo]
            [musics.registries :as reg]
            [musics.conductor :as conductor]
            [musics.engine :as engine]
            [musics.events :as ev]
            [musics.compose :as compose]
            [musics.wall :as wall]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.common.music-elements :as el]))

(defn- fresh-registries-fixture [f]
  ;; Every test in this file used to open with its own (repo/reset-all!)
  ;; (some also individually resetting the three conductor tables) --
  ;; collapsed into one shared fixture, same pattern musics.core-test
  ;; already uses, now genuinely isolated (a fresh bound atom per
  ;; test, not just the shared one reset back to empty) rather than
  ;; just reset-to-empty.
  (with-fresh-registries (f)))

(use-fixtures :each fresh-registries-fixture)

(deftest section-boundary-signals-fire-during-playback
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng     (engine/engine nil (repo/registry) :ROOT)
          entered (promise)
          exited  (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark-enter (fn [_] (deliver entered true)))
        (conductor/register-action! :mark-exit (fn [_] (deliver exited true)))
        (conductor/schedule! :verse :enter :mark-enter)
        (conductor/schedule! :verse :exit :mark-exit)
        (engine/play :verse)
        (is (= true (deref entered 2000 :timeout))
            "play-node signaled :verse's :enter before playing its child")
        (is (= true (deref exited 2000 :timeout))
            "play-node signaled :verse's :exit once its single leaf finished")))))

(deftest bar-boundary-signal-fires-during-playback
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
    (let [eng  (engine/engine nil (repo/registry) :ROOT)
          bar2 (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
        (conductor/schedule! 2 :enter :mark-bar2)
        (engine/play :verse)
        (is (= 2 (:id (deref bar2 2000 :timeout)))
            "advance-bar! signaled entering bar 2 once the four quarter notes filled bar 1 (4/4)")))))

(deftest bar-boundary-respects-a-non-default-meter
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
    (let [eng  (engine/engine nil (repo/registry) :ROOT)
          bar2 (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark-bar2 (fn [event] (deliver bar2 event)))
        (conductor/schedule! 2 :enter :mark-bar2)
        (engine/play :verse)
        (is (= 2 (:id (deref bar2 2000 :timeout)))
            "three quarter notes exactly fill one 3/4 bar, so bar 2 starts right after")))))

(deftest mark-signal-fires-for-a-barline
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        n2    (d/leaf :n2 (c/context) 1/16 [62])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [n1 (d/bar 1) n2]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng    (engine/engine nil (repo/registry) :ROOT)
          marked (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :mark1 (fn [event] (deliver marked event)))
        (conductor/schedule! [:mark 1 1] :enter :mark1)
        (engine/play :verse)
        (let [event (deref marked 2000 :timeout)]
          (is (= [:mark 1 1] (:id event)))
          (is (= 1 (:count event)))
          (is (= :mark (:kind event))))))))

(deftest mark-signal-does-not-advance-bar-position
  ;; A BarLine has zero duration -- it must never itself trigger a :bar
  ;; crossing, only the notes around it can.
  (let [meter (el/make-meter 4 4)
        n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [(d/bar 1) (d/bar 1) (d/bar 1) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 6000 "volume" 80 "Meter" meter})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng      (engine/engine nil (repo/registry) :ROOT)
          finished (promise)
          bar2?    (atom false)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :done (fn [_] (deliver finished true)))
        (conductor/register-action! :mark-bar2 (fn [_] (reset! bar2? true)))
        (conductor/schedule! :verse :exit :done)
        (conductor/schedule! 2 :enter :mark-bar2)
        (engine/play :verse)
        (deref finished 2000 :timeout)
        (is (false? @bar2?)
            "three bare BarLines plus one quarter note never fill a 4/4 bar")))))

(deftest mark-signal-counts-per-strength-independently
  (let [n1    (d/leaf :n1 (c/context) 1/16 [60])
        verse {:type :SEQ :id :verse :context (c/context)
               :children [(d/bar 1) (d/bar 2) (d/bar 1) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng           (engine/engine nil (repo/registry) :ROOT)
          second-single (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! :second-single (fn [event] (deliver second-single event)))
        (conductor/schedule! [:mark 1 2] :enter :second-single)
        (engine/play :verse)
        (is (= [:mark 1 2] (:id (deref second-single 2000 :timeout)))
            "the double bar-line in between doesn't consume a slot in the single-bar-line count")))))

;; ============================================================
;; schedule-tx! -- the primary use case, now per-voice (moved here from
;; musics.conductor since it needs to know what a voice is)
;; ============================================================

(defn- looping
  "An endless loop over :children [id] -- re-reads id on every pass."
  [loop-id id]
  (d/iterator :REPEAT loop-id (c/context)
              {:type :SEQ :id (keyword (str (name loop-id) "-body")) :context (c/context) :children [id]}
              {:count :infinite}))

(defn- one-note [id pitch dur]
  {:type :SEQ :id id :context (c/context) :children [(d/leaf (keyword (str (name id) "-n")) (c/context) dur [pitch])]})

(defn- pitches-of [evs] (map (comp first :pitches) (filter #(= :note (:kind %)) evs)))

(defn- tx-root! [children]
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT
                            :context (c/context-root {"Tempo" 240 "volume" 80})
                            :children children}))

(deftest schedule-tx-cuts-a-voice-over-at-its-boundary
  ;; the voice's material is a snapshot; at :verse's exit it continues
  ;; with what is committed THEN -- committed after schedule-tx! too
  (tx-root! [:loop])
  (repo/commit-many! {:verse (one-note :verse 60 1/4) :loop (looping :loop :verse)})
  (let [plain (ev/voice-events (repo/registry) :loop :cutover #'engine/cutover)
        cut   (ev/voice-events (repo/registry) :loop :cutover #'engine/cutover)]
    (engine/schedule-tx! :verse :exit)
    (repo/commit-node! :verse (one-note :verse 67 1/4))
    (is (= [60 67 67] (take 3 (pitches-of cut)))
        "pass 1 from the snapshot, then the new :verse from its first exit on")
    (conductor/unschedule-repeating! :verse :exit)
    (is (= [60 60 60] (take 3 (pitches-of (ev/voice-events {:ROOT (get @(repo/registry) :ROOT)
                                                           :verse (one-note :verse 60 1/4)
                                                           :loop (looping :loop :verse)}
                                                          :loop :cutover #'engine/cutover))))
        "nothing armed: the snapshot plays on")
    (is (seq plain))))

(deftest schedule-tx-moves-only-voices-that-cross-the-boundary
  (tx-root! [:song])
  (repo/commit-many! {:m (one-note :m 60 1/4) :b (one-note :b 48 1/4)
                      :mloop (looping :mloop :m) :bloop (looping :bloop :b)
                      :song {:type :PAR :id :song :context (c/context) :children [:mloop :bloop]}})
  (let [evs (ev/voice-events (repo/registry) :song :cutover #'engine/cutover)]
    (engine/schedule-tx! :m :exit)
    (repo/commit-many! {:m (one-note :m 72 1/4) :b (one-note :b 36 1/4)})
    (let [by-voice (group-by :path (filter #(= :note (:kind %)) (take 40 evs)))
          p        #(map (comp first :pitches) (by-voice %))]
      ;; hand-built loops carry no pitch sums, so branches keep written order
      (is (= [60 72 72] (take 3 (p [:TAA :TAA]))) "the melody voice crossed :m's exit and moved")
      (is (= [48 48 48] (take 3 (p [:TAA :TAB]))) "the bass voice never did"))))

(deftest schedule-tx-moves-every-voice-crossing-the-same-bar
  ;; a :bar id is shared by every voice: both cross bar 2, both move
  (tx-root! [:song])
  (repo/commit-many! {:m (one-note :m 60 1) :l1 (looping :l1 :m) :l2 (looping :l2 :m)
                      :song {:type :PAR :id :song :context (c/context) :children [:l1 :l2]}})
  (let [evs (ev/voice-events (repo/registry) :song :cutover #'engine/cutover)]
    (engine/schedule-tx! 2 :enter)
    (repo/commit-node! :m (one-note :m 67 1))
    (let [by-voice (group-by :path (filter #(= :note (:kind %)) (take 80 evs)))]
      (is (= 2 (count by-voice)))
      (doseq [[path notes] by-voice]
        (is (= [60 67 67] (take 3 (map (comp first :pitches) notes))) (str path " moved at bar 2"))))))

(deftest schedule-tx-through-real-playback
  ;; a quarter at 240 is 250ms: the commit lands well before the first
  ;; exit is computed (a lookahead before it sounds)
  (tx-root! [:loop])
  (repo/commit-many! {:verse (one-note :verse 60 1/4) :loop (looping :loop :verse)})
  (let [sent (atom [])]
    ;; fs is a token: with-redefs reaches every engine's thread
    (with-redefs [engine/send-midi-on!  (fn [fs ev _] (when (= fs ::fs) (swap! sent conj (first (:pitches ev)))))
                  engine/send-midi-off! (fn [_ _])]
      (binding [engine/*engine* (engine/engine ::fs (repo/registry) :ROOT)]
        (engine/schedule-tx! :verse :exit)
        (engine/play :loop)
        (Thread/sleep 50)
        (repo/commit-node! :verse (one-note :verse 67 1/4))
        (Thread/sleep 700)
        (engine/stop!)))
    (is (= 60 (first @sent)))
    (is (= #{67} (set (rest @sent))) "every pass after the first plays the new :verse")))

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

(deftest channel-for-goes-silent-not-corrupting-when-pool-exhausted
  (let [eng    (engine/engine nil (atom {}) :ROOT)
        claims (:channel-claims eng)
        _      (dotimes [i 15] (#'engine/claim-channel! claims [i {}]))
        claims-before @claims
        [channel fresh?] (#'engine/channel-for! eng [:TAA] :a-16th-distinct-timbre {})]
    (is (nil? channel) "no MIDI channel assigned -- send-midi-on!/off! already treat nil as silent")
    (is (false? fresh?))
    (is (= claims-before @claims)
        "an exhausted voice's own claim attempt must not disturb the 15 real claims")
    (is (nil? (get @(:voice-channels eng) [:TAA]))
        "nothing recorded for the voice, so its next note tries to claim again")))

(deftest channel-for-self-heals-once-a-channel-frees-up
  (let [eng       (engine/engine nil (atom {}) :ROOT)
        claims    (:channel-claims eng)
        used-keys (mapv (fn [i] [i {}]) (range 15))
        _         (doseq [k used-keys] (#'engine/claim-channel! claims k))
        exhausted (#'engine/channel-for! eng [:TAA] :still-locked-out {})]
    (is (nil? (first exhausted)) "pool is genuinely full, first attempt goes silent")
    ;; free up one of the 15 real claims (as if that voice finished/moved on)
    (#'engine/release-channel! claims (ffirst used-keys))
    (let [[channel fresh?] (#'engine/channel-for! eng [:TAA] :still-locked-out {})]
      (is (some? channel)
          "the exhausted voice's very next note retries claiming and succeeds now that a channel is free")
      (is (true? fresh?))
      (is (= [channel [:still-locked-out {}]] (get @(:voice-channels eng) [:TAA]))))))

;; ============================================================
;; display -- greedy, synchronous realization (no core.async, no engine)
;; ============================================================

(deftest display-resolves-a-simple-sequence
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        n2    (d/leaf :n2 (c/context) 1/4 [62])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1 n2]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [steps (compose/display-timed (repo/registry) :verse)]
      (is (= 2 (count steps)))
      (is (= [[60] [62]] (mapv :pitches steps)))
      (is (= 0.0 (:onset (first steps))))
      (is (= (:dur-secs (first steps)) (:onset (second steps)))
          "second note's onset is right after the first's full duration"))))

(deftest display-needs-no-connect-or-live-engine
  ;; confirms display works directly against a plain repo/atom, with no
  ;; (connect)/(set-engine! ...) call at all -- it's pure data, no MIDI.
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (is (= [60] (:pitches (first (compose/display-timed (repo/registry) :verse)))))))

(deftest ramp-in-a-later-top-level-container-is-not-broken-by-earlier-material
  ;; Regression coverage: a container's own envelope is built at parse
  ;; time with LOCAL, zero-based time (walker's (duration
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
  ;; without musics.domain.context/ctx-shift rebasing it first.
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
    (let [steps (compose/display-timed (repo/registry) :block1 :block2)]
      (is (= [64 64 38 54 70 86] (mapv :velocity steps))
          "block1's own two notes at root's default volume, then block2's
           ramp interpolating from its own local start (30) toward 80 --
           velocities rescaled via musics.common.context-keys/volume->midi from
           those raw 0-100-scale volumes --
           not [55 68 80 80], which is what block2's own local envelope
           would read back if queried at block1's-duration-plus-its-own
           local time instead of being rebased to start fresh at 0"))))

(deftest display-forks-a-par-into-a-voices-marker
  ;; melody/bass carry real, hand-baked :pitch-sum/:pitch-n (what
  ;; builder/pop-container would bake at real parse time) --
  ;; #{} has no order of its own for realize-form-par to just preserve
  ;; the way a literal, ordered [:par ...] vector used to, so a real
  ;; mean-pitch-rank input is required here for the low-to-high voice
  ;; order this test asserts to be deterministic at all.
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
    (let [steps    (compose/display-timed (repo/registry) #{:melody :bass})
          par-step (first steps)]
      (is (= 1 (count steps)))
      (is (= :par (:kind par-step)))
      (is (= 2 (count (:voices par-step))))
      (is (= [60] (:pitches (first (first (:voices par-step))))))
      (is (= [67] (:pitches (first (second (:voices par-step)))))))))

(deftest display-honors-parallel-metadata-on-a-bare-seq
  ;; sq (musics.core) tags its own children-of-a-:PAR result {:parallel?
  ;; true} via metadata, since turning a container into a plain seq
  ;; (mapv'd children) leaves no data-level place left to carry a
  ;; :par/:seq tag the way a literal #{...} group has one. display/play
  ;; have to consult that metadata (form-tag+items) FIRST, ahead of the
  ;; vector-vs-set default -- otherwise a genuinely parallel container
  ;; silently plays back sequentially the moment it's passed through sq
  ;; (sq's own output is always a plain vector, never a set). Confirmed
  ;; live before this test existed: (compose/display tx (m/sq :chorale))
  ;; used to come back [:seq ...], not [:par ...] -- and this must keep
  ;; working exactly the same after the []=seq/#{}=par redesign, since
  ;; sq's own metadata answer is untouched by it.
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
    (let [children (d/children @(repo/registry) chorale)
          tagged   (with-meta children {:parallel? true})
          steps    (compose/display-timed (repo/registry) tagged)]
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
  (let [n1   (d/leaf :n1 (c/context) 1/4 [60])
        root {:type :ROOT :id :ROOT
              :context (c/context-root {"Tempo" 120 "volume" 80})
              :children []}]
    (repo/commit-node! :ROOT root)
    (is (= [60] (:pitches (first (compose/display-timed (repo/registry) n1)))))))

(deftest display-accepts-a-plain-list-the-same-as-a-vector-group
  ;; sequential? (not vector?-only) -- a LazySeq/list group (as cycle/take
  ;; would produce) plays identically to the equivalent vector -- both
  ;; default to :seq with no metadata now, whether vector or list, so
  ;; there's no longer a vector-vs-list DEFAULT distinction the way there
  ;; used to be (a literal [:par ...] vector doesn't exist anymore --
  ;; :par is spelled #{...} now, a shape a plain list can't produce).
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
    (let [via-vector (compose/display-timed (repo/registry) [:melody :bass])
          via-list   (compose/display-timed (repo/registry) (list :melody :bass))]
      (is (= via-vector via-list)
          "a list group resolves identically to the same vector group")
      (is (= [[60] [67]] (mapv :pitches via-vector))
          "both play :melody then :bass IN ORDER, sequentially -- not forked"))))

(deftest display-plays-a-cycled-take-of-resolved-children
  ;; The motivating end-to-end shape: (play (take n (cycle (sq id)))) --
  ;; here using a plain resolved-children vector directly (as `sq` itself
  ;; is just `children` plus metadata), fed through ordinary cycle/take.
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        n2     (d/leaf :n2 (c/context) 1/4 [62])
        n3     (d/leaf :n3 (c/context) 1/4 [64])
        verse  {:type :SEQ :id :verse :context (c/context) :children [n1 n2 n3]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [children (d/children @(repo/registry) verse)
          steps    (compose/display-timed (repo/registry) (take 5 (cycle children)))]
      (is (= [[60] [62] [64] [60] [62]] (mapv :pitches steps))))))

(deftest display-includes-mark-steps-for-barlines
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [(d/bar 2) n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 120 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [steps (compose/display-timed (repo/registry) :verse)]
      (is (= {:kind :mark :count 2} (first steps)))
      (is (= [60] (:pitches (second steps)))))))

(deftest display-expands-a-finite-iterator
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        source {:type :SEQ :id :s1 :context (c/context) :children [n1]}
        iter   (d/iterator :REPEAT :r1 (c/context) source {:count 3})
        verse  {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [steps (compose/display-timed (repo/registry) :verse)]
      (is (= 3 (count steps)))
      (is (apply < (map :onset steps))
          "each pass starts strictly after the previous one finished"))))

(deftest display-throws-on-infinite-iterator
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        source {:type :SEQ :id :s1 :context (c/context) :children [n1]}
        iter   (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
        verse  {:type :SEQ :id :verse :context (c/context) :children [iter]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (is (thrown? clojure.lang.ExceptionInfo (compose/display-timed (repo/registry) :verse)))))

(deftest display-continues-after-a-par
  ;; A :SEQ sibling right after a :PAR starts after the :PAR's children,
  ;; as play-par's continue-after-fork! does.
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
    (let [[a-step par-step b-step] (compose/display-timed (repo/registry) :verse)
          x-onset (:onset (first (first (:voices par-step))))]
      (is (= [60] (:pitches a-step)))
      (is (= :par (:kind par-step)))
      (is (= [72] (:pitches b-step)))
      (is (< (Math/abs (- (+ x-onset 0.5) (:onset b-step))) 1e-9)
          "b starts a quarter (0.5s at 120) after x/y did"))))

;; ============================================================
;; play -- a clean error for an id that doesn't resolve, not an NPE
;; ============================================================

(deftest play-throws-a-clear-error-for-an-unresolvable-id
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (swap! (:voices eng) assoc [:already-playing] {:birth-token :sentinel})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No part found for id :bogus"
              (engine/play :bogus)))
        (is (= {:birth-token :sentinel} (get @(:voices eng) [:already-playing]))
            "a rejected play call never wipes eng's :voices registry --
             validate-ids! runs before play's own pre-fn (the '(reset!
             (:voices eng) {})' that implements 'flush everything'), so a
             typo'd id can't supersede whatever is already playing")))))


(deftest play-returns-at-once-for-an-endless-seq
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}]
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (try
          (is (= :TAA (deref (future (engine/play (cycle [:verse]))) 5000 :timeout))
              "validation checks only a prefix of an uncounted seq")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No part found for id :bogus"
                (engine/play (cycle [:verse :bogus]))))
          (finally (engine/stop! eng)))))))

(deftest play-throws-a-clear-error-for-a-nonsense-form
  ;; validate-ids! -- not play-form's own analogous :else branch, which
  ;; runs inside a go block and can't usefully throw (see its own
  ;; comment: a throw there is swallowed by core.async, confirmed live
  ;; -- (<!! ch) on a go block that throws just returns nil) -- is what
  ;; has to reject nil specifically. nil is the concrete, real-world
  ;; case: sq (musics.core) returns nil for an id that doesn't resolve
  ;; to a container at all (a typo, or an id that's a leaf rather than
  ;; a composite). Used to silently no-op -- no sound, no error --
  ;; confirmed live before this test existed. Deliberately narrower
  ;; than "reject anything non-keyword/non-sequential" (an earlier,
  ;; broader version of this guard did that, and broke real material
  ;; containing an inline :assignment node -- see
  ;; display-tolerates-an-inline-assignment-node-in-bare-material).
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"don't know how to play"
              (engine/play nil)))))))

(deftest play-tolerates-an-inline-assignment-node-in-bare-material
  ;; The exact scenario reported live: (play (times N (sq :verse))) on
  ;; material containing an inline !instrument:/!tempo:/!mf-style
  ;; :assignment node used to throw at validate-ids! (an earlier,
  ;; too-broad version of the nonsense-form guard), even though
  ;; (play :verse) directly never did. validate-ids! must let this
  ;; through, same as it always let a real id's own children through.
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        assign {:type :assignment :key :i :val 32 :raw "!i:32"}
        root   {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (is (keyword? (engine/play (with-meta [assign n1] {:parallel? false})))
            "no throw -- returns a fresh track id, same as play always does
             on success; the assignment node is silently tolerated, same as
             play-node already tolerates one during an ordinary container walk")))))

(deftest display-throws-a-clear-error-for-a-nonsense-form
  ;; display has no validate-ids! pass of its own (fully synchronous,
  ;; no go block involved at all) -- realize-form's own :else has to
  ;; carry this instead, and can, since nothing here runs async.
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"don't know how to play"
          (compose/display-timed (repo/registry) nil)))))

(deftest display-tolerates-an-inline-assignment-node-in-bare-material
  ;; Real regression, caught live: sq (musics.core) hands back a
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
  (let [n1     (d/leaf :n1 (c/context) 1/4 [60])
        assign {:type :assignment :key :i :val 32 :raw "!i:32"}
        verse  {:type :SEQ :id :verse :context (c/context) :children [assign n1]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 120 "volume" 80})
                :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [material (with-meta [assign n1] {:parallel? false :id :verse})
          steps    (compose/display-timed (repo/registry) material)]
      (is (= 1 (count steps)) "the assignment node contributes no step of its own")
      (is (= [60] (:pitches (first steps)))))))

;; ============================================================
;; assign-algo!/algo-assignments/play/play-add
;; ============================================================

(deftest assign-algo-and-algo-assignments-round-trip
  (let [root {:type :ROOT :id :ROOT :context (c/context-root {}) :children []}]
    (repo/commit-node! :ROOT root)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (wall/build-algo! ::retro (fn [nodes _ctx _voice] nodes))
      (engine/assign-algo! eng :bass ::retro)
      (is (= {[:bass] ::retro} (engine/algo-assignments eng))
          "a bare keyword path reads back wrapped the same way voice-at/->path treat it")
      (engine/assign-algo! eng :bass nil)
      (is (= {[:bass] nil} (engine/algo-assignments eng))
          "nil clears an assignment back to identity, not to 'unassigned'"))))

(deftest play-mints-a-short-track-id-and-assigns-the-algorithm
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::retro2 (fn [nodes _ctx _voice] nodes))
        (let [id (engine/play :verse :algo ::retro2)]
          (is (= :TAA id) "the first minted track id, deterministically")
          (is (some? (engine/voice-at eng id))
              "the voice is registered synchronously, before play returns")
          (is (= ::retro2 (:algo (engine/voice-at eng id)))
              "the algorithm is baked onto the voice before its first node runs"))))))

(deftest play-with-no-algo-marker-picks-up-a-prepared-assignment-and-keeps-it
  ;; play always mints the SAME id (:TAA) right after its own flush.
  ;; assign-algo! prepares a path ahead of any mint (writes into
  ;; :algo-prepared, never touched by play's own flush); an untagged
  ;; play/play-add call that mints into that path picks the prepared
  ;; name up (mint-leaf!'s own (or algo prepared) fallback), and since
  ;; nothing ever CONSUMES a prep entry, a second untagged mint into
  ;; the SAME path picks it up again too -- it stays in effect until
  ;; assign-algo! is called again to change or clear it. A call-level
  ;; :algo tag, by contrast, is NOT written back into :algo-prepared --
  ;; it only ever bakes onto the one voice this call itself mints (see
  ;; play-mints-a-short-track-id-and-assigns-the-algorithm above)."
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::retro2c (fn [nodes _ctx _voice] nodes))
        (engine/assign-algo! eng [:TAA] ::retro2c)
        (engine/play :verse)
        (is (= ::retro2c (:algo (engine/voice-at eng :TAA)))
            "an untagged mint into :TAA picks up the prepared name")
        (engine/play :verse)
        (is (= ::retro2c (:algo (engine/voice-at eng :TAA)))
            "a SECOND untagged mint (after play's own flush) still picks it up --
             a prep entry isn't consumed, only explicitly changed/cleared")))))

(deftest play-untagged-single-item-vector-is-an-ordinary-one-item-seq-group
  ;; A plain 1-element vector is unambiguously an ordinary [] sequential
  ;; group now -- vector is ALWAYS :seq, never subject to any
  ;; :algo-marker guessing -- unlike the old scheme, where a bare
  ;; single-item vector had to be deliberately distinguished from an
  ;; [:algo name] marker. tagged-form? requires exactly 3 elements with
  ;; :algo at index 1, so a 1-element vector was never even a candidate.
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (engine/play [:verse])
        (is (= {} (engine/algo-assignments eng))
            "[:verse] is ordinary play material -- no :algo tag, so no entry is written and :TAA stays identity")))))

(deftest play-flushes-everything-first
  ;; A voice already registered anywhere (even at a path play never
  ;; touches directly) is gone after play runs; and since the flush
  ;; always runs first, a solo call deterministically lands on :TAA --
  ;; there is never anything left over from a PRIOR play call to skip.
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (swap! (:voices eng) assoc [:some-other-path] {:birth-token :sentinel})
        (let [id (engine/play :verse)]
          (is (= :TAA id) "the flush ran before minting, so :TAA was free")
          (is (nil? (get @(:voices eng) [:some-other-path]))
              "whatever was already registered got wiped, same as play's own flush"))))))

;; ============================================================
;; play-add -- play's own never-flushes alternative
;; ============================================================

(deftest play-add-mints-a-short-track-id-and-assigns-the-algorithm
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::retro3 (fn [nodes _ctx _voice] nodes))
        (let [id (engine/play-add :verse :algo ::retro3)]
          (is (= :TAA id) "the first minted track id, deterministically")
          (is (some? (engine/voice-at eng id))
              "the voice is registered synchronously, before play-add returns")
          (is (= ::retro3 (:algo (engine/voice-at eng id)))
              "the algorithm is baked onto the voice before its first node runs"))))))

(deftest play-add-does-not-flush-other-voices
  ;; The opposite of play's own flush test: whatever's already
  ;; registered survives a play-add call untouched, and a SECOND
  ;; play-add call (unlike a second play call) does NOT reuse :TAA,
  ;; since the first one is still occupying it.
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (swap! (:voices eng) assoc [:some-other-path] {:birth-token :sentinel})
        (let [id1 (engine/play-add :verse)
              id2 (engine/play-add :verse)]
          (is (= :TAA id1))
          (is (= :TAB id2) "TAA is still occupied by the first voice, so a fresh id is minted")
          (is (some? (get @(:voices eng) [:some-other-path]))
              "the unrelated voice was never touched"))))))

;; ============================================================
;; :PAR children get mean-pitch-ranked track-id path segments
;; ============================================================

(deftest par-children-get-mean-pitch-ranked-track-ids
  ;; :verse lists :high BEFORE :low -- proving the ranking is by pitch,
  ;; not by written/positional order (the old child-segment behavior
  ;; this replaces would have put :high at index 0, :low at index 1).
  (let [hi    (d/leaf :hi (c/context) 1/4 [80])
        lo    (d/leaf :lo (c/context) 1/4 [40])
        ;; Hand-built fixtures bypass the real parser, so :pitch-sum/
        ;; :pitch-n (normally baked at pop-container time, see
        ;; builder) have to be baked here too, the same way,
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
    (let [eng   (engine/engine nil (repo/registry) :ROOT)
          hi-p  (promise)
          lo-p  (promise)]
      (binding [engine/*engine* eng]
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
              "lower pitch, listed SECOND, gets :TAA -- the lowest"))))))

;; ============================================================
;; New play-arg mini-language -- []=seq/#{}=par, [Form :algo Name] tags,
;; recursive #{}-mirroring return shape
;; ============================================================

(deftest par-branches-get-their-own-individual-algo-tags
  ;; #{[:high :algo ...] [:low :algo ...]} -- each branch's own tag is
  ;; assign-algo!'d onto ITS OWN freshly-forked (here: freshly-minted
  ;; top-level) path before that voice's first node runs -- the
  ;; motivating case for tagging in the first place.
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
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::hi-algo (fn [nodes _ctx _voice] nodes))
        (wall/build-algo! ::lo-algo (fn [nodes _ctx _voice] nodes))
        (let [ids (engine/play #{[:high :algo ::hi-algo] [:low :algo ::lo-algo]})]
          (is (= #{:TAA :TAB} ids) "one flat id per branch, no wrapping parent")
          (is (= ::lo-algo (:algo (engine/voice-at eng :TAA)))
              "lowest pitch lands on :TAA, and keeps ITS OWN tag -- :low's, not :high's")
          (is (= ::hi-algo (:algo (engine/voice-at eng :TAB)))
              "highest pitch lands on :TAB, with ITS OWN tag"))))))

;; ============================================================
;; par -- a #{}-equivalent that also accepts the same Form more than
;; once, since it's a vector tagged :parallel? in its own metadata
;; rather than a literal Clojure set (which can't hold two = values at
;; all -- a genuine reader error, not just discouraged).
;; ============================================================

(deftest par-of-distinct-forms-behaves-identically-to-a-literal-set
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
    (is (= (compose/display-timed (repo/registry) #{:high :low})
           (compose/display-timed (repo/registry) (compose/par :high :low)))
        "par with genuinely distinct branches previews identically to the
         equivalent literal #{...} -- par doesn't change anything about
         the common case, it only adds what #{} structurally can't do")))

(deftest par-mints-two-real-voices-for-the-same-id-written-twice
  ;; #{:melody :melody} is a reader error before this code even runs --
  ;; (par :melody :melody) is a plain vector, no such restriction.
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse0 {:type :SEQ :id :verse :context (c/context) :children [n1]}
        verse  (d/set-container-pitch-stats verse0 (d/pitch-stats nil verse0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (let [ids (engine/play (compose/par :verse :verse))]
          (is (= #{:TAA :TAB} ids)
              "two genuinely distinct voices minted, both playing the SAME
               underlying :verse content, at two different track ids"))))))

(deftest par-branches-can-share-the-identical-algo-tag
  ;; #{[:verse :algo ::same] [:verse :algo ::same]} is ALSO a reader
  ;; error -- two structurally-identical tag vectors are = to each
  ;; other, so even distinguishing branches by algo doesn't save a
  ;; literal set when the algo itself is meant to be the same on both
  ;; (e.g. two offset copies of one phrase running the same transform --
  ;; the real motivating case, not a contrived one).
  (let [n1    (d/leaf :n1 (c/context) 1/4 [60])
        verse0 {:type :SEQ :id :verse :context (c/context) :children [n1]}
        verse  (d/set-container-pitch-stats verse0 (d/pitch-stats nil verse0))
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::same-algo (fn [nodes _ctx _voice] nodes))
        (let [ids (engine/play (compose/par [:verse :algo ::same-algo]
                                            [:verse :algo ::same-algo]))]
          (is (= #{:TAA :TAB} ids) "two distinct voices, not collapsed into one")
          (is (= ::same-algo (:algo (engine/voice-at eng :TAA))))
          (is (= ::same-algo (:algo (engine/voice-at eng :TAB)))
              "both really did get the identical algo -- the whole point"))))))

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
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (let [ids (engine/play #{:melody #{:a :b}})]
          (is (= #{:TAA #{:TAB :TAC}} ids)
              "every voice/track gets an id, not subparts -- no id spent on
               the nested #{}'s own wrapping")
          (is (every? #(some? (engine/voice-at eng %)) [:TAA :TAB :TAC])
              "all three ids are real, independently addressable top-level voices"))))))

(deftest tagged-vector-member-pushes-then-restores-to-the-enclosing-algo
  ;; A tag inside an ongoing [] walk (play-form-tagged's non-#{} branch)
  ;; temporarily reassigns the CURRENT voice's own path, then restores
  ;; whatever was there before -- not unconditionally to identity -- so
  ;; nesting composes: a tag nested inside an already-tagged outer span
  ;; falls back to the OUTER tag afterward, not identity.
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
    (let [eng  (engine/engine nil (repo/registry) :ROOT)
          done (promise)]
      (binding [engine/*engine* eng]
        (wall/build-algo! ::outer-log (fn [nodes _ctx _voice] (swap! log conj :outer) nodes))
        (wall/build-algo! ::inner-log (fn [nodes _ctx _voice] (swap! log conj :inner) nodes))
        (conductor/register-action! :after-exit (fn [_] (deliver done true)))
        (conductor/schedule! :after :exit :after-exit)
        (engine/play [:before [:middle :algo ::inner-log] :after] :algo ::outer-log)
        (deref done 2000 :timeout)
        (is (= [:outer :inner :outer]
               (map first (partition-by identity @log)))
            "before -> outer, middle -> inner (tagged), after -> outer again
             (restored, not identity) -- consecutive repeats within one
             container-then-leaf apply-algo pass collapsed via partition-by,
             order/identity is what's under test, not call count")))))

(defn- verse-fixture!
  "Shared one-part fixture (:verse, a single 1/32 leaf) for the
   parameterized-algo tests below -- none of them care about the
   material itself, only what ends up as the minted voice's own :algo.
   Does NOT
   set-engine!/bind eng itself -- a helper fn returns before any test
   body runs, so any binding scope started here would already be
   closed by the time the caller does anything; each caller wraps its
   OWN remaining body in (binding [engine/*engine* eng] ...) instead."
  [_eng]
  (let [n1    (d/leaf :n1 (c/context) 1/32 [60])
        verse {:type :SEQ :id :verse :context (c/context) :children [n1]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 240 "volume" 80})
               :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse)))

(deftest a-built-parameterized-algo-applies-the-args-it-was-built-with
  ;; Applying a factory to args is no longer something a play call's own
  ;; :algo tag does inline (see musics.wall's ns docstring on the
  ;; 2026-09-09 redesign) -- the factory is called directly, with its
  ;; own explicit target name, BEFORE play ever runs; the tag then just
  ;; references that already-built, bare name, same as any other.
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (verse-fixture! eng)
    (binding [engine/*engine* eng]
      (let [mark-n (fn [name n] (wall/build-algo! name (fn [nodes _ctx _voice] (map #(assoc % :marked n) nodes))))]
        (mark-n ::marked-5 5)
        (engine/play :verse :algo ::marked-5)
        (let [resolved (wall/algo (:algo (engine/voice-at eng [:TAA])))]
          (is (fn? resolved) "the tag resolved to a real, built fn")
          (is (= [{:marked 5}] (resolved [{}] [] nil))
              "the factory's own args (5) were actually baked into the built wall fn"))))))

(deftest doubling-algo-fn-invoked-exactly-three-times-not-unboundedly
  ;; Regression test for the safety property play-leaves' own docstring
  ;; describes (and musics.wall's ns docstring/register-algo!'s docstring
  ;; now explain to a algo-fn author, not just this internal comment): a
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
  ;; verse-fixture!-using neighbors above, which only ever check the
  ;; minted voice's own :algo synchronously right after play (set at
  ;; minting time, before any voice's own go-block runs) and never actually wait
  ;; on completion at all. musics.conductor's tables are process-wide
  ;; globals (deliberately, see musics.repo/core.wall/core.conductor's own
  ;; single-session design) -- a still-unwinding voice left over from
  ;; a DIFFERENT, already-finished test, one that also happened to use
  ;; the common :verse/:done names, can otherwise deliver this test's
  ;; own `done` promise early. Confirmed live: this exact test flaked
  ;; against verse-fixture!/:done when run as part of the full suite,
  ;; passing in isolation -- a uniquely namespaced container id and
  ;; action-id removes the collision instead of chasing the timing.
  (let [n1     (d/leaf :n1 (c/context) 1/32 [60])
        verse  {:type :SEQ :id ::doubler-verse :context (c/context) :children [n1]}
        root   {:type :ROOT :id :ROOT
                :context (c/context-root {"Tempo" 240 "volume" 80})
                :children [::doubler-verse]}
        eng    (engine/engine nil (repo/registry) :ROOT)
        calls  (atom [])
        double (fn [nodes _ctx _voice]
                 (swap! calls conj (count nodes))
                 (mapcat (fn [n] [n n]) nodes))]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! ::doubler-verse verse)
    (binding [engine/*engine* eng]
      (wall/build-algo! ::doubler double)
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
        ;; speculative dry-walk (see engine.clj's own "Look-ahead"
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
             a genuine unbounded-redispatch regression would produce")))))

;; A bad :algo tag on a `play` call throws immediately, at the call
;; itself, before any voice starts -- matching play's own long-standing
;; treatment of a bad id (see play-throws-a-clear-error-for-an-
;; unresolvable-id above). musics.wall/algo ITSELF still degrades
;; silently to identity (a call reached from inside an already-
;; running voice's own go-block, e.g. a tag nested mid-[] via
;; play-form-tagged, can't safely throw -- see that fn's own docstring)
;; -- these tests cover the pre-flight guard (validate-algo-name!,
;; called from validate-ids!/play-top-level!/play-change), which is
;; what actually gives a mistyped play-call-level :algo tag a loud,
;; immediate failure instead of a console-only warning.

(deftest bare-unregistered-algo-name-throws-before-playing
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (verse-fixture! eng)
    (binding [engine/*engine* eng]
      (swap! (:voices eng) assoc [:already-playing] {:birth-token :sentinel})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered name"
            (engine/play :verse :algo ::still-totally-unregistered)))
      (is (= {:birth-token :sentinel} (get @(:voices eng) [:already-playing]))
          "a rejected :algo tag never wipes eng's :voices registry, same
           invariant a rejected id already has -- validate-algo-name! runs
           before play's own pre-fn")
      (is (nil? (engine/voice-at eng [:TAA]))
          "no voice was ever minted for a call that never actually played"))))

(deftest assign-algo-directly-still-degrades-silently-to-identity
  ;; assign-algo! called DIRECTLY (not via a play call's own :algo tag)
  ;; only ever writes into :algo-prepared, a FUTURE-mint-only table as
  ;; of the 2026-09-10 redesign -- it never reaches an already-playing
  ;; voice at all anymore (that voice's own :algo is immutable once
  ;; minted). A typo'd Name prepared this way still stores as-is
  ;; (nothing resolves it at assign-algo! time), and still resolves to
  ;; plays unchanged (identity), not an exception, the moment something
  ;; actually reads it -- validate-algo-name! is what guards a PLAY-time
  ;; tag instead.
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (binding [engine/*engine* eng]
      (engine/assign-algo! eng [:TAA] ::yet-another-unregistered-name)
      (is (= ::yet-another-unregistered-name (get @(:algo-prepared eng) [:TAA]))
          "the bare Name is stored as-is, whether or not it currently resolves")
      (is (= [{:n 1}] (wall/apply-algo (wall/algo (get @(:algo-prepared eng) [:TAA])) [] nil [{:n 1}]))
          "but applying it right now is the identity"))))

(deftest sq-parallel-metadata-still-wins-over-a-plain-untagged-vector
  ;; Regression check: sq's own {:parallel? true/false} metadata must
  ;; keep winning FIRST in form-tag+items, untouched by the []=seq/
  ;; #{}=par redesign -- sq's own output is always a plain vector, never
  ;; a set, so if the metadata branch were ever skipped a genuinely
  ;; parallel container would silently play back sequentially (vectors
  ;; are always :seq now with no metadata present).
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
    (let [children (d/children @(repo/registry) chorale)
          sq-like  (with-meta children {:parallel? true})]
      (is (vector? sq-like) "sq's own output shape -- a plain vector, never a set")
      (let [[tag _] (#'compose/form-tag+items sq-like)]
        (is (= :par tag)
            "metadata wins over the vector's own now-always-:seq default")))))

(deftest playback-through-nested-seqs-crosses-every-bar
  ;; Namespaced ids: musics.conductor's tables are shared, so a common name
  ;; could be triggered by another test's voice still unwinding.
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
    (let [eng  (engine/engine nil (repo/registry) :ROOT)
          done (promise)
          bar3 (promise)]
      (binding [engine/*engine* eng]
        (conductor/register-action! ::lookahead-e2e-done (fn [event] (deliver done event)))
        (conductor/register-action! ::lookahead-e2e-bar3 (fn [event] (deliver bar3 event)))
        (conductor/schedule! ::lookahead-e2e-verse :exit ::lookahead-e2e-done)
        (conductor/schedule! 3 :enter ::lookahead-e2e-bar3)
        (engine/play ::lookahead-e2e-verse)
        (is (map? (deref done 2000 :timeout))
            "played through both nested bars to the verse's own :exit")
        (is (= [:TAA] (:path (:voice (deref bar3 100 :timeout))))
            "8 quarters in 4/4 carried the voice into bar 3")))))

(deftest a-seq-after-a-par-continues-where-the-longest-branch-ended
  ;; [ {[c d] [e]} g ] -- g's clock continues after the whole block;
  ;; left at the block's start, g's deadline was already past, so it was
  ;; released the instant it sounded
  (let [q     #(d/leaf %1 (c/context) 1/4 [%2])
        s1    {:type :SEQ :id :s1 :context (c/context) :children [(q :c 60) (q :d 62)]}
        s2    {:type :SEQ :id :s2 :context (c/context) :children [(q :e 64)]}
        p1    {:type :PAR :id :p1 :context (c/context) :children [:s1 :s2]}
        verse {:type :SEQ :id :verse :context (c/context) :children [:p1 (q :g 67)]}
        root  {:type :ROOT :id :ROOT
               :context (c/context-root {"Tempo" 600 "volume" 80}) ; a quarter = 100ms
               :children [:verse]}
        g-on  (promise)
        g-off (promise)]
    (repo/commit-many! {:ROOT root :verse verse :p1 p1 :s1 s1 :s2 s2})
    ;; fs is a token: with-redefs reaches every engine's thread
    (with-redefs [engine/send-midi-on!  (fn [fs ev _] (when (and (= fs ::fs) (= [67] (:pitches ev))) (deliver g-on (System/nanoTime))))
                  engine/send-midi-off! (fn [fs ev] (when (and (= fs ::fs) (= [67] (:pitches ev))) (deliver g-off (System/nanoTime))))]
      (binding [engine/*engine* (engine/engine ::fs (repo/registry) :ROOT)]
        (engine/play :verse)
        (let [held-ms (/ (- (deref g-off 2000 0) (deref g-on 2000 0)) 1e6)]
          (is (< 60 held-ms 200) (str "g held " held-ms "ms, not ~90ms")))))))
