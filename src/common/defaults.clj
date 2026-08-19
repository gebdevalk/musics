;; ranges.clj
;; Parameter range definitions — source of truth for numeric defaults.
;; Python source: src/common/data/defaults/ranges.py

(ns common.defaults
  (:require [common.music-elements :as el]))

(def ranges
  "Parameter keyword → {:min :default :max}."
  {:delay          {:min 0.0  :default 0.0   :max 2.0}
   :reverb         {:min 0.0  :default 0.0   :max 1.0}
   :width          {:min 0.0  :default 0.5   :max 1.0}
   :tempo          {:min 20   :default 92    :max 300}
   :articulation   {:min 0.2  :default 0.9   :max 2.0}
   :bend           {:min -2.0 :default 0.0   :max 2.0}
   :conformity     {:min 0.0  :default 0.0   :max 1.0}
   :density        {:min 1    :default 1     :max 16}
   :humanization   {:min 0.0  :default 0.0   :max 1.0}
   :instrument     {:min 0    :default 0     :max 127}
   :micro          {:min -0.5 :default 0.0   :max 0.5}
   :octave         {:min -4   :default 0     :max 4}
   :panning        {:min -1.0 :default 0.0   :max 1.0}
   :quant-strength {:min 0.0  :default 1.0   :max 1.0}
   :rate           {:min 0.1  :default 1.0   :max 10.0}
   :swing          {:min 0.0  :default 0.0   :max 1.0}
   :transposition  {:min -24  :default 0     :max 24}
   :dur-scale      {:min 0.1  :default 1.0   :max 4.0}
   :volume         {:min 0.0  :default 50.0  :max 100.0}
   :window         {:min 0    :default 0     :max 64}
   :pitch          {:min 0    :default 60    :max 127}
   :duration       {:min 0    :default 1/4   :max 4}
   :dynamic        {:min 0    :default 0     :max 10}
   :accent         {:min 0    :default 0     :max 10}
   :vibrato        {:min 0    :default 0     :max 127}
   :tie            {:min false :default false :max true}
   :channel        {:min 0    :default 0     :max 15}
   :key            {:min 0    :default 0     :max 11}
   :effects        {:min 0    :default 0     :max 10}})

(defn min-val [kw] (get-in ranges [kw :min]))
(defn max-val [kw] (get-in ranges [kw :max]))
(defn default [kw] (get-in ranges [kw :default]))

(defn clamp [kw val]
  (if-let [{:keys [min max]} (get ranges kw)]
    (max min (min max val)) val))


;; ============================================================
;; Context key defaults
;; ============================================================

;; 11. CONTEXT KEYS
;; Note: defaults are derived from common.defaults
;;       where a corresponding range entry exists.
;; ============================================================

(def ^:private context-keys-registry (atom {}))

(defn- reg!
  "Register a context key. If range-kw is provided, default and :range'
   are pulled from the ranges registry. Otherwise default must be explicit."
  [kw type description & {:keys [range-kw default' aliases category]}]
  (let [dflt  (if range-kw (default range-kw) default')
        range' (when range-kw [(min-val range-kw) (max-val range-kw)])
        ck    {:name (name kw)
               :type type
               :default dflt
               :description description
               :range range'
               :aliases aliases
               :category (or category :leaf)}]
    (swap! context-keys-registry assoc (name kw) ck)
    (doseq [a aliases] (swap! context-keys-registry assoc (name a) ck))
    kw))

;; World keys (uppercase) — range-kw pulls default from ranges
(reg! :Algorithm :str "Algorithm name for the performer"
      :default' "" :aliases [:A] :category :world)
(reg! :Chord :str "Chord symbol or harmonic context"
      :default' "" :aliases [:C] :category :world)
(reg! :Delay :float "Delay amount in seconds"
      :range-kw :delay :aliases [:D] :category :world)
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
      :range-kw :reverb :aliases [:R] :category :world)
(reg! :Scale :str "Scale/mode name"
      :default' "major" :aliases [:S] :category :world)
(reg! :Tempo :int "Beats per minute"
      :range-kw :tempo :aliases [:T :tempo] :category :world)
(reg! :Voice :str "Voice name or selection"
      :default' "" :aliases [:V] :category :world)
(reg! :Width :float "Stereo width 0.0-1.0"
      :range-kw :width :aliases [:W] :category :world)

;; Leaf keys (lowercase) — numeric defaults all from ranges
(reg! :accidentals :any "Bare-letter accidental mode: :implied (key-implied) or :explicit (literal, LilyPond-style)"
      :default' :implied :aliases [:acc])
(reg! :language :any "Pitch-name language for accidental suffix spelling (:nederlands/:english -- see common.music-data/accidental-tables)"
      :default' :nederlands :aliases [:lang])
(reg! :articulation :float "Note duration multiplier"
      :range-kw :articulation :aliases [:a])
(reg! :bend :float "Pitch bend depth in semitones"
      :range-kw :bend :aliases [:b])
(reg! :conformity :float "Rhythmic/algorithmic conformity"
      :range-kw :conformity :aliases [:c])
(reg! :density :int "Subdivisions per beat"
      :range-kw :density :aliases [:d])
(reg! :humanization :float "Micro-timing randomness"
      :range-kw :humanization :aliases [:h])
(reg! :instrument :int "MIDI program number"
      :range-kw :instrument :aliases [:i :timbre :program :prog])
(reg! :key :any "Resolved Key object"
      :default' (el/key :C :major) :aliases [:k])
(reg! :micro :float "Micro-timing offset in seconds"
      :range-kw :micro :aliases [:m])
(reg! :octave :int "Octave shift"
      :range-kw :octave :aliases [:o])
(reg! :panning :float "Stereo panning -1.0 .. +1.0"
      :range-kw :panning :aliases [:p :pan])
(reg! :quantStrength :float "Quantization strength"
      :range-kw :quant-strength :aliases [:q])
(reg! :rate :float "Envelope rate scaling"
      :range-kw :rate :aliases [:r])
(reg! :swing :float "Swing ratio"
      :range-kw :swing :aliases [:s])
(reg! :transposition :int "Semitone transposition"
      :range-kw :transposition :aliases [:t :transpose])
(reg! :durScale :float "Duration scaling multiplier"
      :range-kw :dur-scale :aliases [:u])
(reg! :volume :float "Volume 0-100 scale"
      :range-kw :volume :aliases [:v :vol])
(reg! :window :int "Algorithmic window size"
      :range-kw :window :aliases [:w])

;; ── Lookup API ──────────────────────────────────────────────

(defn context-key [kw]
  (let [ck (get @context-keys-registry (name kw))]
    (when (nil? ck) (throw (ex-info (str "Unknown context key: " kw) {:key kw})))
    ck))

(defn context-key-default [kw] (:default (context-key kw)))

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

(defn volume->midi [vol]
  (-> vol (* 1.27) double Math/round (max 0) (min 127) int))

;; ============================================================

(defn normalize [kw val]
  (if-let [{:keys [min max]} (get ranges kw)]
    (if (= min max) 0.0 (double (/ (- val min) (- max min)))) val))
