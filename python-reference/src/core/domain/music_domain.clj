;; music_domain.clj
;; Clojure port of the pymusics domain model.
;;
;; Types: Point, Envelope, Context, Leaf, Rest, Drum, Composite, Transient, Score
;;
;; Usage:
;;   (require '[core.domain.music-domain :as d])
;;   (d/make-score root-ctx part)

(ns core.domain.music-domain
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]))

;; ============================================================
;; IP: Interpolation types (ported from envelope.py IP enum)
;; ============================================================

(def ip-easing
  "Map of ip keyword → easing function.
   Each easing fn takes t ∈ [0,1] and returns eased weight ∈ [0,1].
   FIXED and STEP are nil — handled specially in sampling."
  {:fixed       nil
   :step        nil
   :lin-up      (fn [t] t)
   :lin-down    (fn [t] t)
   :smooth      (fn [t] (* t t (- 3 (* 2 t))))
   :ease-in     (fn [t] (* t t))
   :ease-out    (fn [t] (- 1 (* (- 1 t) (- 1 t))))
   :ease-in-out (fn [t]
                  (if (< t 0.5)
                    (* 2.0 t t)
                    (- 1 (* 0.5 (- (* -2.0 t) 2.0) (- (* -2.0 t) 2.0)))))})

(defn easing
  "Look up the easing function for an IP keyword."
  [ip]
  (get ip-easing ip))

;; ============================================================
;; Point (ported from envelope.py Point NamedTuple)
;; ============================================================

(defrecord Point [time value ip])

(defn point
  "Create a Point. ip defaults to :fixed."
  ([time value] (->Point time value :fixed))
  ([time value ip] (->Point time value ip)))

;; ============================================================
;; Envelope (ported from envelope.py Envelope)
;; ============================================================

;; Points stored in an atom — mutation is thread-safe via compare-and-swap.
;; No explicit lock needed.

(defrecord Envelope [points-atom])

(defn envelope
  "Create an empty Envelope."
  []
  (->Envelope (atom [])))

(defn envelope-from
  "Create an Envelope from a seq of point maps [{:time :value :ip} ...]."
  [point-maps]
  (->Envelope (atom (mapv (fn [{:keys [time value ip]}]
                            (->Point time value (or ip :fixed)))
                          point-maps))))

(defn env-duration
  "Duration of the envelope = time of the last point, or 0."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (seq pts)
      (:time (last pts))
      0.0)))

(defn env-empty?
  "True if the envelope has no points."
  [^Envelope env]
  (empty? @(:points-atom env)))

(defn env-append
  "Append a point to the envelope. Mutates in place (swap! on atom).
   If time matches the last point, replace it.
   Returns env for chaining."
  [^Envelope env time value ip]
  (swap! (:points-atom env)
         (fn [pts]
           (if (and (seq pts) (= (:time (last pts)) time))
             (conj (vec (butlast pts)) (->Point time value ip))
             (conj pts (->Point time value ip)))))
  env)

(defn env-get
  "Sample the envelope at the given time.
   Returns the interpolated value, or nil if empty."
  [^Envelope env time]
  (let [pts @(:points-atom env)]
    (cond
      (empty? pts) nil
      (<= time (:time (first pts))) (:value (first pts))
      (>= time (:time (last pts)))  (:value (last pts))
      :else
      (let [times (mapv :time pts)
            ;; Manual bisect — find index of segment start
            idx (loop [lo 0 hi (dec (count times))]
                  (if (>= lo hi)
                    (min lo (dec (count times)))
                    (let [mid (quot (+ lo hi 1) 2)]
                      (if (<= (nth times mid) time)
                        (recur mid hi)
                        (recur lo (dec mid))))))
            prev (nth pts idx)
            nxt  (nth pts (inc idx))
            ip   (:ip nxt)]
        (if (or (= ip :fixed) (= ip :step))
          (:value prev)
          (let [t    (/ (- time (:time prev))
                        (- (:time nxt) (:time prev)))
                ease (easing ip)]
            (if (and (number? (:value prev)) (number? (:value nxt)))
              (+ (* (- 1 (ease t)) (:value prev))
                 (* (ease t) (:value nxt)))
              (:value prev))))))))

(defn env-reverse
  "Return a new Envelope with points reversed in time."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (empty? pts)
      (envelope)
      (let [d       (env-duration env)
            rev     (reverse pts)
            rev-ips (mapv :ip (reverse (butlast pts)))]
        (envelope-from
         (map-indexed
          (fn [i p]
            {:time  (- d (:time p))
             :value (:value p)
             :ip    (if (= i (dec (count rev))) :fixed (nth rev-ips i))})
          rev))))))

