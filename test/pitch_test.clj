(ns ^:algo pitch-test
  (:require [clojure.test :refer [deftest is]]
            [algo.common.pitch :as pitch]))

(deftest build-scale-computes-pitch-classes-from-a-root-and-intervals
  (is (= [0 2 4 5 7 9 11] (pitch/build-scale 0 [0 2 4 5 7 9 11]))
      "C major"))

(deftest build-scale-wraps-past-11-back-into-0-11
  (is (= [9 11 0 2 4 5 7] (pitch/build-scale 9 [0 2 3 5 7 8 10]))
      "A minor -- 9+2=11 stays put, 9+3=12 wraps to 0, etc."))

(deftest build-scale-supports-a-pentatonic-subset
  (is (= [0 2 4 7 9] (pitch/build-scale 0 [0 2 4 7 9]))))

(deftest from-key-derives-a-scale-from-the-central-key-table
  (is (= [0 2 4 5 7 9 11] (pitch/from-key :C :major)))
  (is (= [0 2 4 7 9] (pitch/from-key :C :pentatonic-major))))

(deftest from-key-wraps-a-non-c-tonics-scale-back-into-0-11
  (is (= [9 11 0 2 4 5 7] (pitch/from-key :A :minor))
      "common.music-elements/key's own :pitches for A minor are
       [9 11 12 14 16 17 19], deliberately unwrapped -- from-key wraps
       each back into 0-11 via mod"))

(deftest from-key-non-major-modes-are-rooted-on-their-own-tonic-not-shifted
  ;; Regression guard: common.music-elements/key itself once had a
  ;; real, confirmed-live bug (see that ns's own comment above
  ;; scale-steps) where an extra transposition offset was silently
  ;; applied to every mode EXCEPT major/ionian -- (key :D :dorian) used
  ;; to build E dorian instead of D dorian, the bug never showing for
  ;; major/ionian since their own offset happens to be 0. Long since
  ;; fixed there, but from-key/from-key-spec inherit whatever key
  ;; computes -- a regression there would resurface here too, so this
  ;; checks the actual tonic directly rather than trusting key alone.
  (is (= 7 (first (pitch/from-key :G :dorian))) "G dorian starts on G (pitch class 7), not A (9) or anywhere else")
  (is (= 2 (first (pitch/from-key :D :dorian))) "D dorian starts on D (pitch class 2), not E (4) -- the exact case the old bug hit"))

(deftest from-key-spec-parses-a-dotted-key-scale-string
  (is (= [0 2 4 5 7 9 11] (pitch/from-key-spec "C.major")))
  (is (= [7 9 10 0 2 4 5] (pitch/from-key-spec "G.dorian"))
      "matches from-key :G :dorian exactly -- same underlying key call"))

(deftest from-key-spec-returns-nil-for-an-unparseable-spec
  (is (nil? (pitch/from-key-spec "nonsense"))))

(deftest resolve-scale-passes-a-plain-scale-vector-through-unchanged
  (is (= [0 2 4 5 7 9 11] (pitch/resolve-scale [0 2 4 5 7 9 11]))))

(deftest resolve-scale-resolves-a-key-kw-scale-kw-pair
  (is (= [0 2 4 5 7 9 11] (pitch/resolve-scale [:C :major]))))

(deftest resolve-scale-resolves-a-spec-string
  (is (= [7 9 10 0 2 4 5] (pitch/resolve-scale "G.dorian"))))
