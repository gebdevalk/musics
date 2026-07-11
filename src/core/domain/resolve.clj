(ns core.domain.resolve
  "Two responsibilities:

   1. FORM-UNROLL (structural)
      Walks the repo DAG from a given root, expanding Iterator records
      and threading context chain, producing a vector of independent
      tracks ready for the engine.

      Two modes:
        form-unroll      -- eager, returns vector of tracks (finite pieces)
        form-unroll-lazy -- lazy, returns lazy-seq of tracks (infinite/
                           open-ended patterns, real-time REPL mutation)

      Events carry NO structural-offset -- offset is accumulated
      just-in-time by the engine as it consumes events, using
      flat-domain/part-duration (O(1) read from Context.:duration).

      Output per track: seq of events
        {:part leaf/rest/drum :ctx-chain [Context...]}

   2. EVENT ACTUALIZATION (resolve-event)
      Called by the engine at tick time with the current structural-time
      (beats consumed so far on this track). Samples context envelopes
      (tempo, volume) at structural-time; reads frozen constants
      (timbre, transposition, panning, articulation, dynamic) directly
      from the leaf.

      MidiEvent shape:
        {:onset         float    wall-clock seconds (from engine clock)
         :channel       int
         :pitches       [int]    MIDI note numbers, transposition applied
         :velocity      int      0-127, clamped
         :dur-secs      float    full musical duration in seconds
         :dur-played    float    duration * articulation (for note-off)
         :program       int      MIDI program / timbre
         :tied          bool
         :cc            {int int} e.g. {10 64} for panning}"

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
   Read articulation from leaf (frozen at build time).
   Returns shared timing values."
  [part ctx-chain structural-time]
  (let [t            (double structural-time)
        tempo        (sample ctx-chain :tempo  t 120)
        volume       (sample ctx-chain :volume t 80)
        articulation (or (:articulation part) 1.0)
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
        final-vel  (clamp-velocity (+ volume (or (:dynamic part) 0)))
        program    (or (:timbre        part) 0)
        transpose  (or (:transposition part) 0)
        panning-cc (panning->cc (or (:panning part) 0.0))]
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
   Called by the engine at tick time -- not during form-unroll.

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
;; Track merging
;; ============================================================

(defn- merge-tracks
  "Merge two track sets for sequential composition.
   Track N of B continues track N of A. Extra tracks carry through."
  [tracks-a tracks-b]
  (cond
    (empty? tracks-a) tracks-b
    (empty? tracks-b) tracks-a
    :else
    (let [na (count tracks-a)
          nb (count tracks-b)
          n  (max na nb)]
      (mapv (fn [i]
              (let [a (when (< i na) (nth tracks-a i))
                    b (when (< i nb) (nth tracks-b i))]
                (cond
                  (and a b) (concat a b)   ;; lazy-friendly concat
                  a         a
                  :else     b)))
            (range n)))))

;; ============================================================
;; Form-unroll core -- shared between eager and lazy
;; ============================================================

(declare form-unroll-node-eager)
(declare form-unroll-node-lazy)

(defn- resolve-child [repo child]
  (if (keyword? child) (get repo child) child))

(defn- build-chain [part ctx-chain]
  (if-let [own-ctx (:context part)]
    (into [own-ctx] ctx-chain)
    ctx-chain))

(defn- root-seed
  "The chain a walk/locate starts from, before descending into anything.

   A session's repo always has a :ROOT container with a real context
   (built from common.data.defaults/root-defaults at session-start --
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

;; ---- Eager ----

(defn- walk-seq-eager [repo children ctx-chain]
  (reduce
    (fn [tracks child]
      (let [part         (resolve-child repo child)
            child-tracks (form-unroll-node-eager repo part ctx-chain)]
        (merge-tracks tracks child-tracks)))
    []
    children))

(defn- walk-par-eager [repo children ctx-chain]
  (into []
        (mapcat (fn [child]
                  (form-unroll-node-eager
                    repo (resolve-child repo child) ctx-chain))
                children)))

(defn- expand-iterator-eager [repo iter ctx-chain]
  (let [source (:source iter)
        params (:params iter)
        n      (get params :count 1)
        volta? (= (:repeat-type params) :volta)
        alt    (:alternative params)]
    (loop [i 0 tracks []]
      (if (>= i n)
        tracks
        (let [use-alt?    (and volta? alt (= i (dec n)))
              node        (if use-alt? alt source)
              pass-tracks (form-unroll-node-eager repo node ctx-chain)]
          (recur (inc i) (merge-tracks tracks pass-tracks)))))))

(defn- form-unroll-node-eager [repo part ctx-chain]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    [[{:part part :ctx-chain ctx-chain}]]

    (d/iterator? part)
    (expand-iterator-eager repo part (build-chain part ctx-chain))

    (d/container? part)
    (let [new-chain (build-chain part ctx-chain)
          children  (:children part)]
      (case (:type part)
        :PAR (walk-par-eager repo children new-chain)
        (walk-seq-eager      repo children new-chain)))

    (keyword? part)
    (when-let [resolved (get repo part)]
      (form-unroll-node-eager repo resolved ctx-chain))

    :else []))

;; ---- Lazy ----

(defn- walk-seq-lazy [repo children ctx-chain]
  ;; Reduce over children, lazily concatenating tracks
  (reduce
    (fn [tracks child]
      (let [part         (resolve-child repo child)
            child-tracks (form-unroll-node-lazy repo part ctx-chain)]
        (merge-tracks tracks child-tracks)))
    []
    children))

(defn- walk-par-lazy [repo children ctx-chain]
  (into []
        (mapcat (fn [child]
                  (form-unroll-node-lazy
                    repo (resolve-child repo child) ctx-chain))
                children)))

(defn- expand-iterator-lazy [repo iter ctx-chain]
  (let [source (:source iter)
        params (:params iter)
        n      (get params :count 1)
        infinite? (= n :infinite)
        volta?  (= (:repeat-type params) :volta)
        alt     (:alternative params)]
    (if infinite?
      ;; Infinite repeat: lazy-seq loops forever, engine stops via state atom
      (letfn [(infinite-seq []
                (lazy-seq
                  (concat (form-unroll-node-lazy repo source ctx-chain)
                          (infinite-seq))))]
        ;; Wrap in single-track vector to match output shape
        [(infinite-seq)])
      ;; Finite repeat: same as eager but using lazy node walk
      (loop [i 0 tracks []]
        (if (>= i n)
          tracks
          (let [use-alt?    (and volta? alt (= i (dec n)))
                node        (if use-alt? alt source)
                pass-tracks (form-unroll-node-lazy repo node ctx-chain)]
            (recur (inc i) (merge-tracks tracks pass-tracks))))))))

(defn- form-unroll-node-lazy [repo part ctx-chain]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    ;; Single event -- wrap in lazy-seq for uniformity
    [(lazy-seq [{:part part :ctx-chain ctx-chain}])]

    (d/iterator? part)
    (expand-iterator-lazy repo part (build-chain part ctx-chain))

    (d/container? part)
    (let [new-chain (build-chain part ctx-chain)
          children  (:children part)]
      (case (:type part)
        :PAR (walk-par-lazy repo children new-chain)
        (walk-seq-lazy      repo children new-chain)))

    (keyword? part)
    (when-let [resolved (get repo part)]
      (form-unroll-node-lazy repo resolved ctx-chain))

    :else []))

;; ============================================================
;; Public API
;; ============================================================

(defn form-unroll
  "Eagerly walk repo from root-id, producing a vector of tracks.
   Best for finite pieces, debugging, REPL inspection.

   The root context is never passed in -- repo's own :ROOT container
   always carries one (see root-seed), so it's derived, not supplied.

   Returns vector of tracks:
     [[event ...] [event ...] ...]
   where each event is:
     {:part leaf/rest/drum :ctx-chain [Context...] nearest-first}

   No :structural-offset on events -- the engine accumulates beats
   just-in-time via its per-track structural-time atom."
  [repo root-id]
  (when-let [root (get repo root-id)]
    (form-unroll-node-eager repo root (root-seed repo root-id))))

(defn form-unroll-lazy
  "Lazily walk repo from root-id, producing a vector of lazy-seq tracks.
   Best for:
     - Infinite/open-ended patterns (:count :infinite on an Iterator)
     - Real-time REPL mutation (changes to repo take effect on next
       iteration since the lazy seq reads from repo on demand)

   Same output shape as form-unroll but tracks are lazy seqs.
   The engine consumes them identically via (first cursor)/(rest cursor).

   For infinite patterns, the engine stops by setting state to :stopped --
   the lazy tail is simply dropped and GC'd."
  [repo root-id]
  (when-let [root (get repo root-id)]
    (form-unroll-node-lazy repo root (root-seed repo root-id))))

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
   way exactly as form-unroll would.

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
   pushed, matching how form-unroll hands a leaf the chain built by its
   ancestors, never by itself). Returns nil if the path runs off the
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
  (def n3 (d/leaf :n3 (c/context) 1/4 [64]))

  (def seq1 {:type :SEQ :id :s1
             :context (c/set-duration (c/context) 1/2)
             :children [n1 n2]})

  ;; :ROOT's own context carries the real tempo/volume defaults -- this
  ;; is what a session's :ROOT looks like (see flat-core-builder/
  ;; initial-state), and it's the only root context there is: nothing
  ;; else needs to construct or pass in a second one.
  (def repo {:ROOT {:type :ROOT :id :ROOT
                    :context (c/set-duration
                               (c/context-root {"tempo" 120 "volume" 80}) 1/2)
                    :children [:s1]}
             :s1 seq1})

  ;; Eager -- finite piece
  (def tracks (form-unroll repo :ROOT))
  ;; => [[{:part n1 :ctx-chain [...]}
  ;;      {:part n2 :ctx-chain [...]}]]

  ;; Lazy -- open-ended, real-time mutation
  (def lazy-tracks (form-unroll-lazy repo :ROOT))

  ;; Infinite repeat
  (def inf-iter (d/iterator :REPEAT :R.1 (c/context) seq1
                            {:count :infinite :repeat-type :unfold}))
  (def repo-inf {:ROOT {:type :ROOT :id :ROOT
                        :context (c/set-duration
                                   (c/context-root {"tempo" 120 "volume" 80}) 0)
                        :children [inf-iter]}
                 :s1 seq1})
  (def inf-tracks (form-unroll-lazy repo-inf :ROOT))
  ;; Takes only what you need -- engine consumes until stopped
  (take 6 (first inf-tracks))
  ;; => n1 n2 n1 n2 n1 n2 ...

  ;; resolve-event -- engine calls this at tick time
  ;; (engine supplies channel, onset, structural-time)
  (resolve-event (first (first tracks)) 0 0.0 0)
  ;; => {:onset 0.0 :channel 0 :pitches [60] :velocity 80
  ;;     :dur-secs 0.5 :dur-played 0.5 :program 0 :tied false
  ;;     :cc {10 64}}
  )