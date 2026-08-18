(ns gui.lib.core
  "cljfx entry point: composes gui.lib.components into three kinds of
  windows (the View), and dispatches cljfx event-maps into
  gui.lib.state's real write-through functions (the Controller).
  gui.lib.state is the Model -- see its own docstring, including the
  two features ported from the original JavaFX GUI
  (cljmusics/.../musics/gui/components.clj): zoom! (the 'Z' button's
  circular in/in/in/out-to-the-beginning range narrowing, per slider)
  and toggle-hot!/hot? (the 'R' record-arm button's green/red cold/hot
  gate, per container -- see hot-toggle below).

  THREE SEPARATE WINDOWS, not one: a single always-open 'state' window
  (transport + the watch-a-container control), a dedicated 'root'
  window for :ROOT's own live-editable defaults (opened from the
  state window, see :open-root), and one 'context' window per other
  watched container, opened/closed automatically as watch!/unwatch!
  add/remove it from gui.lib.state's :watched map.

  cljfx's own renderer/mount machinery (cljfx.renderer/mount) is just
  `(add-watch *ref key renderer)`, keyed by the renderer fn's own
  identity -- so mounting several independent renderers against the
  SAME state atom is the ordinary, supported way to get several
  independent windows that all react to one Model, no special
  multi-stage extension needed. The context windows are the only ones
  that come and go at runtime: sync-context-windows! is a watch on
  *state that mounts a fresh renderer for each newly-watched id and
  unmounts it the moment that id leaves :watched."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [cljfx.api :as fx]
    [gui.lib.components :as ui]
    [gui.lib.state :as state]
    [gui.lib.theme :as theme]))

;; ============================================================
;; Shared content -- the slider/combo rows a container's own values
;; render as, reused inside both the root window and every context
;; window.
;; ============================================================

(defn- zoomable-slider
  "A param slider paired with a 'Z' button -- see gui.lib.state/zoom!'s
   own docstring for the circular in/in/in/out-to-the-beginning
   behavior it drives. bounds is id's own [:zoom key] entry, or nil to
   use spec's full :min/:max (the un-zoomed default).
   The slider itself is wrapped in ui/recreate-on-key-changed, keyed by
   [lo hi] -- see that component's own docstring: JavaFX's Slider skin
   doesn't reliably redraw when :min/:max change on an already-showing
   control, in or out, so every zoom step gets a genuinely fresh
   Slider instance instead of trusting an in-place update to render."
  [id key spec value bounds show-labels?]
  (let [lo (get bounds :min (:min spec))
        hi (get bounds :max (:max spec))]
    (ui/button-row
      {:children
       [{:fx/type ui/recreate-on-key-changed
         :key [lo hi]
         :desc (ui/slider
                 (assoc spec
                        :min lo
                        :max hi
                        :value value
                        :show-label? show-labels?
                        :on-change {:event/type :set-param :id id :key key}))}
        (ui/button {:text "Z" :on-action {:event/type :zoom :id id :key key}})]})))

(defn- param-rows
  "Empty when collapsed? (the 'S' show/hide control) -- otherwise every
   param slider + combo row for id, honoring show-labels? (the 'L'
   control) throughout."
  [id {:keys [params combos zoom collapsed? show-labels?]}]
  (if collapsed?
    []
    (-> []
        (into (map (fn [[key spec]]
                     (zoomable-slider id key spec (get params key 0.0) (get zoom key) show-labels?))
                   state/param-specs))
        (into (map (fn [[key spec]]
                     (ui/combo-box
                       {:label (:label spec)
                        :items (:items (:lookup spec))
                        :value (get combos key)
                        :show-label? show-labels?
                        :on-change {:event/type :set-combo :id id :key key}}))
                   state/combo-specs)))))

(defn- hot-toggle
  [id hot?]
  (ui/toggle-button
    {:text (if hot? "HOT" "cold")
     :selected? (boolean hot?)
     :style (str "-fx-font-weight: bold; -fx-background-color: "
                 (if hot? "red;" "green;"))
     :on-action {:event/type :toggle-hot :id id}}))

