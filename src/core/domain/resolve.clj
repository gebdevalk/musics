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
         :velocity      int      0-127, clamped
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
            [core.domain.context :as c]))

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

(defn- sample
  "Sample key from ctx-chain at structural-time (beats).
   Returns default-val if not found anywhere in the chain."
  [ctx-chain key structural-time default-val]
  (or (c/ctx-value-chain ctx-chain key structural-time)
      default-val))

(defn- clamp-velocity [v]
  (int (Math/round (double (max 0 (min 127 v))))))

(defn- musical->seconds [duration tempo]
  (double (* (/ duration tempo) 60.0)))

(defn- panning->cc [panning]
  (int (max 0 (min 127 (^[double] Math/round (* (+ panning 1.0) 63.5))))))

;; ============================================================
;; Event actualization (called by engine at tick time)
;; ============================================================

(defn- resolve-common
  "Sample tempo/volume from ctx-chain at structural-time.
   Articulation: the leaf's own explicit shorthand (e.g. -. staccato),
   frozen at build time, wins when present -- it's the most specific,
   author-written-on-this-note information. Otherwise sampled from
   ctx-chain, so a slur's forced legato (see walk-slur-start/-end in
   flat-tree-walker) applies to every note it spans that doesn't have
   its own explicit articulation, and stops applying (ctx-invalidate)
   the moment the slur ends.
   Returns shared timing values."
  [part ctx-chain structural-time]
  (let [t            (double structural-time)
        tempo        (sample ctx-chain :Tempo  t 120)
        volume       (sample ctx-chain :volume t 80)
        articulation (or (:articulation part)
                         (sample ctx-chain :articulation t 0.9))
        dur-secs     (musical->seconds (:duration part) tempo)
        dur-played   (* dur-secs articulation)]
    {:tempo      tempo
     :volume     volume
     :dur-secs   dur-secs
     :dur-played dur-played}))

(defn- resolve-leaf
  [{:keys [part ctx-chain]} channel onset structural-time]
  (let [{:keys [volume dur-secs dur-played]}
        (resolve-common part ctx-chain structural-time)
        t          (double structural-time)
        final-vel  (clamp-velocity (+ volume (or (:dynamic part) 0)))
        program    (int (sample ctx-chain :instrument t 0))
        transpose  (int (sample ctx-chain :transposition t 0))
        panning-cc (panning->cc (sample ctx-chain :panning t 0.0))]
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
  [{:keys [part ctx-chain]} onset structural-time]
  (let [{:keys [dur-secs dur-played]}
        (resolve-common part ctx-chain structural-time)]
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
  [{:keys [part ctx-chain]} onset structural-time]
  (let [{:keys [volume dur-secs dur-played]}
        (resolve-common part ctx-chain structural-time)]
    {:onset      onset
     :channel    drum-channel
     :pitches    [(or (:program part) 35)]
     :velocity   (clamp-velocity volume)
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
   channel         -- MIDI channel assigned to this track by the engine"
  [{:keys [part] :as event} channel onset structural-time]
  (cond
    (d/leaf? part) (resolve-leaf  event channel onset structural-time)
    (d/rest? part) (resolve-rest  event onset structural-time)
    (d/drum? part) (resolve-drum  event onset structural-time)
    :else          nil))

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
  ;; => {:onset 0.0 :channel 0 :pitches [60] :velocity 80
  ;;     :dur-secs 0.5 :dur-played 0.5 :program 0 :tied false
  ;;     :cc {10 64}}
  )