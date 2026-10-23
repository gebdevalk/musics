(ns musics.algo.tree.lib
  "Ready-made algos for musics.algo.tree trees. Each name here is a node
   constructor; (musics.algo.tree/algos) lists them all with their params.

     (def riff (zip (pulses->durations euclid) (cycle> scale)))
     (musics.algo.tree/run riff {:k 5})

   Exposed from musics/algo/ -- every fn carrying :algo metadata in these
   namespaces (the full list with params: (musics.algo.tree/algos)):
     musics.algo.rhythmic.*   euclid fibonacci primes cantor dragon bell tala drums
                       polyrhythm sieve necklace swing genetic ca ...
     musics.algo.metric       bits cfrac modular
     musics.algo.melodic.*    infra inter ultra polations markov-train
                       markov-gen lsys-melody grammar constrained
                       modulating counterpoint row row-form (serial)
     musics.algo.random.*     samplers (normal uniform triangular ... -- :len
                       draws), walks (walk glide cyclic chain), pink, tendency, logistic
                       henon lorenz, poisson sputter choose-n ...
     musics.algo.indispensability  indisp tilt power
   Defined here:
     scale        :root :intervals     -> :pitch (root + offsets)
   Tools, within one type, any material (marked > so they never shadow
   clojure.core):
     cycle> shuffle>                   repeat forever / reshuffle every pass
     take>        :len                 first :len items
     map> filter> :fn                  each item through a fn / the items a fn keeps
     remove>      :fn                  the items a fn doesn't drop
     mute>        :fn                  leaves: the notes a fn is true for become rests
     scale>       :from-lo .. :to-hi   numbers from one range onto another
     stretch>     :factor              durations or notes, each duration times :factor
     transpose    :semitones           pitches, chords, rests or notes; (transpose :nodes) is a live transform
     pick                              :weight -> one weighted :index
   Leaves, the product, assembled from half products (musics.algo.bridge
   makes those from raw material):
     zip                               :duration :pitch -> leaves: notes, chords, rests
     +volume +articulation +instrument :leaf + that material -> :leaf
     +override    :key                 :leaf :number -> each note's own value for :key
     parts +part                       leaves in parallel -> :part; part :index picks one back
   Stream bridges, raw material -> half product (musics.algo.bridge, category bridge):
     pulses->durations strokes->durations onsets->durations numbers->durations points->durations
     degrees->pitches numbers->pitches points->pitches
     numbers->volumes points->volumes weights->volumes weights->articulations weights->pulses
   Specialised: every source of raw material, as each half product a
   bridge makes of it (musics.algo.tree.half) -- pitch/lorenz,
   duration/euclid, volume/walk, ... in musics.algo.pitch, .duration,
   .volume and .articulation.
   Raw to raw:
     threshold    :level               :number -> :pulse (onset above :level of the range)
     layer        :index               :layer -> one :pulse layer
     axis         :axis                :point -> :number (one coordinate)
     noise        :n :lo :hi :len      smooth value noise -> :number

   And one plain function: (notes->mus parts) renders Leaf/Rest maps as
   musics text, ready to read, edit, or commit with musics.core/parse."
  (:require [musics.algo.tree :refer [defalgo expose-ns]]
            [musics.algo.tree.half :as half]
            [musics.algo.random :as random]
            [musics.common.music-data :as data :refer [quantity]]
            [musics.compose :as compose]
            [musics.domain :as d]
            [clojure.string :as str]
            [musics.input.reader.leaf-parser :as lp]))

(expose-ns musics.algo.bridge
           musics.algo.indispensability
           musics.algo.logic.counterpoint
           musics.algo.metric
           musics.algo.melodic.counterpoint musics.algo.melodic.melody musics.algo.melodic.serial
           musics.algo.random musics.algo.random.henon musics.algo.random.logistic musics.algo.random.lorenz
           musics.algo.rhythmic.automaton musics.algo.rhythmic.constraint musics.algo.rhythmic.decompose musics.algo.rhythmic.drums musics.algo.rhythmic.fractal-geometric
           musics.algo.rhythmic.micro musics.algo.rhythmic.necklace musics.algo.rhythmic.phase-sieve
           musics.algo.rhythmic.physical musics.algo.rhythmic.poly musics.algo.rhythmic.rhythm
           musics.algo.rhythmic.sonification musics.algo.rhythmic.stochastic musics.algo.rhythmic.transform
           musics.algo.rhythmic.world)

