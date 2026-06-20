;; tree_walker.clj
;; Post-parse phase: walk the instaparse tree and build domain objects.
;;
;; Architecture: manual recursive tree walker.
;; - Walks the raw instaparse tree directly (no pre-processing copy)
;; - Duration ambiguity resolved at grammar level (ordered choice)
;; - Dispatches on node tags, maintains mutable state
;; - Reuses existing leaf-parser, music-data, music-elements
;;
;; Pipeline: instaparse tree → walk-tree → domain objects

(ns input.reader.tree-walker
  (:require [core.domain.music-domain :as d]
            [common.data.defaults :as defaults]
            [common.data.music-data :as data]
            [input.reader.parser.leaf-parser :as leaf]
            [common.elements.music-elements :as el]
            [clojure.string :as str]))

;; ============================================================
;; Duration parsing (from music_parser.clj)
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
      (loop [val (/ 1 n)
             i dots]
        (if (zero? i)
          val
          (recur (+ val (/ val 2)) (dec i)))))))

;; ============================================================
;; State and context helpers
;; ============================================================

(defn- initial-state
  "Create the initial walker state."
  []
  (let [init-ctx (d/context-root (defaults/root-defaults))]
    {:stack      [(d/make-score init-ctx)]
     :auto-ids   (atom {})
     :last-pitch (atom nil)
     :last-dur   (atom 1/4)}))

