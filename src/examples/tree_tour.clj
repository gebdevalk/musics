(ns examples.tree-tour
  "algo.tree at the REPL: a tree and the tctx that holds its settings,
   swappable stages, two instances of one algo, a user-defined algo, and
   live playback with settings changing while it sounds. Evaluate the
   (comment ...) forms one by one."
  (:require [algo.tree :as t :refer [defalgo]]
            [algo.tree.lib :refer [euclid scale cycled shuffled gate transpose
                                   indisp tilt power density notes]]))

(defalgo union "Onset wherever either grid has one."
  {:algo {:in [:grid :grid] :out :grid}}
  [a b] (mapv max a b))

(comment
  ;; -- a tree, and a tctx for it ------------------------------------------
  (def grid (density (tilt indisp)))     ; => #node (density (tilt (indisp)))
  (def ctx  (t/tctx grid))               ; an atom: {:params {...} :specs {...}}
  (t/describe ctx)                       ; key, value, range, default, algo, doc
  (t/run grid ctx)                       ; 12/8, half the pulses, the strongest
  (t/set-param! ctx :adherence -0.8)     ; checked against -1.0..1.0
  (t/trace grid ctx)                     ; every stage's result

  ;; swap a stage: write the other expression. power reads the same
  ;; :adherence as tilt (identical specs share a key), so ctx still fits.
  (t/run (density (power indisp)) ctx)

  ;; wrong shapes fail when built, not when played
  (gate (tilt indisp) scale)             ; gate: child 1 should be :grid ...

  ;; -- two instances of one algo: name one ---------------------------------
  (def bass (union (euclid :as :bass) euclid))
  (t/describe bass)                      ; :bass/k :bass/n ... and :k :n ...
  (t/run bass {:bass/k 2 :k 5 :n 16 :bass/n 16})

  ;; -- to sound -------------------------------------------------------------
  (def melody (notes (gate grid (shuffled scale))))
  (def mctx   (t/tctx melody {:dur 1/16}))
  (t/play! melody mctx)                  ; once

  (t/live! :melody melody mctx)          ; endless, alongside anything playing
  (t/set-param! mctx :density 0.8)       ; heard on the next note
  (t/set-param! mctx :adherence -0.8)
  (t/retree! :melody (notes (transpose (gate grid (cycled scale)))))   ; mctx gains :semitones
  (t/set-param! mctx :semitones 12)
  (t/stop! :melody))
