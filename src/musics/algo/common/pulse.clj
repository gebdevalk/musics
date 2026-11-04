;; pulse.clj
;; Converts a flat onset/pulse grid (musics.algo.rhythmic.pattern's own
;; output -- a plain vector of 0/1, or a wider weighted
;; range) into real domain Pulse records -- closes the composability gap
;; those generators otherwise have: musics.algo.common.isorhythm/color-talea's
;; own `talea` parameter wants actual durations, not grid positions, and
;; nothing in musics/algo/ converted one into the other before this.

(ns musics.algo.common.pulse
  (:require [musics.domain :as d]))

(defn grid->pulses
  "Collapse a grid (a flat 0/1 vector, or a wider weighted range, e.g. euclid's output) into Pulse records (musics.domain/pulse), one per run of equal values: :duration is the run length in grid units, :value the shared value. Every value counts alike -- 0 is not special-cased as silence. :id and :context are nil; the caller assigns them.
     (grid->pulses [1 0 0 1 0 0 1 0])
     ;; => Pulses (1 1) (2 0) (1 1) (2 0) (1 1) (1 0) as duration/value
     (map :duration (grid->pulses [1 0 0 1 0 0 1 0]))
     ;; => (1 2 1 2 1 1), what color-talea takes as a talea"
  [grid]
  (map (fn [run] (d/pulse nil nil (count run) (first run)))
       (partition-by identity grid)))

(comment
  (grid->pulses [1 0 0 1 0 0 1 0])
  (map :duration (grid->pulses [1 0 0 1 0 0 1 0]))
  )