(def ^:private transient-types
  "Container types that produce Transients (inline children into parent on pop)."
  #{:LIST :TIMES :TUPLET :TRANSPOSE :DECORATED})

(defn- push-container
  "Push a new container onto the stack, inheriting parent context.
   Transient types inline their children on pop; others become Composites."
  [state container-type]
  (let [parent     (peek (:stack state))
        parent-ctx (:context parent)
        ctx        (d/context parent-ctx)
        auto-ids   @(:auto-ids state)
        n          (get auto-ids container-type 0)
        next-id    (str (name container-type) "." (inc n))
        container  (if (transient-types container-type)
                     (d/transient* container-type next-id ctx)
                     (d/composite container-type next-id ctx))]
    (swap! (:auto-ids state) assoc container-type (inc n))
    (update state :stack conj container)))

(defn- pop-container
  "Pop the top container and append it (or its children) to the parent."
  [state]
  (let [current    (peek (:stack state))
        rest-stack (pop (:stack state))
        parent     (peek rest-stack)]
    (when parent
      (if (d/transient? current)
        (doseq [child (d/transient-children current)]
          (d/composite-append parent child))
        (d/composite-append parent current)))
    (assoc state :stack rest-stack)))

(defn- current-context [state]
  (:context (peek (:stack state))))

;; ============================================================
;; Tag predicates
;; ============================================================

(defn- tag? [node t]
  (and (vector? node) (= (first node) t)))

;; ============================================================
;; Pitch resolution (using leaf-parser)
;; ============================================================

(defn- resolve-pitch-from-tree
  [pitch-children state]
  (let [name-str     (some-> (first (filter #(tag? % :PitchLetter) pitch-children)) second)
        accidental   (some-> (first (filter #(tag? % :Accidental) pitch-children)) second)
        octave-abs   (some-> (first (filter #(tag? % :OctaveAbs) pitch-children)) second)
        octave-ticks (some-> (first (filter #(tag? % :OctaveTicks) pitch-children)) second)
        octave-spec  (or octave-abs octave-ticks "")
        last-midi    @(:last-pitch state)]
    (leaf/resolve-pitch [name-str (or accidental "") octave-spec] last-midi)))

;; ============================================================
;; Child extraction helpers
;; ============================================================

(defn- find-child [children tag]
  (first (filter #(tag? % tag) children)))

(defn- find-all-children [children tag]
  (filter #(tag? % tag) children))

(defn- extract-duration
  [children]
  (let [dur-node (or (find-child children :DurationNum)
                     (find-child children :DurationSpecial))]
    (when dur-node (parse-duration (second dur-node)))))

(defn- extract-articulation
  [children]
  (let [art-node (find-child children :Articulation)]
    (when art-node
      (let [art-children (rest art-node)
            shorthand    (some-> (find-child art-children :ArticulationShorthand) second)
            name-node    (find-child art-children :Name)]
        (leaf/resolve-articulation (or shorthand (when name-node (second name-node))))))))

(defn- extract-modifiers
  [children]
  (for [node (concat (find-all-children children :Modifier)
                     (find-all-children children :Ornament))]
    (let [sub-children (rest node)
          name-node    (find-child sub-children :Name)
          name         (when name-node (second name-node))]
      (if (= (first node) :Modifier)
        (let [val-node (first (filter #(not (tag? % :Name)) sub-children))
              val      (when val-node
                         (if (tag? val-node :Int) (parse-duration (second val-node))
                           (second val-node)))]
          [(str "mod_" name) val])
        ["ornament" name]))))

(defn- has-tie? [children]
  (boolean (find-child children :Tie)))

;; ============================================================
;; Main walker dispatch
;; ============================================================

(declare walk-children
         walk-bang-const walk-assignment walk-key-assignment
         walk-note walk-chord walk-rest walk-drum
         walk-bareword walk-primitive
         walk-times walk-tuplet walk-transpose
         walk-repeat walk-tremolo walk-grace)

(defn- walk-element
  [state node]
  (if (string? node)
    state
    (let [tag      (first node)
          children (rest node)]
      (case tag
        :Sequence   (let [s (push-container state :SEQ)]
                      (->> (walk-children s children) (pop-container)))
        :Parallel   (let [s (push-container state :PAR)]
                      (->> (walk-children s children) (pop-container)))
        :Data       (let [s (push-container state :DATA)]
                      (->> (walk-children s children) (pop-container)))
        :List       (let [s (push-container state :LIST)]
                      (->> (walk-children s children) (pop-container)))
        :Quoted     (let [s (push-container state :QUOTE)]
                      (->> (walk-children s children) (pop-container)))
        :BangConst    (walk-bang-const state children)
        :Assignment   (walk-assignment state children)
        :KeyAssignment (walk-key-assignment state children)
        :Note   (walk-note state children)
        :Chord  (walk-chord state children)
        :Rest   (walk-rest state children)
        :Drum   (walk-drum state children)
        :BareWord (walk-bareword state children)
        :Int       (walk-primitive state :int children)
        :Float     (walk-primitive state :float children)
        :Ratio     (walk-primitive state :ratio children)
        :StringLit (walk-primitive state :string children)
        :Keyword   (walk-primitive state :keyword children)
        :Name      (walk-primitive state :name children)
        :VarDef state
        ;; Commands
        :times     (walk-times state children)
        :tuplet    (walk-tuplet state children)
        :transpose (walk-transpose state children)
        :repeat    (walk-repeat state children)
        :tremolo   (walk-tremolo state children)
        :grace     (walk-grace state children)
        (reduce walk-element state children)))))

(defn- walk-children
  "Walk a sequence of child nodes sequentially.
   Duration ambiguity resolved at grammar level — no inline merging needed."
  [state children]
  (loop [st state
         remaining (vec children)]
    (if (seq remaining)
      (recur (walk-element st (first remaining))
             (rest remaining))
      st)))

;; ============================================================
;; BareWord naming
;; ============================================================

(defn- walk-bareword
  [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))]
    (if name-val
      (let [current (peek (:stack state))]
        (assoc state :stack (conj (pop (:stack state)) (assoc current :id name-val))))
      state)))

;; ============================================================
;; Instructions
;; ============================================================

(defn- walk-bang-const
  [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))
        kw        (keyword name-val)]
    (when name-val
      (let [obj {:type :instruction :const kw :raw (str "!" name-val)}
            ctx (current-context state)]
        (d/composite-append (peek (:stack state)) obj)
        (when-let [[ctx-key ctx-val] (data/instruction-context kw)]
          (d/ctx-append ctx ctx-key 0.0 ctx-val :fixed))))
    state))

(defn- walk-assignment
  [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))]
    (when name-val
      (let [val-nodes (filter #(not (tag? % :Name)) children)
            val-node  (first val-nodes)
            val-tag   (when val-node (first val-node))
            val       (when val-node (second val-node))
            ctx       (current-context state)]
        (case val-tag
          :Ramp
          (let [ramp-children (rest val-node)
                curve-node    (find-child ramp-children :CurvePrefix)
                dir-node      (find-child ramp-children :Direction)
                curve         (when curve-node (second curve-node))
                dir           (when dir-node (second dir-node))
                ip (case [curve dir]
                     ([nil "<"] ["l" "<"]) :lin-up
                     ([nil ">"] ["l" ">"]) :lin-down
                     (["s" "<"])           :smooth
                     (["s" ">"])           :smooth
                     (["i" "<"])           :ease-in
                     (["o" "<"])           :ease-out
                     (["i" ">"])           :ease-in
                     (["o" ">"])           :ease-out
                     :else                 :lin-up)]
            (let [obj {:type :assignment :key (keyword name-val)
                       :val (str "ramp" dir) :raw (str "!" name-val ":" dir)}]
              (d/composite-append (peek (:stack state)) obj)
              (d/ctx-append ctx (keyword name-val) 0.0 :ramp-start ip)))
          :Int
          (let [parsed-val (Integer/parseInt val)
                obj {:type :assignment :key (keyword name-val)
                     :val parsed-val :raw (str "!" name-val ":" val)}]
            (d/composite-append (peek (:stack state)) obj)
            (when (= name-val "key")
              (let [ks (or (el/parse-key val) (el/parse-key (str val ".major")))]
                (when ks (d/ctx-append ctx :key 0.0 ks :fixed))))
            (d/ctx-append ctx (keyword name-val) 0.0 parsed-val :fixed))
          :Float
          (let [parsed-val (Double/parseDouble val)
                obj {:type :assignment :key (keyword name-val)
                     :val parsed-val :raw (str "!" name-val ":" val)}]
            (d/composite-append (peek (:stack state)) obj)
            (d/ctx-append ctx (keyword name-val) 0.0 parsed-val :fixed))
          :QualifiedName
          (let [name-children (rest val-node)
                names         (mapv second (filter #(tag? % :Name) name-children))
                key-str       (str/join "." names)
                parsed-val    (keyword key-str)
                obj {:type :assignment :key (keyword name-val)
                     :val parsed-val :raw (str "!" name-val ":" key-str)}]
            (d/composite-append (peek (:stack state)) obj)
            (when (= name-val "key")
              (let [ks (or (el/parse-key key-str) (el/parse-key (str key-str ".major")))]
                (when ks (d/ctx-append ctx :key 0.0 ks :fixed))))
            (d/ctx-append ctx (keyword name-val) 0.0 parsed-val :fixed))
          :StringLit
          (let [obj {:type :assignment :key (keyword name-val)
                     :val val :raw (str "!" name-val ":\"" val "\"")}]
            (d/composite-append (peek (:stack state)) obj)
            (d/ctx-append ctx (keyword name-val) 0.0 val :fixed))
          :StructValue
          (let [obj {:type :struct-assign :key (keyword name-val)
                     :val val :raw (str "!" name-val ":" val)}]
            (d/composite-append (peek (:stack state)) obj))
          (let [obj {:type :assignment :key (keyword name-val)
                     :val val :raw (str "!" name-val ":" (pr-str val))}]
            (d/composite-append (peek (:stack state)) obj)))))
    state))

(defn- walk-key-assignment
  [state children]
  (let [key-node (find-child children :KeySpec)
        key-val  (when key-node (second key-node))]
    (when key-val
      (let [ctx (current-context state)
            ks  (or (el/parse-key key-val) (el/parse-key (str key-val ".major")))
            obj {:type :assignment :key :key :val key-val :raw (str "!key:" key-val)}]
        (d/composite-append (peek (:stack state)) obj)
        (when ks (d/ctx-append ctx :key 0.0 ks :fixed))))
    state))

;; ============================================================
;; Leaf nodes
;; ============================================================

(defn- walk-note
  [state children]
  (let [ctx        (current-context state)
        pitch-node (find-child children :Pitch)
        dur        (or (extract-duration children) @(:last-dur state))
        art        (extract-articulation children)
        modifiers  (extract-modifiers children)
        tied       (has-tie? children)]
    (when pitch-node
      (let [[midi new-last] (resolve-pitch-from-tree (rest pitch-node) state)]
        (reset! (:last-pitch state) new-last)
        (when dur (reset! (:last-dur state) dur))
        (let [leaf (d/leaf (str "note-" midi)
                           (or ctx (d/context)) dur (if midi [midi] [])
                           art (when (map? art) (:dynamic art)) modifiers tied)]
          (d/composite-append (peek (:stack state)) leaf))))
    state))

(defn- walk-chord
  [state children]
  (let [ctx       (current-context state)
        pitches   (filter #(tag? % :Pitch) children)
        dur       (or (extract-duration children) @(:last-dur state))
        art       (extract-articulation children)
        modifiers (extract-modifiers children)
        tied      (has-tie? children)]
    (when (seq pitches)
      (let [midis (atom [])
            last-p (atom @(:last-pitch state))]
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m) (reset! last-p l)))
        (reset! (:last-pitch state) @last-p)
        (when dur (reset! (:last-dur state) dur))
        (let [leaf (d/leaf (str "chord-" (str/join "-" @midis))
                           (or ctx (d/context)) dur (vec @midis)
                           art (when (map? art) (:dynamic art)) modifiers tied)]
          (d/composite-append (peek (:stack state)) leaf))))
    state))

(defn- walk-rest
  [state children]
  (let [ctx (current-context state)
        dur (or (extract-duration children) @(:last-dur state))]
    (when dur (reset! (:last-dur state) dur))
    (let [rest-obj (d/rest* (str "rest-" dur) (or ctx (d/context)) dur)]
      (d/composite-append (peek (:stack state)) rest-obj))
    state))

(defn- walk-drum
  [state children]
  (let [ctx      (current-context state)
        dur      (or (extract-duration children) @(:last-dur state))
        drum-mod (find-child children :DrumMod)
        prog     (when drum-mod
                   (let [inner (first (rest drum-mod))
                         val   (second inner)]
                     (data/resolve-drum val)))]
    (let [drum-obj (d/drum (str "drum-" (or prog "?"))
                           (or ctx (d/context)) (or dur 1/4) prog)]
      (d/composite-append (peek (:stack state)) drum-obj))
    state))

;; ============================================================
;; Primitives
;; ============================================================

(defn- walk-primitive
  [state type children]
  (let [val (first children)]
    (case type
      :int     (d/composite-append (peek (:stack state)) {:type :int :val (Integer/parseInt val)})
      :float   (d/composite-append (peek (:stack state)) {:type :float :val (Double/parseDouble val)})
      :ratio   (let [parts (str/split val #"/")]
                 (d/composite-append (peek (:stack state))
                   {:type :ratio :val (/ (Integer/parseInt (first parts))
                                         (Integer/parseInt (second parts)))}))
      :string  (d/composite-append (peek (:stack state)) {:type :string :val val})
      :keyword (d/composite-append (peek (:stack state)) {:type :keyword :val (keyword val)})
      :name    (d/composite-append (peek (:stack state)) {:type :name :val val})
      nil))
  state)

;; ============================================================
;; Command helpers
;; ============================================================

(defn- parse-ratio-str
  "Parse a ratio string like '2/3' into a Clojure ratio."
  [s]
  (when s
    (let [parts (str/split s #"/")]
      (/ (Integer/parseInt (first parts))
         (Integer/parseInt (second parts))))))

(defn- scale-durations!
  "Mutate children of a transient, scaling all durations by factor."
  [container factor]
  (swap! (:children-atom container)
         (fn [cs]
           (mapv (fn [child]
                   (if (:duration child)
                     (update child :duration * factor)
                     child))
                 cs))))

(defn- transpose-pitches!
  "Mutate children of a transient, transposing all pitches by interval."
  [container interval]
  (swap! (:children-atom container)
         (fn [cs]
           (mapv (fn [child]
                   (if (:pitches child)
                     (update child :pitches #(mapv (partial + interval) %))
                     child))
                 cs))))

(defn- make-iterator
  "Create an Iterator and append it to the current parent.
   Returns updated state."
  [state iter-type source params]
  (let [parent     (peek (:stack state))
        parent-ctx (:context parent)
        ctx        (d/context parent-ctx)
        auto-ids   @(:auto-ids state)
        n          (get auto-ids iter-type 0)
        iter-id    (str (name iter-type) "." (inc n))
        iterator   (d/iterator iter-type iter-id ctx source params)]
    (swap! (:auto-ids state) assoc iter-type (inc n))
    (d/composite-append parent iterator)
    state))

;; ============================================================
;; Command handlers — Transient (flatten immediately)
;; ============================================================

(defn- walk-times
  "\\times ratio {seq} — scale durations by multiply-factor, inline."
  [state children]
  (let [factor-node (find-child children :multiply-factor)
        ratio-node  (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str   (when ratio-node (second ratio-node))
        seq-node    (find-child children :Sequence)
        factor      (parse-ratio-str ratio-str)]
    (if (and factor seq-node)
      (let [s1 (push-container state :TIMES)
            s2 (walk-children s1 (rest seq-node))
            _  (scale-durations! (peek (:stack s2)) factor)]
        (pop-container s2))
      state)))

(defn- walk-tuplet
  "\\tuplet 3/2 {seq} — 3 in the time of 2 → factor 2/3, inline."
  [state children]
  (let [factor-node (find-child children :divide-factor)
        ratio-node  (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str   (when ratio-node (second ratio-node))
        seq-node    (find-child children :Sequence)
        ;; \tuplet N/D means "play N notes in time of D" → scale = D/N
        factor      (when ratio-str
                      (let [parts (str/split ratio-str #"/")]
                        (/ (Integer/parseInt (second parts))
                           (Integer/parseInt (first parts)))))]
    (if (and factor seq-node)
      (let [s1 (push-container state :TUPLET)
            s2 (walk-children s1 (rest seq-node))
            _  (scale-durations! (peek (:stack s2)) factor)]
        (pop-container s2))
      state)))

(defn- walk-transpose
  "\\transpose from to {seq} — shift all pitches by interval, inline."
  [state children]
  (let [from-node (find-child children :from-pitch)
        to-node   (find-child children :to-pitch)
        seq-node  (find-child children :Sequence)]
    (if (and from-node to-node seq-node)
      (let [from-pitch (find-child (rest from-node) :Pitch)
            to-pitch   (find-child (rest to-node) :Pitch)
            [from-midi _] (resolve-pitch-from-tree (rest from-pitch) state)
            [to-midi _]   (resolve-pitch-from-tree (rest to-pitch) state)
            interval      (- to-midi from-midi)
            s1 (push-container state :TRANSPOSE)
            s2 (walk-children s1 (rest seq-node))
            _  (transpose-pitches! (peek (:stack s2)) interval)]
        (pop-container s2))
      state)))

(defn- walk-decorated
  "Walk element(s) into a transient, apply decorate-fn to each child, inline."
  [state element-nodes decorate-fn]
  (let [s1 (push-container state :DECORATED)
        s2 (reduce walk-element s1 element-nodes)]
    (swap! (:children-atom (peek (:stack s2))) #(mapv decorate-fn %))
    (pop-container s2)))

(defn- walk-grace
  "\\grace, \\acciaccatura, etc. — tag as grace, inline."
  [state children]
  (let [grace-type    (some-> (first (filter string? children))
                              (str/replace #"\\" ""))
        element-nodes (filter (complement string?) children)]
    (walk-decorated state element-nodes
      #(cond-> %
         (:duration %)  (assoc :duration 0)
         (:modifiers %) (update :modifiers conj ["grace" (or grace-type "grace")])))))

(defn- walk-tremolo
  "Note/Chord tremolo → add modifier, inline.
   Measured tremolo (\\repeat tremolo) → Iterator."
  [state children]
  (let [note-node    (find-child children :Note)
        chord-node   (find-child children :Chord)
        divisor-node (find-child children :divisor)
        seq-node     (find-child children :Sequence)]
    (cond
      ;; Note or Chord tremolo: c4:32, <c e>4:32
      (or note-node chord-node)
      (let [int-node (find-child children :Int)
            subdiv   (when int-node (Integer/parseInt (second int-node)))]
        (walk-decorated state [(or note-node chord-node)]
          #(update % :modifiers conj ["tremolo" subdiv])))

      ;; Measured tremolo: \repeat tremolo N {seq} → Iterator
      (and divisor-node seq-node)
      (let [div-int   (find-child (rest divisor-node) :Int)
            count-val (when div-int (Integer/parseInt (second div-int)))
            s1 (push-container state :SEQ)
            s2 (walk-children s1 (rest seq-node))
            seq-composite (peek (:stack s2))
            s3 (update s2 :stack pop)]
        (make-iterator s3 :TREMOLO seq-composite {:count count-val}))

      :else state)))

;; ============================================================
;; Command handlers — Iterator (deferred expansion)
;; ============================================================

(defn- walk-repeat
  "\\repeat volta/unfold N {seq} → Iterator wrapping the walked Sequence."
  [state children]
  (let [repeat-type  (some #{"volta" "unfold"} children)
        repeats-node (find-child children :repeats)
        count-int    (when repeats-node
                       (find-child (rest repeats-node) :Int))
        count-val    (when count-int (Integer/parseInt (second count-int)))
        seq-node     (find-child children :Sequence)
        volta-node   (find-child children :volta)]
    (if (and count-val seq-node)
      (let [;; Walk main sequence into a :SEQ composite
            s1 (push-container state :SEQ)
            s2 (walk-children s1 (rest seq-node))
            seq-composite (peek (:stack s2))
            s3 (update s2 :stack pop)   ;; manual pop, no auto-append

            ;; Walk alternative if present
            [s4 alt-composite]
            (if volta-node
              (let [alt-seq (find-child (rest volta-node) :Sequence)]
                (if alt-seq
                  (let [sa (push-container s3 :SEQ)
                        sb (walk-children sa (rest alt-seq))
                        alt (peek (:stack sb))
                        sc (update sb :stack pop)]
                    [sc alt])
                  [s3 nil]))
              [s3 nil])

            params (cond-> {:count       count-val
                            :repeat-type (keyword (or repeat-type "unfold"))}
                     alt-composite (assoc :alternative alt-composite))]
        (make-iterator s4 :REPEAT seq-composite params))
      state)))

;; ============================================================
;; Public API
;; ============================================================

(defn walk
  "Walk a raw instaparse tree and build domain objects.
   Returns {:score Composite, :tokens [...]}."
  [tree]
  (let [state            (initial-state)
        program-children (rest tree)]
    (loop [st state
           remaining (vec program-children)]
      (if (seq remaining)
        (recur (walk-element st (first remaining)) (rest remaining))
        (let [final-st (reduce (fn [s _] (pop-container s))
                               st (range (dec (count (:stack st)))))]
          {:score  (peek (:stack final-st))
           :tokens (vec (d/composite-children (peek (:stack final-st))))})))))
