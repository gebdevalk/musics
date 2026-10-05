(ns musics.algo.bridge
  "Bridges: one-way conversions from raw types to end material
   (doc/algo-audit.md, the leaf principle) -- pulse, onset, number,
   stroke, point and weight become duration, pitch, volume or
   articulation; never back, and never one end material into another.

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
            [musics.common.music-data :refer [quantity]]
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
(def ^:private key-spec     {:type :string :default "C.major" :doc "key as !key: writes it"})
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
          :params {:lo (quantity :volume {:default 40.0 :doc "weakest"})
                   :hi (quantity :volume {:default 80.0 :doc "strongest"})}}}
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
