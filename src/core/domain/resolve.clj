(ns core.domain.resolve
  "Two responsibilities:

   1. EVENT ACTUALIZATION (resolve-event)
      Called by the engine at tick time with the current structural-time
      (beats consumed so far on this track). Samples context envelopes
      (tempo, volume, instrument, transposition, panning, articulation --
      the last only when the leaf itself doesn't carry an explicit
      articulation shorthand) at structural-time; reads the one frozen
      constant (dynamic) directly from the leaf.

      MidiEvent shape:
        {:onset         float    wall-clock seconds (from engine clock)
         :channel       int
         :pitches       [int]    MIDI note numbers, transposition applied
         :velocity      int      0-127, rescaled from :volume's own 0-100
                                  authoring scale (common.defaults/
                                  volume->midi), not just clamped
         :dur-secs      float    full musical duration in seconds
         :dur-played    float    duration * articulation (for note-off)
         :program       int      MIDI program / timbre
         :tied          bool
         :cc            {int int} e.g. {10 64} for panning}

   2. NAVIGATION (locate)
      Walks the repo DAG from a given root along an explicit path of
      selectors, threading the ctx-chain along the way exactly as a real
      traversal would (see build-chain/root-seed) -- used for REPL
      inspection/addressing, not by the live engine (which walks
      just-in-time via core.async-engine instead)."

  (:require [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.defaults :as defaults]))

;; ============================================================
;; Constants
;; ============================================================

(def ^:private drum-channel 9)
(def ^:private cc-panning   10)

;; ============================================================
;; Duration helpers
;; ============================================================

(defn chain-offset
  "Sum :duration fields of all contexts in chain (nearest-first).
   Gives total span of enclosing containers -- useful for relative
   timing calculations. Not the same as absolute leaf offset, which
   the engine accumulates per-track via structural-time."
  [chain]
  (reduce + 0 (keep :duration chain)))

;; ============================================================
;; Context sampling helpers
;; ============================================================

(defn- chain-links
  "The [ctx offset] pairs (see core.domain.context/sample-many) to
   actually sample part against. If part carries its own baked
   :ctx-chain (see flat-core-builder/current-context-chain -- a
   nearest-first vector of [context relative-offset] pairs, snapshotted
   at walk time), each ancestor's own offset is (structural-time -
   relative-offset) -- reconstructing exactly the entry point that
   ancestor's own container would have had if it were being walked
   normally right now -- so a leaf resolves correctly whether it's
   reached by walking straight through its own container (where this
   works out numerically identical to build-chain's own per-container
   shifting) or standalone, extracted from its container entirely by
   sq/times/cycle/etc. (where ctx-chain, built externally, would
   otherwise be missing that container's own !instrument:/!tempo:/!mf/
   etc. altogether).
   The relative-offset subtraction is load-bearing, not incidental: an
   earlier version of this shifted every ancestor uniformly by
   structural-time alone (no offset), which broke ramp interpolation
   for ordinary, already-correct container-walk playback -- a ramp
   spanning several leaves collapsed to its start value on each one,
   since shifting every one of them to 'right now' erases their
   relative spacing instead of preserving it. Caught by the existing
   ramp-rebasing test suite, not reasoning.
   part having NO baked :ctx-chain at all (built directly via d/leaf,
   bypassing the real walker -- ornaments/algo-registry/tests/warm-up!)
   falls back to ctx-chain as the traversal threaded it in, each paired
   with offset 0 -- same as before this mechanism existed, and also
   exactly how ordinary (non-extracted) playback reaches this now too:
   ctx-chain is already correctly positioned by async-engine's own
   build-chain, so there's nothing left to re-base.
   Confirmed live as the original bug this whole mechanism fixes -- a
   mock MIDI receiver showed (play :verse) sending program 32 correctly
   and (play (times 12 (sq :verse))) sending program 0 (piano) and
   velocity 50 (ROOT's raw default, not !mf's), because :verse's own
   context was entirely absent from ctx-chain in that case.
   Unlike its predecessor (effective-chain, since renamed and folded
   into this), this does NOT itself call ctx-shift or otherwise touch
   any ancestor's own envelopes -- it only pairs each ancestor with its
   own offset; core.domain.context/sample-many is what actually shifts
   a value, lazily, only for a key that's actually found there. That
   split is the whole point: no more re-basing an entire ancestor's
   envelope map for keys nobody's about to ask for."
  [part ctx-chain structural-time]
  (if-let [baked (:ctx-chain part)]
    (mapv (fn [[ctx offset]] [ctx (- structural-time offset)]) baked)
    (mapv (fn [ctx] [ctx 0]) ctx-chain)))

