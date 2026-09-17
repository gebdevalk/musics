(ns ^:repl pipeline-test
  "Not just a test -- a runnable walkthrough of the whole pipeline this
   project is built around: read text -> commit (immediate) -> play, then
   mutate -> commit -> move playback over to the new commit, and confirm
   what's actually playing changed only when you told it to.

   Both variants below use the same two equal-length sequences (melody/
   bass) and the same mutation (a new melody); they differ only in *how*
   the tx-cutover step happens:
     - pipeline-direct-tx-cutover     -- call play-latest! yourself, right
                                          now (fast: an instant jump).
     - pipeline-scheduled-tx-cutover  -- core.async-engine/schedule-tx!
                                          it in advance and let real
                                          playback trigger it once it
                                          reaches a chosen boundary
                                          (deliberate: \"commit now, cut
                                          over whenever we get there\").

   (In a real REPL session you'd use (musics/reset) for the first step
   below; these use test-support/with-fresh-session (which seeds :ROOT
   the same way musics.core/reset itself does) inside its own isolated
   binding instead, matching the rest of the test suite's convention of
   not printing/disconnecting AND of not leaking state across test
   namespaces."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-session]]
            [musics.core :as m]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.async-engine :as engine]))

(defn- reset-everything! []
  ;; Registry/repo isolation (incl. :ROOT seeding) is handled by
  ;; with-fresh-session, which wraps every test body -- this fn now only
  ;; resets musics.core's OWN session atom (:auto-ids/:var-map), which is
  ;; a plain defonce, not one of core.registries' ^:dynamic vars, so
  ;; with-fresh-session's own binding never touches it.
  (reset! m/session {:auto-ids {}}))

;; ============================================================
;; Fast / direct: cut playback over the instant you call play-latest!
;; ============================================================

(deftest pipeline-direct-tx-cutover
 (with-fresh-session
  (reset-everything!)

  ;; 1. Read two equal-length sequences as ONE atomic committed batch --
  ;;    a single (parse ...) call can define more than one named part;
  ;;    they land under one tx and commit together, immediately.
  (let [{:keys [tx ids]} (m/parse "[melody: c4 d e f] [bass: c,4 c c c]")]
    (is (= [:melody :bass] ids))
    (is (some? (m/find :melody)) "visible immediately, parse already committed it")

    ;; 2. Explicitly point playback at it -- committing alone never
    ;;    moves what's playing (see musics.core/parse's own docstring).
    (let [tx1                tx
          original-pitches   (mapv (comp first :pitches) (m/children :melody tx1))]
      (m/play-latest!)
      (is (= tx1 @repo/play-tx))

      (let [eng (engine/engine nil repo/play-tx :ROOT)]
        (binding [engine/*engine* eng]

          ;; 3. Play the two equal-length sequences in parallel, and wait
          ;;    for this first pass to actually finish (via melody's own
          ;;    :section :exit signal) before moving on.
          (let [first-pass-done (promise)]
            (conductor/register-action! :first-done (fn [_] (deliver first-pass-done true)))
            (conductor/schedule! :melody :exit :first-done)
            (engine/play #{:melody :bass})
            (is (= true (deref first-pass-done 4000 :timeout))
                "first pass through melody/bass finished playing"))

          ;; 4. Mutate melody -- parse a redefinition under the same id,
          ;;    same length -- commits immediately. (Pitches are captured
          ;;    from what was actually committed, not hardcoded -- this
          ;;    test is about the pipeline mechanics, not pitch-resolution
          ;;    arithmetic.)
          (let [tx2             (:tx (m/parse "[melody: g4 f e d]"))
                mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))]
            (is (not= tx1 tx2))
            (is (not= original-pitches mutated-pitches)
                "the mutation actually changed melody's content")
            (is (= tx1 @repo/play-tx)
                "committing left play-tx untouched -- still on the original tx")
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
                  "second pass (the mutated melody) finished playing too")))))))))

;; ============================================================
;; Deliberate / scheduled: prepare the cutover, let playback trigger it
;; ============================================================

(deftest pipeline-scheduled-tx-cutover
 (with-fresh-session
  (reset-everything!)

  ;; 1. Same two equal-length sequences, same atomic commit.
  (let [tx1              (:tx (m/parse "[melody: c4 d e f] [bass: c,4 c c c]"))
        original-pitches (mapv (comp first :pitches) (m/children :melody tx1))]
    (m/play-latest!)

    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (binding [engine/*engine* eng]

        ;; 2. Mutate melody -- parse commits immediately -- but this time
        ;;    do NOT touch play-tx ourselves at all.
        (let [tx2             (:tx (m/parse "[melody: g4 f e d]"))
              mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))]
          (is (not= original-pitches mutated-pitches)
              "the mutation actually changed melody's content")
          (is (= tx1 @repo/play-tx) "still on the original tx after committing")

          ;; 3. Schedule the cutover for the moment melody's section next
          ;;    exits. :latest resolves at the moment this actually fires,
          ;;    not when it was scheduled -- see schedule-tx!'s docstring.
          ;;    schedule-tx! returns the action-id it registered the
          ;;    cutover under; register-action! is a plain overwrite
          ;;    (swap! action-registry assoc id f), so re-registering under
          ;;    that SAME id lets us layer a completion signal directly onto
          ;;    the real cutover action itself -- our wrapper calls the
          ;;    actual cutover fn, then delivers melody's own voice (from
          ;;    the same event the cutover itself just read :voice out of),
          ;;    both synchronously in melody's own goroutine when its :exit
          ;;    signal fires. That's a real ordering guarantee, unlike
          ;;    watching a *different* voice's (bass's) exit as a proxy and
          ;;    hoping the two land in the same order every time -- :PAR
          ;;    forks each child into its own independent go-block (see
          ;;    fork-voice), so two different voices' callbacks completing
          ;;    have no ordering guarantee between them even when they're
          ;;    structurally simultaneous. (This test used to do exactly
          ;;    that, and it was genuinely flaky because of it.)
          (let [action-id       (m/schedule-tx! :melody :exit :latest)
                cut-over-fn     (get @reg/*conductor-action-registry* action-id)
                melody-voice-box (promise)]
            (conductor/register-action! action-id
                                         (fn [event]
                                           (cut-over-fn event)
                                           (deliver melody-voice-box (:voice event))))

            ;; 4. Play the parallel pass -- melody plays its ORIGINAL
            ;;    content (its own voice's :tx is still tx1 when this pass
            ;;    starts), and the scheduled cutover fires automatically
            ;;    right as melody's :SEQ exits, with no further action from
            ;;    us -- redirecting ONLY melody's own voice; core.repo/
            ;;    play-tx itself is untouched throughout (see core.async-
            ;;    engine's own docstring on why: it only ever seeds a
            ;;    brand-new top-level voice, never something already
            ;;    running).
            (engine/play #{:melody :bass})
            (let [melody-voice (deref melody-voice-box 4000 :timeout)]
              (is (not= :timeout melody-voice) "the scheduled cutover fired")
              (is (= tx1 @repo/play-tx)
                  "play-tx itself never moves -- only melody's own voice does")

              ;; 5. melody's own voice now genuinely points at the mutated
              ;;    content -- we never called play-tx!/play-latest! or
              ;;    touched any shared pointer ourselves.
              (is (= tx2 @(:tx melody-voice))
                  "the scheduled cutover redirected melody's own voice, and only that voice")
              (is (= mutated-pitches
                     (mapv (comp first :pitches) (m/children :melody @(:tx melody-voice))))
                  "melody's own voice now sees the mutated melody")))))))))
