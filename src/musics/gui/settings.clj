(ns musics.gui.settings
  "A window of the session's tctx -- the settings a voice that is a function of the beat reads
   (:steps, :lag, :soft …), one row each, without the rest of the GUI:

     (open-settings!)        ; mu: :tctx gui

   A number is a slider, whole numbers whole, with the buttons z and Z beside it (zoom in, zoom
   out: ui/zoom-bounds, ui/zoom-out-bounds). A setting has no range of its own, so the first
   one shown is the value's own, symmetrical around it and including zero (8 is 0..16), and
   Z widens it from there; it is only what the slider shows, never a limit on the value. A list
   or a string is a text field read as EDN on Enter. A computed setting (a quotation given to
   T!) and a function of time are shown, not edited. A change is heard on the voice's next
   leaf, and one made elsewhere (8 :steps T!) moves the control."
  (:require [cljfx.api :as fx]
            [clojure.edn :as edn]
            [musics.domain.context :as c]
            [musics.gui.components :as ui]
            [musics.gui.state :as state]
            [musics.gui.theme :as theme]
            [musics.registries :as reg])
  (:import [javafx.application Platform]))

;; ---------------------------------------------------------------------------
;; what the tctx holds, as rows
;; ---------------------------------------------------------------------------

(defn kind-of
  "How a tctx entry is shown: :int :ratio :double (a slider), :computed (a quotation given to
   T!), :function (a function of time), or :edn (any other value, a text field)."
  [entry]
  (cond
    (instance? musics.domain.context.Derived entry)   :computed
    (instance? musics.domain.context.Generator entry) :function
    (integer? entry)                                  :int
    (ratio? entry)                                    :ratio
    (and (number? entry) (Double/isFinite (double entry))) :double
    :else                                             :edn))

(defn entries
  "[[key entry kind] …] of the tctx, by name."
  []
  (->> @(:envelopes-atom reg/tctx)
       (sort-by (comp name key))
       (mapv (fn [[k e]] [k e (kind-of e)]))))

(defn- contains-value? [{:keys [min max]} v] (<= min v max))

(defn first-bounds
  "The visible range of a slider the first time its value is seen: the value's own, symmetrical
   around it and including zero (ui/zoom-out-bounds with nothing yet), widened until it holds the
   value (a ratio's or a whole number's are the same)."
  [kind v]
  (loop [b (ui/zoom-out-bounds {:type kind} nil v) n 0]
    (if (or (contains-value? b (double v)) (> n 40))
      b
      (recur (ui/zoom-out-bounds {:type kind} b v) (inc n)))))

(defn- hull [a b] {:min (min (:min a) (:min b)) :max (max (:max a) (:max b))})

(defn with-ranges
  "ranges ({key {:full :visible}}) brought up to the entries: a number seen for the first time
   gets its first range; one whose value has left the visible range gets that widened."
  [ranges entries]
  (reduce (fn [m [k e kind]]
            (if (#{:int :ratio :double} kind)
              (let [cur (get m k)
                    v   (double e)]
                (cond
                  (nil? cur) (let [b (first-bounds kind e)] (assoc m k {:full b :visible b}))
                  (contains-value? (:visible cur) v) m
                  :else (let [b (first-bounds kind e)]
                          (assoc m k {:full (hull (:full cur) b) :visible (hull (:visible cur) b)}))))
              m))
          ranges entries))

(defn zoom-in
  "ranges with key's visible range zoomed in around its value (ui/zoom-bounds: halved, and
   back out to the full range when too narrow)."
  [ranges k kind v]
  (let [{:keys [full visible]} (get ranges k)]
    (assoc-in ranges [k :visible]
              (ui/zoom-bounds {:type kind :min (:min full) :max (:max full)} visible v))))

(defn zoom-out
  "ranges with key's visible range widened around its value (ui/zoom-out-bounds), the full range
   with it."
  [ranges k kind v]
  (let [{:keys [full visible]} (get ranges k)
        b (ui/zoom-out-bounds {:type kind} visible v)]
    (-> ranges (assoc-in [k :visible] b) (assoc-in [k :full] (hull full b)))))

;; ---------------------------------------------------------------------------
;; the window
;; ---------------------------------------------------------------------------

(defn- row [{:keys [ranges]} [k e kind]]
  (case kind
    (:int :ratio :double)
    (let [{:keys [full visible]} (get ranges k)]
      (ui/param-control {:setting k} k e {:type kind :min (:min full) :max (:max full)}
                        {:bounds visible
                         :event {:event/type ::zoom :type kind}
                         :out-event {:event/type ::zoom-out :type kind}}))
    :computed
    (ui/label {:text (str (name k) " = " (or (:text e) "computed") "   (computed: read, not edited)")})
    :function
    (ui/label {:text (str (name k) "   (a function of time: read, not edited)")})
    (ui/param-control {:setting k} k e {:type :edn})))

(defn view [{:keys [entries message showing theme] :as st}]
  {:fx/type :stage
   :showing showing
   :title "Settings — the tctx"
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
                 :children (if (seq entries)
                             (mapv #(row st %) entries)
                             [(ui/label {:text "The tctx is empty: 8 :steps T! puts a setting here."})])}})
             :v-box/vgrow :always)
      (ui/label {:text (or message "")})
      (ui/button {:text "Close" :on-action {:event/type ::close}})]}}})

(defonce ^:private window (atom nil))   ; {:state :renderer}

(declare close!)

(defn- refresh [st]
  (let [es (entries)]
    (-> st (assoc :entries es) (update :ranges with-ranges es))))

(defn- report! [st f]
  (try (f) (swap! st assoc :message nil)
       (catch Exception e (swap! st assoc :message (ex-message e)))))

(defn- handler [st]
  (fn [event]
    (do
      (case (:event/type event)
        :set-tree-param      (report! st #(c/tctx! reg/tctx (:key event) (ui/param-input event)))
        :set-tree-param-text (report! st #(c/tctx! reg/tctx (:key event) (edn/read-string (ui/param-input event))))
        ::zoom     (swap! st (fn [s] (update s :ranges zoom-in (:key event) (:type event)
                                            (get @(:envelopes-atom reg/tctx) (:key event)))))
        ::zoom-out (swap! st (fn [s] (update s :ranges zoom-out (:key event) (:type event)
                                            (get @(:envelopes-atom reg/tctx) (:key event)))))
        ::close    (close!)
        nil))))

(defn close!
  "Close the settings window."
  []
  (when-let [{:keys [state renderer]} @window]
    (remove-watch (:envelopes-atom reg/tctx) ::window)
    (swap! state assoc :showing false)
    (fx/unmount-renderer state renderer)
    (reset! window nil))
  nil)

(defn open-settings!
  "A window of the session's tctx, a control each. One window."
  []
  (Platform/setImplicitExit false)             ; closing this window mustn't end JavaFX
  (if-let [{:keys [state]} @window]
    (swap! state assoc :showing true)
    (let [st (atom (refresh {:showing true :theme (:theme @state/*state :dark) :ranges {}}))
          r  (fx/create-renderer
               :middleware (fx/wrap-map-desc view)
               :opts {:fx.opt/map-event-handler (handler st)})]
      (add-watch (:envelopes-atom reg/tctx) ::window (fn [_ _ _ _] (swap! st refresh)))
      (reset! window {:state st :renderer r})
      (fx/mount-renderer st r)))
  nil)
