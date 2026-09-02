(ns algo.common.reshape
  "Compositional reshaping recipes over already-resolved domain material
   -- typically a real Clojure seq produced by musics.clj/sq, reshaped
   further with ordinary seq functions, then handed to play.

   Distinct from core.domain.flat-domain's per-leaf transforms (transpose/
   invert/times/dotted/dynamic), which reshape one part's own fields in
   place -- transpose/times/dotted/dynamic only ever look at their own
   part. invert is the one exception once an axis isn't given: its own
   no-arg form means each part around its own individual mean (a chord
   folds around its own center, part by part), which is still a per-part
   computation, not a sequence-wide one. This namespace's own invert
   below is the sequence-wide version -- one shared axis, computed from
   every pitch across the whole sequence -- alongside retrograde/
   arpeggiate/hocket's reordering, splitting, and combining.

   weighted-shuffle/weighted-shuffle-algo (added later, alongside the
   others but a genuinely different KIND of thing) are this project's
   first composite wall-fn FACTORY -- one whose own arg is another named
   thing (a registered distribution, core.wall/distribution-fn) rather
   than a literal value, explored directly as the concrete case for
   'can algorithm composition itself be specified, not just algorithm
   parameters' (see doc/decisions.md for the fuller design discussion).

   lo-filter/hi-filter/window-filter (and their own -algo factory
   wrappers) are the audio low-pass/high-pass/band-pass analogy, gating
   PITCH instead of frequency -- ordinary parameterized factories (a
   literal cutoff/range, not a name resolved against another registry),
   unlike weighted-shuffle-algo."
  (:require [core.domain.flat-domain :as d]
            [core.wall :as wall]))

