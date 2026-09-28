(ns algo.tree.lib
  "Ready-made algos for algo.tree trees. Each name here is a node
   constructor; (algo.tree/algos) lists them all with their params.

     (def riff (notes (gate (euclid) (cycled (scale)))))
     (algo.tree/run riff {:k 5})

   Exposed from algo/ -- every fn carrying :algo metadata in these
   namespaces (the full list with params: (algo.tree/algos)):
     algo.rhythmic.*   euclid fibonacci primes cantor dragon bell tala
                       polyrhythm sieve necklace swing genetic ...
     algo.metric       bits cfrac modular
     algo.melodic.*    infra inter ultra polations markov-train
                       markov-gen lsys-melody grammar constrained
                       modulating counterpoint
     algo.random.*     samplers (normal uniform triangular ... -- :len
                       draws), walks (walk glide cyclic chain), logistic
                       henon lorenz, poisson sputter choose-n ...
     algo.indisp       indisp tilt power density
     color-talea       :periods             :pitches :durations -> :pairs
   Defined here:
     scale        :root :intervals     -> :pitches (root + offsets)
     cycled, shuffled                  repeat forever / reshuffle every pass
     head         :len                 first :len items
     gate                              :grid :pitches -> a pitch per onset, nil (rest) elsewhere
     transpose    :semitones           pitches, chords, rests or notes; (transpose :nodes) is a live transform
     stretch      :factor              durations or notes, each duration times :factor
     pick                              :weights -> one weighted :index
     notes        :dur                 :pitches -> Leaf/Rest maps (lazy)
     pair-notes                        :pairs -> Leaf/Rest maps
   Bridges between types:
     degrees      :octaves             :numbers :pitches -> pitches (data rescaled onto the scale)
     threshold    :level               :numbers -> :grid (onset above :level of the range)
     gaps         :unit :quantum       :onsets -> :durations between them
     layer        :index               :layers -> one layer
     axis         :axis                :points -> :numbers (one coordinate)
     noise        :n :lo :hi :len      smooth value noise -> :numbers

   And one plain function: (notes->mus parts) renders Leaf/Rest maps as
   musics text, ready to read, edit, or commit with musics.core/parse."
  (:require [algo.tree :refer [defalgo expose-ns]]
            [algo.random :as random]
            [core.domain.flat-domain :as d]
            [clojure.string :as str]
            [input.abc-import :as abc]
            [input.reader.leaf-parser :as lp]))

