(ns core.domain.flat-domain
  "Domain model for musical parts.
   - Leaf, Rest, Drum, Iterator are immutable records.
   - Containers are plain maps with :type, :id, :context, :children (vector).
   - No atoms inside nodes — all data is plain."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [core.domain.context :as c]))

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

(defn rest*
  "Create a Rest (silent duration)."
  ([id context duration] (->Rest id context duration)))

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
(defn container? [x]
  (and (map? x) (contains? x :children)))

(defn part?
  "True if x is any musical part (leaf, rest, drum, container, or iterator)."
  [x]
  (or (leaf? x) (rest? x) (drum? x) (container? x) (iterator? x)))


;; ============================================================
;; Container helpers
;; ============================================================

(defn container-children
  "Return the children of a container (reads :children from a plain map)."
  [x]
  (cond
    (map? x) (:children x)
    :else (throw (ex-info "Not a container" {:arg x}))))

(defn container-duration
  "Sum the durations of all children."
  [x]
  (reduce (fn [acc child] (+ acc (or (:duration child) 0)))
          0
          (container-children x)))

;; ============================================================
;; Context wiring (still useful for external code)
;; ============================================================

(defn wire-context
  "Return part with context wired into the composite's context tree.
   - Leaf nodes (Leaf, Rest, Drum): replace context with parent-ctx.
   - Container nodes: preserve own context, set :parent to parent-ctx.
   If the part has no context at all, returns unchanged."
  [part parent-ctx]
  (if-let [pc (:context part)]
    (if (container? part)
      (assoc part :context (assoc pc :parent parent-ctx))
      (assoc part :context parent-ctx))
    part))

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
  (container-children c)                                    ;; => []
  (container-duration c)                                    ;; => 0
  )