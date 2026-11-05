(ns musics.gui.params
  "A window of a live voice's inputs -- one control each, under the algo its
   parameter belongs to, without the rest of the GUI:

     (open-inputs! :bass)

   The voice's inputs are the model, both ways: a control stores into them
   (checked against the parameter's spec; a refused value is shown, not
   applied), and a change made elsewhere (:bass/onsets !) moves the control.
   A change is heard on the voice's next note. One window per voice."
  (:require [cljfx.api :as fx]
            [clojure.edn :as edn]
            [musics.gui.components :as ui]
            [musics.gui.state :as state]
            [musics.gui.theme :as theme]
            [musics.mu.inputs :as inputs])
  (:import [javafx.application Platform]))

(defn param-groups
  "[algo [[key value spec] ...]] in the voice's own param order, the params of one
   algo together under it."
  [{:keys [params specs]}]
  (->> (sort-by (comp :order val) specs)
       (map (fn [[k spec]] [k (get params k) spec]))
       (ui/group-by-algo (fn [[_ _ spec]] (:algo spec)))))

(defn- view [{:keys [title-text inputs-value message showing theme]}]
  {:fx/type :stage
   :showing showing
   :title (str "Inputs — " title-text)
   :width 620
   :height 520
   :on-close-request {:event/type ::close}
   :scene
   {:fx/type :scene
    :stylesheets [(theme/stylesheet theme)]
    :root
    {:fx/type :v-box
     :spacing 8
     :style "-fx-padding: 8;"
     :children
     [(assoc (ui/scroll-pane
               {:content
                {:fx/type :v-box
                 :spacing 8
                 :children (vec (for [[algo rows] (param-groups inputs-value)]
                                  (ui/titled-panel
                                    {:title (str (symbol algo))
                                     :children (vec (for [[k v spec] rows]
                                                      (ui/param-control {} k v spec)))})))}})
             :v-box/vgrow :always)
      (ui/label {:text (or message "")})
      (ui/button {:text "Close" :on-action {:event/type ::close}})]}}})

(defonce ^:private windows (atom {}))   ; voice -> {:state :renderer}

(declare close!)

(defn- report! [st f]
  (try (f) (swap! st assoc :message nil)
       (catch Exception e (swap! st assoc :message (ex-message e)))))

(defn- handler [st voice]
  (fn [event]
    (case (:event/type event)
      (:set-tree-param :set-tree-param-text)
      (report! st #(let [v (ui/param-input event)]
                     (inputs/put! voice (:key event) (if (string? v) (edn/read-string v) v))))
      ::close (close! voice)
      nil)))

(defn close!
  "Close the window of voice's inputs."
  [voice]
  (when-let [{:keys [state renderer]} (get @windows voice)]
    (swap! state assoc :showing false)
    (fx/unmount-renderer state renderer)
    (swap! windows dissoc voice))
  nil)

(defn open-inputs!
  "A window of a word voice's inputs, a control each. One window a voice."
  [voice]
  (Platform/setImplicitExit false)             ; closing this window mustn't end JavaFX
  (if-let [{:keys [state]} (get @windows voice)]
    (swap! state assoc :showing true)
    (let [st (atom {:title-text   (str ":" (name voice))
                    :inputs-value (inputs/state voice)
                    :showing      true
                    :theme        (:theme @state/*state :dark)})
          r  (fx/create-renderer
               :middleware (fx/wrap-map-desc view)
               :opts {:fx.opt/map-event-handler (handler st voice)})]
      (inputs/watch! voice ::window (fn [_ _] (swap! st assoc :inputs-value (inputs/state voice))))
      (swap! windows assoc voice {:state st :renderer r})
      (fx/mount-renderer st r)))
  voice)
