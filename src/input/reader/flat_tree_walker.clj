(ns input.reader.flat-tree-walker
  "Post-parse tree walker that builds a flat repository of containers,
   with leaves stored inline in :children vectors.
   Uses input.reader.flat-core-builder for state management.

   Changes from previous version:
   - Added walk-context for ^[ ] Context definition form
   - Added walk-reference distinguishing :CONTEXT vs container refs
   - Updated extract-modifiers to include :Tremolo as NoteSuffix
   - Updated walk-assignment :Ramp case for timed ramps (DurationExpr + Target)
   - Removed :FormSign and :FormJump (form navigation removed from grammar)
   - Fixed make-iterator (removed unused parent-ctx binding)
   - Added resolve-ip, parse-duration-expr-node, parse-target-node helpers"
  (:require [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.data.music-data :as data]
            [common.elements.music-elements :as el]
            [input.reader.parser.leaf-parser :as leaf]
            [input.reader.flat-core-builder :as flat]
            [clojure.string :as str]))

;; ============================================================
;; Duration parsing
;; ============================================================

(defn- parse-duration
  "Convert a duration string ('4', '2.', '8..') to a rational.
   longa = 4, breve = 2, otherwise n = 1/n with dots adding half each."
  [s]
  (cond
    (nil? s) nil
    (= s "longa") 4
    (= s "breve") 2
    :else
    (let [dots (count (take-while #{\.} (str/replace s #"[^.]+" "")))
          n    (Integer/parseInt (apply str (remove #{\.} s)))]
      (loop [val (/ 1 n) i dots]
        (if (zero? i) val
                      (recur (+ val (/ val 2)) (dec i)))))))

;; ============================================================
;; Initial state
;; ============================================================

(defn- initial-state
  ([input] (flat/initial-state input))
  ([input session] (flat/initial-state input session)))

;; ============================================================
;; Tag predicates
;; ============================================================

(defn- tag? [node t]
  (and (vector? node) (= (first node) t)))

(defn- node-text [state node]
  (when-let [input (:input state)]
    (let [m (meta node)]
      (when-let [start (:instaparse.gll/start-index m)]
        (subs input start (:instaparse.gll/end-index m))))))

;; ============================================================
;; Pitch resolution
;; ============================================================

(defn- resolve-pitch-from-tree [pitch-children state]
  (let [name-str     (some-> (first (filter #(tag? % :PitchLetter) pitch-children)) second)
        accidental   (some-> (first (filter #(tag? % :Accidental)  pitch-children)) second)
        octave-abs   (some-> (first (filter #(tag? % :OctaveAbs)   pitch-children)) second)
        octave-ticks (some-> (first (filter #(tag? % :OctaveTicks) pitch-children)) second)
        octave-spec  (or octave-abs octave-ticks "")
        last-midi    @(:last-pitch state)]
    (leaf/resolve-pitch [name-str (or accidental "") octave-spec] last-midi)))

;; ============================================================
;; Child extraction helpers
;; ============================================================

(defn- find-child     [children tag] (first (filter #(tag? % tag) children)))
(defn- find-all-children [children tag] (filter #(tag? % tag) children))

(defn- extract-duration [children]
  (let [dur-node (or (find-child children :DurationNum)
                     (find-child children :DurationSpecial))]
    (when dur-node (parse-duration (second dur-node)))))

(defn- extract-articulation [children]
  (let [art-node (find-child children :Articulation)]
    (when art-node
      (let [art-children (rest art-node)
            shorthand    (some-> (find-child art-children :ArticulationShorthand) second)
            name-node    (find-child art-children :Name)]
        (leaf/resolve-articulation (or shorthand (when name-node (second name-node))))))))

(defn- extract-modifiers
  "Extract modifiers, ornaments and tremolo from note/chord children.
   Tremolo is now a NoteSuffix: c4:32 produces [:Tremolo [:Int '32']]."
  [children]
  (for [node (concat (find-all-children children :Modifier)
                     (find-all-children children :Ornament)
                     (find-all-children children :Tremolo))]
    (let [sub-children (rest node)]
      (case (first node)
        :Modifier
        (let [name-node (find-child sub-children :Name)
              name      (when name-node (second name-node))
              val-node  (first (filter #(not (tag? % :Name)) sub-children))
              val       (when val-node
                          (if (tag? val-node :Int)
                            (parse-duration (second val-node))
                            (second val-node)))]
          [(str "mod_" name) val])
        :Ornament
        (let [name-node (find-child sub-children :Name)
              name      (when name-node (second name-node))]
          ["ornament" name])
        :Tremolo
        (let [int-node (find-child sub-children :Int)
              subdiv   (when int-node (Integer/parseInt (second int-node)))]
          ["tremolo" subdiv])))))

(defn- has-tie? [children] (boolean (find-child children :Tie)))

;; ============================================================
;; Ramp helpers
;; ============================================================

(defn- resolve-ip
  "Derive interpolation type from curve prefix and direction strings.
   CurvePrefix and Direction are transparent grammar rules -- they appear
   as bare strings in the Ramp node's children."
  [curve dir]
  (case [curve dir]
    ([nil "<"] ["l" "<"]) :lin-up
    ([nil ">"] ["l" ">"]) :lin-down
    ["s" "<"]             :smooth
    ["s" ">"]             :smooth
    ["i" "<"]             :ease-in
    ["i" ">"]             :ease-in
    ["o" "<"]             :ease-out
    ["o" ">"]             :ease-out
    :lin-up))

(defn- ramp-curve
  "Extract curve prefix string from Ramp children.
   Handles both tagged [:CurvePrefix 's'] and transparent 's' forms."
  [ramp-children]
  (let [prefixes #{"l" "s" "i" "o"}]
    (or (some-> (find-child ramp-children :CurvePrefix) second)
        (first (filter prefixes (filter string? ramp-children))))))

(defn- ramp-direction
  "Extract direction string from Ramp children.
   Handles both tagged [:Direction '<'] and transparent '<' forms."
  [ramp-children]
  (let [directions #{"<" ">"}]
    (or (some-> (find-child ramp-children :Direction) second)
        (first (filter directions (filter string? ramp-children))))))

(defn- parse-duration-expr-node
  "Evaluate a DurationExpr parse node to a rational number.
   DurationExpr = DurationAtom (* DurationAtom)*
   Each DurationAtom wraps either an Int or a Ratio."
  [dur-node]
  (let [atoms (find-all-children (rest dur-node) :DurationAtom)]
    (reduce (fn [acc atom-node]
              (let [inner (first (rest atom-node))]
                (* acc (cond
                         (tag? inner :Ratio) (leaf/parse-duration-atom (second inner))
                         (tag? inner :Int)   (Integer/parseInt (second inner))
                         :else               1))))
            1
            atoms)))

(defn- parse-target-node
  "Resolve a Target parse node to a numeric value.
   Target = DynamicMark | Float | Int
   DynamicMark -> velocity via leaf/resolve-dynamic
   Float/Int   -> numeric value directly."
  [target-node]
  (when target-node
    (let [inner (first (rest target-node))]
      (cond
        (tag? inner :DynamicMark) (leaf/resolve-dynamic (second inner))
        (tag? inner :Float)       (Double/parseDouble (second inner))
        (tag? inner :Int)         (Integer/parseInt (second inner))
        :else                     nil))))

(defn- ctx-local-value
  "Value already active for key in ctx's OWN envelope at time, or nil if
   this context has no local envelope for key (yet). Only looks locally --
   at tree-walking time the enclosing ctx-chain isn't known (see
   context.clj), so a ramp can only pick up a start value the author has
   already set earlier in this same context (e.g. `!vol:pp` before
   `!vol:<16:ff`)."
  [ctx key time]
  (when-let [env (clojure.core/get @(:envelopes-atom ctx) (name key))]
    (c/env-get env time)))

;; ============================================================
;; StructValue extraction  !name:(v1 v2 ...)
;; ============================================================

(defn- extract-struct-value-item
  "Extract a single ExtendedDataElement child of a StructValue into a plain
   Clojure value. StructValue content is a literal data list, not walked via
   walk-children (there's no container to append into), so each element is
   evaluated inline here instead."
  [node]
  (when (vector? node)
    (case (first node)
      :Int             (Integer/parseInt (second node))
      :Float           (Double/parseDouble (second node))
      :Ratio           (let [parts (str/split (second node) #"/")]
                          (/ (Integer/parseInt (first parts))
                             (Integer/parseInt (second parts))))
      :StringLit       (second node)
      :Keyword         (keyword (second node))
      :Name            (second node)
      :BangConst       (keyword (second (find-child (rest node) :Name)))
      :DurationNum     (parse-duration (second node))
      :DurationSpecial (parse-duration (second node))
      :Articulation    (let [ac        (rest node)
                             shorthand (some-> (find-child ac :ArticulationShorthand) second)
                             name-node (find-child ac :Name)]
                         (leaf/resolve-articulation (or shorthand (when name-node (second name-node)))))
      :Pitch           (first (leaf/resolve-pitch
                                [(some-> (find-child (rest node) :PitchLetter) second)
                                 (or (some-> (find-child (rest node) :Accidental) second) "")
                                 (or (some-> (find-child (rest node) :OctaveAbs) second)
                                     (some-> (find-child (rest node) :OctaveTicks) second)
                                     "")]
                                nil))
      nil)))

(defn- extract-struct-values
  "Extract all ExtendedDataElement values from a StructValue node into a
   plain Clojure vector, e.g. !env:(1 2 3) -> [1 2 3]."
  [struct-node]
  (into [] (keep extract-struct-value-item) (rest struct-node)))

;; ============================================================
;; Context reference application
;; ============================================================

(defn- apply-context-ref
  "Apply a referenced :CONTEXT's envelope data to the current container's
   context. All points are offset by the current beat position so they
   take effect at the right moment in the enclosing sequence.

   Example: ^[ my-ctx: !tempo:120 ] registered at t=0.
   Referenced at beat 4: tempo point added at t=4 in current context."
  [state ref-ctx]
  (let [current-ctx (flat/current-context state)
        t           (d/duration (:repo state) (peek (:stack state)))]
    (doseq [[k env] @(:envelopes-atom ref-ctx)]
      (doseq [pt @(:points-atom env)]
        (c/ctx-append current-ctx (keyword k)
                      (+ t (:time pt))
                      (:value pt)
                      (:ip pt))))
    state))

;; ============================================================
;; Main walker dispatch
;; ============================================================

(declare walk-children
         walk-context walk-reference
         walk-bang-const walk-assignment walk-key-assignment
         walk-note walk-chord walk-rest walk-drum
         walk-bareword walk-primitive walk-container-field
         walk-times walk-tuplet walk-transpose
         walk-repeat walk-tremolo walk-grace)

(defn- walk-element
  [state node]
  (if (string? node)
    state
    (let [tag      (first node)
          children (rest node)]
      (case tag
        ;; ---- Composites ----
        :Context     (walk-context state children)
        :Sequence    (let [s (flat/push-container state :SEQ)]
                       (->> (walk-children s children) flat/pop-container))
        :Parallel    (let [s (flat/push-container state :PAR)]
                       (->> (walk-children s children) flat/pop-container))
        :Data        (let [s (flat/push-container state :DATA)]
                       (->> (walk-children s children) flat/pop-container))
        :AtomicAlgo  (let [s (flat/push-container state :ATOMIC_ALGO)]
                       (->> (walk-children s children) flat/pop-container))
        :ElementAlgo (let [s (flat/push-container state :ELEMENT_ALGO)]
                       (->> (walk-children s children) flat/pop-container))
        ;; ---- Container identifying fields (Data's `type`, Algo's `algo`) ----
        ;; Both wrap a bare Name and identify the container, not its content --
        ;; stamp them onto the container being built rather than appending
        ;; them as a data child.
        :type        (walk-container-field state children :data-type)
        :algo        (walk-container-field state children :algo)
        ;; ---- References ----
        :Reference   (walk-reference state children)
        ;; ---- Instructions ----
        :BangConst    (walk-bang-const    state children)
        :Assignment   (walk-assignment    state children)
        :KeyAssignment (walk-key-assignment state children)
        :SlurStart    (flat/append-child state {:type :slur-start})
        :SlurEnd      (flat/append-child state {:type :slur-end})
        :BarLine      (flat/append-child state (d/bar (count (first children))))
        ;; ---- Leaves ----
        :Note  (walk-note  state children (node-text state node))
        :Chord (walk-chord state children (node-text state node))
        :Rest  (walk-rest  state children (node-text state node))
        :Drum  (walk-drum  state children (node-text state node))
        ;; ---- Id / Primitives ----
        :Id        (walk-bareword  state children)
        :Int       (walk-primitive state :int     children)
        :Float     (walk-primitive state :float   children)
        :Ratio     (walk-primitive state :ratio   children)
        :StringLit (walk-primitive state :string  children)
        :Keyword   (walk-primitive state :keyword children)
        :Name      (walk-primitive state :name    children)
        ;; ---- Bare Atoms inside Data containers ----
        ;; Pitch/Duration/Articulation only ever reach generic dispatch as a
        ;; bare DataElement ('[ ]) -- Note/Chord/Rest/Drum extract their own
        ;; via find-child directly and never recurse into these via
        ;; walk-element, so there's no risk of double-handling here.
        :Pitch     (let [[midi new-last] (resolve-pitch-from-tree children state)]
                     (reset! (:last-pitch state) new-last)
                     (flat/append-child state {:type :pitch :val midi}))
        :DurationNum     (flat/append-child state {:type :duration :val (parse-duration (first children))})
        :DurationSpecial (flat/append-child state {:type :duration :val (parse-duration (first children))})
        :Articulation
        (flat/append-child state
          {:type :articulation
           :val  (leaf/resolve-articulation
                   (or (some-> (find-child children :ArticulationShorthand) second)
                       (some-> (find-child children :Name) second)))})
        ;; ---- Commands ----
        :times     (walk-times     state children)
        :tuplet    (walk-tuplet    state children)
        :transpose (walk-transpose state children)
        :repeat    (walk-repeat    state children)
        :tremolo   (walk-tremolo   state children)
        :grace     (walk-grace     state children)
        ;; ---- Fallback: descend ----
        (reduce walk-element state children)))))

(defn- walk-children [state children]
  (loop [st state remaining (vec children)]
    (if (seq remaining)
      (recur (walk-element st (first remaining)) (rest remaining))
      st)))

;; ============================================================
;; Context definition  ^[ id: instructions ]
;; ============================================================

(defn- walk-context
  "Walk a ^[ ] Context definition block.
   Pushes a :CONTEXT container, walks its instructions (which call
   ctx-append on the context's own envelopes), then pops.
   pop-container registers it in repo WITHOUT appending to parent's
   children -- it is a definition form, not musical content."
  [state children]
  (let [s (flat/push-container state :CONTEXT)]
    (->> (walk-children s children) flat/pop-container)))

;; ============================================================
;; Reference  :name
;; ============================================================

(defn- walk-reference
  "Walk a :name Reference node.
   Looks up the name in repo:
   - :CONTEXT type -> apply its envelopes to the current container's context
   - anything else -> append its keyword id as a child (container/iterator ref)"
  [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))
        id        (keyword name-val)
        resolved  (get (:repo state) id)]
    (cond
      ;; Named context reference -- apply envelopes at current beat position
      (and resolved (= (:type resolved) :CONTEXT))
      (apply-context-ref state (:context resolved))

      ;; Container/iterator reference -- append id as child
      :else
      (flat/append-child state id))))

;; ============================================================
;; Id naming (definition-time: my-name:)
;; ============================================================

(defn- walk-bareword [state children]
  (let [name-val (some (fn [c]
                         (cond
                           (string? c) (keyword c)
                           (and (vector? c) (= :Name (first c))) (keyword (second c))))
                       children)]
    (if name-val
      (let [idx (dec (count (:stack state)))]
        (update-in state [:stack idx :id] (constantly name-val)))
      state)))

;; ============================================================
;; Container identifying fields  ('[ type ... ]  @'[ algo ... ]  @[ algo ... ])
;; ============================================================

(defn- walk-container-field
  "Stamp a bare Name value (Data's `type`, AtomicAlgo/ElementAlgo's `algo`)
   onto the container currently on top of the stack, under field.
   These identify the container itself -- they are not musical/data content,
   so they must not be appended as a child."
  [state children field]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))]
    (if name-val
      (let [idx (dec (count (:stack state)))]
        (update-in state [:stack idx field] (constantly (keyword name-val))))
      state)))

;; ============================================================
;; Instructions
;; ============================================================

(defn duration
  "Accumulated duration of the current container on the stack.
   Used as the time coordinate for ctx-append calls."
  [state]
  (d/duration (:repo state) (peek (:stack state))))

(defn- walk-bang-const [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))
        kw        (keyword name-val)]
    (if name-val
      (let [obj    {:type :instruction :const kw :raw (str "!" name-val)}
            ctx    (flat/current-context state)
            t      (duration state)
            state' (flat/append-child state obj)]
        (when-let [[ctx-key ctx-val] (data/instruction-context kw)]
          (c/ctx-append ctx ctx-key t ctx-val :fixed))
        state')
      state)))

(defn- walk-assignment [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))]
    (if name-val
      (let [val-nodes (filter #(not (tag? % :Name)) children)
            val-node  (first val-nodes)
            val-tag   (when val-node (first val-node))
            val       (when val-node (second val-node))
            ctx       (flat/current-context state)
            t         (duration state)]
        (case val-tag
          :Ramp
          (let [ramp-children (rest val-node)
                curve         (ramp-curve      ramp-children)
                dir           (ramp-direction  ramp-children)
                ip            (resolve-ip curve dir)
                dur-node      (find-child ramp-children :DurationExpr)
                target-node   (find-child ramp-children :Target)]
            (if (and dur-node target-node)
              ;; ---- Timed ramp: !vol<s:16*4:ff ----
              ;; Two envelope points, so the ramp has both ends:
              ;;   1. at t       -- the value already active for this key
              ;;      locally (the author must have set it earlier in this
              ;;      same context, e.g. `!vol:pp` before `!vol<16:ff`),
              ;;      re-stamped with the ramp's ip so interpolation starts
              ;;      here (env-get uses the LEFT point's ip as the curve).
              ;;   2. at t+dur   -- the target value, :fixed so it holds
              ;;      until a later instruction changes it again.
              ;; If no local value exists yet at t, there is nothing to ramp
              ;; from -- fall back to just the target point (old behavior).
              (let [dur       (parse-duration-expr-node dur-node)
                    target    (parse-target-node target-node)
                    start-val (ctx-local-value ctx (keyword name-val) t)
                    obj       {:type :assignment
                               :key  (keyword name-val)
                               :val  {:dir dir :curve curve :dur dur :target target}
                               :raw  (str "!" name-val dir (when curve (str curve ":")) dur ":" target)}
                    state'    (flat/append-child state obj)]
                (when target
                  (when start-val
                    (c/ctx-append ctx (keyword name-val) t start-val ip))
                  (c/ctx-append ctx (keyword name-val) (+ t dur) target :fixed))
                state')
              ;; ---- Open-ended ramp: !vol< ----
              (let [obj    {:type :assignment
                            :key  (keyword name-val)
                            :val  (str "ramp" dir)
                            :raw  (str "!" name-val dir curve)}
                    state' (flat/append-child state obj)]
                (c/ctx-append ctx (keyword name-val) t :ramp-start ip)
                state')))

          :Int
          (let [parsed-val (Integer/parseInt val)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" val)}
                state' (flat/append-child state obj)]
            (when (= name-val "key")
              (when-let [ks (or (el/parse-key val) (el/parse-key (str val ".major")))]
                (c/ctx-append ctx :key t ks :fixed)))
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed)
            state')

          :Float
          (let [parsed-val (Double/parseDouble val)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" val)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed)
            state')

          :QualifiedName
          ;; A single bare name (no dots) that happens to be a dynamic mark
          ;; -- e.g. `!vol:pp` -- resolves to its numeric velocity, same as
          ;; a Ramp's Target does. One usually writes `!pp` (BangConst)
          ;; instead, but `!<key>:pp` must still work and produce a usable
          ;; number, not the bare keyword, since a later timed ramp may
          ;; read this value back as its start point (see ctx-local-value).
          ;; Genuinely dotted/symbolic names (`!key:C.major`, scale names,
          ;; etc.) fall through to the keyword form as before.
          (let [name-children (rest val-node)
                names         (mapv second (filter #(tag? % :Name) name-children))
                key-str       (str/join "." names)
                dyn-val       (when (= 1 (count names)) (leaf/resolve-dynamic key-str))
                parsed-val    (or dyn-val (keyword key-str))
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" key-str)}
                state' (flat/append-child state obj)]
            (when (= name-val "key")
              (when-let [ks (or (el/parse-key key-str) (el/parse-key (str key-str ".major")))]
                (c/ctx-append ctx :key t ks :fixed)))
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed)
            state')

          :StringLit
          (let [obj    {:type :assignment :key (keyword name-val)
                        :val val :raw (str "!" name-val ":\"" val "\"")}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx (keyword name-val) t val :fixed)
            state')

          :StructValue
          (flat/append-child state {:type :struct-assign
                                    :key  (keyword name-val)
                                    :val  (extract-struct-values val-node)
                                    :raw  (str "!" name-val ":" val)})

          ;; Fallback
          (flat/append-child state {:type :assignment :key (keyword name-val)
                                    :val val :raw (str "!" name-val ":" (pr-str val))})))
      state)))

(defn- walk-key-assignment [state children]
  (let [key-node (find-child children :KeySpec)
        key-val  (when key-node (second key-node))]
    (if key-val
      (let [ctx    (flat/current-context state)
            ks     (or (el/parse-key key-val) (el/parse-key (str key-val ".major")))
            obj    {:type :assignment :key :key :val key-val :raw (str "!key:" key-val)}
            t      (duration state)
            state' (flat/append-child state obj)]
        (when ks (c/ctx-append ctx :key t ks :fixed))
        state')
      state)))

;; ============================================================
;; Leaf nodes
;; ============================================================

(defn- walk-note [state children token]
  (let [ctx        (flat/current-context state)
        pitch-node (find-child children :Pitch)
        dur        (or (extract-duration children) @(:last-dur state))
        art        (extract-articulation children)
        modifiers  (extract-modifiers children)
        tied       (has-tie? children)]
    (if pitch-node
      (let [[midi new-last] (resolve-pitch-from-tree (rest pitch-node) state)]
        (reset! (:last-pitch state) new-last)
        (when dur (reset! (:last-dur state) dur))
        (flat/append-child state
                           (d/leaf (or token (str "note-" midi))
                                   (or ctx (c/context)) dur (if midi [midi] [])
                                   art (when (map? art) (:dynamic art)) modifiers tied)))
      state)))

(defn- walk-chord [state children token]
  (let [ctx       (flat/current-context state)
        pitches   (filter #(tag? % :Pitch) children)
        dur       (or (extract-duration children) @(:last-dur state))
        art       (extract-articulation children)
        modifiers (extract-modifiers children)
        tied      (has-tie? children)]
    (if (seq pitches)
      (let [midis  (atom [])
            last-p (atom @(:last-pitch state))]
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m) (reset! last-p l)))
        (reset! (:last-pitch state) @last-p)
        (when dur (reset! (:last-dur state) dur))
        (flat/append-child state
                           (d/leaf (or token (str "chord-" (str/join "-" @midis)))
                                   (or ctx (c/context)) dur (vec @midis)
                                   art (when (map? art) (:dynamic art)) modifiers tied)))
      state)))

(defn- walk-rest [state children token]
  (let [ctx (flat/current-context state)
        dur (or (extract-duration children) @(:last-dur state))]
    (when dur (reset! (:last-dur state) dur))
    (flat/append-child state
                       (d/rest* (or token (str "rest-" dur)) (or ctx (c/context)) dur))))

(defn- walk-drum [state children token]
  (let [ctx      (flat/current-context state)
        dur      (or (extract-duration children) @(:last-dur state))
        drum-mod (find-child children :DrumMod)
        prog     (when drum-mod
                   (let [inner (first (rest drum-mod))
                         val   (second inner)]
                     (data/resolve-drum val)))]
    (flat/append-child state
                       (d/drum (or token (str "drum-" (or prog "?")))
                               (or ctx (c/context)) (or dur 1/4) prog))))

;; ============================================================
;; Primitives
;; ============================================================

(defn- walk-primitive [state type children]
  (let [val (first children)]
    (case type
      :int     (flat/append-child state {:type :int     :val (Integer/parseInt val)})
      :float   (flat/append-child state {:type :float   :val (Double/parseDouble val)})
      :ratio   (let [parts (str/split val #"/")]
                 (flat/append-child state
                                    {:type :ratio :val (/ (Integer/parseInt (first parts))
                                                          (Integer/parseInt (second parts)))}))
      :string  (flat/append-child state {:type :string  :val val})
      :keyword (flat/append-child state {:type :keyword :val (keyword val)})
      :name    (flat/append-child state {:type :name    :val val})
      state)))

;; ============================================================
;; Command helpers
;; ============================================================

(defn- parse-ratio-str [s]
  (when s
    (let [parts (str/split s #"/")]
      (/ (Integer/parseInt (first parts))
         (Integer/parseInt (second parts))))))

(defn- make-iterator
  "Create an Iterator and append it to the current parent.
   No parent context wiring -- enclosing context is visit-dependent
   and resolved by form-unroll at traversal time."
  [state iter-type source params]
  (let [ctx     (c/context)
        iter-id (flat/next-auto-id state iter-type)]
    (flat/append-child state (d/iterator iter-type iter-id ctx source params))))

;; ============================================================
;; Command handlers — Transient
;; ============================================================

(defn- walk-times [state children]
  (let [factor-node (find-child children :multiply-factor)
        ratio-node  (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str   (when ratio-node (second ratio-node))
        seq-node    (find-child children :Sequence)
        factor      (parse-ratio-str ratio-str)]
    (if (and factor seq-node)
      (-> state
          (flat/push-container :TIMES)
          (walk-children (rest seq-node))
          (flat/scale-durations! factor)
          flat/pop-container)
      state)))

(defn- walk-tuplet [state children]
  (let [factor-node (find-child children :divide-factor)
        ratio-node  (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str   (when ratio-node (second ratio-node))
        seq-node    (find-child children :Sequence)
        factor      (when ratio-str
                      (let [parts (str/split ratio-str #"/")]
                        (/ (Integer/parseInt (second parts))
                           (Integer/parseInt (first parts)))))]
    (if (and factor seq-node)
      (-> state
          (flat/push-container :TUPLET)
          (walk-children (rest seq-node))
          (flat/scale-durations! factor)
          flat/pop-container)
      state)))

(defn- walk-transpose [state children]
  (let [from-node (find-child children :from-pitch)
        to-node   (find-child children :to-pitch)
        seq-node  (find-child children :Sequence)]
    (if (and from-node to-node seq-node)
      (let [from-pitch (find-child (rest from-node) :Pitch)
            to-pitch   (find-child (rest to-node)   :Pitch)
            [from-midi _] (resolve-pitch-from-tree (rest from-pitch) state)
            [to-midi   _] (resolve-pitch-from-tree (rest to-pitch)   state)
            interval      (- to-midi from-midi)]
        (-> state
            (flat/push-container :TRANSPOSE)
            (walk-children (rest seq-node))
            (flat/transpose-pitches! interval)
            flat/pop-container))
      state)))

(defn- grace-tag [tag]
  #(cond-> %
           (:modifiers %) (update :modifiers conj ["grace" tag])))

(def ^:private grace-cap
  "Grace notes may borrow at most this fraction of the main note's own
   duration."
  1/4)

(defn- borrow-grace-duration
  "Rescale grace-part and main-part so the grace notes' combined duration
   is non-zero, capped at grace-cap of the main note's own duration, and
   taken directly from the main note: the main note shrinks by exactly the
   (possibly capped) grace duration, so the pair's total duration is
   unchanged. grace-part/main-part may be inline leaves or keyword ids
   into repo (e.g. a bracketed group of several grace notes)."
  [repo grace-part main-part]
  (let [grace-dur (d/duration repo grace-part)
        main-dur  (d/duration repo main-part)]
    (if (and (pos? grace-dur) (pos? main-dur))
      (let [effective-grace (min grace-dur (* main-dur grace-cap))
            grace-factor    (/ effective-grace grace-dur)
            main-factor     (/ (- main-dur effective-grace) main-dur)
            [repo'  grace'] (d/scale-duration repo  grace-part grace-factor)
            [repo'' main']  (d/scale-duration repo' main-part  main-factor)]
        [repo'' grace' main'])
      [repo grace-part main-part])))

(defn- walk-grace [state children]
  (let [grace-type    (some-> (first (filter string? children))
                              (str/replace #"\\" ""))
        element-nodes (filter (complement string?) children)
        after?        (= grace-type "afterGrace")
        built         (-> state
                          (flat/push-container :DECORATED)
                          (#(reduce walk-element % element-nodes)))
        [c1 c2]                (:children (peek (:stack built)))
        ;; \afterGrace captures [main-note grace-note]; the other four
        ;; keywords capture [grace-note main-note].
        [grace-part main-part] (if after? [c2 c1] [c1 c2])
        [repo' grace' main']   (borrow-grace-duration (:repo built) grace-part main-part)
        grace''                ((grace-tag (or grace-type "grace")) grace')
        new-children           (if after? [main' grace''] [grace'' main'])]
    (-> built
        (assoc :repo repo')
        (flat/set-children! new-children)
        flat/pop-container)))

(defn- walk-tremolo [state children]
  (let [divisor-node (find-child children :divisor)
        seq-node     (find-child children :Sequence)]
    (if (and divisor-node seq-node)
      ;; Measured tremolo: \repeat tremolo N [ seq ] -> Iterator
      (let [div-int   (find-child (rest divisor-node) :Int)
            count-val (when div-int (Integer/parseInt (second div-int)))
            s1        (flat/push-container state :SEQ)
            s2        (walk-children s1 (rest seq-node))
            s3        (update s2 :stack pop)
            src       (peek (:stack s2))]
        (make-iterator s3 :TREMOLO src {:count count-val}))
      ;; Note/Chord tremolo: now handled as :Tremolo NoteSuffix
      ;; The note/chord walker picks it up via extract-modifiers.
      ;; This branch is a fallback for unexpected tree shapes.
      state)))

;; ============================================================
;; Command handlers — Iterator
;; ============================================================

(defn- walk-repeat [state children]
  (let [repeat-type  (some #{"volta" "unfold"} children)
        repeats-node (find-child children :repeats)
        count-int    (when repeats-node (find-child (rest repeats-node) :Int))
        count-val    (when count-int (Integer/parseInt (second count-int)))
        seq-node     (find-child children :Sequence)
        volta-node   (find-child children :volta)]
    (if (and count-val seq-node)
      (let [s1            (flat/push-container state :SEQ)
            s2            (walk-children s1 (rest seq-node))
            seq-composite (peek (:stack s2))
            s3            (update s2 :stack pop)
            [s4 alt]
            (if volta-node
              (let [alt-seq (find-child (rest volta-node) :Sequence)]
                (if alt-seq
                  (let [sa (flat/push-container s3 :SEQ)
                        sb (walk-children sa (rest alt-seq))
                        a  (peek (:stack sb))
                        sc (update sb :stack pop)]
                    [sc a])
                  [s3 nil]))
              [s3 nil])
            params (cond-> {:count       count-val
                            :repeat-type (keyword (or repeat-type "unfold"))}
                           alt (assoc :alternative alt))]
        (make-iterator s4 :REPEAT seq-composite params))
      state)))

;; ============================================================
;; Public API
;; ============================================================

(defn walk
  "Walk a raw instaparse tree and build domain objects.
   Returns {:tree map :root-id keyword :auto-ids map} where :tree is the
   id->container map. input is the original parsed text (for token ID
   extraction via insta/span). session, if given, is an existing
   {:repo :auto-ids} to continue building onto (same :ROOT, id counters
   picking up where they left off) instead of starting fresh."
  [tree & [input session]]
  (let [state            (initial-state input session)
        program-children (rest tree)]
    (loop [st state remaining (vec program-children)]
      (if (seq remaining)
        (recur (walk-element st (first remaining)) (rest remaining))
        (flat/finish st)))))