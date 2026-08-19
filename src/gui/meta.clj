(ns gui.meta
  (:require
    [musics.engine :refer [play-records start-engine stop-engine]]
    [musics.global :refer [voice-count get-value set-value run-later]]
    [musics.gui.components :refer [create-button
                                   create-record-toggle-button
                                   create-toggle-button
                                   record-buttons-from-aggregate-control
                                   set-record-button-style stage
                                   toggle-record-button]]
    [musics.gui.global :refer :all]
    [musics.gui.main :refer [package-main-aggregate-control]]
    [musics.gui.bifurcation :refer [package-befur-aggregate-control]]
    [musics.gui.indispensability :refer [package-indisps-aggregate-control]])
  (:import
    (javafx.scene Node SceneBuilder)
    (javafx.scene.layout HBoxBuilder)
    (javafx.stage StageBuilder)))

(def meta-control-stage (atom nil))

(defn create-meta-control-stage
  [title meta-components]
  "layout:
   stage
   |  scene
   |  |  root(HBox)
   |  |  |  children"
  (let [hbox
        (.. HBoxBuilder create
            (style (str "-fx-padding: 2;" "-fx-background-color: black;"))
            (spacing 2)
            (prefWidth 20)
            (maxWidth 480.)
            (children meta-components)
            build)
        scene
        (.. SceneBuilder create
            (root hbox)
            build)
        stage
        (.. StageBuilder create
            (title title)
            (scene scene)
            build)]
    stage))

(defn- add-to-or-remove-from-scene
  [^Node aggregate-control]
  (let [id (-> aggregate-control .getId)
        aggregate-controls (-> @stage .getScene .getRoot .getChildren)
        current-aggregate-control (first (filter #(= (.getId %) id) (seq aggregate-controls)))]
    (if (nil? current-aggregate-control)
      (-> aggregate-controls (.add aggregate-control))
      (-> aggregate-controls (.remove aggregate-control))))
  (.sizeToScene @stage)
  (if (not (-> @stage .isShowing))
    (run-later (.showAndWait @stage))))

(defn- toggle-record-buttons
  [record-buttons]
  (loop [buttons record-buttons]
    (when (> (count buttons) 0)
      (do
        (.fire (first buttons))
        (recur (rest buttons))))))

(defn record-buttons-from-aggregate-controls
  [aggregate-controls]
  (map record-buttons-from-aggregate-control aggregate-controls))

(defn create-meta-control-buttons
  []
  (let [min-width 80
        min-height 40
        main-aggregate-control (package-main-aggregate-control)
        bifurc-aggregate-control (package-befur-aggregate-control)
        indisps-aggregate-control (package-indisps-aggregate-control)
        button-main (create-button "Main" min-width min-height)
        button-bifurcation (create-button "Bifurc." min-width min-height)
        button-indisps (create-button "Indisps." min-width min-height)
        button-start (create-toggle-button ">" min-height min-height)
        button-stop (create-toggle-button "." min-height min-height)
        play-buttons
        (loop [coll [] index 0]
          (if (not (< index voice-count))
            coll
            (let [btn (create-record-toggle-button (str "P" (inc index)) min-height min-height)]
              (.setOnAction btn (event-handler [_] (do
                                                     (toggle-record-button btn)
                                                     (swap! (:play? (nth play-records index)) #(not %)))))
              (recur (conj coll btn) (inc index)))))

        button-record (create-record-toggle-button "R" min-height min-height)
        record-buttons (flatten (record-buttons-from-aggregate-controls
                                  [main-aggregate-control
                                   bifurc-aggregate-control
                                   indisps-aggregate-control]))
        buttons (flatten [button-main button-bifurcation button-indisps
                          button-start button-stop
                          play-buttons
                          button-record])]

    (.setOnAction button-main (event-handler [_] (add-to-or-remove-from-scene main-aggregate-control)))
    (.setOnAction button-bifurcation (event-handler [_] (add-to-or-remove-from-scene bifurc-aggregate-control)))
    (.setOnAction button-indisps (event-handler [_] (add-to-or-remove-from-scene indisps-aggregate-control)))

    (.setOnAction button-start (event-handler [_] (start-engine)))
    (.setOnAction button-stop (event-handler [_] (stop-engine)))

    (.setOnAction button-record (event-handler [_] (do
                                                     (toggle-record-buttons record-buttons)
                                                     (set-record-button-style button-record))))
    buttons))
