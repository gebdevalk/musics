(ns ^:parsing midi-record-test
  "Pure-function tests for input.midi-record's quantization/text-
   generation pipeline -- no real MIDI hardware needed anywhere here,
   every test starts from a plain synthetic {:pitch :onset :off} seq,
   same shape input.midi-record/open-record itself builds internally
   from real NOTE_ON/NOTE_OFF events. The grammar-round-trip tests are
   what caught a real bug during development: an earlier version wrote
   !tempo:/!instrument: as a bare top-level header BEFORE the [ ]
   Sequence, which fails to parse at all now that a bare top-level
   Instruction is no longer a valid TopElement (see CLAUDE.md's 'ROOT
   read-only' section) -- exactly the kind of thing that's invisible
   from just eyeballing generated text and only shows up by actually
   parsing it, which is why grammar-parser is required here too."
  (:require [clojure.test :refer [deftest is testing]]
            [input.midi-record :as rec]
            [input.grammar-parser :as gp]))

;; ============================================================
;; Duration rounding -- deterministic at a FIXED bpm (find-pulse's own
;; tempo-detection ambiguity doesn't apply here).
;; ============================================================

(deftest round-duration-test
  (testing "exact matches at 120 bpm (quarter = 500ms)"
    (is (= "4"  (rec/round-duration 120 500))  "quarter")
    (is (= "2"  (rec/round-duration 120 1000))) "half"
    (is (= "8"  (rec/round-duration 120 250)))  "eighth"
    (is (= "4." (rec/round-duration 120 750)))  "dotted quarter"))

;; ============================================================
;; Pulse-finding
;; ============================================================

(deftest find-pulse-test
  (testing "four identical durations -- octave-ambiguous by construction
            (500ms is exactly a quarter at 120, an eighth at 60, ...);
            the tie-break prefers the slowest exact fit"
    (is (= 60 (rec/find-pulse [500 500 500 500]))))
  (testing "a single duration never throws -- degenerate but harmless"
    (is (integer? (rec/find-pulse [500]))))
  (testing "whatever bpm is found, every duration rounds to SOME real
            duration digit (round-trip property, not a specific value)"
    (let [durs [480 480 240 240 960 300 300 300]
          bpm  (rec/find-pulse durs)]
      (doseq [d durs]
        (is (string? (rec/round-duration bpm d)))))))

;; ============================================================
;; Chording + rests
;; ============================================================

(deftest group-chords-test
  (testing "onsets within chord-window-ms collapse into one chord"
    (is (= [{:onset 0 :off 800 :pitches [60 64 67]}]
           (rec/group-chords [{:pitch 60 :onset 0  :off 800}
                               {:pitch 64 :onset 5  :off 800}
                               {:pitch 67 :onset 10 :off 800}]))))
  (testing "onsets further apart than chord-window-ms stay separate,
            ordered by onset regardless of input order"
    (is (= [{:onset 0 :off 500 :pitches [60]}
            {:onset 500 :off 1000 :pitches [62]}]
           (rec/group-chords [{:pitch 62 :onset 500 :off 1000}
                               {:pitch 60 :onset 0   :off 500}])))))

(deftest ->segments-test
  (testing "a gap between one group's end and the next's onset becomes
            an explicit rest"
    (is (= [{:type :note :dur 800 :pitches [60]}
            {:type :rest :dur 400}
            {:type :note :dur 400 :pitches [72]}]
           (rec/->segments [{:onset 0 :off 800 :pitches [60]}
                             {:onset 1200 :off 1600 :pitches [72]}]))))
  (testing "back-to-back groups with no gap produce no rest"
    (is (= [{:type :note :dur 500 :pitches [60]}
            {:type :note :dur 500 :pitches [62]}]
           (rec/->segments [{:onset 0 :off 500 :pitches [60]}
                             {:onset 500 :off 1000 :pitches [62]}])))))

;; ============================================================
;; Pitch spelling
;; ============================================================

(deftest pitch-text-test
  (is (= "C4"  (rec/pitch-text 60)))
  (is (= "C#4" (rec/pitch-text 61)))
  (is (= "C1"  (rec/pitch-text 24)) "this DSL's own C1 -- see rec/stop-note"))

;; ============================================================
;; Text generation + a real grammar round-trip
;; ============================================================

(deftest ->musics-text-test
  (testing "a single note needs the disambiguating '/' before its
            duration digit; a chord's trailing duration doesn't"
    (is (= "[ !tempo:120 C4/4 ]\n"
           (rec/->musics-text [{:type :note :dur 500 :pitches [60]}] 120 nil)))
    (is (= "[ !tempo:120 !instrument:40 <C4 E4 G4>4 ]\n"
           (rec/->musics-text [{:type :note :dur 500 :pitches [60 64 67]}] 120 40))))
  (testing "a rest spells as r<duration>, no pitch involved"
    (is (= "[ !tempo:120 r4 ]\n"
           (rec/->musics-text [{:type :rest :dur 500}] 120 nil))))
  (testing "an empty recording still produces a valid, empty Sequence"
    (is (= "[ !tempo:120 ]\n" (rec/->musics-text [] 120 nil)))))

(deftest generated-text-parses-test
  (testing "every shape ->musics-text can produce is real, parseable
            musics text -- NOT just eyeballed as plausible-looking.
            A bare !tempo:/!instrument: header BEFORE the [ ] Sequence
            (an earlier version of ->musics-text did exactly this)
            fails this check; confirmed live before being fixed."
    (doseq [text [(rec/->musics-text [] 120 nil)
                  (rec/->musics-text [{:type :note :dur 500 :pitches [60]}] 120 nil)
                  (rec/->musics-text [{:type :note :dur 500 :pitches [60 64 67]}] 90 40)
                  (rec/->musics-text [{:type :rest :dur 500}
                                       {:type :note :dur 500 :pitches [62]}] 120 nil)]]
      (is (some? (gp/try-parse text)) (str "failed to parse:\n" text)))))
