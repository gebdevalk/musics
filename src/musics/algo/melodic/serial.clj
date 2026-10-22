(ns musics.algo.melodic.serial
  "Twelve-tone rows and pitch-class sets (Dodge & Jerse, Computer Music,
   8.2). A row is raw material, its pitch classes as numbers 0-11, so the
   bridges serialise any parameter with it: classes->pitches as pitches,
   numbers->durations/numbers->volumes as durations and volumes (total
   serialism), time-points->durations as Babbitt's time points.

     (row :random)                  a random row: (3 10 0 ...)
     (row :all-interval)            every interval 1-11 once between neighbours
     (row-form r :RI 5)             one of its 48 forms
     (matrix r)                     the 12x12 table: P forms across, I down
     (combinatorial r)              forms whose first hexachord completes r's
     (prime-form [0 4 7])           [0 3 7], the set class
     (interval-vector [0 4 7])      [0 0 1 1 1 0]"
  (:require [musics.algo.random :as random]
            [musics.common.music-data :refer [quantity]]))

(defn- pc [x] (mod (long (Math/round (double x))) 12))

(defn- all-interval
  "A random all-interval row from 0: a depth-first search, its choices
   shuffled, so a seed repeats it."
  []
  (letfn [(grow [r used ivs]
            (if (= 12 (count r))
              r
              (some (fn [iv]
                      (let [p (mod (+ (peek r) iv) 12)]
                        (when-not (or (used p) (ivs iv))
                          (grow (conj r p) (conj used p) (conj ivs iv)))))
                    (random/shuffle (range 1 12)))))]
    (grow [0] #{0} #{})))

(defn row
  "A twelve-tone row as pitch classes 0-11: :random any order of the
   twelve, :all-interval one whose eleven intervals between neighbours
   are all different (always starting on 0; row-form transposes it)."
  {:algo {:in [] :out :number
          :params {:kind {:type :keyword :choices [:random :all-interval] :default :random
                          :doc "any row, or one with every interval once"}}}}
  [kind]
  (if (= :all-interval kind) (all-interval) (vec (random/shuffle (range 12)))))

(defn row-form
  "One of a row's 48 forms: :P as written, :I inverted around its first
   class, :R backwards, :RI both, each transposed by n semitones."
  {:algo {:category "tool" :works :whole :in [:number] :out :number
          :params {:form {:type :keyword :choices [:P :I :R :RI] :default :P :doc "prime, inversion, retrograde, both"}
                   :n    {:type :int :min 0 :max 11 :default 0 :doc "transposition, semitones"}}}}
  [xs form n]
  (let [r (mapv pc xs)
        f (first r)
        p (fn [x] (mod (+ x n) 12))
        i (fn [x] (mod (+ (- (* 2 f) x) n) 12))]
    (case form
      :P  (mapv p r)
      :I  (mapv i r)
      :R  (vec (rseq (mapv p r)))
      :RI (vec (rseq (mapv i r))))))

(defn classes->pitches
  "Each pitch class (a number, rounded, mod 12) as a pitch in the octave
   from :root up."
  {:algo {:category "bridge" :works :value :in [:number] :out :pitch
          :params {:root (quantity :pitch {:default 60 :doc "lowest pitch"})}}}
  [xs root]
  (map #(+ root (mod (- (pc %) root) 12)) xs))

(defn time-points->durations
  "Babbitt's time points: each pitch class is where its note starts in a
   bar of twelve :units, lasting until the next one starts (a whole bar
   when they're equal); the last lasts until the first comes round."
  {:algo {:category "bridge" :works :shape :in [:number] :out :duration
          :params {:unit (quantity :note-value {:default 1/16 :doc "one step of the twelve"})}}}
  [xs unit]
  (let [xs (map pc xs)]
    (map (fn [a b] (* unit (let [d (mod (- b a) 12)] (if (zero? d) 12 d))))
         xs (concat (rest xs) [(first xs)]))))

(defn matrix
  "The 12x12 matrix of a row: row i is P transposed so it starts on the
   i-th class of I, so the rows read P forms left to right (R right to
   left) and the columns I forms down (RI up)."
  [r]
  (let [r (mapv pc r) f (first r)]
    (vec (for [a r] (mapv #(mod (+ % (- f a)) 12) r)))))

(defn combinatorial
  "The P and I forms whose first hexachord is the complement of r's
   first: [[:P n] [:I n] ...]. Each forms an aggregate with r, half
   against half (hexachordal combinatoriality)."
  [r]
  (let [hexa   #(set (take 6 %))
        target (reduce disj (set (range 12)) (hexa (map pc r)))]
    (vec (for [form [:P :I] n (range 12)
               :when (= target (hexa (row-form r form n)))]
           [form n]))))

(defn- normal-orders
  "Every rotation of a set's sorted classes, transposed to start on 0."
  [pcs]
  (let [s (vec (sort (distinct (map pc pcs)))) k (count s)]
    (for [i (range k)
          :let [rot (into (subvec s i) (map #(+ % 12) (subvec s 0 i)))]]
      (mapv #(- % (first rot)) rot))))

(defn prime-form
  "The prime form of a pitch-class set (Forte): the most packed of its
   rotations and those of its inversion, transposed to 0 -- the smallest
   span first, then the smallest intervals from the first class."
  [pcs]
  (when (seq pcs)
    (->> (concat (normal-orders pcs) (normal-orders (map #(- 12 (pc %)) pcs)))
         (sort-by (fn [v] (into [(peek v)] (rest v))) compare)
         first)))

(defn interval-vector
  "How many times each interval class 1-6 occurs between the set's classes."
  [pcs]
  (let [s (vec (sort (distinct (map pc pcs))))
        ics (for [i (range (count s)) j (range (inc i) (count s))
                  :let [d (- (s j) (s i))]]
              (min d (- 12 d)))]
    (mapv #(count (filter #{%} ics)) (range 1 7))))
