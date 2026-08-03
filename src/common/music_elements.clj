;; music_elements.clj
;; Clojure port of pymusics common/elements/ — computational music types.
;;
;; Sections: Tempo, Meter, Pitch names, Key, Chords, Circle of Fifths
;; Requires common.music-data for keys, scales, time-signatures.

(ns common.music-elements
  (:refer-clojure :exclude [key])
  (:require [common.music-data :as data]
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

(defn tempo->quarter-bpm
  "Convert a Tempo's beat-duration + BPM to the equivalent quarter-note
   BPM -- what the engine's tempo sampling actually expects, regardless of
   which note value the author wrote the marking against (e.g. `!tempo:8=120`,
   eighth=120, is the same speed as quarter=60)."
  [^Tempo t]
  (* (:bpm t) (:duration t) 4))

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

;; num/den is the printed time signature; subdivisions, when given, is an
;; explicit additive beat grouping (e.g. 7/8(2+2+3) -> [2 2 3]) overriding
;; the conventional default derivable from num/den alone (see
;; default-subdivisions) -- nil means "no override, use the default."
(defrecord Meter [num den subdivisions])

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

(defn make-meter
  "Create a Meter. subdivisions, when given, is an explicit additive
   grouping (see Meter's docstring above)."
  ([n d] (->Meter n d nil))
  ([n d s] (->Meter n d s)))

(defn- prime-factors-ascending
  "n's prime factors, smallest first, with multiplicity (e.g. 12 -> [2 2 3],
   5 -> [5], 1 -> [])."
  [n]
  (loop [n n d 2 factors []]
    (cond
      (<= n 1)           factors
      (zero? (mod n d))  (recur (quot n d) d (conj factors d))
      :else              (recur n (inc d) factors))))

(defn default-subdivisions
  "The conventional default beat-grouping for a meter with no explicit
   subdivisions, given directly as num/den (not a Meter -- this computes
   what subdivisions *would* default to, it doesn't read an existing
   Meter's own field). Compound meters (num/3 main beats, each further
   dividing into 3) get their main-beat count prime-factored ascending
   with a final 3 appended; simple meters just get num prime-factored
   ascending directly -- e.g. 4/4 -> [2 2], 3/4 -> [3], 6/8 -> [2 3],
   12/8 -> [2 2 3], 5/4 -> [5], 15/8 -> [5 3].
   Deliberately does NOT try to guess a grouping for irregular meters like
   5/8 or 7/8 beyond their flat prime beat count -- real practice groups
   those in genuinely convention/piece-dependent ways (2+3 vs 3+2 vs
   2+2+3...), so the default stays the unbiased flat cycle and an explicit
   override (e.g. \"7/8(2+2+3)\") is how the composer picks a specific
   feel, rather than the system guessing one."
  [num den]
  (if (compound? {:num num :den den})
    (conj (vec (prime-factors-ascending (quot num 3))) 3)
    (vec (prime-factors-ascending num))))

(defn parse-meter-str
  "Parse \"N/D\" or \"N/D(a+b+c)\" (the format meter->str prints) into a
   Meter. Throws if the additive groups (when given) don't sum to N."
  [s]
  (let [[_ num-str den-str _ groups-str]
        (re-matches #"(\d+)/(\d+)(\((\d+(?:\+\d+)*)\))?" s)]
    (when (nil? num-str)
      (throw (ex-info (str "Bad meter string: " s) {:input s})))
    (let [num          (Integer/parseInt num-str)
          den          (Integer/parseInt den-str)
          subdivisions (when groups-str
                         (mapv #(Integer/parseInt %) (str/split groups-str #"\+")))]
      (when (and subdivisions (not= num (reduce + subdivisions)))
        (throw (ex-info (str "Meter subdivisions " subdivisions
                             " don't sum to numerator " num) {:input s})))
      (->Meter num den subdivisions))))

;; ============================================================
;; 2a. INDISPENSABILITY (Clarence Barlow)
;; ============================================================

;; Indispensability for a single-level cycle of q pulses (0-indexed,
;; downbeat = q-1). q=2/q=3 are simple rotations; q=5/q=7 are Barlow's
;; real, non-trivial anacrusis-breaking pattern -- verified against a
;; known-correct reference, not derivable from the q=2/q=3 case by
;; extrapolation. Only these four are supported: real meters always
;; decompose additively into them (see default-subdivisions), so a
;; genuine bare prime cycle beyond 7 never actually arises.
(def ^:private indispensability-base-tables
  {2 [1 0]
   3 [2 0 1]
   5 [4 0 1 3 2]
   7 [6 0 1 3 5 2 4]})

(defn- indispensability-digit-fn
  "The base table for q, rotated left by one position so it aligns with
   the internal d = (n-1 mod Q) convention indispensability-at uses below.
   For q=2/3 this happens to reduce to the identity permutation (their
   base tables are pure rotations, (n-1) mod q); for q=5/7 it doesn't --
   that difference is exactly the non-trivial part of Barlow's theory."
  [q]
  (if-let [t (get indispensability-base-tables q)]
    (vec (concat (rest t) [(first t)]))
    (throw (ex-info (str "No indispensability base table for factor " q
                         " -- only 2, 3, 5, and 7 are supported.")
                    {:factor q}))))

(defn- pi-product
  "Product of subdivisions[start..stop), 1 if the range is empty."
  [subdivisions start stop]
  (reduce * 1 (subvec (vec subdivisions) start stop)))

(defn- indispensability-at
  "Indispensability of pulse n (any integer, reduced mod Q) in a cycle
   built from subdivisions (an ordered factor sequence, e.g. [2 2 3]),
   Q = product of subdivisions. Recombines each level's own base-table
   rank (via indispensability-digit-fn) using the same place-value
   structure as the pulse index itself, so the result is guaranteed a
   permutation of 0..Q-1 with the downbeat (n=0) always mapping to Q-1."
  [n Q subdivisions]
  (let [n (rem n Q)
        q (count subdivisions)
        d (mod (+ (dec n) Q) Q)]
    (loop [i 0 r 0]
      (if (< i q)
        (let [i'     (- q i 1)
              a      (pi-product subdivisions 0 i')
              b      (pi-product subdivisions (- q i) q)
              c      (nth subdivisions i')
              digit  (mod (quot d b) c)
              digit' (nth (indispensability-digit-fn c) digit)]
          (recur (inc i) (+ r (* a digit'))))
        r))))

(defn indispensability
  "Barlow indispensability for a meter whose beats decompose into
   subdivisions (an ordered factor sequence, e.g. [2 2 3] for 12/8's
   default grouping -- see default-subdivisions/Meter). Returns a vector
   of N ranks (0..N-1, downbeat pulse always N-1), one per pulse position
   0..N-1, where N is the product of subdivisions. Each factor must be
   2, 3, 5, or 7 (see indispensability-digit-fn)."
  [subdivisions]
  (let [Q (reduce * 1 subdivisions)]
    (mapv #(indispensability-at % Q subdivisions) (range Q))))

(defn meter-indispensability
  "Barlow indispensability for a Meter -- uses its own explicit
   subdivisions if given, otherwise the conventional default (see
   default-subdivisions)."
  [{:keys [num den subdivisions]}]
  (indispensability (or subdivisions (default-subdivisions num den))))

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
;; Scale definitions -- each :steps is the scale's own self-contained
;; interval pattern (cumulative semitone deltas between consecutive
;; degrees, summing to 12), walkable directly from ANY tonic with no
;; further adjustment. There used to be an :offset here too, applied as
;; (+ tonic-pc offset) before walking :steps -- removed after confirming
;; directly it was wrong: e.g. minor's -3 (and dorian's 2, phrygian's 4,
;; ...) turned out to be that mode's own pitch class *within C major*
;; (A is C major's 6th degree, D its 2nd, E its 3rd, ...), which is
;; where those numbers come from, but applying that same C-major-
;; relative number to an arbitrary requested tonic instead of just
;; walking :steps from the tonic directly meant (key :A :minor) silently
;; built F# minor, (key :D :dorian) built E dorian, and so on for every
;; entry except :major/:ionian (offset 0, so the bug never showed).
;; :steps alone, walked from the actual tonic, needs no offset at all --
;; confirmed by hand against real scale formulas for every mode below
;; before removing it.
(def scale-steps
  {:major            [2 2 1 2 2 2 1]
   :minor            [2 1 2 2 1 2 2]
   :harmonic-minor   [2 1 2 2 1 3 1]
   :melodic-minor    [2 1 2 2 2 2 1]
   :ionian           [2 2 1 2 2 2 1]
   :dorian           [2 1 2 2 2 1 2]
   :phrygian         [1 2 2 2 1 2 2]
   :lydian           [2 2 2 1 2 2 1]
   :mixolydian       [2 2 1 2 2 1 2]
   :aeolian          [2 1 2 2 1 2 2]
   :locrian          [1 2 2 1 2 2 2]
   :chromatic        [1 1 1 1 1 1 1 1 1 1 1 1]
   :pentatonic-major [2 2 3 2 3]
   :pentatonic-minor [3 2 2 3 2]
   :blues-major      [2 1 1 3 2]
   :blues-minor      [3 2 1 1 3]
   :whole-tone       [2 2 2 2 2 2]
   :diminished-hw    [1 2 1 2 1 2 1 2]
   :diminished-wh    [2 1 2 1 2 1 2 1]
   :phrygian-dominant [1 3 1 2 1 2 2]
   :hungarian-minor  [2 1 3 1 1 3 1]
   :double-harmonic  [1 3 1 2 1 3 1]
   :bebop-dominant   [2 2 1 2 2 1 1 1]
   :bebop-major      [2 2 1 2 1 1 2 1]})

;; Key record
(defrecord Key [signature scale pitches])

(defn key
  "Create a Key from key and scale keywords."
  [key-kw scale-kw]
  (let [k     (get data/signatures key-kw)
        steps (get scale-steps scale-kw)]
    (when (nil? k) (throw (ex-info (str "Unknown key: " key-kw) {})))
    (when (nil? steps) (throw (ex-info (str "Unknown scale: " scale-kw) {})))
    (let [start (:tonic-pc k)]
      (->Key k {:name scale-kw}
             (loop [ps [start] steps steps cur start]
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

(defn key-tonic-letter
  "The tonic's own natural letter (lowercase char), derived from the
   key's :display name (e.g. \"F#\" -> \\f, \"Bb\" -> \\b) -- always the
   plain natural letter, regardless of the tonic's own accidental."
  [^Key ks]
  (Character/toLowerCase ^Character (first (:display (:signature ks)))))

(defn key-letter-offset
  "Semitone offset ks implies for letter (a lowercase char, e.g. \\f)
   when no explicit accidental is written -- 0 for a non-7-note scale
   (pentatonic/blues/whole-tone/chromatic/bebop/diminished/...), since
   there's no clean 1:1 letter<->degree correspondence to derive one
   from.

   Derived from ks's own actual :pitches, not signatures' raw
   :accidental count -- that count is always the tonic's *major*-key
   signature specifically (D minor shares :D's entry, 2 sharps, but a
   real D minor key signature is 1 flat, confirmed directly before
   settling on this approach), so it's only correct when scale really
   is :major. A 7-note scale's degree N is always built by walking N
   consecutive letters up from the tonic's own letter, regardless of
   the scale's specific step pattern (mode, minor variant, ...), so
   reading the offset back off :pitches directly is correct for any of
   them, not just :major."
  [^Key ks letter]
  (let [pitches (key-pitches ks)]
    (if (not= 7 (count pitches))
      0
      (let [tonic-degree  (data/diatonic-degree (key-tonic-letter ks))
            letter-degree (data/diatonic-degree letter)
            degree        (mod (- letter-degree tonic-degree) 7)
            scale-pc      (mod (nth pitches degree) 12)
            natural-pc    (data/diatonic-pcs letter)
            raw           (- scale-pc natural-pc)]
        (cond (> raw 6)  (- raw 12)
              (< raw -6) (+ raw 12)
              :else      raw)))))

(defn key-pitch-name
  "Like pitch->name, but spelled according to ks: a pitch that's
   actually one of ks's own 7 diatonic scale degrees is spelled with
   that degree's own letter + key-letter-offset (so it always matches
   what an unmarked note under this key would resolve to -- never a
   coincidentally-different enharmonic spelling of the same pitch
   class); a pitch outside the scale (a chromatic passing tone, or ks
   isn't a 7-note scale at all) falls back to picking sharps vs. flats
   from ks's own signature sign, same as pitch->name's own default.
   Used by flat-tree-walker/respell-fn so a transposed note's
   respelling is key-aware in the same way resolving one from scratch
   already is -- one lookup powers both directions."
  [^Key ks pitch]
  (let [octave  (dec (quot pitch 12))
        pc      (mod pitch 12)
        pitches (key-pitches ks)
        degree  (when (= 7 (count pitches))
                  (some #(when (= pc (mod (nth pitches %) 12)) %) (range 7)))]
    (if degree
      (let [tonic-degree (data/diatonic-degree (key-tonic-letter ks))
            letter       (nth data/letter-order (mod (+ tonic-degree degree) 7))
            offset       (key-letter-offset ks letter)
            accidental   (case offset -2 "bb" -1 "b" 0 "" 1 "#" 2 "##"
                           (if (pos? offset) "#" "b"))]
        (str letter accidental octave))
      (pitch->name pitch (>= (:accidental (:signature ks)) 0)))))



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