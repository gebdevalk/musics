;; music_elements.clj
;; Clojure port of pymusics common/elements/ — computational music types.
;;
;; Sections: Tempo, Meter, Pitch names, Key, Chords, Circle of Fifths
;; Requires common.data.music-data for keys, scales, time-signatures.

(ns common.elements.music-elements
  (:refer-clojure :exclude [key])
  (:require [common.data.music-data :as data]
            [clojure.string :as str]))

;; ============================================================
;; 1. TEMPO (tempo.py)
;; ============================================================

(defrecord Tempo [duration bpm])

(defn tempo
  "Create a Tempo. Duration can be an int (1/n) or a Ratio."
  [duration bpm]
  (->Tempo (if (integer? duration) (/ 1 duration) duration) bpm))

(defn ms-per-whole
  "Milliseconds per whole note at this tempo."
  [^Tempo t]
  (let [d (:duration t)]
    (quot (* (quot (* (.denominator d) 60) (.numerator d)) 1000) (:bpm t))))

(defn duration-ms
  "Duration in ms for the given note fraction."
  [^Tempo t dur]
  (if (zero? dur) 0
      (let [msw (ms-per-whole t)]
        (quot (* msw (.numerator dur)) (.denominator dur)))))

(defn duration-seconds [^Tempo t dur] (/ (duration-ms t dur) 1000.0))

(defn tempo* [^Tempo t factor] (->Tempo (:duration t) (int (* (:bpm t) factor))))
(defn tempo+ [^Tempo t delta] (->Tempo (:duration t) (+ (:bpm t) delta)))

(defn tempo- [^Tempo t delta] (->Tempo (:duration t) (- (:bpm t) delta)))

(defn tempo-diff
  "Difference in BPM between two Tempos. Returns a Tempo."
  [^Tempo t1 ^Tempo t2]
  (->Tempo (:duration t1) (- (:bpm t1) (:bpm t2))))

(defn tempo= [^Tempo t1 ^Tempo t2] (= (ms-per-whole t1) (ms-per-whole t2)))

(defn tempo->str [^Tempo t]
  (let [d (:duration t)]
    (if (= (.numerator d) 1)
      (str (.denominator d) "=" (:bpm t))
      (str (.numerator d) "/" (.denominator d) "=" (:bpm t)))))

(defn tempo->lilypond [^Tempo t] (str "\\tempo " (tempo->str t)))

