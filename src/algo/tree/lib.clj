(ns algo.tree.lib
  "Ready-made algos for algo.tree trees. Each name here is a node
   constructor; (algo.tree/algos) lists them all with their params.

     (def riff (notes (gate (euclid) (cycled (scale)))))
     (algo.tree/run riff {:k 5})

   Exposed from algo/ -- every fn carrying :algo metadata in these
   namespaces (the full list with params: (algo.tree/algos)):
     algo.rhythmic.*   euclid fibonacci primes cantor dragon bell tala drums
                       polyrhythm sieve necklace swing genetic ...
     algo.metric       bits cfrac modular
     algo.melodic.*    infra inter ultra polations markov-train
                       markov-gen lsys-melody grammar constrained
                       modulating counterpoint
     algo.random.*     samplers (normal uniform triangular ... -- :len
                       draws), walks (walk glide cyclic chain), logistic
                       henon lorenz, poisson sputter choose-n ...
     algo.indisp       indisp tilt power density
     color-talea       :periods             :pitch :dur -> :pair
   Defined here:
     scale        :root :intervals     -> :pitch (root + offsets)
     cycled, shuffled                  repeat forever / reshuffle every pass
     head         :len                 first :len items
     gate                              :pulse :pitch -> a pitch per onset, nil (rest) elsewhere
     transpose    :semitones           pitches, chords, rests or notes; (transpose :nodes) is a live transform
     stretch      :factor              durations or notes, each duration times :factor
     pick                              :weight -> one weighted :index
     notes        :dur                 :pitch -> Leaf/Rest maps (lazy)
     pair-notes                        :pair -> Leaf/Rest maps
   Leaves, the end product (algo.glue makes the material):
     leaf                              :dur :pitch -> notes, chords, rests
     +volume +articulation +instrument :leaf + that material -> :leaf
   Glue, raw type -> end material (algo.glue, category glue):
     weight->pulse pulse->dur onset->dur number->dur stroke->dur point->dur
     degree->pitch range->pitch point->pitch weight->volume weight->articulation
   Bridges between types:
     degrees      :octaves             :number :pitch -> pitches (data rescaled onto the scale)
     threshold    :level               :number -> :pulse (onset above :level of the range)
     gaps         :unit :quantum       :onset -> :dur between them
     layer        :index               :part -> one part
     axis         :axis                :point -> :number (one coordinate)
     noise        :n :lo :hi :len      smooth value noise -> :number

   And one plain function: (notes->mus parts) renders Leaf/Rest maps as
   musics text, ready to read, edit, or commit with musics.core/parse."
  (:require [algo.tree :refer [defalgo expose-ns]]
            [algo.random :as random]
            [common.music-data :as data :refer [quantity]]
            [core.domain.flat-domain :as d]
            [clojure.string :as str]
            [input.reader.leaf-parser :as lp]))

(expose-ns algo.common.isorhythm
           algo.glue
           algo.indisp.indispensability
           algo.logic.counterpoint
           algo.metric.metric
           algo.melodic.counterpoint algo.melodic.melody algo.melodic.slonimsky
           algo.random algo.random.henon algo.random.logistic algo.random.lorenz
           algo.rhythmic.constraint algo.rhythmic.decompose algo.rhythmic.drums algo.rhythmic.fractal-geometric
           algo.rhythmic.micro algo.rhythmic.necklace algo.rhythmic.phase-sieve
           algo.rhythmic.physical algo.rhythmic.poly algo.rhythmic.rhythm
           algo.rhythmic.sonification algo.rhythmic.stochastic algo.rhythmic.transform
           algo.rhythmic.world)

(defn ->part
  "nil -> Rest, an int -> single-pitch Leaf, a collection -> chord Leaf.
   A Leaf/Rest map passes through unchanged."
  [pitch dur]
  (cond
    (nil? pitch)                     (d/rest* nil nil dur)
    (and (map? pitch) (:type pitch)) pitch
    (coll? pitch)                    (d/leaf nil nil dur (vec pitch))
    :else                            (d/leaf nil nil dur [pitch])))

(defn- gate-seq [grid pitches]
  (lazy-seq
   (when-let [[g & gs] (seq grid)]
     (if (and g (not= 0 g))
       (when-let [[p & ps] (seq pitches)]
         (cons p (gate-seq gs ps)))
       (cons nil (gate-seq gs pitches))))))