(defn- group-controls
  "The U/S/L row for a container window, ported from the original
   JavaFX GUI's per-voice-array controls but reinterpreted for this
   app's :PAR-shaped tree (see gui.lib.state's own docstring): U gangs
   id's slider/combo writes onto every other unified? watched id
   sharing that key, S collapses id's own body, L hides id's own
   labels. hot-toggle rides along in the same row since all four are
   'how does this container's own window behave' controls."
  [id {:keys [hot? unified? collapsed? show-labels?]}]
  (ui/button-row
    {:children
     [(hot-toggle id hot?)
      (ui/toggle-button {:text "U" :selected? (boolean unified?)
                         :on-action {:event/type :toggle-unified :id id}})
      (ui/toggle-button {:text "S" :selected? (boolean collapsed?)
                         :on-action {:event/type :toggle-collapsed :id id}})
      (ui/toggle-button {:text "L" :selected? (not show-labels?)
                         :on-action {:event/type :toggle-labels :id id}})]}))

;; ============================================================
;; State window -- transport + watch control. Always open.
;; ============================================================

(defn- transport-bar
  [transport theme]
  (ui/titled-panel
    {:title (str "Transport (" (name transport) ")")
     :children
     [(ui/button-row
        {:children
         [(ui/button {:text "Connect" :on-action {:event/type :connect}})
          (ui/button {:text "Play"    :on-action {:event/type :play}})
          (ui/button {:text "Pause"   :on-action {:event/type :pause}})
          (ui/button {:text "Resume"  :on-action {:event/type :resume}})
          (ui/button {:text "Stop"    :on-action {:event/type :stop}})
          (ui/button {:text "Abort"   :on-action {:event/type :abort}})
          (ui/button {:text "Reset"   :on-action {:event/type :reset}})
          (ui/button {:text (if (= theme :dark) "☀ Light" "🌙 Dark")
                      :on-action {:event/type :toggle-theme}})]})]}))

(defn- watch-row
  [new-id]
  (ui/button-row
    {:children
     [(ui/text-field
        {:text new-id
         :prompt "container id, e.g. verse"
         :on-text-changed {:event/type :set-new-id}
         :on-action {:event/type :watch}})
      (ui/button {:text "Watch" :on-action {:event/type :watch}})
      (ui/button {:text "Root panel..." :on-action {:event/type :open-root}})]}))

(defn- voices-panel
  "'Access to the actually playing voices and the committed voices that
   wait for activation' -- playing-ids is mirrored from
   core.async-engine (see gui.lib.state/start-voice-poll!); waiting-ids
   is every other committed top-level id. Purely informational (no
   watch!/play here) -- click Watch above, or Play, to act on one."
  [playing-ids]
  (ui/titled-panel
    {:title "Voices"
     :children
     [(ui/label {:text (str "Playing: " (str/join ", " (map name (sort playing-ids))))})
      (ui/label {:text (str "Waiting: " (str/join ", " (map name (state/waiting-ids))))})]}))

(defn- state-view
  [{:keys [transport new-id watched playing-ids theme]}]
  {:fx/type :stage
   :showing true
   :title "Musics — state"
   :width 720
   :height 320
   :scene
   {:fx/type :scene
    :stylesheets [(theme/stylesheet theme)]
    :root
    {:fx/type :v-box
     :spacing 8
     :style "-fx-padding: 8;"
     :children
     [(transport-bar transport theme)
      (watch-row new-id)
      (voices-panel (or playing-ids #{}))
      (ui/label {:text (str "Watching: " (str/join ", " (map name (keys (dissoc watched :ROOT)))))})]}}})

;; ============================================================
;; Root window -- :ROOT's own live-editable defaults. Toggled from
;; the state window; closing it (its own Close button, or the OS
;; window-close) just hides it, via :root-open? -- see close-root!.
;; ============================================================

(def ^:private default-entry
  {:params {} :combos {} :hot? false :unified? false :collapsed? false :show-labels? true})

(defn- scrollable-param-rows
  "param-rows for id/entry, wrapped in a scroll pane that grows to fill
   whatever space its window leaves it (:v-box/vgrow :always) -- see
   ui/scroll-pane's own docstring for why this is needed at all now."
  [id entry]
  (assoc (ui/scroll-pane
           {:content {:fx/type :v-box :spacing 8 :children (vec (param-rows id entry))}})
         :v-box/vgrow :always))

(defn- root-view
  [{:keys [root-open? watched theme]}]
  (let [entry (get watched :ROOT default-entry)]
    {:fx/type :stage
     :showing (boolean root-open?)
     :title "Musics — Root (session defaults)"
     :width 780
     ;; 4 slider-rows (~33px each incl. spacing) shorter than a plain
     ;; fit-everything height -- the scroll pane (see scrollable-
     ;; param-rows) makes the rest reachable by scrolling instead.
     :height 350
     :on-close-request {:event/type :close-root}
     :scene
     {:fx/type :scene
      :stylesheets [(theme/stylesheet theme)]
      :root
      {:fx/type :v-box
       :spacing 8
       :style "-fx-padding: 8;"
       :children
       [(group-controls :ROOT entry)
        (scrollable-param-rows :ROOT entry)
        (ui/button {:text "Close" :on-action {:event/type :close-root}})]}}}))

;; ============================================================
;; Context windows -- one per watched non-:ROOT container id, created
;; and destroyed dynamically as watch!/unwatch! change :watched.
;; ============================================================

(defn- context-view
  [id]
  (fn [{:keys [watched playing-ids theme]}]
    (let [entry (get watched id default-entry)
          status (if (contains? playing-ids id) "▶ playing" "committed, waiting")]
      {:fx/type :stage
       :showing true
       :title (str "Musics — " (name id) " (" status ")")
       :width 780
       ;; 4 slider-rows shorter -- see root-view's own comment on this.
       :height 350
       :on-close-request {:event/type :unwatch :id id}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(group-controls id entry)
          (scrollable-param-rows id entry)
          (ui/button {:text "Unwatch" :on-action {:event/type :unwatch :id id}})]}}})))

