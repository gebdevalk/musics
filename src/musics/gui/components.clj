(ns musics.gui.components
  "Reusable cljfx component-description functions -- the actual
  'component library' the app is built from. Every fn here takes a
  plain map and returns a plain cljfx description map (a
  fx/create-renderer :desc, not a mounted JavaFX object) -- none of
  them know about musics.repo/musics.domain.context, or about
  each other's callers. Wiring a component to a real action is always
  the caller's job, via an ordinary cljfx event-map (:on-* keys),
  exactly the way cljfx itself expects. This is what makes them
  'instantiatable' -- musics.gui.state decides *what* a slider controls
  (a Context envelope key, a transport action, ...), this ns only
  decides how it looks and how a raw JavaFX edit becomes one event
  map.

  Kept deliberately small: a handful of primitives (slider, button,
  toggle-button, text-field, label, titled-panel) rather than one
  component per panel -- musics.gui.core composes these into the actual
  transport bar / context-editor panels."
  (:require [cljfx.lifecycle :as lifecycle]
            [cljfx.component :as component]
            [musics.mu.inputs :as inputs]))

(def recreate-on-key-changed
  "A cljfx extension lifecycle -- {:fx/type recreate-on-key-changed
   :key k :desc child-desc} deletes and recreates child-desc's own
   JavaFX instance from scratch whenever k changes between renders,
   instead of the ordinary in-place :setter advance every other
   description gets. Ported locally from a newer cljfx release's
   cljfx.api/ext-recreate-on-key-changed (this project pins cljfx
   1.7.19, which doesn't have it yet -- same technique, copied
   verbatim from cljfx.lifecycle's own later implementation, just
   without its purely-cosmetic `annotate` print-method call).

   Needed for musics.gui.core/zoomable-slider: JavaFX's own Slider skin
   doesn't reliably redraw the thumb/track position when :min/:max
   change via plain property setters on an already-showing control --
   confirmed live (the zoomed range wasn't reflected on screen after
   clicking 'Z', in or out) even though cljfx itself was correctly
   calling .setMin/.setMax every time (verified in cljfx's own
   composite.clj: a :setter prop's replace! always fires when the
   value actually differs -- this is a JavaFX skin-layer quirk, not a
   cljfx wiring gap). Recreating the Slider instance outright sidesteps
   whatever stale layout the skin was caching, rather than trying to
   coax a redraw out of the existing one."
  (reify lifecycle/Lifecycle
    (create [_ {:keys [key desc]} opts]
      (with-meta
        {:key key :child (lifecycle/create lifecycle/dynamic desc opts)}
        {`component/instance #(-> % :child component/instance)}))
    (advance [this component {:keys [key desc] :as this-desc} opts]
      (if (= key (:key component))
        (update component :child #(lifecycle/advance lifecycle/dynamic % desc opts))
        (do (lifecycle/delete this component opts)
            (lifecycle/create this this-desc opts))))
    (delete [_ component opts]
      (lifecycle/delete lifecycle/dynamic (:child component) opts))))

(defn slider
  "A labeled slider + numeric readout, bracketed by its own current
   min/max bounds as text (NOT just the slider's native tick-label
   rendering, which stays off -- see show-tick-labels below -- since
   min/max here are the CURRENT, possibly-zoomed bounds a caller like
   musics.gui.core/zoomable-slider passes in, not necessarily the full
   spec range; seeing them as plain text is what makes the 'Z' button's
   circular zoom legible at all -- otherwise there'd be no way to read
   back what range you just zoomed into). value/min/max are plain
   doubles; fmt is a `format` control string for every readout
   (defaults to 2 decimals). on-change is a cljfx event-map merged
   with :fx/event -> the new double value, fired on every drag tick
   (JavaFX :on-value-changed), not just on release. show-label?
   (default true) omits every text label when false, leaving just the
   bare slider -- the 'L' toggle's own hook. scale :log moves the value
   in equal ratios (min must be > 0): the slider runs over log(value),
   its events carry ::log, and slider-value turns them back."
  [{:keys [label value min max fmt on-change show-label? scale]
    :or {fmt "%.2f" show-label? true}}]
  (let [log? (= :log scale)
        pos  (if log? #(Math/log (double %)) double)]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children
   (cond-> []
     show-label? (conj {:fx/type :label :min-width 90 :text (str label)}
                        {:fx/type :label :min-width 60 :text (format fmt (double value))}
                        {:fx/type :label :min-width 50 :text (format fmt (double min))})
     true (conj {:fx/type :slider
                 :min (pos min)
                 :max (pos max)
                 :value (pos value)
                 :pref-width 360
                 ;; Without an explicit min-width, an HBox under space
                 ;; pressure (e.g. the 'Z' zoom button sharing this
                 ;; row) shrinks the slider toward JavaFX's own small
                 ;; default min-width instead of clipping/wrapping --
                 ;; confirmed live: the slider's upper (max-value) end
                 ;; visually disappeared, with Z rendering where it
                 ;; used to be. Pinning min-width = pref-width makes
                 ;; the row's total width non-negotiable instead, so
                 ;; the window (see musics.gui.core's own widths) is what
                 ;; has to be wide enough, not the slider that shrinks.
                 :min-width 360
                 :show-tick-marks false
                 :show-tick-labels false
                 :on-value-changed (cond-> on-change log? (assoc ::log true))})
     show-label? (conj {:fx/type :label :min-width 50 :text (format fmt (double max))}))}))

(defn slider-value
  "The value a slider event carries -- turned back from log position
   when the slider has scale :log."
  [event]
  (let [x (:fx/event event)]
    (if (::log event) (Math/exp (double x)) x)))

(defn button
  "A plain push button. on-action is a cljfx event-map fired on click.
   style is an optional CSS string (the assist highlight, see
   musics.gui.core/action-button); tooltip an optional hover text."
  [{:keys [text on-action disabled? style tooltip]}]
  (cond-> {:fx/type :button
           :text text
           :disable (boolean disabled?)
           :on-action on-action
           :style (or style "")}
    tooltip (assoc :tooltip {:fx/type :tooltip :text tooltip})))

(defn toggle-button
  "A two-state button. selected? drives its current visual state;
   on-action fires on every click (the caller owns the actual state
   flip, same as a plain button). style is an optional CSS string,
   e.g. for a hot/cold arm toggle colored red/green."
  [{:keys [text selected? on-action style]}]
  {:fx/type :toggle-button
   :text text
   :selected (boolean selected?)
   :on-action on-action
   :style (or style "")})

(defn combo-box
  "A labeled dropdown picker. items is a seq of display strings (e.g.
   musics.gui.data's :items); value is the currently-selected string.
   on-change is a cljfx event-map merged with :fx/event -> the newly
   picked string, fired on JavaFX ComboBox's own :on-value-changed.
   show-label? (default true) omits the label when false, same as
   slider's own. editable? lets a value be typed as well as picked
   (on-change gets the typed text when it is committed)."
  [{:keys [label items value on-change show-label? editable?]
    :or {show-label? true}}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children
   (cond-> []
     show-label? (conj {:fx/type :label :min-width 90 :text (str label)})
     true (conj {:fx/type :combo-box
                 :items items
                 :value value
                 :editable (boolean editable?)
                 :pref-width 200
                 :on-value-changed on-change}))})

(defn text-field
  "A single-line text input. on-text-changed fires on every keystroke
   with :fx/event -> the new string; on-action fires on Enter, if given
   at all -- omitted from the description entirely when the caller
   doesn't pass one (every prior caller here always did, but a bare
   `nil` fails: cljfx's own event-handler coercer errors trying to
   coerce it, confirmed live -- 'Don't know how to coerce {:target
   javafx.event.EventHandler, :x nil}' -- rather than treating a nil
   handler as 'no handler', the way an absent key is)."
  [{:keys [text prompt on-text-changed on-action]}]
  (cond-> {:fx/type :text-field
           :text (or text "")
           :prompt-text (or prompt "")
           :on-text-changed on-text-changed}
    on-action (assoc :on-action on-action)))

(defn label
  [{:keys [text style]}]
  {:fx/type :label :text (str text) :style (or style "")})

(defn text-area
  "A multi-line text block -- record-midi's own generated-text-for-
   inspection-and-alteration panel, the one place a plain text-field's
   single seq isn't enough. on-text-changed fires on every keystroke,
   same contract as text-field's own -- and, same as text-field's own
   on-action, omitted from the description entirely when the caller
   doesn't pass one, rather than sent through as a literal nil (cljfx's
   event-handler coercer errors on that, same bug text-field already
   hit). editable? (default true) set false for a read-only display
   pane (e.g. the repo browser's structure/context readouts) -- those
   have no on-text-changed at all, since there's nothing to write back."
  [{:keys [text prompt on-text-changed pref-row-count editable?]
    :or {pref-row-count 10 editable? true}}]
  (cond-> {:fx/type :text-area
           :text (or text "")
           :prompt-text (or prompt "")
           :pref-row-count pref-row-count
           :wrap-text true
           :editable editable?}
    on-text-changed (assoc :on-text-changed on-text-changed)))

(defn titled-panel
  "A titled, bordered vertical group -- the container every param panel
   and the transport bar are built from. Optionally collapsible: pass
   BOTH collapsed? and on-toggle (a cljfx event-map, fired on click) to
   get a small ▾/▸ button next to the title that hides/shows children
   -- omit either (the default) and this behaves exactly as before, no
   toggle rendered, children always shown."
  [{:keys [title children collapsed? on-toggle]}]
  {:fx/type :v-box
   :spacing 4
   :style "-fx-border-color: gray; -fx-border-width: 1; -fx-padding: 6;"
   :children
   (into [{:fx/type :h-box
           :spacing 6
           :alignment :center-left
           :children
           (cond-> [{:fx/type :label :text (str title)
                     :style "-fx-font-weight: bold;"}]
             on-toggle (conj {:fx/type :button
                               :text (if collapsed? "▸" "▾")
                               :on-action on-toggle}))}]
         (when-not collapsed? children))})

(defn button-row
  [{:keys [children]}]
  {:fx/type :h-box :spacing 6 :alignment :center-left :children children})

(defn scroll-pane
  "A vertically-scrolling wrapper -- musics.common.context-keys/context-keys now
   drives param-specs with EVERY registered ranged key (see
   musics.gui.state), not a hand-picked handful, so a container's own
   param rows routinely run well past one screen's height; this is
   what keeps a window's own size sane regardless of how many rows
   that produces."
  [{:keys [content]}]
  {:fx/type :scroll-pane
   :fit-to-width true
   :content content})

(defn group-by-algo
  "rows grouped under the algo each belongs to, in the order the algos first
   appear: [[algo [row ...]] ...]. algo-of reads an algo from a row."
  [algo-of rows]
  (let [by (group-by algo-of rows)]
    (vec (for [a (distinct (map algo-of rows))] [a (vec (get by a))]))))

(defn zoom-bounds
  "The next visible range of a slider whose parameter is possible between the
   spec's :min and :max, zoomed to `current` ({:min :max}, nil: not zoomed),
   centred on value: each call halves the smaller distance to a bound, and
   wraps back out to the full range when it would get too narrow (an integer
   parameter: under 4 steps; otherwise under 1/64 of the range). Purely a
   display range, never the value."
  [{:keys [type min max]} current value]
  (let [full  {:min (double min) :max (double max)}
        cur   (or current full)
        floor (if (= :int type) 4.0 (/ (- (double max) (double min)) 64.0))]
    (if (or (nil? value) (< value (:min cur)) (> value (:max cur)))
      full
      (let [delta (/ (clojure.core/min (- (:max cur) value) (- value (:min cur))) 2.0)
            lo    (- value delta)
            hi    (+ value delta)
            [lo hi] (if (= :int type) [(Math/floor lo) (Math/ceil hi)] [lo hi])]
        (if (> (- hi lo) floor) {:min (double lo) :max (double hi)} full)))))

(defn zoom-out-bounds
  "The next, wider visible range of a slider: the zoom-out that undoes `z`. Always
   symmetrical around value, and always including zero where it can: with no range yet
   (a setting with no spec) the first step is value - |value| .. value + |value|, so 8 gives
   0..16, 0.5 gives 0..1, -0.5 gives -1..0, and 0 gives -1..1; every further step doubles the
   distance from the value, so 0..16 becomes -8..24. A parameter with a finite :min and :max
   widens the same way but never past them, and a slider already showing its whole range
   stays there. Nothing to centre on (no value): the whole range, if there is one. Purely a
   display range, never the value."
  [{:keys [type min max]} current value]
  (let [finite? (and (number? min) (number? max)
                     (Double/isFinite (double min)) (Double/isFinite (double max)))
        full    (when finite? {:min (double min) :max (double max)})
        v       (when (and (number? value) (Double/isFinite (double value))) (double value))]
    (cond
      (nil? v)   full
      (and full (nil? current)) full
      :else
      (let [half  (cond (nil? current) (let [a (Math/abs v)] (if (zero? a) 1.0 a))
                        :else (let [d (clojure.core/max (- v (:min current)) (- (:max current) v))]
                                (* 2.0 (if (zero? d) 1.0 d))))
            lo    (- v half)
            hi    (+ v half)
            [lo hi] (if (= :int type) [(Math/floor lo) (Math/ceil hi)] [lo hi])]
        (if full
          (let [lo (clojure.core/max lo (:min full)) hi (clojure.core/min hi (:max full))]
            (if (and (<= lo (:min full)) (>= hi (:max full))) full {:min lo :max hi}))
          {:min (double lo) :max (double hi)})))))

(defn param-control
  "One control for one voice input: a slider for a finite numeric range, a
   dropdown for :choices, a note for a function (set in the recipe),
   else a text field applied on Enter -- EDN, or plain text for a
   :string. An unset (##NaN) value is marked required. `ev` is merged
   into every event it sends (e.g. {:name nm}); param-input reads the
   value back out of one. A slider is paired with a z (in) and a Z (out) button
   when `zoom` is given, {:bounds {:min :max} or nil, :event event-map,
   :out-event event-map}: the visible range, zoomed, and the events they send (with :key); the slider is made
   afresh for every range, as JavaFX's Slider skin does not redraw a
   changed one."
  ([ev k v spec] (param-control ev k v spec nil))
  ([ev k v {:keys [type min max choices doc scale]} zoom]
  (let [lbl   (str (name k) (when (inputs/nan? v) "  (required)"))
        text  (fn [t] (button-row
                        {:children [(label {:text lbl})
                                    (text-field {:text t :prompt (or doc "EDN value")
                                                 :on-action (merge ev {:event/type :set-tree-param-text :key k
                                                                       :string? (= :string type)})})]}))]
    (cond
      (and (#{:int :double :ratio} type) (Double/isFinite (double min)) (Double/isFinite (double max)))
      (let [lo (get (:bounds zoom) :min (double min))
            hi (get (:bounds zoom) :max (double max))
            sl (slider {:label lbl :min lo :max hi :scale scale
                        :value (double (if (and (number? v) (not (inputs/nan? v))) v min))
                        :fmt (if (= :int type) "%.0f" "%.3f")
                        :on-change (merge ev {:event/type :set-tree-param :key k :type type})})]
        (if zoom
          (button-row
            {:children
             [{:fx/type recreate-on-key-changed :key [lo hi] :desc sl}
              (button {:text "z" :tooltip "zoom in" :on-action (merge ev (:event zoom) {:key k})})
              (button {:text "Z" :tooltip "zoom out" :on-action (merge ev (:out-event zoom) {:key k})})]})
          sl))

      choices
      (let [shown #(if (string? %) % (pr-str %))]
        (combo-box {:label lbl :items (mapv shown choices) :value (shown v)
                    :on-change (merge ev {:event/type :set-tree-param-text :key k
                                          :string? (= :string type)})}))

      (or (= :fn type) (some fn? (tree-seq coll? seq v)))
      (label {:text (str lbl ": " (if (inputs/nan? v) "a function" "set")
                         " -- set in the recipe")})

      (= :string type) (text (if (inputs/nan? v) "" v))
      :else            (text (if (inputs/nan? v) "" (pr-str v)))))))

(defn param-input
  "What a param-control event carries: a number from a slider (rounded
   for :int, a 1/64 ratio for :ratio), or text to read as EDN (a :string
   param's text comes already quoted)."
  [event]
  (let [x (slider-value event)]
    (if (= :set-tree-param (:event/type event))
      (case (:type event)
        :int   (Math/round (double x))
        :ratio (rationalize (/ (Math/round (* 64 (double x))) 64))
        x)
      (let [t (if (string? x) x (.getText ^javafx.scene.control.TextField (.getSource ^javafx.event.Event x)))]
        (if (:string? event) (pr-str t) t)))))
