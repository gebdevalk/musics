;; pitch.clj
;; Shared pitch-class helper -- build-scale/from-key, moved out of
;; algo.melodic.melody (2026-09-05, closing algo.txt's GAP 1:
;; melody.clj's own scale representation used to be pitch-class NAME
;; STRINGS -- "C", "C#", ... -- incompatible with every other pitch-
;; touching fn in algo/, which all already agree on plain numeric pitch
;; (algo.common.gate's own pitch-class-criterion and algo.common.
;; zfilter's own pc-smooth both already expect a genuine mod-12
;; pitch-class int). Both fns here work entirely in that same numeric
;; space, and live here rather than staying local to melody.clj since
;; neither is melody-specific -- any algo/ generator wanting a
;; root+interval-pattern (or a named key/scale) can use them.

(ns algo.common.pitch
  (:require [common.music-elements :as el]))

(defn build-scale
  "root (a pitch class, 0-11) + intervals (cumulative semitone offsets
   from root, e.g. [0 2 4 5 7 9 11] for a major scale) -> a vector of
   pitch classes, each wrapped into 0-11 via mod -- the same numeric
   convention algo.common.gate/pitch-class-criterion and algo.common.
   zfilter/pc-smooth already use, so a scale built here plugs straight
   into either without any further conversion.

   For a NAMED key/scale (:major, :pentatonic-major, ...), prefer
   from-key below instead of hand-typing intervals here -- this fn is
   for a genuinely custom/non-standard pattern that isn't in common.
   music-elements/scale-steps at all.

   (build-scale 0 [0 2 4 5 7 9 11])  ;=> [0 2 4 5 7 9 11] -- C major
   (build-scale 9 [0 2 3 5 7 8 10])  ;=> [9 11 0 2 4 5 7]  -- A minor"
  [root intervals]
  (mapv #(mod (+ root %) 12) intervals))

(defn- key->pcs
  "A Key record's own :pitches, wrapped into 0-11 -- the one place both
   from-key and from-key-spec below actually do the wrapping, so it's
   never typed out twice."
  [k]
  (mapv #(mod % 12) (el/key-pitches k)))

(defn from-key
  "Build a 0-11 pitch-class scale from a NAMED key+scale (common.
   music-elements/key, the project's own central key/scale-formula
   table -- scale-steps) rather than hand-typing the same interval
   pattern a second time. key's own :pitches are deliberately NOT
   wrapped into 0-11 there (an ascending scale run from a non-C tonic
   needs to keep ascending in absolute terms, e.g. A minor's own
   key-pitches are [9 11 12 14 16 17 19], past a full octave) -- this
   wraps them, same 0-11 convention build-scale's own output already
   has, and the one algo.common.gate/pitch-class-criterion and algo.
   common.zfilter/pc-smooth actually need.

   (from-key :C :major)            ;=> [0 2 4 5 7 9 11]
   (from-key :A :minor)            ;=> [9 11 0 2 4 5 7]
   (from-key :C :pentatonic-major) ;=> [0 2 4 7 9]"
  [key-kw scale-kw]
  (key->pcs (el/key key-kw scale-kw)))

(defn from-key-spec
  "Same as from-key, but from a single \"F#.major\"/\"Bb.minor\"/
   \"C.dorian\"-style spec string (common.music-elements/parse-key)
   instead of two separate keyword args. Returns nil if spec doesn't
   parse -- same failure behavior parse-key itself already has (a bad
   tonic/mode name, not an ex-info), rather than this fn inventing a
   different one.

   (from-key-spec \"G.dorian\") ;=> [7 9 10 0 2 4 5] -- STILL rooted on
   G (tonic-pc 7), not shifted to some other tonic -- a real, once-
   confirmed-live bug class in common.music-elements/key itself applied
   an extra transposition offset to every mode except major/ionian
   (key :D :dorian) used to silently build E dorian), long since fixed
   there; this fn inherits whatever key computes, so a regression there
   would resurface here too -- see algo.melodic.melody's own test for a
   direct check of exactly this."
  [spec]
  (when-let [k (el/parse-key spec)]
    (key->pcs k)))

(defn resolve-scale
  "Normalize scale-spec into a plain 0-11 pitch-class vector, accepting
   any of three shapes a caller might reasonably have in hand: an
   already-built scale vector (passed through unchanged, via vec), a
   [key-kw scale-kw] pair of two keywords (from-key), or a single
   \"F#.major\"-style spec string (from-key-spec). Lets a caller mix
   pre-built scales with spec shorthand freely -- see algo.melodic.
   melody/modulating-melody's own segments, the motivating use.
   A 2-element vector is only ever treated as a [key-kw scale-kw] pair
   when BOTH elements are keywords -- an ordinary scale (always plain
   pitch-class integers) can never be mistaken for one."
  [scale-spec]
  (cond
    (string? scale-spec) (from-key-spec scale-spec)
    (and (vector? scale-spec) (= 2 (count scale-spec)) (every? keyword? scale-spec))
    (apply from-key scale-spec)
    :else (vec scale-spec)))

(comment
  (build-scale 0 [0 2 4 5 7 9 11])
  (build-scale 9 [0 2 3 5 7 8 10])
  (from-key :C :major)
  (from-key :A :minor)
  (from-key :C :pentatonic-major)
  (from-key-spec "G.dorian")
  (resolve-scale [0 2 4 5 7 9 11])
  (resolve-scale [:C :major])
  (resolve-scale "G.dorian")
  )