(defn ->part
  "nil -> Rest, an int -> single-pitch Leaf, a collection -> chord Leaf.
   A Leaf/Rest map passes through unchanged."
  [pitch dur]
  (cond
    (nil? pitch)                     (d/rest* nil nil dur)
    (and (map? pitch) (:type pitch)) pitch
    (coll? pitch)                    (d/leaf nil nil dur (vec pitch))
    :else                            (d/leaf nil nil dur [pitch])))

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

;; -- tools: within one type, any material ------------------------------------

(defalgo cycle> "Its child, repeated forever (lazy)."
  {:algo {:category "tool" :in [:any] :out :same}}
  [xs] (cycle xs))

(defalgo shuffle> "Its child, reshuffled on every pass, forever (lazy)."
  {:algo {:category "tool" :in [:any] :out :same}}
  [xs] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))

(defalgo take> "The first :len items of its child."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:len {:type :int :min 0 :max 256 :default 16 :doc "items kept"}}}}
  [xs len] (vec (take len xs)))

(defalgo map> "Each item through :fn (set at the REPL), lazily; the type stays."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default identity :doc "applied to each item"}}}}
  [xs fn] (map fn xs))

(defalgo filter> "The items :fn (set at the REPL) keeps, lazily."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default some? :doc "true keeps the item"}}}}
  [xs fn] (filter fn xs))

(defalgo remove> "The items :fn (set at the REPL) doesn't drop, lazily: on leaves the line gets shorter, later notes move earlier."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default nil? :doc "true drops the item"}}}}
  [xs fn] (remove fn xs))

(defalgo mute> "Each note :fn (set at the REPL) is true for becomes a rest of its duration, lazily: the rhythm stays. Rests stay."
  {:algo {:category "tool" :in [:leaf] :out :leaf
          :params {:fn {:type :fn :default (constantly false) :doc "true silences the note"}}}}
  [leaves fn] (map #(if (and (not (d/rest? %)) (fn %)) (d/rest* nil nil (:duration %)) %) leaves))

(defalgo scale> "Each number's place in :from-lo..:from-hi onto :to-lo..:to-hi, lazily (not clamped): a factor is 0..1 onto 0..factor."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:from-lo {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "maps to :to-lo"}
                   :from-hi {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "maps to :to-hi"}
                   :to-lo   {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "lowest out"}
                   :to-hi   {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "highest out"}}}}
  [xs from-lo from-hi to-lo to-hi]
  (let [span (- from-hi from-lo)
        k    (if (zero? span) 0.0 (/ (- to-hi to-lo) (double span)))]
    (map #(+ to-lo (* k (- % from-lo))) xs)))

(defalgo transpose "Shift pitches, chords or notes; rests stay."
  {:algo {:category "shape" :in [:any] :out :same
          :params {:semitones (quantity :semitones {:doc "shift"})}}}
  [xs semitones] (map (partial shift semitones) xs))

(defn- stretched [factor x]
  (cond (number? x)   (* factor x)
        (:duration x) (update x :duration #(* factor %))
        :else         x))

(defalgo stretch> "Every duration times :factor -- numbers, or notes' :duration; anything else stays."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:factor (quantity :ratio {:doc "duration multiplier"})}}}
  [xs factor] (map (partial stretched factor) xs))

(defalgo pick "One index, drawn with the child's weights."
  {:algo {:category "shape" :in [:weight] :out :index}}
  [ws] (random/weighted-choose (vec (range (count ws))) ws))

;; -- leaves: the product, assembled from half products ------------------------

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

(defalgo zip "Durations and pitches zipped into leaves: a note, a chord (several pitches), or a rest (nil pitch, or a Rest in the durations, which uses no pitch). Ends with the shorter -- cycle a source for an isorhythm."
  {:algo {:category "output" :in [:duration :pitch] :out :leaf}}
  [durs pitches] (zip-skipping-rests (fn [dur p] (->part p dur)) durs pitches))

(defn- override
  "p with its own value v for context key k (written c4\\k:v); nil leaves it."
  [p k v]
  (if (some? v) (assoc-in p [:overrides k] v) p))

(def ^:private overridable
  "The numeric context keys playback reads per note (musics.domain.resolve/played-keys)."
  [:panning :transposition :octave :Tempo :durScale :micro :humanization :swing :swingUnit :volume :instrument])

