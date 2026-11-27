(ns musics.gui.settings
  "A window of the session's tctx -- the settings a voice that is a function of the onset reads
   (:steps, :lag, :soft …), one row each, without the rest of the GUI:

     (open-settings!)        ; mu: :tctx gui
     (open-settings! :bass)  ; mu: :bass gui, the window scrolled to the bass's section

   The shared settings come first, then a section for each voice that has settings of its own
   (:bass/steps, and the inputs of a word voice, :bass/onsets): its keys without the voice's
   name, an input in the range of the algo parameter of the same name (a store is checked against
   it; a refused value is shown, not applied), another key a free slider, and a button to take
   such a key out of the scope (T_), so the voice reads the shared one again.

   A number is a slider, whole numbers whole, with the buttons z and Z beside it (zoom in, zoom
   out: ui/zoom-bounds, ui/zoom-out-bounds). A setting has no range of its own, so the first
   one shown is the value's own, symmetrical around it and including zero (8 is 0..16), and
   Z widens it from there; it is only what the slider shows, never a limit on the value. A list
   or a string is a text field read as EDN on Enter. A computed setting (a quotation given to
   T!) and a function of time are shown, not edited. A change is heard on the voice's next
   leaf, and one made elsewhere (8 :steps T!) moves the control."
  (:require [clojure.edn :as edn]
            [musics.domain.context :as c]
            [musics.gui.components :as ui]
            [musics.gui.state :as state]
            [musics.gui.theme :as theme]
            [musics.mu.inputs :as inputs]
            [musics.registries :as reg]))

;; cljfx.api and JavaFX are loaded when the window opens, not when this namespace is: loading
;; them starts the toolkit, which needs a display, and the logic above is tested without one.
(defn- fx [sym] (requiring-resolve (symbol "cljfx.api" (name sym))))

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

