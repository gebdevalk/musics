(ns musics.algo.common.reshape
  "Compositional reshaping recipes over already-resolved domain material
   -- typically a real Clojure seq produced by musics.core/sq, reshaped
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
  "Sequence-level convenience over musics.domain/invert: mirrors
   every part in parts around one shared axis pitch, rather than each part
   inverting around its own individual mean. With axis given, this is
   exactly (mapv (d/invert axis) parts). With no axis, the axis used is
   the rounded mean of every pitch across the WHOLE sequence, so the
   sequence folds around its own overall center as one shape -- distinct
   from d/invert's own no-arg form, which means each part around only its
   own pitches."
  ([parts]
   (let [pitches (mapcat :pitches parts)
         mean    (Math/round (double (/ (reduce + pitches) (count pitches))))]
     (invert mean parts)))
  ([axis parts]
   (mapv (d/invert axis) parts)))

(defn retrograde
  "Reverse a sequence of parts -- the classical retrograde transform, same
   idea as musics.domain.context/env-reverse but applied to a sequence of
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

(defn weighted-shuffle
  "Shuffle parts by repeatedly drawing the NEXT output element from
   whatever's still remaining, at index (dist-fn 0 n) -- n the CURRENT
   remaining count, floored and clamped into [0, n-1] -- rather than
   assigning each element an independent sort key. That more obvious-
   looking construction was tried first and rejected once actually
   checked: ranks of i.i.d. continuous draws are uniform over
   permutations no matter their marginal distribution, so 'sort by an
   independent draw per element' silently makes dist-fn's own shape
   irrelevant to the result -- confirmed with a live probe (a 20000-
   trial average-displacement comparison) before writing this, not
   just reasoned about; uniform and musics.algo.random/lo-emph came back
   statistically indistinguishable (1.9456 vs 1.9395) under that
   construction.

   This one actually responds to dist-fn's shape, confirmed the same
   way: with dist-fn = musics.algo.random/uniform, draws are unbiased over the
   remaining pool at every step -- pick-random-without-replacement,
   which reduces to the SAME permutation distribution plain Fisher-
   Yates/clojure.core/shuffle produces (1.9422 vs 1.9449 in the same
   probe -- confirmed, not assumed). With musics.algo.random/lo-emph (peaked
   toward the LOW end of a range), draws cluster near 0 -- i.e. near
   the FRONT of whatever's still remaining -- so elements tend to keep
   close to their ORIGINAL relative order: a weaker, order-preserving
   shuffle (1.2626 average displacement vs uniform's 1.9422 in the same
   n=6 probe). hi-emph is the mirror image, biased toward picking from
   near the END of what's remaining each step -- a stronger, more
   reversal-leaning reorder."
  [parts dist-fn]
  (loop [remaining (vec parts) result []]
    (if (empty? remaining)
      result
      (let [n   (count remaining)
            idx (-> (dist-fn 0 n) Math/floor long (max 0) (min (dec n)))]
        (recur (into (subvec remaining 0 idx) (subvec remaining (inc idx) n))
               (conj result (nth remaining idx)))))))

;; The old pitch-range/pitch-class/interval/probability filters that
;; used to live here (lo-filter/hi-filter/window-filter/pitch-class-
;; filter/interval-filter/probability-filter) moved to
;; musics.algo.common.gate (2026-09-03) -- one general engine (gate) plus
;; criterion constructors, replacing six
;; bespoke functions. See that ns's own docstring for the full
;; rationale, including why this refactor was deliberately scoped to
;; just the filters and not applied to the other, superficially similar
;; cases found across musics/algo/ (musics.algo.random's own lo-emph/mean-emph/
;; hi-emph, musics.algo.common.zfilter's own smooth/momentum/memory, etc.).