(defn- shift [n x]
  (cond (number? x) (+ x n)
        (map? x)    (cond-> x (:pitches x) (update :pitches (partial mapv #(+ % n))))
        (coll? x)   (mapv #(+ % n) x)
        :else       x))

(defalgo scale "Root plus offsets, as absolute pitches."
  {:algo {:category "sources" :in [] :out :pitch
          :params {:root      (quantity :pitch {:doc "lowest pitch"})
                   :intervals {:type :vector :default [0 2 4 7 9] :doc "semitones above root"}}}}
  [root intervals] (mapv #(+ root %) intervals))

(defalgo cycled "Its child, repeated forever (lazy)."
  {:algo {:category "shape" :in [:any] :out :same}}
  [xs] (cycle xs))

(defalgo shuffled "Its child, reshuffled on every pass, forever (lazy)."
  {:algo {:category "shape" :in [:any] :out :same}}
  [xs] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))

(defalgo head "The first :len items of its child."
  {:algo {:category "shape" :in [:any] :out :same
          :params {:len {:type :int :min 0 :max 256 :default 16 :doc "items kept"}}}}
  [xs len] (vec (take len xs)))

(defalgo gate "A pitch on each onset of the grid, nil (a rest) elsewhere."
  {:algo {:category "shape" :in [:pulse :pitch] :out :pitch}}
  [grid pitches] (gate-seq grid pitches))

(defalgo transpose "Shift pitches, chords or notes; rests stay."
  {:algo {:category "shape" :in [:any] :out :same
          :params {:semitones (quantity :semitones {:doc "shift"})}}}
  [xs semitones] (map (partial shift semitones) xs))

(defn- stretched [factor x]
  (cond (number? x)   (* factor x)
        (:duration x) (update x :duration #(* factor %))
        :else         x))

(defalgo stretch "Every duration times :factor -- numbers, or notes' :duration; anything else stays."
  {:algo {:category "shape" :in [:any] :out :same
          :params {:factor (quantity :ratio {:doc "duration multiplier"})}}}
  [xs factor] (map (partial stretched factor) xs))

(defalgo pick "One index, drawn with the child's weights."
  {:algo {:category "shape" :in [:weight] :out :index}}
  [ws] (random/weighted-choose (vec (range (count ws))) ws))

(defalgo notes "Pitches as Leaf/Rest maps of :dur (lazy)."
  {:algo {:category "output" :in [:pitch] :out :leaf
          :params {:dur (quantity :note-value {:doc "note length"})}}}
  [pitches dur] (map #(->part % dur) pitches))

(defalgo pair-notes "[pitch dur] pairs as Leaf/Rest maps."
  {:algo {:category "output" :in [:pair] :out :leaf}}
  [pairs] (map (fn [[p dur]] (->part p dur)) pairs))

;; -- leaves: the end product, blended from end material -----------------------

(defn- zip-skipping-rests
  "f applied to each part and the next value of xs; a Rest passes through
   without using a value. Ends with the shorter."
  [f parts xs]
  (lazy-seq
   (when-let [[p & ps] (seq parts)]
     (if (d/rest? p)
       (cons p (zip-skipping-rests f ps xs))
       (when-let [[x & more] (seq xs)]
         (cons (f p x) (zip-skipping-rests f ps more)))))))

(defalgo leaf "Durations and pitches as leaves: a note, a chord (several pitches), or a rest (nil pitch, or a Rest in the durations, which uses no pitch). Ends with the shorter -- cycle a source for an isorhythm."
  {:algo {:category "output" :in [:dur :pitch] :out :leaf}}
  [durs pitches] (zip-skipping-rests (fn [dur p] (->part p dur)) durs pitches))

(defalgo +volume "Each note's own volume (the !vol: 0-100 scale), overriding the context's; rests take none."
  {:algo {:category "output" :in [:leaf :volume] :out :leaf}}
  [parts volumes] (zip-skipping-rests (fn [p v] (if v (assoc p :volume v) p)) parts volumes))

(defalgo +articulation "Each note's articulation, a name from common.music-data/articulations (as c4-> writes :accent); nil plays plain. Rests take none."
  {:algo {:category "output" :in [:leaf :articulation] :out :leaf}}
  [parts arts]
  (zip-skipping-rests
   (fn [p a]
     (if-let [{:keys [duration dynamic]} (some-> a keyword data/articulations)]
       (cond-> p
         duration          (assoc :articulation duration)
         (pos? (abs dynamic)) (update :dynamic #(+ (or % 0) dynamic)))
       p))
   parts arts))

(defn- program
  "A General MIDI name as its MIDI program (common.music-data/gm-sound-set)."
  [x]
  (:prog (get data/gm-sound-set (keyword x))))

(defalgo +instrument "Each note's instrument: a MIDI program (0-127) or a General MIDI name, or a drum (a name such as \"kick\", or a number with :drum? on) -- the note then becomes that drum, keeping its length and volume. Rests take none."
  {:algo {:category "output" :in [:leaf :instrument] :out :leaf
          :params {:drum? {:type :bool :default false :doc "numbers are drum keys, not programs"}}}}
  [parts instruments drum?]
  (zip-skipping-rests
   (fn [p i]
     (let [prog (cond (nil? i) nil (number? i) (when-not drum? (int i)) :else (program i))
           drum (when-not prog
                  (cond (number? i) (int i) (some? i) (data/resolve-drum (name i))))]
       (cond prog (assoc p :program prog)
             drum (merge (d/drum nil nil (:duration p) drum) (select-keys p [:volume :dynamic]))
             (some? i) (throw (ex-info (str "+instrument: not a program, General MIDI name or drum: " (pr-str i)) {:instrument i}))
             :else p)))
   parts instruments))

;; -- bridges between types ----------------------------------------------------

(defn- unit-scaled
  "xs rescaled so its own min..max spans 0..1 (all 0.0 when flat)."
  [xs]
  (let [xs (vec xs) lo (apply min xs) span (- (apply max xs) lo)]
    (mapv #(if (zero? span) 0.0 (/ (- % lo) (double span))) xs)))

(defalgo degrees "Numbers rescaled onto a scale: lowest -> first degree, highest -> last."
  {:algo {:category "bridges" :in [:number :pitch] :out :pitch
          :params {:octaves {:type :int :min 1 :max 4 :default 1 :doc "octaves of the scale spanned"}}}}
  [xs scale octaves]
  (let [steps (vec (for [o (range octaves) p scale] (+ p (* 12 o))))
        top   (dec (count steps))]
    (mapv #(nth steps (Math/round (double (* % top)))) (unit-scaled xs))))

(defalgo threshold "An onset where a number is above :level of its range."
  {:algo {:category "bridges" :in [:number] :out :pulse
          :params {:level {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "0 = lowest, 1 = highest"}}}}
  [xs level] (mapv #(if (> % level) 1 0) (unit-scaled xs)))

(defalgo gaps "The time between successive onsets, as note values."
  {:algo {:category "bridges" :in [:onset] :out :dur
          :params {:unit    (quantity :note-value {:doc "note value of one time unit"})
                   :quantum {:type :ratio :min 1/128 :max 1 :default 1/32 :doc "rounded to a multiple of this"}}}}
  [onsets unit quantum]
  (let [ts (sort onsets)]
    (vec (for [[a b] (map vector ts (rest ts))
               :let [q (Math/round (double (/ (* (- b a) unit) quantum)))]
               :when (pos? q)]
           (* q quantum)))))

(defalgo layer "One layer of several parallel ones (wrapping)."
  {:algo {:category "bridges" :in [:part] :out :any
          :params {:index {:type :int :min 0 :max 63 :default 0 :doc "which layer"}}}}
  [layers index] (let [v (vec layers)] (nth v (mod index (count v)))))

(defalgo axis "One coordinate of each point."
  {:algo {:category "bridges" :in [:point] :out :number
          :params {:axis {:type :int :min 0 :max 2 :default 0 :doc "x = 0, y = 1, z = 2"}}}}
  [points axis] (mapv #(nth % axis) points))

(defalgo noise "Smooth value noise: n random points, blended, sampled :len times."
  {:algo {:category "sources" :in [] :out :number
          :params {:n   {:type :int :min 2 :max 64 :default 8 :doc "random points blended"}
                   :lo  {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "lowest value"}
                   :hi  {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "highest value"}
                   :len {:type :int :min 1 :max 1024 :default 16 :doc "how many values"}}}}
  [n lo hi len]
  (let [f (random/smooth-noise n lo hi)]
    (mapv #(f (* % (/ (dec n) (double (max 1 (dec len)))))) (range len))))

(defn notes->mus
  "Leaf/Rest maps -> one musics text Sequence: absolute pitches, explicit
   durations, and !acc:explicit so its meaning never depends on
   the key it's later committed under."
  [parts]
  (doseq [m (mapcat :pitches parts)]
    (when-not (<= 24 m 119)
      (throw (ex-info (str "notes->mus: MIDI " m " is outside musics text's octaves 1-8 (MIDI 24-119)") {}))))
  (str "[ !acc:explicit " (str/join " " (keep lp/part->mus parts)) " ]"))