(expose-ns algo.common.isorhythm
           algo.indisp.indispensability
           algo.metric.metric
           algo.melodic.counterpoint algo.melodic.melody algo.melodic.slonimsky
           algo.random algo.random.henon algo.random.logistic algo.random.lorenz
           algo.rhythmic.constraint algo.rhythmic.decompose algo.rhythmic.fractal-geometric
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
  {:algo {:in [] :out :pitches
          :params {:root      {:type :int :min 24 :max 96 :default 60 :doc "lowest pitch"}
                   :intervals {:type :vector :default [0 2 4 7 9] :doc "semitones above root"}}}}
  [root intervals] (mapv #(+ root %) intervals))

(defalgo cycled "Its child, repeated forever (lazy)."
  {:algo {:in [:any] :out :same}}
  [xs] (cycle xs))

(defalgo shuffled "Its child, reshuffled on every pass, forever (lazy)."
  {:algo {:in [:any] :out :same}}
  [xs] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))

(defalgo head "The first :len items of its child."
  {:algo {:in [:any] :out :same
          :params {:len {:type :int :min 0 :max 256 :default 16 :doc "items kept"}}}}
  [xs len] (vec (take len xs)))

(defalgo gate "A pitch on each onset of the grid, nil (a rest) elsewhere."
  {:algo {:in [:grid :pitches] :out :pitches}}
  [grid pitches] (gate-seq grid pitches))

(defalgo transpose "Shift pitches, chords or notes; rests stay."
  {:algo {:in [:any] :out :same
          :params {:semitones {:type :int :min -60 :max 60 :default 0 :doc "shift"}}}}
  [xs semitones] (map (partial shift semitones) xs))

(defn- stretched [factor x]
  (cond (number? x)   (* factor x)
        (:duration x) (update x :duration #(* factor %))
        :else         x))

(defalgo stretch "Every duration times :factor -- numbers, or notes' :duration; anything else stays."
  {:algo {:in [:any] :out :same
          :params {:factor {:type :ratio :min 1/16 :max 16 :default 1 :doc "duration multiplier"}}}}
  [xs factor] (map (partial stretched factor) xs))

(defalgo pick "One index, drawn with the child's weights."
  {:algo {:in [:weights] :out :index}}
  [ws] (random/weighted-choose (vec (range (count ws))) ws))

(defalgo notes "Pitches as Leaf/Rest maps of :dur (lazy)."
  {:algo {:in [:pitches] :out :notes
          :params {:dur {:type :ratio :min 1/64 :max 4 :default 1/8 :doc "note length"}}}}
  [pitches dur] (map #(->part % dur) pitches))

(defalgo pair-notes "[pitch dur] pairs as Leaf/Rest maps."
  {:algo {:in [:pairs] :out :notes}}
  [pairs] (map (fn [[p dur]] (->part p dur)) pairs))

;; -- bridges between types ----------------------------------------------------

(defn- unit-scaled
  "xs rescaled so its own min..max spans 0..1 (all 0.0 when flat)."
  [xs]
  (let [xs (vec xs) lo (apply min xs) span (- (apply max xs) lo)]
    (mapv #(if (zero? span) 0.0 (/ (- % lo) (double span))) xs)))

(defalgo degrees "Numbers rescaled onto a scale: lowest -> first degree, highest -> last."
  {:algo {:in [:numbers :pitches] :out :pitches
          :params {:octaves {:type :int :min 1 :max 4 :default 1 :doc "octaves of the scale spanned"}}}}
  [xs scale octaves]
  (let [steps (vec (for [o (range octaves) p scale] (+ p (* 12 o))))
        top   (dec (count steps))]
    (mapv #(nth steps (Math/round (double (* % top)))) (unit-scaled xs))))

(defalgo threshold "An onset where a number is above :level of its range."
  {:algo {:in [:numbers] :out :grid
          :params {:level {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "0 = lowest, 1 = highest"}}}}
  [xs level] (mapv #(if (> % level) 1 0) (unit-scaled xs)))

(defalgo gaps "The time between successive onsets, as note values."
  {:algo {:in [:onsets] :out :durations
          :params {:unit    {:type :ratio :min 1/64 :max 4 :default 1/4 :doc "note value of one time unit"}
                   :quantum {:type :ratio :min 1/128 :max 1 :default 1/32 :doc "rounded to a multiple of this"}}}}
  [onsets unit quantum]
  (let [ts (sort onsets)]
    (vec (for [[a b] (map vector ts (rest ts))
               :let [q (Math/round (double (/ (* (- b a) unit) quantum)))]
               :when (pos? q)]
           (* q quantum)))))

(defalgo layer "One layer of several parallel ones (wrapping)."
  {:algo {:in [:layers] :out :any
          :params {:index {:type :int :min 0 :max 63 :default 0 :doc "which layer"}}}}
  [layers index] (let [v (vec layers)] (nth v (mod index (count v)))))

(defalgo axis "One coordinate of each point."
  {:algo {:in [:points] :out :numbers
          :params {:axis {:type :int :min 0 :max 2 :default 0 :doc "x = 0, y = 1, z = 2"}}}}
  [points axis] (mapv #(nth % axis) points))

(defalgo noise "Smooth value noise: n random points, blended, sampled :len times."
  {:algo {:in [] :out :numbers
          :params {:n   {:type :int :min 2 :max 64 :default 8 :doc "random points blended"}
                   :lo  {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "lowest value"}
                   :hi  {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "highest value"}
                   :len {:type :int :min 1 :max 1024 :default 16 :doc "how many values"}}}}
  [n lo hi len]
  (let [f (random/smooth-noise n lo hi)]
    (mapv #(f (* % (/ (dec n) (double (max 1 (dec len)))))) (range len))))

(defn- pitch-text
  "A MIDI int -> absolute musics pitch text, always with the '/' after
   the octave digit (without it, a following duration digit reparses as
   part of a wrong octave -- see input.abc-import/note->pitch-text)."
  [midi]
  (let [{:keys [letter accidental octave]} (lp/midi->spelling midi)]
    (when-not (<= 1 octave 8)
      (throw (ex-info (str "notes->mus: MIDI " midi " is outside musics text's octaves 1-8 (MIDI 24-119)") {})))
    (str (str/upper-case letter) accidental octave "/")))

(defn- duration-text [r]
  (let [r (rationalize r)]
    (if (and (integer? r) (> r 1)) (str "1*" r "/1") (abc/duration->mus r))))

(defn notes->mus
  "Leaf/Rest maps -> one musics text Sequence: absolute pitches, explicit
   durations, and !accidentals:explicit so its meaning never depends on
   the key it's later committed under."
  [parts]
  (str "[ !accidentals:explicit "
       (str/join " "
                 (for [n parts
                       :let [dur (duration-text (:duration n))]]
                   (cond
                     (d/rest? n)                (str "r" dur)
                     (= 1 (count (:pitches n))) (str (pitch-text (first (:pitches n))) dur)
                     :else (str "<" (str/join " " (map pitch-text (:pitches n))) ">" dur))))
       " ]"))
