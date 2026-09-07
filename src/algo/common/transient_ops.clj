;; transient_ops.clj
;; Plain-material versions of the grammar's own TRANSIENT commands --
;; (times factor [...]) / (tuplet factor [...]) / (transpose from to
;; [...]) -- for algo/ generators working directly on already-realized
;; material (a seq of parts, or bare numeric durations) rather than
;; parsed text. "Transient" here means the same thing CLAUDE.md's own
;; domain-model section does: times/tuplet/transpose never register
;; their own body under an id, they just reshape it in place -- this is
;; that same reshape, minus the container/walker machinery around it
;; (push-container/pop-container/scale-durations!), reusing
;; core.domain.flat-domain's own per-part fns directly rather than
;; re-deriving their arithmetic.
;;
;; transpose here is the SIMPLE semitone-count sibling
;; (core.domain.flat-domain/transpose), not the grammar's own two-pitch,
;; key-aware form (which derives an interval from two written pitches
;; and respells accidentals via the active key) -- musics.clj's own
;; REPL-level `transpose` documents the identical gap for the same
;; reason: a two-pitch, key-aware common-level equivalent doesn't exist
;; yet either.

(ns algo.common.transient-ops
  (:require [core.domain.flat-domain :as d]))

(defn- times-value
  "factor * x -- the product directly if x is a bare number (past what
   core.domain.flat-domain/times covers on its own, which only ever
   touches a :duration key); (d/times factor) applied unchanged
   otherwise, so a real part scales exactly the way the grammar's own
   (times ...) already does, and anything without a :duration passes
   through untouched, same policy d/times already has."
  [factor x]
  (if (number? x) (* factor x) ((d/times factor) x)))

(defn times
  "material, duration multiplied by factor -- the grammar's own
   (times factor [...]), applied directly to already-realized material
   instead of a parsed body. Generalizes past parts to bare numbers too
   (unlike core.domain.flat-domain/times on its own), so this composes
   with plain Clojure seqs of durations the same way it does with real
   parts:
     (times 2 [1/4 1/8 1/2]) => (1/2 1/4 1)"
  [factor material]
  (map (partial times-value factor) material))

(defn tuplet
  "material, duration DIVIDED by factor -- the grammar's own
   (tuplet factor [...]), e.g. (tuplet 3/2 material) for 3 notes in the
   time of 2. Exactly (times (/ 1 factor) material) -- see
   core.domain.flat-domain/to-tuplet, the per-part fn this mirrors."
  [factor material]
  (times (/ 1 factor) material))

(defn transpose
  "material, every pitch shifted by semitones -- the simple sibling of
   the grammar's own (transpose from-pitch to-pitch [...]) described
   above. This is core.domain.flat-domain/transpose mapped across
   material directly -- non-pitched items (an inline instruction
   marker, say) pass through unchanged, same as that fn already does
   on its own."
  [semitones material]
  (map (d/transpose semitones) material))

(comment
  (times 2 [1/4 1/8 1/2])
  (tuplet 3/2 [1/4 1/4 1/4])
  (transpose 7 [{:pitches [60 64 67] :duration 1/4}])
  )
