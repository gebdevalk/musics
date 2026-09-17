(ns ^:repl pipeline-test
  "Not just a test -- a runnable walkthrough of the whole pipeline this
   project is built around: read text -> commit (immediate, auto-
   advances play-tx too, see core.repo/play-tx's own docstring) -> play.

   Two genuinely different scenarios, not two ways of doing the same
   thing:
     - pipeline-direct-tx-cutover     -- a mutation lands with NOTHING
                                          currently playing (the first
                                          pass already finished) -- a
                                          fresh (play ...) call afterward
                                          picks up the edit automatically,
                                          zero extra action needed now
                                          that commit always keeps
                                          play-tx current.
     - pipeline-scheduled-tx-cutover  -- a mutation lands WHILE a voice
                                          is still sounding the pre-
                                          mutation content -- that voice's
                                          own :view was captured once, at
                                          birth, so it's entirely
                                          unaffected by play-tx moving;
                                          core.async-engine/schedule-tx!
                                          is the only way to redirect it,
                                          deliberately, at a chosen
                                          boundary (\"commit now, cut over
                                          whenever we get there\").

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
;; Fast / direct: nothing is playing when the mutation lands -- a fresh
;; play call afterward just sees current automatically
;; ============================================================

(deftest pipeline-direct-tx-cutover
 (with-fresh-session
  (reset-everything!)

  ;; 1. Read two equal-length sequences as ONE atomic committed batch --
  ;;    a single (parse ...) call can define more than one named part;
  ;;    they land under one tx and commit together, immediately -- and
  ;;    play-tx auto-advances to it too (see core.repo/play-tx's own
  ;;    docstring).
  (let [{:keys [tx ids]} (m/parse "[melody: c4 d e f] [bass: c,4 c c c]")]
    (is (= [:melody :bass] ids))
    (is (some? (m/find :melody)) "visible immediately, parse already committed it")

    (let [tx1                tx
          original-pitches   (mapv (comp first :pitches) (m/children :melody tx1))]
      (is (= tx1 @repo/play-tx) "play-tx already points at it, no explicit step needed")

      (let [eng (engine/engine nil repo/play-tx :ROOT)]
        (binding [engine/*engine* eng]

          ;; 2. Play the two equal-length sequences in parallel, and wait
          ;;    for this first pass to actually finish (via melody's own
          ;;    :section :exit signal) before moving on -- nothing is
          ;;    playing by the time the mutation below lands.
          (let [first-pass-done (promise)]
            (conductor/register-action! :first-done (fn [_] (deliver first-pass-done true)))
            (conductor/schedule! :melody :exit :first-done)
            (engine/play #{:melody :bass})
            (is (= true (deref first-pass-done 4000 :timeout))
                "first pass through melody/bass finished playing"))

          ;; 3. Mutate melody -- parse a redefinition under the same id,
          ;;    same length -- commits immediately, which ALSO advances
          ;;    play-tx to it automatically (no play-latest! call needed
          ;;    anymore -- that's the whole point of this scenario).
          ;;    (Pitches are captured from what was actually committed,
          ;;    not hardcoded -- this test is about the pipeline
          ;;    mechanics, not pitch-resolution arithmetic.)
          (let [tx2             (:tx (m/parse "[melody: g4 f e d]"))
                mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))]
            (is (not= tx1 tx2))
            (is (not= original-pitches mutated-pitches)
                "the mutation actually changed melody's content")
            (is (= tx2 @repo/play-tx)
                "committing already advanced play-tx to the new commit, automatically")
            (is (= mutated-pitches (mapv (comp first :pitches) (m/children :melody @repo/play-tx)))
                "play-tx already sees the mutated melody, no extra step taken")

            ;; 4. Play melody again -- a genuinely NEW voice, so it reads
            ;;    play-tx fresh at birth (see core.async-engine/fresh-view)
            ;;    and hears the mutated content automatically.
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
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (binding [engine/*engine* eng]

        ;; 2. The mutation has to land WHILE melody's voice is already
        ;;    alive (its own :view already captured at birth) for this
        ;;    scenario to mean anything -- a mutation landing BEFORE a
        ;;    voice is even minted is just what pipeline-direct-tx-
        ;;    cutover already covers (a fresh voice reads play-tx fresh
        ;;    regardless). So the mutation itself is triggered by
        ;;    melody's own :enter signal -- guaranteed to fire the
        ;;    instant its voice exists, before any of its own leaves
        ;;    have played -- not by real-time sleeping/guessing.
        (let [mutation-result (promise)]
          (conductor/register-action!
            :mutate-melody
            (fn [event]
              (let [tx2             (:tx (m/parse "[melody: g4 f e d]"))
                    mutated-pitches (mapv (comp first :pitches) (m/children :melody tx2))
                    ;; melody's own voice's :view right after the mutation
                    ;; commits -- must still be the PRE-mutation snapshot,
                    ;; proving play-tx auto-advancing never retroactively
                    ;; touches an already-minted voice.
                    view-right-after-mutation @(:view (:voice event))]
                (deliver mutation-result {:tx2 tx2 :mutated-pitches mutated-pitches
                                           :view-right-after-mutation view-right-after-mutation}))))
          (conductor/schedule! :melody :enter :mutate-melody)

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

            ;; 4. Play the parallel pass -- melody's voice is minted
            ;;    reading play-tx as of RIGHT NOW (tx1, the original),
            ;;    its own :enter fires the mutation above, and the
            ;;    scheduled cutover fires automatically right as melody's
            ;;    :SEQ exits, with no further action from us -- redirecting
            ;;    ONLY melody's own voice.
            (engine/play #{:melody :bass})
            (let [{:keys [tx2 mutated-pitches view-right-after-mutation]}
                  (deref mutation-result 4000 :timeout)
                  melody-voice (deref melody-voice-box 4000 :timeout)]
              (is (not= :timeout mutation-result) "the :enter-triggered mutation fired")
              (is (not= :timeout melody-voice) "the scheduled cutover fired")
              (is (not= original-pitches mutated-pitches)
                  "the mutation actually changed melody's content")
              (is (= tx2 @repo/play-tx)
                  "play-tx already advanced to the mutated commit, automatically")

              ;; 5. The crux of this whole scenario: melody's own voice,
              ;;    right after the mutation committed (play-tx already
              ;;    moved), still had its PRE-mutation :view -- play-tx
              ;;    auto-advancing never reaches back into an
              ;;    already-minted voice.
              (is (= (into {} (repo/view tx1)) view-right-after-mutation)
                  "melody's own voice was untouched by play-tx moving -- only an explicit redirect can move it")

              ;; 6. ...and only the scheduled cutover, firing later at
              ;;    melody's own :exit, is what finally moves it.
              (is (= (into {} (repo/view tx2)) @(:view melody-voice))
                  "the scheduled cutover redirected melody's own voice, and only that voice")
              (let [view          @(:view melody-voice)
                    melody-node   (get view :melody)
                    melody-children (mapv (fn [child] (if (keyword? child) (get view child) child))
                                           (:children melody-node))]
                (is (= mutated-pitches (mapv (comp first :pitches) melody-children))
                    "melody's own voice now sees the mutated melody"))))))))))
