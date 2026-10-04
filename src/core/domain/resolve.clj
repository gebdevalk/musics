(ns core.domain.resolve
  "Two responsibilities:

   1. EVENT ACTUALIZATION (resolve-event)
      Called by the engine at tick time with the current structural-time
      (beats consumed so far on this track). Samples context envelopes
      (tempo, volume, instrument, transposition, panning, articulation,
      Meter -- the second-to-last only when the leaf itself doesn't
      carry an explicit articulation shorthand) at structural-time, all
      in ONE c/sample-many pass (see resolve-common); reads the one
      frozen constant (dynamic) directly from the leaf.

      :meter rides along on the returned MidiEvent specifically so
      core.engine's own advance-bar!/bar-length (bar-crossing
      tracking, called right after every note fires) can reuse THIS
      SAME sampling pass instead of walking the chain a second time
      just for Meter, the way it used to -- 'everything the engine
      needs to play and account for one note' is meant to come from
      this one call, not from a second, independent lookup elsewhere.

      MidiEvent shape:
        {:onset         float    wall-clock seconds (from engine clock)
         :channel       int
         :pitches       [int]    MIDI note numbers, transposition and
                                  :octave (12 semitones each) applied
         :velocity      int      0-127, rescaled from :volume's own 0-100
                                  authoring scale (common.context-keys/
                                  volume->midi), not just clamped
         :dur-secs      float    full musical duration in seconds, times
                                  :durScale (so it also moves what follows)
         :dur-played    float    duration * articulation (for note-off)
         :program       int      MIDI program / timbre
         :tied          bool
         :cc            {int int} e.g. {10 64} for panning
         :meter         Meter or nil -- common.music-elements/Meter in
                                  effect for this note, or nil if none
                                  is set anywhere in the chain (see
                                  common.music-elements/meter-bar-length)
         :micro         float    :micro context value, seconds, sampled
                                  as-is -- see core.engine/schedule!,
                                  which offsets the note-on by it
         :humanization  float    :humanization context value, 0.0-1.0,
                                  sampled as-is -- see humanize}

   2. NAVIGATION (locate)
      Walks the repo DAG from a given root along an explicit path of
      selectors, threading the ctx-chain along the way exactly as a real
      traversal would (see build-chain/root-seed) -- used for REPL
      inspection/addressing, not by the live engine (which walks
      just-in-time via core.engine instead)."

  (:require [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [common.context-keys :as ck]
            [common.music-data :as data]
            [common.music-elements :as el]))

(defn humanize
  "[onset-offset velocity] for a resolved note e: its :micro, plus, scaled
   by its :humanization (0..1), a random onset shift and velocity change
   of up to the :humanization quantity's :spread either way (velocity
   kept within 1..127). draw is a fn of no args
   giving a uniform double in [0,1) -- rand live, a seeded Random for a
   file, so a render is repeatable."
  [{:keys [micro humanization velocity]} draw]
  (let [m (or micro 0.0)
        h (or humanization 0.0)]
    (if (zero? h)
      [m velocity]
      (let [{:keys [secs] max-vel :velocity} (:spread (data/quantity :humanization))
            spread #(* h % (dec (* 2.0 (draw))))]
        [(+ m (spread secs))
         (-> (+ velocity (Math/round (double (spread max-vel)))) (max 1) (min 127) int)]))))

