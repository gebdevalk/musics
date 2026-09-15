(ns gui.lib.state
  "The Model layer for the new GUI: a plain cljfx render-state atom
  (*state) whose :watched containers mirror real core.repo containers'
  own Context envelopes, plus write-through functions that edit the
  REAL, live Context object directly (core.domain.context/ctx-append),
  not just *state -- *state only exists because cljfx re-renders from
  a plain data snapshot, it is never the source of truth.

  This is deliberately last-write-wins, per the existing GUI vision
  (see the 'GUI real-time context vision' project note): a Context's
  envelope is exactly the same atom the live engine samples from at
  fire-time (core.domain.resolve/resolve-event, via ctx-value-chain),
  so a slider edit here takes effect on already-playing material the
  next time the engine reads that key, with no separate sync step --
  PROVIDED that container is armed 'hot' (see toggle-hot!/hot?, ported
  from the original JavaFX GUI's green/red record-arm button): while
  cold, set-param!/set-combo! still update *state's preview (so the
  slider/dropdown itself is responsive) but never touch the real
  Context, letting you rehearse values before committing them live.
  zoom! is the other ported feature, a per-slider display-range-only
  concern with no Context interaction at all -- see its own docstring.

  Three more features ported from the same original JavaFX GUI, U/S/L,
  but reinterpreted: that GUI's model (in turn inherited from a Forth-
  hosted predecessor with no :PAR-shaped tree, just a fixed set of
  named voices) had U/S/L gang/collapse/label N per-VOICE sliders of
  ONE param. This app's :PAR makes the tree shape arbitrary, so there
  is no fixed voice axis to index sliders by -- the closest real
  analogue is the set of currently-WATCHED CONTEXT PATHS (container
  ids), so U/S/L here operate across THAT set instead:
    unified?     (U) -- see toggle-unified!/set-param!'s fanout
    collapsed?   (S) -- see toggle-collapsed! (gui.lib.core reads it)
    show-labels? (L) -- see toggle-labels! (gui.lib.core reads it)
  Each is a per-watched-id flag rather than global, so different
  windows can be ganged/collapsed/labeled independently.

  playing-ids/waiting-ids/start-voice-poll! are the other half of this
  turn's work: 'access to the actually playing voices and the
  committed voices that wait for activation'. core.async-engine now
  keeps a live id -> voice-count registry (see that ns's own
  docstring); start-voice-poll! mirrors its playing-ids into *state's
  own :playing-ids every ~200ms rather than add-watch-ing the engine's
  :active-voices atom directly, since THAT atom's identity is rebuilt
  fresh on every (connect!) (a new engine map each time) -- a one-time
  add-watch wouldn't survive a reconnect the way a poll naturally does.
  waiting-ids is just root-children (musics.clj) minus :playing-ids.

  :ROOT IS a valid, live-editable watch target, via a different write
  path than every other container (see set-param!): :ROOT's own
  values are grammar-guaranteed write-once ONLY from parsed musics
  text (TopElement excludes every construct that could reach it a
  second time -- see CLAUDE.md's 'ROOT read-only' section) -- that
  restriction is enforced by the grammar/walker, not by the Context
  object itself, which is exactly as mutable as any other. This GUI is
  the 'external actor' the 'GUI real-time context vision' project note
  already anticipated needing raw atom access for. context-root
  (core.domain.context) stores each of ROOT's values BARE, never as a
  real Envelope (see that ns's own ValueSource comment -- there's
  never a second point to accommodate from parsed text), so editing it
  live is a direct swap! of the bare value, not a timed ctx-append."
  (:refer-clojure :exclude [reset!])
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [core.repo :as repo]
    [core.registries :as reg]
    [core.domain.context :as c]
    [core.async-engine :as engine]
    [common.defaults :as defaults]
    [common.music-elements :as el]
    [gui.lib.data :as data]
    [input.midi-record :as rec]
    [musics.core :as m]))

