;; pulse.clj
;; Converts a flat onset/pulse grid (algo.rhythmic.rhythm/algo.metric.
;; metric's own output -- a plain vector of 0/1, or a wider weighted
;; range) into real domain Pulse records -- closes the composability gap
;; those generators otherwise have: algo.common.isorhythm/color-talea's
;; own `talea` parameter wants actual durations, not grid positions, and
;; nothing in algo/ converted one into the other before this.

(ns algo.common.pulse
  (:require [core.domain.flat-domain :as d]))

(defn grid->pulses
  "Collapse grid (a flat vector of 0/1, or a wider weighted range, e.g.
   algo.rhythmic.rhythm/euclidean-rhythm's or algo.metric.metric/
   modular-rhythm's own output) into a seq of real domain Pulse records
   (core.domain.flat-domain/pulse) -- one per consecutive equal-value
   run: :duration = run length in grid units, :value = the shared value.
   Every value is treated symmetrically -- no special-casing 0 as
   silence vs nonzero as onset -- matching what a Pulse actually is: a
   duration and a value, nothing more (see doc/decisions.md's 2026-09-05
   entry on why :value stays this generic rather than richer).

   :id/:context are both nil here -- this fn only computes the
   duration/value pairing, same as algo.common.isorhythm/color-talea
   never builds a domain record itself either; a caller placing these
   into real, addressable material assigns both (mirroring however
   Leaf/Rest/Drum construction already works elsewhere), typically via
   d/pulse's own 4-arg form again with a real id/context, or by
   assoc'ing them in directly since a Pulse is a plain map.

   (grid->pulses [1 0 0 1 0 0 1 0])
   ;; => a seq of 6 Pulses: duration/value pairs
   ;;    (1 1) (2 0) (1 1) (2 0) (1 1) (1 0)
   (map :duration (grid->pulses [1 0 0 1 0 0 1 0]))
   ;; => (1 2 1 2 1 1) -- feeds color-talea's own `talea` param directly"
  [grid]
  (map (fn [run] (d/pulse nil nil (count run) (first run)))
       (partition-by identity grid)))

(comment
  (grid->pulses [1 0 0 1 0 0 1 0])
  (map :duration (grid->pulses [1 0 0 1 0 0 1 0]))
  )
