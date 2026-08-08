(ns ^:repl pipeline-test
  "Not just a test -- a runnable walkthrough of the whole pipeline this
   project is built around: read text -> stage -> commit -> play, then
   mutate -> stage -> commit -> move playback over to the new commit,
   and confirm what's actually playing changed only when you told it to.

   Both variants below use the same two equal-length sequences (melody/
   bass) and the same mutation (a new melody); they differ only in *how*
   the tx-cutover step happens:
     - pipeline-direct-tx-cutover     -- call play-latest! yourself, right
                                          now (fast: an instant jump).
     - pipeline-scheduled-tx-cutover  -- core.conductor/schedule-tx! it in
                                          advance and let real playback
                                          trigger it once it reaches a
                                          chosen boundary (deliberate:
                                          \"commit now, cut over whenever
                                          we get there\").

   (In a real REPL session you'd use (musics/reset) for the first step
   below; these use core.repo/reset-all! directly instead, matching the
   rest of the test suite's convention of not printing/disconnecting."
  (:require [clojure.test :refer [deftest is]]
            [musics :as m]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.async-engine :as engine]
            [input.reader.flat-core-builder :as flat]))

(defn- reset-everything! []
  ;; core.repo's registry/staging/play-tx (and conductor's own registry/
  ;; schedule) are defonce'd, shared across the whole test namespace --
  ;; reset-all! wipes even :ROOT, so it has to be re-committed before any
  ;; (parse ...) can build on it (matches musics.clj's own bootstrap).
  (repo/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! m/session {:auto-ids {}})
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {}))

;; ============================================================
;; Fast / direct: cut playback over the instant you call play-latest!
;; ============================================================

(deftest pipeline-direct-tx-cutover
  (reset-everything!)

  ;; 1. Read two equal-length sequences as ONE atomic staged batch --
  ;;    a single (parse ...) call can define more than one named part;
  ;;    they land under one sid and commit together or not at all.
  (let [{:keys [sid ids]} (m/parse "{melody: c4 d e f} {bass: c,4 c c c}")]
    (is (= #{:melody :bass} ids))
    (is (nil? (m/find :melody)) "staged, not yet visible")

    ;; 2. Commit, then explicitly point playback at it -- committing
    ;;    alone never moves what's playing (see musics.clj/commit!).
    (let [tx1                (m/commit! sid)
          original-pitches   (mapv (comp first :pitches) (m/children :melody tx1))]
      (m/play-latest!)
      (is (= tx1 @repo/play-tx))

      (let [eng (engine/engine nil repo/play-tx :ROOT)]
        (engine/set-engine! eng)

        ;; 3. Play the two equal-length sequences in parallel, and wait
        ;;    for this first pass to actually finish (via melody's own
        ;;    :section :exit signal) before moving on.
        (let [first-pass-done (promise)]
          (conductor/register-action! :first-done (fn [_] (deliver first-pass-done true)))
          (conductor/schedule! :melody :exit :first-done)
          (engine/play [:par :melody :bass])
          (is (= true (deref first-pass-done 4000 :timeout))
              "first pass through melody/bass finished playing"))

        ;; 4. Mutate melody -- parse+commit a redefinition under the
        ;;    same id, same length. (Pitches are captured from what was
        ;;    actually committed, not hardcoded -- this test is about the
        ;;    pipeline mechanics, not pitch-resolution arithmetic.)
        (let [{:keys [sid]} (m/parse "{melody: g4 f e d}")
              tx2           (m/commit! sid)
              mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))]
          (is (not= tx1 tx2))
          (is (not= original-pitches mutated-pitches)
              "the mutation actually changed melody's content")
          (is (= tx1 @repo/play-tx)
              "commit! left play-tx untouched -- still on the original tx")
          (is (= original-pitches (mapv (comp first :pitches) (m/children :melody @repo/play-tx)))
              "what's actually playing still has the original melody")

          ;; 5. The tx-increment step, done directly: cut playback over
          ;;    to the new commit right now, no waiting.
          (m/play-latest!)
          (is (= tx2 @repo/play-tx) "play-latest! moved playback to the new commit")
          (is (= mutated-pitches (mapv (comp first :pitches) (m/children :melody @repo/play-tx)))
              "playback now sees the mutated melody")

          ;; 6. Play melody again and confirm it really is the mutated
          ;;    version that gets performed this time (re-schedule the
          ;;    same [:melody :exit] slot -- it's one-shot, consumed by
          ;;    step 3 already).
          (let [second-pass-done (promise)]
            (conductor/register-action! :second-done (fn [_] (deliver second-pass-done true)))
            (conductor/schedule! :melody :exit :second-done)
            (engine/play :melody)
            (is (= true (deref second-pass-done 4000 :timeout))
                "second pass (the mutated melody) finished playing too")))))))

