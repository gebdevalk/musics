(ns algo.glue
  "One-way glue from raw types to end material (doc/algo-audit.md, the
   leaf principle): pulse, onset, number, stroke, point and weight become
   dur, pitch, volume or articulation -- never back, and never one end
   material into another. Each is an ordinary tree algo: data children
   in, params in the tctx, one type out.

   A dur stream holds note values, plus a Rest (core.domain.flat-domain/
   rest*) where there is silence before anything sounds; algo.tree.lib's
   leaf passes a Rest through without using a pitch. Glue that works
   value by value is lazy, so a cycled source can feed it; glue that
   maps a range (number->dur, range->pitch, the weight glue) needs its
   whole input, so give it a finite one. Keys are spelled as
   !key: writes them (\"D.major\"), volume on the !vol: 0-100 scale,
   articulations from common.music-data/articulations."
  (:require [algo.indisp.indispensability :as indisp]
            [common.music-data :as data :refer [quantity]]
            [common.music-elements :as el]
            [core.domain.flat-domain :as d]))

;; ---------------------------------------------------------------------------
;; shared
;; ---------------------------------------------------------------------------

(defn- unit
  "xs rescaled so their own min..max spans 0..1 (all 0.0 when flat)."
  [xs]
  (let [xs (vec xs) lo (apply min xs) span (- (apply max xs) lo)]
    (mapv #(if (zero? span) 0.0 (/ (- % lo) (double span))) xs)))

(defn- quantise
  "x (a note value) rounded to a multiple of q, at least q."
  [x q]
  (* q (max 1 (Math/round (double (/ x q))))))

(defn- spread
  "xs' own range mapped onto lo..hi."
  [xs lo hi]
  (map #(+ lo (* % (- hi lo))) (unit xs)))

(defn- key-steps
  "The ascending pitch classes of a key spelled as !key: writes it."
  [spec]
  (or (some-> (el/parse-key spec) el/key-pitches vec)
      (throw (ex-info (str "glue: not a key: " (pr-str spec) " -- write it as !key: does, e.g. \"D.major\"")
                      {:key spec}))))

(defn- run-durs
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

(def ^:private pulse-spec (quantity :note-value {:default 1/16 :doc "note value of one pulse"}))
(def ^:private quantum-spec {:type :ratio :min 1/128 :max 1 :default 1/32 :doc "rounded to a multiple of this"})
(def ^:private key-spec {:type :string :default "C.major" :doc "key as !key: writes it"})
(def ^:private octave-spec {:type :int :min 0 :max 8 :default 4 :doc "octave of the tonic (4: C4 = 60)"})

;; ---------------------------------------------------------------------------
;; to pulse
;; ---------------------------------------------------------------------------

(defn weight->pulse
  "The strongest :density fraction of the pulses on, the rest off -- a
   meter thinned to its most indispensable pulses."
  {:algo {:category "glue" :in [:weight] :out :pulse
          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "fraction of pulses kept"}}}}
  [weights density]
  (indisp/density-grid weights density))

;; ---------------------------------------------------------------------------
;; to dur
;; ---------------------------------------------------------------------------

(defn pulse->dur
  "Each onset lasts until the next one; a 0 lengthens the note before it,
   and 0s before the first onset are one Rest."
  {:algo {:category "glue" :in [:pulse] :out :dur
          :params {:pulse pulse-spec}}}
  [pulses pulse]
  (run-durs #(and % (not= 0 %)) pulses pulse))

(defn stroke->dur
  "A syllable starts a note, \"-\" lengthens it; \"-\" before the first
   syllable is a Rest."
  {:algo {:category "glue" :in [:stroke] :out :dur
          :params {:pulse pulse-spec}}}
  [strokes pulse]
  (run-durs #(not= "-" %) strokes pulse))

(defn onset->dur
  "The time between successive onsets, as note values: :unit is the note
   value of one time unit, rounded to multiples of :quantum."
  {:algo {:category "glue" :in [:onset] :out :dur
          :params {:unit    (quantity :note-value {:doc "note value of one time unit"})
                   :quantum quantum-spec}}}
  [onsets unit quantum]
  (let [ts (sort onsets)]
    (vec (for [[a b] (map vector ts (rest ts))
               :let [q (Math/round (double (/ (* (- b a) unit) quantum)))]
               :when (pos? q)]
           (* q quantum)))))

(defn number->dur
  "Numbers' own range onto note values :lo..:hi, rounded to :quantum."
  {:algo {:category "glue" :in [:number] :out :dur
          :params {:lo (quantity :note-value {:default 1/16 :doc "shortest"})
                   :hi (quantity :note-value {:default 1/2 :doc "longest"})
                   :quantum quantum-spec}}}
  [xs lo hi quantum]
  (mapv #(quantise (rationalize %) quantum) (spread xs lo hi)))

(defn point->dur
  "One coordinate of each point as a note value, like number->dur."
  {:algo {:category "glue" :in [:point] :out :dur
          :params {:axis {:type :int :min 0 :max 2 :default 0 :doc "x = 0, y = 1, z = 2"}
                   :lo (quantity :note-value {:default 1/16 :doc "shortest"})
                   :hi (quantity :note-value {:default 1/2 :doc "longest"})
                   :quantum quantum-spec}}}
  [points axis lo hi quantum]
  (number->dur (mapv #(nth % axis) points) lo hi quantum))

;; ---------------------------------------------------------------------------
;; to pitch
;; ---------------------------------------------------------------------------

(defn degree->pitch
  "Whole numbers as scale steps of :key from the tonic in :octave: 0 the
   tonic, 7 the tonic an octave up in a seven-note scale, -1 the step
   below."
  {:algo {:category "glue" :in [:number] :out :pitch
          :params {:key key-spec :octave octave-spec}}}
  [degrees key octave]
  (let [steps (key-steps key)
        n     (count steps)
        base  (* 12 (inc octave))]
    (map (fn [x] (let [dg (Math/round (double x))]
                    (+ base (nth steps (mod dg n)) (* 12 (Math/floorDiv dg n)))))
          degrees)))

(defn range->pitch
  "Numbers' own range onto pitches :lo..:hi, rounded to semitones."
  {:algo {:category "glue" :in [:number] :out :pitch
          :params {:lo (quantity :pitch {:default 48 :doc "lowest pitch"})
                   :hi (quantity :pitch {:default 84 :doc "highest pitch"})}}}
  [xs lo hi]
  (mapv #(Math/round (double %)) (spread xs lo hi)))

(defn point->pitch
  "One coordinate of each point onto the steps of :key over :octaves
   from the tonic in :octave."
  {:algo {:category "glue" :in [:point] :out :pitch
          :params {:axis {:type :int :min 0 :max 2 :default 0 :doc "x = 0, y = 1, z = 2"}
                   :key key-spec :octave octave-spec
                   :octaves {:type :int :min 1 :max 4 :default 2 :doc "octaves spanned"}}}}
  [points axis key octave octaves]
  (let [n (count (key-steps key))]
    (degree->pitch (map #(* % (dec (* n octaves))) (unit (map #(nth % axis) points))) key octave)))

;; ---------------------------------------------------------------------------
;; to volume and articulation
;; ---------------------------------------------------------------------------

(defn weight->volume
  "Weights' own range onto volumes :lo..:hi (the !vol: 0-100 scale):
   strong pulses louder."
  {:algo {:category "glue" :in [:weight] :out :volume
          :params {:lo (quantity :volume {:default 40.0 :doc "weakest"})
                   :hi (quantity :volume {:default 80.0 :doc "strongest"})}}}
  [weights lo hi]
  (vec (spread weights lo hi)))

(defn weight->articulation
  "Weights' own range split into as many bands as :levels, weakest
   first; nil plays plain. Levels are common.music-data/articulations'
   names."
  {:algo {:category "glue" :in [:weight] :out :articulation
          :params {:levels {:type :vector :default [:ghost nil :accent :marcato]
                            :doc "articulation per band, weakest first"}}}}
  [weights levels]
  (let [levels (vec levels) n (count levels)]
    (mapv #(nth levels (min (dec n) (int (* % n)))) (unit weights))))
