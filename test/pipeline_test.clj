(ns ^:repl pipeline-test
  "Not just a test -- a runnable walkthrough of the whole pipeline this
   project is built around: read text -> commit (immediate, always
   visible right away) -> play.

   Two genuinely different scenarios, not two ways of doing the same
   thing:
     - pipeline-direct-cutover     -- a mutation lands with NOTHING
                                       currently playing (the first
                                       pass already finished) -- a
                                       fresh (play ...) call afterward
                                       picks up the edit automatically,
                                       since a brand-new voice always
                                       starts from whatever's currently
                                       committed.
     - pipeline-scheduled-cutover  -- a mutation lands WHILE a voice
                                       is still sounding the pre-
                                       mutation content -- that voice's
                                       own :view was captured once, at
                                       birth, so it's entirely
                                       unaffected by the commit;
                                       musics.engine/schedule-tx!
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
            [musics.repo :as repo]
            [musics.registries :as reg]
            [musics.conductor :as conductor]
            [musics.engine :as engine]))

(defn- reset-everything! []
  ;; Registry/repo isolation (incl. :ROOT seeding) is handled by
  ;; with-fresh-session, which wraps every test body -- this fn now only
  ;; resets musics.core's OWN session atom (:auto-ids/:var-map), which is
  ;; a plain defonce, not one of musics.registries' ^:dynamic vars, so
  ;; with-fresh-session's own binding never touches it.
  (reset! m/session {:auto-ids {}}))

;; ============================================================
;; Fast / direct: nothing is playing when the mutation lands -- a fresh
;; play call afterward just sees current automatically
;; ============================================================

(deftest pipeline-direct-cutover
 (with-fresh-session
  (reset-everything!)

  ;; 1. Read two equal-length sequences as ONE atomic committed batch --
  ;;    a single (parse ...) call can define more than one named part;
  ;;    they commit together, immediately.
  (let [{:keys [ids]} (m/parse "[melody: c4 d e f] [bass: c,4 c c c]")]
    (is (= [:melody :bass] ids))
    (is (some? (m/find :melody)) "visible immediately, parse already committed it")

    (let [original-pitches (mapv (comp first :pitches) (m/children :melody))
          eng               (engine/engine nil (repo/registry) :ROOT)]
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
        ;;    same length -- commits immediately, visible right away.
        ;;    (Pitches are captured from what was actually committed,
        ;;    not hardcoded -- this test is about the pipeline
        ;;    mechanics, not pitch-resolution arithmetic.)
        (m/parse "[melody: g4 f e d]")
        (let [mutated-pitches (mapv (comp first :pitches) (m/children :melody))]
          (is (not= original-pitches mutated-pitches)
              "the mutation actually changed melody's content")

          ;; 4. Play melody again -- a genuinely NEW voice, so it reads
          ;;    a fresh :view at birth (see musics.events/voice-events)
          ;;    and hears the mutated content automatically.
          (let [second-pass-done (promise)]
            (conductor/register-action! :second-done (fn [_] (deliver second-pass-done true)))
            (conductor/schedule! :melody :exit :second-done)
            (engine/play :melody)
            (is (= true (deref second-pass-done 4000 :timeout))
                "second pass (the mutated melody) finished playing too"))))))))

;; ============================================================
;; Deliberate / scheduled: prepare the cutover, let playback trigger it
;; ============================================================

(deftest pipeline-scheduled-cutover
 (with-fresh-session
  (reset-everything!)
  ;; melody twice against the bass; melody is rewritten while its first
  ;; pass plays. A commit never reaches a voice already playing, so pass
  ;; 1 plays the original; the cutover armed for melody's exit moves
  ;; that voice on, so pass 2 -- which reads :melody again -- plays the
  ;; rewrite. The bass never crosses melody's exit and stays as it was.
  (m/parse "[melody: !tempo:960 c4 d e f] [bass: !tempo:960 c,1 c,1]")
  (let [sent (atom [])
        done (promise)]
;; fs is a token: with-redefs reaches every engine's thread, and another
    ;; test's engine may still be sounding
    (with-redefs [engine/send-midi-on!  (fn [fs ev _] (when (= fs ::fs) (swap! sent conj [(:path ev) (first (:pitches ev))])))
                  engine/send-midi-off! (fn [_ _])]
      (binding [engine/*engine* (engine/engine ::fs (repo/registry) :ROOT)]
        (conductor/register-action! :rewrite (fn [_] (with-out-str (m/parse "[melody: !tempo:960 g4 f e d]"))))
        (conductor/schedule! :melody :enter :rewrite)
        (conductor/register-action! :bass-done (fn [_] (deliver done true)))
        (conductor/schedule! :bass :exit :bass-done)
        (m/schedule-tx! :melody :exit)
        (engine/play #{[:melody :melody] :bass})
        (is (= true (deref done 4000 :timeout)))))
    (let [by-voice (group-by first @sent)
          melody   (map second (by-voice [:TAB]))
          bass     (map second (by-voice [:TAA]))]
      (is (= [60 62 64 65 55 53 52 50] melody)
          "pass 1 the original (the commit didn't reach it), pass 2 the rewrite")
      (is (= [48 36] bass) "the bass, which never crosses melody's exit, is untouched")))))
