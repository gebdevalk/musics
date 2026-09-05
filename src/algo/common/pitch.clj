;; pitch.clj
;; Shared pitch-class helper -- build-scale, moved out of algo.melodic.
;; melody (2026-09-05, closing algo.txt's GAP 1: melody.clj's own scale
;; representation used to be pitch-class NAME STRINGS -- "C", "C#", ...
;; -- incompatible with every other pitch-touching fn in algo/, which
;; all already agree on plain numeric pitch (algo.common.gate's own
;; pitch-class-criterion and algo.common.zfilter's own pc-smooth both
;; already expect a genuine mod-12 pitch-class int). build-scale now
;; works entirely in that same numeric space, and lives here rather
;; than staying local to melody.clj since nothing about it is melody-
;; specific -- any algo/ generator wanting a root + interval-pattern
;; scale can use it.

(ns algo.common.pitch)

(defn build-scale
  "root (a pitch class, 0-11) + intervals (cumulative semitone offsets
   from root, e.g. [0 2 4 5 7 9 11] for a major scale) -> a vector of
   pitch classes, each wrapped into 0-11 via mod -- the same numeric
   convention algo.common.gate/pitch-class-criterion and algo.common.
   zfilter/pc-smooth already use, so a scale built here plugs straight
   into either without any further conversion.

   (build-scale 0 [0 2 4 5 7 9 11])  ;=> [0 2 4 5 7 9 11] -- C major
   (build-scale 9 [0 2 3 5 7 8 10])  ;=> [9 11 0 2 4 5 7]  -- A minor"
  [root intervals]
  (mapv #(mod (+ root %) 12) intervals))

(comment
  (build-scale 0 [0 2 4 5 7 9 11])
  (build-scale 9 [0 2 3 5 7 8 10])
  )
