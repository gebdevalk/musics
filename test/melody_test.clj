(ns ^:algo melody-test
  "Tests for melodic algorithms. Run: lein test melody-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.melodic.melody :as a]))

(deftest constraint-melody-test
  (let [melody (a/constraint-melody a/c-major 8 [a/no-repeat-constraint])]
    (is (= 8 (count melody)))
    (is (every? #(contains? (set a/c-major) %) melody))))

(deftest direction-limit-constraint-test
  ;; Regression test: the inner loop's own termination check was
  ;; (< i 0), but its body reads (nth melody (dec i)) -- once i reached
  ;; 0, that's (nth melody -1), a confirmed-live IndexOutOfBoundsException
  ;; on ordinary input -- fixed 2026-09-03. A plain literal here rather
  ;; than a/c-major -- at the time this test was written, c-major was
  ;; built via build-scale as NOTE-NAME STRINGS ("C", "D", ...),
  ;; incompatible with this test's own bare 0/2/4/5/7 melody literals.
  ;; c-major is plain pitch-class integers now (2026-09-05, algo.common.
  ;; pitch/build-scale, see doc/decisions.md's GAP 1 entry) -- kept as a
  ;; literal anyway since nothing needed changing once it already
  ;; worked, not because a/c-major would fail here today.
  (let [scale [0 2 4 5 7 9 11]
        f     (a/direction-limit-constraint scale 2)]
    (is (true? (f [0 2 4] 5))
        "no crash, and only 2 consecutive rising pairs exist so far -- allowed")
    (is (false? (f [0 2 4 5 7] 9))
        "4 consecutive rising pairs already exist -- a 5th same-direction
         step exceeds max-consecutive and must be rejected")
    (is (true? (f [0] 2)) "fewer than 2 notes -- nothing to check yet, always allowed"))
  ;; Confirm it actually works end to end through the real generator,
  ;; not just in isolation.
  (let [melody (a/constraint-melody a/c-major 12 [(a/direction-limit-constraint a/c-major 2)])]
    (is (= 12 (count melody)))))

(deftest modulating-melody-each-segment-stays-within-its-own-scale
  (let [melody (a/modulating-melody [[a/c-major 6] [a/a-minor 5]] [])]
    (is (= 11 (count melody)) "6 + 5 = 11, exactly the sum of every segment's own length")
    (is (every? (set a/c-major) (subvec melody 0 6)))
    (is (every? (set a/a-minor) (subvec melody 6 11)))))

(deftest modulating-melody-accepts-key-kw-and-spec-string-segments
  ;; scale-spec goes through algo.common.pitch/resolve-scale -- a
  ;; [key-kw scale-kw] pair and a "F#.major"-style string both resolve,
  ;; same as an already-built scale vector.
  (let [melody (a/modulating-melody [[[:C :major] 4] ["A.minor" 4]] [])]
    (is (= 8 (count melody)))
    (is (every? (set a/c-major) (subvec melody 0 4)))
    (is (every? (set a/a-minor) (subvec melody 4 8)))))

(deftest modulating-melody-pivots-on-a-shared-note-when-one-exists
  ;; A single-pitch "scale" [5] forces constraint-melody to pick
  ;; exactly 5 every time (with no constraints, every candidate in a
  ;; 1-element scale trivially passes) -- deterministic, no seeding
  ;; needed. c-major contains 5, so the next segment's own first note
  ;; must be the pivot itself: 5.
  (let [melody (a/modulating-melody [[[5] 3] [a/c-major 3]] [])]
    (is (= [5 5 5] (subvec melody 0 3)))
    (is (= 5 (nth melody 3)) "segment 2 pivots on 5 -- it's a real member of c-major")))

(deftest modulating-melody-falls-back-to-a-fresh-start-when-no-pivot-exists
  ;; [0 2 4] never contains 5 -- the pivot check must fail, so segment
  ;; 2's own first note comes from constraint-melody's own default
  ;; (rand-nth scale) instead of being forced to an invalid 5.
  (let [melody (a/modulating-melody [[[5] 2] [[0 2 4] 3]] [])]
    (is (= [5 5] (subvec melody 0 2)))
    (is (contains? #{0 2 4} (nth melody 2))
        "a genuine member of the new scale, never the old segment's own 5")))
