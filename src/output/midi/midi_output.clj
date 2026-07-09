;; midi_output.clj
;; Clojure port of pymusics output/midi/ — MIDI data, leaf rendering, timing.
;;
;; Sections:
;;   1. MidiNote records
;;   2. Leaf → MidiNote rendering (expressive parameter resolution)
;;   3. Musical time ↔ wall-clock integration
;;   4. Channel management
;;
;; Python sources: midi_data.py, leaf_to_midi.py, timing.py, midi_engine.py

(ns output.midi.midi-output
  (:require [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.elements.music-elements :as el]))

;; ============================================================
;; 1. MIDI NOTE RECORDS (midi_data.py)
;; ============================================================

(defrecord MidiNote [channel duration-notated duration-played
                     pitches velocity program tied cc-values])

(defrecord MidiDrumNote [program duration-notated duration-played velocity])

(defrecord MidiNoteOn [channel pitches velocity program cc-values])

(defrecord MidiNoteOff [channel pitches])

;; ============================================================
;; 2. LEAF → MIDI RENDERING (leaf_to_midi.py)
;; ============================================================

(def ^:private cc-panning 10)

(def root-ctx (c/context-root {"tempo" 120 "volume" 0.8 "timbre" 42}))

(defn ^:private resolve-expressive
  "Resolve velocity, program, panning, transposition, articulation from leaf + context.
   Returns [velocity program panning-cc transposition articulation]."
  [leaf time]
  (let [ctx (:context leaf)
        ;; Volume: read from context volume-atom (set by instructions)
        base   (/ (or (c/ctx-value-chain [ctx root-ctx] :volume time) 50.0) 100.0)  ;; 0-100 → 0-1
        dyn-art (or (:dynamic leaf) 0)
        dyn    (+ base (/ (double dyn-art) 100.0))
        vel    (-> dyn (* 127.0) ^[double] Math/round (long) (max 0) (min 127) int)
        ;; Program (timbre)
        prog   (or (:program leaf)
                   (c/ctx-value-chain [ctx root-ctx] :instrument time)
                   0)
        ;; Panning: [-1, 1] → [0, 127]
        pan    (or (:panning leaf)
                   (c/ctx-value-chain [ctx root-ctx] :panning time)
                   0.0)
        pan-cc (-> pan (+ 1.0) (* 63.5) ^[double] Math/round int)
        ;; Transposition
        trans  (or (c/ctx-value-chain [ctx root-ctx] :transposition time) 0)
        ;; Articulation
        art    (or (:articulation leaf)
                   (c/ctx-value-chain [ctx root-ctx] :articulation time)
                   1.0)]
    [vel prog pan-cc trans art]))

