;; melody.clj
;; Clojure port of pymusics src/algorithm/ — melodic algorithms (scales,
;; Markov/L-system/grammar generators, constraint-satisfaction walks).
;; Python/Kotlin sources: rule_based_melodic_algorithms.py

(ns algo.melodic.melody
  (:require [clojure.string :as str]))

(def chromatic-notes ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn build-scale [root intervals]
  (let [idx (.indexOf (vec chromatic-notes) root)]
    (mapv #(nth chromatic-notes (mod (+ idx %) 12)) intervals)))

(def c-major      (build-scale "C" [0 2 4 5 7 9 11]))
(def a-minor      (build-scale "A" [0 2 3 5 7 8 10]))
(def c-pentatonic (build-scale "C" [0 2 4 7 9]))

;; ── Markov Chain Melody ─────────────────────────────────────

(defn markov-train [melody order]
  (let [pairs (for [i (range (- (count melody) order))]
                [(vec (subvec melody i (+ i order)))
                 (nth melody (+ i order))])]
    {:order order
     :transitions (reduce (fn [m [s nxt]]
                            (update m s #(conj (or % []) nxt)))
                          {} pairs)}))

(defn markov-generate [model length & {:keys [seed]}]
  (let [{:keys [order transitions]} model
        seed (or seed (first (shuffle (keys transitions))))]
    (loop [melody (vec seed) state seed]
      (if (>= (count melody) length)
        (vec (take length melody))
        (if-let [choices (seq (get transitions state))]
          (let [nxt (rand-nth choices)]
            (recur (conj melody nxt)
                   (vec (take-last order (conj melody nxt)))))
          (let [ns (rand-nth (vec (keys transitions)))]
            (recur (conj melody (first ns)) ns)))))))

;; ── L-System Melody ─────────────────────────────────────────

(defn lsystem-melody
  [axiom rules note-map iterations max-notes]
  (let [expanded (nth (iterate (fn [s]
                                 (str/join (map #(get rules (str %) (str %)) s)))
                               axiom)
                      iterations)]
    (->> (keep (fn [c] (get note-map c)) expanded)
         (take max-notes) vec)))

;; ── Generative Grammar ──────────────────────────────────────

(defn grammar-generate
  [rules terminals & {:keys [start max-depth remove-rests?]
                      :or {start 'S max-depth 10 remove-rests? true}}]
  (letfn [(expand [sym depth]
            (if (or (terminals sym) (>= depth max-depth))
              [sym]
              (when-let [prods (seq (get rules sym))]
                (let [chosen (rand-nth prods)]
                  (mapcat #(expand % (inc depth)) chosen)))))]
    (let [raw (expand start 0)]
      (if remove-rests?
        (vec (remove #(= % "REST") raw))
        (vec raw)))))

;; ── Constraint Satisfaction ─────────────────────────────────

(defn constraint-melody
  [scale length constraints & {:keys [start]}]
  (let [first-note (or start (rand-nth scale))]
    (loop [melody [first-note]]
      (if (>= (count melody) length)
        (vec melody)
        (let [valid (filter (fn [note]
                              (every? (fn [c] (c melody note)) constraints))
                            scale)
              candidates (if (empty? valid) scale valid)]
          (recur (conj melody (rand-nth candidates))))))))

(defn max-leap-constraint [scale max-degrees]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (empty? melody) true
          (let [pi (.indexOf sv (last melody))
                ni (.indexOf sv note)]
            (<= (abs (- ni pi)) max-degrees))))))

(defn no-repeat-constraint [melody note]
  (or (empty? melody) (not= (last melody) note)))

(defn direction-limit-constraint [scale max-consecutive]
  (let [sv (vec scale)]
    (fn [melody note]
      (if (< (count melody) 2) true
          (let [ni (.indexOf sv note)
                pi (.indexOf sv (last melody))]
            (if (or (neg? ni) (neg? pi) (= ni pi)) true
                (let [new-dir (if (> ni pi) 1 -1)]
                  (loop [i (dec (count melody)) cnt 0]
                    (if (< i 0) true
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
  (constraint-melody c-major 16
    [(max-leap-constraint c-major 2) no-repeat-constraint
     (cadence-constraint 16 "C")])
  )
