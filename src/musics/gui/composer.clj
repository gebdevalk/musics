(ns musics.gui.composer
  "The tree composer window behind (build-tree): a canvas showing the
   tree with its brackets, a pane of algo categories -> algos on the
   right, drag and drop from the pane onto the canvas.

   The rules are musics.algo.tree.builder's (the same model the REPL twin,
   repl-build, uses): an algo may go only where its output fits; the
   pane dims what doesn't fit the active slot; dropping on a hole fills
   it, on a node replaces it, on the empty canvas makes the root. Once
   the tree is complete its settings appear under the canvas. Finalize
   closes the window and delivers [tree tctx]; closing it delivers nil."
  (:require [musics.algo.tree :as t]
            [musics.algo.tree.builder :as b]
            [cljfx.api :as fx]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [musics.gui.components :as ui]
            [musics.gui.params :as params]
            [musics.gui.state :as state]
            [musics.gui.theme :as theme])
  (:import [javafx.application Platform]
           [javafx.scene.input ClipboardContent TransferMode]))

;; ---------------------------------------------------------------------------
;; Styles
;; ---------------------------------------------------------------------------

(def ^:private mono "-fx-font-family: monospace; ")

(defn- slot-style [{:keys [active? over]}]
  (str mono "-fx-padding: 2 6; -fx-border-radius: 3; -fx-background-radius: 3; "
       (cond (= over :fits) "-fx-background-color: rgba(60,170,90,0.35); "
             (= over :no)   "-fx-background-color: rgba(200,60,60,0.30); "
             :else "")
       (if active? "-fx-border-color: #3d8bd9; -fx-border-width: 2; "
                   "-fx-border-color: rgba(128,128,128,0.6); -fx-border-width: 1; ")))

(def ^:private hole-extra "-fx-border-style: dashed; ")

;; ---------------------------------------------------------------------------
;; The canvas
;; ---------------------------------------------------------------------------

(defn- drop-props
  "Click selects; a dragged algo is accepted here only if it fits."
  [path]
  {:on-mouse-clicked {:event/type ::select :path path}
   :on-drag-over     {:event/type ::drag-over :path path}
   :on-drag-entered  {:event/type ::drag-entered :path path}
   :on-drag-exited   {:event/type ::drag-exited :path path}
   :on-drag-dropped  {:event/type ::drag-dropped :path path}})

(defn- slot-view [{:keys [draft over]} hole-num slot path]
  (let [style {:active? (= path (:active draft)) :over (when (= path (first over)) (second over))}]
    (cond
      (nil? slot)
      (merge {:fx/type :label
              :text (str "[ " (hole-num path) "  " (name (b/slot-type draft path)) " ]")
              :style (str (slot-style style) hole-extra)}
             (drop-props path))

      (:algo slot)
      {:fx/type :v-box
       :spacing 2
       :children
       (concat
         [(merge {:fx/type :label
                  :text (str "( " (name (:algo slot)) (when (:as slot) (str "  :as " (:as slot))))
                  :style (str (slot-style style) "-fx-font-weight: bold; ")}
                 (drop-props path))]
         [{:fx/type :v-box
           :spacing 2
           :style "-fx-padding: 0 0 0 26;"
           :children (vec (map-indexed (fn [i c] (slot-view {:draft draft :over over} hole-num c (conj path i)))
                                       (:children slot)))}]
         [{:fx/type :label :text ")" :style mono}])}

      :else
      (merge {:fx/type :label
              :text (if (:param slot) (str (:param slot)) (pr-str (:literal slot)))
              :style (slot-style style)}
             (drop-props path)))))

(defn- canvas [{:keys [draft] :as st}]
  (let [holes    (b/holes draft)
        hole-num (fn [p] (let [i (.indexOf ^java.util.List holes p)]
                           (if (<= 0 i 19) (str (nth "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳" i)) (str (inc i)))))]
    (if (:root draft)
      (slot-view st hole-num (:root draft) [])
      (merge {:fx/type :label
              :text "Drop an algo here to start -- the root (usually from output: notes)"
              :style (str (slot-style {:active? true :over (when (= [] (first (:over st))) (second (:over st)))})
                          hole-extra "-fx-padding: 40 20;")}
             (drop-props [])))))

(defn- settings-view [{:keys [tctx-value]}]
  (when tctx-value
    [(ui/titled-panel
       {:title "Settings"
        :children (vec (for [[algo rows] (params/param-groups tctx-value)
                             c (cons (ui/label {:text (name algo) :style "-fx-font-weight: bold;"})
                                     (for [[k v spec] rows] (ui/param-control {} k v spec)))]
                         c))})]))