;; ============================================================
;; Context (ported from context.py Context NamedTuple)
;; ============================================================

(defrecord Context [parent envelopes-atom])

(defn context
  "Create a Context with optional parent and empty envelopes."
  ([parent] (->Context parent (atom {})))
  ([] (->Context nil (atom {}))))

(defn context-root
  "Create a root Context from a map of key → value.
   Each value becomes a single FIXED point at time 0."
  [data]
  (let [ctx (context)]
    (doseq [[k v] data]
      (let [env (envelope)]
        (env-append env 0.0 v :fixed)
        (swap! (:envelopes-atom ctx) assoc (name k) env)))
    ctx))

;; --- Hierarchical key lookup ---

(defn ^:private find-envelope
  "Walk up the parent chain to find the envelope for key."
  [^Context ctx key]
  (loop [c ctx]
    (when c
      (if-let [env (get @(:envelopes-atom c) key)]
        env
        (recur (:parent c))))))

(defn ctx-value
  "Sample the value for key at time, walking up the parent chain.
   Returns nil if key is not found anywhere."
  [^Context ctx key time]
  (when-let [env (find-envelope ctx (name key))]
    (env-get env time)))

(defn ctx-append
  "Add a point to the envelope for key in this context.
   If key exists locally: append to it.
   If key exists only in parent: create new local envelope.
   If key doesn't exist anywhere: create new local envelope."
  [^Context ctx key time value ip]
  (let [k (name key)]
    (if-let [env (get @(:envelopes-atom ctx) k)]
      (env-append env time value ip)
      (let [env (envelope)]
        (env-append env time value ip)
        (swap! (:envelopes-atom ctx) assoc k env)))
    ctx))

;; ============================================================
;; Leaf types (ported from parts.py)
;; ============================================================

;; Immutable — Clojure records are immutable by default.
;; No need for frozen=True or dataclasses.replace.

(defrecord Leaf [id context duration pitches
                 articulation dynamic modifiers tied])

(defn leaf
  "Create a Leaf (pitched note or chord).
   duration should be a Ratio (Clojure fraction).
   pitches is a vector of ints (MIDI note numbers)."
  ([id context duration pitches]
   (->Leaf id context duration (vec pitches) nil nil [] false))
  ([id context duration pitches articulation dynamic modifiers tied]
   (->Leaf id context duration (vec pitches) articulation dynamic
           (vec modifiers) (boolean tied))))

(defrecord Rest [id context duration])

(defn rest*
  "Create a Rest (silent duration).
   Name ends with * to avoid shadowing clojure.core/rest."
  ([id context duration] (->Rest id context duration)))

(defrecord Drum [id context duration program])

(defn drum
  "Create a Drum (unpitched percussive event)."
  ([id context duration program] (->Drum id context duration program)))

;; ============================================================
;; Transient (operator list — not part of musical domain)
;; ============================================================

(defrecord Transient [type id context children-atom])

(defn transient*
  "Create a Transient container. Uses atom for children (thread-safe)."
  ([type id context]
   (->Transient type id context (atom []))))

(defn transient-append
  "Append an item to the transient."
  [^Transient t item]
  (swap! (:children-atom t) conj item)
  t)

(defn transient-children
  "Snapshot the current children."
  [^Transient t]
  @(:children-atom t))

;; ============================================================
;; Composite (mutable container — ported from parts.py Composite)
;; ============================================================

(defrecord Composite [type id context children-atom])

(defn composite
  "Create a Composite container.
   type is a keyword: :SEQ, :PAR, :ALGO, :SCORE etc.
   Thread-safe mutation via atom (no RLock needed)."
  ([type id context]
   (->Composite type id context (atom [])))
  ([type id context children]
   (->Composite type id context (atom (vec children)))))

(defn composite-children
  "Snapshot the current children of a composite."
  [^Composite c]
  @(:children-atom c))

(defn composite-duration
  "Sum the durations of all children."
  [^Composite c]
  (reduce (fn [acc child]
            (+ acc (or (:duration child) 0)))
          0
          @(:children-atom c)))

(defn composite-append
  "Append a part to the composite. Thread-safe (atom swap!)."
  [^Composite c part]
  (swap! (:children-atom c) conj part)
  c)

