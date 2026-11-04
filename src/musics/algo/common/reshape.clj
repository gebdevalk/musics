(ns musics.algo.common.reshape
  "Compositional reshaping recipes over already-resolved domain material
   -- typically a real Clojure seq produced by musics.compose/sq, reshaped
   further with ordinary seq functions, then handed to play.

   Distinct from musics.domain's per-leaf transforms (transpose/
   invert/times/dotted/dynamic), which reshape one part's own fields in
   place -- transpose/times/dotted/dynamic only ever look at their own
   part. invert is the one exception once an axis isn't given: its own
   no-arg form means each part around its own individual mean (a chord
   folds around its own center, part by part), which is still a per-part
   computation, not a sequence-wide one. This namespace's own invert
   below is the sequence-wide version -- one shared axis, computed from
   every pitch across the whole sequence -- alongside retrograde/
   arpeggiate/hocket's reordering, splitting, and combining."
  (:require [musics.domain :as d]))

(defn invert
  "Mirror every part around one shared axis pitch: axis if given, else the rounded mean of all pitches in the sequence. With axis this is (mapv (d/invert axis) parts); d/invert without one inverts each part around its own mean."
  ([parts]
   (let [pitches (mapcat :pitches parts)
         mean    (Math/round (double (/ (reduce + pitches) (count pitches))))]
     (invert mean parts)))
  ([axis parts]
   (mapv (d/invert axis) parts)))

(defn retrograde
  "Reverse a sequence of parts. :tied flags are not adjusted, so a phrase with ties may not retrograde cleanly."
  [parts]
  (vec (reverse parts)))

(defn arpeggiate
  "Split a chord leaf into single-note leaves, its duration divided evenly: a chord becomes a run. Pitches are sorted ascending; order-fn (e.g. (comp reverse sort)) changes that. A leaf with fewer than 2 pitches, or any part without :pitches, comes back as [part]."
  ([leaf] (arpeggiate leaf sort))
  ([leaf order-fn]
   (let [pitches (order-fn (:pitches leaf))
         n       (count pitches)]
     (if (< n 2)
       [leaf]
       (let [dur (/ (:duration leaf) n)]
         (mapv #(assoc leaf :pitches [%] :duration dur) pitches))))))

(defn hocket
  "Interleave two or more part-sequences into one, alternating single
   elements from each in turn -- the medieval hocket technique: a single
   melodic line split across voices, one note/group at a time. A thin
   named wrapper over interleave -- the value here is the name, not new
   logic."
  [& parts-seqs]
  (apply interleave parts-seqs))

(defn weighted-shuffle
  "Shuffle parts by repeatedly drawing the next element from those remaining, at index (dist-fn 0 n) floored into [0, n-1], n the remaining count. dist-fn's shape matters: uniform gives the plain permutation distribution, lo-emph keeps elements near their original order (a weaker shuffle), hi-emph leans towards reversal."
  [parts dist-fn]
  (loop [remaining (vec parts) result []]
    (if (empty? remaining)
      result
      (let [n   (count remaining)
            idx (-> (dist-fn 0 n) Math/floor long (max 0) (min (dec n)))]
        (recur (into (subvec remaining 0 idx) (subvec remaining (inc idx) n))
               (conj result (nth remaining idx)))))))

;; The filters (pitch range, pitch class, interval, probability) are in musics.algo.common.gate.
