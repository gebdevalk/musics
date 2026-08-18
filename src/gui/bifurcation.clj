(ns gui.bifurcation
  (:require
    [musics.harmonic.bifurcation :refer :all]
    [musics.gui.components :refer [create-multi-slider-jfx-components
                                   create-separator
                                   package-aggregate-control
                                   package-multi-slider-jfx-components]]
     [musics.gui.global :refer [show-labels]]))

(defn package-befur-aggregate-control
  []
  (package-aggregate-control
    "Bifur"
    [(package-multi-slider-jfx-components
       "Seed"
       (create-multi-slider-jfx-components 0.0 1.0 0.6 10 show-labels "%.6f" bifurcation-records :seed nil))
     (create-separator)
     (package-multi-slider-jfx-components
       "R"
       (create-multi-slider-jfx-components 2.4 4.0 3.0 8 show-labels "%.6f" bifurcation-records :r nil))
     (create-separator)
     (package-multi-slider-jfx-components
       "Transposition"
       (create-multi-slider-jfx-components 0.0 120.0 [24.0 36.0 48.0 60.0] 10 show-labels "%.0f" bifurcation-records :transposition nil))
     (create-separator)
     (package-multi-slider-jfx-components
       "Scale"
       (create-multi-slider-jfx-components 0.0 120.0 [24.0 30.0] 10 show-labels "%.0f" bifurcation-records :scale nil))
     ]))
