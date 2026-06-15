;; leaf_parser.clj
;; Clojure port of the pymusics leaf-level parser.
;; Pitch parsing, pitch→MIDI resolution, and articulation resolution.
;; No dependency on the lexer — all regex patterns are self-contained.

(ns input.reader.parser.leaf-parser
  (:require [clojure.string :as str]
            [common.data.music-data :as data]))

;; ============================================================
;; Pitch parsing helpers (ported from regex.py parse_pitch)
;; ============================================================

(def ^:private PITCH_PARSE_RE
  #"([A-G][1-8]|[a-g])?([b#n]{0,2})('*)")

(defn parse-pitch
  "Split a pitch string like 'C#4' or 'a#' into [name accidental octave]."
  [pitch-str]
  (when-let [m (re-matches PITCH_PARSE_RE pitch-str)]
    [(or (nth m 1) "") (nth m 2) (nth m 3)]))

(defn parse-pitches
  "Split chord content '<C E G>' into individual pitch tuples."
  [chord-content]
  (let [inner (str/replace chord-content #"^<|>$" "")]
    (keep parse-pitch (str/split inner #"\s+"))))

;; ============================================================
;; Pitch → MIDI resolution
;; ============================================================

(def ^:private diatonic-pcs
  "Map note letter (lowercase) → diatonic pitch class (C=0 through B=11)."
  {\c 0, \d 2, \e 4, \f 5, \g 7, \a 9, \b 11})

(defn- accidental-semitones
  "Convert accidental string to semitone offset."
  [s]
  (case s
    ""   0
    "#"  1  "##"  2
    "b"  -1 "bb" -2
    "n"  0  "nn"  0
    0))

(defn- abs->midi
  "Convert absolute pitch notation 'C4', 'F#5', 'Bb3' to MIDI note number.
   name-str includes the octave digit (e.g. 'C4')."
  [name-str accidental-str]
  (let [letter   (first name-str)
        octave   (Character/digit (char (second name-str)) 10)
        base-pc  (get diatonic-pcs (Character/toLowerCase ^Character letter))
        acc-off  (accidental-semitones accidental-str)]
    (+ base-pc acc-off (* (inc octave) 12))))

(defn- rel->midi
  "Compute MIDI pitch for a relative note (e.g. 'c', 'd#', 'f'')
   given the last absolute MIDI pitch.
   Interval-direction logic: ≤ fifth (7 semitones) goes up,
   > fifth goes down. Octave ticks force an upward octave shift."
  [last-midi name-str accidental-str octave-ticks]
  (let [letter      (first name-str)
        target-pc   (get diatonic-pcs (Character/toLowerCase ^Character letter))
        acc-off     (accidental-semitones accidental-str)
        target-full (+ target-pc acc-off)
        current-pc  (mod last-midi 12)
        current-oct (quot last-midi 12)
        up-oct      (if (>= target-full current-pc) current-oct (inc current-oct))
        down-oct    (if (<= target-full current-pc) current-oct (dec current-oct))
        up-pc       (+ (* up-oct 12) target-full)
        down-pc     (+ (* down-oct 12) target-full)
        up-dist     (- up-pc last-midi)]
    (if (seq octave-ticks)
      ;; Octave ticks: pick direction base, then shift up by tick count
      (+ (if (<= up-dist 7) up-pc down-pc)
         (* (count octave-ticks) 12))
      ;; No ticks: interval logic — prefer the closer direction
      (if (<= up-dist 7) up-pc down-pc))))

(defn resolve-pitch
  "Resolve a parsed pitch tuple [name accidental octave-ticks] to a MIDI
   note number. Absolute notation ('C4') resets the reference point.
   Returns [midi new-last-midi]."
  [[name accidental octave-ticks] last-midi]
  (let [upper? (Character/isUpperCase (char (first name)))]
    (if upper?
      (let [midi (abs->midi name accidental)]
        [midi midi])
      (let [base  (or last-midi 60)
            ticks (or octave-ticks "")
            midi  (rel->midi base name accidental ticks)]
        [midi midi]))))

(defn resolve-pitches-seq
  "Resolve a seq of [name accidental ticks] tuples sequentially.
   Each successive pitch is relative to the previous.
   Returns [midis-vec final-last-midi]."
  [tuples last-midi]
  (reduce (fn [[midis last] t]
            (let [[midi new-last] (resolve-pitch t last)]
              [(conj midis (or midi 60)) new-last]))
          [[] (or last-midi 60)]
          tuples))

;; ============================================================
;; Articulation resolution (ported from articulations.py Articulation.get)
;; ============================================================

(defn resolve-articulation
  "Resolve an articulation shorthand or name to a {:duration :dynamic} map.
   Accepts shorthand with or without dash (\"-^\" or \"^\") or full name
   (\"marcato\"), case-insensitive. Returns nil for nil input, the original
   string if unknown."
  [s]
  (when s
    (let [s-lower    (str/lower-case s)
          ;; try: shorthand with dash, then shorthand without dash
          shorthand   (or (get data/articulation-shorthand s-lower)
                          (get data/articulation-shorthand (str "-" s-lower)))
          art-from-shorthand (when shorthand (get data/articulations shorthand))
          ;; try: as name keyword directly
          art-from-name     (get data/articulations (keyword s-lower))]
      (or art-from-shorthand art-from-name s))))
