;; drums.clj
;; Style-authentic drum-kit grooves (rock, funk, hip-hop, jazz, house,
;; metal, ...), built in four strata -- backbone (kick/snare), time-
;; keeping (hats/ride), ghost notes, and a fill every 4th bar.

(ns algo.rhythmic.drums
  (:require [algo.random :as random]
            [algo.random.core :as rcore]
            [common.music-data :as data]
            [core.compose :as compose]
            [core.domain.flat-domain :as d]))

(def kit
  "The kit pieces, in layer order, each a common.music-data drum name."
  [[:kick "kick"] [:snare "snare"] [:rim "ss"] [:clap "clap"]
   [:closed-hat "hhc"] [:pedal-hat "hhp"] [:open-hat "hho"]
   [:tom-hi "tom-hi"] [:tom-mid "tom-mid"] [:tom-lo "tom-lo"]
   [:crash "crash"] [:ride "ride"]])

(def styles [:rock :pop :funk :hiphop :trap :jazz :house :techno :metal])

;; A hit is {:drum piece :at whole-notes-into-the-bar :vel 1-127}; a bar
;; of 4/4 is 1 whole note.

(defn- hit [drum at vel] {:drum drum :at at :vel (int vel)})

(defn- r [rng] (random/rand-double rng))
(defn- chance [rng p] (< (r rng) p))

(defn- backbone [style rng]
  (case style
    (:rock :pop)     [(hit :kick 0 110) (hit :kick 1/2 105) (hit :snare 1/4 110) (hit :snare 3/4 110)]
    :funk            [(hit :kick 0 115) (hit :kick 3/8 95) (hit :kick 5/8 90)
                      (hit :snare 1/4 112) (hit :snare 3/4 112)]
    (:hiphop :trap)  (cond-> [(hit :kick 0 118) (hit :snare 1/4 115) (hit :snare 3/4 115)]
                       (chance rng 0.6) (conj (hit :kick 5/8 100)))
    :jazz            (cond-> []
                       (chance rng 0.7) (conj (hit :kick 0 85))
                       (chance rng 0.4) (conj (hit :snare 5/16 70)))
    (:house :techno) (into [(hit :clap 1/4 110) (hit :clap 3/4 110)]
                           (for [b (range 4)] (hit :kick (/ b 4) 120)))
    :metal           (into [(hit :snare 1/4 120) (hit :snare 3/4 120)]
                           (for [i (range 16)] (hit :kick (/ i 16) 120)))))

(defn- time-keeping [style density rng]
  (case style
    (:rock :pop)     (cond-> (vec (for [i (range 8)] (hit :closed-hat (/ i 8) (if (even? i) 95 70))))
                       (chance rng 0.3) (conj (hit :open-hat 7/8 80)))
    :funk            (conj (vec (for [i (range 16)] (hit :closed-hat (/ i 16) (if (zero? (mod i 4)) 100 60))))
                           (hit :open-hat 7/8 85))
    (:hiphop :trap)  (concat (for [i (range 16) :when (chance rng density)]
                               (hit :closed-hat (/ i 16) (+ 50 (* 40 (r rng)))))
                             (when (chance rng 0.4)             ; a 32nd-note roll on beat 4
                               (for [i (range 8)]
                                 (hit :closed-hat (+ 3/4 (/ i 32)) (+ 40 (* 30 (r rng)))))))
    ;; spang-a-lang: the skip is the last triplet eighth of beats 2 and 4
    :jazz            [(hit :ride 0 90) (hit :ride 1/4 70) (hit :ride 5/12 80)
                      (hit :ride 1/2 90) (hit :ride 3/4 70) (hit :ride 11/12 80)
                      (hit :pedal-hat 1/4 60) (hit :pedal-hat 3/4 60)]
    (:house :techno) (for [b (range 4)] (hit :open-hat (+ (/ b 4) 1/8) 75))
    :metal           (for [i (range 16)] (hit :closed-hat (/ i 16) 90))))

(defn- ghosts [style density rng]
  (case style
    (:funk :hiphop :rock :trap)
    (for [p (range 1 16 2) :when (chance rng (* density 0.55))]
      (hit :snare (/ p 16) (+ 30 (* 25 (r rng)))))
    :jazz (when (chance rng 0.35) [(hit :snare (random/choose rng [1/8 3/8 5/8]) 55)])
    nil))

