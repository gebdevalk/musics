(ns examples.tree-tour
  "algo.tree at the REPL: a tree and the tctx that holds its settings,
   swappable stages, two instances of one algo, a user-defined algo, and
   live playback with settings changing while it sounds. Evaluate the
   (comment ...) forms one by one."
  (:require [algo.tree :as t :refer [defalgo]]
            [algo.tree.lib :refer [euclid scale cycle> shuffle> transpose zip +articulation
                                   indisp tilt power weights->pulses pulses->durations]]))

(defalgo union "Onset wherever either grid has one."
  {:algo {:in [:pulse :pulse] :out :pulse}}
  [a b] (mapv max a b))

(comment
  ;; -- a tree, and a tctx for it ------------------------------------------
  (def grid (weights->pulses (tilt indisp)))   ; => #node (weights->pulses (tilt (indisp)))
  (def tctx  (t/tctx grid))               ; an atom: {:params {...} :specs {...}}
  (t/describe tctx)                       ; key, value, range, default, algo, doc
  (t/run grid tctx)                       ; 12/8, half the pulses, the strongest
  (t/setp! tctx :adherence -0.8)     ; checked against -1.0..1.0
  (t/trace grid tctx)                     ; every stage's result

  ;; swap a stage: write the other expression. power reads the same
  ;; :adherence as tilt (identical specs share a key), so tctx still fits.
  (t/run (weights->pulses (power indisp)) tctx)

  ;; wrong shapes fail when built, not when played
  (zip (tilt indisp) scale)              ; zip: child 1 should be :duration ...

  ;; -- two instances of one algo: name one ---------------------------------
  (def bass (union (euclid :as :bass) euclid))
  (t/describe bass)                      ; :bass/k :bass/n ... and :k :n ...
  (t/run bass {:bass/k 2 :k 5 :n 16 :bass/n 16})

  ;; -- to sound -------------------------------------------------------------
  (def melody (zip (pulses->durations grid) (shuffle> scale)))   ; each onset lasts until the next
  (def melody-tctx   (t/tctx melody {:pulse 1/16}))
  (t/play! melody melody-tctx)                  ; once
  (t/gui melody melody-tctx)                    ; a window: a control per param, result preview, Play / Live

  (t/live! :melody melody melody-tctx)          ; endless, alongside anything playing
  (t/setp! melody-tctx :density 0.8)       ; heard on the next note
  (t/setp! melody-tctx :adherence -0.8)
  (t/retree! :melody (transpose (+articulation (zip (pulses->durations grid) (cycle> scale)) (cycle> [:staccato]))))
                                                ; detached, and melody-tctx gains :semitones
  (t/setp! melody-tctx :semitones 12)
  (t/stop! :melody))