(defn- humanize-label
  "durScale -> \"Dur Scale\", volume -> \"Volume\", Tempo -> \"Tempo\" --
   a readable slider label derived from a context key's own registered
   name, not a second hand-typed label per key."
  [kw]
  (let [n (name kw)
        spaced (str/replace n #"(?<=[a-z])(?=[A-Z])" " ")]
    (str (str/upper-case (subs spaced 0 1)) (subs spaced 1))))

(def param-specs
  "Context keys this GUI shows as a slider -- canonical key -> slider
   bounds/format, computed directly from common.defaults/context-keys
   (itself built from the SAME registration calls that give !key:
   instructions their bounds/defaults), not a hand-typed, independently
   maintained copy of those numbers -- exactly the kind of drift that
   let an earlier version of this list hardcode :volume's own bounds
   wrong (0-128 against the registry's real 0-100) in the first place.
   Every registered key with a real numeric :range is included
   automatically -- add a key to common.defaults' own ranges/reg! calls
   and it shows up here with no GUI-side change at all -- EXCEPT
   :instrument, deliberately excluded: it already gets a name-based
   dropdown (see combo-specs) rather than a raw 0-127 slider.
   :label is derived from the key's own name (see humanize-label);
   :fmt is %.0f when the registered range is all-integer bounds, %.2f
   otherwise; :zoom-floor (a GUI-only interaction concern with no
   registry equivalent -- see zoom!) defaults to an eighth of the
   registered span, the original JavaFX GUI's own ~8-block convention.
   Ordered :world keys first (Tempo/Delay/Reverb/Width -- reg!'s own
   :category, exactly the 'toplevel', uppercase-named group CLAUDE.md's
   Grammar section describes), then :leaf keys, alphabetically by name
   within each group -- a real sorted-map-by on each key's own
   registered :category, not an accident of ASCII (capital letters
   happening to sort before lowercase ones) that a differently-cased
   future key could quietly break."
  (let [registry     (defaults/context-keys)
        category-of  #(:category (get registry %))
        rank         (fn [k] [(if (= :world (category-of k)) 0 1) (name k)])]
    (into (sorted-map-by #(compare (rank %1) (rank %2)))
          (for [[key {:keys [range]}] registry
                :when (and range (not= key :instrument))]
            (let [[lo hi] range
                  lo (double lo)
                  hi (double hi)
                  integral? (and (integer? (first range)) (integer? (second range)))]
              [key {:label (humanize-label key)
                    :min lo
                    :max hi
                    :fmt (if integral? "%.0f" "%.2f")
                    :zoom-floor (/ (- hi lo) 8.0)}])))))

(def combo-specs
  "Categorical context keys this GUI shows as a name dropdown instead
   of a slider -- each backed by a REAL gui.lib.data lookup (which is
   itself backed by common.music-data, see that ns's own docstring),
   never a GUI-side copy of instrument/dynamic names. Adding another
   picker is one more entry here, no other code changes needed.
   :volume's own named-dynamic picker (pp/mf/ff/...) used to live here
   too, dropped: it only ever reached the ~11 discrete values in
   common.music-data/dynamics, while :volume's own param-specs slider
   already reaches every value in its real 0-100 range -- a strictly
   more restrictive alternative sitting right next to the slider that
   already subsumes it, not a second, independently useful control."
  {:instrument {:label "Instrument" :lookup data/instruments}})

(def *state
  (atom {:transport :stopped
         :new-id ""
         ;; :dark or :light -- see gui.lib.theme/stylesheet, applied to
         ;; every window's Scene. Set via (musics/gui) -> launch!, or
         ;; live via set-theme!/a state-window toggle. Defaults to
         ;; :dark per the user's own request.
         :theme :dark
         ;; Whether the dedicated :ROOT window (see gui.lib.core) is
         ;; currently showing -- :ROOT stays in :watched once opened
         ;; (its values are cheap to keep around), this only toggles
         ;; that window's own visibility.
         :root-open? false
         ;; Mirrored from core.async-engine/playing-ids by
         ;; start-voice-poll! -- see ns docstring.
         :playing-ids #{}
         ;; id -> {:algo :tx}, one entry per currently-playing id,
         ;; mirrored alongside :playing-ids by the same poll (see
         ;; voice-detail/start-voice-poll! below) -- the Voices panel's
         ;; own per-voice detail.
         :voice-details {}
         ;; Whether the Editor/Browser/Play Builder windows (see their
         ;; own sections below) are currently showing -- same toggle
         ;; pattern as :root-open?.
         :editor-open? false
         :browser-open? false
         :play-builder-open? false
         :wall-open? false
         ;; Editor panel -- write/parse/stage/commit musics text. :sid
         ;; is the pending staged sid (nil once committed/aborted/never
         ;; parsed), :ids that sid's own top-level ids, :message the
         ;; panel's own status/error line. See the Editor section below.
         :editor {:text "" :load-path "" :sid nil :ids nil :message nil}
         ;; Repo browser panel -- :ids is kept live-synced with
         ;; core.repo's own registry (see start-browser-sync!, unlike
         ;; the engine's :voices/:playing-ids which must be polled --
         ;; see ns docstring). :selected-id/:tx address what :detail
         ;; (structure/ctx/history text, or :error) currently shows.
         :browser {:ids [] :query "" :selected-id nil :tx nil :tx-text ""
                   :detail {}}
         ;; Play Builder panel -- assembles a real play/play-add/
         ;; play-change Form from an ordered list of ids. :mode is
         ;; :seq ([] Form) or :par ((par ...) Form, via musics.core/par
         ;; so a repeated id is legal here too, not just at the REPL).
         ;; :algo, :change-path, :tx-text are all free text, resolved
         ;; only when an action button actually fires.
         :play-builder {:ids [] :mode :seq :algo "" :query ""
                         :change-path "" :tx-text "" :message nil}
         ;; Wall algorithms panel -- factories/algos/distributions/
         ;; criteria are all kept live-synced via add-watch on
         ;; core.registries' own atoms (see start-wall-sync!, same
         ;; reasoning as the Browser panel's :ids above -- these are
         ;; stable defonce atoms, not rebuilt on reconnect the way
         ;; the engine's own :voices is). :assignments (the PREPARED
         ;; algo-assignments table) lives on the engine instead, so
         ;; it's polled alongside :playing-ids/:voice-details instead
         ;; (see start-voice-poll!). The four -text keys are already
         ;; pre-formatted display strings (see refresh-wall!) rather
         ;; than raw maps, so the view layer has no formatting of its
         ;; own to do -- same reasoning gui.lib.state already applies
         ;; to the Browser panel's :structure/:ctx text.
         :wall {:factories-text "" :algos-text "" :distributions-text ""
                :criteria-text "" :build-name "" :build-factory ""
                :build-params "" :assign-path "" :assign-algo ""
                :assignments {} :message nil}
         ;; Conductor / scheduling panel -- core.conductor's action-
         ;; registry/schedule/repeating tables, all three live-synced
         ;; via add-watch (see start-conductor-sync!, same stable-
         ;; defonce-atom reasoning as the Wall panel's own four).
         ;; Registering a brand NEW action isn't exposed here at all --
         ;; register-action! needs a real Clojure function, which has
         ;; no generic GUI representation the way build!'s EDN params
         ;; do; this panel only lists/triggers already-REPL-registered
         ;; actions and arms/disarms schedule entries against them.
         :conductor-open? false
         :conductor {:actions-text "" :scheduled-text "" :scheduled-repeating-text ""
                     :trigger-id "" :trigger-args ""
                     :schedule-id "" :schedule-phase "enter" :schedule-action-id ""
                     :tx-id "" :tx-phase "enter" :tx-target ""
                     :message nil}
         ;; Adviser popup -- musics.core/uh?, a lightweight "what should
         ;; I do next" hint. Opened/refreshed by the same Uh? button
         ;; each click (see uh! below) rather than tied to any other
         ;; toggle mechanism.
         :adviser-open? false
         :adviser {:text ""}
         ;; Persistence popup -- musics.core's write/load (plain repo
         ;; material) and persist-session/restore-session (also
         ;; round-trips a voice's :algo-assignments), previously REPL-
         ;; only. Each op runs in a background future (see
         ;; run-persistence-op! below) since a full repo walk can be
         ;; non-trivial for a large session -- :busy? gates the panel's
         ;; own buttons meanwhile.
         :persistence-open? false
         :persistence {:write-path "" :load-path "" :busy? false :message nil}
         ;; record-midi's own panel state -- see start-record!/
         ;; write-record! below. :text is what the panel's text area
         ;; shows/edits; :recording? gates the Start button's own
         ;; label/disable state while a background future (see
         ;; start-record!) is blocked in input.midi-record/open-record;
         ;; :collapsed? drives the panel's own ▾/▸ toggle (see
         ;; toggle-record-collapsed! and gui.lib.components/titled-panel).
         :record {:recording? false :text "" :name "" :instrument "" :collapsed? true
                  :auto-commit? false}
         ;; MIDI Input -- musics.core's own open-midi/close-midi/
         ;; list-midi-inputs (input.midi), previously reachable only
         ;; by requiring that namespace directly (this record panel
         ;; itself already does, for input.midi-record). :device-
         ;; substring matches a source's name/description as a case-
         ;; insensitive regexp (blank -> overtone's own GUI chooser);
         ;; :open? gates the panel's own Open/Close toggle button and
         ;; is deliberately separate from :record :recording? --
         ;; opening MIDI input (audible thru + event delivery) and
         ;; actually recording it to text are two different actions
         ;; now, where record-midi's own open-record used to require
         ;; a device to already be open with no GUI path to do that at
         ;; all (confirmed live: Start Recording would always fail
         ;; with \"No MIDI input open\" otherwise)."
         :midi-input {:devices-text "" :device-substring "" :open? false :message nil}
         ;; Transform workbench -- musics.core's generative transforms
         ;; (times/transpose/invert/scale/reverse/shuffle/tonal-*),
         ;; previously REPL-only. :last-result stashes Preview's own
         ;; already-computed material for Commit to reuse (NOT
         ;; recomputed -- some transforms, shuffle in particular,
         ;; aren't idempotent, so re-running the transform for Commit
         ;; could commit something different from what Preview showed).
         :transform-open? false
         :transform {:source-id "" :transform-name "" :params ""
                     :preview-text "" :new-id "" :last-result nil :message nil}
         ;; id -> {:params {canonical-key double} :combos {canonical-key display-name}
         ;;        :hot? bool :zoom {key {:min :max}}
         ;;        :unified? bool :collapsed? bool :show-labels? bool}
         ;; :params/:combos kept as two separate maps rather than one,
         ;; even for a key like :volume that appears in BOTH param-specs
         ;; (a raw fader) and combo-specs (a named-dynamic picker) -- a
         ;; single {key -> value} map couldn't hold both a double and
         ;; a display string under the same key at once.
         :watched (sorted-map)}))

(defn- container-context
  "The real, live Context for id (as of the latest committed tx),
   :ROOT included, or nil if id doesn't resolve to a container at all."
  [id]
  (get-in (repo/view (repo/latest-tx)) [id :context]))

(defn- read-value
  [id key]
  (let [v (m/ctx-value id key 0)]
    (if (number? v) (double v) v)))

(defn- read-combo
  "The display name for id's current value of key, per lookup's own
   :value->name -- falls back to lookup's first item if the raw value
   doesn't match any known name (e.g. :instrument's un-set default of
   0, which is below gm-sound-set's own lowest :prog of 1)."
  [id key {:keys [lookup]}]
  (let [v (m/ctx-value id key 0)]
    (or (get (:value->name lookup) (some-> v int))
        (first (:items lookup)))))

(defn- write-value!
  "The one place that actually mutates a real Context: root? picks
   between :ROOT's bare-swap path and every other container's timed
   ctx-append path -- see set-param!'s own docstring for why each
   exists. Shared by set-param! (a raw slider write) and set-combo!
   (a picked-name write, already resolved to its real numeric value
   by the caller)."
  [ctx root? k value]
  (if root?
    (swap! (:envelopes-atom ctx) assoc (name k) value)
    (let [env (get @(:envelopes-atom ctx) (name k))
          time (if (instance? core.domain.context.Envelope env)
                 (or (:time (last @(:points-atom env))) 0)
                 0)]
      (c/ctx-append ctx k time value :fixed))))

(defn watch!
  "Start showing sliders/dropdowns for id-str's own context values
   (the keys in param-specs/combo-specs). :ROOT is a valid target --
   watching it edits the session-wide defaults everything else falls
   through to (see set-param!). No-op (prints why) if id-str is blank
   or doesn't resolve to a real container.
   If id is ALREADY watched, this only clears the input field --
   re-reading fresh values would also silently reset that container's
   own :hot?/:zoom state (e.g. every time open-root! re-ensures :ROOT
   is watched), which is surprising for a window that's just sitting
   open."
  [id-str]
  (when (seq (str/trim id-str))
    (let [id (keyword (str/trim id-str))]
      (cond
        (contains? (:watched @*state) id)
        (swap! *state assoc :new-id "")

        (container-context id)
        (swap! *state
               (fn [s]
                 (-> s
                     (assoc-in [:watched id]
                               {:hot? false
                                :zoom {}
                                :unified? false
                                :collapsed? false
                                :show-labels? true
                                :params (into {} (for [[k _] param-specs] [k (read-value id k)]))
                                :combos (into {} (for [[k spec] combo-specs] [k (read-combo id k spec)]))})
                     (assoc :new-id ""))))

        :else
        (println "[gui] Not a watchable container:" id-str))))
  nil)

(defn unwatch!
  [id]
  (swap! *state update :watched dissoc id)
  nil)

(defn open-root!
  "Show the dedicated :ROOT window -- watches :ROOT first if it isn't
   already (idempotent, see watch!)."
  []
  (watch! "ROOT")
  (swap! *state assoc :root-open? true)
  nil)

(defn close-root!
  []
  (swap! *state assoc :root-open? false)
  nil)

(defn set-new-id!
  [s]
  (swap! *state assoc :new-id s)
  nil)

(defn set-theme!
  "Switch every window's stylesheet -- theme is :dark or :light (see
   gui.lib.theme/stylesheet)."
  [theme]
  (swap! *state assoc :theme theme)
  nil)

(defn toggle-theme!
  []
  (set-theme! (if (= :dark (:theme @*state)) :light :dark)))

(defn- hot?
  [id]
  (boolean (get-in @*state [:watched id :hot?])))

(defn- apply-param!
  "The actual per-id write: *state preview always, real Context only
   while id is hot. No fanout -- see set-param! for the unified? group
   broadcast, which calls this directly (once per group member) rather
   than recursing back through set-param! itself, to avoid a unified
   member's own fanout cascading into every other member repeatedly."
  [id key value]
  (when (hot? id)
    (when-let [ctx (container-context id)]
      (write-value! ctx (= id :ROOT) (defaults/canonical-key key) value)))
  (swap! *state assoc-in [:watched id :params key] value))

(defn- apply-combo!
  [id key display-name]
  (when-let [{:keys [lookup]} (get combo-specs key)]
    (when-let [value (get (:name->value lookup) display-name)]
      (when (hot? id)
        (when-let [ctx (container-context id)]
          (write-value! ctx (= id :ROOT) (defaults/canonical-key key) value)))
      (swap! *state assoc-in [:watched id :combos key] display-name))))

(defn- unified-peers
  "Every OTHER watched id that's also unified? and also exposes key --
   see toggle-unified!/set-param!'s own docstring for why this is the
   context-path analogue of the original per-voice 'U' gang control."
  [id key entry-key]
  (for [[other-id entry] (:watched @*state)
        :when (and (not= other-id id)
                   (:unified? entry)
                   (contains? (get entry entry-key) key))]
    other-id))

(defn set-param!
  "Update *state's preview value for key on id -- always, so a slider
   drag is responsive even while cold -- and, only while id is HOT
   (see toggle-hot!), also write value onto its REAL Context
   (canonicalized the same way an authored !key: instruction is, so
   this reads back under whatever alias set-param!/ctx-value are both
   called with).

   :ROOT takes a different path than every other container: its own
   values are always bare (never a real Envelope, see ns docstring),
   so this is a direct swap! of the bare value -- no time coordinate,
   no points, takes effect everywhere that falls through to :ROOT the
   instant it lands.

   Every other container writes at its own envelope's latest point
   time (or 0 if it has none yet) -- env-append replaces a same-instant
   point rather than adding a new one (see env-append's own docstring
   on ==), so repeated drags move ONE point rather than building up a
   new point per tick. Placing new points at chosen future beats to
   author a ramp from the GUI is not this function's job -- it only
   ever edits 'the current value'.

   If id is unified? (see toggle-unified!, the 'U' control), the SAME
   value also fans out to every other unified?+param-specs[key]-having
   watched id, each independently gated by ITS OWN hot? -- so dragging
   one member's Tempo slider moves every ganged member's Tempo slider
   together, but only the ones also armed hot actually sound the
   change, exactly mirroring the original per-voice U+R combination."
  [id key value]
  (apply-param! id key value)
  (doseq [peer (unified-peers id key :params)]
    (apply-param! peer key value))
  nil)

(defn set-combo!
  "Like set-param!, but for a combo-specs key: display-name is looked
   up against key's own gui.lib.data lookup (:name->value) to recover
   the real value BEFORE writing it through -- *state and the slider/
   combo-facing code never juggle raw MIDI program numbers or velocity
   values for these keys directly, only names. Also gated by hot?, and
   fanned out to unified? peers, exactly like set-param!."
  [id key display-name]
  (apply-combo! id key display-name)
  (doseq [peer (unified-peers id key :combos)]
    (apply-combo! peer key display-name))
  nil)

(defn toggle-hot!
  "Flip id's hot/cold arm state (see ns docstring: cold means slider/
   combo drags only update the *state preview; hot means they also
   write through to the real Context). Arming (cold -> hot) also
   immediately pushes every currently-PREVIEWED param/combo value
   through, same as the original JavaFX record-arm button did -- so
   values you dragged while cold aren't silently dropped the moment
   you arm."
  [id]
  (let [now-hot? (not (hot? id))]
    (swap! *state assoc-in [:watched id :hot?] now-hot?)
    (when now-hot?
      (when-let [ctx (container-context id)]
        (let [{:keys [params combos]} (get-in @*state [:watched id])]
          (doseq [[key value] params]
            (write-value! ctx (= id :ROOT) (defaults/canonical-key key) value))
          (doseq [[key display-name] combos]
            (when-let [{:keys [lookup]} (get combo-specs key)]
              (when-let [value (get (:name->value lookup) display-name)]
                (write-value! ctx (= id :ROOT) (defaults/canonical-key key) value))))))))
  nil)

(defn toggle-unified!
  "Flip id's membership in the unified? ('U') group -- see set-param!'s
   own docstring for the fanout this drives."
  [id]
  (swap! *state update-in [:watched id :unified?] not)
  nil)

(defn toggle-collapsed!
  "Flip id's collapsed? ('S', show/hide) flag -- gui.lib.core reads
   this to omit that window's slider/combo body, leaving just its
   title bar and hot/unified controls."
  [id]
  (swap! *state update-in [:watched id :collapsed?] not)
  nil)

(defn toggle-labels!
  "Flip id's show-labels? ('L') flag -- gui.lib.core reads this to
   hide/show the descriptive + readout labels next to id's own
   sliders/combos, leaving just the bare controls."
  [id]
  (swap! *state update-in [:watched id :show-labels?] not)
  nil)

(defn zoom!
  "Cycle key's visible slider range on id, centered on its current
   value -- the 'circular zoom' from the original JavaFX GUI: each
   call halves the smaller of (distance to current max) / (distance
   to current min) around the current value, UNLESS the resulting
   span would fall at or below key's own :zoom-floor, in which case
   it wraps back out to the full param-specs range instead of
   narrowing further ('in, in, in, out to the beginning'). Purely a
   display-range concern -- never touches the real Context, only
   [:watched id :zoom key], which param-rows reads instead of the
   static spec bounds when it's present."
  [id key]
  (let [{:keys [min max zoom-floor] :or {zoom-floor 1.0}} (get param-specs key)
        {cmin :min cmax :max} (get-in @*state [:watched id :zoom key] {:min min :max max})
        value (get-in @*state [:watched id :params key] min)
        delta (/ (clojure.core/min (- cmax value) (- value cmin)) 2.0)
        new-min (- value delta)
        new-max (+ value delta)]
    (swap! *state assoc-in [:watched id :zoom key]
           (if (> (- new-max new-min) zoom-floor)
             {:min new-min :max new-max}
             {:min (double min) :max (double max)})))
  nil)

;; ============================================================
;; Transport -- thin wrappers over musics.clj's real engine control.
;; Reset (musics/reset) wipes the ENTIRE session/history, not just
;; playback -- wired here because the user asked for it explicitly,
;; but it is genuinely destructive, unlike the other three.
;; ============================================================

(defn connect!
  "Open the MIDI receiver and warm up the engine explicitly -- play!
   would do this lazily on first use anyway, but a visible Connect
   step lets the warm-up burst happen ahead of a real Play click."
  []
  (m/connect)
  nil)

(defn play!
  "Play every currently-watched container id, in watch order. Wrapped
   into a single [] Form -- play's own single-Form call shape no longer
   accepts several top-level ids directly -- [] is always sequential,
   so the 'in watch order' behavior is unchanged."
  []
  (let [ids (keys (:watched @*state))]
    (if (seq ids)
      (do (m/play (vec ids))
          (swap! *state assoc :transport :playing))
      (println "[gui] Nothing watched yet -- type an id and hit Watch first.")))
  nil)

(defn stop!
  []
  (m/stop!)
  (swap! *state assoc :transport :stopped)
  nil)

(defn pause!
  []
  (m/pause!)
  (swap! *state assoc :transport :paused)
  nil)

(defn resume!
  []
  (m/resume!)
  (swap! *state assoc :transport :playing)
  nil)

(defn abort!
  "Hard cutoff: silence every MIDI channel immediately, distinct from
   stop! (which halts scheduling but relies on the engine's own ~20ms
   note-off check) -- the GUI's panic button."
  []
  (m/all-notes-off)
  (swap! *state assoc :transport :stopped)
  nil)

(defn reset!
  "Wipe the whole session -- see musics/reset's own docstring. Clears
   :watched too, since every watched Context just stopped existing."
  []
  (m/reset)
  (swap! *state assoc :transport :stopped :watched (sorted-map))
  nil)

;; ============================================================
;; Voices -- "actually playing" vs. "committed, waiting for
;; activation". See ns docstring for why this is a poll, not a watch.
;; ============================================================

(defonce ^:private voice-poll-running? (atom false))

(defn waiting-ids
  "Committed top-level ids (musics.clj/root-children) that are NOT
   currently in :playing-ids -- 'waiting for activation'."
  []
  (into (sorted-set) (remove (:playing-ids @*state)) (m/root-children)))

(defn connected?
  "Whether the current session has an open MIDI receiver -- true the
   moment ANY connect has succeeded (the Connect button, or play/
   play-add/play!'s own auto-connect), not just after this button
   specifically. Read fresh at render time (see gui.lib.core's own
   status-bar) rather than tracked as its own :state key -- same
   pattern waiting-ids above already uses; the voice poll's own
   ~200ms tick already forces a re-render regularly enough to pick
   this up without a dedicated poll of its own."
  []
  (some? @m/receiver))

(defn latest-tx
  []
  (m/latest-tx))

(defn- live-voice-details
  "path -> {:algo :tx} for every CURRENTLY LIVE voice on the engine --
   NOT keyed by the repo container id a naive reading of playing-ids
   might suggest. Confirmed live (a real bug, not a hypothetical): a
   played container's own id (:testMelody) is never itself a
   registered voice path -- play/play-add always mint a fresh :TAA/
   :TAB/... path, so voice-at only ever resolves a real path, never
   the id material was played FROM (that's what playing-ids, tracked
   completely separately via :active-voices, is for). Built from
   core.async-engine/live-algos (already path -> algo, read straight
   off each voice's own immutable :algo field -- see that fn's own
   docstring) plus voice-at on that SAME path for its own live :tx
   atom (dereffed here; :algo needs no deref, already a plain value)."
  []
  (into {}
        (map (fn [[path algo]]
               [path {:algo algo :tx (some-> (m/voice-at path) :tx deref)}]))
        (engine/live-algos)))

(defn start-voice-poll!
  "Begin mirroring core.async-engine's live playing-ids (repo container
   ids currently sounding), live-voice-details (voice paths currently
   live, each with its own algo/tx), AND algo-assignments (the wall
   panel's own PREPARED-not-live table -- lives on the engine
   instance, same identity-churns-on-reconnect reason :voices does, so
   it has to ride this same poll rather than an add-watch) into
   *state's own :playing-ids/:voice-details/[:wall :assignments] every
   ~200ms. Idempotent -- a second call while already running is a
   no-op."
  []
  (when (compare-and-set! voice-poll-running? false true)
    (future
      (while @voice-poll-running?
        (swap! *state
               (fn [s]
                 (-> s
                     (assoc :playing-ids (engine/playing-ids)
                            :voice-details (live-voice-details))
                     (assoc-in [:wall :assignments] (m/algo-assignments)))))
        (Thread/sleep 200))))
  nil)

(defn stop-voice-poll!
  []
  ;; clojure.core/reset! is excluded from this ns (see ns form) so
  ;; gui.lib.state/reset! -- the 0-arg session-wipe -- can have that
  ;; name; fully-qualify to reach the atom primitive here instead.
  (clojure.core/reset! voice-poll-running? false)
  nil)

;; ============================================================
;; record-midi -- thin GUI wrapper over input.midi-record. The actual
;; blocking (input.midi-record/open-record) call runs in a `future`,
;; same pattern start-voice-poll! already uses to keep a background
;; loop off cljfx's own render thread -- a plain swap! from that
;; future's own thread is enough for cljfx to pick the change up (see
;; ns docstring's own note on this, and the "GUI real-time context
;; vision" project memory this app was built from).
;; ============================================================

(defn set-record-text!
  "Update the panel's own text area -- both live typing (a hand edit
   before Write) and open-record's own eventual result land here, the
   same key either way."
  [text]
  (swap! *state assoc-in [:record :text] text)
  nil)

(defn set-record-name!
  [name]
  (swap! *state assoc-in [:record :name] name)
  nil)

(defn set-record-instrument!
  [instrument]
  (swap! *state assoc-in [:record :instrument] instrument)
  nil)

(defn toggle-record-collapsed!
  "Flip the Record MIDI panel's own ▾/▸ collapse state -- purely a
   display concern, no effect on an in-progress recording (start-
   record!'s own future keeps running collapsed or not)."
  []
  (swap! *state update-in [:record :collapsed?] not)
  nil)

(defn start-record!
  "Start a background recording -- see input.midi-record/open-record's
   own docstring for start/stop and quantization. :instrument, if
   non-blank, is passed through as-is (a GM program number OR name
   string, open-record's own resolve-instrument accepts either). A
   second call while already recording is a no-op (recording is a
   single global input.midi-record/*cancel-chan, not per-panel state)."
  []
  (when-not (:recording? (:record @*state))
    (swap! *state assoc-in [:record :recording?] true)
    (let [instrument (let [i (str/trim (:instrument (:record @*state) ""))]
                        (when (seq i) i))]
      (future
        (let [text (try
                     (rec/open-record instrument)
                     (catch Exception e
                       (str "%% record-midi failed: " (ex-message e))))]
          (swap! *state (fn [s] (-> s
                                     (assoc-in [:record :recording?] false)
                                     (assoc-in [:record :text] text)))))))
    nil))

(defn stop-record!
  "Manually end whatever recording is currently running -- see
   input.midi-record/stop-record!'s own docstring. No-op if nothing is
   currently recording."
  []
  (rec/stop-record!)
  nil)

(defn toggle-record-auto-commit!
  []
  (swap! *state update-in [:record :auto-commit?] not)
  nil)

(defn write-record!
  "Save the panel's current text (whatever's in the text area right
   now, hand edits included) to <name>.mus in the current working
   directory -- same as any other .mus a user might load via
   (musics/parse-file). No-op (prints why) if name is blank. When
   :auto-commit? is on, also immediately parse-file + commit! the
   written file, so a recorded phrase becomes playable without leaving
   the GUI at all -- off by default, since a plain disk write with no
   further side effect is what this button has always done."
  []
  (let [{:keys [name text auto-commit?]} (:record @*state)
        name (str/trim (or name ""))]
    (if (seq name)
      (let [path (str name ".mus")]
        (spit path text)
        (println "[gui] Wrote" path)
        (when auto-commit?
          (let [{:keys [sid ids]} (or (m/parse-file path) {})]
            (if sid
              (do (m/commit! sid)
                  (println "[gui] Auto-committed" path "->" ids))
              (println "[gui] Auto-commit skipped -- parse failed, see console.")))))
      (println "[gui] Nothing written -- type a name first.")))
  nil)

;; ============================================================
;; MIDI Input -- musics.core's open-midi/close-midi/list-midi-inputs
;; (input.midi), previously REPL-only. Deliberately separate from
;; record-midi's own :recording? -- opening MIDI input (audible thru
;; + event delivery) and actually recording it to text are two
;; different actions; open-midi's own no-arg/blank-substring form can
;; pop a blocking Swing device chooser (same hazard output.midi.midi-
;; live/open-receiver already has on the output side), so this runs in
;; a background future too, same pattern as start-record!.
;; ============================================================

(defn refresh-midi-devices!
  []
  (swap! *state assoc-in [:midi-input :devices-text]
         (str/join "\n" (map :name (m/list-midi-inputs))))
  nil)

(defn set-midi-device-substring!
  [s]
  (swap! *state assoc-in [:midi-input :device-substring] s)
  nil)

(defn open-midi-input!
  []
  (swap! *state assoc-in [:midi-input :message] "Opening…")
  (let [sub (str/trim (:device-substring (:midi-input @*state) ""))]
    (future
      (let [result (try (m/open-midi (when (seq sub) sub)) {:ok true}
                         (catch Exception e {:error (ex-message e)}))]
        (swap! *state (fn [s]
                         (-> s
                             (assoc-in [:midi-input :open?] (not (:error result)))
                             (assoc-in [:midi-input :message]
                                       (if (:error result)
                                         (str "Failed: " (:error result))
                                         "MIDI input open -- audible through (connect)'s own receiver."))))))))
  nil)

(defn close-midi-input!
  []
  (m/close-midi)
  (swap! *state (fn [s] (-> s
                            (assoc-in [:midi-input :open?] false)
                            (assoc-in [:midi-input :message] "MIDI input closed."))))
  nil)

(defn toggle-midi-input!
  []
  (if (:open? (:midi-input @*state))
    (close-midi-input!)
    (open-midi-input!)))

;; ============================================================
;; Editor panel -- write/parse/stage/commit musics text, the GUI's own
;; path to musics.core/parse+commit! (previously REPL-only). Toggled
;; open/closed the same way as the :ROOT window (see open-root!/
;; close-root! above) rather than tied to any watch!/unwatch! -- it
;; isn't a container's context, so it doesn't belong in :watched.
;; ============================================================

(defn open-editor! [] (swap! *state assoc :editor-open? true) nil)
(defn close-editor! [] (swap! *state assoc :editor-open? false) nil)

(defn set-editor-text!
  [text]
  (swap! *state assoc-in [:editor :text] text)
  nil)

(defn set-editor-load-path!
  [path]
  (swap! *state assoc-in [:editor :load-path] path)
  nil)

(defn- capture-out
  "Calls f with *out* rebound to a fresh StringWriter -- returns
   [return-value printed-string]. musics.core/parse (and commit!'s own
   redefinition warning) only ever PRINT their error/warning text, they
   never return it -- with no visible console in front of a GUI user
   (confirmed live: 'Parse failed -- see the REPL console' was useless
   advice when there's no REPL console reachable at all in this
   workflow), this is what lets a caller show that same text directly
   in the panel instead. Same technique the Browser panel's own
   refresh-browser-detail! already uses via with-out-str, just also
   keeping the return value that with-out-str alone discards."
  [f]
  (let [sw (java.io.StringWriter.)
        result (binding [*out* sw] (f))]
    [result (str sw)]))

(defn- apply-editor-parse-result!
  "Shared by editor-parse!/editor-load-file! -- result is whatever
   musics.core/parse or parse-file just returned (nil on failure);
   printed is whatever it printed along the way (see capture-out) --
   the actual error text on failure, empty on success."
  [{:keys [sid ids]} printed]
  (swap! *state update :editor merge
         {:sid sid :ids ids
          :message (if sid
                     (str "Staged sid " sid ", ids: " (pr-str ids))
                     (let [p (str/trim (or printed ""))]
                       (if (seq p)
                         (str "Parse failed: " p)
                         "Parse failed (no error message available).")))})
  nil)

(defn editor-parse!
  "Parse (stage, not commit) the editor's current text -- see
   musics.core/parse. A second Parse before Commit/Abort re-parses
   against the same still-uncommitted baseline and simply replaces the
   pending sid/ids/message with this call's own."
  []
  (let [[result printed] (capture-out #(m/parse (:text (:editor @*state))))]
    (apply-editor-parse-result! (or result {}) printed)))

(defn editor-commit!
  "Commit whatever's currently staged (see musics.core/commit!) -- a
   no-op (message only) if nothing's pending. commit!'s own redefine-
   affects-others warning (if any -- see its own docstring) is folded
   into the success message too, via capture-out, rather than left
   console-only."
  []
  (if-let [sid (:sid (:editor @*state))]
    (let [[tx printed] (capture-out #(m/commit! sid))
          warning (str/trim (or printed ""))]
      (swap! *state update :editor merge
             {:sid nil :ids nil
              :message (cond
                         (and tx (seq warning)) (str "Committed at tx " tx ". " warning)
                         tx (str "Committed at tx " tx ".")
                         :else "Nothing to commit.")}))
    (swap! *state assoc-in [:editor :message] "Nothing staged to commit."))
  nil)

(defn editor-abort!
  "Discard whatever's currently staged (see musics.core/abort!) without
   ever making it visible."
  []
  (if-let [sid (:sid (:editor @*state))]
    (do (m/abort! sid)
        (swap! *state update :editor merge {:sid nil :ids nil :message "Aborted."}))
    (swap! *state assoc-in [:editor :message] "Nothing staged to abort."))
  nil)

(defn editor-clear!
  "Reset the Editor panel back to blank -- text, load path, and any
   pending sid/ids/message -- the panel's own \"new file\" button.
   Aborts a pending staged sid first (see musics.core/abort!) rather
   than just forgetting about it here: leaving it staged-but-invisible
   would silently orphan it in core.repo's staging area, reachable
   only by (pending sid) at the REPL from then on."
  []
  (when-let [sid (:sid (:editor @*state))]
    (m/abort! sid))
  (swap! *state assoc :editor {:text "" :load-path "" :sid nil :ids nil :message nil})
  nil)

(defn editor-parse-and-commit!
  "Parse then immediately commit, in one click -- the panel's own
   (sc! text), with the same pass/fail message convention as
   editor-parse!/editor-commit!."
  []
  (let [[{:keys [sid ids]} parse-printed] (capture-out #(m/parse (:text (:editor @*state))))]
    (if sid
      (let [[tx commit-printed] (capture-out #(m/commit! sid))
            warning (str/trim (or commit-printed ""))]
        (swap! *state update :editor merge
               {:sid nil :ids nil
                :message (str "Committed at tx " tx ", ids: " (pr-str ids)
                              (when (seq warning) (str " " warning)))}))
      (let [p (str/trim (or parse-printed ""))]
        (swap! *state assoc-in [:editor :message]
               (if (seq p) (str "Parse failed: " p) "Parse failed (no error message available).")))))
  nil)

(defn editor-load-file!
  "Read the editor's :load-path, show its own contents in the text area
   (musics.core/parse-file alone never surfaces the text it read, only
   its parse result -- calling that directly here left the editor
   showing nothing after a load, a real gap: the file WAS staged, just
   invisibly), and stage it via musics.core/parse on that same text --
   still requires a separate Commit."
  []
  (let [path (str/trim (:load-path (:editor @*state) ""))]
    (if (seq path)
      (let [text (try (slurp path) (catch Exception _ ::read-failed))]
        (if (= text ::read-failed)
          (swap! *state assoc-in [:editor :message] (str "Could not read file: " path))
          (do (swap! *state assoc-in [:editor :text] text)
              (let [[result printed] (capture-out #(m/parse text))]
                (apply-editor-parse-result! (or result {}) printed)))))
      (swap! *state assoc-in [:editor :message] "Type a file path first.")))
  nil)

(defn editor-save-to-path!
  "Write the editor's current text (hand edits included) to path,
   overwriting whatever's already there -- a plain disk save, same
   spirit as the record panel's write-record!, and deliberately
   separate from Parse/Commit (a Save never touches staging at all,
   just like a text editor's own Save doesn't imply anything about
   the running session). Also updates :load-path to path, so this
   is what a following plain Save reuses -- see gui.lib.core's own
   editor-save! for the \"no path yet -> fall back to a Save dialog\"
   decision, which belongs on the Controller side (it needs a Window)."
  [path]
  (spit path (:text (:editor @*state) ""))
  (swap! *state update :editor merge {:load-path path :message (str "Saved " path ".")})
  nil)

;; ============================================================
;; Play Builder panel -- assembles a real play/play-add/play-change
;; Form (musics.core's own mini-language, see that ns's own docstring)
;; from an ordered list of ids instead of the state window's own
;; fixed, always-sequential "play everything watched" button.
;; ============================================================

(defn open-play-builder! [] (swap! *state assoc :play-builder-open? true) nil)
(defn close-play-builder! [] (swap! *state assoc :play-builder-open? false) nil)

(defn play-builder-add-id!
  "Append id to the builder's own ordered id list, unless it's already
   there (:par mode -- see musics.core/par -- is the way to actually
   repeat an id; a plain Add click adding it twice by accident would
   silently double it instead)."
  [id]
  (when id
    (swap! *state update-in [:play-builder :ids]
           (fn [ids] (if (some #{id} ids) ids (conj (vec ids) id)))))
  nil)

(defn set-play-builder-query!
  [s]
  (swap! *state assoc-in [:play-builder :query] s)
  nil)

(defn play-builder-add-from-text!
  []
  (let [q (str/trim (:query (:play-builder @*state) ""))]
    (when (seq q)
      (play-builder-add-id! (keyword q))
      (swap! *state assoc-in [:play-builder :query] "")))
  nil)

(defn play-builder-remove-id!
  [id]
  (swap! *state update-in [:play-builder :ids] (fn [ids] (vec (remove #{id} ids))))
  nil)

(defn play-builder-remove-from-text!
  "Remove whatever id is currently typed in the same query field Add
   reads from -- the panel's own Remove button."
  []
  (let [q (str/trim (:query (:play-builder @*state) ""))]
    (when (seq q)
      (play-builder-remove-id! (keyword q))
      (swap! *state assoc-in [:play-builder :query] "")))
  nil)

(defn play-builder-clear!
  []
  (swap! *state assoc-in [:play-builder :ids] [])
  nil)

(defn toggle-play-builder-mode!
  []
  (swap! *state update-in [:play-builder :mode] #(if (= % :par) :seq :par))
  nil)

(defn set-play-builder-algo!
  [s]
  (swap! *state assoc-in [:play-builder :algo] s)
  nil)

(defn set-play-builder-change-path!
  [s]
  (swap! *state assoc-in [:play-builder :change-path] s)
  nil)

(defn set-play-builder-tx-text!
  [s]
  (swap! *state assoc-in [:play-builder :tx-text] s)
  nil)

(defn- play-builder-form
  "The one Form play/play-add/play-change all expect -- a plain vector
   ([] Form, always sequential) for :seq mode, or musics.core/par's
   metadata-tagged vector ((par ...) Form, always parallel and,
   unlike a literal #{...}, fine with a repeated id) for :par mode."
  [{:keys [ids mode]}]
  (if (= mode :par)
    (apply m/par ids)
    (vec ids)))

(defn- play-builder-algo-name
  [{:keys [algo]}]
  (let [a (str/trim (or algo ""))]
    (when (seq a) (keyword a))))

(defn- play-builder-args
  "form plus play/play-add/play-change's own OPTIONAL trailing
   :algo Name -- omitted entirely when the panel's algo field is
   blank, same as calling (play form) with no :algo at all."
  [pb]
  (let [form (play-builder-form pb)
        algo (play-builder-algo-name pb)]
    (if algo [form :algo algo] [form])))

(defn play-builder-play!
  "(play Form), replacing everything currently sounding -- see
   musics.core/play."
  []
  (let [pb (:play-builder @*state)]
    (if (seq (:ids pb))
      (let [result (apply m/play (play-builder-args pb))]
        (swap! *state assoc :transport :playing)
        (swap! *state assoc-in [:play-builder :message] (str "Playing: " (pr-str result))))
      (swap! *state assoc-in [:play-builder :message] "Add at least one id first.")))
  nil)

(defn play-builder-play-add!
  "(play-add Form), joining whatever's already sounding -- see
   musics.core/play-add."
  []
  (let [pb (:play-builder @*state)]
    (if (seq (:ids pb))
      (let [result (apply m/play-add (play-builder-args pb))]
        (swap! *state assoc :transport :playing)
        (swap! *state assoc-in [:play-builder :message] (str "Added: " (pr-str result))))
      (swap! *state assoc-in [:play-builder :message] "Add at least one id first.")))
  nil)

(defn play-builder-play-change!
  "(play-change path Form), superseding only whichever voice is
   currently registered at :change-path -- see musics.core/play-change.
   path is typed free-form (e.g. \"TAA\", whatever play/play-add last
   returned) -- there's no public way to enumerate every live voice
   path to pick from instead, only look one up once you already have
   it (voice-at)."
  []
  (let [pb (:play-builder @*state)
        path-text (str/trim (:change-path pb ""))]
    (cond
      (empty? (:ids pb))
      (swap! *state assoc-in [:play-builder :message] "Add at least one id first.")

      (empty? path-text)
      (swap! *state assoc-in [:play-builder :message] "Type a target path first, e.g. TAA.")

      :else
      (let [path (keyword path-text)
            result (apply m/play-change path (play-builder-args pb))]
        (swap! *state assoc :transport :playing)
        (swap! *state assoc-in [:play-builder :message] (str "Changed " path " -> " (pr-str result))))))
  nil)

(defn play-builder-set-tx!
  "Point the NEXT play/play-add/play-change call at a chosen tx -- see
   musics.core/play-tx!. Never affects anything already playing."
  []
  (let [txt (str/trim (:tx-text (:play-builder @*state) ""))]
    (if (seq txt)
      (try
        (m/play-tx! (Long/parseLong txt))
        (swap! *state assoc-in [:play-builder :message] (str "Next play starts from tx " txt "."))
        (catch NumberFormatException _
          (swap! *state assoc-in [:play-builder :message] "tx must be a whole number.")))
      (swap! *state assoc-in [:play-builder :message] "Type a tx number first.")))
  nil)

(defn play-builder-use-latest-tx!
  []
  (m/play-latest!)
  (swap! *state assoc-in [:play-builder :message] "Next play starts from the latest committed tx.")
  nil)

;; ============================================================
;; Repo browser panel -- musics.core's own inspection surface
;; (ids/children/leaves/print-structure/ctx/history/as-of), previously
;; REPL-only. :ids is kept live-synced with core.repo's own registry
;; atom via add-watch, deliberately NOT a poll: unlike core.async-
;; engine's :voices (a fresh map every (connect!), see ns docstring),
;; core.repo's registry (core.registries/*repo-registry*) is a stable
;; defonce whose identity survives a reconnect, so a plain add-watch
;; here genuinely reflects a concurrent REPL parse!/commit! immediately
;; -- a real improvement over polling, not just a style choice.
;; ============================================================

(defn open-browser! [] (swap! *state assoc :browser-open? true) nil)
(defn close-browser! [] (swap! *state assoc :browser-open? false) nil)

(defn- refresh-browser-ids!
  [& _]
  (swap! *state assoc-in [:browser :ids] (vec (m/ids)))
  nil)

(defonce ^:private browser-watch-installed? (atom false))

(defn start-browser-sync!
  "Install the add-watch described above and do one initial sync.
   Idempotent -- a second call is a no-op."
  []
  (when (compare-and-set! browser-watch-installed? false true)
    (add-watch reg/*repo-registry* ::browser-sync refresh-browser-ids!)
    (refresh-browser-ids!))
  nil)

(defn set-browser-query!
  [s]
  (swap! *state assoc-in [:browser :query] s)
  nil)

(defn set-browser-tx!
  [s]
  (swap! *state assoc-in [:browser :tx-text] s)
  nil)

(defn- refresh-browser-detail!
  "Re-render :browser :detail from :selected-id/:tx -- structure/ctx
   are captured via with-out-str straight off musics.core/print-
   structure and musics.core/ctx (both already-console-printing REPL
   fns) rather than re-implementing either's own tree/chain rendering
   a second time for the GUI."
  []
  (let [{:keys [selected-id tx]} (:browser @*state)
        t (or tx (m/latest-tx))]
    (when selected-id
      (if (m/find selected-id t)
        (swap! *state update :browser merge
               {:tx t
                :detail {:structure (with-out-str (m/print-structure selected-id t))
                         :ctx (with-out-str (m/ctx selected-id t))
                         :history (mapv first (m/history selected-id))
                         :error nil}})
        (swap! *state assoc-in [:browser :detail]
               {:error (str "Not found: " (name selected-id) " at tx " t)}))))
  nil)

(defn browser-inspect!
  []
  (let [q (str/trim (:query (:browser @*state) ""))]
    (if (seq q)
      (do (swap! *state update :browser assoc :selected-id (keyword q) :tx nil :query "")
          (refresh-browser-detail!))
      (swap! *state assoc-in [:browser :detail] {:error "Type an id first."})))
  nil)

(defn browser-goto-tx!
  []
  (let [txt (str/trim (:tx-text (:browser @*state) ""))]
    (if (seq txt)
      (try
        (swap! *state assoc-in [:browser :tx] (Long/parseLong txt))
        (refresh-browser-detail!)
        (catch NumberFormatException _
          (swap! *state assoc-in [:browser :detail :error] "tx must be a whole number.")))
      (swap! *state assoc-in [:browser :detail :error] "Type a tx number first.")))
  nil)

(defn browser-goto-latest!
  []
  (swap! *state update :browser assoc :tx nil :tx-text "")
  (refresh-browser-detail!)
  nil)

(defn browser-watch!
  "Open a context-editor window for the currently-selected id -- see
   watch! above."
  []
  (when-let [id (:selected-id (:browser @*state))]
    (watch! (name id)))
  nil)

(defn browser-add-to-play-builder!
  []
  (when-let [id (:selected-id (:browser @*state))]
    (play-builder-add-id! id))
  nil)

;; ============================================================
;; Wall algorithms panel -- musics.core's register-factory!/build!/
;; assign-algo! mechanism (core.wall), previously REPL-only. Factories/
;; algos/distributions/criteria are all read-only browse (pre-formatted
;; text, see fmt-doc-map/fmt-registered), live-synced via add-watch on
;; core.registries' own atoms exactly like the Browser panel's :ids --
;; see start-wall-sync!. :assignments (PREPARED, not live) is polled
;; alongside :playing-ids instead, since it lives on the engine
;; instance -- see start-voice-poll! above.
;; ============================================================

(defn open-wall! [] (swap! *state assoc :wall-open? true) nil)
(defn close-wall! [] (swap! *state assoc :wall-open? false) nil)

(defn- fmt-doc-map
  "{name -> doc} -> one \"name — doc\" line per entry, sorted by name --
   shared by the Factories/Distributions/Criteria displays, all the
   same shape (musics.core/factories, distributions, criteria)."
  [m]
  (str/join "\n"
            (for [[k v] (sort-by (comp str first) m)]
              (str (name k) " — " (or v "(no doc)")))))

(defn- fmt-registered
  "Like fmt-doc-map, but for musics.core/registered's own fuller
   {name -> {:fn :doc :factory-name :params}} shape -- appends the
   build recipe (factory + resolved params) when there is one, since
   a factory called directly (bypassing build!) stamps neither."
  [m]
  (str/join "\n"
            (for [[k {:keys [doc factory-name params]}] (sort-by (comp str first) m)]
              (str (name k) " — " (or doc "(no doc)")
                   (when factory-name
                     (str "  [factory: " (name factory-name) ", params: " (pr-str params) "]"))))))

(defn- refresh-wall!
  [& _]
  (swap! *state update :wall merge
         {:factories-text (fmt-doc-map (m/factories))
          :algos-text (fmt-registered (m/registered))
          :distributions-text (fmt-doc-map (m/distributions))
          :criteria-text (fmt-doc-map (m/criteria))})
  nil)

(defonce ^:private wall-watch-installed? (atom false))

(defn start-wall-sync!
  "Install add-watch on all four core.registries atoms this panel
   displays and do one initial sync. Idempotent -- a second call is a
   no-op."
  []
  (when (compare-and-set! wall-watch-installed? false true)
    (add-watch reg/*algo-factory-registry* ::wall-sync refresh-wall!)
    (add-watch reg/*algo-registry* ::wall-sync refresh-wall!)
    (add-watch reg/*distribution-registry* ::wall-sync refresh-wall!)
    (add-watch reg/*criteria-registry* ::wall-sync refresh-wall!)
    (refresh-wall!))
  nil)

(defn set-wall-build-name!
  [s]
  (swap! *state assoc-in [:wall :build-name] s)
  nil)

(defn set-wall-build-factory!
  [s]
  (swap! *state assoc-in [:wall :build-factory] s)
  nil)

(defn set-wall-build-params!
  [s]
  (swap! *state assoc-in [:wall :build-params] s)
  nil)

(defn wall-build!
  "Build (or hot-swap, if name already exists -- see musics.core/
   build!'s own docstring) a wall algo from the panel's own name/
   factory-name fields and its params text, parsed as EDN -- build!
   always wants a plain map, same contract every factory already
   requires. A malformed or non-map params string is caught HERE,
   before ever reaching build!, and reported directly in the panel
   rather than only via a console warning."
  []
  (let [{:keys [build-name build-factory build-params]} (:wall @*state)
        name (some-> (str/trim (or build-name "")) not-empty keyword)
        factory-name (some-> (str/trim (or build-factory "")) not-empty keyword)
        params-text (str/trim (or build-params ""))]
    (cond
      (nil? name)
      (swap! *state assoc-in [:wall :message] "Type a name first.")

      (nil? factory-name)
      (swap! *state assoc-in [:wall :message] "Type a factory name first.")

      :else
      (let [params (try (if (seq params-text) (edn/read-string params-text) {})
                         (catch Exception _ ::bad-edn))]
        (if (or (= params ::bad-edn) (not (map? params)))
          (swap! *state assoc-in [:wall :message] "Params must be a valid EDN map, e.g. {:n 5}")
          (do (m/build! name factory-name params)
              (swap! *state assoc-in [:wall :message] (str "Built " name " from " factory-name "."))
              (refresh-wall!))))))
  nil)

(defn set-wall-assign-path!
  [s]
  (swap! *state assoc-in [:wall :assign-path] s)
  nil)

(defn set-wall-assign-algo!
  [s]
  (swap! *state assoc-in [:wall :assign-algo] s)
  nil)

(defn wall-assign!
  "Prepare path so the NEXT voice minted there picks up the typed algo
   name -- musics.core/assign-algo!. A blank algo field clears
   whatever's currently prepared for path (assign-algo!'s own
   nil-clears contract)."
  []
  (let [{:keys [assign-path assign-algo]} (:wall @*state)
        path-text (str/trim (or assign-path ""))
        algo-text (str/trim (or assign-algo ""))]
    (if (empty? path-text)
      (swap! *state assoc-in [:wall :message] "Type a target path first, e.g. TAA.")
      (let [path (keyword path-text)
            algo (when (seq algo-text) (keyword algo-text))]
        (m/assign-algo! path algo)
        (swap! *state assoc-in [:wall :message]
               (str "Prepared " path " -> " (or algo "(cleared)") ".")))))
  nil)

;; ============================================================
;; Conductor / scheduling panel -- core.conductor's action-registry/
;; schedule/repeating tables (musics.core's register-action!/trigger!/
;; schedule!/unschedule!/schedule-tx!/unschedule-repeating!),
;; previously REPL-only. No musics.core wrapper lists every registered
;; action id the way factories/algos do for the Wall panel -- read
;; straight off core.registries' own atom for that one display, same
;; as gui.lib.state already does for core.async-engine's :voices where
;; no wrapper exists either.
;; ============================================================

(defn open-conductor! [] (swap! *state assoc :conductor-open? true) nil)
(defn close-conductor! [] (swap! *state assoc :conductor-open? false) nil)

(defn- fmt-ids
  [ids]
  (str/join "\n" (sort (map name ids))))

(defn- fmt-schedule-table
  "{[id phase] -> action-id} -> one \"id phase -> action-id\" line per
   entry, sorted -- shared by the one-shot and repeating displays,
   both the same shape (musics.core/scheduled, scheduled-repeating)."
  [m]
  (str/join "\n"
            (for [[[id phase] action-id] (sort-by (comp str first) m)]
              (str (name id) " " (name phase) " -> " (name action-id)))))

(defn- refresh-conductor!
  [& _]
  (swap! *state update :conductor merge
         {:actions-text (fmt-ids (keys @reg/*conductor-action-registry*))
          :scheduled-text (fmt-schedule-table (m/scheduled))
          :scheduled-repeating-text (fmt-schedule-table (m/scheduled-repeating))})
  nil)

(defonce ^:private conductor-watch-installed? (atom false))

(defn start-conductor-sync!
  "Install add-watch on all three core.registries atoms this panel
   displays and do one initial sync. Idempotent -- a second call is a
   no-op."
  []
  (when (compare-and-set! conductor-watch-installed? false true)
    (add-watch reg/*conductor-action-registry* ::conductor-sync refresh-conductor!)
    (add-watch reg/*conductor-schedule* ::conductor-sync refresh-conductor!)
    (add-watch reg/*conductor-repeating* ::conductor-sync refresh-conductor!)
    (refresh-conductor!))
  nil)

(defn set-conductor-trigger-id!
  [s]
  (swap! *state assoc-in [:conductor :trigger-id] s)
  nil)

(defn set-conductor-trigger-args!
  [s]
  (swap! *state assoc-in [:conductor :trigger-args] s)
  nil)

(defn conductor-trigger!
  "Apply the action registered under the typed id to the typed args
   (an EDN vector, e.g. [1 2 3] -- blank means no args) -- musics.core/
   trigger!. A no-op (message only) if the id is blank; trigger! itself
   already no-ops silently on an unregistered id, so nothing further
   to guard there."
  []
  (let [{:keys [trigger-id trigger-args]} (:conductor @*state)
        id-text (str/trim (or trigger-id ""))]
    (if (empty? id-text)
      (swap! *state assoc-in [:conductor :message] "Type an action id first.")
      (let [id (keyword id-text)
            args-text (str/trim (or trigger-args ""))
            args (try (if (seq args-text) (edn/read-string args-text) [])
                       (catch Exception _ ::bad-edn))]
        (if (or (= args ::bad-edn) (not (vector? args)))
          (swap! *state assoc-in [:conductor :message] "Args must be a valid EDN vector, e.g. [1 2 3]")
          (do (apply m/trigger! id args)
              (swap! *state assoc-in [:conductor :message] (str "Triggered " id ".")))))))
  nil)

(defn set-conductor-schedule-id!
  [s]
  (swap! *state assoc-in [:conductor :schedule-id] s)
  nil)

(defn toggle-conductor-schedule-phase!
  []
  (swap! *state update-in [:conductor :schedule-phase] #(if (= % "exit") "enter" "exit"))
  nil)

(defn set-conductor-schedule-action-id!
  [s]
  (swap! *state assoc-in [:conductor :schedule-action-id] s)
  nil)

(defn conductor-schedule!
  "One-shot: fire the typed action-id the next time the typed section
   id crosses the toggled phase (:enter/:exit) -- musics.core/
   schedule!. Consumed the moment it fires, same as the REPL fn."
  []
  (let [{:keys [schedule-id schedule-phase schedule-action-id]} (:conductor @*state)
        id-text (str/trim (or schedule-id ""))
        action-text (str/trim (or schedule-action-id ""))]
    (cond
      (empty? id-text)
      (swap! *state assoc-in [:conductor :message] "Type a section id first.")

      (empty? action-text)
      (swap! *state assoc-in [:conductor :message] "Type an action id first.")

      :else
      (let [id (keyword id-text)
            phase (keyword (or schedule-phase "enter"))
            action-id (keyword action-text)]
        (m/schedule! id phase action-id)
        (swap! *state assoc-in [:conductor :message]
               (str "Scheduled " id " " (name phase) " -> " action-id ".")))))
  nil)

(defn conductor-unschedule!
  "Cancel a pending one-shot schedule! entry -- musics.core/
   unschedule!."
  []
  (let [{:keys [schedule-id schedule-phase]} (:conductor @*state)
        id-text (str/trim (or schedule-id ""))]
    (if (empty? id-text)
      (swap! *state assoc-in [:conductor :message] "Type a section id first.")
      (let [id (keyword id-text)
            phase (keyword (or schedule-phase "enter"))]
        (m/unschedule! id phase)
        (swap! *state assoc-in [:conductor :message]
               (str "Unscheduled " id " " (name phase) ".")))))
  nil)

(defn set-conductor-tx-id!
  [s]
  (swap! *state assoc-in [:conductor :tx-id] s)
  nil)

(defn toggle-conductor-tx-phase!
  []
  (swap! *state update-in [:conductor :tx-phase] #(if (= % "exit") "enter" "exit"))
  nil)

(defn set-conductor-tx-target!
  [s]
  (swap! *state assoc-in [:conductor :tx-target] s)
  nil)

(defn conductor-schedule-tx!
  "Arm a live-playback cutover: the next time the typed section id
   crosses the toggled phase, EVERY voice there redirects to the typed
   target tx -- musics.core/schedule-tx!. A blank or \"latest\" target
   means :latest, resolved at fire time, not now. Stays armed until
   explicitly unscheduled (see conductor-unschedule-repeating!) --
   unlike conductor-schedule!'s own one-shot table, this one repeats."
  []
  (let [{:keys [tx-id tx-phase tx-target]} (:conductor @*state)
        id-text (str/trim (or tx-id ""))
        target-text (str/trim (or tx-target ""))]
    (if (empty? id-text)
      (swap! *state assoc-in [:conductor :message] "Type a section id first.")
      (let [id (keyword id-text)
            phase (keyword (or tx-phase "enter"))
            target (if (or (empty? target-text) (= target-text "latest"))
                     :latest
                     (try (Long/parseLong target-text)
                          (catch NumberFormatException _ ::bad-tx)))]
        (if (= target ::bad-tx)
          (swap! *state assoc-in [:conductor :message]
                 "Target tx must be a whole number, or blank/\"latest\".")
          (do (m/schedule-tx! id phase target)
              (swap! *state assoc-in [:conductor :message]
                     (str "Armed " id " " (name phase) " -> tx " target ".")))))))
  nil)

(defn conductor-unschedule-repeating!
  "Cancel an armed schedule-tx! cutover -- musics.core/
   unschedule-repeating!."
  []
  (let [{:keys [tx-id tx-phase]} (:conductor @*state)
        id-text (str/trim (or tx-id ""))]
    (if (empty? id-text)
      (swap! *state assoc-in [:conductor :message] "Type a section id first.")
      (let [id (keyword id-text)
            phase (keyword (or tx-phase "enter"))]
        (m/unschedule-repeating! id phase)
        (swap! *state assoc-in [:conductor :message]
               (str "Unscheduled repeating " id " " (name phase) ".")))))
  nil)

;; ============================================================
;; Adviser popup -- musics.core/uh?, previously REPL-only. uh? only
;; ever PRINTS its suggestions, never returns them, so this captures
;; its own printed output via capture-out, same as the Editor panel
;; already does for parse/commit errors. No extra logging wiring
;; needed anywhere else: musics.core's own wrapper fns (parse/commit!/
;; play/build!/...) already log to the adviser automatically, so
;; whatever this shows reflects genuine recent GUI activity.
;; ============================================================

(defn close-adviser! [] (swap! *state assoc :adviser-open? false) nil)

(defn uh!
  "Open (or refresh, if already open) the Adviser popup with up to 3
   suggested next steps, based on this session's own recent activity."
  []
  (let [[_ printed] (capture-out #(m/uh?))]
    (swap! *state (fn [s] (-> s
                              (assoc :adviser-open? true)
                              (assoc-in [:adviser :text] (str/trim printed))))))
  nil)

;; ============================================================
;; Persistence popup -- musics.core's write/load (plain repo material)
;; and persist-session/restore-session (also round-trips a voice's
;; :algo-assignments -- see each fn's own docstring for what's NOT
;; captured: factory recipes built outside build!, conductor schedule
;; tables), previously REPL-only.
;; ============================================================

(defn open-persistence! [] (swap! *state assoc :persistence-open? true) nil)
(defn close-persistence! [] (swap! *state assoc :persistence-open? false) nil)

(defn set-persistence-write-path!
  [s]
  (swap! *state assoc-in [:persistence :write-path] s)
  nil)

(defn set-persistence-load-path!
  [s]
  (swap! *state assoc-in [:persistence :load-path] s)
  nil)

(defn- run-persistence-op!
  "Shared plumbing: run f (a zero-arg thunk performing the actual
   write/load/persist-session/restore-session call) in a background
   future -- each can involve a non-trivial whole-repo walk (see the
   'Background work off the FX thread' architecture note this whole
   GUI already follows for MIDI/record-midi) -- setting :busy? true
   meanwhile and reporting success/failure via :message when done.
   Exceptions inside a future are otherwise silently swallowed (never
   surfaced unless the future itself is deref'd), so this explicitly
   catches and reports one instead of letting a bad path or malformed
   file vanish with no feedback."
  [success-msg f]
  (swap! *state assoc-in [:persistence :busy?] true)
  (future
    (let [result (try (f) {:ok true} (catch Exception e {:error (ex-message e)}))]
      (swap! *state (fn [s]
                       (-> s
                           (assoc-in [:persistence :busy?] false)
                           (assoc-in [:persistence :message]
                                     (if (:error result)
                                       (str "Failed: " (:error result))
                                       success-msg)))))))
  nil)

(defn persistence-write!
  "Write the repo (latest committed tx) plus auto-ids to :write-path
   as EDN -- musics.core/write. Plain material only, no live algo
   assignments -- see persistence-persist-session! for that."
  []
  (let [path (str/trim (:write-path (:persistence @*state) ""))]
    (if (empty? path)
      (swap! *state assoc-in [:persistence :message] "Type a path first.")
      (run-persistence-op! (str "Wrote " path ".") #(m/write path))))
  nil)

(defn persistence-load!
  "Load a session from :load-path, REPLACING all committed history
   wholesale (a fresh baseline commit) -- musics.core/load. Points
   playback at the loaded material; does NOT restore any live algo
   assignments -- see persistence-restore-session! for that."
  []
  (let [path (str/trim (:load-path (:persistence @*state) ""))]
    (if (empty? path)
      (swap! *state assoc-in [:persistence :message] "Type a path first.")
      (run-persistence-op!
        (str "Loaded " path " (replaced all committed history).")
        #(m/load path))))
  nil)

(defn persistence-persist-session!
  "Like persistence-write!, but also captures currently-LIVE voice
   algorithm assignments -- musics.core/persist-session. Does NOT
   capture a wall algo's own factory/params recipe if it was built
   outside build! (a factory called directly), nor any core.conductor
   schedule table -- documented gaps, not this panel's own limitation."
  []
  (let [path (str/trim (:write-path (:persistence @*state) ""))]
    (if (empty? path)
      (swap! *state assoc-in [:persistence :message] "Type a path first.")
      (run-persistence-op! (str "Persisted session to " path ".") #(m/persist-session path))))
  nil)

(defn persistence-restore-session!
  "Like persistence-load!, but also replays a persist-session
   snapshot's own algo-assignments into the PREP table (assign-algo!)
   -- musics.core/restore-session. Never recreates a live voice
   itself; a later play/play-change at the same path picks the
   prepared algo back up automatically."
  []
  (let [path (str/trim (:load-path (:persistence @*state) ""))]
    (if (empty? path)
      (swap! *state assoc-in [:persistence :message] "Type a path first.")
      (run-persistence-op!
        (str "Restored session from " path " (replaced all committed history).")
        #(m/restore-session path))))
  nil)

;; ============================================================
;; Transform workbench -- musics.core's generative transforms (times/
;; transpose/invert/scale/reverse/shuffle/tonal-transpose/tonal-invert/
;; snap-to-scale/tonal-harmonize), previously REPL-only. One uniform
;; calling convention across a family of otherwise differently-shaped
;; fns -- a typed transform NAME plus a params EDN map, extracting
;; whichever keys that transform actually expects -- same reasoning
;; core.wall/build!'s own always-a-map params convention already
;; established for factories. repeat is deliberately NOT included: it
;; operates on an id+count+type directly, wrapping it in a lazy
;; Iterator, not on already-materialized sq seq data the way every
;; other transform here does -- it doesn't fit this panel's own
;; preview-then-commit shape, and belongs with the Play Builder's own
;; (repeat ...) grammar-level construct instead.
;; ============================================================

(def transform-names
  ["times" "transpose" "invert" "scale" "reverse" "shuffle"
   "tonal-transpose" "tonal-invert" "snap-to-scale" "tonal-harmonize"])

(defn open-transform! [] (swap! *state assoc :transform-open? true) nil)
(defn close-transform! [] (swap! *state assoc :transform-open? false) nil)

(defn set-transform-source-id!
  [s]
  (swap! *state assoc-in [:transform :source-id] s)
  nil)

(defn set-transform-name!
  [s]
  (swap! *state assoc-in [:transform :transform-name] s)
  nil)

(defn set-transform-params!
  [s]
  (swap! *state assoc-in [:transform :params] s)
  nil)

(defn set-transform-new-id!
  [s]
  (swap! *state assoc-in [:transform :new-id] s)
  nil)

(defn- apply-transform
  "name a string (one of transform-names), params an already-parsed EDN
   map, material an already-sq'd seq. :ks (tonal-* transforms only) is
   a plain key-spec string, e.g. \"D.major\" -- exactly !key:'s own
   surface syntax -- parsed here via common.music-elements/parse-key
   rather than asking a GUI user to somehow construct a real Key
   record by hand."
  [name params material]
  (case name
    "times"           (m/times (get params :n 2) material)
    "transpose"       (m/transpose (get params :semitones 0) material)
    "invert"          (if (contains? params :axis)
                         (m/invert (get params :axis) material)
                         (m/invert material))
    "scale"           (m/scale (get params :factor 1) material)
    "reverse"         (m/reverse material)
    "shuffle"         (m/shuffle material)
    "tonal-transpose" (m/tonal-transpose (el/parse-key (get params :ks)) (get params :steps 1) material)
    "tonal-invert"    (m/tonal-invert (el/parse-key (get params :ks)) (get params :axis 0) material)
    "snap-to-scale"   (m/snap-to-scale (el/parse-key (get params :ks)) material)
    "tonal-harmonize" (m/tonal-harmonize (el/parse-key (get params :ks)) (get params :steps 1) material)
    (throw (ex-info (str "Unknown transform: " (pr-str name) ". Try one of " transform-names) {}))))

(defn transform-preview!
  "Look up :source-id, apply the typed transform, and preview the
   result via musics.core/display (synchronous, no MIDI -- exactly
   what it's for). Stashes the actual computed material in
   :last-result for transform-commit! to reuse without recomputing."
  []
  (let [{:keys [source-id transform-name params]} (:transform @*state)
        id-text (str/trim (or source-id ""))
        name (str/trim (or transform-name ""))
        material (when (seq id-text) (m/sq (keyword id-text)))
        params-text (str/trim (or params ""))
        parsed (when (seq params-text)
                 (try (edn/read-string params-text) (catch Exception _ ::bad-edn)))
        params-map (if (nil? parsed) {} parsed)]
    (cond
      (empty? id-text)
      (swap! *state assoc-in [:transform :message] "Type a source id first.")

      (empty? name)
      (swap! *state assoc-in [:transform :message]
             (str "Type a transform name, one of: " (str/join ", " transform-names)))

      (nil? material)
      (swap! *state assoc-in [:transform :message] (str "Not found: " id-text))

      (or (= params-map ::bad-edn) (not (map? params-map)))
      (swap! *state assoc-in [:transform :message] "Params must be a valid EDN map, e.g. {:n 2}")

      :else
      (let [result (try {:ok (apply-transform name params-map material)}
                         (catch Exception e {:error (ex-message e)}))]
        (if (:error result)
          (swap! *state assoc-in [:transform :message] (str "Failed: " (:error result)))
          (let [transformed (:ok result)
                [_ printed] (capture-out #(m/display transformed))]
            (swap! *state
                   (fn [s]
                     (-> s
                         (assoc-in [:transform :preview-text] (str/trim printed))
                         (assoc-in [:transform :last-result] transformed)
                         (assoc-in [:transform :message]
                                   (str (count transformed) " item(s) -- ready to commit."))))))))))
  nil)

(defn transform-commit!
  "Commit :last-result (Preview's own stashed material -- see its own
   docstring on why this isn't recomputed) as a brand new :SEQ
   container under :new-id -- same {:type :SEQ ...} shape musics.core/
   transpose-part already commits its own result as, minus that fn's
   own key-transposition specifics (not generally applicable to every
   transform here)."
  []
  (let [{:keys [new-id last-result]} (:transform @*state)
        id-text (str/trim (or new-id ""))]
    (cond
      (nil? last-result)
      (swap! *state assoc-in [:transform :message] "Click Preview first.")

      (empty? id-text)
      (swap! *state assoc-in [:transform :message] "Type a new id first.")

      :else
      (let [id (keyword id-text)
            ctx (c/context)]
        (repo/commit-node! id {:type :SEQ :id id :context ctx :children (vec last-result)})
        (swap! *state assoc-in [:transform :message] (str "Committed " id ".")))))
  nil)