(defn composite-remove
  "Remove the first occurrence of part from the composite."
  [^Composite c part]
  (swap! (:children-atom c)
         (fn [cs] (vec (remove #(= % part) cs))))
  c)

(defn composite-insert
  "Insert part at index."
  [^Composite c idx part]
  (swap! (:children-atom c)
         #(vec (concat (take idx %) [part] (drop idx %))))
  c)

(defn composite-replace
  "Replace the child at index with part. Returns the old child."
  [^Composite c idx part]
  (let [old (atom nil)]
    (swap! (:children-atom c)
           (fn [cs]
             (let [o (nth cs idx)]
               (reset! old o)
               (assoc (vec cs) idx part))))
    @old))

(defn composite-count
  "Number of children in the composite."
  [^Composite c]
  (count @(:children-atom c)))

(defn composite-seq
  "Lazy seq over a snapshot of the children."
  [^Composite c]
  (seq @(:children-atom c)))

(defn composite-to-string
  "Pretty-print a composite like the Python __str__."
  [^Composite c]
  (let [inner (str/join " " (map #(str (type %)) (composite-children c)))]
    (case (:type c)
      :SEQ   (str "[ " inner " ]")
      :PAR   (str "<< " inner " >>")
      :ALGO  (str "'[ " inner " ]'")
      :QLIST (str "'( " inner " )")
      :LIST  (str "( " inner " )")
      :SCORE (str "Score[" (:id c) " " inner " ]")
      (str "( " inner " )"))))

;; ============================================================
;; Score (ported from score.py)
;; ============================================================

(defn make-score
  "Create a Score (Composite with type :SCORE).
   The part's context parent is set to root-ctx."
  ([root-ctx]
   (composite :SCORE "score" root-ctx))
  ([root-ctx part]
   (let [score (composite :SCORE "score" root-ctx)]
     (when part
       (let [part-ctx (:context part)]
         ;; Set context parent
         (when part-ctx
           (composite-append score
             (assoc part :context (assoc part-ctx :parent root-ctx))))))
     score)))

;; ============================================================
;; Transform / mutate (ported from parts.py mutate/transform)
;; ============================================================

(defn mutate
  "Return a new version of part with updated fields.
   Like Python's dataclasses.replace."
  [part & kvs]
  (apply assoc part kvs))

(defn transform
  "Apply fns left-to-right to part."
  [part & fns]
  (reduce (fn [p f] (f p)) part fns))

(defn transpose
  "Return a fn that transposes pitches by semitones."
  [semitones]
  (fn [part]
    (if (:pitches part)
      (update part :pitches #(mapv (partial + semitones) %))
      part)))

(defn to-triplet
  "Return a fn that converts duration to triplet."
  [part]
  (if (:duration part)
    (update part :duration #(* % 2/3))
    part))

(defn dotted
  "Return a fn that dots the duration."
  [part]
  (if (:duration part)
    (update part :duration #(* % 3/2))
    part))

;; ============================================================
;; Part union — predicate functions
;; ============================================================

(defn leaf?      [x] (instance? Leaf x))
(defn rest?      [x] (instance? Rest x))
(defn drum?      [x] (instance? Drum x))
(defn composite? [x] (instance? Composite x))
(defn transient? [x] (instance? Transient x))
(defn part?      [x] (or (leaf? x) (rest? x) (drum? x) (composite? x)))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Envelope ---
  (def env (envelope))
  (env-append env 0.0 0.5 :fixed)
  (env-append env 2.0 1.0 :lin-up)
  (env-append env 4.0 0.0 :smooth)
  (env-duration env)     ;; => 4.0
  (env-get env 1.0)      ;; => 0.75 (linear interpolation)
  (env-get env 3.0)      ;; => smoothed value

  ;; --- Context ---
  (def root-ctx (context-root {"tempo" 120 "volume" 0.8 "timbre" 42}))
  (ctx-value root-ctx "tempo" 0.0)    ;; => 120

  (def child-ctx (context root-ctx))
  (ctx-append child-ctx :tempo 2.0 80 :lin-up)
  (ctx-value child-ctx "tempo" 0.0)   ;; => 120 (inherited from root)
  (ctx-value child-ctx "tempo" 3.0)   ;; => 80  (local override)

  ;; --- Leaf ---
  (def n (leaf "c4" (context) 1/4 [60]))
  (transform n (transpose 7)  to-tuplet dotted)
  ;; => Leaf with pitches [67], duration 1/2

  ;; --- Composite ---
  (def c (composite :SEQ "phrase" (context)))
  (composite-append c (leaf "a" (context) 1/4 [69]))
  (composite-append c (leaf "b" (context) 1/4 [71]))
  (composite-children c)    ;; => vector of 2 Leaf records
  (composite-duration c)    ;; => 1/2
  (composite-to-string c)   ;; => "[ .. .. ]"
  )
