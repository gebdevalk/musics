(ns gui.lib.core
  "cljfx entry point: composes gui.lib.components into several kinds of
  windows (the View), and dispatches cljfx event-maps into
  gui.lib.state's real write-through functions (the Controller).
  gui.lib.state is the Model -- see its own docstring, including the
  two features ported from the original JavaFX GUI
  (cljmusics/.../musics/gui/components.clj): zoom! (the 'Z' button's
  circular in/in/in/out-to-the-beginning range narrowing, per slider)
  and toggle-hot!/hot? (the 'R' record-arm button's green/red cold/hot
  gate, per container -- see hot-toggle below).

  SEVERAL SEPARATE WINDOWS, not one: a single always-open 'state'
  window (transport + the watch-a-container control + buttons opening
  the three panels below), a dedicated 'root' window for :ROOT's own
  live-editable defaults (opened from the state window, see
  :open-root), one 'context' window per other watched container
  (opened/closed automatically as watch!/unwatch! add/remove it from
  gui.lib.state's :watched map), and three more always-available,
  toggle-open/closed windows covering what used to be REPL-only:
  'Editor' (parse/stage/commit musics text -- musics.core/parse/
  commit!/abort!/parse-file), 'Browser' (repo inspection --
  ids/children/leaves/print-structure/ctx/history/as-of), and 'Play
  Builder' (the full play/play-add/play-change mini-language, not just
  the state window's own fixed, always-sequential Play button).

  cljfx's own renderer/mount machinery (cljfx.renderer/mount) is just
  `(add-watch *ref key renderer)`, keyed by the renderer fn's own
  identity -- so mounting several independent renderers against the
  SAME state atom is the ordinary, supported way to get several
  independent windows that all react to one Model, no special
  multi-stage extension needed. The context windows are the only ones
  that come and go at runtime: sync-context-windows! is a watch on
  *state that mounts a fresh renderer for each newly-watched id and
  unmounts it the moment that id leaves :watched -- Editor/Browser/
  Play Builder are each a single always-mounted renderer instead,
  toggling :showing via their own -open? flag, same as the root window."
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [cljfx.api :as fx]
    [gui.lib.components :as ui]
    [gui.lib.state :as state]
    [gui.lib.theme :as theme])
  (:import (javafx.application Platform)
           (javafx.stage Stage FileChooser FileChooser$ExtensionFilter)))

;; ============================================================
;; show-on-top -- every window kind is wrapped in this (see the bottom
;; of state-view/root-view/context-view) so it's not just :showing
;; true in the description (which cljfx already turns into an ordinary
;; Stage/.show() call, see cljfx.fx.stage -- portable, not OS-specific)
;; but also comes to the front and takes focus the moment it's
;; actually created, instead of possibly appearing behind whatever
;; already has focus (the REPL, an editor, ...). .toFront/.requestFocus
;; are plain Stage methods, same on every platform JavaFX runs on --
;; nothing OS-conditional here, same as :showing itself.
;; ============================================================

(defn- show-on-top
  [stage-desc]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Stage stage]
                 (.toFront stage)
                 (.requestFocus stage)
                 ;; A brand-new Scene auto-focuses its own first
                 ;; focus-traversable control (confirmed live: the
                 ;; transport bar's Connect button -- the state
                 ;; window's own first button -- showed a highlighted
                 ;; focus ring on open, nothing actually clicked). The
                 ;; fix is stealing focus onto the window's own root
                 ;; pane instead (made focus-traversable here purely so
                 ;; requestFocus has somewhere non-control to land),
                 ;; deferred via Platform/runLater since the Scene/root
                 ;; aren't guaranteed attached yet at :on-created time
                 ;; the way the Stage itself already is -- .toFront/
                 ;; .requestFocus above needed no such deferral because
                 ;; they're Stage-level, not Scene-content-level.
                 (Platform/runLater
                   (fn []
                     (when-let [root (some-> stage .getScene .getRoot)]
                       (.setFocusTraversable root true)
                       (.requestFocus root)))))
   :desc stage-desc})

;; ============================================================
;; File chooser -- native Open/Save dialogs for the Editor's Load File
;; and Record MIDI's Write, both previously a bare path/name text
;; field only. Genuinely imperative (needs a real Window to parent the
;; dialog, blocks the calling thread until dismissed) -- handled here,
;; in the Controller, rather than gui.lib.state: which Window owns the
;; dialog isn't Model state, it's read straight off the ActionEvent
;; cljfx hands an :on-action handler as :fx/event, the same object
;; every plain button click already carries.
;; ============================================================

(defn- owner-window
  "The JavaFX Window a button-click ActionEvent's own button belongs to
   -- lets a chooser dialog parent itself correctly instead of opening
   ownerless. nil (via some->) if the event doesn't carry a Node source
   for some reason -- FileChooser accepts a nil owner too, it just
   won't be modal to any particular window."
  [fx-event]
  (some-> fx-event .getSource .getScene .getWindow))

(defn- mus-file-chooser
  [title]
  (let [fc (FileChooser.)]
    (.setTitle fc title)
    (.addAll (.getExtensionFilters fc)
             [(FileChooser$ExtensionFilter. "Musics files (*.mus)" ["*.mus"])
              (FileChooser$ExtensionFilter. "All files" ["*.*"])])
    fc))

(defn- session-file-chooser
  "write/load/persist-session/restore-session all write plain EDN text
   with no fixed extension convention of their own (unlike .mus) --
   .edn is offered as the default filter since that's the honest
   content type, but All files is right there too since a caller is
   free to pick anything."
  [title]
  (let [fc (FileChooser.)]
    (.setTitle fc title)
    (.addAll (.getExtensionFilters fc)
             [(FileChooser$ExtensionFilter. "EDN files (*.edn)" ["*.edn"])
              (FileChooser$ExtensionFilter. "All files" ["*.*"])])
    fc))

