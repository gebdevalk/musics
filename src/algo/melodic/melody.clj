;; melody.clj
;; Clojure port of pymusics src/algorithm/ — melodic algorithms (scales,
;; Markov/L-system/grammar generators, constraint-satisfaction walks).
;; Python/Kotlin sources: rule_based_melodic_algorithms.py

(ns algo.melodic.melody
  (:require [clojure.string :as str]
            [algo.common.pitch :as pitch]))

;; Scales are plain pitch-class integers (0-11) now, not note-name
;; strings -- see algo.common.pitch/build-scale's own docstring, and
;; doc/decisions.md's 2026-09-05 entry (algo.txt's GAP 1) for why this
;; moved out of melody.clj entirely rather than staying a local,
;; string-based helper. Built via from-key, not hand-typed intervals --
;; :major/:minor/:pentatonic-major's own formulas already live in
;; common.music-elements/scale-steps, the project's own central table;
;; re-typing them here as a second copy would just be duplication.
;;
;; These three are just convenience defs, not the only scales reachable
;; -- common.music-elements/scale-steps has 24 named scales (every
;; church mode, harmonic/melodic minor, both pentatonics, blues major/
;; minor, whole-tone, both diminished forms, hungarian-minor, double-
;; harmonic, bebop-dominant/major, ...) across 13 tonics (common.music-
;; data/signatures), so algo.common.pitch/from-key reaches 312 named
;; key/scale combinations directly: (pitch/from-key :F# :dorian). Or,
;; for a single spec string instead of two keyword args (from-key-spec,
;; e.g. (pitch/from-key-spec "Bb.blues-minor")) -- either resolves,
;; along with a plain already-built scale vector, through
;; algo.common.pitch/resolve-scale, which every generator/constraint fn
;; below happily accepts unchanged (they were already representation-
;; agnostic before any of this existed) -- see modulating-melody below
;; for the motivating use.
(def c-major      (pitch/from-key :C :major))
(def a-minor      (pitch/from-key :A :minor))
(def c-pentatonic (pitch/from-key :C :pentatonic-major))

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

(defn modulating-melody
  "Generate a melody across several scale segments in sequence -- each
   [scale-spec length] pair in segments gets its own constraint-melody
   run of exactly length notes, constraints shared across every
   segment. scale-spec is anything algo.common.pitch/resolve-scale
   accepts -- an already-built scale vector, a [key-kw scale-kw] pair,
   or a \"F#.major\"-style spec string -- so segments can freely mix
   pre-built scales with spec shorthand.

   Every segment after the first tries to continue smoothly from the
   previous segment's own last note (used as constraint-melody's own
   :start) IF that pitch is actually a member of the new segment's
   scale -- a genuine melodic pivot tone, the same idea a real
   modulation uses -- otherwise falls back to constraint-melody's own
   default (a fresh random note in the new scale), since forcing
   continuity onto a pitch the new scale doesn't even contain isn't a
   real modulation, it's just a wrong note. A pivot note is never
   dropped/de-duplicated at the seam -- each segment always contributes
   exactly its own declared length, so (count result) always equals the
   sum of every segment's length, and a pivot simply repeats that one
   note once at the boundary (a held tone), rather than making length
   mean something fuzzier. A thin sequencing layer over constraint-
   melody, not a different generator -- its own scale/length/
   constraints/:start contract is otherwise unchanged.

   (modulating-melody [[c-major 8] [\"A.minor\" 8]] [no-repeat-constraint])
   ;; => a 16-note melody, first 8 in C major, next 8 in A minor,
   ;;    pivoting on the hand-off note if it happens to fit both"
  [segments constraints]
  (loop [segs segments melody []]
    (if (empty? segs)
      melody
      (let [[scale-spec length] (first segs)
            scale (pitch/resolve-scale scale-spec)
            pivot (when (and (seq melody) (some #{(peek melody)} scale))
                    (peek melody))
            piece (constraint-melody scale length constraints :start pivot)]
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
  "Reject note after melody if it would extend a run of max-consecutive
   (or more) melodic steps already moving in the SAME direction, walked
   backward from the end of melody. (Fixed 2026-09-03: the inner loop's
   own termination check was (< i 0), but its body reads (nth melody
   (dec i)) -- once i reached 0, that's (nth melody -1), a confirmed-
   live IndexOutOfBoundsException on ordinary input. Every existing
   pair in melody is (i-1, i) for i from (dec (count melody)) down to
   1, so the loop must stop once i reaches 0, before the body ever
   computes (dec i) again -- (< i 1), not (< i 0)."
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
  (constraint-melody c-major 16
    [(max-leap-constraint c-major 2) no-repeat-constraint
     (cadence-constraint 16 0)])
  )
