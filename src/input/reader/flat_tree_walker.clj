(ns input.reader.flat-tree-walker
  "Post-parse tree walker that builds a flat repository of containers,
   with leaves stored inline in :children vectors.
   Uses input.reader.flat-core-builder for state management.

   Changes from previous version:
   - Added walk-context for ^{ } Context definition form
   - Added walk-reference distinguishing :CONTEXT vs container refs
   - Updated extract-modifiers to include :Tremolo as NoteSuffix
   - Updated walk-assignment :Ramp case for timed ramps (DurationExpr + Target)
   - Removed :FormSign and :FormJump (form navigation removed from grammar)
   - Fixed make-iterator (removed unused parent-ctx binding)
   - Added resolve-ip, parse-duration-expr-node, parse-target-node helpers"
  (:require [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.music-data :as data]
            [common.defaults :as defaults]
            [common.music-elements :as el]
            [input.reader.leaf-parser :as leaf]
            [input.reader.flat-core-builder :as flat]
            [input.algo-registry :as algo-registry]
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

(defn- node-position
  "1-based [line column] for node's start position in state's original
   input, or nil if unavailable. Used for walk-time errors (unlike a
   grammar-level parse failure, which already carries :line/:column from
   instaparse itself) -- a VarRef the walker rejects needs to point at
   the same kind of position, not just a bare message with no location."
  [state node]
  (when-let [input (:input state)]
    (when-let [start (:instaparse.gll/start-index (meta node))]
      (let [before     (subs input 0 start)
            last-nl    (str/last-index-of before "\n")
            line       (inc (count (re-seq #"\n" before)))
            column     (- start (or last-nl -1))]
        [line column]))))

;; ============================================================
;; Pitch resolution
;; ============================================================

(declare duration)

(defn- pitch-tuple
  "Raw [name accidental octave-spec] tuple straight off a Pitch node's
   children, with no state/reference dependency of its own.
   accidental is nil when no Accidental child exists at all -- kept as
   nil rather than collapsed to \"\", so leaf-parser/resolve-pitch can
   tell 'nothing written, look up the key' apart from an explicit
   accidental (which always wins outright, same as real notation)."
  [pitch-children]
  (let [name-str     (some-> (first (filter #(or (tag? % :PitchLetterAbs) (tag? % :PitchLetterRel)) pitch-children)) second)
        accidental   (some-> (first (filter #(tag? % :Accidental)  pitch-children)) second)
        octave-abs   (some-> (first (filter #(tag? % :OctaveAbs)   pitch-children)) second)
        octave-ticks (some-> (first (filter #(tag? % :OctaveTicks) pitch-children)) second)]
    [name-str accidental (or octave-abs octave-ticks "")]))

(defn- walk-key-chain
  "Nearest-first vector of every Context still open on the walker's own
   stack right now -- same idea as respell-fn's chain (below) and
   musics.clj's full-ctx-chain, but built from the in-progress walk
   stack rather than a finished tree: pitch is resolved eagerly, note
   by note, as the walk descends, so there's no tree yet to search."
  [state]
  (keep :context (rseq (:stack state))))

(defn- key-for-mode
  "The Key to resolve/spell a bare (unmarked) pitch letter against,
   given a nearest-first ctx-chain and beat t. :accidentals (sampled
   from that chain) decides whether the actually-active :key even
   applies: :explicit (LilyPond-style -- a bare letter is always
   natural, regardless of key, exactly like real LilyPond input)
   always resolves against C major, no matter what key is set;
   :implied (the default) uses whatever :key is actually active,
   falling back to C major itself when none ever was -- either way
   there's one lookup, never a separate no-key code path. Shared by
   resolve-pitch-from-tree (forward: resolving a written note) and
   respell-fn (reverse: spelling a transposed one) -- same lookup
   either direction."
  [chain t]
  (let [mode (or (c/ctx-value-chain chain :accidentals t) :implied)]
    (if (= mode :explicit)
      (el/key :C :major)
      (or (c/ctx-value-chain chain :key t) (el/key :C :major)))))

(defn- resolve-pitch-from-tree [pitch-children state]
  (let [chain (walk-key-chain state)
        t     (duration state)]
    (leaf/resolve-pitch (pitch-tuple pitch-children) @(:last-pitch state)
                         (key-for-mode chain t))))

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

(defn- articulation-ratio
  "resolve-articulation returns a {:duration :dynamic} map (or a raw
   unresolved string) -- a Leaf's :articulation field wants just the
   numeric duration multiplier resolve/resolve-common multiplies the
   note's seconds by."
  [art]
  (when (map? art) (:duration art)))

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

(defn- extract-modifiers
  "Extract modifiers, ornaments, dynamics, hairpins and tremolo from
   note/chord children. Tremolo is now a NoteSuffix: c4:32 produces
   [:Tremolo [:Int '32']].
   :Dynamic can contribute up to two entries (mark, then hairpin) -- the
   grammar now lets a direction glue straight onto a DynamicMark with no
   second '\\' (c4\\mf<, same idea as c4\\mf\\<'s older two-suffix
   spelling, still handled by the separate :Hairpin case below for that
   spelling) -- so this is mapcat, not a straight for, everywhere else
   still contributing exactly one entry per node."
  [children]
  (mapcat
    (fn [node]
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
            [[(str "mod_" name) val]])
          :Ornament
          (let [name-node (find-child sub-children :OrnamentName)
                name      (when name-node (second name-node))]
            [["ornament" name]])
          :Dynamic
          (let [mark-node (find-child sub-children :DynamicMark)
                mark      (when mark-node (second mark-node))
                dir       (first (filter #{"<" ">"} sub-children))]
            (cond-> [["dynamic" mark]]
              dir (conj ["hairpin" dir])))
          :Hairpin
          (let [dir (first (filter #{"<" ">"} sub-children))]
            [["hairpin" dir]])
          :Tremolo
          (let [int-node (find-child sub-children :Int)
                subdiv   (when int-node (Integer/parseInt (second int-node)))]
            [["tremolo" subdiv]]))))
    (concat (find-all-children children :Modifier)
            (find-all-children children :Ornament)
            (find-all-children children :Dynamic)
            (find-all-children children :Hairpin)
            (find-all-children children :Tremolo))))

(defn- apply-note-dynamics!
  "Dynamic marks and hairpins glued directly onto a note/chord (c4\\f,
   c4\\<, chainable as c4\\mf\\<) mean the same thing as a bare !f/!vol<
   BangConst/Assignment written just before it -- LilyPond dynamics set
   the going-forward volume level (or the start of a crescendo/
   decrescendo), they don't just decorate the one note they're written
   on. modifiers already carries [\"dynamic\" mark]/[\"hairpin\" dir] for
   inspectability (same as tremolo/ornament); this is what actually makes
   it audible, via the same ctx-append path BangConst/Assignment use.

   A bare hairpin with no preceding dynamic on the same note falls back to
   the same open-ended-ramp sentinel !vol</!vol> already writes
   (:ramp-start, with no numeric value yet -- appended under the
   hairpin's own direction/curve ip, not :fixed/:invalid, so
   ctx-value-chain can later interpolate from whatever value turns out
   to be ambient at this point in time, once a real target value
   eventually arrives -- see context.clj's own ctx-value-chain
   docstring; with no target ever arriving, that same function treats
   an unresolved :ramp-start exactly like :invalid, so a numeric
   consumer sampling it too early still never sees the non-numeric
   sentinel itself). Chained after a dynamic (c4\\mf\\<), there IS a
   known numeric value right here, so the hairpin instead re-stamps
   that same point with the ramp's IP -- one real point that both sets
   the volume and starts the curve, the same trick a timed Ramp uses
   when a local start value is already active (see walk-assignment)."
  [ctx t modifiers]
  (let [mark    (some (fn [[k v]] (when (= k "dynamic") v)) modifiers)
        dir     (some (fn [[k v]] (when (= k "hairpin") v)) modifiers)
        vol     (when mark (leaf/resolve-dynamic mark))
        ip      (when dir (resolve-ip nil dir))]
    (cond
      (and vol ip) (c/ctx-append ctx :volume t vol ip)
      vol          (c/ctx-append ctx :volume t vol :fixed)
      ip           (c/ctx-append ctx :volume t :ramp-start ip))))

(defn- has-tie? [children] (boolean (find-child children :Tie)))

(def ^:private legato-duration
  "Duration multiplier a slur forces on the notes it spans -- same value
   as the \\legato articulation shorthand (data/articulations). Baked
   directly onto each spanned Leaf (see slur-articulation! below), not
   sampled from context at resolve time -- a slur marks specific notes,
   LilyPond-style, so it has to travel with those notes rather than with
   a time window, or a shuffle-safe reordering (Unit/ElementAlgo) could
   silently detach it from the notes it was meant for."
  (:duration (data/articulations :legato)))

(defn- extract-slur-marks
  "{:open? :close?} for this note's own SlurMark suffixes (LilyPond-style
   '(' / ')' glued directly onto a Note/Chord -- see musics.ebnf).
   A note can carry either, both (rare, but harmless) or neither."
  [children]
  (let [marks (map second (find-all-children children :SlurMark))]
    {:open?  (boolean (some #{"("} marks))
     :close? (boolean (some #{")"} marks))}))

(defn- slur-articulation!
  "Decide this note's articulation given any explicit shorthand it
   already carries (explicit-art, from extract-articulation) plus the
   walker's ongoing slur state, and update that state for the notes that
   follow. A note that opens a slur (its own '(' or an outer !( already
   in effect) is itself part of the slur; a note that closes one (')' or
   !)) is the last note still inside it -- the state only turns off for
   notes *after* this one. An explicit shorthand on the note always wins,
   same as it would outside any slur.
   Returns the articulation-ratio to bake onto this Leaf (nil if neither
   an explicit shorthand nor an active slur apply -- resolve-common then
   falls back to sampling :articulation from context, per the general
   \"no articulation info on the leaf\" rule)."
  [state explicit-art {:keys [open? close?]}]
  (when open? (reset! (:in-slur? state) true))
  (let [art (or explicit-art (when @(:in-slur? state) legato-duration))]
    (when close? (reset! (:in-slur? state) false))
    art))

;; ============================================================
;; Ramp helpers
;; ============================================================

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
   Target = DynamicMark | SignedFloat | SignedInt
   DynamicMark               -> velocity via leaf/resolve-dynamic
   SignedFloat/SignedInt     -> numeric value directly (Double/Integer's
                                 own parseDouble/parseInt already handle
                                 a leading '-' or '+' natively)."
  [target-node]
  (when target-node
    (let [inner (first (rest target-node))]
      (cond
        (tag? inner :DynamicMark)  (leaf/resolve-dynamic (second inner))
        (tag? inner :SignedFloat)  (Double/parseDouble (second inner))
        (tag? inner :SignedInt)    (Integer/parseInt (second inner))
        :else                      nil))))

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
                                [(some-> (or (find-child (rest node) :PitchLetterAbs)
                                             (find-child (rest node) :PitchLetterRel)) second)
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
   take effect at the right moment in the enclosing sequence (see
   flat-core-builder/replay-context! for the shared mechanism -- also
   used to replay a transient command's own context onto its parent).

   Example: ^{ my-ctx: !tempo:120 } registered at t=0.
   Referenced at beat 4: tempo point added at t=4 in current context."
  [state ref-ctx]
  (let [current-ctx (flat/current-context state)
        t           (d/duration (:repo state) (peek (:stack state)))]
    (flat/replay-context! current-ctx ref-ctx t)
    state))

;; ============================================================
;; Variables (name = { ... } / \name)
;; ============================================================
;; Real grammar constructs (musics.ebnf's VarDef/VarRef), resolved in the
;; same single top-to-bottom walk everything else uses -- not a separate
;; text-level pre-processing pass, so nothing about a variable's
;; definition or expansion can ever shift a later parse error's position
;; relative to what was actually written.
;;
;; walk-var-def builds the value the same way a real Sequence would (its
;; own :context, so an instruction inside it -- !f or a note-glued \f --
;; has somewhere real to write to), then, instead of registering it,
;; stashes {:children :context} in state's :var-map under its name and
;; discards the scratch container. walk-var-ref looks the name up,
;; splices the stored children in flat (same shape a \times/\tuplet body
;; already gets spliced in), and replays the stored context onto the
;; current container via flat/replay-context! -- the exact mechanism
;; already used for :CONTEXT references and for a transient command's own
;; context, reused a third time here for the same reason: an instruction
;; written inside src-ctx must still take effect once src-ctx itself is
;; discarded.
;;
;; A variable must be defined before it's referenced (same rule LilyPond
;; itself uses) -- not just conventionally, but structurally: this is a
;; single walk, so nothing is in :var-map yet for anything that hasn't
;; been walked yet. A VarRef whose name isn't there yet is a walk-time
;; error, not a silent no-op.

(declare walk-children)

(defn- walk-var-def [state children]
  (let [name-node (find-child children :VarName)
        name      (when name-node (second name-node))
        seq-node  (find-child children :Scope)]
    (if (and name seq-node)
      (let [s1     (flat/push-container state :VARDEF)
            s2     (walk-children s1 (rest seq-node))
            built  (peek (:stack s2))
            s3     (update s2 :stack pop)]
        (swap! (:var-map s3) assoc name
               {:children (:children built) :context (:context built)})
        s3)
      state)))

(defn- walk-var-ref [state children]
  (let [name-node (find-child children :VarName)
        name      (when name-node (second name-node))
        entry     (get @(:var-map state) name)]
    (when-not entry
      (let [[line column] (or (node-position state name-node) [nil nil])]
        (throw (ex-info (str "Variable \"" name "\" referenced before its "
                             "definition, or never defined"
                             (when line (str " (line " line ", column " column ")")))
                        {:var name :line line :column column}))))
    (let [target-ctx (flat/current-context state)
          t          (d/duration (:repo state) (peek (:stack state)))
          state'     (reduce flat/append-child state (:children entry))]
      (flat/replay-context! target-ctx (:context entry) t)
      state')))

;; ============================================================
;; Main walker dispatch
;; ============================================================

(declare walk-context walk-reference
         walk-bang-const walk-assignment walk-key-assignment walk-invalidate
         walk-slur-start walk-slur-end
         walk-note walk-chord walk-rest walk-drum
         walk-bareword walk-primitive walk-container-field
         walk-atomic-algo run-algo
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
        :Unit        (let [s (flat/push-container state :UNIT)]
                       (->> (walk-children s children) flat/pop-container))
        :Data        (let [s (flat/push-container state :DATA)]
                       (->> (walk-children s children) flat/pop-container))
        ;; :AtomicAlgo is never pushed/popped as its own container -- see
        ;; walk-atomic-algo's own docstring. Unlike ElementAlgo below,
        ;; it's wired to real execution now: it looks its `algo` name up
        ;; in input.algo-registry and splices the result straight into
        ;; whatever container is already current.
        :AtomicAlgo  (walk-atomic-algo state children)
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
        ;; ---- Variables ----
        :VarDef      (walk-var-def state children)
        :VarRef      (walk-var-ref state children)
        ;; ---- Comments: real, tagged nodes (see musics.ebnf's ws/Comment)
        ;; so a later parse error's position is always relative to the
        ;; original text -- nothing is stripped before instaparse runs.
        ;; Purely discarded here, same as a bare ws-artifact string.
        :Comment     state
        ;; ---- Instructions ----
        :BangConst    (walk-bang-const    state children)
        :Assignment   (walk-assignment    state children)
        :KeyAssignment (walk-key-assignment state children)
        :Invalidate   (walk-invalidate    state children)
        :SlurStart    (walk-slur-start state)
        :SlurEnd      (walk-slur-end   state)
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
        ;; bare DataElement ([ ]) -- Note/Chord/Rest/Drum extract their own
        ;; via find-child directly and never recurse into these via
        ;; walk-element, so there's no risk of double-handling here.
        :Pitch     (let [[midi new-last] (resolve-pitch-from-tree children state)]
                     (reset! (:last-pitch state) new-last)
                     (flat/append-child state {:type :pitch :val midi}))
        :DurationNum     (flat/append-child state {:type :duration :val (parse-duration (first children))})
        :DurationSpecial (flat/append-child state {:type :duration :val (parse-duration (first children))})
        ;; :BareDuration ('/4, '/8., authoring a talea -- a duration-only
        ;; isorhythmic cycle -- as pure data, e.g. [/4 /8 /8 /4]) has no
        ;; case of its own: it wraps a plain DurationNum/DurationSpecial
        ;; (musics.ebnf's `BareDuration = <'/'> Duration`, the '/' itself
        ;; discarded by the grammar), so the default fallback below just
        ;; recurses into it and the DurationNum/DurationSpecial case right
        ;; above handles the actual append -- same {:type :duration :val
        ;; ...} shape a bare Pitch atom already produces above.
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
;; Context definition  ^{ id: instructions }
;; ============================================================

(defn- walk-context
  "Walk a ^{ } Context definition block.
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
;; Container identifying fields  ([ type ... ]  @[ algo ... ]  @{ algo ... })
;; ============================================================

(defn- walk-container-field
  "Stamp a bare Name/AlgoName value (Data's `type` -- Name, hyphen-free;
   ElementAlgo's `algo` -- AlgoName, hyphens allowed, see musics.ebnf's
   own comment on `algo`) onto the container currently on top of the
   stack, under field. These identify the container itself -- they are
   not musical/data content, so they must not be appended as a child."
  [state children field]
  (let [name-node (or (find-child children :Name) (find-child children :AlgoName))
        name-val  (when name-node (second name-node))]
    (if name-val
      (let [idx (dec (count (:stack state)))]
        (update-in state [:stack idx field] (constantly (keyword name-val))))
      state)))

(defn- algo-name
  "AtomicAlgo/ElementAlgo's own `algo` field, read directly off the raw
   node -- unlike walk-container-field above, this doesn't stamp onto any
   pushed container (walk-atomic-algo never pushes one at all, see
   below), so it just extracts the bare AlgoName string straight from
   the raw tree (musics.ebnf's `algo = AlgoName`, hyphens allowed --
   distinct from Data's `type`, which stays the shared, hyphen-free
   Name)."
  [children]
  (when-let [algo-node (find-child children :algo)]
    (when-let [name-node (find-child (rest algo-node) :AlgoName)]
      (second name-node))))

(defn- walk-data-values
  "Walk one raw :Data node into a plain seq of its atoms' bare values
   (:val stripped of the {:type :pitch/:duration ...} wrapper -- see the
   :DurationNum/:BareDuration/bare-Pitch-atom cases in walk-element).
   Pushes a scratch :DATA container and walks straight into it, same as
   the ordinary :Data case does, but only ever peeks the result rather
   than popping -- pop-container is what registers a container in :repo
   and links it onto its parent's :children, neither of which is wanted
   here: this Data is feeding an AtomicAlgo's pre-existing algorithm
   function, not authoring a real, addressable container of its own."
  [state data-node]
  (let [scratch (flat/push-container state :DATA)
        walked  (walk-children scratch (rest data-node))
        built   (peek (:stack walked))]
    (map :val (:children built))))

(defn- walk-single-value
  "Walk one raw bare-Primitive node (:Int/:Float/:Ratio -- an AtomicAlgo
   scalar arg, e.g. a rhythm generator's pulse/step count) into just its
   own bare value. Same scratch-container-and-peek trick as
   walk-data-values, one node instead of a whole :Data node's children
   -- no :repo/parent side effects, this is feeding a function call, not
   authoring content."
  [state node]
  (let [scratch (flat/push-container state :DATA)
        walked  (walk-element scratch node)
        built   (peek (:stack walked))]
    (:val (first (:children built)))))

(defn- algo-arg-node? [node]
  (or (tag? node :Data) (tag? node :Int) (tag? node :Float) (tag? node :Ratio)
      (tag? node :AtomicAlgo)))

(defn- walk-algo-arg
  "One AtomicAlgo argument -- Data, bare Primitive, or a nested AtomicAlgo
   call -- walked to whatever shape it should arrive at the registered
   fn as: a seq for Data, a single scalar for a bare Primitive, or
   (recursively, via run-algo) whatever the nested call's own fn
   actually returns, passed through exactly as-is. No flattening and no
   reinterpretation at this boundary -- a nested call's raw result
   becomes this argument's value, full stop, so a fn expecting a plain
   pitch list gets exactly that from a nested pitch-generating call, a
   fn expecting [pitch duration] pairs gets exactly that from a nested
   color-talea-shaped call, and a combinator (a \"zip\" algo, say) can be
   fed several nested calls at once -- one for pitches, one for
   durations, whatever its own params expect -- each contributing
   whatever shape it naturally produces."
  [state node]
  (cond
    (tag? node :Data)        (walk-data-values state node)
    (tag? node :AtomicAlgo)  (run-algo state (rest node))
    :else                    (walk-single-value state node)))

(defn- run-algo
  "@[ name Arg... ] -- look `name` up in input.algo-registry, walk each
   Arg (Data/Primitive/nested AtomicAlgo, via walk-algo-arg -- genuinely
   recursive, an Arg can itself be another @[ ... ] call whose own Args
   are walked the same way), and apply the registered :fn positionally.
   Returns whatever the fn returns, completely as-is -- this function
   itself has no opinion on shape. walk-atomic-algo (below) is the one
   caller that requires the top-level result to be a seq of [pitch
   duration] pairs, because it's the one that splices Leaves into real
   musical content; a nested call reached via walk-algo-arg has no such
   requirement; its result only has to match whatever its own caller
   (another algo fn) expects -- see input.algo-registry's own namespace
   docstring for the full contract."
  [state children]
  (let [name  (algo-name children)
        entry (get @algo-registry/atomic-algo-registry name)
        args  (map #(walk-algo-arg state %) (filter algo-arg-node? children))]
    (if entry
      (apply (:fn entry) args)
      (throw (ex-info (str "Unknown algo: " name)
                       {:algo name :known (keys @algo-registry/atomic-algo-registry)})))))

(defn- walk-atomic-algo
  "@[ name Arg... ] as it appears directly in musical content (a
   Sequence's own body, say) -- run-algo computes name's result, which
   at THIS, top-level position must be a seq of [pitch duration] pairs
   (event order), each appended as a real Leaf onto whatever container
   is already current -- the same splice-into-the-enclosing-container
   shape a transient command (\\times/\\tuplet/...) already has, not a
   new container of its own. AtomicAlgo is deliberately never pushed/
   popped/registered at all (see walk-element's :AtomicAlgo case) -- it's
   purely a compute-then-splice step, so it can never be independently
   addressed or referenced the way a real Sequence/Data container can.
   \\repeat unfold N { ... } around the call is how the *text* asks for
   more than one period -- this always computes exactly what the
   registered fn returns for the args given, nothing repeated on its
   own."
  [state children]
  (let [pairs (run-algo state children)
        ctx   (or (flat/current-context state) (c/context))]
    (reduce (fn [st [pitch dur]]
              (flat/append-child st (d/leaf (str "algo-" pitch) ctx dur [pitch])))
            state pairs)))

;; ============================================================
;; Instructions
;; ============================================================

(defn duration
  "Accumulated duration of the current container on the stack.
   Used as the time coordinate for ctx-append calls."
  [state]
  (d/duration (:repo state) (peek (:stack state))))

(defn- walk-slur-start [state]
  (reset! (:in-slur? state) true)
  (flat/append-child state {:type :slur-start}))

(defn- walk-slur-end [state]
  (reset! (:in-slur? state) false)
  (flat/append-child state {:type :slur-end}))

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

(defn- walk-invalidate [state children]
  (let [name-node (find-child children :Name)
        name-val  (when name-node (second name-node))
        kw        (keyword name-val)]
    (if name-val
      (let [obj     {:type :instruction :invalidate kw :raw (str "!/" name-val)}
            ctx     (flat/current-context state)
            t       (duration state)
            state'  (flat/append-child state obj)
            ;; !mf-style names resolve through instruction-context to their
            ;; real ctx-key (:mf -> :volume); anything else is assumed to
            ;; be an Assignment-style name, which walk-assignment writes
            ;; under its canonical alias (!vol:80/!v:80 both -> :volume),
            ;; so invalidation must canonicalize the same way.
            ctx-key (if-let [[k _] (data/instruction-context kw)]
                      k
                      (defaults/canonical-key kw))]
        (c/ctx-invalidate ctx ctx-key t)
        state')
      state)))

(defn- walk-assignment [state children]
  (let [name-node (find-child children :AssignName)
        name-val  (when name-node (second name-node))]
    (if name-val
      (let [val-nodes (filter #(not (tag? % :AssignName)) children)
            val-node  (first val-nodes)
            val-tag   (when val-node (first val-node))
            val       (when val-node (second val-node))
            ctx       (flat/current-context state)
            t         (duration state)
            ;; Aliases (!timbre/!program/!prog/!i, !vol/!v, ...) all collapse
            ;; to one canonical context key, so they read back as the same
            ;; envelope regardless of which alias was used to write them.
            ctx-key   (defaults/canonical-key (keyword name-val))
            ;; !key:value RampMark? (!vol:mf<) -- an optional trailing
            ;; direction (+ curve) glued straight onto a plain Value,
            ;; distinct from the :Ramp case below (which has no Value at
            ;; all, just a bare direction). Reuses ramp-direction/
            ;; ramp-curve directly against Assignment's own children --
            ;; safe because a RampMark's Direction/CurvePrefix are bare
            ;; strings there (both hidden grammar rules), never
            ;; confusable with val-node's own tagged content (a StringLit
            ;; containing a literal '<', say) since that's a nested
            ;; vector, not a bare string sibling. Every plain-Value branch
            ;; below shares this one ip instead of hardcoding :fixed, so
            ;; !vol:mf< means the same "value here, ramp starts here" as
            ;; c4\\mf</c4\\mf\\< already mean glued onto a note -- just
            ;; usable for any key, not just volume, and not tied to a note.
            dir       (ramp-direction children)
            curve     (ramp-curve     children)
            ip        (if dir (resolve-ip curve dir) :fixed)
            raw-mark  (when dir (str dir curve))]
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
                    start-val (ctx-local-value ctx ctx-key t)
                    obj       {:type :assignment
                               :key  (keyword name-val)
                               :val  {:dir dir :curve curve :dur dur :target target}
                               :raw  (str "!" name-val dir (when curve (str curve ":")) dur ":" target)}
                    state'    (flat/append-child state obj)]
                (when target
                  (when start-val
                    (c/ctx-append ctx ctx-key t start-val ip))
                  (c/ctx-append ctx ctx-key (+ t dur) target :fixed))
                state')
              ;; ---- Open-ended ramp: !vol< ----
              ;; ip (the direction/curve computed above) is kept on this
              ;; point, not discarded -- ctx-value-chain uses it to
              ;; interpolate from whatever value turns out to be ambient
              ;; here once a later real target value arrives (see its
              ;; own docstring); with no target ever arriving it treats
              ;; this exactly like "nothing said here at all", same as
              ;; apply-note-dynamics!'s own bare-hairpin branch.
              (let [obj    {:type :assignment
                            :key  (keyword name-val)
                            :val  (str "ramp" dir)
                            :raw  (str "!" name-val dir curve)}
                    state' (flat/append-child state obj)]
                (c/ctx-append ctx ctx-key t :ramp-start ip)
                state')))

          ;; LilyPond-style tempo marking, note-value=BPM (!tempo:4=120,
          ;; !tempo:3/8=120) -- the note-value side is a bare Int (N means
          ;; 1/N) or an explicit Ratio (taken as-is), same convention as
          ;; el/tempo. Converted to the quarter-note-equivalent BPM the
          ;; engine's tempo sampling expects, so `4=120`/`8=60` etc. all
          ;; land on the same context value regardless of which note value
          ;; the author marked it against.
          :TempoMark
          (let [note-node   (second val-node)
                bpm-node    (nth val-node 2)
                note-dur    (if (tag? note-node :Ratio)
                              (let [[n d] (str/split (second note-node) #"/")]
                                (/ (Integer/parseInt n) (Integer/parseInt d)))
                              (Integer/parseInt (second note-node)))
                bpm         (Integer/parseInt (second bpm-node))
                parsed-val  (el/tempo->quarter-bpm (el/tempo note-dur bpm))
                raw-str     (str (second note-node) "=" bpm)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" raw-str raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
            state')

          ;; SignedInt/SignedFloat, not the plain Int/Float used
          ;; elsewhere -- see musics.ebnf's Value rule for why a context
          ;; value's own literal is the one place a leading '-'/'+' is
          ;; accepted at all. Integer/parseInt and Double/parseDouble
          ;; already handle either sign natively, same code as before.
          :SignedInt
          (let [parsed-val (Integer/parseInt val)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" val raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
            state')

          :SignedFloat
          (let [parsed-val (Double/parseDouble val)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" val raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
            state')

          ;; Divisible meter (!Meter:7/8) or any other bare ratio value --
          ;; Meter parses to a proper Meter (see common.music-elements);
          ;; anything else is just a plain Clojure ratio, same
          ;; as walk-tuplet's own divide-factor parsing.
          :Ratio
          (let [parsed-val (if (= ctx-key :Meter)
                             (el/parse-meter-str val)
                             (let [[n d] (str/split val #"/")]
                               (/ (Integer/parseInt n) (Integer/parseInt d))))
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" val raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
            state')

          :QualifiedName
          ;; A single bare name (no dots) that happens to be a dynamic mark
          ;; -- e.g. `!vol:pp` -- resolves to its numeric velocity, same as
          ;; a Ramp's Target does. One usually writes `!pp` (BangConst)
          ;; instead, but `!<key>:pp` must still work and produce a usable
          ;; number, not the bare keyword, since a later timed ramp may
          ;; read this value back as its start point (see ctx-local-value).
          ;; Genuinely dotted/symbolic names (scale names, etc.) fall
          ;; through to the keyword form as before -- `!key:C.major` no
          ;; longer reaches this case at all (AssignName excludes "key",
          ;; see musics.ebnf's Assignment), it's always KeyAssignment now.
          (let [name-children (rest val-node)
                names         (mapv second (filter #(tag? % :Name) name-children))
                key-str       (str/join "." names)
                dyn-val       (when (= 1 (count names)) (leaf/resolve-dynamic key-str))
                parsed-val    (or dyn-val (keyword key-str))
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" key-str raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
            state')

          ;; Additive meter (!Meter:"7/8(2+2+3)") or any other quoted
          ;; string value -- Meter parses via the same reader as the bare-
          ;; ratio :Ratio case above (see parse-meter-str), so both forms
          ;; land on the same Meter shape regardless of which one was used.
          :StringLit
          (let [parsed-val (if (= ctx-key :Meter) (el/parse-meter-str val) val)
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":\"" val "\"" raw-mark)}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val ip)
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
        chain       (flat/current-context-chain state)
        pitch-node (find-child children :Pitch)
        dur        (or (extract-duration children) @(:last-dur state))
        art        (extract-articulation children)
        slur-marks (extract-slur-marks children)
        modifiers  (extract-modifiers children)
        tied       (has-tie? children)]
    (if pitch-node
      (let [[midi new-last] (resolve-pitch-from-tree (rest pitch-node) state)]
        (reset! (:last-pitch state) new-last)
        (when dur (reset! (:last-dur state) dur))
        (apply-note-dynamics! (or ctx (c/context)) (duration state) modifiers)
        (flat/append-child state
                           (assoc (d/leaf (or token (str "note-" midi))
                                          (or ctx (c/context)) dur (if midi [midi] [])
                                          (slur-articulation! state (articulation-ratio art) slur-marks)
                                          (when (map? art) (:dynamic art)) modifiers tied)
                                  :ctx-chain chain)))
      state)))

(defn- walk-chord [state children token]
  (let [ctx       (flat/current-context state)
        chain     (flat/current-context-chain state)
        pitches   (filter #(tag? % :Pitch) children)
        dur       (or (extract-duration children) @(:last-dur state))
        art       (extract-articulation children)
        slur-marks (extract-slur-marks children)
        modifiers (extract-modifiers children)
        tied      (has-tie? children)]
    (if (seq pitches)
      (let [midis     (atom [])
            first-ref (atom nil)]
        ;; Matches real LilyPond octave-entry semantics (confirmed
        ;; against its own docs, not guessed): within a chord, each
        ;; pitch after the first resolves relative to the PREVIOUS PITCH
        ;; WITHIN THE SAME CHORD (sequential chaining, same "nearest
        ;; fourth" rule a plain note stream already uses -- hence
        ;; mutating (:last-pitch state) directly here, the same atom
        ;; resolve-pitch-from-tree itself reads, right after each pitch)
        ;; -- but whatever comes AFTER the chord is anchored to the
        ;; chord's OWN FIRST note, not its last. Both halves matter: an
        ;; earlier version of this fn used a separate local atom that
        ;; only got written back to (:last-pitch state) once, after the
        ;; whole chord, so every pitch silently resolved against the
        ;; same pre-chord reference instead of chaining at all; a fix
        ;; that only added the chaining (mutate (:last-pitch state)
        ;; directly, per pitch, full stop) still left the chord's LAST
        ;; tone as the anchor for the next event -- for a chord whose
        ;; pitches are listed high-to-low (very common piano voicing,
        ;; e.g. <c g eb>), sequential chaining alone naturally keeps
        ;; landing lower with each tone, and using that lowest tone to
        ;; anchor the next chord compounds the same downward bias
        ;; indefinitely. Confirmed live as a real, severe bug either
        ;; way: a long relative-mode passage alternating chords and bare
        ;; notes (Beethoven's Pathétique) drifted by whole octaves per
        ;; chord, reaching pitches hundreds of semitones off within a
        ;; few bars -- not a rounding/octave-choice nicety, a completely
        ;; unusable result. Resetting (:last-pitch state) to the FIRST
        ;; tone's own resolution once the chord is done (rather than
        ;; leaving whatever the sequential chaining left it at) is what
        ;; actually stops the compounding, matching LilyPond's own rule.
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m)
            (when (nil? @first-ref) (reset! first-ref l))
            (reset! (:last-pitch state) l)))
        (reset! (:last-pitch state) @first-ref)
        (apply-note-dynamics! (or ctx (c/context)) (duration state) modifiers)
        (when dur (reset! (:last-dur state) dur))
        (flat/append-child state
                           (assoc (d/leaf (or token (str "chord-" (str/join "-" @midis)))
                                          (or ctx (c/context)) dur (vec @midis)
                                          (slur-articulation! state (articulation-ratio art) slur-marks)
                                          (when (map? art) (:dynamic art)) modifiers tied)
                                  :ctx-chain chain)))
      state)))

(defn- walk-rest [state children token]
  (let [ctx   (flat/current-context state)
        chain (flat/current-context-chain state)
        dur   (or (extract-duration children) @(:last-dur state))]
    (when dur (reset! (:last-dur state) dur))
    (flat/append-child state
                       (assoc (d/rest* (or token (str "rest-" dur)) (or ctx (c/context)) dur)
                              :ctx-chain chain))))

(defn- walk-drum [state children token]
  (let [ctx      (flat/current-context state)
        chain    (flat/current-context-chain state)
        dur      (or (extract-duration children) @(:last-dur state))
        drum-mod (find-child children :DrumMod)
        prog     (when drum-mod
                   (let [inner (first (rest drum-mod))
                         val   (second inner)]
                     (data/resolve-drum val)))]
    (flat/append-child state
                       (assoc (d/drum (or token (str "drum-" (or prog "?")))
                                      (or ctx (c/context)) (or dur 1/4) prog)
                              :ctx-chain chain))))

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
   and resolved by whatever traversal visits it (the engine, or
   core.domain.resolve/locate) at traversal time."
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
        seq-node    (find-child children :Scope)
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
        seq-node    (find-child children :Scope)
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

(def ^:private pitch-token-re
  #"^([A-Ga-g])(isis|eses|ses|is|es|s|##|bb|[#bn])?((?:[1-8](?:/|(?!\d)))|(?:'+|,+))?(.*)$")

(defn- pitch-token-parts
  "Split a note token into its pitch-prefix (letter, accidental, octave
   marker -- absolute '5/' or relative ticks \"'+\"/\",+\") and
   everything after it (duration/articulation/NoteSuffix*/Tie, kept
   together and never touched by respelling), same shape as musics.ebnf's
   Pitch/Duration split. nil for a token that isn't a pitch at all (a
   rest's \"r\").

   :absolute? mirrors leaf-parser/resolve-pitch's own rule exactly --
   letter case alone decides absolute vs. relative, not whether an
   explicit octave digit happened to be written (a bare capital letter
   with no digit still resolves absolute, just at resolve-pitch's own
   implicit default octave)."
  [token]
  (when-let [[_ letter accidental octave suffix] (re-matches pitch-token-re token)]
    {:letter letter
     :absolute? (Character/isUpperCase (char (first letter)))
     :accidental (or accidental "")
     :octave (or octave "")
     :suffix suffix}))

(defn- respell-fn
  "Build a transpose-pitches! respell-fn (see flat-core-builder) for
   \\transpose.

   Every transposed note goes through the same key-aware el/key-pitch-
   name lookup key-for-mode (above) uses for resolving a written note
   in the first place -- real diatonic spelling now, not a coarse
   sharps-vs-flats guess: a pitch that's actually one of the active
   key's own scale degrees is spelled with that degree's own letter,
   the same one an unmarked note under this key would resolve to; only
   a genuinely chromatic pitch falls back to picking sharps vs. flats
   from the key's signature sign. No separate 'it's just an octave
   shift, don't bother' special case either: a whole-octave interval
   leaves the pitch class unchanged, so this same lookup naturally
   comes back with the same letter+accidental it started with -- a
   real key-sensitive 'c stays c', not a hand-rolled shortcut -- while
   a genuine octave difference still shows up in the digit reported.
   One mechanism covers both directions and both cases.

   The token's own format is preserved either way: an absolute note
   (uppercase letter, e.g. \"C5/2\", or even a bare \"C\") gets its
   letter/accidental/octave replaced -- always with an explicit octave
   digit now, even if the original omitted one and relied on
   resolve-pitch's implicit default, since after transposing that
   default would silently stop being correct -- but keeps its duration/
   articulation/tie suffix byte-for-byte. A relative note (lowercase)
   gets just its letter/accidental swapped, keeping whatever ticks/
   suffix it already had -- ticks are left alone too, since \\relative
   resolution depends only on proximity to the previous, equally-
   shifted, note.

   Only respells a single-pitch child (a plain Note); a chord's :id
   would need reconstructing a whole <...> token, out of scope here --
   left unchanged."
  [ctx-chain t]
  (fn [child new-pitches]
    (when (= 1 (count new-pitches))
      (when-let [{:keys [absolute? octave suffix]} (pitch-token-parts (:id child))]
        (let [ks (key-for-mode ctx-chain t)
              [_ nl na nO] (re-matches #"^([a-g])([#b]*)(\d+)$"
                                        (el/key-pitch-name ks (first new-pitches)))]
          (if absolute?
            (str (str/upper-case nl) na nO "/" suffix)
            (str nl na octave suffix)))))))

(defn- walk-transpose [state children]
  (let [from-node (find-child children :from-pitch)
        to-node   (find-child children :to-pitch)
        seq-node  (find-child children :Scope)]
    (if (and from-node to-node seq-node)
      (let [from-pitch (find-child (rest from-node) :Pitch)
            to-pitch   (find-child (rest to-node)   :Pitch)
            from-midi  (leaf/resolve-fixed-pitch (pitch-tuple (rest from-pitch)))
            to-midi    (leaf/resolve-fixed-pitch (pitch-tuple (rest to-pitch)))
            interval   (- to-midi from-midi)
            s1         (flat/push-container state :TRANSPOSE)
            s2         (walk-children s1 (rest seq-node))
            ctx-chain  (keep :context (rseq (:stack s2)))
            t          (d/duration (:repo s2) (peek (:stack s2)))]
        (-> s2
            (flat/transpose-pitches! interval (respell-fn ctx-chain t))
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
            src       (flat/ensure-id s2 (peek (:stack s2)))]
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
            seq-composite (flat/ensure-id s2 (peek (:stack s2)))
            s3            (update s2 :stack pop)
            [s4 alt]
            (if volta-node
              (let [alt-seq (find-child (rest volta-node) :Sequence)]
                (if alt-seq
                  (let [sa (flat/push-container s3 :SEQ)
                        sb (walk-children sa (rest alt-seq))
                        a  (flat/ensure-id sb (peek (:stack sb)))
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