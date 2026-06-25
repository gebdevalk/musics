;; ;; music_domain.clj
;; ;; Clojure port of the musics domain model.
;; ;;
;; ;; Types: Point, Envelope, Context, Leaf, Rest, Drum, Composite, Transient, Score
;; ;;
;; ;; Usage:
;; ;;   (require '[core.domain.music-domain :as d])
;; ;;   (d/make-score root-ctx part)

(ns core.domain.music-domain
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:require [core.domain.context :as c]))

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

(defmethod print-method Leaf [^Leaf l ^java.io.Writer w]
  (.write w "#<Leaf ")
  (.write w (pr-str (:id l)))
  (.write w " ")
  (.write w (pr-str (:pitches l)))
  (when (:duration l)
    (.write w (str " " (:duration l))))
  (when (:articulation l)
    (.write w (str " " (:articulation l))))
  (when (:dynamic l)
    (.write w (str " " (:dynamic l))))
  (when (seq (:modifiers l))
    (.write w (str " " (:modifiers l))))
  (when (:tied l) (.write w " tied"))
  (.write w ">"))

(defrecord Rest [id context duration])

(defn make-rest
  "Create a Rest (silent duration).
   Name ends with * to avoid shadowing clojure.core/rest."
  ([id context duration] (->Rest id context duration)))
(def rest* make-rest)                                       ;; backward compat

(defmethod print-method Rest [r ^java.io.Writer w]
  (.write w "#<Rest ")
  (.write w (pr-str (:id r)))
  (when (:duration r) (.write w (str " " (:duration r))))
  (.write w ">"))

(defrecord Drum [id context duration program])

(defn drum
  "Create a Drum (unpitched percussive event)."
  ([id context duration program] (->Drum id context duration program)))

(defmethod print-method Drum [d ^java.io.Writer w]
  (.write w "#<Drum ")
  (.write w (pr-str (:id d)))
  (.write w " ")
  (.write w (pr-str (:program d)))
  (when (:duration d) (.write w (str " " (:duration d))))
  (.write w ">"))

;; ============================================================
;; Transient (operator list — not part of musical domain)
;; ============================================================

(defrecord Transient [type id context children-atom])

(defn make-transient
  "Create a Transient container. Uses atom for children (thread-safe)."
  ([type id context]
   (->Transient type id context (atom []))))
(def transient* make-transient)                             ;; backward compat

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

(defn wire-context
  "Return part with context wired into the composite's context tree.
   - Leaf nodes (Leaf, Rest, Drum): replace context with parent-ctx.
   - Container nodes (Composite): preserve own context, set :parent to parent-ctx.
   If the part has no context at all, returns unchanged."
  [part parent-ctx]
  (if-let [pc (:context part)]
    (if (instance? Composite part)
      (assoc part :context (assoc pc :parent parent-ctx))
      (assoc part :context parent-ctx))
    part))

