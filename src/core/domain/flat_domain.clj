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

(defrecord Bar [count, duration])

(defn bar
  "Create a Bar (unpitched zero duration signal event)."
  ([count] (->Bar count 0)))

(defmethod print-method Leaf [^Leaf l ^java.io.Writer w]
  (.write w (pr-str (:id l))))

(defmethod print-method Rest [r ^java.io.Writer w]
  (.write w (pr-str (:id r))))

(defmethod print-method Drum [d ^java.io.Writer w]
  (.write w (pr-str (:id d))))

(defmethod print-method Bar [^Bar b ^java.io.Writer w]
  (.write w (str "#<Bar " (apply str (repeat (:count b) "|")) ">")))

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
(defn bar?  [x] (instance? Bar x))

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

(defn scale-duration
  "Recursively multiply the duration of a part by factor.
   part may be a Leaf/Rest/Drum, a container map, or a keyword id into repo.
   Returns [repo' part'] -- repo' has any nested repo-registered containers
   rescaled in place (by id); part' is the value to store at the call site
   (the same keyword if part was a keyword, otherwise the scaled value)."
  [repo part factor]
  (cond
    (or (leaf? part) (rest? part) (drum? part))
    [repo (update part :duration * factor)]

    (keyword? part)
    (let [[repo' scaled] (scale-duration repo (get repo part) factor)]
      [(assoc repo' part scaled) part])

    (container? part)
    (let [[repo' new-children]
          (reduce (fn [[r acc] child]
                    (let [[r' child'] (scale-duration r child factor)]
                      [r' (conj acc child')]))
                  [repo []]
                  (:children part))]
      [repo' (-> part
                 (assoc :children new-children)
                 (update :context c/set-duration
                         (* factor (get-in part [:context :duration] 0))))])

    :else [repo part]))

;; ============================================================
;; Structure report
;; ============================================================

(defn part-duration
  "Get the duration of a part -- O(1), no repo traversal.
   Containers/Iterators: reads pre-computed :duration from Context,
                         set at pop-container time.
   Leaves/Rest/Drum:     reads :duration field directly.
   Returns 0 if not yet set."
  [part]
  (cond
    (container? part) (or (get-in part [:context :duration]) 0)
    (iterator?  part) (or (get-in part [:context :duration]) 0)
    :else             (or (:duration part) 0)))

(defn- describe-node
  "Recurse on an already-resolved part value -- never re-looks-up by :id,
   since Iterators (and other transient/inline nodes) are embedded directly
   in a container's :children and are never registered under their own id
   in repo the way push-container/pop-container-created containers are.

   A keyword child that doesn't resolve in repo (e.g. a forward reference
   to an id not parsed yet) reports as a :MISSING placeholder instead of
   silently vanishing or crashing the caller."
  [repo part]
  (cond
    (container? part)
    (let [pairs    (map (fn [child]
                          [child (if (keyword? child) (get repo child) child)])
                        (:children part))
          leaves   (filter (fn [[_ c]] (or (leaf? c) (rest? c) (drum? c))) pairs)
          non-leaf (remove (fn [[_ c]] (or (leaf? c) (rest? c) (drum? c))) pairs)]
      {:type       (:type part)
       :id         (:id part)
       :duration   (part-duration part)
       :leaf-count (count leaves)
       :children   (mapv (fn [[orig resolved]]
                            (if (nil? resolved)
                              {:type :MISSING :id orig}
                              (describe-node repo resolved)))
                          non-leaf)})

    (iterator? part)
    (let [alt (:alternative (:params part))]
      (cond-> {:type        :ITER
               :id          (:id part)
               :iter-type   (:type part)
               :count       (:count (:params part))
               :repeat-type (:repeat-type (:params part))
               :duration    (part-duration part)
               :source      (describe-node repo (:source part))}
        alt (assoc :alternative (describe-node repo alt))))

    :else nil))

(defn describe
  "Abbreviated structural report from root-id: containers and iterators
   only -- leaves/rests/drums are counted, never listed individually.
   Returns a nested map (not a printed string), so callers can pr-str,
   pretty-print, or diff it:

     {:type :SEQ :id :verse :duration 1/2 :leaf-count 4 :children [...]}
     {:type :ITER :id :R.1 :iter-type :REPEAT :repeat-type :unfold
      :count 4 :source {...}}
     {:type :ITER :id :R.2 :iter-type :REPEAT :repeat-type :volta
      :count 2 :source {...} :alternative {...}}
     {:type :MISSING :id :verse}  ;; a reference not yet resolvable in repo"
  [repo root-id]
  (describe-node repo (get repo root-id)))

(def ^:private brackets
  "Same bracket scheme as the surface grammar (musics.ebnf) -- see the
   bracket table in CLAUDE.md. Types with no surface bracket (:ROOT and
   the command-wrapper types like :TIMES/:TUPLET/:TRANSPOSE/:DECORATED)
   fall back to a generic ( )."
  {:SEQ          ["[" "]"]
   :PAR          ["{" "}"]
   :DATA         ["'[" "]'"]
   :ATOMIC_ALGO  ["@'[" "]'"]
   :ELEMENT_ALGO ["@[" "]"]
   :CONTEXT      ["^[" "]"]
   :ROOT         ["[" "]"]})

(defn- bracket-for [type]
  (get brackets type ["(" ")"]))

(defn- iter-header
  "Render an :ITER node's header using the same surface syntax the grammar
   accepts for it -- \\repeat unfold/volta N or \\repeat tremolo N -- so the
   report reads like something you could paste back in, not an internal
   type name."
  [node]
  (case (:iter-type node)
    :REPEAT  (str "\\repeat " (name (:repeat-type node)) " " (:count node))
    :TREMOLO (str "\\repeat tremolo " (:count node))
    (str (name (:iter-type node)) (when (:count node) (str " ×" (:count node))))))

(defn print-structure
  "Pretty-print (describe repo root-id) as an indented tree using the same
   brackets as the surface grammar, e.g.:

     [ :song  dur 3/2
       [ :verse  dur 1/2  (4 leaves) ]
       \\repeat unfold 2  dur 1/2
         [ :chorus  dur 1/4  (2 leaves) ]
     ]

   A \\repeat volta ... with an \\alternative ending is rendered with the
   alternative as a sibling block after the main source, same as it's
   written in text. A dangling/forward reference (an id not yet resolvable
   in repo) is rendered as \"?? :id (unresolved)\" rather than crashing."
  [repo root-id]
  (letfn [(line [node depth]
            (let [pad (apply str (repeat (* 2 depth) " "))]
              (cond
                (= :MISSING (:type node))
                (str pad "?? " (:id node) "  (unresolved)\n")

                (= :ITER (:type node))
                (str pad (iter-header node)
                     "  dur " (:duration node) "\n"
                     (line (:source node) (inc depth))
                     (when-let [alt (:alternative node)]
                       (str pad "\\alternative\n"
                            (line alt (inc depth)))))

                :else
                (let [[open close] (bracket-for (:type node))
                      header       (str open " " (:id node)
                                         "  dur " (:duration node)
                                         (when (pos? (:leaf-count node))
                                           (str "  (" (:leaf-count node) " leaves)")))]
                  (if (seq (:children node))
                    (str pad header "\n"
                         (apply str (map #(line % (inc depth)) (:children node)))
                         pad close "\n")
                    (str pad header " " close "\n"))))))]
    (print (line (describe repo root-id) 0))))

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