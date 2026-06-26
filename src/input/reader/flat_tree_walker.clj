(ns input.reader.flat-tree-walker
  "Post-parse tree walker that builds a flat repository of containers,
   with leaves stored inline in :children vectors.
   Uses input.reader.flat-core for state management."
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
          n (Integer/parseInt (apply str (remove #{\.} s)))]
      (loop [val (/ 1 n)
             i dots]
        (if (zero? i)
          val
          (recur (+ val (/ val 2)) (dec i)))))))

;; ============================================================
;; Initial state (delegates to flat-core)
;; ============================================================

(defn- initial-state
  "Create a fresh walker state using flat-core."
  [input]
  (flat/initial-state input))

;; ============================================================
;; Tag predicates
;; ============================================================

(defn- tag? [node t]
  (and (vector? node) (= (first node) t)))

(defn- node-text
  "Extract the original input text that matched this parse node."
  [state node]
  (when-let [input (:input state)]
    (let [m (meta node)]
      (when-let [start (:instaparse.gll/start-index m)]
        (subs input start (:instaparse.gll/end-index m))))))

;; ============================================================
;; Pitch resolution (using leaf-parser)
;; ============================================================

(defn- resolve-pitch-from-tree
  [pitch-children state]
  (let [name-str (some-> (first (filter #(tag? % :PitchLetter) pitch-children)) second)
        accidental (some-> (first (filter #(tag? % :Accidental) pitch-children)) second)
        octave-abs (some-> (first (filter #(tag? % :OctaveAbs) pitch-children)) second)
        octave-ticks (some-> (first (filter #(tag? % :OctaveTicks) pitch-children)) second)
        octave-spec (or octave-abs octave-ticks "")
        last-midi @(:last-pitch state)]
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
            shorthand (some-> (find-child art-children :ArticulationShorthand) second)
            name-node (find-child art-children :Name)]
        (leaf/resolve-articulation (or shorthand (when name-node (second name-node))))))))

(defn- extract-modifiers
  [children]
  (for [node (concat (find-all-children children :Modifier)
                     (find-all-children children :Ornament))]
    (let [sub-children (rest node)
          name-node (find-child sub-children :Name)
          name (when name-node (second name-node))]
      (if (= (first node) :Modifier)
        (let [val-node (first (filter #(not (tag? % :Name)) sub-children))
              val (when val-node
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
    (let [tag (first node)
          children (rest node)]
      (case tag
        :Sequence (let [s (flat/push-container state :SEQ)]
                    (->> (walk-children s children) (flat/pop-container)))
        :Parallel (let [s (flat/push-container state :PAR)]
                    (->> (walk-children s children) (flat/pop-container)))
        :Data (let [s (flat/push-container state :DATA)]
                (->> (walk-children s children) (flat/pop-container)))
        :AtomicAlgo (let [s (flat/push-container state :ATOMIC_ALGO)]
                      (->> (walk-children s children) (flat/pop-container)))
        :ElementAlgo (let [s (flat/push-container state :ELEMENT_ALGO)]
                       (->> (walk-children s children) (flat/pop-container)))
        :BangConst (walk-bang-const state children)
        :Assignment (walk-assignment state children)
        :KeyAssignment (walk-key-assignment state children)
        :Note (walk-note state children (node-text state node))
        :Chord (walk-chord state children (node-text state node))
        :Rest (walk-rest state children (node-text state node))
        :Drum (walk-drum state children (node-text state node))
        :Id (walk-bareword state children)
        :Int (walk-primitive state :int children)
        :Float (walk-primitive state :float children)
        :Ratio (walk-primitive state :ratio children)
        :StringLit (walk-primitive state :string children)
        :Keyword (walk-primitive state :keyword children)
        :Name (walk-primitive state :name children)
        ;; Commands
        :times (walk-times state children)
        :tuplet (walk-tuplet state children)
        :transpose (walk-transpose state children)
        :repeat (walk-repeat state children)
        :tremolo (walk-tremolo state children)
        :grace (walk-grace state children)
        :FormSign (let [val (node-text state node)]
                    (flat/append-child state {:type :form-sign :val val})
                    state)
        :FormJump (let [val (node-text state node)]
                    (flat/append-child state {:type :form-jump :val val})
                    state)
        (reduce walk-element state children)))))

(defn- walk-children
  "Walk a sequence of child nodes sequentially."
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
  (let [name-val (some (fn [c]
                         (cond
                           (string? c) (keyword c)
                           (and (vector? c) (= :Name (first c))) (keyword (second c))))
                       children)]
    (if name-val
      ;; Update the current container's :id on the stack
      (let [idx (dec (count (:stack state)))]
        (update-in state [:stack idx :id] (constantly name-val)))
      state)))

;; ============================================================
;; Instructions
;; ============================================================

(defn- walk-bang-const
  [state children]
  (let [name-node (find-child children :Name)
        name-val (when name-node (second name-node))
        kw (keyword name-val)]
    (when name-val
      (let [obj {:type :instruction :const kw :raw (str "!" name-val)}
            ctx (flat/current-context state)]
        (flat/append-child state obj)
        (when-let [[ctx-key ctx-val] (data/instruction-context kw)]
          (c/ctx-append ctx ctx-key (flat/accumulated-time state) ctx-val :fixed))))
    state))

(defn- walk-assignment
  [state children]
  (let [name-node (find-child children :Name)
        name-val (when name-node (second name-node))]
    (when name-val
      (let [val-nodes (filter #(not (tag? % :Name)) children)
            val-node (first val-nodes)
            val-tag (when val-node (first val-node))
            val (when val-node (second val-node))
            ctx (flat/current-context state)
            t (flat/accumulated-time state)]
        (case val-tag
          :Ramp
          (let [ramp-children (rest val-node)
                curve-node (find-child ramp-children :CurvePrefix)
                dir-node (find-child ramp-children :Direction)
                curve (when curve-node (second curve-node))
                dir (when dir-node (second dir-node))
                ip (case [curve dir]
                     ([nil "<"] ["l" "<"]) :lin-up
                     ([nil ">"] ["l" ">"]) :lin-down
                     (["s" "<"]) :smooth
                     (["s" ">"]) :smooth
                     (["i" "<"]) :ease-in
                     (["o" "<"]) :ease-out
                     (["i" ">"]) :ease-in
                     (["o" ">"]) :ease-out
                     :else :lin-up)]
            (let [obj {:type :assignment :key (keyword name-val)
                       :val  (str "ramp" dir) :raw (str "!" name-val ":" dir)}]
              (flat/append-child state obj)
              (c/ctx-append ctx (keyword name-val) t :ramp-start ip)))
          :Int
          (let [parsed-val (Integer/parseInt val)
                obj {:type :assignment :key (keyword name-val)
                     :val  parsed-val :raw (str "!" name-val ":" val)}]
            (flat/append-child state obj)
            (when (= name-val "key")
              (let [ks (or (el/parse-key val) (el/parse-key (str val ".major")))]
                (when ks (c/ctx-append ctx :key t ks :fixed))))
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed))
          :Float
          (let [parsed-val (Double/parseDouble val)
                obj {:type :assignment :key (keyword name-val)
                     :val  parsed-val :raw (str "!" name-val ":" val)}]
            (flat/append-child state obj)
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed))
          :QualifiedName
          (let [name-children (rest val-node)
                names (mapv second (filter #(tag? % :Name) name-children))
                key-str (str/join "." names)
                parsed-val (keyword key-str)
                obj {:type :assignment :key (keyword name-val)
                     :val  parsed-val :raw (str "!" name-val ":" key-str)}]
            (flat/append-child state obj)
            (when (= name-val "key")
              (let [ks (or (el/parse-key key-str) (el/parse-key (str key-str ".major")))]
                (when ks (c/ctx-append ctx :key t ks :fixed))))
            (c/ctx-append ctx (keyword name-val) t parsed-val :fixed))
          :StringLit
          (let [obj {:type :assignment :key (keyword name-val)
                     :val  val :raw (str "!" name-val ":\"" val "\"")}]
            (flat/append-child state obj)
            (c/ctx-append ctx (keyword name-val) t val :fixed))
          :StructValue
          (let [obj {:type :struct-assign :key (keyword name-val)
                     :val  val :raw (str "!" name-val ":" val)}]
            (flat/append-child state obj))
          (let [obj {:type :assignment :key (keyword name-val)
                     :val  val :raw (str "!" name-val ":" (pr-str val))}]
            (flat/append-child state obj)))))
    state))

(defn- walk-key-assignment
  [state children]
  (let [key-node (find-child children :KeySpec)
        key-val (when key-node (second key-node))]
    (when key-val
      (let [ctx (flat/current-context state)
            ks (or (el/parse-key key-val) (el/parse-key (str key-val ".major")))
            obj {:type :assignment :key :key :val key-val :raw (str "!key:" key-val)}]
        (flat/append-child state obj)
        (when ks (c/ctx-append ctx :key (flat/accumulated-time state) ks :fixed))))
    state))

;; ============================================================
;; Leaf nodes
;; ============================================================

(defn- walk-note
  [state children token]
  (let [ctx (flat/current-context state)
        pitch-node (find-child children :Pitch)
        dur (or (extract-duration children) @(:last-dur state))
        art (extract-articulation children)
        modifiers (extract-modifiers children)
        tied (has-tie? children)]
    (if pitch-node
      (let [[midi new-last] (resolve-pitch-from-tree (rest pitch-node) state)]
        (reset! (:last-pitch state) new-last)
        (when dur (reset! (:last-dur state) dur))
        (let [leaf (d/leaf (or token (str "note-" midi))
                           (or ctx (c/context)) dur (if midi [midi] [])
                           art (when (map? art) (:dynamic art)) modifiers tied)]
          (flat/append-child state leaf)))
      state)))

(defn- walk-chord
  [state children token]
  (let [ctx (flat/current-context state)
        pitches (filter #(tag? % :Pitch) children)
        dur (or (extract-duration children) @(:last-dur state))
        art (extract-articulation children)
        modifiers (extract-modifiers children)
        tied (has-tie? children)]
    (if (seq pitches)
      (let [midis (atom [])
            last-p (atom @(:last-pitch state))]
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m) (reset! last-p l)))
        (reset! (:last-pitch state) @last-p)
        (when dur (reset! (:last-dur state) dur))
        (let [leaf (d/leaf (or token (str "chord-" (str/join "-" @midis)))
                           (or ctx (c/context)) dur (vec @midis)
                           art (when (map? art) (:dynamic art)) modifiers tied)]
          (flat/append-child state leaf)))
      state)))

(defn- walk-rest
  [state children token]
  (let [ctx (flat/current-context state)
        dur (or (extract-duration children) @(:last-dur state))]
    (when dur (reset! (:last-dur state) dur))
    (let [rest-obj (d/rest* (or token (str "rest-" dur)) (or ctx (c/context)) dur)]
      (flat/append-child state rest-obj))))

(defn- walk-drum
  [state children token]
  (let [ctx (flat/current-context state)
        dur (or (extract-duration children) @(:last-dur state))
        drum-mod (find-child children :DrumMod)
        prog (when drum-mod
               (let [inner (first (rest drum-mod))
                     val (second inner)]
                 (data/resolve-drum val)))]
    (let [drum-obj (d/drum (or token (str "drum-" (or prog "?")))
                           (or ctx (c/context)) (or dur 1/4) prog)]
      (flat/append-child state drum-obj))))

;; ============================================================
;; Primitives
;; ============================================================

(defn- walk-primitive
  [state type children]
  (let [val (first children)]
    (case type
      :int (flat/append-child state {:type :int :val (Integer/parseInt val)})
      :float (flat/append-child state {:type :float :val (Double/parseDouble val)})
      :ratio (let [parts (str/split val #"/")]
               (flat/append-child state
                                  {:type :ratio :val (/ (Integer/parseInt (first parts))
                                                        (Integer/parseInt (second parts)))}))
      :string (flat/append-child state {:type :string :val val})
      :keyword (flat/append-child state {:type :keyword :val (keyword val)})
      :name (flat/append-child state {:type :name :val val})
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

(defn- make-iterator
  "Create an Iterator and append it to the current parent."
  [state iter-type source params]
  (let [parent-ctx (flat/current-context state)
        ctx (c/context parent-ctx)
        auto-ids @(:auto-ids state)
        n (get auto-ids iter-type 0)
        iter-id (keyword (str (name iter-type) "." (inc n)))]
    (swap! (:auto-ids state) assoc iter-type (inc n))
    (let [iterator (d/iterator iter-type iter-id ctx source params)]
      (flat/append-child state iterator)
      state)))

;; ============================================================
;; Command handlers — Transient (inline immediately)
;; ============================================================

(defn- walk-times
  "\\times ratio {seq} — scale durations by multiply-factor, inline."
  [state children]
  (let [factor-node (find-child children :multiply-factor)
        ratio-node (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str (when ratio-node (second ratio-node))
        seq-node (find-child children :Sequence)
        factor (parse-ratio-str ratio-str)]
    (if (and factor seq-node)
      (let [s1 (flat/push-container state :TIMES)
            s2 (walk-children s1 (rest seq-node))
            s3 (flat/scale-durations! s2 factor)]
        (flat/pop-container s3))
      state)))

(defn- walk-tuplet
  "\\tuplet 3/2 {seq} — 3 in the time of 2 → factor 2/3, inline."
  [state children]
  (let [factor-node (find-child children :divide-factor)
        ratio-node (when factor-node (find-child (rest factor-node) :Ratio))
        ratio-str (when ratio-node (second ratio-node))
        seq-node (find-child children :Sequence)
        factor (when ratio-str
                 (let [parts (str/split ratio-str #"/")]
                   (/ (Integer/parseInt (second parts))
                      (Integer/parseInt (first parts)))))]
    (if (and factor seq-node)
      (let [s1 (flat/push-container state :TUPLET)
            s2 (walk-children s1 (rest seq-node))
            s3 (flat/scale-durations! s2 factor)]
        (flat/pop-container s3))
      state)))

(defn- walk-transpose
  "\\transpose from to {seq} — shift all pitches by interval, inline."
  [state children]
  (let [from-node (find-child children :from-pitch)
        to-node (find-child children :to-pitch)
        seq-node (find-child children :Sequence)]
    (if (and from-node to-node seq-node)
      (let [from-pitch (find-child (rest from-node) :Pitch)
            to-pitch (find-child (rest to-node) :Pitch)
            [from-midi _] (resolve-pitch-from-tree (rest from-pitch) state)
            [to-midi _] (resolve-pitch-from-tree (rest to-pitch) state)
            interval (- to-midi from-midi)
            s1 (flat/push-container state :TRANSPOSE)
            s2 (walk-children s1 (rest seq-node))
            s3 (flat/transpose-pitches! s2 interval)]
        (flat/pop-container s3))
      state)))

(defn- walk-decorated
  "Walk element(s) into a transient, apply decorate-fn to each child, inline."
  [state element-nodes decorate-fn]
  (let [s1 (flat/push-container state :DECORATED)
        s2 (reduce walk-element s1 element-nodes)
        s3 (flat/decorate-children! s2 decorate-fn)]
    (flat/pop-container s3)))

(defn- walk-grace
  "\\grace, \\acciaccatura, etc. — tag as grace, inline."
  [state children]
  (let [grace-type (some-> (first (filter string? children))
                           (str/replace #"\\" ""))
        element-nodes (filter (complement string?) children)]
    (walk-decorated state element-nodes
                    #(cond-> %
                             (:duration %) (assoc :duration 0)
                             (:modifiers %) (update :modifiers conj ["grace" (or grace-type "grace")])))))

(defn- walk-tremolo
  "Note/Chord tremolo → add modifier, inline.
   Measured tremolo (\\repeat tremolo) → Iterator."
  [state children]
  (let [note-node (find-child children :Note)
        chord-node (find-child children :Chord)
        divisor-node (find-child children :divisor)
        seq-node (find-child children :Sequence)]
    (cond
      ;; Note or Chord tremolo: c4:32, <c e>4:32
      (or note-node chord-node)
      (let [int-node (find-child children :Int)
            subdiv (when int-node (Integer/parseInt (second int-node)))]
        (walk-decorated state [(or note-node chord-node)]
                        #(update % :modifiers conj ["tremolo" subdiv])))

      ;; Measured tremolo: \repeat tremolo N {seq} → Iterator
      (and divisor-node seq-node)
      (let [div-int (find-child (rest divisor-node) :Int)
            count-val (when div-int (Integer/parseInt (second div-int)))
            s1 (flat/push-container state :SEQ)
            s2 (walk-children s1 (rest seq-node))
            s3 (update s2 :stack pop)                       ;; manually pop the transient SEQ without registering
            seq-composite (peek (:stack s2))]
        (make-iterator s3 :TREMOLO seq-composite {:count count-val}))

      :else state)))

;; ============================================================
;; Command handlers — Iterator (deferred expansion)
;; ============================================================

(defn- walk-repeat
  "\\repeat volta/unfold N {seq} → Iterator wrapping the walked Sequence."
  [state children]
  (let [repeat-type (some #{"volta" "unfold"} children)
        repeats-node (find-child children :repeats)
        count-int (when repeats-node
                    (find-child (rest repeats-node) :Int))
        count-val (when count-int (Integer/parseInt (second count-int)))
        seq-node (find-child children :Sequence)
        volta-node (find-child children :volta)]
    (if (and count-val seq-node)
      (let [;; Walk main sequence into a :SEQ composite
            s1 (flat/push-container state :SEQ)
            s2 (walk-children s1 (rest seq-node))
            seq-composite (peek (:stack s2))
            s3 (update s2 :stack pop)                       ;; manual pop, no auto-append

            ;; Walk alternative if present
            [s4 alt-composite]
            (if volta-node
              (let [alt-seq (find-child (rest volta-node) :Sequence)]
                (if alt-seq
                  (let [sa (flat/push-container s3 :SEQ)
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
   Returns {:tree map, :root-id keyword} where :tree is the id->container map.
   input is the original parsed text (for token ID extraction via insta/span)."
  [tree & [input]]
  (let [state (initial-state input)
        program-children (rest tree)]
    (loop [st state
           remaining (vec program-children)]
      (if (seq remaining)
        (recur (walk-element st (first remaining)) (rest remaining))
        (flat/finish st)))))