(defn- musical->seconds
  "duration is a whole-note fraction (quarter note = 1/4, per
   common.music-data/note-lengths and the digit->fraction conversion in
   flat-tree-walker); tempo is quarter-note BPM (quarter-note-equivalent,
   per common.music-elements/tempo->quarter-bpm -- 'quarter note implied'
   for a bare BPM, per the grammar). A quarter note's own duration in
   beats is (/ duration 1/4) = duration*4, so seconds = duration*4*60/
   tempo. Confirmed live before this was fixed: a quarter note at
   Tempo=120 computed 0.125s here (implying 480 BPM), not the musically
   correct 0.5s -- exactly the missing *4 -- cross-checked against
   common.music-elements/duration-ms, an independent, already-correct
   implementation of this same conversion, which agreed with 0.5s."
  [duration tempo]
  (double (* (/ duration tempo) 240.0)))

(defn- panning->cc [panning]
  (int (max 0 (min 127 (^[double] Math/round (* (+ panning 1.0) 63.5))))))

;; ============================================================
;; Event actualization (called by engine at tick time)
;; ============================================================

(def ^:private common-keys+defaults
  "Tempo/volume, sampled for every leaf/rest/drum alike -- the shared
   half of resolve-common's own single c/sample-many call. :articulation
   joins this map only when the leaf itself has no explicit shorthand
   of its own (see resolve-common) -- assoc'd in per-call, not baked in
   here, since whether it's needed varies leaf to leaf."
  {:Tempo 120 :volume 80})

(defn- resolve-common
  "Sample tempo/volume (and articulation, unless part's own explicit
   shorthand wins outright -- see below) from chain-links at
   structural-time, in ONE c/sample-many call together with
   extra-keys+defaults (resolve-leaf's own instrument/transposition/
   panning, {} for resolve-rest/resolve-drum) -- a single pass over
   chain-links regardless of leaf type, not one pass per key the way
   this used to work (see c/sample-many's own docstring for why that
   matters: one deref per ancestor, not one per still-pending key, and
   a found value is shifted lazily instead of core.domain.resolve's
   old effective-chain eagerly re-basing an entire ancestor's envelope
   map up front).

   Articulation: the leaf's own explicit shorthand (e.g. -. staccato),
   frozen at build time, wins when present -- it's the most specific,
   author-written-on-this-note information, so it's never even added
   to the keys c/sample-many is asked to look for. Otherwise sampled
   from chain-links, so a slur's forced legato (see walk-slur-start/-end
   in flat-tree-walker) applies to every note it spans that doesn't have
   its own explicit articulation, and stops applying (ctx-invalidate)
   the moment the slur ends.
   structural-time is sampled as-is (an exact Ratio/int, the same type
   the engine's own :structural atom accumulates in, summing Ratio
   :duration fields) rather than coerced to double here -- keeps
   envelope-point comparisons and interpolation exact all the way up to
   musical->seconds' own, unavoidable, real-world-seconds boundary
   below, instead of introducing float drift one step earlier than
   necessary. This also settles a small pre-existing inconsistency:
   resolve-leaf's own instrument/transposition/panination lookups used
   to sample at (double structural-time) instead, for no documented
   reason -- now that they share this same c/sample-many call, they get
   the same exact-time treatment tempo/volume/articulation always had.
   Returns shared timing values, plus whatever extra-keys+defaults asked
   for (resolve-leaf pulls its own instrument/transposition/panning back
   out of this same map)."
  [part chain-links structural-time extra-keys+defaults]
  (let [need-articulation? (nil? (:articulation part))
        keys+defaults (cond-> (merge common-keys+defaults extra-keys+defaults)
                        need-articulation? (assoc :articulation 0.9))
        sampled      (c/sample-many chain-links keys+defaults structural-time)
        tempo        (:Tempo sampled)
        volume       (:volume sampled)
        articulation (or (:articulation part) (:articulation sampled))
        dur-secs     (musical->seconds (:duration part) tempo)
        dur-played   (* dur-secs articulation)]
    (assoc sampled
           :tempo      tempo
           :volume     volume
           :dur-secs   dur-secs
           :dur-played dur-played)))