(defn invert
  "Sequence-level convenience over core.domain.flat-domain/invert: mirrors
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
   just reasoned about; uniform and algo.random/lo-emph came back
   statistically indistinguishable (1.9456 vs 1.9395) under that
   construction.

   This one actually responds to dist-fn's shape, confirmed the same
   way: with dist-fn = algo.random/uniform, draws are unbiased over the
   remaining pool at every step -- pick-random-without-replacement,
   which reduces to the SAME permutation distribution plain Fisher-
   Yates/clojure.core/shuffle produces (1.9422 vs 1.9449 in the same
   probe -- confirmed, not assumed). With algo.random/lo-emph (peaked
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

(defn weighted-shuffle-algo
  "A core.wall FACTORY -- (fn [dist-name] -> wall-fn) -- resolving
   dist-name against core.wall/distribution-fn (register it there
   first, e.g. (register-distribution! :lo-emph algo.random/lo-emph))
   and building a wall-fn that reorders whatever nodes it's handed via
   weighted-shuffle above. This project's first composite wall-fn
   factory whose own arg names ANOTHER registered thing -- a
   distribution, not a literal value -- the concrete case explored for
   whether algorithm COMPOSITION itself, not just parameters, is worth
   specifying declaratively (see doc/decisions.md).

   An unregistered dist-name is checked and handled HERE, eagerly, at
   factory-application time -- not left to fail lazily the first time
   the returned wall-fn actually runs, deep inside a live voice's own
   go-block, where a thrown exception silently kills the voice instead
   of surfacing (confirmed elsewhere in this project, see
   validate-ids!'s own docstring) -- so an unregistered name degrades
   to identity (no shuffling) with a console warning immediately,
   same 'degrade and warn, never throw from inside a live voice' policy
   core.wall/apply-factory already has for its own failure cases.

   register-algo! this under a name with :kind :factory, then tag it
   inline ([name dist-name] as a play/assign-algo! :algo argument) --
   see core.wall's own docstring for the mechanism:
     (register-distribution! :lo-emph algo.random/lo-emph)
     (register-algo! :weightedShuffle weighted-shuffle-algo nil :factory)
     (play (repeat unfold 4 [c4 d4 e4 f4]) :algo [:weightedShuffle :lo-emph])
   Because a repeat's own body is re-visited fresh, and its wall-fn re-
   invoked fresh, on EVERY pass (core.async-engine's play-node container
   branch calls resolve-algo on raw-children on every single visit, no
   caching -- confirmed live, not assumed), this reshuffles anew each
   cycle with zero extra plumbing -- the whole point of the original
   'repeat n times, reshuffled every cycle, weighted by lo-emph' case
   this factory was built to answer."
  [dist-name]
  (if-let [dist-fn (wall/distribution-fn dist-name)]
    (fn [nodes _ctx-chain _voice] (weighted-shuffle nodes dist-fn))
    (do (println "algo.common.reshape: no distribution registered as" dist-name
                  "-- falling back to identity")
        (fn [nodes _ctx-chain _voice] nodes))))

;; ============================================================
;; Pitch-range gates: lo-filter/hi-filter/window-filter -- the audio
;; low-pass/high-pass/band-pass analogy, gating PITCH instead of
;; frequency. Ordinary parameterized factories (a literal cutoff/range,
;; not a name resolved against another registry the way weighted-
;; shuffle-algo's distribution arg is) -- ANY cutoff/range works, no
;; registration step needed beyond register-algo! itself, so these
;; don't touch core.wall's distribution registry at all.
;; ============================================================

(defn pitch-filter
  "Gate parts (a seq of Leaf/Rest/Drum/container/etc -- the same shape a
   wall-fn always receives) by pred, a MIDI-pitch predicate (int ->
   boolean). Only Leaf parts are affected -- Rest passes through
   untouched (nothing to filter), Drum passes through untouched (its
   :program identifies an instrument/sound, not a pitch, so a pitch
   predicate doesn't meaningfully apply), and any container/Bar/
   :assignment/etc. passes through too, same tolerance every wall-fn in
   this project already has for a shape it doesn't specifically act on.

   A chord Leaf is filtered PITCH BY PITCH, not kept/dropped wholesale
   -- (pitch-filter #(<= % 67) [chord-with-pitches-60-72-64]) keeps
   [60 64], drops 72, same as a real filter gates each frequency
   component of a signal independently rather than an all-or-nothing
   decision per note. A Leaf whose pitches ALL fail becomes a Rest of
   the SAME :id/:context/:duration -- never dropped from the sequence
   outright, so timing/repeat-cycle length is never affected by
   filtering, only what actually sounds."
  [pred parts]
  (mapv (fn [part]
          (if (d/leaf? part)
            (let [kept (filterv pred (:pitches part))]
              (if (seq kept)
                (assoc part :pitches kept)
                (d/rest* (:id part) (:context part) (:duration part))))
            part))
        parts))

(defn lo-filter
  "Keep only pitches at or below cutoff -- everything above rests (or,
   in a chord, is dropped from the chord). The audio low-pass analogy:
   passes LOW, gates out HIGH. See pitch-filter."
  [parts cutoff]
  (pitch-filter #(<= % cutoff) parts))

(defn hi-filter
  "Keep only pitches at or above cutoff -- the audio high-pass analogy:
   passes HIGH, gates out LOW. See pitch-filter."
  [parts cutoff]
  (pitch-filter #(>= % cutoff) parts))

(defn window-filter
  "Keep only pitches within [lo hi] inclusive -- the audio band-pass
   analogy. See pitch-filter."
  [parts lo hi]
  (pitch-filter #(<= lo % hi) parts))

(defn lo-filter-algo
  "A core.wall FACTORY -- (fn [cutoff] -> wall-fn) -- wrapping lo-filter
   as a per-voice playback algorithm:
     (register-algo! :loFilter lo-filter-algo nil :factory)
     (play :verse :algo [:loFilter 67])"
  [cutoff]
  (fn [nodes _ctx-chain _voice] (lo-filter nodes cutoff)))

(defn hi-filter-algo
  "A core.wall FACTORY -- (fn [cutoff] -> wall-fn) -- wrapping hi-filter
   as a per-voice playback algorithm. See lo-filter-algo's own
   docstring for the registration/use pattern."
  [cutoff]
  (fn [nodes _ctx-chain _voice] (hi-filter nodes cutoff)))

(defn window-filter-algo
  "A core.wall FACTORY -- (fn [lo hi] -> wall-fn) -- wrapping
   window-filter as a per-voice playback algorithm:
     (register-algo! :windowFilter window-filter-algo nil :factory)
     (play :verse :algo [:windowFilter 60 72])"
  [lo hi]
  (fn [nodes _ctx-chain _voice] (window-filter nodes lo hi)))