;; ============================================================
;; Controller
;; ============================================================

(defn- handle-event
  [{:keys [event/type] :as event}]
  (case type
    :set-param      (state/set-param! (:id event) (:key event) (:fx/event event))
    :set-combo      (state/set-combo! (:id event) (:key event) (:fx/event event))
    :zoom           (state/zoom! (:id event) (:key event))
    :toggle-hot     (state/toggle-hot! (:id event))
    :toggle-unified (state/toggle-unified! (:id event))
    :toggle-collapsed (state/toggle-collapsed! (:id event))
    :toggle-labels  (state/toggle-labels! (:id event))
    :toggle-theme   (state/toggle-theme!)
    :set-new-id     (state/set-new-id! (:fx/event event))
    :watch          (state/watch! (:new-id @state/*state))
    :unwatch        (state/unwatch! (:id event))
    :open-root      (state/open-root!)
    :close-root     (state/close-root!)
    :connect        (state/connect!)
    :play           (state/play!)
    :pause          (state/pause!)
    :resume         (state/resume!)
    :stop           (state/stop!)
    :abort          (state/abort!)
    :reset          (state/reset!)))

;; ============================================================
;; Renderers + dynamic context-window mounting
;; ============================================================

(defn- mk-renderer
  [desc-fn]
  (fx/create-renderer
    :middleware (fx/wrap-map-desc (fn [state] (assoc state :fx/type desc-fn)))
    :opts {:fx.opt/map-event-handler handle-event}))

(def ^:private state-renderer (mk-renderer state-view))
(def ^:private root-renderer (mk-renderer root-view))

;; id -> mounted renderer for that id's own context window -- tracked
;; so sync-context-windows! knows what to unmount when an id leaves
;; :watched. defonce (not def) so reloading this ns doesn't orphan
;; already-mounted renderers.
(defonce ^:private context-renderers (atom {}))

(defn- watched-context-ids
  [state]
  (disj (set (keys (:watched state))) :ROOT))

(defn- sync-context-windows!
  [_key _ref old-state new-state]
  (let [old-ids (watched-context-ids old-state)
        new-ids (watched-context-ids new-state)]
    (doseq [id (set/difference new-ids old-ids)]
      (let [r (mk-renderer (context-view id))]
        (swap! context-renderers assoc id r)
        (fx/mount-renderer state/*state r)))
    (doseq [id (set/difference old-ids new-ids)]
      (when-let [r (get @context-renderers id)]
        (fx/unmount-renderer state/*state r)
        (swap! context-renderers dissoc id)))))

(defn launch!
  "Start the GUI: the state window (always open), watch for
   :open-root/:watch/:unwatch to open/close the root and per-container
   context windows. Idempotent -- calling it again while already
   running just re-renders (and re-themes, if theme differs), it
   doesn't double-mount.
   theme is :dark (default) or :light -- see gui.lib.theme -- applied
   to every window's Scene; also switchable live from the state
   window's own toggle button, or (gui.lib.state/set-theme! theme)."
  ([] (launch! :dark))
  ([theme]
   (state/set-theme! theme)
   (fx/mount-renderer state/*state state-renderer)
   (fx/mount-renderer state/*state root-renderer)
   (add-watch state/*state ::context-windows sync-context-windows!)
   (sync-context-windows! ::context-windows state/*state {:watched {}} @state/*state)
   (state/start-voice-poll!)
   nil))

(defn -main
  [& args]
  (launch! (if-let [t (first args)] (keyword t) :dark)))