(defn- resolve-leaf
  [{:keys [part chain-links]} channel onset structural-time]
  (let [{:keys [volume dur-secs dur-played instrument transposition panning]}
        (resolve-common part chain-links structural-time
                         {:instrument 0 :transposition 0 :panning 0.0})
        final-vel  (defaults/volume->midi (+ volume (or (:dynamic part) 0)))
        program    (int instrument)
        transpose  (int transposition)
        panning-cc (panning->cc panning)]
    {:onset      onset
     :channel    channel
     :pitches    (mapv #(+ % transpose) (:pitches part))
     :velocity   final-vel
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    program
     :tied       (boolean (:tied part))
     :cc         {cc-panning panning-cc}}))

(defn- resolve-rest
  [{:keys [part chain-links]} onset structural-time]
  (let [{:keys [dur-secs dur-played]}
        (resolve-common part chain-links structural-time {})]
    {:onset      onset
     :channel    nil    ;; no MIDI output, duration drives clock only
     :pitches    []
     :velocity   0
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    0
     :tied       false
     :cc         {}}))

(defn- resolve-drum
  [{:keys [part chain-links]} onset structural-time]
  (let [{:keys [volume dur-secs dur-played]}
        (resolve-common part chain-links structural-time {})]
    {:onset      onset
     :channel    drum-channel
     :pitches    [(or (:program part) 35)]
     :velocity   (defaults/volume->midi volume)
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    0
     :tied       false
     :cc         {}}))

(defn resolve-event
  "Actualize a raw event {:part p :ctx-chain chain} into a MidiEvent map.
   Called by the engine at tick time, right as a leaf fires.

   onset           -- wall-clock seconds (from engine's clock-atom)
   structural-time -- beats consumed so far (from engine's structural-atom)
   channel         -- MIDI channel assigned to this track by the engine

   ctx-chain is turned into chain-links before dispatch -- part's own
   baked :ctx-chain (if any) checked ahead of whatever the traversal
   threaded in, so a leaf resolves correctly even when it's been
   extracted from its container entirely (see chain-links' own
   docstring)."
  [{:keys [part ctx-chain]} channel onset structural-time]
  (let [links (chain-links part ctx-chain structural-time)
        event {:part part :chain-links links}]
    (cond
      (d/leaf? part) (resolve-leaf  event channel onset structural-time)
      (d/rest? part) (resolve-rest  event onset structural-time)
      (d/drum? part) (resolve-drum  event onset structural-time)
      :else          nil)))

;; ============================================================
;; Navigation helpers -- shared by locate
;; ============================================================

(defn- resolve-child [repo child]
  (if (keyword? child) (get repo child) child))

(defn- build-chain [part ctx-chain]
  (if-let [own-ctx (:context part)]
    (into [own-ctx] ctx-chain)
    ctx-chain))

(defn- root-seed
  "The chain a walk/locate starts from, before descending into anything.

   A session's repo always has a :ROOT container with a real context
   (built from common.defaults/root-defaults at session-start --
   see flat-core-builder/initial-state) -- that IS the one true root
   context, so nothing else needs to construct or supply another one.

   If root-id is :ROOT itself, start with an empty chain: :ROOT's own
   context gets pushed exactly once, normally, via build-chain when the
   walk descends into it. If root-id is anything else (previewing a
   subtree without going through :ROOT at all, e.g. a bare :verse),
   :ROOT is never walked into, so its context is seeded here as the
   ultimate fallback beneath whatever the subtree provides."
  [repo root-id]
  (if (= root-id :ROOT)
    []
    (if-let [root-ctx (:context (get repo :ROOT))]
      [root-ctx]
      [])))

;; ============================================================
;; Navigation
;; ============================================================

(defn- child-index
  "Resolve one path selector to an index into a container's :children.
   An integer selects by position. A keyword selects the first child
   whose resolved :id matches it -- whether that child is a keyword
   reference into repo or an inline value with its own :id (e.g. an
   Iterator, which is never repo-registered under its own id -- see
   flat-domain/describe-node). Returns nil if nothing matches."
  [repo children sel]
  (cond
    (and (integer? sel) (<= 0 sel) (< sel (count children))) sel

    (keyword? sel)
    (->> children
         (map-indexed (fn [i child] [i (resolve-child repo child)]))
         (some (fn [[i c]] (when (= sel (:id c)) i))))

    :else nil))

(defn locate
  "Navigate to a location in the repo, threading the ctx-chain along the
   way exactly as a real traversal (e.g. core.async-engine's
   play-node) would via build-chain.

   `path` is a vector of selectors from root-id, each either:
     integer  -- child at that position
     keyword  -- the child whose :id matches (no need to know its
                 position -- e.g. [:chorus 1] means \"the child named
                 :chorus, then its 2nd child\")
   e.g. [1 0] means \"root's 2nd child, then that child's 1st child\".
   Iterators have no indexable/named children (only a :source), so any
   selector there always means \"descend into :source\" -- addressing
   is structural, not about which repeat pass.

   Returns {:part <leaf/rest/drum/container/iterator> :ctx-chain [...]
   :path path} for a valid path -- the returned :ctx-chain is the chain
   that node would receive as an occurrence (its own :context is NOT
   pushed, matching how a real traversal hands a leaf the chain built by
   its ancestors, never by itself). Returns nil if the path runs off the
   structure (bad index/unmatched id, path continues past a leaf, etc)."
  [repo root-id path]
  (loop [part      (get repo root-id)
         chain     (root-seed repo root-id)
         remaining path]
    (cond
      (nil? part) nil

      (empty? remaining)
      {:part part :ctx-chain chain :path path}

      (or (d/leaf? part) (d/rest? part) (d/drum? part)) nil

      (d/iterator? part)
      (recur (:source part) (build-chain part chain) (rest remaining))

      (d/container? part)
      (let [children (:children part)
            idx      (child-index repo children (first remaining))]
        (if idx
          (recur (resolve-child repo (nth children idx))
                 (build-chain part chain)
                 (rest remaining))
          nil))

      :else nil)))

(comment
  (def n1 (d/leaf :n1 (c/context) 1/4 [60]))
  (def n2 (d/leaf :n2 (c/context) 1/4 [62]))

  (def seq1 {:type :SEQ :id :s1
             :context (c/set-duration (c/context) 1/2)
             :children [n1 n2]})

  ;; :ROOT's own context carries the real tempo/volume defaults -- this
  ;; is what a session's :ROOT looks like (see flat-core-builder/
  ;; initial-state), and it's the only root context there is: nothing
  ;; else needs to construct or pass in a second one.
  (def repo {:ROOT {:type :ROOT :id :ROOT
                    :context (c/set-duration
                               (c/context-root {"Tempo" 120 "volume" 80}) 1/2)
                    :children [:s1]}
             :s1 seq1})

  ;; locate -- navigate a path from root, same ctx-chain-threading a real
  ;; traversal would build
  (locate repo :ROOT [0 1])
  ;; => {:part n2 :ctx-chain [...] :path [0 1]}

  ;; resolve-event -- engine calls this at tick time
  ;; (engine supplies channel, onset, structural-time)
  (resolve-event {:part n1 :ctx-chain [(:context (:s1 repo))]} 0 0.0 0)
  ;; => {:onset 0.0 :channel 0 :pitches [60] :velocity 102
  ;;     :dur-secs 0.5 :dur-played 0.5 :program 0 :tied false
  ;;     :cc {10 64}}
  ;; velocity 102, not the raw 80 authored on :ROOT's own "volume" --
  ;; see defaults/volume->midi: (round (* 80 1.27)) = 102, the real
  ;; MIDI-scale rescale of :volume's own 0-100 authoring scale.
  )