(defn- fill
  "A fill over the second half of the bar; its crash (and kick) land on
   the next downbeat, at `next`."
  [style next]
  (case style
    (:rock :pop :metal)
    [(hit :tom-hi 1/2 100) (hit :tom-hi 9/16 95) (hit :tom-mid 5/8 100) (hit :tom-mid 11/16 95)
     (hit :tom-lo 3/4 105) (hit :tom-lo 13/16 100) (hit :snare 7/8 110)
     (hit :crash next 120) (hit :kick next 120)]
    (:funk :hiphop :trap)
    (conj (vec (for [i (range 8)] (hit :snare (+ 1/2 (/ i 16)) (+ 40 (* i 9)))))
          (hit :crash next 110))
    :jazz [(hit :snare 1/2 80) (hit :tom-hi 5/8 75) (hit :tom-lo 3/4 85)]
    nil))

(defn- swung
  "Delay every hit on an off-beat 16th by swing x a 32nd."
  [swing at]
  (let [x (* at 16)]
    (if (and (integer? x) (odd? x)) (+ at (* (rationalize swing) 1/32)) at)))

(defn- velocity->dynamic
  "A hit's 1-127 velocity as a Drum's :dynamic, an offset on the 0-100
   volume scale: 100 plays at the context's volume, softer or harder
   hits half a volume step per velocity step below or above it."
  [vel]
  (/ (- vel 100) 2.0))

(defn- piece-layer
  "One piece's hits (sorted, one per onset) as Drum/Rest maps filling
   `total` whole notes."
  [program hits total]
  (let [onsets (map :at hits)
        lead   (first onsets)]
    (vec (concat (when (pos? lead) [(d/rest* nil nil lead)])
                 (map (fn [{:keys [at vel]} next]
                        (assoc (d/drum nil nil (- next at) program) :dynamic (velocity->dynamic vel)))
                      hits (concat (rest onsets) [total]))))))

(defn drum-pattern
  "A drum-kit groove of :bars bars of 4/4 in :style, as parallel layers,
   one per kit piece that plays (kick, snare, rim, clap, hats, toms,
   crash, ride -- in that order): each a vector of Drum/Rest maps, the
   whole a par group ready for play. :density thins the hip-hop hats and
   the ghost notes; :swing delays the off-beat 16ths by up to a 32nd.
   Every 4th bar ends in a fill whose crash lands on the next downbeat
   (bar 1's, after the last bar, so the pattern loops). A hit's velocity
   rides on the context's volume as the Drum's :dynamic; timing and
   velocity spread come from the :humanization context key.

   (drum-pattern :funk 8 0.75 0.2 1)"
  {:algo {:short :drums :in [] :out :layers
          :params {:style   {:type :keyword :default :rock :choices styles :doc "the groove's style"}
                   :bars    {:type :int :min 1 :max 64 :default 8 :doc "bars of 4/4"}
                   :density {:type :double :min 0.0 :max 1.0 :default 0.7 :doc "how many hats and ghost notes"}
                   :swing   {:type :double :min 0.0 :max 1.0 :default 0.0 :doc "off-beat 16ths late by this x a 32nd"}
                   :seed    {:type :int :min 0 :max 1000000 :default 1 :doc "the random choices (reproducible)"}}}}
  [style bars density swing seed]
  (let [rng      (doto (atom nil) (rcore/seed! seed))
        hits     (for [bar (range bars)
                       h   (concat (backbone style rng)
                                   (time-keeping style density rng)
                                   (ghosts style density rng)
                                   (when (zero? (mod (inc bar) 4)) (fill style 1)))]
                   (update h :at #(mod (+ bar (swung swing %)) bars)))
        by-piece (group-by :drum hits)]
    (apply compose/par
           (for [[piece name] kit
                 :let [hs (get by-piece piece)]
                 :when hs]
             (piece-layer (data/resolve-drum name)
                    (->> hs (sort-by :at) (partition-by :at) (map #(apply max-key :vel %)))
                    bars)))))
