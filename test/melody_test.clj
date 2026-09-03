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
  ;; on ordinary input -- fixed 2026-09-03. A plain scale literal here,
  ;; not a/c-major (built via build-scale, real MIDI values, not the
  ;; bare 0/2/4/5/7 pitch classes this test's own melody literals need).
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