(defn parse-tempo-str [s]
  (let [[frac-str bpm-str] (str/split s #"=")
        bpm  (Integer/parseInt bpm-str)
        dur  (if (str/includes? frac-str "/")
               (let [[n d] (map #(Integer/parseInt %) (str/split frac-str #"/"))] (/ n d))
               (/ 1 (Integer/parseInt frac-str)))]
    (->Tempo dur bpm)))

;; ============================================================
;; 2. METER (meter.py)
;; ============================================================

(defn meter-beats [{:keys [num den subdivisions]}]
  (if (and (nil? subdivisions) (#{8 16 32} den) (zero? (mod num 3)) (not= num 3))
    (quot num 3) num))

(defn meter-beat-unit [{:keys [den]}] den)
(defn duple? [m] (= 2 (meter-beats m)))
(defn triple? [m] (= 3 (meter-beats m)))
(defn quadruple? [m] (= 4 (meter-beats m)))

(defn simple? [{:keys [den num subdivisions]}]
  (and (nil? subdivisions) (not (and (#{8 16 32} den) (zero? (mod num 3)) (not= num 3)))))

(defn compound? [{:keys [den num subdivisions]}]
  (and (nil? subdivisions) (#{8 16 32} den) (zero? (mod num 3)) (not= num 3)))

(defn additive? [{:keys [subdivisions]}] (some? subdivisions))

(defn meter->str [{:keys [num den subdivisions]}]
  (if subdivisions
    (str num "/" den "(" (str/join "+" subdivisions) ")")
    (str num "/" den)))

(defn meter->lilypond [{:keys [num den]}] (str "\\time " num "/" den))

(defn make-meter ([n d] {:num n :den d}) ([n d s] {:num n :den d :subdivisions s}))

;; ============================================================
;; 3. PITCH NAMES (pitch_names.py)
;; ============================================================

(defn pitch->name
  ([pitch] (pitch->name pitch true))
  ([pitch prefer-sharps?]
   (let [octave (dec (quot pitch 12))
         pc     (mod pitch 12)
         names  (if prefer-sharps? data/note-names-sharp data/note-names-flat)]
     (str (nth names pc) octave))))

(defn name->pitch [name]
  (let [s      (str/lower-case (str/trim name))
        octave (Character/digit (last s) 10)
        pc-str (subs s 0 (dec (count s)))
        pc     (or (some #(when (= (second %) pc-str) (first %))
                         (map-indexed vector data/note-names-sharp))
                   (some #(when (= (second %) pc-str) (first %))
                         (map-indexed vector data/note-names-flat)))]
    (when (nil? octave) (throw (ex-info (str "Bad octave: " name) {})))
    (when (nil? pc)     (throw (ex-info (str "Unknown pitch: " name) {})))
    (+ (* (inc octave) 12) pc)))

;; ============================================================

;; 5. CHORDS (chords.py)
;; ================
;; Scale definitions
(def scale-steps
  {:major            {:steps [2 2 1 2 2 2 1]    :offset 0}
   :minor            {:steps [2 1 2 2 1 2 2]    :offset -3}
   :harmonic-minor   {:steps [2 1 2 2 1 3 1]    :offset -3}
   :melodic-minor    {:steps [2 1 2 2 2 2 1]    :offset -3}
   :ionian           {:steps [2 2 1 2 2 2 1]    :offset 0}
   :dorian           {:steps [2 1 2 2 2 1 2]    :offset 2}
   :phrygian         {:steps [1 2 2 2 1 2 2]    :offset 4}
   :lydian           {:steps [2 2 2 1 2 2 1]    :offset 5}
   :mixolydian       {:steps [2 2 1 2 2 1 2]    :offset 7}
   :aeolian          {:steps [2 1 2 2 1 2 2]    :offset -3}
   :locrian          {:steps [1 2 2 1 2 2 2]    :offset -1}
   :chromatic        {:steps [1 1 1 1 1 1 1 1 1 1 1 1] :offset 0}
   :pentatonic-major {:steps [2 2 3 2 3]        :offset 0}
   :pentatonic-minor {:steps [3 2 2 3 2]        :offset 0}
   :blues-major      {:steps [2 1 1 3 2]        :offset 0}
   :blues-minor      {:steps [3 2 1 1 3]        :offset 0}
   :whole-tone       {:steps [2 2 2 2 2 2]      :offset 0}
   :diminished-hw    {:steps [1 2 1 2 1 2 1 2]   :offset 0}
   :diminished-wh    {:steps [2 1 2 1 2 1 2 1]   :offset 0}
   :phrygian-dominant {:steps [1 3 1 2 1 2 2]    :offset 0}
   :hungarian-minor  {:steps [2 1 3 1 1 3 1]     :offset 0}
   :double-harmonic  {:steps [1 3 1 2 1 3 1]     :offset 0}
   :bebop-dominant   {:steps [2 2 1 2 2 1 1 1]   :offset 0}
   :bebop-major      {:steps [2 2 1 2 1 1 2 1]   :offset 0}})

;; Key record
(defrecord Key [signature scale pitches])

(defn key
  "Create a Key from key and scale keywords."
  [key-kw scale-kw]
  (let [k (get data/signatures key-kw)
        s (get scale-steps scale-kw)]
    (when (nil? k) (throw (ex-info (str "Unknown key: " key-kw) {})))
    (when (nil? s) (throw (ex-info (str "Unknown scale: " scale-kw) {})))
    (let [start   (mod (+ (:tonic-pc k) (:offset s)) 12)]
      (->Key k (assoc s :name scale-kw)
             (loop [ps [start] steps (:steps s) cur start]
               (if (empty? steps) (vec (butlast ps))
                   (let [nxt (+ cur (first steps))]
                     (recur (conj ps nxt) (rest steps) nxt))))))))

(defn key-pitches [^Key ks] (:pitches ks))

(defn key-absolute [^Key ks octave]
  (mapv #(+ % (* octave 12)) (key-pitches ks)))

(def ^:private tonic-by-display
  "Display name -> keyword. e.g. F# -> :F#, Bb -> :Bb."
  (into {} (map (fn [[k v]] [(:display v) k]) data/signatures)))

(defn parse-key
  "Parse F#.major, Bb.minor, C.dorian into a Key record."
  [s]
  (let [[tonic-str mode-str] (str/split s #"\." 2)
        tonic-kw (or (tonic-by-display tonic-str) (keyword tonic-str))
        mode-kw  (keyword mode-str)]
    (when (and tonic-kw mode-kw)
      (try (key tonic-kw mode-kw) (catch Exception _ nil)))))

(defn key->str [^Key ks]
  (str (:display (:signature ks)) "." (name (:name (:scale ks)))))
(defn key-pitch-names [ks]
  (let [sharp? (>= (:accidental (:signature ks)) 0)]
    (mapv #(pitch->name % sharp?) (key-pitches ks))))



;; ============================================

(def chords
  {:major           {:symbol ""    :intervals [0 4 7]       :aliases ["M" "maj" "Δ"]}
   :minor           {:symbol "m"   :intervals [0 3 7]       :aliases ["min" "-"]}
   :augmented       {:symbol "aug" :intervals [0 4 8]       :aliases ["+"]}
   :diminished      {:symbol "dim" :intervals [0 3 6]       :aliases ["°"]}
   :dominant-7      {:symbol "7"   :intervals [0 4 7 10]    :aliases ["dom7"]}
   :major-7         {:symbol "M7"  :intervals [0 4 7 11]    :aliases ["maj7" "Δ7"]}
   :minor-7         {:symbol "m7"  :intervals [0 3 7 10]    :aliases ["min7" "-7"]}
   :half-diminished {:symbol "m7b5":intervals [0 3 6 10]    :aliases ["ø" "m7-5"]}
   :diminished-7    {:symbol "dim7":intervals [0 3 6 9]     :aliases ["°7"]}
   :augmented-7     {:symbol "aug7":intervals [0 4 8 10]    :aliases ["+7" "7#5"]}
   :minor-major-7   {:symbol "mM7" :intervals [0 3 7 11]    :aliases ["mΔ7" "-Δ7"]}
   :major-6         {:symbol "6"   :intervals [0 4 7 9]     :aliases ["M6"]}
   :minor-6         {:symbol "m6"  :intervals [0 3 7 9]     :aliases ["min6"]}
   :dominant-9      {:symbol "9"   :intervals [0 4 7 10 14] :aliases ["dom9"]}
   :major-9         {:symbol "M9"  :intervals [0 4 7 11 14] :aliases ["maj9" "Δ9"]}
   :minor-9         {:symbol "m9"  :intervals [0 3 7 10 14] :aliases ["min9" "-9"]}
   :dominant-11     {:symbol "11"  :intervals [0 4 7 10 14 17]}
   :dominant-13     {:symbol "13"  :intervals [0 4 7 10 14 17 21]}
   :sus2            {:symbol "sus2":intervals [0 2 7]}
   :sus4            {:symbol "sus4":intervals [0 5 7]}
   :sus4-7          {:symbol "7sus4":intervals [0 5 7 10]   :aliases ["sus7"]}
   :dominant-7b5    {:symbol "7b5" :intervals [0 4 6 10]    :aliases ["7-5"]}
   :dominant-7b9    {:symbol "7b9" :intervals [0 4 7 10 13]}
   :dominant-7#9    {:symbol "7#9" :intervals [0 4 7 10 15]}
   :dominant-7#11   {:symbol "7#11":intervals [0 4 7 10 14 18]}
   :dominant-7b13   {:symbol "7b13":intervals [0 4 7 10 14 20]}
   :add9            {:symbol "add9":intervals [0 4 7 14]}
   :madd9           {:symbol "madd9":intervals [0 3 7 14]}
   :add11           {:symbol "add11":intervals [0 4 7 17]}
   :power           {:symbol "5"   :intervals [0 7]}})

(def ^:private symbol->chord
  (reduce-kv (fn [m k v]
               (let [m2 (assoc m (:symbol v) k)]
                 (reduce (fn [m3 alias] (assoc m3 alias k)) m2 (:aliases v))))
             {} chords))

(defn chord-pitches [chord-kw root-pc]
  (mapv #(mod (+ root-pc %) 12) (:intervals (get chords chord-kw))))

(def ^:private root-patterns
  ["C#" "F#" "G#" "D#" "A#" "E#" "B#"
   "Db" "Eb" "Gb" "Ab" "Bb"
   "C" "D" "E" "F" "G" "A" "B"])

(defn parse-chord-symbol [s]
  (let [s (str/trim s)]
    (when-let [root (some #(when (str/starts-with? s %) %) root-patterns)]
      (let [remaining (subs s (count root))
            chord-kw  (get symbol->chord remaining)]
        (when chord-kw [root chord-kw])))))

;; ============================================================
;; 6. CIRCLE OF FIFTHS (cycle_of_fifths.py)
;; ============================================================

(def ^:private cof-order [:Gb :Db :Ab :Eb :Bb :F :C :G :D :A :E :B :F#])
(def ^:private cof-index (into {} (map-indexed (fn [i k] [k i]) cof-order)))
(def ^:private steps->fifths [0 -5 2 -3 4 -1 6 1 -4 3 -2 5])

(defn modulate [key-kw delta] (nth cof-order (mod (+ (cof-index key-kw) delta) 13)))
(defn transpose-key [key-kw semitones] (modulate key-kw (steps->fifths (mod semitones 12))))
(defn fifths-up ([k] (modulate k 1)) ([k n] (modulate k n)))
(defn fifths-down ([k] (modulate k -1)) ([k n] (modulate k (- n))))
(defn cof-distance [from to] (mod (- (cof-index to) (cof-index from)) 13))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  (def t (tempo 4 120))
  (tempo->str t)             ;; "4=120"
  (duration-ms t 1/4)        ;; quarter note ms

  (pitch->name 60)           ;; "c4"
  (name->pitch "c4")         ;; 60

  (compound? (make-meter 6 8)) ;; true
  (meter->str (make-meter 7 8 [2 2 3])) ;; "7/8(2+2+3)"

  (def cm (key :C :major))
  (key-pitches cm)      ;; [0 2 4 5 7 9 11]
  (key-pitch-names cm)  ;; ["c" "d" "e" "f" "g" "a" "b"]

  (chord-pitches :major 0)   ;; [0 4 7]
  (parse-chord-symbol "Cm7") ;; ["C" :minor-7]

  (modulate :C 1)            ;; :G
  (transpose-key :C 2)       ;; :D
  (cof-distance :C :G)       ;; 1
  )