;; ============================================================
;; Deliberate / scheduled: prepare the cutover, let playback trigger it
;; ============================================================

(deftest pipeline-scheduled-tx-cutover
  (reset-everything!)

  ;; 1. Same two equal-length sequences, same atomic batch.
  (let [{:keys [sid]}    (m/parse "{melody: c4 d e f} {bass: c,4 c c c}")
        tx1              (m/commit! sid)
        original-pitches (mapv (comp first :pitches) (m/children :melody tx1))]
    (m/play-latest!)

    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (engine/set-engine! eng)

      ;; 2. Mutate melody -- parse+commit -- but this time do NOT touch
      ;;    play-tx ourselves at all.
      (let [{:keys [sid]}   (m/parse "{melody: g4 f e d}")
            tx2             (m/commit! sid)
            mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))]
        (is (not= original-pitches mutated-pitches)
            "the mutation actually changed melody's content")
        (is (= tx1 @repo/play-tx) "still on the original tx after committing")

        ;; 3. Schedule the cutover for the moment melody's section next
        ;;    exits. :latest resolves at the moment this actually fires,
        ;;    not when it was scheduled -- see schedule-tx!'s docstring.
        (m/schedule-tx! :melody :exit :latest)

        ;; 4. Play the parallel pass -- melody plays its ORIGINAL content
        ;;    (play-tx is still tx1 when this pass starts), and the
        ;;    scheduled cutover fires automatically right as melody's
        ;;    :SEQ exits, with no further action from us. (We watch
        ;;    :bass's own exit instead of :melody's here, since
        ;;    schedule-tx! already claimed the [:melody :exit] slot --
        ;;    one action per boundary; melody and bass are equal length
        ;;    and start together, so bass's exit lands at the same
        ;;    moment.)
        (let [pass-done (promise)]
          (conductor/register-action! :pass-done (fn [_] (deliver pass-done true)))
          (conductor/schedule! :bass :exit :pass-done)
          (engine/play [:par :melody :bass])
          (is (= true (deref pass-done 4000 :timeout)) "the parallel pass finished"))

        ;; melody and bass fork into independent go-blocks at :PAR (see
        ;; play-par), each firing its own :exit signal from its own
        ;; goroutine -- structurally simultaneous, but core.async gives no
        ;; ordering guarantee between two *different* voices' callbacks
        ;; completing, only within one voice's own sequential steps. So
        ;; bass's pass-done landing doesn't guarantee melody's own :exit
        ;; (and the tx-cutover action scheduled on it) has *already* run --
        ;; poll briefly instead of asserting the instant pass-done resolves.
        (let [deadline (+ (System/currentTimeMillis) 2000)]
          (while (and (not= tx2 @repo/play-tx) (< (System/currentTimeMillis) deadline))
            (Thread/sleep 5)))
        (is (= tx2 @repo/play-tx)
            "the scheduled cutover fired on its own once melody's section exited")

        ;; 5. A follow-up pass now genuinely performs the mutated melody
        ;;    -- we never called play-tx!/play-latest! ourselves.
        (is (= mutated-pitches (mapv (comp first :pitches) (m/children :melody @repo/play-tx)))
            "playback now sees the mutated melody")))))
