;; transform.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py section 13
;; -- pattern-VARIATION (rather than pattern-generating) techniques:
;; David Cope's EMI-style variation, Brian Eno's Oblique Strategies --
;; both mutate/vary a pattern's own CONTENT. Section 14's own
;; microtiming/groove techniques (swing, humanize, pocket) -- adjusting
;; onset TIMING FEEL rather than content -- moved to their own file,
;; algo.rhythmic.micro, once "transform" turned out too wide a name for
;; two genuinely distinct concerns (2026-09-05).

(ns algo.rhythmic.transform
  (:require [algo.random :as rand]))

(defn emi-style-variation
  "Vary pattern in the style of David Cope's EMI: independently, for
   each position, with probability (1 - similarity) apply a random
   mutation (flip this bit, swap it with the next, insert a random bit
   here, or delete this position). similarity close to 1.0 stays close
   to the original; close to 0.0 varies heavily. The result is then
   clamped back to within [half, double] the original length.

   The reference this ports iterates a FIXED number of times (the
   ORIGINAL pattern length, captured once via Python's `range(len(...))`
   before the loop starts) while insert/delete keep resizing the
   working list underneath it -- confirmed live, this throws IndexError
   in ~27% of runs at similarity=0.3, a genuine bug, not a quirk worth
   reproducing. This port instead re-checks the CURRENT length before
   every step, so it always terminates cleanly regardless of how many
   inserts/deletes have happened so far."
  ([pattern] (emi-style-variation pattern 0.7))
  ([pattern similarity]
   (if (empty? pattern)
     []
     (let [n (count pattern)
           varied (loop [i 0 v (vec pattern)]
                    (if (>= i (count v))
                      v
                      (if (> (rand/rand-double) similarity)
                        (case (rand/choose ["flip" "swap" "insert" "delete"])
                          "flip" (recur (inc i) (update v i #(- 1 %)))
                          "swap" (if (< i (dec (count v)))
                                   (recur (inc i) (assoc v i (nth v (inc i)) (inc i) (nth v i)))
                                   (recur (inc i) v))
                          "insert" (if (< (rand/rand-double) 0.3)
                                     (recur (inc i) (vec (concat (subvec v 0 i) [(rand/rand-int 2)] (subvec v i))))
                                     (recur (inc i) v))
                          "delete" (if (> (count v) 1)
                                     (recur i (vec (concat (subvec v 0 i) (subvec v (inc i)))))
                                     (recur (inc i) v)))
                        (recur (inc i) v))))]
       (cond
         (> (count varied) (* n 2)) (subvec varied 0 (* n 2))
         (< (count varied) (quot n 2)) (vec (concat varied (repeat (- (quot n 2) (count varied)) 0)))
         :else varied)))))

(def oblique-strategies
  "Name -> transform fn, Brian Eno's Oblique Strategies applied to a
   binary rhythm pattern."
  {"reverse"         (fn [p] (vec (reverse p)))
   "invert"          (fn [p] (mapv #(- 1 %) p))
   "slowest"         (fn [p] (vec (mapcat #(repeat 3 %) p)))
   "fastest"         (fn [p] (if (> (count p) 1) (vec (take-nth 2 p)) (vec p)))
   "disconnect"      (fn [p] (vec (map-indexed (fn [i v] (if (even? i) v 0)) p)))
   "only_essentials" (fn [p] (vec (map-indexed (fn [i v] (if (and (= v 1) (even? i)) 1 0)) p)))
   "mistakes"        (fn [p] (mapv (fn [v] (if (> (rand/rand-double) 0.2) v (- 1 v))) p))
   "silence"         (fn [p] (vec (repeat (count p) 0)))
   "double"          (fn [p] (vec (concat p p)))
   "mirror"          (fn [p] (vec (concat p (reverse p))))})

(defn oblique-strategies-transform
  "Apply one of the named oblique-strategies transforms to pattern.
   strategy \"random\" (the default) picks one uniformly at random each
   call; an unrecognized strategy name is a no-op (returns pattern
   unchanged)."
  ([pattern] (oblique-strategies-transform pattern "random"))
  ([pattern strategy]
   (let [strategy (if (= strategy "random") (rand/choose (keys oblique-strategies)) strategy)
         f (get oblique-strategies strategy identity)]
     (f pattern))))

(comment
  (emi-style-variation [1 0 1 0 1 0 1 0] 0.8)
  (oblique-strategies-transform [1 0 1 1 0] "reverse")
  )
