(ns algorithm.reshape
  "Compositional reshaping recipes over already-resolved domain material
   -- typically a real Clojure seq produced by musics.clj/sq, reshaped
   further with ordinary seq functions, then handed to play.

   Distinct from core.domain.flat-domain's per-leaf transforms (transpose/
   invert/times/dotted/dynamic), which reshape one part's own fields in
   place. These reshape a *sequence* of parts into a differently-shaped
   sequence of parts -- reordering, splitting, or combining them -- not
   just changing a value on each one.")

(defn retrograde
  "Reverse a sequence of parts -- the classical retrograde transform, same
   idea as core.domain.context/env-reverse but applied to a sequence of
   parts rather than a single envelope. Doesn't adjust :tied flags -- a
   tie into what's now the previous note isn't un-tied or re-anchored,
   so a phrase with ties may not retrograde cleanly on its own."
  [parts]
  (vec (reverse parts)))

(defn arpeggiate
  "Split a chord leaf's simultaneous pitches into a sequence of single-
   note leaves, splitting the original duration evenly across them --
   turns a chord into a run. Pitches are sorted ascending by default;
   pass order-fn (e.g. (comp reverse sort) for descending) to change the
   order. A no-op (returns [leaf]) for a leaf with fewer than 2 pitches,
   or any part with no :pitches at all (rest/drum/container)."
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
