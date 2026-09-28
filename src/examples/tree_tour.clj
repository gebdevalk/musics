(ns examples.tree-tour
  "algo.tree at the REPL: a staged indispensability pipeline with
   swappable stages, a second source merged in, reshuffled cycles, and
   live playback with params changing while it sounds. Evaluate the
   (comment ...) forms one by one."
  (:require [algo.tree :as tr :refer [defalgos]]
            [algo.tree.lib :as lib]
            [algo.tree.live :as live]))


(defalgos
  ;; 0/1 grid + a pitch source -> one pitch per onset, rests elsewhere
  onsets (fn [[grid pitches]] (map #(when (pos? %1) %2) grid (cycle pitches)))
  ;; element-wise max of two 0/1 grids
  union  (fn [[a b]] (mapv max a b)))

(comment
  ;; -- the pipeline: subdivisions -> ranks -> probabilities -> grid ------
  (def grid (lib/density (lib/tilt (lib/indisp [2 2 3]))))
  grid                                   ; => #node (density (tilt (indisp [2 2 3])))
  (tr/params grid)                        ; what it reads, with ranges
  (tr/run grid {:adherence 0.8 :density 0.5})
  (tr/trace grid {:adherence 0.8 :density 0.5})   ; every stage's result

  ;; swap a stage: write the other expression -- no positions, no paths.
  ;; power reads the same :adherence as tilt (both ^:shared), so the
  ;; params map doesn't change either.
  (tr/run (lib/density (lib/power (lib/indisp [2 2 3]))) {:adherence 0.8 :density 0.5})
  (tr/run (lib/pick (lib/tilt (lib/indisp [2 2 3]))) {:adherence 0.8})

  ;; merge a second source: it's just another child
  (tr/run (union grid (lib/head (lib/cycled lib/euclid))) {:adherence 0.8 :density 0.3 :k 3 :n 4 :len 12})

  ;; two instances of one algo, different params: with
  (tr/run (union (tr/with {:density 0.2} grid) (tr/with {:density 0.6} grid)) {:adherence 0.8})

  ;; -- reshuffled cycles (cyclic-random) -----------------------------------
  (tr/run (lib/head (lib/shuffled [60 62 64 67])) {:len 12})

  ;; -- to sound -------------------------------------------------------------
  (def melody (lib/notes (onsets grid (lib/shuffled lib/scale))))
  (def p {:adherence 0.8 :density 0.5 :root 60 :intervals [0 2 4 7 9] :dur 1/16})
  ((requiring-resolve 'musics.core/play) (tr/run melody p))   ; once

  (live/play! :melody melody p)          ; endless, alongside anything playing
  (live/param! :melody :density 0.8)     ; heard on the next note
  (live/param! :melody :adherence -0.8)
  (live/retree! :melody (lib/notes (onsets (lib/density (lib/power (lib/indisp [3 3]))) [48 55])))
  (live/spec :melody)
  (live/stop! :melody))