(defn- browse-editor-load-file!
  "Open-file dialog for the Editor's own :load-path field -- fills the
   field in, same as typing a path by hand; still requires a separate
   Load File click, no auto-parse surprise."
  [event]
  (let [window (owner-window (:fx/event event))
        file (.showOpenDialog (mus-file-chooser "Load musics file") window)]
    (when file
      (state/set-editor-load-path! (.getAbsolutePath file))))
  nil)

(defn- browse-record-save-file!
  "Save-file dialog for the Record panel's own :name field --
   write-record! always appends \".mus\" itself, so a chosen path
   already ending in .mus has that suffix stripped back off first to
   avoid a doubled extension; the rest of the chosen path (directory
   included) is kept as-is, since (spit ...) accepts an absolute path
   exactly as well as a bare name."
  [event]
  (let [fc (mus-file-chooser "Save recorded musics text")
        current (str/trim (:name (:record @state/*state) ""))
        window (owner-window (:fx/event event))]
    (.setInitialFileName fc (if (seq current) (str current ".mus") "recording.mus"))
    (when-let [file (.showSaveDialog fc window)]
      (let [path (.getAbsolutePath file)
            name (if (str/ends-with? path ".mus") (subs path 0 (- (count path) 4)) path)]
        (state/set-record-name! name))))
  nil)

(defn- browse-persistence-write-path!
  [event]
  (let [fc (session-file-chooser "Save session")
        current (str/trim (:write-path (:persistence @state/*state) ""))
        window (owner-window (:fx/event event))]
    (when (seq current) (.setInitialFileName fc current))
    (when-let [file (.showSaveDialog fc window)]
      (state/set-persistence-write-path! (.getAbsolutePath file))))
  nil)

(defn- browse-persistence-load-path!
  [event]
  (let [window (owner-window (:fx/event event))]
    (when-let [file (.showOpenDialog (session-file-chooser "Load session") window)]
      (state/set-persistence-load-path! (.getAbsolutePath file))))
  nil)

(defn- editor-save!
  "The Editor's own Save button -- writes straight back to :load-path if
   one's already set (loaded, or previously saved to), otherwise falls
   back to a Save dialog (same shape as a plain text editor's own
   Ctrl+S-with-no-file-yet behavior) -- the actual write is
   gui.lib.state/editor-save-to-path!, this just decides WHICH path,
   a Window-owning concern that belongs here, not in the Model."
  [event]
  (let [path (str/trim (:load-path (:editor @state/*state) ""))]
    (if (seq path)
      (state/editor-save-to-path! path)
      (let [fc (mus-file-chooser "Save musics file")
            window (owner-window (:fx/event event))]
        (when-let [file (.showSaveDialog fc window)]
          (state/editor-save-to-path! (.getAbsolutePath file))))))
  nil)

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

(def ^:private intent->opener
  "core.adviser :intent -> the event/type of the panels-row button that
   opens the panel where acting on that intent starts. Deliberately
   partial: :commit has no panels-row panel of its own (Commit lives
   inside the Editor panel's own buttons, not a top-level opener), and
   an intent of nil (the generic tier-2 pipeline reminder, or an empty
   candidate list) means 'nothing specific to point at' -- both cases
   correctly fall through suggested-opener below to no highlight at
   all, rather than a wrong or misleading guess."
  {:parse :open-editor
   :stage :open-editor
   :configure :open-wall
   :conductor :open-conductor
   :play :open-play-builder})

(defn- suggested-opener
  "The event/type of the panels-row button to highlight right now, or
   nil for none -- see intent->opener's own docstring for the
   deliberately-not-covered cases."
  []
  (intent->opener (state/suggested-intent)))

(def ^:private suggested-style
  "-fx-border-color: -fx-accent; -fx-border-width: 2; -fx-border-radius: 3; -fx-background-radius: 3;")

(defn- panels-row
  "Opens the five always-available windows -- Editor (parse/commit
   text), Browser (repo inspection), Play Builder (the full play/
   play-add/play-change mini-language), Wall (register-factory!/
   build!/assign-algo!), Conductor (register-action!/trigger!/
   schedule!/schedule-tx!) -- same toggle pattern as 'Root panel...'
   above. Uh? is its own thing: one click both opens the Adviser popup
   AND refreshes its suggestions (musics.core/uh?), rather than a
   plain open/close toggle.

   Whichever opener matches the adviser's own top suggestion right now
   (suggested-opener, read fresh at render time -- same pattern as
   status-bar's connected?/latest-tx) gets an accent-colored border, a
   lightweight 'try this next' pointer alongside the on-demand Uh?
   popup rather than instead of it -- the popup still has the full,
   ranked, human-readable text this can only gesture at with one
   highlighted button."
  []
  (let [suggested (suggested-opener)
        style-for #(when (= % suggested) suggested-style)]
    (ui/button-row
      {:children
       [(ui/button {:text "Editor..." :on-action {:event/type :open-editor} :style (style-for :open-editor)})
        (ui/button {:text "Browser..." :on-action {:event/type :open-browser} :style (style-for :open-browser)})
        (ui/button {:text "Play Builder..." :on-action {:event/type :open-play-builder} :style (style-for :open-play-builder)})
        (ui/button {:text "Wall..." :on-action {:event/type :open-wall} :style (style-for :open-wall)})
        (ui/button {:text "Conductor..." :on-action {:event/type :open-conductor} :style (style-for :open-conductor)})
        (ui/button {:text "Persistence..." :on-action {:event/type :open-persistence}})
        (ui/button {:text "Transform..." :on-action {:event/type :open-transform}})
        (ui/button {:text "Uh?" :on-action {:event/type :uh}})]})))

(defn- voices-panel
  "'Access to the actually playing voices and the committed voices that
   wait for activation' -- playing-ids (repo container ids currently
   sounding) and voice-details (voice PATHS currently live, each with
   its own algo/tx) are both mirrored from core.async-engine (see
   gui.lib.state/start-voice-poll!) but are genuinely DIFFERENT key
   spaces -- confirmed live, not assumed: a played container's own id
   is never itself a registered voice path (play/play-add always mint
   a fresh :TAA/:TAB/... path), so this shows them as two separate
   lists rather than trying to line them up id-by-id. waiting-ids is
   every other committed top-level id. Purely informational (no watch!/
   play here) -- use the Play Builder to act on one; its own Play
   Change path field wants exactly the path spelling shown below."
  [playing-ids voice-details]
  (ui/titled-panel
    {:title "Voices"
     :children
     (-> [(ui/label {:text (str "Playing (material): " (str/join ", " (map name (sort playing-ids))))})]
         (into (for [[path {:keys [algo tx]}] (sort-by (comp str first) voice-details)]
                 (ui/label {:text (str "    " (str/join "/" (map name path))
                                        " — algo: " (or algo "(none)") ", tx: " (or tx "?"))})))
         (conj (ui/label {:text (str "Waiting: " (str/join ", " (map name (state/waiting-ids))))})))}))

(defn- midi-input-panel
  "musics.core's open-midi/close-midi/list-midi-inputs, previously
   REPL-only -- deliberately separate from Record MIDI's own Start/
   Stop below (opening MIDI input and recording it to text are two
   different actions; this is what actually makes Start Recording work
   at all from the GUI alone now, see gui.lib.state's own docstring on
   :midi-input)."
  [{:keys [devices-text device-substring open? message]}]
  (ui/titled-panel
    {:title "MIDI Input"
     :children
     [(ui/button-row
        {:children
         [(ui/button {:text "List Devices" :on-action {:event/type :refresh-midi-devices}})
          (ui/text-field
            {:text device-substring
             :prompt "device name (blank = system chooser)"
             :on-text-changed {:event/type :set-midi-device-substring}})
          (ui/button {:text (if open? "Close" "Open") :on-action {:event/type :toggle-midi-input}})]})
      (ui/text-area
        {:text devices-text :pref-row-count 3 :editable? false
         :prompt "Click \"List Devices\" to see what's available."})
      (ui/label {:text (or message "")})]}))

(defn- record-panel
  "record-midi's own panel -- Start/Stop, an optional instrument name/
   number, the generated (and freely hand-editable) musics text, a file
   name, and Write. All wired straight through to gui.lib.state's own
   record-* fns -- see that ns's own docstring section on record-midi
   for what each actually does (recording itself runs in a background
   future, this panel just reflects :record's own state). Requires
   MIDI Input (above) to be Open first -- Start Recording surfaces
   input.midi-record's own \"No MIDI input open\" error directly in
   the text area otherwise, rather than silently doing nothing."
  [{:keys [recording? text name instrument collapsed? auto-commit?]}]
  (ui/titled-panel
    {:title "Record MIDI"
     :collapsed? collapsed?
     :on-toggle {:event/type :toggle-record-collapsed}
     :children
     [(ui/button-row
        {:children
         [(ui/button {:text (if recording? "Recording…" "Start Recording")
                      :disabled? recording?
                      :on-action {:event/type :start-record}})
          (ui/button {:text "Stop" :disabled? (not recording?)
                      :on-action {:event/type :stop-record}})
          (ui/text-field
            {:text instrument
             :prompt "instrument (GM name or number, optional)"
             :on-text-changed {:event/type :set-record-instrument}})]})
      (ui/text-area
        {:text text
         :prompt "Recorded musics text appears here once a recording ends -- edit freely before writing."
         :pref-row-count 8
         :on-text-changed {:event/type :set-record-text}})
      (ui/button-row
        {:children
         [(ui/text-field
            {:text name
             :prompt "file name (no extension)"
             :on-text-changed {:event/type :set-record-name}})
          (ui/button {:text "Browse..." :on-action {:event/type :record-browse-file}})
          (ui/toggle-button {:text "Auto Parse+Commit" :selected? (boolean auto-commit?)
                              :on-action {:event/type :toggle-record-auto-commit}})
          (ui/button {:text "Write" :on-action {:event/type :write-record}})]})]}))

(defn- status-bar
  "Pinned at the bottom of the state window (the last child in its
   v-box, same convention every other panel here already stacks by) --
   connection/transport/tx/id-count at a glance, without opening any
   other panel. connected?/latest-tx are read fresh at render time
   (see gui.lib.state's own docstrings on both) rather than tracked as
   their own :state keys; ids-count reuses the Browser's own already-
   live-synced :ids rather than polling core.repo a second time."
  [transport ids-count]
  (ui/label
    {:style "-fx-border-color: gray; -fx-border-width: 1 0 0 0; -fx-padding: 4 2 2 2; -fx-font-size: 11;"
     :text (str (if (state/connected?) "● Connected" "○ Not connected")
                "    Transport: " (name transport)
                "    Latest tx: " (state/latest-tx)
                "    Ids: " ids-count)}))

(defn- state-view
  [{:keys [transport new-id watched playing-ids voice-details theme record browser midi-input]}]
  (show-on-top
    {:fx/type :stage
     :showing true
     :title "Musics — state"
     :width 720
     :height 700
     :scene
     {:fx/type :scene
      :stylesheets [(theme/stylesheet theme)]
      :root
      ;; :border-pane rather than one flat :v-box -- :bottom genuinely
      ;; pins the status bar to the window's own bottom edge regardless
      ;; of resizing (a v-box's own last child only ends up at the
      ;; bottom incidentally, when its siblings happen to fill exactly
      ;; the rest of the window -- resize taller and it'd float up into
      ;; the middle instead). :center wraps everything else in a
      ;; scroll-pane for the same reason record-panel's own scrollable-
      ;; param-rows already needed one: this window's own content keeps
      ;; growing (Editor/Browser/Play Builder buttons, per-voice detail
      ;; rows, ...) past a fixed height more easily than it used to.
      {:fx/type :border-pane
       :center
       (ui/scroll-pane
         {:content
          {:fx/type :v-box
           :spacing 8
           :style "-fx-padding: 8;"
           :children
           [(transport-bar transport theme)
            (watch-row new-id)
            (panels-row)
            (voices-panel (or playing-ids #{}) (or voice-details {}))
            (ui/label {:text (str "Watching: " (str/join ", " (map name (keys (dissoc watched :ROOT)))))})
            (midi-input-panel midi-input)
            (record-panel record)]}})
       :bottom (status-bar transport (count (:ids browser)))}}}))

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
    ;; show-on-top's :on-created only fires once, at this Stage's own
    ;; first creation -- re-opening root after closing it toggles
    ;; :showing back to true (still an ordinary .show()) without a
    ;; second toFront/requestFocus. Good enough for "show right after
    ;; it's first created", which is what was actually asked for; a
    ;; toFront on every reopen would need tracking the false->true
    ;; :showing transition itself, not just creation.
    (show-on-top
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
          (ui/button {:text "Close" :on-action {:event/type :close-root}})]}}})))

;; ============================================================
;; Context windows -- one per watched non-:ROOT container id, created
;; and destroyed dynamically as watch!/unwatch! change :watched.
;; ============================================================

(defn- context-view
  [id]
  (fn [{:keys [watched playing-ids theme]}]
    (let [entry (get watched id default-entry)
          status (if (contains? playing-ids id) "▶ playing" "committed, waiting")]
      (show-on-top
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
            (ui/button {:text "Unwatch" :on-action {:event/type :unwatch :id id}})]}}}))))

;; ============================================================
;; Editor window -- write/parse/stage/commit musics text. Toggled from
;; the state window's "Editor..." button; closing just hides it, same
;; as the root window (see :editor-open?/gui.lib.state/close-editor!).
;; ============================================================

(defn- editor-view
  [{:keys [editor-open? editor theme]}]
  (let [{:keys [text sid message load-path]} editor
        title (if (seq (str/trim (or load-path "")))
                (str "Musics — Editor — " (.getName (io/file load-path)))
                "Musics — Editor")]
    (show-on-top
      {:fx/type :stage
       :showing (boolean editor-open?)
       :title title
       :width 720
       :height 600
       :on-close-request {:event/type :close-editor}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(assoc (ui/text-area
                   {:text text
                    :prompt "Write or paste musics text here..."
                    :pref-row-count 18
                    :on-text-changed {:event/type :set-editor-text}})
                 :v-box/vgrow :always)
          (ui/button-row
            {:children
             [(ui/button {:text "Parse" :on-action {:event/type :editor-parse}})
              (ui/button {:text "Parse+Commit" :on-action {:event/type :editor-parse-commit}})
              (ui/button {:text "Commit" :disabled? (nil? sid) :on-action {:event/type :editor-commit}})
              (ui/button {:text "Abort" :disabled? (nil? sid) :on-action {:event/type :editor-abort}})]})
          (ui/label {:text (or message "")})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text load-path
                 :prompt "path/to/file.mus"
                 :on-text-changed {:event/type :set-editor-load-path}
                 :on-action {:event/type :editor-load-file}})
              (ui/button {:text "Browse..." :on-action {:event/type :editor-browse-file}})
              (ui/button {:text "Load File" :on-action {:event/type :editor-load-file}})
              (ui/button {:text "Save" :on-action {:event/type :editor-save}})
              (ui/button {:text "Clear" :on-action {:event/type :editor-clear}})]})
          (ui/button {:text "Close" :on-action {:event/type :close-editor}})]}}})))

;; ============================================================
;; Browser window -- musics.core's own repo-inspection surface
;; (ids/children/leaves/print-structure/ctx/history/as-of). :ids is
;; live-synced against core.repo itself (see gui.lib.state/
;; start-browser-sync!), so a concurrent REPL parse!/commit! shows up
;; here with no manual refresh.
;; ============================================================

(defn- browser-view
  [{:keys [browser-open? browser theme]}]
  (let [{:keys [ids query selected-id tx tx-text detail]} browser
        {:keys [structure ctx history error]} detail]
    (show-on-top
      {:fx/type :stage
       :showing (boolean browser-open?)
       :title "Musics — Browser"
       :width 760
       :height 660
       :on-close-request {:event/type :close-browser}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(ui/label {:text (str "All ids: " (str/join ", " (map name ids)))})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text query
                 :prompt "id, e.g. verse"
                 :on-text-changed {:event/type :set-browser-query}
                 :on-action {:event/type :browser-inspect}})
              (ui/button {:text "Inspect" :on-action {:event/type :browser-inspect}})]})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text tx-text
                 :prompt "tx (blank = latest)"
                 :on-text-changed {:event/type :set-browser-tx}
                 :on-action {:event/type :browser-goto-tx}})
              (ui/button {:text "Go to tx" :on-action {:event/type :browser-goto-tx}})
              (ui/button {:text "Latest" :on-action {:event/type :browser-goto-latest}})]})
          (ui/label {:text (cond
                              error error
                              selected-id (str (name selected-id) " @ tx " (or tx "latest")
                                                " — history tx's: " (str/join ", " history))
                              :else "Type an id and click Inspect.")})
          (assoc (ui/text-area
                   {:text (or structure "")
                    :prompt "Structure appears here after Inspect."
                    :pref-row-count 12
                    :editable? false})
                 :v-box/vgrow :always)
          (ui/text-area
            {:text (or ctx "")
             :prompt "Context chain appears here after Inspect."
             :pref-row-count 6
             :editable? false})
          (ui/button-row
            {:children
             [(ui/button {:text "Watch" :disabled? (nil? selected-id)
                          :on-action {:event/type :browser-watch}})
              (ui/button {:text "Add to Play Builder" :disabled? (nil? selected-id)
                          :on-action {:event/type :browser-add-to-play-builder}})]})
          (ui/button {:text "Close" :on-action {:event/type :close-browser}})]}}})))

;; ============================================================
;; Play Builder window -- assembles a real play/play-add/play-change
;; Form from an ordered list of ids instead of the state window's own
;; fixed, always-sequential "play everything watched" button.
;; ============================================================

(defn- play-builder-view
  [{:keys [play-builder-open? play-builder theme]}]
  (let [{:keys [ids mode algo query change-path tx-text message]} play-builder]
    (show-on-top
      {:fx/type :stage
       :showing (boolean play-builder-open?)
       :title "Musics — Play Builder"
       :width 640
       :height 520
       :on-close-request {:event/type :close-play-builder}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(ui/label {:text (str "Form: " (str/join ", " (map name ids))
                                 " (" (if (= mode :par) "parallel — (par ...)" "sequential — []") ")")})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text query
                 :prompt "id to add, e.g. melody"
                 :on-text-changed {:event/type :set-play-builder-query}
                 :on-action {:event/type :play-builder-add}})
              (ui/button {:text "Add" :on-action {:event/type :play-builder-add}})
              (ui/button {:text "Remove" :on-action {:event/type :play-builder-remove}})
              (ui/button {:text "Clear" :on-action {:event/type :play-builder-clear}})
              (ui/toggle-button {:text (if (= mode :par) "Parallel" "Sequential")
                                  :selected? (= mode :par)
                                  :on-action {:event/type :play-builder-toggle-mode}})]})
          (ui/text-field
            {:text algo
             :prompt "algo name (optional, e.g. bright — see the Wall algorithm docs)"
             :on-text-changed {:event/type :set-play-builder-algo}})
          (ui/button-row
            {:children
             [(ui/button {:text "Play (replace)" :on-action {:event/type :play-builder-play}})
              (ui/button {:text "Play Add (join)" :on-action {:event/type :play-builder-play-add}})]})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text change-path
                 :prompt "target path, e.g. TAA"
                 :on-text-changed {:event/type :set-play-builder-change-path}})
              (ui/button {:text "Play Change (supersede)" :on-action {:event/type :play-builder-play-change}})]})
          (ui/button-row
            {:children
             [(ui/text-field
                {:text tx-text
                 :prompt "tx for NEXT play (blank = latest)"
                 :on-text-changed {:event/type :set-play-builder-tx-text}})
              (ui/button {:text "Set Play Tx" :on-action {:event/type :play-builder-set-tx}})
              (ui/button {:text "Use Latest" :on-action {:event/type :play-builder-use-latest-tx}})]})
          (ui/label {:text (or message "")})
          (ui/button {:text "Close" :on-action {:event/type :close-play-builder}})]}}})))

;; ============================================================
;; Wall algorithms window -- register-factory!/build!/assign-algo!
;; (core.wall), previously REPL-only. Factories/algos/distributions/
;; criteria are all read-only, pre-formatted text (see gui.lib.state's
;; own fmt-doc-map/fmt-registered) -- this view does no formatting of
;; its own, same "Model does the formatting, View just displays it"
;; split the Browser window already uses for its structure/ctx panes.
;; ============================================================

(defn- wall-view
  [{:keys [wall-open? wall theme]}]
  (let [{:keys [factories-text algos-text distributions-text criteria-text
                build-name build-factory build-params
                assign-path assign-algo assignments message]} wall]
    (show-on-top
      {:fx/type :stage
       :showing (boolean wall-open?)
       :title "Musics — Wall Algorithms"
       :width 760
       :height 700
       :on-close-request {:event/type :close-wall}
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
                     :children
                     [(ui/label {:text "Factories" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text factories-text :pref-row-count 4 :editable? false})
                      (ui/label {:text "Built algos (name — doc  [factory, params])"
                                 :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text algos-text :pref-row-count 6 :editable? false})
                      (ui/label {:text "Distributions" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text distributions-text :pref-row-count 3 :editable? false})
                      (ui/label {:text "Criteria" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text criteria-text :pref-row-count 3 :editable? false})
                      (ui/titled-panel
                        {:title "Build (or hot-swap, by reusing a name)"
                         :children
                         [(ui/button-row
                            {:children
                             [(ui/text-field {:text build-name :prompt "name, e.g. bright"
                                              :on-text-changed {:event/type :set-wall-build-name}})
                              (ui/text-field {:text build-factory :prompt "factory name, e.g. colorTalea"
                                              :on-text-changed {:event/type :set-wall-build-factory}})]})
                          (ui/text-area
                            {:text build-params
                             :prompt "params, a plain EDN map, e.g. {:n 5}"
                             :pref-row-count 3
                             :on-text-changed {:event/type :set-wall-build-params}})
                          (ui/button {:text "Build" :on-action {:event/type :wall-build}})]})
                      (ui/titled-panel
                        {:title "Assign (prepares the NEXT mint at path, not anything already live)"
                         :children
                         [(ui/button-row
                            {:children
                             [(ui/text-field {:text assign-path :prompt "target path, e.g. TAA"
                                              :on-text-changed {:event/type :set-wall-assign-path}})
                              (ui/text-field {:text assign-algo :prompt "algo name (blank clears)"
                                              :on-text-changed {:event/type :set-wall-assign-algo}})
                              (ui/button {:text "Assign" :on-action {:event/type :wall-assign}})]})
                          (ui/label {:text (str "Prepared: "
                                                 (str/join ", " (for [[p n] assignments]
                                                                   (str (str/join "/" (map name p))
                                                                        " -> " (or n "nil")))))})]})]}})
                 :v-box/vgrow :always)
          (ui/label {:text (or message "")})
          (ui/button {:text "Close" :on-action {:event/type :close-wall}})]}}})))

;; ============================================================
;; Conductor / scheduling window -- register-action!/trigger!/
;; schedule!/schedule-tx! (core.conductor), previously REPL-only.
;; Registering a brand NEW action needs a real Clojure function, which
;; has no generic GUI representation -- this window only lists/
;; triggers already-REPL-registered actions and arms/disarms schedule
;; entries against them (see gui.lib.state's own docstring on this).
;; ============================================================

(defn- conductor-view
  [{:keys [conductor-open? conductor theme]}]
  (let [{:keys [actions-text scheduled-text scheduled-repeating-text
                trigger-id trigger-args
                schedule-id schedule-phase schedule-action-id
                tx-id tx-phase tx-target message]} conductor]
    (show-on-top
      {:fx/type :stage
       :showing (boolean conductor-open?)
       :title "Musics — Conductor"
       :width 720
       :height 680
       :on-close-request {:event/type :close-conductor}
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
                     :children
                     [(ui/label {:text "Registered actions" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text actions-text :pref-row-count 3 :editable? false})
                      (ui/label {:text "Scheduled (one-shot)" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text scheduled-text :pref-row-count 3 :editable? false})
                      (ui/label {:text "Scheduled (repeating — schedule-tx!)" :style "-fx-font-weight: bold;"})
                      (ui/text-area {:text scheduled-repeating-text :pref-row-count 3 :editable? false})
                      (ui/titled-panel
                        {:title "Trigger"
                         :children
                         [(ui/button-row
                            {:children
                             [(ui/text-field {:text trigger-id :prompt "action id"
                                              :on-text-changed {:event/type :set-conductor-trigger-id}})
                              (ui/text-field {:text trigger-args :prompt "args EDN vector (optional, e.g. [1 2])"
                                              :on-text-changed {:event/type :set-conductor-trigger-args}})
                              (ui/button {:text "Trigger" :on-action {:event/type :conductor-trigger}})]})]})
                      (ui/titled-panel
                        {:title "Schedule (one-shot)"
                         :children
                         [(ui/button-row
                            {:children
                             [(ui/text-field {:text schedule-id :prompt "section id"
                                              :on-text-changed {:event/type :set-conductor-schedule-id}})
                              (ui/toggle-button {:text (if (= schedule-phase "exit") "exit" "enter")
                                                  :selected? (= schedule-phase "exit")
                                                  :on-action {:event/type :toggle-conductor-schedule-phase}})
                              (ui/text-field {:text schedule-action-id :prompt "action id"
                                              :on-text-changed {:event/type :set-conductor-schedule-action-id}})
                              (ui/button {:text "Schedule" :on-action {:event/type :conductor-schedule}})
                              (ui/button {:text "Unschedule" :on-action {:event/type :conductor-unschedule}})]})]})
                      (ui/titled-panel
                        {:title "Schedule tx (repeating cutover)"
                         :children
                         [(ui/button-row
                            {:children
                             [(ui/text-field {:text tx-id :prompt "section id"
                                              :on-text-changed {:event/type :set-conductor-tx-id}})
                              (ui/toggle-button {:text (if (= tx-phase "exit") "exit" "enter")
                                                  :selected? (= tx-phase "exit")
                                                  :on-action {:event/type :toggle-conductor-tx-phase}})
                              (ui/text-field {:text tx-target :prompt "target tx (blank = latest)"
                                              :on-text-changed {:event/type :set-conductor-tx-target}})
                              (ui/button {:text "Arm" :on-action {:event/type :conductor-schedule-tx}})
                              (ui/button {:text "Disarm" :on-action {:event/type :conductor-unschedule-repeating}})]})]})]}})
                 :v-box/vgrow :always)
          (ui/label {:text (or message "")})
          (ui/button {:text "Close" :on-action {:event/type :close-conductor}})]}}})))

;; ============================================================
;; Adviser popup -- musics.core/uh?, previously REPL-only. Small and
;; deliberately transient-feeling (no live-sync of its own) -- the
;; panels-row's own Uh? button both opens this window and refreshes
;; its text in one click (see gui.lib.state/uh!), rather than treating
;; open/refresh as two separate actions.
;; ============================================================

(defn- adviser-view
  [{:keys [adviser-open? adviser theme]}]
  (let [{:keys [text]} adviser]
    (show-on-top
      {:fx/type :stage
       :showing (boolean adviser-open?)
       :title "Musics — Adviser"
       :width 480
       :height 320
       :on-close-request {:event/type :close-adviser}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(assoc (ui/text-area
                   {:text (or text "")
                    :prompt "Click \"Uh?\" again to refresh."
                    :pref-row-count 8
                    :editable? false})
                 :v-box/vgrow :always)
          (ui/button-row
            {:children
             [(ui/button {:text "Uh?" :on-action {:event/type :uh}})
              (ui/button {:text "Close" :on-action {:event/type :close-adviser}})]})]}}})))

;; ============================================================
;; Persistence window -- write/load/persist-session/restore-session,
;; previously REPL-only. write/persist-session share the same
;; :write-path field (Persist Session is "write, plus live algo
;; assignments too" -- same destination, different amount of state
;; captured); load/restore-session share :load-path the same way.
;; ============================================================

(defn- persistence-view
  [{:keys [persistence-open? persistence theme]}]
  (let [{:keys [write-path load-path busy? message]} persistence]
    (show-on-top
      {:fx/type :stage
       :showing (boolean persistence-open?)
       :title "Musics — Persistence"
       :width 640
       :height 420
       :on-close-request {:event/type :close-persistence}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(ui/titled-panel
            {:title "Save (write / persist-session)"
             :children
             [(ui/button-row
                {:children
                 [(ui/text-field {:text write-path :prompt "path/to/session.edn"
                                  :on-text-changed {:event/type :set-persistence-write-path}})
                  (ui/button {:text "Browse..." :disabled? busy?
                              :on-action {:event/type :browse-persistence-write-path}})]})
              (ui/button-row
                {:children
                 [(ui/button {:text "Write" :disabled? busy?
                              :on-action {:event/type :persistence-write}})
                  (ui/button {:text "Persist Session (+ live algos)" :disabled? busy?
                              :on-action {:event/type :persistence-persist-session}})]})]})
          (ui/titled-panel
            {:title "Load (load / restore-session)"
             :children
             [(ui/button-row
                {:children
                 [(ui/text-field {:text load-path :prompt "path/to/session.edn"
                                  :on-text-changed {:event/type :set-persistence-load-path}})
                  (ui/button {:text "Browse..." :disabled? busy?
                              :on-action {:event/type :browse-persistence-load-path}})]})
              (ui/button-row
                {:children
                 [(ui/button {:text "Load" :disabled? busy?
                              :on-action {:event/type :persistence-load}})
                  (ui/button {:text "Restore Session (+ live algos)" :disabled? busy?
                              :on-action {:event/type :persistence-restore-session}})]})
              (ui/label {:text "Load/Restore REPLACE all committed history — this isn't a merge."
                         :style "-fx-font-style: italic;"})]})
          (ui/label {:text "Persist Session / Restore Session don't capture a wall algo's own factory recipe built outside build!, or any conductor schedule table."
                     :style "-fx-font-style: italic;"})
          (ui/label {:text (or (when busy? "Working…") message "")})
          (ui/button {:text "Close" :on-action {:event/type :close-persistence}})]}}})))

;; ============================================================
;; Transform workbench window -- musics.core's generative transforms
;; (times/transpose/invert/scale/reverse/shuffle/tonal-*), previously
;; REPL-only. Preview (core.compose/display, no MIDI) is a separate
;; step from Commit as New Part, same reasoning gui.lib.state's own
;; docstring gives -- Preview's own already-computed material is what
;; Commit actually commits, never recomputed.
;; ============================================================

(defn- transform-view
  [{:keys [transform-open? transform theme]}]
  (let [{:keys [source-id transform-name params preview-text new-id message]} transform]
    (show-on-top
      {:fx/type :stage
       :showing (boolean transform-open?)
       :title "Musics — Transform"
       :width 680
       :height 620
       :on-close-request {:event/type :close-transform}
       :scene
       {:fx/type :scene
        :stylesheets [(theme/stylesheet theme)]
        :root
        {:fx/type :v-box
         :spacing 8
         :style "-fx-padding: 8;"
         :children
         [(ui/text-field
            {:text source-id :prompt "source id, e.g. verse"
             :on-text-changed {:event/type :set-transform-source-id}})
          (ui/text-field
            {:text transform-name
             :prompt "times / transpose / invert / scale / reverse / shuffle / tonal-transpose / tonal-invert / snap-to-scale / tonal-harmonize"
             :on-text-changed {:event/type :set-transform-name}})
          (ui/text-area
            {:text params
             :prompt "params EDN map, e.g. {:n 2}  {:semitones 5}  {:ks \"D.major\" :steps 2}"
             :pref-row-count 2
             :on-text-changed {:event/type :set-transform-params}})
          (ui/button {:text "Preview" :on-action {:event/type :transform-preview}})
          (assoc (ui/text-area
                   {:text preview-text :pref-row-count 12 :editable? false
                    :prompt "Click Preview to see the transformed material (via core.compose/display, no MIDI)."})
                 :v-box/vgrow :always)
          (ui/button-row
            {:children
             [(ui/text-field {:text new-id :prompt "new id to commit as"
                              :on-text-changed {:event/type :set-transform-new-id}})
              (ui/button {:text "Commit as New Part" :on-action {:event/type :transform-commit}})]})
          (ui/label {:text (or message "")})
          (ui/button {:text "Close" :on-action {:event/type :close-transform}})]}}})))

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
    :reset          (state/reset!)
    :start-record          (state/start-record!)
    :stop-record            (state/stop-record!)
    :set-record-instrument (state/set-record-instrument! (:fx/event event))
    :set-record-text       (state/set-record-text! (:fx/event event))
    :set-record-name       (state/set-record-name! (:fx/event event))
    :write-record           (state/write-record!)
    :toggle-record-auto-commit (state/toggle-record-auto-commit!)
    :refresh-midi-devices      (state/refresh-midi-devices!)
    :set-midi-device-substring (state/set-midi-device-substring! (:fx/event event))
    :toggle-midi-input         (state/toggle-midi-input!)
    :toggle-record-collapsed (state/toggle-record-collapsed!)
    :open-editor    (state/open-editor!)
    :close-editor   (state/close-editor!)
    :set-editor-text      (state/set-editor-text! (:fx/event event))
    :set-editor-load-path (state/set-editor-load-path! (:fx/event event))
    :editor-parse         (state/editor-parse!)
    :editor-parse-commit  (state/editor-parse-and-commit!)
    :editor-commit        (state/editor-commit!)
    :editor-abort         (state/editor-abort!)
    :editor-load-file     (state/editor-load-file!)
    :editor-save          (editor-save! event)
    :editor-clear         (state/editor-clear!)
    :editor-browse-file   (browse-editor-load-file! event)
    :record-browse-file   (browse-record-save-file! event)
    :open-browser   (state/open-browser!)
    :close-browser  (state/close-browser!)
    :set-browser-query (state/set-browser-query! (:fx/event event))
    :browser-inspect    (state/browser-inspect!)
    :set-browser-tx     (state/set-browser-tx! (:fx/event event))
    :browser-goto-tx    (state/browser-goto-tx!)
    :browser-goto-latest (state/browser-goto-latest!)
    :browser-watch                 (state/browser-watch!)
    :browser-add-to-play-builder   (state/browser-add-to-play-builder!)
    :open-play-builder  (state/open-play-builder!)
    :close-play-builder (state/close-play-builder!)
    :set-play-builder-query (state/set-play-builder-query! (:fx/event event))
    :play-builder-add          (state/play-builder-add-from-text!)
    :play-builder-remove       (state/play-builder-remove-from-text!)
    :play-builder-clear        (state/play-builder-clear!)
    :play-builder-toggle-mode  (state/toggle-play-builder-mode!)
    :set-play-builder-algo        (state/set-play-builder-algo! (:fx/event event))
    :play-builder-play            (state/play-builder-play!)
    :play-builder-play-add        (state/play-builder-play-add!)
    :set-play-builder-change-path (state/set-play-builder-change-path! (:fx/event event))
    :play-builder-play-change     (state/play-builder-play-change!)
    :set-play-builder-tx-text     (state/set-play-builder-tx-text! (:fx/event event))
    :play-builder-set-tx          (state/play-builder-set-tx!)
    :play-builder-use-latest-tx   (state/play-builder-use-latest-tx!)
    :open-wall   (state/open-wall!)
    :close-wall  (state/close-wall!)
    :set-wall-build-name    (state/set-wall-build-name! (:fx/event event))
    :set-wall-build-factory (state/set-wall-build-factory! (:fx/event event))
    :set-wall-build-params  (state/set-wall-build-params! (:fx/event event))
    :wall-build              (state/wall-build!)
    :set-wall-assign-path  (state/set-wall-assign-path! (:fx/event event))
    :set-wall-assign-algo  (state/set-wall-assign-algo! (:fx/event event))
    :wall-assign              (state/wall-assign!)
    :open-conductor   (state/open-conductor!)
    :close-conductor  (state/close-conductor!)
    :set-conductor-trigger-id   (state/set-conductor-trigger-id! (:fx/event event))
    :set-conductor-trigger-args (state/set-conductor-trigger-args! (:fx/event event))
    :conductor-trigger            (state/conductor-trigger!)
    :set-conductor-schedule-id        (state/set-conductor-schedule-id! (:fx/event event))
    :toggle-conductor-schedule-phase  (state/toggle-conductor-schedule-phase!)
    :set-conductor-schedule-action-id (state/set-conductor-schedule-action-id! (:fx/event event))
    :conductor-schedule            (state/conductor-schedule!)
    :conductor-unschedule          (state/conductor-unschedule!)
    :set-conductor-tx-id     (state/set-conductor-tx-id! (:fx/event event))
    :toggle-conductor-tx-phase (state/toggle-conductor-tx-phase!)
    :set-conductor-tx-target (state/set-conductor-tx-target! (:fx/event event))
    :conductor-schedule-tx          (state/conductor-schedule-tx!)
    :conductor-unschedule-repeating (state/conductor-unschedule-repeating!)
    :uh              (state/uh!)
    :close-adviser   (state/close-adviser!)
    :open-persistence  (state/open-persistence!)
    :close-persistence (state/close-persistence!)
    :set-persistence-write-path (state/set-persistence-write-path! (:fx/event event))
    :set-persistence-load-path  (state/set-persistence-load-path! (:fx/event event))
    :browse-persistence-write-path (browse-persistence-write-path! event)
    :browse-persistence-load-path  (browse-persistence-load-path! event)
    :persistence-write            (state/persistence-write!)
    :persistence-load              (state/persistence-load!)
    :persistence-persist-session   (state/persistence-persist-session!)
    :persistence-restore-session   (state/persistence-restore-session!)
    :open-transform   (state/open-transform!)
    :close-transform  (state/close-transform!)
    :set-transform-source-id (state/set-transform-source-id! (:fx/event event))
    :set-transform-name      (state/set-transform-name! (:fx/event event))
    :set-transform-params    (state/set-transform-params! (:fx/event event))
    :set-transform-new-id    (state/set-transform-new-id! (:fx/event event))
    :transform-preview          (state/transform-preview!)
    :transform-commit           (state/transform-commit!)))

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
(def ^:private editor-renderer (mk-renderer editor-view))
(def ^:private browser-renderer (mk-renderer browser-view))
(def ^:private play-builder-renderer (mk-renderer play-builder-view))
(def ^:private wall-renderer (mk-renderer wall-view))
(def ^:private conductor-renderer (mk-renderer conductor-view))
(def ^:private adviser-renderer (mk-renderer adviser-view))
(def ^:private persistence-renderer (mk-renderer persistence-view))
(def ^:private transform-renderer (mk-renderer transform-view))

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
   (fx/mount-renderer state/*state editor-renderer)
   (fx/mount-renderer state/*state browser-renderer)
   (fx/mount-renderer state/*state play-builder-renderer)
   (fx/mount-renderer state/*state wall-renderer)
   (fx/mount-renderer state/*state conductor-renderer)
   (fx/mount-renderer state/*state adviser-renderer)
   (fx/mount-renderer state/*state persistence-renderer)
   (fx/mount-renderer state/*state transform-renderer)
   (add-watch state/*state ::context-windows sync-context-windows!)
   (sync-context-windows! ::context-windows state/*state {:watched {}} @state/*state)
   (state/start-voice-poll!)
   (state/start-browser-sync!)
   (state/start-wall-sync!)
   (state/start-conductor-sync!)
   nil))

(defn -main
  [& args]
  (launch! (if-let [t (first args)] (keyword t) :dark)))
