(ns musics.algo.bridge
  "Bridges: one-way conversions from raw material to half products
   (doc/md/algo-space.md, section 0) -- pulse, onset, number, stroke, point
   and weight become duration, pitch, volume or articulation; never
   back, and never one half product into another.

   A type names one value (:pitch, :duration); a tree carries streams of
   them. A value bridge converts one value and is named in the singular
   (degree->pitch); a stream bridge is the tree algo, named in the plural
   (degrees->pitches), and maps its value bridge. How a stream bridge
   works is in its :works metadata:
     :value  each value on its own -- lazy, so an endless stream is fine
     :shape  by neighbours (an onset lasts until the next) -- lazy too
     :whole  it reads the whole stream first (a meter's weights, its
             strongest pulses) -- give it a finite one

   A duration stream holds note values, plus a Rest (musics.domain.
   domain/rest*) for silence before anything sounds; musics.algo.tree.lib's
   zip passes a Rest through without using a pitch. Keys are spelled as
   !key: writes them (\"D.major\"), volume on the !vol: 0-100 scale,
   articulations from musics.common.music-data/articulations."
  (:require [musics.algo.indispensability :as indisp]
            [musics.common.music-data :refer [quantity program-of]]
            [musics.common.music-elements :as el]
            [musics.domain :as d]))

;; ---------------------------------------------------------------------------
;; shared
;; ---------------------------------------------------------------------------

(defn- unit
  "xs rescaled so their own min..max spans 0..1 (all 0.0 when flat)."
  [xs]
  (let [xs (vec xs) lo (apply min xs) span (- (apply max xs) lo)]
    (mapv #(if (zero? span) 0.0 (/ (- % lo) (double span))) xs)))

(defn- along
  "x's place in from-lo..from-hi as 0..1, clamped."
  [x from-lo from-hi]
  (let [span (- from-hi from-lo)]
    (if (zero? span) 0.0 (-> (/ (- x from-lo) (double span)) (max 0.0) (min 1.0)))))

(defn- quantise
  "x (a note value) rounded to a multiple of q, at least q."
  [x q]
  (* q (max 1 (Math/round (double (/ x q))))))

(def ^:private key-steps
  "The ascending pitch classes of a key spelled as !key: writes it --
   memoized, so a value bridge called per note parses each key once."
  (memoize
   (fn [spec]
     (or (some-> (el/parse-key spec) el/key-pitches vec)
         (throw (ex-info (str "bridge: not a key: " (pr-str spec) " -- write it as !key: does, e.g. \"D.major\"")
                         {:key spec}))))))

(defn- run-durations
  "Durations of `pulse` each, lazily: the items before the first onset
   (onset? true) as one Rest, then each onset with the items up to the
   next one as one note."
  [onset? xs pulse]
  (let [notes (fn notes [xs]
                (lazy-seq
                 (when-let [[_ & tail] (seq xs)]
                   (let [[hold more] (split-with (complement onset?) tail)]
                     (cons (* pulse (inc (count hold))) (notes more))))))]
    (lazy-seq
     (let [[lead more] (split-with (complement onset?) xs)]
       (cond->> (notes more)
         (seq lead) (cons (d/rest* nil nil (* (count lead) pulse))))))))

(def ^:private pulse-spec   (quantity :note-value {:default 1/16 :doc "note value of one pulse"}))
(def ^:private quantum-spec {:type :ratio :min 1/128 :max 1 :default 1/32 :doc "rounded to a multiple of this"})
(def ^:private key-spec     {:type :string :default "C.major" :context :key
                             :doc "key as !key: writes it; else the music's !key: where it plays"})
(def ^:private octave-spec  {:type :int :min 0 :max 8 :default 4 :doc "octave of the tonic (4: C4 = 60)"})
(def ^:private axis-spec    {:type :int :min 0 :max 2 :default 0 :doc "x = 0, y = 1, z = 2"})
(def ^:private from-lo-spec {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "input value that maps to the lowest"})
(def ^:private from-hi-spec {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "input value that maps to the highest"})
(def ^:private dur-lo-spec  (quantity :note-value {:default 1/16 :doc "shortest"}))
(def ^:private dur-hi-spec  (quantity :note-value {:default 1/2 :doc "longest"}))

;; ---------------------------------------------------------------------------
;; value bridges: one value
;; ---------------------------------------------------------------------------

(defn degree->pitch
  "One scale step of `key` (as !key: writes it) as a pitch, counted from
   the tonic in `octave`: 0 the tonic, 7 the tonic an octave up in a
   seven-note scale, -1 the step below. degrees->pitches maps it."
  [degree key octave]
  (let [steps (key-steps key)
        n     (count steps)
        dg    (Math/round (double degree))]
    (+ (* 12 (inc octave)) (nth steps (mod dg n)) (* 12 (Math/floorDiv dg n)))))

(defn number->pitch
  "x's place in from-lo..from-hi as a pitch in lo..hi, to the nearest
   semitone (clamped). numbers->pitches maps it."
  [x from-lo from-hi lo hi]
  (Math/round (double (+ lo (* (along x from-lo from-hi) (- hi lo))))))

(defn number->duration
  "x's place in from-lo..from-hi as a note value in lo..hi, rounded to a
   multiple of quantum (clamped). numbers->durations maps it."
  [x from-lo from-hi lo hi quantum]
  (quantise (rationalize (+ lo (* (along x from-lo from-hi) (- hi lo)))) quantum))

(defn number->volume
  "x's place in from-lo..from-hi as a volume in lo..hi (the !vol: 0-100
   scale, clamped). numbers->volumes maps it."
  [x from-lo from-hi lo hi]
  (+ lo (* (along x from-lo from-hi) (- hi lo))))

;; ---------------------------------------------------------------------------
;; stream bridges to duration
;; ---------------------------------------------------------------------------

(defn pulses->durations
  "Each onset lasts until the next one; a 0 lengthens the note before it,
   and 0s before the first onset are one Rest."
  {:algo {:category "bridge" :works :shape :in [:pulse] :out :duration
          :params {:pulse pulse-spec}}}
  [pulses pulse]
  (run-durations #(and % (not= 0 %)) pulses pulse))

(defn strokes->durations
  "A syllable starts a note, \"-\" lengthens it; \"-\" before the first
   syllable is a Rest."
  {:algo {:category "bridge" :works :shape :in [:stroke] :out :duration
          :params {:pulse pulse-spec}}}
  [strokes pulse]
  (run-durations #(not= "-" %) strokes pulse))

(defn onsets->durations
  "The time between successive onsets, as note values: :unit is the note
   value of one time unit, rounded to multiples of :quantum. The onsets
   are sorted first, so the stream must be finite."
  {:algo {:category "bridge" :works :whole :in [:onset] :out :duration
          :params {:unit    (quantity :note-value {:doc "note value of one time unit"})
                   :quantum quantum-spec}}}
  [onsets unit quantum]
  (let [ts (sort onsets)]
    (vec (for [[a b] (map vector ts (rest ts))
               :let [q (Math/round (double (/ (* (- b a) unit) quantum)))]
               :when (pos? q)]
           (* q quantum)))))

(defn numbers->durations
  "Each number's place in :from-lo..:from-hi as a note value in
   :lo..:hi, rounded to :quantum."
  {:algo {:category "bridge" :works :value :in [:number] :out :duration
          :params {:from-lo from-lo-spec :from-hi from-hi-spec
                   :lo dur-lo-spec :hi dur-hi-spec :quantum quantum-spec}}}
  [xs from-lo from-hi lo hi quantum]
  (map #(number->duration % from-lo from-hi lo hi quantum) xs))

(defn points->durations
  "One coordinate of each point as a note value, like numbers->durations."
  {:algo {:category "bridge" :works :value :in [:point] :out :duration
          :params {:axis axis-spec :from-lo from-lo-spec :from-hi from-hi-spec
                   :lo dur-lo-spec :hi dur-hi-spec :quantum quantum-spec}}}
  [points axis from-lo from-hi lo hi quantum]
  (map #(number->duration (nth % axis) from-lo from-hi lo hi quantum) points))

;; ---------------------------------------------------------------------------
;; stream bridges to pitch
;; ---------------------------------------------------------------------------

(defn degrees->pitches
  "Whole numbers as scale steps of :key from the tonic in :octave: 0 the
   tonic, 7 the tonic an octave up in a seven-note scale, -1 the step
   below."
  {:algo {:category "bridge" :works :value :in [:number] :out :pitch
          :params {:key key-spec :octave octave-spec}}}
  [degrees key octave]
  (key-steps key)                        ; a bad key fails here, not lazily
  (map #(degree->pitch % key octave) degrees))

(defn numbers->pitches
  "Each number's place in :from-lo..:from-hi as a pitch in :lo..:hi."
  {:algo {:category "bridge" :works :value :in [:number] :out :pitch
          :params {:from-lo from-lo-spec :from-hi from-hi-spec
                   :lo (quantity :pitch {:default 48 :doc "lowest pitch"})
                   :hi (quantity :pitch {:default 84 :doc "highest pitch"})}}}
  [xs from-lo from-hi lo hi]
  (map #(number->pitch % from-lo from-hi lo hi) xs))

(defn points->pitches
  "One coordinate of each point, its place in :from-lo..:from-hi, onto
   the steps of :key over :octaves from the tonic in :octave."
  {:algo {:category "bridge" :works :value :in [:point] :out :pitch
          :params {:axis axis-spec :from-lo from-lo-spec :from-hi from-hi-spec
                   :key key-spec :octave octave-spec
                   :octaves {:type :int :min 1 :max 4 :default 2 :doc "octaves spanned"}}}}
  [points axis from-lo from-hi key octave octaves]
  (let [top (dec (* (count (key-steps key)) octaves))]
    (map #(degree->pitch (* top (along (nth % axis) from-lo from-hi)) key octave) points)))

;; ---------------------------------------------------------------------------
;; stream bridges to volume
;; ---------------------------------------------------------------------------

(def ^:private vol-lo-spec (quantity :volume {:default 40.0 :doc "softest"}))
(def ^:private vol-hi-spec (quantity :volume {:default 80.0 :doc "loudest"}))

(defn numbers->volumes
  "Each number's place in :from-lo..:from-hi as a volume in :lo..:hi
   (the !vol: 0-100 scale)."
  {:algo {:category "bridge" :works :value :in [:number] :out :volume
          :params {:from-lo from-lo-spec :from-hi from-hi-spec :lo vol-lo-spec :hi vol-hi-spec}}}
  [xs from-lo from-hi lo hi]
  (map #(number->volume % from-lo from-hi lo hi) xs))

(defn points->volumes
  "One coordinate of each point as a volume, like numbers->volumes."
  {:algo {:category "bridge" :works :value :in [:point] :out :volume
          :params {:axis axis-spec :from-lo from-lo-spec :from-hi from-hi-spec :lo vol-lo-spec :hi vol-hi-spec}}}
  [points axis from-lo from-hi lo hi]
  (map #(number->volume (nth % axis) from-lo from-hi lo hi) points))

;; ---------------------------------------------------------------------------
;; stream bridges to instrument
;; ---------------------------------------------------------------------------

(def ^:private palette-spec
  {:type :vector :default []
   :doc "instruments to choose from, programs or General MIDI names (seashore, :bird-tweet); empty: all 128, in General MIDI's order"})

(defn- programs
  "A palette as programs: names through musics.common.music-data/program-of."
  [p]
  (if (empty? p)
    (vec (range 128))
    (mapv (fn [x] (cond (number? x) (int x)
                        :else (or (program-of x)
                                  (throw (ex-info (str "bridge: not a General MIDI instrument: " (pr-str x)) {:instrument x})))))
          p)))

(defn number->instrument
  "x's place in from-lo..from-hi as one of the palette's instruments (a
   MIDI program), lowest first. numbers->instruments maps it."
  [x from-lo from-hi programs]
  (let [n (count programs)]
    (nth programs (min (dec n) (int (* (along x from-lo from-hi) n))))))

(defn numbers->instruments
  "Each number's place in :from-lo..:from-hi as an instrument of
   :palette (a MIDI program; all of General MIDI when empty)."
  {:algo {:category "bridge" :works :value :in [:number] :out :instrument
          :params {:from-lo from-lo-spec :from-hi from-hi-spec :palette palette-spec}}}
  [xs from-lo from-hi palette]
  (let [ps (programs palette)] (map #(number->instrument % from-lo from-hi ps) xs)))

(defn points->instruments
  "One coordinate of each point as an instrument, like numbers->instruments."
  {:algo {:category "bridge" :works :value :in [:point] :out :instrument
          :params {:axis axis-spec :from-lo from-lo-spec :from-hi from-hi-spec :palette palette-spec}}}
  [points axis from-lo from-hi palette]
  (let [ps (programs palette)] (map #(number->instrument (nth % axis) from-lo from-hi ps) points)))

;; ---------------------------------------------------------------------------
;; stream bridges from pulses to accents
;; ---------------------------------------------------------------------------

(defn- onset-weights
  "One weight (0..1) per onset of pulses, lazily: :metric its place in the
   bar (the meter's), :agogic its length (a pulse 0, a bar or longer 1)."
  [pulses accent pulse meter]
  (let [meter  (cond (string? meter) (el/parse-meter-str meter) meter meter :else (el/make-meter 4 4))
        mw     (when (= accent :metric) (el/metric-weights meter pulse))
        bar-n  (max 2 (long (/ (el/meter-bar-length meter) pulse)))
        onset? #(and % (not= 0 %))
        weight (fn [i len] (if (= accent :agogic)
                             (min 1.0 (/ (Math/log len) (Math/log bar-n)))
                             (if mw (nth mw (mod i (count mw))) 1.0)))
        step   (fn step [i xs]
                 (lazy-seq
                  (when-let [[x & tail] (seq xs)]
                    (if (onset? x)
                      (let [[hold more] (split-with (complement onset?) tail)
                            len         (inc (count hold))]
                        (cons (weight i len) (step (+ i len) more)))
                      (step (inc i) tail)))))]
    (step 0 pulses)))

(def ^:private accent-spec {:type :keyword :choices [:metric :agogic] :default :metric
                            :doc "metric: by place in the bar; agogic: by length"})
(def ^:private meter-spec  {:type :any :default "4/4" :context :Meter
                            :doc "meter as !Meter: writes it; else the music's where it plays"})

(defn pulses->volumes
  "Each onset's accent as a volume in :lo..:hi (the !vol: 0-100 scale),
   one per note pulses->durations makes: :metric by its place in the bar
   (the downbeat loudest), :agogic by its length (longer louder)."
  {:algo {:category "bridge" :works :shape :in [:pulse] :out :volume
          :params {:accent accent-spec :pulse pulse-spec :meter meter-spec :lo vol-lo-spec :hi vol-hi-spec}}}
  [pulses accent pulse meter lo hi]
  (map #(+ lo (* % (- hi lo))) (onset-weights pulses accent pulse meter)))

(defn pulses->articulations
  "Each onset's accent as an articulation, as weights->articulations
   bands a weight: :metric by its place in the bar, :agogic by its
   length; one per note pulses->durations makes."
  {:algo {:category "bridge" :works :shape :in [:pulse] :out :articulation
          :params {:accent accent-spec :pulse pulse-spec :meter meter-spec
                   :levels {:type :vector :default [:ghost nil :accent :marcato]
                            :doc "articulation per band, weakest first"}}}}
  [pulses accent pulse meter levels]
  (let [levels (vec levels) n (count levels)]
    (map #(nth levels (min (dec n) (int (* % n)))) (onset-weights pulses accent pulse meter))))

;; ---------------------------------------------------------------------------
;; stream bridges from weights (a meter's, finite)
;; ---------------------------------------------------------------------------

(defn weights->pulses
  "The strongest :density fraction of the pulses on, the rest off -- a
   meter thinned to its most indispensable pulses. A weight of 0 or
   less is never on, so at :density 1.0 every accented pulse sounds."
  {:algo {:category "bridge" :works :whole :in [:weight] :out :pulse
          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "fraction of pulses kept"}}}}
  [weights density]
  (mapv (fn [on w] (if (pos? w) on 0)) (indisp/density-grid weights density) weights))

(defn weights->volumes
  "The weights' own range onto volumes :lo..:hi (the !vol: 0-100
   scale): strong pulses louder."
  {:algo {:category "bridge" :works :whole :in [:weight] :out :volume
          :params {:lo vol-lo-spec :hi vol-hi-spec}}}
  [weights lo hi]
  (mapv #(+ lo (* % (- hi lo))) (unit weights)))

(defn weights->articulations
  "The weights' own range split into as many bands as :levels, weakest
   first; nil plays plain. Levels are musics.common.music-data/articulations'
   names."
  {:algo {:category "bridge" :works :whole :in [:weight] :out :articulation
          :params {:levels {:type :vector :default [:ghost nil :accent :marcato]
                            :doc "articulation per band, weakest first"}}}}
  [weights levels]
  (let [levels (vec levels) n (count levels)]
    (mapv #(nth levels (min (dec n) (int (* % n)))) (unit weights))))