(defn render-leaf
  "Render a Leaf into a MidiNote.
   Requires a Tempo object from context to convert duration to seconds."
  [leaf time tempo channel]
  (let [[vel prog pan-cc trans art] (resolve-expressive leaf time)
        dur-notated (el/duration-seconds tempo (:duration leaf))
        dur-played  (* dur-notated (if (map? art) (:duration art 1.0) art))
        pitches     (mapv #(+ % trans) (:pitches leaf))]
    (->MidiNote channel dur-notated dur-played pitches vel prog
                (:tied leaf) {cc-panning pan-cc})))

(defn render-leaf-on
  "Render a Leaf into a MidiNoteOn (no duration — note-on only)."
  [leaf time channel]
  (let [[vel prog pan-cc trans _] (resolve-expressive leaf time)
        pitches (mapv #(+ % trans) (:pitches leaf))]
    (->MidiNoteOn channel pitches vel prog [[cc-panning pan-cc]])))

(defn render-leaf-off
  "Render a Leaf into a MidiNoteOff."
  [leaf time channel]
  (let [trans (or (c/ctx-value-chain [(:context leaf) root-ctx] :transposition time) 0)
        pitches (mapv #(+ % trans) (:pitches leaf))]
    (->MidiNoteOff channel pitches)))

(defn render-rest
  "Compute rest duration in seconds."
  [rest tempo]
  (el/duration-seconds tempo (:duration rest)))

(defn render-drum
  "Render a Drum into a MidiDrumNote."
  [drum time tempo]
  (let [ctx  (:context drum)
        base (/ (or (c/ctx-value-chain [ctx root-ctx] :volume time) 50.0) 100.0)
        art  (or (:dynamic drum) 0)
        dyn  (+ base (/ (double art) 100.0))
        vel  (-> dyn (* 127.0) ^[double] Math/round (long) (max 0) (min 127) int)
        art  (or (:articulation drum) (c/ctx-value-chain [ctx root-ctx] :articulation time) 1.0)
        dur-notated (el/duration-seconds tempo (:duration drum))
        dur-played  (* dur-notated (if (map? art) (:duration art 1.0) art))]
    (->MidiDrumNote (:program drum) dur-notated dur-played vel)))

;; ============================================================
;; 3. TIMING INTEGRATION (timing.py)
;; ============================================================

(def ^:private drum-channel 9)

(defn musical->seconds
  "Integrate tempo envelope over musical time [0, offset].
   Returns wall-clock seconds.
   step controls integration granularity (default 1/64 note)."
  [ctx offset & {:keys [step] :or {step 1/64}}]
  (loop [t 0 secs 0.0]
    (if (>= t offset)
      secs
      (let [remaining (- offset t)
            d         (if (> remaining step) step remaining)
            t-f       (double t)
            tempo-val (c/ctx-value-chain [ctx root-ctx] :Tempo t-f)
            tempo-obj (if (number? tempo-val)
                        (el/tempo 4 (int tempo-val))
                        (or tempo-val (el/tempo 4 120)))]
        (recur (+ t d) (+ secs (el/duration-seconds tempo-obj d)))))))

(defn compute-onset
  "Real-time onset = engine start + integrated tempo + microtiming offset."
  [start-time ctx offset micro-on]
  (+ start-time (musical->seconds ctx offset) micro-on))

(defn compute-noteoff
  "Real-time note-off = onset + played duration + microtiming offset."
  [onset-time duration-played micro-off]
  (+ onset-time duration-played micro-off))

(defn compute-micro
  "Per-note microtiming: base micro offset + random humanization jitter."
  [ctx time]
  (let [micro (or (c/ctx-value-chain [ctx root-ctx] :micro time) 0.0)
        human (or (c/ctx-value-chain [ctx root-ctx] :humanization time) 0.0)]
    (+ micro (if (pos? human)
               (* (- (rand) 0.5) human) ;; jitter in seconds
               0.0))))

;; ============================================================
;; 4. CHANNEL MANAGEMENT (midi_engine.py)
;; ============================================================

(defn make-channel
  "Create a channel state: {:number int, :offset Ratio, :program int-or-nil, :notes #{pitches}}."
  [number]
  {:number number :offset 0 :program nil :notes #{}})

(defn channel-note-on [channel pitches]
  (update channel :notes conj pitches))

(defn channel-sounding? [channel pitches]
  (contains? (:notes channel) pitches))

(defn channel-note-off [channel pitches]
  (update channel :notes disj pitches))

(defn build-channel-pool
  "Build initial pool of channels (all except drum channel 9)."
  []
  (vec (for [n (range 15 -1 -1) :when (not= n drum-channel)]
         (make-channel n))))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; Create a leaf and render
  (def ctx (c/context-root {"tempo" 120 "volume" 50.0 "instrument" 0}))
  (def leaf (d/leaf "c4" ctx 1/4 [60]))
  (def tempo (el/tempo 4 120))

  (render-leaf leaf 0.0 tempo 0)
  ;; => MidiNote{:channel 0, :pitches [60], :velocity 64, ...}

  ;; Timing
  (musical->seconds ctx 1/4)   ;; quarter note at 120 BPM → 0.5s
  (musical->seconds ctx 1)     ;; whole note → 2.0s

  ;; Micro timing
  (compute-micro ctx 0.0)      ;; some small random jitter
  )