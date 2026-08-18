(ns gui.indispensability
  (:require
    [musics.gui.components :refer [create-multi-slider-jfx-components
                                   create-separator
                                   package-aggregate-control
                                   package-multi-slider-jfx-components]]
    [musics.gui.global :refer [show-labels]]
    [musics.metric.indispensability :refer :all]))

(defn meter-label-converter-fn
  [^Double n]
  (cond
    (= (int n) 4) "22232"
    (= (int n) 3) "22322"
    (= (int n) 2) "23222"
    (= (int n) 1) "32222"
    (= (int n) 0) "22223"))

(defn package-indisps-aggregate-control
  []
  (package-aggregate-control
    "Indisps"
    [(package-multi-slider-jfx-components
       "Meter"
       (create-multi-slider-jfx-components 0.0 4.0 0.0 4 show-labels "%.0f" indispensability-records :meter meter-label-converter-fn))
     (create-separator)
     (package-multi-slider-jfx-components
       "Density"
       (create-multi-slider-jfx-components 0.0 (float micro-divs) [1.0 2.0 3.0 4.0] 8 show-labels "%.0f" indispensability-records :density nil))
     (create-separator)
     (package-multi-slider-jfx-components
       "Adherence"
       (create-multi-slider-jfx-components 0.0 (float micro-divs) (float micro-divs) 8 show-labels "%.0f" indispensability-records :adherence nil))]))