(defn- dflt
  "A quantity's default -- common.music-data/quantities is the one source
   of truth; these are only reached when nothing in the ctx-chain (not
   even :ROOT) sets the key."
  [q]
  (:default (data/quantity q)))

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
  "The [ctx offset] links (see core.domain.context/sample-many) to
   sample part against: ctx-chain, the chain the walk that reached part
   built, preceded by whatever of part's own baked :ctx-chain that walk
   didn't pass through.

   A leaf bakes its ancestors at parse time (flat-core-builder/
   current-context-chain: nearest-first [ctx relative-offset] pairs,
   :ROOT last). Walked through its own container, the nearest baked
   ancestor is already on ctx-chain, so ctx-chain is returned as it is.
   Extracted from it (sq/times/cycle), the baked ancestors up to the
   first one ctx-chain does hold go in front, each re-based to its
   entry point (structural-time - relative-offset -- the relative
   offset is what keeps a ramp across several extracted leaves
   interpolating). So a container's own settings win, and the context
   playing it -- a referencing container's !tempo:, !ff, !key: -- still
   reaches what the container doesn't set itself. The baked :ROOT never
   goes in front: its defaults would answer every key before ctx-chain
   got a turn. Ancestors are compared by their :envelopes-atom, which
   the repo's container and the leaves share (the Context record
   itself is re-made as the container's :duration grows)."
  [part ctx-chain structural-time]
  (if-let [baked (:ctx-chain part)]
    (let [walked? (fn [ctx]
                    (let [a (:envelopes-atom ctx)]
                      (some #(identical? a (:envelopes-atom (if (vector? %) (first %) %))) ctx-chain)))
          n       (dec (count baked))]
      (loop [i 0 front []]
        (let [[ctx offset] (when (< i n) (nth baked i))]
          (if (or (nil? ctx) (walked? ctx))
            (if (zero? i) ctx-chain (into front ctx-chain))
            (recur (inc i) (conj front [ctx (- structural-time offset)]))))))
    ctx-chain))

(defn rekey
  "part with its pitches read under the key it is played in: a leaf
   keeps the Key its bare letters were resolved against (:key, see
   flat-tree-walker/written-key); where the context's :key differs,
   each pitch that is a degree of the leaf's own key takes the playing
   key's accidental for its letter (common.music-elements/rekey -- c d
   e f g written in C plays c d e f# g under G), and :key becomes the
   playing key. A \\transpose around the leaf (:key-shift semitones)
   transposes both keys first, so its notes follow the playing key
   transposed the same way. A pitch written with its own accidental
   (chromatic in the leaf's key) stays. A chordmode chord (:key-root)
   moves every tone by its root's change, so f:maj in C plays f#:maj
   under G rather than f# a c. Unchanged without a :key
   (generated material, ornament sub-notes), under :accidentals
   :explicit, or in the same key. Applied before the wall and
   ornaments, so both see the pitch that sounds."
  [part ctx-chain structural-time]
  (if-let [from (:key part)]
    (let [{to :key acc :accidentals}
          (c/sample-many (chain-links part ctx-chain structural-time)
                         {:key nil :accidentals :implied} structural-time)]
      (if (or (nil? to) (= acc :explicit) (= from to))
        part
        (let [shift (:key-shift part 0)
              at    #(if (zero? shift) % (el/transpose-key % shift))
              f     (at from)
              t     (at to)]
          (if-let [root (:key-root part)]
            (let [d (- (el/rekey f t root) root)]
              (assoc part :key to :key-root (+ root d) :pitches (mapv #(+ % d) (:pitches part))))
            (assoc part :key to :pitches (mapv #(el/rekey f t %) (:pitches part)))))))
    part))

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
  "Tempo/volume/Meter/Partial, sampled for every leaf/rest/drum alike --
   the shared half of resolve-common's own single c/sample-many call.
   :Meter rides in the same batched pass specifically so
   core.engine's advance-bar! never needs a second, separate
   chain walk of its own just to find it (see resolve-event's own
   docstring) -- 'everything required for playing/accounting for one
   note' comes from this one call, nothing the engine needs is ever
   looked up a second time elsewhere. default nil (not a real Meter)
   matches ctx-value-chain's own not-found contract -- core.async-
   engine/bar-length already treats a nil meter as 'no meter set
   anywhere in the chain', same as before this existed. :Partial rides
   along the same way, for the same reason -- core.engine applies
   it once, against whichever leaf a voice resolves first, to seed that
   voice's own :bar-pos (see that ns's own comment on
   :partial-pending?); default nil means 'no \\partial in scope', same
   not-found contract as :Meter's own.
   :articulation joins this map only when the leaf itself has no
   explicit shorthand of its own (see resolve-common) -- assoc'd in
   per-call, not baked in here, since whether it's needed varies leaf
   to leaf.
   :micro/:humanization ride along the same way, for micro-timing (see
   core.engine/schedule!'s own onset-offset handling) -- both
   default to 0.0, the :micro/:humanization quantities' defaults, so a
   piece that never sets either is
   completely unaffected: resolve-common's own sampled map already
   carries them through to every caller for free, no extra plumbing
   needed here beyond registering the defaults."
  {:Tempo (dflt :tempo) :volume (dflt :volume) :Meter nil :Partial nil
   :micro (dflt :micro) :humanization (dflt :humanization)
   :durScale (dflt :ratio)})

(def ^:private leaf-keys+defaults
  "What resolve-leaf samples on top of common-keys+defaults."
  {:instrument (dflt :instrument) :transposition (dflt :semitones)
   :octave (dflt :octave) :panning (dflt :panning)})

(def played-keys
  "Every context key playback reads -- the one answer to 'does setting
   this change what's heard'; the GUI shows sliders for these only."
  (into #{:articulation :key :accidentals} (concat (keys common-keys+defaults) (keys leaf-keys+defaults))))

(defn- resolve-common
  "Sample tempo/volume (and articulation, unless part's own explicit
   shorthand wins outright -- see below) from chain-links at
   structural-time, in ONE c/sample-many call together with
   extra-keys+defaults (resolve-leaf's own instrument/transposition/
   panning, {} for resolve-rest/resolve-drum) -- a single pass over
   chain-links regardless of leaf type, not one pass per key the way
   this used to work (see c/sample-many's own docstring for why that
   matters: one deref per ancestor, not one per still-pending key, and
   no ancestor's own envelope points are ever touched or copied at
   all -- the query time shifts instead -- unlike core.domain.resolve's
   old effective-chain, which eagerly re-based an entire ancestor's
   envelope map up front, for every key it happened to hold, not just
   the ones about to be sampled).

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
                        need-articulation? (assoc :articulation (dflt :articulation)))
        ;; a note's own \name:value overrides win over the context's
        sampled      (merge (c/sample-many chain-links keys+defaults structural-time)
                            (:overrides part))
        tempo        (:Tempo sampled)
        volume       (:volume sampled)
        articulation (or (:articulation part) (:articulation sampled))
        dur-secs     (* (musical->seconds (:duration part) tempo) (:durScale sampled))
        dur-played   (* dur-secs articulation)]
    (assoc sampled
           :tempo      tempo
           :volume     volume
           :meter      (:Meter sampled)
           :partial    (:Partial sampled)
           :dur-secs   dur-secs
           :dur-played dur-played)))

(defn- resolve-leaf
  [{:keys [part chain-links]} channel onset structural-time]
  (let [{:keys [volume dur-secs dur-played meter partial instrument transposition octave panning
                micro humanization]}
        (resolve-common part chain-links structural-time leaf-keys+defaults)
        final-vel  (ck/volume->midi (+ volume (or (:dynamic part) 0)))
        program    (int instrument)
        transpose  (int (+ transposition (* 12 octave)))
        panning-cc (panning->cc panning)]
    {:onset      onset
     :channel    channel
     ;; zero? fast path: transposition defaults to 0 and stays there
     ;; for the overwhelming majority of pieces that never write !t:/
     ;; !transpose: anywhere -- mapv-ing +0 over every note's pitches
     ;; would still allocate a whole new, element-wise-identical
     ;; vector, on every single note, for nothing.
     :pitches    (if (zero? transpose) (:pitches part) (mapv #(+ % transpose) (:pitches part)))
     :velocity   final-vel
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    program
     :tied       (boolean (:tied part))
     :cc         {cc-panning panning-cc}
     :meter      meter
     :partial    partial
     :micro         micro
     :humanization  humanization}))

(defn- resolve-rest
  [{:keys [part chain-links]} onset structural-time]
  (let [{:keys [dur-secs dur-played meter partial micro humanization]}
        (resolve-common part chain-links structural-time {})]
    {:onset      onset
     :channel    nil    ;; no MIDI output, duration drives clock only
     :pitches    []
     :velocity   0
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    0
     :tied       false
     :cc         {}
     :meter      meter
     :partial    partial
     :micro         micro
     :humanization  humanization}))

(defn- resolve-drum
  [{:keys [part chain-links]} onset structural-time]
  (let [{:keys [volume dur-secs dur-played meter partial micro humanization]}
        (resolve-common part chain-links structural-time {})]
    {:onset      onset
     :channel    drum-channel
     :pitches    [(or (:program part) 35)]
     :velocity   (ck/volume->midi (+ volume (or (:dynamic part) 0)))
     :dur-secs   dur-secs
     :dur-played dur-played
     :program    0
     :tied       false
     :cc         {}
     :meter      meter
     :partial    partial
     :micro         micro
     :humanization  humanization}))

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
   (built from common.context-keys/root-defaults at session-start --
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
   way exactly as a real traversal (core.events/walk-node) would via
   build-chain.

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
  ;;     :cc {10 64} :meter nil}
  ;; velocity 102, not the raw 80 authored on :ROOT's own "volume" --
  ;; see ck/volume->midi: (round (* 80 1.27)) = 102, the real
  ;; MIDI-scale rescale of :volume's own 0-100 authoring scale.
  ;; meter nil since this hand-built repo's own :ROOT never sets one
  ;; (a real session's :ROOT always does -- see common.context-keys/reg!'s
  ;; own :Meter registration -- so nil only shows up for a repo built
  ;; by hand like this one, not real playback).
  )