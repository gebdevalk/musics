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
      part-duration (O(1) read from Context.:duration).

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

(defn part-duration
  "Get the duration of a part -- O(1), no repo traversal.
   Containers/Iterators: reads pre-computed :duration from Context,
                         set at pop-container time.
   Leaves/Rest/Drum:     reads :duration field directly.
   Returns 0 if not yet set."
  [part]
  (cond
    (d/container? part) (or (get-in part [:context :duration]) 0)
    (d/iterator?  part) (or (get-in part [:context :duration]) 0)
    :else               (or (:duration part) 0)))

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

   root-ctx -- root Context (c/context-root), sits at end of every
               ctx-chain, providing tempo/volume defaults.

   Returns vector of tracks:
     [[event ...] [event ...] ...]
   where each event is:
     {:part leaf/rest/drum :ctx-chain [Context...] nearest-first}

   No :structural-offset on events -- the engine accumulates beats
   just-in-time via its per-track structural-time atom."
  [repo root-id root-ctx]
  (when-let [root (get repo root-id)]
    (form-unroll-node-eager repo root [root-ctx])))

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
  [repo root-id root-ctx]
  (when-let [root (get repo root-id)]
    (form-unroll-node-lazy repo root [root-ctx])))

(comment
  (require '[core.domain.flat-domain :as d]
           '[core.domain.context :as c])

  (def root-ctx (c/context-root {"tempo" 120 "volume" 80}))

  (def n1 (d/leaf :n1 (c/context) 1/4 [60]))
  (def n2 (d/leaf :n2 (c/context) 1/4 [62]))
  (def n3 (d/leaf :n3 (c/context) 1/4 [64]))

  (def seq1 {:type :SEQ :id :SEQ.1
             :context (c/set-duration (c/context) 1/2)
             :children [n1 n2]})

  (def repo {:ROOT {:type :ROOT :id :ROOT
                    :context (c/set-duration (c/context) 1/2)
                    :children [:SEQ.1]}
             :SEQ.1 seq1})

  ;; Eager -- finite piece
  (def tracks (form-unroll repo :ROOT root-ctx))
  ;; => [[{:part n1 :ctx-chain [...]}
  ;;      {:part n2 :ctx-chain [...]}]]

  ;; Lazy -- open-ended, real-time mutation
  (def lazy-tracks (form-unroll-lazy repo :ROOT root-ctx))

  ;; Infinite repeat
  (def inf-iter (d/iterator :REPEAT :R.1 (c/context) seq1
                            {:count :infinite :repeat-type :unfold}))
  (def repo-inf {:ROOT {:type :ROOT :id :ROOT
                        :context (c/set-duration (c/context) 0)
                        :children [inf-iter]}
                 :SEQ.1 seq1})
  (def inf-tracks (form-unroll-lazy repo-inf :ROOT root-ctx))
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