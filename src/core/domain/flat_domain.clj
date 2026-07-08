(ns core.domain.flat-domain
  "Domain model for musical parts.
   - Leaf, Rest, Drum, Iterator are immutable records.
   - Containers are plain maps with :type, :id, :context, :children (vector).
   - No atoms inside nodes — all data is plain."
  (:require [core.domain.context :as c]))

;; ============================================================
;; Leaf types
;; ============================================================

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
  "Create a Rest (silent duration)."
  ([id context duration] (->Rest id context duration)))

(defrecord Drum [id context duration program])

(defn drum
  "Create a Drum (unpitched percussive event)."
  ([id context duration program] (->Drum id context duration program)))

(defmethod print-method Leaf [^Leaf l ^java.io.Writer w]
  (.write w (pr-str (:id l))))

(defmethod print-method Rest [r ^java.io.Writer w]
  (.write w (pr-str (:id r))))

(defmethod print-method Drum [d ^java.io.Writer w]
  (.write w (pr-str (:id d))))

;; ============================================================
;; Iterator (deferred-expansion wrapper)
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
;; Predicates
;; ============================================================

(defn leaf? [x] (instance? Leaf x))
(defn rest? [x] (instance? Rest x))
(defn drum? [x] (instance? Drum x))
(defn iterator? [x] (instance? Iterator x))

(defn container? [x] (and (map? x) (contains? x :children)))

(defn part?
  "True if x is any musical part (leaf, rest, drum, container, or iterator)."
  [x]
  (or (leaf? x) (rest? x) (drum? x) (container? x) (iterator? x)))

;; ============================================================
;; Container helpers
;; ============================================================

(defn children
  "Return children of a container, resolving keyword IDs via repo."
  [repo x]
  (mapv (fn [child]
          (if (keyword? child)
            (get repo child)
            child))
        (:children x)))

;(defmethod print-method Composite [^Composite c ^java.io.Writer w]
;  (let [inner (str/join " " (map #(str (type %)) (composite-children c)))]
;    (case (:type c)
;      :SEQ (str "[ " inner " ]")
;      :PAR (str "<< " inner " >>")
;      :ALGO (str "'[ " inner " ]'")
;      :QLIST (str "'( " inner " )")
;      :LIST (str "( " inner " )")
;      :SCORE (str "Score[" (:id c) " " inner " ]")
;      (str "( " inner " )"))))

;; ============================================================
;; Transform / mutate (immutable helpers)
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
  [factor]
  (fn [part]
    (if (:duration part)
      (update part :duration #(* % factor))
      part)))

(defn to-tuplet
  "Scale duration by factor — e.g. 2/3 for triplet."
  [factor]
  (fn [part]
    (if (:duration part)
      (update part :duration #(/ % factor))
      part)))

(defn to-triplet
  "Shorthand for (to-tuplet 3/2) — 3 notes in the time of 2."
  []
  (to-tuplet 3/2))

(defn dotted
  "Return a fn that dots the duration."
  []
  (fn [part]
    (if (:duration part)
      (update part :duration #(* % 3/2))
      part)))

;; ============================================================
;; Duration calculation (recursive, computed on demand)
;; ============================================================

(defn duration
  "Return the total duration of any part (leaf or container).

   If a repo is provided, it resolves keyword IDs to containers.
   If no repo is given, it assumes the part is fully self-contained (or a leaf).

   (duration leaf)                     ; => 1/4
   (duration repo :SEQ.1)              ; => duration of container with that ID
   (duration repo container-map)       ; => sum of all children
   (duration repo child-id-keyword)    ; => resolves recursively"
  ([part]
   (duration nil part))
  ([repo part]
   (cond
     ;; --- Leaf / Rest / Drum ---
     (or (leaf? part) (rest? part) (drum? part))
     (:duration part 0)

     ;; --- Container: dispatch on type ---
     (container? part)
     (case (:type part)
       ;; Parallel — all children start at same time, total = max
       :PAR (reduce (fn [acc child] (max acc (duration repo child)))
                    0
                    (children repo part))
       ;; Default: sequential — sum children durations (SEQ, LIST, ALGO, DATA, QUOTE, etc.)
       (reduce (fn [acc child] (+ acc (duration repo child)))
               0
               (children repo part)))

     ;; --- Keyword ID (look it up in repo, then recurse) ---
     (keyword? part)
     (if repo
       (recur repo (get repo part))
       0)

     ;; --- Any other map with a :duration field ---
     (map? part)
     (:duration part 0)

     ;; --- Fallback ---
     :else 0)))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Leaf / transforms ---
  (def n (leaf "c4" (c/context) 1/4 [60]))
  ((times 2) n)                                             ;; => duration 1/2
  ((to-tuplet 3/2) n)                                       ;; => duration 1/6 (triplet)
  ((dotted) n)                                              ;; => duration 3/8
  (transform n (transpose 7) (times 2))                     ;; => pitches [67], duration 1/2

  ;; --- Container (plain map) ---
  (def c {:type :SEQ :id :SEQ.1 :context (c/context) :children []})
  (container? c)                                            ;; => true
  (children nil c)                                          ;; => [] (no children to resolve)
  )