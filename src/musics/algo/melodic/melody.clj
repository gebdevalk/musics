;; Melodic generators: Markov, L-system and grammar melodies, and constraint-satisfaction walks.

(ns musics.algo.melodic.melody
  (:require [musics.algo.melodic.pitch :as pitch]
            [musics.algo.numeric.lsystem :as lsystem]
            [musics.algo.numeric.random :as rand]))

;; The scales are plain pitch-class integers (0-11), built with pitch/from-key from
;; musics.common.music-elements/scale-steps. These three are conveniences: from-key reaches 312 named
;; key and scale combinations, e.g. (pitch/from-key :F# :dorian); from-key-spec takes one string,
;; (pitch/from-key-spec "B&.blues-minor"); and every generator here accepts any of them, or a built
;; scale vector, through pitch/resolve-scale (see modulating).
(def c-major      (pitch/from-key :C :major))
(def a-minor      (pitch/from-key :A :minor))
(def c-pentatonic (pitch/from-key :C :pentatonic-major))

;; Every function here draws from musics.algo.numeric.random, so a seed repeats it.

;; ── Markov Chain Melody ─────────────────────────────────────

(declare no-repeat-constraint)

(def ^:private length-spec {:type :int :min 1 :max 256 :default 16 :doc "notes"})

(defn markov-train
  "A Markov model of melody: each run of `order` notes -> what followed it."
  {:algo {:in [:pitch] :out :model
          :params {:order {:type :int :min 1 :max 4 :default 1 :doc "notes of memory"}}}}
  [melody order]
  (let [pairs (for [i (range (- (count melody) order))]
                [(vec (subvec (vec melody) i (+ i order)))
                 (nth melody (+ i order))])]
    {:order order
     :transitions (reduce (fn [m [s nxt]]
                            (update m s #(conj (or % []) nxt)))
                          {} pairs)}))

(defn markov-gen
  "A melody of `length` notes walked through a markov-train model."
  {:algo {:in [:model] :out :pitch
          :params {:length length-spec
                   :opening {:type :any :default nil :doc "the opening notes, nil = random"}}}}
  [model length & {:keys [opening]}]
  (let [{:keys [order transitions]} model
        opening (or opening (first (rand/shuffle (keys transitions))))]
    (loop [melody (vec opening) state opening]
      (if (>= (count melody) length)
        (vec (take length melody))
        (if-let [choices (seq (get transitions state))]
          (let [nxt (rand/choose choices)]
            (recur (conj melody nxt)
                   (vec (take-last order (conj melody nxt)))))
          ;; dead end: jump to a state seen in training and play all of
          ;; it, so the state is again the melody's last `order` notes
          (let [ns (rand/choose (vec (keys transitions)))]
            (recur (into melody ns) ns)))))))

;; ── L-System Melody ─────────────────────────────────────────

(defn lsys-melody
  "Expand axiom through rules `iterations` times; each character in
   note-map becomes that pitch, others are skipped."
  {:algo {:in [] :out :pitch
          :params {:axiom      {:type :string :default "A" :doc "starting string"}
                   :rules      {:type :map :default {"A" "AB" "B" "CA" "C" "A"} :doc "character -> replacement"}
                   :note-map   {:type :map :default {\A 60 \B 64 \C 67} :doc "character -> pitch"}
                   :iterations {:type :int :min 0 :max 12 :default 5 :doc "generations"}
                   :max-notes  {:type :int :min 1 :max 256 :default 16 :doc "notes kept"}}}}
  [axiom rules note-map iterations max-notes]
  (let [expanded (lsystem/expand axiom rules iterations)]
    (->> (keep (fn [c] (get note-map c)) expanded)
         (take max-notes) vec)))

;; ── Generative Grammar ──────────────────────────────────────

(defn grammar
  "Expand `start` through a generative grammar: rules maps a symbol to
   its productions (one chosen at random), terminals are kept as-is.
   \"REST\" terminals are dropped when remove-rests? is true."
  {:algo {:in [] :out :pitch
          :params {:rules {:type :map :doc "symbol -> productions"
                           :default {'S [['A 'B] ['B 'A 'S]]
                                     'A [[60 64] [67 64]]
                                     'B [[62 "REST"] [65 69 67]]}}
                   :terminals {:type :any :default #{60 62 64 65 67 69 "REST"} :doc "a set of terminal symbols"}
                   :start {:type :any :default 'S :doc "starting symbol"}
                   :max-depth {:type :int :min 1 :max 32 :default 10 :doc "expansion depth"}
                   :remove-rests? {:type :bool :default true :doc "drop \"REST\""}}}}
  [rules terminals & {:keys [start max-depth remove-rests?]
                      :or {start 'S max-depth 10 remove-rests? true}}]
  (letfn [(expand [sym depth]
            (if (or (terminals sym) (>= depth max-depth))
              [sym]
              (when-let [prods (seq (get rules sym))]
                (let [chosen (rand/choose prods)]
                  (mapcat #(expand % (inc depth)) chosen)))))]
    (let [raw (expand start 0)]
      (if remove-rests?
        (vec (remove #(= % "REST") raw))
        (vec raw)))))

;; ── Constraint Satisfaction ─────────────────────────────────

(defn constrained
  "A melody of `length` notes from scale: each note drawn from those
   every constraint (fn [melody note] -> bool) accepts, any scale note
   when none does."
  {:algo {:in [:pitch] :out :pitch
          :params {:length length-spec
                   :constraints {:type :vector :default [#'no-repeat-constraint]
                                 :doc "fns [melody note] -> bool, e.g. max-leap-constraint"}
                   :start {:type :any :default nil :doc "first note, nil = random"}}}}
  [scale length constraints & {:keys [start]}]
  (let [first-note (or start (rand/choose scale))]
    (loop [melody [first-note]]
      (if (>= (count melody) length)
        (vec melody)
        (let [valid (filter (fn [note]
                              (every? (fn [c] (c melody note)) constraints))
                            scale)
              candidates (if (empty? valid) scale valid)]
          (recur (conj melody (rand/choose candidates))))))))

(defn modulating
  "A melody across several scale segments: each [scale-spec length] pair in segments gets a constrained run of exactly length notes, the constraints shared. scale-spec is anything resolve-scale accepts (a scale vector, a [key-kw scale-kw] pair, a \"F#.major\" string). A segment starts on the previous segment's last note when the new scale contains it (a pivot tone, repeated once at the seam), else on constrained's own default start; so (count result) is the sum of the lengths.
     (modulating [[c-major 8] [\"A.minor\" 8]] [no-repeat-constraint])
     ;; => 16 notes, 8 in C major then 8 in A minor"
  {:algo {:in [] :out :pitch
          :params {:segments {:type :vector :default [[[:C :major] 8] [[:A :minor] 8]]
                              :doc "[scale-spec length] pairs; pitch classes 0-11"}
                   :constraints {:type :vector :default [#'no-repeat-constraint]
                                 :doc "fns [melody note] -> bool"}}}}
  [segments constraints]
  (loop [segs segments melody []]
    (if (empty? segs)
      melody
      (let [[scale-spec length] (first segs)
            scale (pitch/resolve-scale scale-spec)
            pivot (when (and (seq melody) (some #{(peek melody)} scale))
                    (peek melody))
            piece (constrained scale length constraints :start pivot)]
        (recur (rest segs) (into melody piece))))))

(defn max-leap-constraint [scale max-degrees]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (empty? melody) true
          (let [pi (.indexOf sv (last melody))
                ni (.indexOf sv note)]
            (<= (abs (- ni pi)) max-degrees))))))

(defn no-repeat-constraint [melody note]
  (or (empty? melody) (not= (last melody) note)))

(defn direction-limit-constraint
  "Reject note after melody if it would extend a run of max-consecutive (or more) melodic steps in the same direction, counted backward from the end of melody."
  [scale max-consecutive]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (< (count melody) 2) true
          (let [ni (.indexOf sv note)
                pi (.indexOf sv (last melody))]
            (if (or (neg? ni) (neg? pi) (= ni pi)) true
                (let [new-dir (if (> ni pi) 1 -1)]
                  (loop [i (dec (count melody)) cnt 0]
                    (if (< i 1) true
                        (let [a (.indexOf sv (nth melody i))
                              b (.indexOf sv (nth melody (dec i)))]
                          (if (or (neg? a) (neg? b) (= a b)) true
                              (if (and (= (if (> a b) 1 -1) new-dir)
                                       (>= cnt max-consecutive))
                                false
                                (recur (dec i) (inc cnt))))))))))))))

(defn cadence-constraint [target-length cadence-note]
  (fn [melody note]
    (if (= (count melody) (dec target-length))
      (= note cadence-note) true)))

(comment
  (constrained c-major 16
    [(max-leap-constraint c-major 2) no-repeat-constraint
     (cadence-constraint 16 0)])
  )