(defn- row-of
  "A row of the window: {:key :entry :kind :spec :label :input?}. voice is nil for a shared key."
  [voice [k e]]
  (let [nm (keyword (name k))
        sp (when voice (inputs/effective-spec (keyword voice) nm))]
    {:key k :entry e :kind (kind-of e) :spec sp :label (name k)
     :input? (boolean (and voice (some #{(name k)} (inputs/names (keyword voice)))))}))

(defn sections
  "[{:scope nil or \"bass\", :rows [row …]} …]: the shared keys first, by name, then a section for
   each voice that has keys of its own: its inputs in the order of the word's stack effect, then the
   rest by name. A row is {:key (the tctx's key, :bass/steps) :entry :kind :spec :label :input?}."
  []
  (let [m      @(:envelopes-atom reg/tctx)
        shared (->> m (remove (fn [[k _]] (namespace k))) (sort-by (comp name key)))
        voices (->> (keys m) (keep namespace) distinct sort)]
    (into [{:scope nil :rows (mapv #(row-of nil %) shared)}]
          (for [v voices
                :let [own   (->> m (filter (fn [[k _]] (= v (namespace k)))))
                      order (vec (inputs/names (keyword v)))
                      idx   (fn [[k _]] (let [i (.indexOf order (name k))] (if (neg? i) (count order) i)))]]
            {:scope v :rows (mapv #(row-of v %) (sort-by (juxt idx (comp name key)) own))}))))

(defn scope?
  "Does the tctx hold keys written with voice (a keyword or string) before the slash?"
  [voice]
  (let [n (name voice)]
    (boolean (some #(= n (namespace %)) (keys @(:envelopes-atom reg/tctx))))))

(defn entries
  "[[key entry kind] …] of every row of sections, what the slider ranges are kept for."
  [sections]
  (vec (for [{:keys [rows]} sections {:keys [key entry kind]} rows] [key entry kind])))

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

(defn- control
  "The control of one row. An input with a range is a slider in it (zoomed in the state's :zoom);
   a number without one a free slider (:ranges); a quotation, a function, a list or a string as
   before."
  [{:keys [ranges zoom]} {:keys [key entry kind spec label]}]
  (let [shown (symbol label)]
    (case kind
      (:int :ratio :double)
      (if (and spec (number? (:min spec)) (number? (:max spec)))
        (ui/param-control {:setting key} key entry spec
                          {:bounds (get zoom key) :event {:event/type ::zoom :spec? true} :out-event {:event/type ::zoom-out :spec? true}})
        (let [{:keys [full visible]} (get ranges key)]
          (ui/param-control {:setting key} key entry {:type kind :min (:min full) :max (:max full)}
                            {:bounds visible
                             :event {:event/type ::zoom :type kind}
                             :out-event {:event/type ::zoom-out :type kind}})))
      :computed
      (ui/label {:text (str shown " = " (or (:text entry) "computed") "   (computed: read, not edited)")})
      :function
      (ui/label {:text (str shown "   (a function of time: read, not edited)")})
      (ui/param-control {:setting key} key entry (cond-> {:type :edn} (and spec (:type spec)) (assoc :type (:type spec) :choices (:choices spec)))))))

(defn- row [st {:keys [key scope input?] :as r}]
  (let [ctrl (control st r)]
    (if (and scope (not input?))
      (ui/button-row {:children [ctrl (ui/button {:text "reset" :tooltip "take it out of the scope: the voice reads the shared one"
                                                  :on-action {:event/type ::reset :key key}})]})
      ctrl)))

(defn scroll-fraction
  "Where to put the scroll bar (0..1) so voice's section is in view: the rows before it, a header
   counting as one, over all of them. 0 for no voice, or one with no section."
  [sections voice]
  (if (nil? voice)
    0.0
    (let [size   #(inc (count (:rows %)))
          total  (reduce + (map size sections))
          before (reduce + (map size (take-while #(not= (name voice) (:scope %)) sections)))]
      (if (or (zero? total) (>= before total)) 0.0 (min 1.0 (/ (double before) total))))))

(defn view [{:keys [sections message showing theme focus] :as st}]
  {:fx/type :stage
   :showing showing
   :title "Settings — the tctx"
   :width 780
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
     [(cond-> (assoc (ui/scroll-pane
                       {:content
                        {:fx/type :v-box
                         :spacing 8
                         :children (if (some seq (map :rows sections))
                                     (vec (for [{:keys [scope rows]} sections :when (or (nil? scope) (seq rows))]
                                            (ui/titled-panel
                                              {:title (if scope (str ":" scope) "shared")
                                               :children (if (seq rows)
                                                           (mapv #(row st (assoc % :scope scope)) rows)
                                                           [(ui/label {:text "Nothing yet: 8 :steps T! puts a setting here."})])})))
                                     [(ui/label {:text "The tctx is empty: 8 :steps T! puts a setting here."})])}})
                     :v-box/vgrow :always)
        focus (assoc :vvalue (scroll-fraction sections focus)))
      (ui/label {:text (or message "")})
      (ui/button {:text "Close" :on-action {:event/type ::close}})]}}})

(defonce ^:private window (atom nil))   ; {:state :renderer}

(declare close!)

(defn- refresh [st]
  (let [secs (sections)]
    (-> st (assoc :sections secs) (update :ranges with-ranges (entries secs)))))

(defn- report! [st f]
  (try (f) (swap! st assoc :message nil)
       (catch Exception e (swap! st assoc :message (ex-message e)))))

(defn- spec-of
  "The spec of an input row's key, nil for any other."
  [k]
  (when (namespace k)
    (get (inputs/specs (keyword (namespace k))) (keyword (name k)))))

(defn- store!
  "Make key hold x: an input of a voice through its spec (a value it refuses is an error), any
   other key as it is."
  [k x]
  (if (spec-of k)
    (inputs/put! (keyword (namespace k)) (name k) x)
    (c/tctx! reg/tctx k x)))

(defn- value-of [k] (get @(:envelopes-atom reg/tctx) k))

(defn- handler [st]
  (fn [event]
    (case (:event/type event)
      :set-tree-param      (report! st #(store! (:key event) (ui/param-input event)))
      :set-tree-param-text (report! st #(store! (:key event) (edn/read-string (ui/param-input event))))
      ::zoom     (let [k (:key event)]
                   (if (:spec? event)
                     (swap! st update :zoom assoc k (ui/zoom-bounds (spec-of k) (get-in @st [:zoom k]) (value-of k)))
                     (swap! st (fn [s] (update s :ranges zoom-in k (:type event) (value-of k))))))
      ::zoom-out (let [k (:key event)]
                   (if (:spec? event)
                     (swap! st update :zoom assoc k (ui/zoom-out-bounds (spec-of k) (get-in @st [:zoom k]) (value-of k)))
                     (swap! st (fn [s] (update s :ranges zoom-out k (:type event) (value-of k))))))
      ::reset    (report! st #(c/tctx-forget reg/tctx (:key event)))
      ::close    (close!)
      nil)))

(defn close!
  "Close the settings window."
  []
  (when-let [{:keys [state renderer]} @window]
    (remove-watch (:envelopes-atom reg/tctx) ::window)
    (swap! state assoc :showing false)
    ((fx 'unmount-renderer) state renderer)
    (reset! window nil))
  nil)

(defn open-settings!
  "A window of the session's tctx, a control each, the shared settings first and a section for
   each voice. With a voice (a keyword), the window is scrolled to its section. One window."
  ([] (open-settings! nil))
  ([voice]
   (clojure.lang.Reflector/invokeStaticMethod "javafx.application.Platform" "setImplicitExit"
                                              (object-array [false]))   ; closing this window mustn't end JavaFX
   (if-let [{:keys [state]} @window]
     (swap! state (fn [s] (-> s refresh (assoc :showing true :focus (some-> voice name)))))
     (let [st (atom (refresh {:showing true :theme (:theme @state/*state :dark) :ranges {} :zoom {}
                              :focus (some-> voice name)}))
           r  ((fx 'create-renderer)
               :middleware ((fx 'wrap-map-desc) view)
               :opts {:fx.opt/map-event-handler (handler st)})]
       (add-watch (:envelopes-atom reg/tctx) ::window (fn [_ _ _ _] (swap! st refresh)))
       (reset! window {:state st :renderer r})
       ((fx 'mount-renderer) st r)))
   nil))
