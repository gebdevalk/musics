;; context_keys.clj
;; The context-key registry: every !key: a musics text can set, with its
;; aliases, type, default and range. Numeric ranges and defaults come
;; from common.music-data/quantities -- the one source of truth -- never
;; from a table of their own.

(ns common.context-keys
  (:require [common.music-data :as data]
            [common.music-elements :as el]))

;; ============================================================
;; Context key defaults
;; ============================================================

;; 11. CONTEXT KEYS
;; Note: defaults are derived from common.context-keys
;;       where a corresponding range entry exists.
;; ============================================================

(def ^:private context-keys-registry (atom {}))

(defn- reg!
  "Register a context key. With :quantity, its default, range and scale
   come from common.music-data/quantities; otherwise :default' is given."
  [kw type description & {:keys [quantity default' aliases category]}]
  (let [q  (when quantity (data/quantity quantity))
        ck {:name (name kw)
            :type type
            :default (if q (:default q) default')
            :description description
            :range (when q [(:min q) (:max q)])
            :scale (:scale q)
            :aliases aliases
            :category (or category :leaf)}]
    (swap! context-keys-registry assoc (name kw) ck)
    (doseq [a aliases] (swap! context-keys-registry assoc (name a) ck))
    kw))

;; World keys (uppercase)
(reg! :Algorithm :str "Algorithm name for the performer"
      :default' "" :aliases [:A] :category :world)
(reg! :Chord :str "Chord symbol or harmonic context"
      :default' "" :aliases [:C] :category :world)
(reg! :Delay :float "Delay amount in seconds"
      :quantity :delay :aliases [:D] :category :world)
(reg! :Form :str "Form/section markers"
      :default' "" :aliases [:F] :category :world)
(reg! :Key :str "Tonic key name"
      :default' "C" :aliases [:K] :category :world)
(reg! :Meter :meter "Time signature"
      :default' (el/parse-meter-str "4/4") :aliases [:M] :category :world)
(reg! :Orchestration :str "Orchestration preset name"
      :default' "" :aliases [:O] :category :world)
(reg! :QuantMode :str "Quantization mode"
      :default' "grid" :aliases [:Q] :category :world)
(reg! :Reverb :float "Reverb amount 0.0-1.0"
      :quantity :reverb :aliases [:R] :category :world)
(reg! :Scale :str "Scale/mode name"
      :default' "major" :aliases [:S] :category :world)
(reg! :Tempo :int "Beats per minute"
      :quantity :tempo :aliases [:T :tempo] :category :world)
(reg! :Voice :str "Voice name or selection"
      :default' "" :aliases [:V] :category :world)
(reg! :Width :float "Stereo width 0.0-1.0"
      :quantity :width :aliases [:W] :category :world)

;; Leaf keys (lowercase)
(reg! :accidentals :any "Bare-letter accidental mode: :implied (key-implied) or :explicit (literal, LilyPond-style)"
      :default' :implied :aliases [:acc])
(reg! :articulation :float "Note duration multiplier"
      :quantity :articulation :aliases [:a])
(reg! :bend :float "Pitch bend depth in semitones"
      :quantity :bend :aliases [:b])
(reg! :conformity :float "Rhythmic/algorithmic conformity"
      :quantity :conformity :aliases [:c])
(reg! :density :int "Subdivisions per beat"
      :quantity :density :aliases [:d])
(reg! :humanization :float "Micro-timing randomness"
      :quantity :humanization :aliases [:h])
(reg! :instrument :int "MIDI program number"
      :quantity :instrument :aliases [:i :timbre :program :prog])
(reg! :key :any "Resolved Key object"
      :default' (el/key :C :major) :aliases [:k])
(reg! :micro :float "Micro-timing offset in seconds"
      :quantity :micro :aliases [:m])
(reg! :octave :int "Octave shift"
      :quantity :octave :aliases [:o])
(reg! :panning :float "Stereo panning -1.0 .. +1.0"
      :quantity :panning :aliases [:p :pan])
(reg! :quantStrength :float "Quantization strength"
      :quantity :quant-strength :aliases [:q])
(reg! :rate :float "Envelope rate scaling"
      :quantity :ratio :aliases [:r])
(reg! :swing :float "Swing ratio"
      :quantity :swing :aliases [:s])
(reg! :transposition :int "Semitone transposition"
      :quantity :semitones :aliases [:t :transpose])
(reg! :durScale :float "Duration scaling multiplier"
      :quantity :ratio :aliases [:u])
(reg! :volume :float "Volume 0-100 scale"
      :quantity :volume :aliases [:v :vol])
(reg! :window :int "Algorithmic window size"
      :quantity :window :aliases [:w])

;; ── Lookup API ──────────────────────────────────────────────

(defn context-key [kw]
  (let [ck (get @context-keys-registry (name kw))]
    (when (nil? ck) (throw (ex-info (str "Unknown context key: " kw) {:key kw})))
    ck))

(defn canonical-key
  "Resolve kw through the alias registry to its canonical keyword (e.g.
   :timbre/:program/:prog/:i -> :instrument). Unregistered keys (custom,
   algorithm-specific context values) pass through unchanged."
  [kw]
  (if-let [ck (get @context-keys-registry (name kw))]
    (keyword (:name ck))
    kw))

(defn root-defaults []
  (into {} (for [[name ck] @context-keys-registry :when (= name (:name ck))]
             [name (:default ck)])))

(defn context-keys
  "Every registered context key's own full config ({:name :type
   :default :description :range :aliases :category}), keyed by its
   CANONICAL keyword -- one entry per key, aliases collapsed out
   (context-keys-registry itself stores the identical config map under
   every alias too, e.g. :volume and :v/:vol all point at the same
   map; iterating the raw registry directly would repeat each entry
   once per alias). This is THE single source of truth for every
   registered key's own numeric range -- any other ns wanting to show
   or edit a context key's bounds (gui.lib.state's param-specs, for
   one) should read through this rather than hand-copying its own
   parallel {:min :max} table that could silently drift from it, the
   way an earlier GUI slider spec once did (hardcoded 0-128 for
   :volume against this registry's real 0-100)."
  []
  (->> @context-keys-registry
       vals
       distinct
       (map (fn [ck] [(keyword (:name ck)) ck]))
       (into {})))

(defn volume->midi [vol]
  (-> vol (* 1.27) double Math/round (max 0) (min 127) int))
