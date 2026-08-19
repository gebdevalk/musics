(ns gui.main
  "cljfx port of musics.gui.main's Main panel: volume, tempo, pan."
  (:require
    [musics.new-gui.components :as c]))

(defn view
  [{:keys [main record?]}]
  {:fx/type :v-box
   :spacing 6
   :style "-fx-border-color: black; -fx-padding: 6;"
   :children
   [{:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children
     [{:fx/type :label :text "Main" :style "-fx-font-weight: bold; -fx-font-size: 14;"}
      (c/record-toggle {:group :main :selected? (:main record?)})]}
    (c/param-column {:group :main :param :volume :label "Volume"
                     :min 0.0 :max 128.0 :fmt "%.2f" :values (:volume main)})
    (c/param-column {:group :main :param :tempo :label "Tempo"
                     :min 40.0 :max 180.0 :fmt "%.0f" :values (:tempo main)})
    (c/param-column {:group :main :param :pan :label "Pan"
                     :min -1.0 :max 1.0 :fmt "%.2f" :values (:pan main)})]})
