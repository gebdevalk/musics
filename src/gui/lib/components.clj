(ns gui.lib.components
  "Reusable cljfx component-description functions -- the actual
  'component library' the app is built from. Every fn here takes a
  plain map and returns a plain cljfx description map (a
  fx/create-renderer :desc, not a mounted JavaFX object) -- none of
  them know about musics.clj/core.repo/core.domain.context, or about
  each other's callers. Wiring a component to a real action is always
  the caller's job, via an ordinary cljfx event-map (:on-* keys),
  exactly the way cljfx itself expects. This is what makes them
  'instantiatable' -- gui.lib.state decides *what* a slider controls
  (a Context envelope key, a transport action, ...), this ns only
  decides how it looks and how a raw JavaFX edit becomes one event
  map.

  Kept deliberately small: a handful of primitives (slider, button,
  toggle-button, text-field, label, titled-panel) rather than one
  component per panel -- gui.lib.core composes these into the actual
  transport bar / context-editor panels."
  (:require [clojure.string :as str]))

(defn slider
  "A labeled slider + numeric readout. value/min/max are plain doubles;
   fmt is a `format` control string for the readout (defaults to 2
   decimals). on-change is a cljfx event-map merged with
   :fx/event -> the new double value, fired on every drag tick (JavaFX
   :on-value-changed), not just on release. show-label? (default true)
   omits both text labels when false, leaving just the bare slider --
   the 'L' toggle's own hook."
  [{:keys [label value min max fmt on-change show-label?]
    :or {fmt "%.2f" show-label? true}}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children
   (cond-> []
     show-label? (conj {:fx/type :label :min-width 90 :text (str label)}
                        {:fx/type :label :min-width 60 :text (format fmt (double value))})
     true (conj {:fx/type :slider
                 :min min
                 :max max
                 :value value
                 :pref-width 360
                 :show-tick-marks false
                 :show-tick-labels false
                 :on-value-changed on-change}))})

(defn button
  "A plain push button. on-action is a cljfx event-map fired on click."
  [{:keys [text on-action disabled?]}]
  {:fx/type :button
   :text text
   :disable (boolean disabled?)
   :on-action on-action})

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
   gui.lib.data's :items); value is the currently-selected string.
   on-change is a cljfx event-map merged with :fx/event -> the newly
   picked string, fired on JavaFX ComboBox's own :on-value-changed.
   show-label? (default true) omits the label when false, same as
   slider's own."
  [{:keys [label items value on-change show-label?]
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
                 :on-value-changed on-change}))})

(defn text-field
  "A single-line text input. on-text-changed fires on every keystroke
   with :fx/event -> the new string; on-action fires on Enter."
  [{:keys [text prompt on-text-changed on-action]}]
  {:fx/type :text-field
   :text (or text "")
   :prompt-text (or prompt "")
   :on-text-changed on-text-changed
   :on-action on-action})

(defn label
  [{:keys [text style]}]
  {:fx/type :label :text (str text) :style (or style "")})

(defn titled-panel
  "A titled, bordered vertical group -- the container every param panel
   and the transport bar are built from."
  [{:keys [title children]}]
  {:fx/type :v-box
   :spacing 4
   :style "-fx-border-color: gray; -fx-border-width: 1; -fx-padding: 6;"
   :children
   (into [{:fx/type :label :text (str title)
           :style "-fx-font-weight: bold;"}]
         children)})

(defn button-row
  [{:keys [children]}]
  {:fx/type :h-box :spacing 6 :alignment :center-left :children children})