;; ---------------------------------------------------------------------------
;; The pane
;; ---------------------------------------------------------------------------

(defn- category-row [{:keys [name count fit]}]
  {:fx/type :button
   :text (format "%-10s %d / %d" name fit count)
   :max-width Double/MAX_VALUE
   :style (str mono (when (zero? fit) "-fx-opacity: 0.45;"))
   :on-action {:event/type ::open-category :category name}})

(defn- algo-row [{:keys [short in out doc fits?]}]
  (cond-> {:fx/type :v-box
           :style (str "-fx-padding: 3 6; -fx-border-color: transparent transparent rgba(128,128,128,0.3) transparent; "
                       (if fits? "-fx-cursor: hand;" "-fx-opacity: 0.4;"))
           :children [{:fx/type :label :style (str mono "-fx-font-weight: bold;")
                       :text (str (name short) "   " (str/join " " (map name in)) " → " (name out))}
                      {:fx/type :label :text (or doc "") :wrap-text true :style "-fx-font-size: 11;"}]}
    fits? (assoc :on-drag-detected {:event/type ::drag-start :short short}
                 :on-mouse-clicked {:event/type ::place :short short})))

(defn- pane [{:keys [draft category literal param]}]
  {:fx/type :v-box
   :spacing 6
   :pref-width 360
   :min-width 300
   :style "-fx-padding: 8;"
   :children
   [{:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [(ui/button {:text "◀ Back" :disabled? (nil? category) :on-action {:event/type ::back}})
                (ui/label {:text (or category "categories") :style "-fx-font-weight: bold;"})]}
    (ui/label {:text (let [a (:active draft)]
                       (if (b/slot-at draft a) "active: a node -- a drop replaces it"
                           (str "active slot needs: " (name (b/slot-type draft a)))))})
    {:fx/type :scroll-pane
     :fit-to-width true
     :v-box/vgrow :always
     :content {:fx/type :v-box
               :spacing 2
               :children (if category
                           (mapv algo-row (b/algos-in draft category))
                           (mapv category-row (b/categories draft)))}}
    (ui/label {:text "literal in the active slot (EDN, Enter)"})
    (ui/text-field {:text literal :prompt "[60 64 67]"
                    :on-text-changed {:event/type ::literal-text}
                    :on-action {:event/type ::place-literal}})
    (ui/label {:text "param keyword in the active slot (Enter)"})
    (ui/text-field {:text param :prompt ":nodes"
                    :on-text-changed {:event/type ::param-text}
                    :on-action {:event/type ::place-param}})]})

;; ---------------------------------------------------------------------------
;; The window
;; ---------------------------------------------------------------------------

(defn- view [{:keys [draft message showing theme] :as st}]
  {:fx/type :stage
   :showing showing
   :title "Compose a tree"
   :width 1180
   :height 760
   :on-close-request {:event/type ::cancel}
   :scene
   {:fx/type :scene
    :stylesheets [(theme/stylesheet theme)]
    :root
    {:fx/type :v-box
     :on-key-pressed {:event/type ::key}
     :children
     [{:fx/type :h-box
       :v-box/vgrow :always
       :children
       [{:fx/type :scroll-pane
         :h-box/hgrow :always
         :fit-to-width true
         :content {:fx/type :v-box
                   :spacing 12
                   :style "-fx-padding: 14;"
                   :children (into [(canvas st)] (settings-view st))}}
        (pane st)]}
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :style "-fx-padding: 8;"
       :children [(ui/button {:text "Undo" :disabled? (not (b/can-undo? draft)) :on-action {:event/type ::undo}})
                  (ui/button {:text "Redo" :disabled? (not (b/can-redo? draft)) :on-action {:event/type ::redo}})
                  (ui/button {:text "Remove" :disabled? (nil? (b/slot-at draft (:active draft)))
                              :on-action {:event/type ::remove}})
                  {:fx/type :label :h-box/hgrow :always :max-width Double/MAX_VALUE
                   :style mono :text (if (:root draft) (b/render draft) "")}
                  (ui/label {:text (or message "") :style "-fx-text-fill: #d9534f;"})
                  (ui/button {:text "Cancel" :on-action {:event/type ::cancel}})
                  (ui/button {:text "Finalize" :disabled? (not (b/complete? draft))
                              :on-action {:event/type ::finalize}})]}]}}})

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(defn- change!
  "Apply f to the draft; on a rule violation keep it and show why. The
   settings follow the tree whenever it's complete
   (musics.algo.tree.builder/settings-for) and are kept while it isn't."
  [st f]
  (try
    (let [d (f (:draft @st))
          s (or (b/settings-for d (:settings @st)) (:settings @st))]
      (swap! st assoc :draft d :settings s :message nil
             :tctx-value (when (b/complete? d) (some-> s deref))))
    (catch Exception e (swap! st assoc :message (ex-message e)))))