(defn composite
  "Create a Composite container.
   type is a keyword: :SEQ, :PAR, :ALGO, :SCORE etc.
   Thread-safe mutation via atom (no RLock needed)."
  ([type id parent-context]
   (->Composite type id (c/context parent-context) (atom [])))
  ([type id parent-context children]
   (let [ctx (c/context parent-context)
         wired (mapv #(wire-context % ctx) children)]
     (->Composite type id ctx (atom wired)))))

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
  "Append a part (or a collection of parts) to the composite.
   Thread-safe (atom swap!). Wires each part into the composite's context tree.
   When given a single part, wraps it in a vector."
  [^Composite c part-or-vec]
  (let [parts (if (sequential? part-or-vec) part-or-vec [part-or-vec])]
    (swap! (:children-atom c) into
           (mapv #(wire-context % (:context c)) parts))
    c))

(defn composite-remove
  "Remove the first occurrence of part from the composite."
  [^Composite c part]
  (swap! (:children-atom c)
         (fn [cs] (vec (remove #(= % part) cs))))
  c)

(defn composite-insert
  "Insert part at index. Wires the part into the composite's context tree."
  [^Composite c idx part]
  (let [wired (wire-context part (:context c))]
    (swap! (:children-atom c)
           #(vec (concat (take idx %) [wired] (drop idx %))))
    c))

(defn composite-replace
  "Replace the child at index with part. Returns the old child.
   Wires the new part into the composite's context tree."
  [^Composite c idx part]
  (let [old (atom nil)
        wired (wire-context part (:context c))]
    (swap! (:children-atom c)
           (fn [cs]
             (let [o (nth cs idx)]
               (reset! old o)
               (assoc (vec cs) idx wired))))
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
      :SEQ (str "[ " inner " ]")
      :PAR (str "<< " inner " >>")
      :ALGO (str "'[ " inner " ]'")
      :QLIST (str "'( " inner " )")
      :LIST (str "( " inner " )")
      :SCORE (str "Score[" (:id c) " " inner " ]")
      (str "( " inner " )"))))

;; ============================================================
;; Iterator (deferred-expansion wrapper — e.g. \repeat)
;; ============================================================

(defrecord Iterator [type id context source params])

(defn iterator
  "Create an Iterator — a lazy traversal wrapper around a source Composite.
   type   — :REPEAT, :TREMOLO, etc.
   source — the walked Composite (sequence content)
   params — e.g. {:count 4, :repeat-type :volta, :alternative Composite}"
  [type id context source params]
  (->Iterator type id context source (or params {})))

(defn iterator? [x] (instance? Iterator x))

(defmethod print-method Iterator [^Iterator it ^java.io.Writer w]
  (.write w "#<Iterator ")
  (.write w (name (:type it)))
  (.write w " ")
  (.write w (pr-str (:id it)))
  (when-let [c (:count (:params it))]
    (.write w (str " ×" c)))
  (.write w ">"))

;; ============================================================
;; Score (ported from score.py)
;; ============================================================

(defn make-score
  "Create a Score (Composite with type :SCORE).
   The part is wired into the score's context tree via composite-append."
  ([root-ctx]
   (composite :SCORE "score" root-ctx))
  ([root-ctx part]
   (let [score (composite :SCORE "score" root-ctx)]
     (when part
       (composite-append score part))
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

(defn times
  "Return a fn that multiplies the duration."
  [part factor]
  (if (:duration part)
    (update part :duration #(* % factor))
    part))

(defn to-tuplet
  "Scale duration by factor — e.g. 2/3 for triplet."
  [part factor]
  (if (:duration part)
    (update part :duration #(/ % factor))
    part))

(defn to-triplet
  "Shorthand for (to-tuplet part 3/2) — 3 notes in the time of 2."
  [part]
  (to-tuplet part 3/2))

(defn dotted
  "Return a fn that dots the duration."
  [part]
  (if (:duration part)
    (update part :duration #(* % 3/2))
    part))

;; ============================================================
;; Part union — predicate functions
;; ============================================================

(defn leaf? [x] (instance? Leaf x))
(defn rest? [x] (instance? Rest x))
(defn drum? [x] (instance? Drum x))
(defn composite? [x] (instance? Composite x))
(defn transient? [x] (instance? Transient x))
(defn part? [x] (or (leaf? x) (rest? x) (drum? x) (composite? x) (iterator? x)))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Leaf / transforms ---
  (def n (leaf "c4" (c/context) 1/4 [60]))
  (times n 2)                                               ;; => duration 1/2
  (to-tuplet n 3/2)                                         ;; => duration 1/6 (triplet)
  (dotted n)                                                ;; => duration 3/8
  (transform n (transpose 7))                               ;; => pitches [67]

  ;; --- Composite ---
  (def c (composite :SEQ "phrase" (c/context)))
  (composite-append c (leaf "a" (c/context) 1/4 [69]))
  (composite-append c (leaf "b" (c/context) 1/4 [71]))
  (composite-children c)                                    ;; => vector of 2 Leaf records
  (composite-duration c)                                    ;; => 1/2
  (composite-to-string c)                                   ;; => "[ .. .. ]"
  )