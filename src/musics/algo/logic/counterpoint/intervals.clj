(ns musics.algo.logic.counterpoint.intervals
  "Notes, intervals and modes for species counterpoint (see
   doc/counterpoint.md).

   A note is {:m midi :d dpos}: dpos is its diatonic position, 7 per
   octave (c = 0 in octave 0), so an interval knows both its size in
   steps and in semitones -- [4 7] is a perfect fifth, [4 6] a
   diminished fifth, [3 6] an augmented fourth. Notes altered by musica
   ficta keep their letter (C# is a C) and carry :ficta, saying where
   the alteration may stand."
  (:require [musics.common.music-elements :as el]
            [musics.common.music-data :as data]))

;; ---------------------------------------------------------------------------
;; Modes
;; ---------------------------------------------------------------------------

(def modes
  "The church modes, in the order a mode is guessed from a cantus."
  [:dorian :phrygian :lydian :mixolydian :aeolian :ionian])

(def ^:private aliases {:major :ionian :minor :aeolian})

(def ^:private pc->tonic
  {0 :C 1 :Db 2 :D 3 :Eb 4 :E 5 :F 6 :F# 7 :G 8 :Ab 9 :A 10 :Bb 11 :B})

(defn mode-key
  "The Key of `mode` (a church mode, :major or :minor) on the pitch
   class of `final` (a MIDI note)."
  [final mode]
  (el/key (pc->tonic (mod final 12)) (get aliases mode mode)))

(defn- in-key? [ks m]
  (contains? (set (map #(mod % 12) (el/key-pitches ks))) (mod m 12)))

(defn infer-mode
  "The first church mode on the cantus' final that holds every note of
   it, or nil."
  [cantus]
  (let [final (peek (vec cantus))]
    (first (filter #(every? (partial in-key? (mode-key final %)) cantus) modes))))

;; ---------------------------------------------------------------------------
;; Notes
;; ---------------------------------------------------------------------------

(defn- letter-octave
  "The octave a note of `letter` with accidental `acc` sounding at m
   is written in (C4 = 60, so B#3 = 60 too)."
  [m letter acc]
  (dec (quot (- m (data/diatonic-pcs letter) acc) 12)))

(defn note
  "The note m as spelled in ks (m must be one of its degrees)."
  [ks m]
  (let [step   (el/key-step ks m)
        letter (nth data/letter-order step)
        acc    (el/key-letter-offset ks letter)]
    {:m m :d (+ (* 7 (letter-octave m letter acc)) step)}))

(defn- degree-pc
  "The pitch class of ks's degree n (0 = the final)."
  [ks n]
  (mod (nth (el/key-pitches ks) n) 12))

(defn ficta-rules
  "[degree semitones where] for each alteration mode allows on the key
   ks: the raised leading tone in a cadence (dorian, mixolydian,
   aeolian, minor), the lowered sixth of dorian and fourth of lydian
   against the tritone (anywhere), and a major third in the final
   chord where the mode's third is minor."
  [ks mode]
  (let [mode (get aliases mode mode)
        tonic (degree-pc ks 0)
        minor-third? (= 3 (mod (- (degree-pc ks 2) tonic) 12))
        whole-step-below? (= 10 (mod (- (degree-pc ks 6) tonic) 12))]
    (cond-> []
      (and whole-step-below? (not= mode :phrygian)) (conj [6 1 :cadence])
      (= mode :dorian)                              (conj [5 -1 :free])
      (= mode :lydian)                              (conj [3 -1 :free])
      (and minor-third? (not= mode :phrygian))      (conj [2 1 :final]))))

(defn candidates
  "Every note a voice may sing in mode on `final`, within [lo hi]:
   the mode's degrees, plus its ficta (marked :ficta :cadence, :free or
   :final), sorted by pitch."
  [final mode [lo hi]]
  (let [ks       (mode-key final mode)
        diatonic (for [m (range (- lo 2) (+ hi 3)) :when (in-key? ks m)] (note ks m))
        altered  (for [[deg semis where] (ficta-rules ks mode)
                       n diatonic
                       :when (= (degree-pc ks deg) (mod (:m n) 12))]
                   (assoc n :m (+ (:m n) semis) :ficta where))]
    (->> (concat diatonic altered)
         (filter #(<= lo (:m %) hi))
         (sort-by (juxt :m :d))
         vec)))

;; ---------------------------------------------------------------------------
;; Intervals
;; ---------------------------------------------------------------------------

(def ^:private qualities
  {[0 0] :P1 [1 1] :m2 [1 2] :M2 [2 3] :m3 [2 4] :M3 [3 5] :P4 [3 6] :A4
   [4 6] :d5 [4 7] :P5 [5 8] :m6 [5 9] :M6 [6 10] :m7 [6 11] :M7})

(defn interval
  "The interval from a to b: {:steps :semis} (signed, b - a), its
   :quality (:P1 :m2 ... :M7, or :other for augmented and diminished
   ones beyond A4/d5), reduced to within an octave, and :octaves, how
   many octaves it spans beyond that (a P1 with :octaves 1 is an
   octave)."
  [a b]
  (let [dd (- (:d b) (:d a))
        ds (- (:m b) (:m a))
        q  (quot (abs dd) 7)
        sd (- (abs dd) (* 7 q))
        ss (- (abs ds) (* 12 q))]
    {:steps dd :semis ds :octaves q :quality (get qualities [sd ss] :other)}))

(defn quality [a b] (:quality (interval a b)))

(def perfect   #{:P1 :P5})
(def imperfect #{:m3 :M3 :m6 :M6})

(defn perfect?    [a b] (contains? perfect (quality a b)))
(defn imperfect?  [a b] (contains? imperfect (quality a b)))
(defn consonant?
  "Consonant over the bass: perfect or imperfect (the fourth is not)."
  [a b]
  (or (perfect? a b) (imperfect? a b)))
(defn upper-consonant?
  "Consonant between two upper voices: the perfect fourth counts too."
  [a b]
  (or (consonant? a b) (= :P4 (quality a b))))

(defn steps [a b] (- (:d b) (:d a)))
(defn semis [a b] (- (:m b) (:m a)))
(defn step? [a b] (= 1 (abs (steps a b))))
(defn leap? [a b] (> (abs (steps a b)) 1))
(defn same? [a b] (= (:m a) (:m b)))

(defn melodic?
  "An interval a voice may sing: a second, third, perfect fourth or
   fifth, octave, the minor sixth upwards, or a repeated note."
  [a b]
  (let [{:keys [quality octaves semis]} (interval a b)]
    (or (and (zero? octaves) (contains? #{:P1 :m2 :M2 :m3 :M3 :P4 :P5} quality))
        (and (= 1 octaves) (= :P1 quality))
        (and (zero? octaves) (= :m6 quality) (pos? semis)))))
