(ns algo.common.split
  "Voice-splitting canon/heterophony generator: start from one slow, low
   melody, and each split-off cycle spins a new voice off the CURRENT
   HIGHEST one -- its pitches move up an octave, its durations halve,
   and the resulting line plays twice back to back. Building each new
   voice from the previous split's own (already-doubled) result, rather
   than from the original melody directly, is what keeps every voice
   the same total length: halving a voice's own durations then playing
   it twice always lands back on that voice's own prior total duration,
   so voice k's total length always equals voice (k-1)'s, which by
   induction always equals the original melody's own single pass --
   every layer, once written out in full, spans exactly one 'cycle' and
   lines up as a parallel voice against every other layer with no
   further adjustment needed.

   split-leafs/split-leaf-voice below (the @{ } ElementAlgo entry
   point, see input.algo-registry) operate on real Leaf/Rest/Drum
   records -- chords transpose every pitch together, and every other
   field (:articulation/:dynamic/:modifiers/:tied/:context/:ctx-chain)
   just carries forward unchanged from whichever source part a given
   output part derives from, since the transform only ever touches
   :pitches/:duration. split/split-off below (no longer wired to
   AtomicAlgo, kept as the plain-data-in-plain-data-out core) operate
   one level earlier still, on bare [pitch duration] pairs, the same
   pre-Leaf level as algo.common.isorhythm/color-talea."
  )

(defn- split-off*
  "One split-off cycle: step-fn transforms one part of voice (octave up,
   duration halved), then the whole transformed voice is repeated twice
   back to back."
  [step-fn voice]
  (let [up (mapv step-fn voice)]
    (into up up)))

(defn- splits*
  "source's own (inc n) voices: index 0 the untouched original, index k
   the k-th split-off, each built from index (k-1) via step-fn+repeat
   (split-off* above)."
  [step-fn source n]
  (vec (reductions (fn [voice _] (split-off* step-fn voice)) (vec source) (range n))))

;; ============================================================
;; Bare [pitch duration] pairs -- pre-Leaf, same level as color-talea
;; ============================================================

(defn- pair-step [[pitch duration]] [(+ pitch 12) (/ duration 2)])

(defn split
  "melody: a seq of [pitch duration] pairs, the original low/slow line.
   n: how many times to split a new voice off the current highest one.
   Returns a vector of (inc n) voices -- see namespace docstring for the
   recipe."
  [melody n]
  (splits* pair-step melody n))

;; ============================================================
;; Real Leaf/Rest/Drum records -- @{ split n voiceIndex? Element... }
;; ============================================================

(defn- leaf-step
  "Octave-up-and-halve for one real part: transposes every pitch in
   :pitches (so a chord moves as a whole), halves :duration, and passes
   any other shape (a keyword container id, a Bar, an :assignment
   record -- same tolerance play-node/sq already have for a container's
   heterogeneous :children) through untouched."
  [part]
  (cond-> part
    (:pitches part)  (update :pitches (fn [ps] (mapv #(+ % 12) ps)))
    (:duration part) (update :duration #(/ % 2))))

(defn split-leafs
  "leafs: a seq of real Leaf/Rest/Drum records, the original low/slow
   line. n: how many times to split a new voice off the current highest
   one. Returns a vector of (inc n) voices, each a vector of real
   Leaf/Rest/Drum-shaped records -- same recipe as split above, just one
   level later (real parts, not bare pairs)."
  [leafs n]
  (splits* leaf-step leafs n))

(defn split-leaf-voice
  "Adapter for @{ split n voiceIndex? Element... }, ElementAlgo's own
   splice contract: n and an optional voice-index (0..n, defaulting to n
   itself -- the final, fastest, highest split-off -- when omitted) are
   the leading scalar args ElementAlgo's grammar allows before its body;
   leafs is the walked seq of real Leaf/Rest/Drum content forming the
   original line. Returns ONE layer's worth of real Leaf-shaped records,
   ready to splice directly into whatever container the @{ } call sits
   in -- place one call per voiceIndex 0..n, each in its own << >>
   branch, to play the whole texture together."
  ([n leafs] (split-leaf-voice n n leafs))
  ([n voice-index leafs]
   (nth (split-leafs leafs n) voice-index)))