(defn- dragged [^javafx.scene.input.DragEvent e]
  (some-> e .getDragboard .getString keyword))

(defn- close! [st result r]
  (when-not (realized? (:result @st))
    (deliver (:result @st) result)
    (swap! st assoc :showing false)
    (fx/unmount-renderer st r)))

(defn- handler [st r]
  (fn [event]
    (let [d (:draft @st)]
      (case (:event/type event)
        ::open-category (swap! st assoc :category (:category event))
        ::back          (swap! st assoc :category nil)
        ::select        (swap! st update :draft b/select (:path event))
        ::place         (do (change! st #(b/place % (:short event) (:active %)))
                            (swap! st assoc :category nil))
        ::literal-text  (swap! st assoc :literal (:fx/event event))
        ::param-text    (swap! st assoc :param (:fx/event event))
        ::place-literal (change! st #(b/place-literal % (edn/read-string (:literal @st)) (:active %)))
        ::place-param   (change! st #(b/place-literal % (keyword (str/replace (str/trim (:param @st)) #"^:" ""))
                                                      (:active %)))
        ::undo          (change! st b/undo)
        ::redo          (change! st b/redo)
        ::key           (let [^javafx.scene.input.KeyEvent e (:fx/event event)
                              k (.getCode e)]
                          (when (.isShortcutDown e)
                            (cond (and (= k javafx.scene.input.KeyCode/Z) (.isShiftDown e)) (change! st b/redo)
                                  (= k javafx.scene.input.KeyCode/Z) (change! st b/undo)
                                  (= k javafx.scene.input.KeyCode/Y) (change! st b/redo))))
        ::remove        (change! st #(b/remove % (:active %)))

        ::drag-start
        (let [^javafx.scene.input.MouseEvent e (:fx/event event)
              db (.startDragAndDrop ^javafx.scene.Node (.getSource e) (into-array TransferMode [TransferMode/COPY]))]
          (.setContent db (doto (ClipboardContent.) (.putString (name (:short event)))))
          (.consume e))

        ::drag-over
        (let [^javafx.scene.input.DragEvent e (:fx/event event)]
          (when-let [s (dragged e)]
            (when (b/fits? d (:path event) s)
              (.acceptTransferModes e (into-array TransferMode [TransferMode/COPY]))))
          (.consume e))

        ::drag-entered
        (let [e (:fx/event event) s (dragged e)]
          (when s (swap! st assoc :over [(:path event) (if (b/fits? d (:path event) s) :fits :no)])))

        ::drag-exited   (swap! st assoc :over nil)

        ::drag-dropped
        (let [^javafx.scene.input.DragEvent e (:fx/event event) s (dragged e)]
          (when s (change! st #(b/place % s (:path event))))
          (swap! st assoc :over nil :category nil)
          (.setDropCompleted e (boolean s))
          (.consume e))

        (:set-tree-param :set-tree-param-text)
        (try (let [v (ui/param-input event)]
               (t/setp! (:settings @st) (:key event) (if (string? v) (edn/read-string v) v))
               (swap! st assoc :tctx-value @(:settings @st) :message nil))
             (catch Exception e (swap! st assoc :message (ex-message e))))

        ::finalize (try (close! st (b/finalize d (:settings @st)) r)
                        (catch Exception e (swap! st assoc :message (ex-message e))))
        ::cancel   (close! st nil r)
        nil))))

(defn open!
  "Open the composer on `draft` (an musics.algo.tree.builder draft), with
   `settings` (a tctx, or nil). Returns a promise of [tree tctx], or nil
   when the window is closed instead."
  [draft settings]
  (Platform/setImplicitExit false)
  (let [result (promise)
        st     (atom {:draft draft :category nil :literal "" :param ""
                      :settings (b/settings-for draft settings) :showing true
                      :theme (:theme @state/*state :dark) :result result})
        _      (swap! st assoc :tctx-value (some-> (:settings @st) deref))
        r      (atom nil)
        rend   (fx/create-renderer
                 :middleware (fx/wrap-map-desc view)
                 :opts {:fx.opt/map-event-handler (fn [e] ((handler st @r) e))})]
    (reset! r rend)
    (fx/mount-renderer st rend)
    result))