(defalgo +override "Each note's own value for a context key it plays with (as c4\\pan:-1.0 writes it): :key picks which. Rests take none."
  {:algo {:category "output" :in [:leaf :number] :out :leaf
          :params {:key {:type :keyword :default :panning :choices overridable :doc "the context key overridden"}}}}
  [parts xs key] (zip-skipping-rests #(override %1 key %2) parts xs))

(defalgo +volume "Each note's own volume (the !vol: 0-100 scale, written c4\\vol:90); rests take none."
  {:algo {:category "output" :in [:leaf :volume] :out :leaf}}
  [parts volumes] (zip-skipping-rests #(override %1 :volume %2) parts volumes))

(defalgo +articulation "Each note's articulation, a name from musics.common.music-data/articulations (as c4-> writes :accent); nil plays plain. Rests take none."
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

(defalgo +instrument "Each note's instrument (written c4\\i:40): a MIDI program (0-127), a General MIDI name or its short name. A drum has none: its sound is a key of the kit (+drum). Rests take none."
  {:algo {:category "output" :in [:leaf :instrument] :out :leaf}}
  [parts instruments]
  (zip-skipping-rests
   (fn [p i]
     (cond (nil? i) p
           (d/drum? p) (throw (ex-info "+instrument: a drum has no instrument -- its sound is a key of the kit (+drum)" {:instrument i}))
           :else (override p :instrument (or (when (number? i) (int i)) (data/program-of i)
                                             (throw (ex-info (str "+instrument: not a program or General MIDI name: " (pr-str i)) {:instrument i}))))))
   parts instruments))

(defalgo +drum "Each note made a drum (written x8\\38): a key of the kit (35-81) or a drum's name (kick, bd1, hhc), keeping its length, articulation weight and own settings; a drum gets the new key. Rests take none."
  {:algo {:category "output" :in [:leaf :drum] :out :leaf}}
  [parts drums]
  (zip-skipping-rests
   (fn [p k]
     (if (nil? k)
       p
       (let [key (or (when (number? k) (int k)) (data/resolve-drum (name k))
                     (throw (ex-info (str "+drum: not a drum: " (pr-str k)) {:drum k})))]
         (if (d/drum? p)
           (assoc p :program key)
           (merge (d/drum nil nil (:duration p) key) (select-keys p [:dynamic])
                  {:overrides (dissoc (:overrides p) :instrument)})))))
   parts drums))

;; -- bridges between types ----------------------------------------------------

(defn- unit-scaled
  "xs rescaled so its own min..max spans 0..1 (all 0.0 when flat)."
  [xs]
  (let [xs (vec xs) lo (apply min xs) span (- (apply max xs) lo)]
    (mapv #(if (zero? span) 0.0 (/ (- % lo) (double span))) xs)))

(defalgo threshold "An onset where a number is above :level of its range."
  {:algo {:category "bridge" :in [:number] :out :pulse
          :params {:level {:type :double :min 0.0 :max 1.0 :default 0.5 :doc "0 = lowest, 1 = highest"}}}}
  [xs level] (mapv #(if (> % level) 1 0) (unit-scaled xs)))

(defn- nth-wrapping [xs index] (let [v (vec xs)] (when (seq v) (nth v (mod index (count v))))))

(defalgo layer "One of several pulse layers meant to sound together (wrapping) -- name each pick, (layer polyrhythm :as :top), so each has its own :index."
  {:algo {:category "tool" :in [:layer] :out :pulse
          :params {:index {:type :int :min 0 :max 63 :default 0 :doc "which layer"}}}}
  [layers index] (nth-wrapping layers index))

(defalgo part "One of several parallel parts (wrapping): a stream of leaves."
  {:algo {:category "tool" :in [:part] :out :leaf
          :params {:index {:type :int :min 0 :max 63 :default 0 :doc "which part"}}}}
  [parts index] (nth-wrapping parts index))

(defalgo parts "Two streams of leaves played together, as parallel parts."
  {:algo {:category "output" :in [:leaf :leaf] :out :part}}
  [a b] (compose/par (vec a) (vec b)))

(defalgo +part "One more stream of leaves, alongside the parts."
  {:algo {:category "output" :in [:part :leaf] :out :part}}
  [ps leaves] (apply compose/par (conj (vec ps) (vec leaves))))

(defalgo axis "One coordinate of each point."
  {:algo {:category "bridge" :in [:point] :out :number
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

(half/register-all!)
