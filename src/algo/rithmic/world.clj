;; world.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 19-20 -- the Indian tala system (matra/vibhag structure, theka
;; stroke patterns, konnakol) and West African timeline patterns (bell
;; patterns, cross-rhythms, polyrhythm, djembe). The reference's Tala
;; class carries no real mutable state -- its own "instance data" is
;; just a name plus a lookup into a fixed table -- so this port is a
;; data table plus plain functions taking a tala name, not a class.

(ns algo.rithmic.world
  (:require [algo.random :as rand]))

(def common-talas
  "name -> {:matras :vibhags :tali-khali}, the standard Indian talas."
  {"teental" {:matras 16 :vibhags [4 4 4 4]       :tali-khali ["T" " " "T" " "]}
   "jhaptal" {:matras 10 :vibhags [2 3 2 3]       :tali-khali ["T" " " "T" " "]}
   "rupak"   {:matras 7  :vibhags [3 2 2]         :tali-khali [" " "T" "T"]}
   "ektal"   {:matras 12 :vibhags [2 2 2 2 2 2]   :tali-khali ["T" " " "T" " " "T" " "]}})

(defn- tala-structure [tala-name] (get common-talas tala-name (get common-talas "teental")))

(defn tala-pattern
  "The full matra-by-matra structure of tala-name's own cycle, at tempo
   multiplier laya (1.0 = normal): a seq of {:matra :vibhag :accent
   :time :tali-khali} maps, one per matra (1-indexed), accent 3 on sam
   (the cycle's own first beat), 2 on a tali (clap) vibhag's own first
   matra, 1 on a khali (wave, blank tali-khali entry) vibhag's first
   matra, 0 elsewhere."
  ([tala-name] (tala-pattern tala-name 1.0))
  ([tala-name laya]
   (let [{:keys [vibhags tali-khali]} (tala-structure tala-name)]
     (vec (mapcat
           (fn [[vibhag-idx vibhag-length]]
             (let [tali-type (get tali-khali vibhag-idx " ")]
               (map (fn [j]
                      (let [matra-idx (+ (reduce + (take vibhag-idx vibhags)) j)
                            accent (cond
                                     (zero? matra-idx) 3
                                     (not (zero? j)) 0
                                     (= tali-type "T") 2
                                     (= tali-type " ") 1
                                     :else 0)]
                        {:matra (inc matra-idx) :vibhag (inc vibhag-idx) :accent accent
                         :time (/ matra-idx laya) :tali-khali tali-type}))
                    (range vibhag-length))))
           (map-indexed vector vibhags))))))

(defn theka-pattern
  "Simplified binary stroke pattern (theka) for tala-name. instrument
   \"tabla\" (default) uses hand-transcribed patterns for teental and
   jhaptal, falling back to \"accent the first matra of every vibhag\"
   for any other tala; any other instrument name (e.g. \"mridangam\")
   alternates on/off every other matra."
  ([tala-name] (theka-pattern tala-name "tabla"))
  ([tala-name instrument]
   (let [{:keys [matras vibhags]} (tala-structure tala-name)]
     (if (= instrument "tabla")
       (case tala-name
         "teental" [1 1 1 1 1 1 1 1 1 0 0 1 1 1 1 1]
         "jhaptal" [1 0 1 1 0 1 0 1 1 0]
         (loop [pattern (vec (repeat matras 0)) pos 0 [v & more] vibhags]
           (if (nil? v)
             pattern
             (recur (assoc pattern pos 1) (+ pos v) more))))
       (mapv #(if (even? %) 1 0) (range matras))))))

(defn konnakol-pattern
  "South Indian vocal-percussion syllables for pattern (a binary rhythm,
   default a fixed 8-beat example): syllables (default [\"Ta\" \"Ka\"
   \"Di\" \"Mi\" \"Tom\" \"Nam\"]) cycle across the ON beats, in order;
   OFF beats become \"-\"."
  ([] (konnakol-pattern ["Ta" "Ka" "Di" "Mi" "Tom" "Nam"] [1 0 1 0 1 1 0 1]))
  ([syllables pattern]
   (let [syllables (vec syllables)]
     (first (reduce (fn [[out idx] beat]
                       (if (pos? beat)
                         [(conj out (nth syllables (mod idx (count syllables)))) (inc idx)]
                         [(conj out "-") idx]))
                     [[] 0] pattern)))))

;; ── West African timeline ────────────────────────────────────

(def named-bell-patterns
  {"standard"   [1 0 1 0 1 0 1 0 1 0 1 0]
   "clave"      [1 0 0 1 0 0 1 0 0 0 1 0 0 1 0 0]
   "bossanova"  [1 0 0 1 0 0 1 0 0 0 1 0 1 0 0 0]
   "funk"       [1 0 0 1 0 1 0 0 1 0 0 1 0 1 0 0]
   "ghanian"    [1 0 1 1 0 1 0 1 1 0 1 0]})

(defn bell-pattern
  "West African bell (timeline) pattern for meter [pulses subdivision],
   either one of the named patterns (\"standard\"/\"clave\"/\"bossanova\"/
   \"funk\"/\"ghanian\") or, for any other name, a simple pattern with a
   beat every (subdivision/2) pulses -- truncated or cyclically
   repeated as needed to come out exactly pulses long."
  ([] (bell-pattern [12 8] "standard"))
  ([meter pattern-name]
   (let [[pulses subdivision] meter
         base (get named-bell-patterns pattern-name
                    (mapv #(if (zero? (mod % (quot subdivision 2))) 1 0) (range pulses)))
         n (count base)]
     (cond
       (> n pulses) (subvec base 0 pulses)
       (< n pulses) (vec (take pulses (cycle base)))
       :else base))))

(defn cross-rhythm-3-2
  "Classic 3:2 cross-rhythm (hemiola): two layers over length pulses,
   one marking every length/3 pulses (triple meter), the other every
   length/2 (duple meter)."
  ([] (cross-rhythm-3-2 12))
  ([length]
   [(mapv #(if (zero? (mod % (quot length 3))) 1 0) (range length))
    (mapv #(if (zero? (mod % (quot length 2))) 1 0) (range length))]))

(defn african-polyrhythm
  "layers interlocking rhythmic patterns of base-length pulses each: the
   first four layers use the classic African polyrhythm ratios 3:2,
   4:3, 5:4, 7:4 (num beats evenly spaced across base-length); any
   further layer gets a random ratio (2-7 : 2-7). Each layer also has a
   ~50% chance, independently at each of its own secondary beat
   positions, of adding an extra beat halfway to the next primary one."
  ([] (african-polyrhythm 3 12))
  ([layers base-length]
   (let [ratios [[3 2] [4 3] [5 4] [7 4]]]
     (mapv (fn [i]
             (let [[num _den] (if (< i (count ratios))
                                 (nth ratios i)
                                 [(rand/int-range 2 8) (rand/int-range 2 8)])
                   step (quot base-length num)
                   primary (map (fn [j] (mod (* j step) base-length)) (range num))
                   secondary (keep (fn [j]
                                      (when (and (pos? j) (rand/weighted-coin 0.5))
                                        (mod (+ (* j step) (quot step 2)) base-length)))
                                    (range 1 num))]
               (reduce #(assoc %1 %2 1) (vec (repeat base-length 0)) (concat primary secondary))))
           (range layers)))))

(defn djembe-pattern
  "Djembe stroke sequence for technique \"basic\" (a fixed 8-stroke
   bass/tone/slap pattern, 0.5s apart), \"solo\" (length random strokes
   at 0.25s apart with a 4-beat accent cycle), or \"accompaniment\" (the
   default fallback -- a steady bass/tone alternation, 0.5s apart).
   Each event is {:time :stroke :accent}."
  ([] (djembe-pattern "basic" 8))
  ([technique length]
   (case technique
     "basic"
     (let [strokes ["B" "T" "S" "T" "B" "T" "S" "T"]]
       (mapv (fn [i stroke] {:time (* i 0.5) :stroke stroke :accent (if (even? i) 1 0)})
             (range (count strokes)) strokes))

     "solo"
     (mapv (fn [i]
             {:time (* i 0.25)
              :stroke (rand/choose ["B" "T" "S"])
              :accent (cond (zero? (mod i 4)) 2 (even? i) 1 :else 0)})
           (range length))

     (mapv (fn [i]
             (if (even? i)
               {:time (* i 0.5) :stroke "B" :accent 0}
               {:time (* i 0.5) :stroke "T" :accent 0}))
           (range length)))))

(comment
  (theka-pattern "teental")
  (theka-pattern "jhaptal")
  (tala-pattern "teental")
  (konnakol-pattern)
  (bell-pattern [12 8] "standard")
  (cross-rhythm-3-2 12)
  (african-polyrhythm 3 12)
  (djembe-pattern "basic" 8)
  )
