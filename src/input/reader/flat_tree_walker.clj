(ns input.reader.flat-tree-walker
  "Post-parse tree walker for musics.ebnf -- the Clojure-flavored
   grammar (container brackets mirror core.async-engine's own play-arg
   mini-language: [ ] sequential, #{ } parallel; times/tuplet/
   transpose/repeat/grace are Lisp prefix calls) that replaced the
   earlier LilyPond-superset grammar of the same name. See musics.ebnf's
   own header comment for the full syntax and the reasoning behind the
   switch; that earlier grammar and this file's own prior LilyPond-
   oriented implementation are preserved in git history (see the
   project root CLAUDE.md for the migration this replaced), not carried
   forward here.

   Every leaf/instruction/variable rule (Note, Pitch, Assignment,
   VarDef, BangConst, Ramp, etc.) is unaffected by the bracket/command
   redesign -- that part of this file is unchanged from before the
   switch. What IS gone, along with the grammar rules that used to
   produce them: :Unit/:AtomicAlgo/:ElementAlgo/:algo/:Time/:Tempo/:Key
   have no case here at all (Unit dropped entirely, @[ ]/@{ } dropped,
   \\time/\\tempo/\\key dropped -- !Meter:/!tempo:/!key: remain the only
   spelling for those three).

   walk-repeat covers unfold/volta/tremolo as one rule (a repeat-type
   value), not two rules sharing a keyword the way an earlier version
   of this grammar had it -- repeat's own fields are `count`/
   `alternative`. walk-times/walk-tuplet/walk-transpose/walk-grace
   never read a command's own leading keyword text directly, only
   find-child lookups by tag (multiply-factor/divide-factor/from-pitch/
   to-pitch/Sequence) -- confirmed live, not assumed, when this file
   was written."
  (:require [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.music-data :as data]
            [common.defaults :as defaults]
            [common.music-elements :as el]
            [input.reader.leaf-parser :as leaf]
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

(defn- language-for-mode
  "Which \\language (a keyword into common.music-data/accidental-tables)
   is active in chain at beat t -- :nederlands (this DSL's own default,
   matching LilyPond's own) if nothing was ever set. Same chain/t shape
   key-for-mode already samples with, one more key in the same spirit:
   an interpretation-mode flag read from context, not a sounding value."
  [chain t]
  (or (c/ctx-value-chain chain :language t) :nederlands))

(defn- resolve-pitch-from-tree
  "Resolve one written note against the walk's own running :last-pitch
   ref, key-for-mode's Key (for a bare letter's implied accidental), and
   language-for-mode's active \\language (for how accidental-str itself,
   when one WAS written, should be read -- see leaf-parser/
   accidental-semitones)."
  [pitch-children state]
  (let [chain (walk-key-chain state)
        t     (duration state)
        lang  (language-for-mode chain t)]
    (leaf/resolve-pitch (pitch-tuple pitch-children) @(:last-pitch state)
                         (key-for-mode chain t) lang)))

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
   an open-ended ramp, same as a bare !vol</!vol> (see walk-assignment) --
   its own starting value is resolved immediately, from whatever's
   already ambient in chain's ancestors (chain minus its own first,
   innermost pair, which is ctx itself -- see context.clj's own
   `ambient-value`), and appended as a real point under the hairpin's
   own direction/curve ip so ordinary envelope interpolation carries it
   toward whatever target eventually arrives, with no sentinel or
   query-time special-casing needed at all. If nothing at all is
   ambient (only possible for an unregistered custom key with no root
   default anywhere), no start point is appended -- same as
   ctx-value-chain already treats 'found nothing anywhere in the
   chain'. Chained after a dynamic (c4\\mf\\<), there IS a known numeric
   value right here, so the hairpin instead re-stamps that same point
   with the ramp's IP -- one real point that both sets the volume and
   starts the curve, the same trick a timed Ramp uses when a local start
   value is already active (see walk-assignment)."
  [ctx t modifiers chain]
  (let [mark    (some (fn [[k v]] (when (= k "dynamic") v)) modifiers)
        dir     (some (fn [[k v]] (when (= k "hairpin") v)) modifiers)
        vol     (when mark (leaf/resolve-dynamic mark))
        ip      (when dir (resolve-ip nil dir))]
    (cond
      (and vol ip) (c/ctx-append ctx :volume t vol ip)
      vol          (c/ctx-append ctx :volume t vol :fixed)
      ip           (when-let [amb (c/ambient-value (rest chain) :volume)]
                     (c/ctx-append ctx :volume t amb ip)))))

(defn- has-tie? [children] (boolean (find-child children :Tie)))

(def ^:private legato-duration
  "Duration multiplier a slur forces on the notes it spans -- same value
   as the \\legato articulation shorthand (data/articulations). Baked
   directly onto each spanned Leaf (see slur-articulation! below), not
   sampled from context at resolve time -- a slur marks specific notes,
   LilyPond-style, so it has to travel with those notes rather than with
   a time window."
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
   follow. A note that opens a slur (its own '(' or an outer state
   already in effect) is itself part of the slur; a note that closes one
   (')') is the last note still inside it -- the state only turns off
   for notes *after* this one. An explicit shorthand on the note always
   wins, same as it would outside any slur.
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

   Example: { my-ctx: !tempo:120 } registered at t=0.
   Referenced at beat 4: tempo point added at t=4 in current context."
  [state ref-ctx]
  (let [current-ctx (flat/current-context state)
        t           (d/duration (:repo state) (peek (:stack state)))]
    (flat/replay-context! current-ctx ref-ctx t)
    state))

;; ============================================================
;; Variables (name = [ ... ] / \name)
;; ============================================================
;; Real grammar constructs (VarDef/VarRef), resolved in the same single
;; top-to-bottom walk everything else uses.
;;
;; walk-var-def builds the value the same way a real Sequence would (its
;; own :context, so an instruction inside it -- !f or a note-glued \f --
;; has somewhere real to write to), then, instead of registering it,
;; stashes {:children :context} in state's :var-map under its name and
;; discards the scratch container. walk-var-ref looks the name up,
;; splices the stored children in flat (same shape a times/tuplet body
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
        seq-node  (find-child children :Sequence)]
    (if (and name seq-node)
      ;; :last-pitch/:last-dur/:in-slur? are shared, mutable atoms
      ;; threaded through the WHOLE walk, not per-container state -- see
      ;; flat-tree-walker's own walk-var-def for the confirmed-live bug
      ;; this save/restore closes.
      (let [saved-pitch @(:last-pitch state)
            saved-dur   @(:last-dur state)
            saved-slur  @(:in-slur? state)
            _           (reset! (:last-pitch state) nil)
            _           (reset! (:last-dur state) 1/4)
            _           (reset! (:in-slur? state) false)
            s1     (flat/push-container state :VARDEF)
            s2     (walk-children s1 (rest seq-node))
            built  (peek (:stack s2))
            s3     (update s2 :stack pop)]
        (reset! (:last-pitch state) saved-pitch)
        (reset! (:last-dur state) saved-dur)
        (reset! (:in-slur? state) saved-slur)
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
         walk-partial
         walk-note walk-chord walk-rest walk-multi-rest walk-drum
         walk-bareword walk-primitive walk-container-field
         walk-times walk-tuplet walk-transpose
         walk-repeat walk-grace)

(def data-element-types
  "The closed vocabulary a Data container's elements -- and its own
   optional `type` prefix -- are checked against (see
   check-data-element-type!/walk-element's :type case below): the
   grammar's own Atom kinds that carry real musical meaning
   (:pitch/:duration/:articulation -- everything a Leaf itself can
   carry) plus its Primitive kinds (:int/:float/:ratio/:string/
   :keyword/:name), which are generic values, NOT automatically atoms
   just because they happen to share a Clojure representation with one
   -- a bare Ratio Primitive is not per se a :duration, even though
   both walk to a plain Clojure Ratio once appended (see this ns's own
   :Pitch/:DurationNum/walk-primitive cases -- there is no wrapper left
   on the committed child to tell them apart after the fact, which is
   exactly why this has to be checked HERE, at the point each element
   is actually built, not inferred later from its bare value)."
  #{:pitch :duration :articulation :int :float :ratio :string :keyword :name})

(defn- check-data-element-type!
  "Track and validate the single, shared type of every DataElement
   appended to the :DATA container currently on top of state's own
   stack. The FIRST element to arrive (or an explicit `type` prefix,
   always walked before any element per Data's own grammar rule) fixes
   it; every element after that must agree, or this throws a clear
   ex-info rather than silently letting one Data container mix kinds a
   factory downstream (core.wall/configure-preset!) could never
   distinguish again once appended. Reuses the SAME :data-type field
   the composer's own optional `type` prefix already writes (see
   walk-container-field) -- one field, not a separate scratch one, so
   a Data container with no explicit prefix still ends up with an
   accurate :data-type once it has at least one element, for free."
  [state type-kw]
  (let [idx     (dec (count (:stack state)))
        current (get-in state [:stack idx :data-type])]
    (cond
      (nil? current) (update-in state [:stack idx :data-type] (constantly type-kw))
      (= current type-kw) state
      :else (throw (ex-info
                     (str "Data container mixes " current " and " type-kw
                          " -- a Data container's elements must all share one type")
                     {:declared current :found type-kw})))))

(defn- walk-element
  [state node]
  (if (string? node)
    state
    (let [tag      (first node)
          children (rest node)]
      (case tag
        ;; ---- Composites ---- (no :Unit/:AtomicAlgo/:ElementAlgo/:algo
        ;; case -- musics.ebnf produces none of those tags at all, see
        ;; its own header comment on what was dropped and why)
        :Context     (walk-context state children)
        :Sequence    (let [s (flat/push-container state :SEQ)]
                       (->> (walk-children s children) flat/pop-container))
        :Parallel    (let [s (flat/push-container state :PAR)]
                       (->> (walk-children s children) flat/pop-container))
        :Data        (let [s (flat/push-container state :DATA)]
                       (->> (walk-children s children) flat/pop-container))
        ;; ---- Container identifying field (Data's `type`) ----
        ;; Wraps a bare Name and identifies the container, not its
        ;; content -- stamp it onto the container being built rather
        ;; than appending it as a data child. Checked against
        ;; data-element-types -- an arbitrary label like `talea` (a
        ;; semantic ROLE, not a real element type) is rejected right
        ;; here rather than silently accepted; see
        ;; check-data-element-type!'s own docstring for why this same
        ;; field also gets set/validated per-element, not just here.
        :type
        (let [name-val (some-> (find-child children :Name) second)
              kw       (some-> name-val keyword)]
          (if (and kw (not (data-element-types kw)))
            (throw (ex-info
                     (str "'" name-val "' is not a recognized Data element type -- expected one of "
                          data-element-types)
                     {:given kw :expected data-element-types}))
            (walk-container-field state children :data-type)))
        ;; ---- References ----
        :Reference   (walk-reference state children)
        ;; ---- Variables ----
        :VarDef      (walk-var-def state children)
        :VarRef      (walk-var-ref state children)
        ;; ---- Comments: real, tagged nodes (see musics.ebnf's ws/
        ;; Comment) so a later parse error's position is always relative
        ;; to the original text -- nothing is stripped before instaparse
        ;; runs. Purely discarded here, same as a bare ws-artifact
        ;; string.
        :Comment     state
        ;; ---- Instructions ---- (no :Time/:Tempo/:Key -- this grammar
        ;; dropped those LilyPond-conformity concessions entirely,
        ;; !Meter:/!tempo:/!key: remain the only spelling)
        :BangConst    (walk-bang-const    state children)
        :Assignment   (walk-assignment    state children)
        :KeyAssignment (walk-key-assignment state children)
        :Invalidate   (walk-invalidate    state children)
        :Partial      (walk-partial       state children)
        :BarLine      (flat/append-child state (d/bar (count (first children))))
        ;; ---- Leaves ----
        :Note  (walk-note  state children (node-text state node))
        :Chord (walk-chord state children (node-text state node))
        :Rest      (walk-rest      state children (node-text state node))
        :MultiRest (walk-multi-rest state children (node-text state node))
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
        ;; walk-element, so there's no risk of double-handling here. Each
        ;; case checks its own type against check-data-element-type! FIRST
        ;; (throwing if it disagrees with an earlier element or an explicit
        ;; `type` prefix), then appends a PLAIN value -- a MIDI int, a
        ;; Ratio -- never a {:type :X :val v} wrapper: a Data container
        ;; feeds algorithms (color/talea and the like, see
        ;; core.wall/configure-preset!), and the composer calling that
        ;; algorithm already knows what each argument means once every
        ;; element in the container is guaranteed to be one, single,
        ;; checked type -- carrying a per-element tag on top of that
        ;; guarantee would be redundant, not just unread.
        :Pitch     (let [[midi new-last] (resolve-pitch-from-tree children state)]
                     (reset! (:last-pitch state) new-last)
                     (-> state (check-data-element-type! :pitch) (flat/append-child midi)))
        :DurationNum     (-> state (check-data-element-type! :duration)
                              (flat/append-child (parse-duration (first children))))
        :DurationSpecial (-> state (check-data-element-type! :duration)
                              (flat/append-child (parse-duration (first children))))
        ;; :BareDuration ('/4, '/8., authoring a talea) has no case of
        ;; its own -- see the fallback below, same as flat-tree-walker.
        :Articulation
        (-> state
            (check-data-element-type! :articulation)
            (flat/append-child
              (leaf/resolve-articulation
                (or (some-> (find-child children :ArticulationShorthand) second)
                    (some-> (find-child children :Name) second)))))
        ;; ---- Commands ---- (no separate :tremolo case -- repeat's own
        ;; rule covers unfold/volta/tremolo as one rule, see walk-repeat
        ;; below)
        :times     (walk-times     state children)
        :tuplet    (walk-tuplet    state children)
        :transpose (walk-transpose state children)
        :repeat    (walk-repeat    state children)
        :grace     (walk-grace     state children)
        ;; ---- Fallback: descend ----
        (reduce walk-element state children)))))

(defn- walk-children [state children]
  (loop [st state remaining (vec children)]
    (if (seq remaining)
      (recur (walk-element st (first remaining)) (rest remaining))
      st)))

;; ============================================================
;; Context definition  { id: instructions }
;; ============================================================

(defn- walk-context
  "Walk a { } Context definition block.
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
;; Container identifying field  ('[ type ... ])
;; ============================================================

(defn- walk-container-field
  "Stamp a bare Name value (Data's `type`) onto the container currently
   on top of the stack, under field. This identifies the container
   itself -- it is not musical/data content, so it must not be appended
   as a child."
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

(defn- walk-partial
  "\\partial <duration> -- a plain, :fixed context value under :Partial,
   same shape !Meter:/!tempo: already store their own values as."
  [state children]
  (let [dur (extract-duration children)]
    (if dur
      (let [ctx    (flat/current-context state)
            t      (duration state)
            obj    {:type :instruction :key :Partial :val dur
                    :raw  (str "\\partial " dur)}
            state' (flat/append-child state obj)]
        (c/ctx-append ctx :Partial t dur :fixed)
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
            chain     (flat/current-context-chain state)
            t         (duration state)
            ctx-key   (defaults/canonical-key (keyword name-val))
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
              (let [obj    {:type :assignment
                            :key  (keyword name-val)
                            :val  (str "ramp" dir)
                            :raw  (str "!" name-val dir curve)}
                    state' (flat/append-child state obj)
                    amb    (c/ambient-value (rest chain) ctx-key)]
                (when amb
                  (c/ctx-append ctx ctx-key t amb ip))
                state')))

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
        (apply-note-dynamics! (or ctx (c/context)) (duration state) modifiers chain)
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
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m)
            (when (nil? @first-ref) (reset! first-ref l))
            (reset! (:last-pitch state) l)))
        (reset! (:last-pitch state) @first-ref)
        (apply-note-dynamics! (or ctx (c/context)) (duration state) modifiers chain)
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

(defn- walk-multi-rest
  "R -- see musics.ebnf's own comment on MultiRest for the full
   LilyPond-superset story: an explicit Duration is used literally, *n
   multiplying it. With no Duration at all, one bar's length is derived
   from whatever Meter is actually active right here."
  [state children token]
  (let [ctx      (flat/current-context state)
        chain    (flat/current-context-chain state)
        dur      (extract-duration children)
        n-node   (find-child children :Int)
        n        (if n-node (Integer/parseInt (second n-node)) 1)
        bar-dur  (when-not dur
                   (el/meter-bar-length (c/ambient-value chain :Meter)))
        total    (* (or dur bar-dur) n)]
    (flat/append-child state
                       (assoc (d/rest* (or token (str "R-" total)) (or ctx (c/context)) total)
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

(defn- walk-primitive
  "Only ever reached as a bare DataElement inside a Data container, same
   as the :Pitch/:DurationNum/:DurationSpecial/:Articulation cases right
   above it in walk-element -- checks type against
   check-data-element-type! (throwing if it disagrees with an earlier
   element or an explicit `type` prefix), then appends a PLAIN value,
   not a {:type :X :val v} wrapper, same reason those do."
  [state type children]
  (let [val   (first children)
        state (check-data-element-type! state type)]
    (case type
      :int     (flat/append-child state (Integer/parseInt val))
      :float   (flat/append-child state (Double/parseDouble val))
      :ratio   (let [parts (str/split val #"/")]
                 (flat/append-child state (/ (Integer/parseInt (first parts))
                                              (Integer/parseInt (second parts)))))
      :string  (flat/append-child state val)
      :keyword (flat/append-child state (keyword val))
      :name    (flat/append-child state val)
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
;; times/tuplet/transpose never read a command's own leading keyword
;; text -- only find-child lookups by tag -- so these three are
;; unmodified from flat-tree-walker: whether the source spelled this
;; \times 2/3 { ... } or (times 2/3 [ ... ]) is invisible by the time
;; the tree reaches here.

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

(def ^:private pitch-token-re
  #"^([A-Ga-g])(isis|eses|ses|is|es|s|##|bb|[#bn])?((?:[1-8](?:/|(?!\d)))|(?:'+|,+))?(.*)$")

(defn- pitch-token-parts
  "Split a note token into its pitch-prefix (letter, accidental, octave
   marker) and everything after it -- see flat-tree-walker's own version
   for the full reasoning, identical here since Note/Pitch/Duration
   spelling is unchanged."
  [token]
  (when-let [[_ letter accidental octave suffix] (re-matches pitch-token-re token)]
    {:letter letter
     :absolute? (Character/isUpperCase (char (first letter)))
     :accidental (or accidental "")
     :octave (or octave "")
     :suffix suffix}))

(defn- respell-fn
  "Build a transpose-pitches! respell-fn (see flat-core-builder) for
   \\transpose -- identical to flat-tree-walker's own, see that ns for
   the full reasoning (diatonic respelling via key-for-mode, format
   preserved either way, single-pitch children only)."
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
        seq-node  (find-child children :Sequence)]
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
   is non-zero, capped at grace-cap of the main note's own duration --
   identical to flat-tree-walker's own, see that ns for the full
   reasoning."
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

(defn- walk-grace
  "(grace G M) / (acciaccatura G M) / (appoggiatura G M) /
   (slashedGrace G M) / (afterGrace M G) -- grace-type is the bare,
   untagged keyword text musics.ebnf's own grace rule leaves visible
   (five variants share one rule name, so the word is the only way to
   tell them apart -- see that grammar's own header comment on hidden
   vs. visible command keywords). The (str/replace #\"\\\\\" \"\")
   backslash-strip is a harmless no-op here -- musics.ebnf's grace-type
   words ('grace'/'acciaccatura'/.../'afterGrace') never carry a
   leading backslash in the first place -- kept rather than removed
   since it's free and guards against a future grammar change that
   reintroduces one."
  [state children]
  (let [grace-type    (some-> (first (filter string? children))
                              (str/replace #"\\" ""))
        element-nodes (filter (complement string?) children)
        after?        (= grace-type "afterGrace")
        built         (-> state
                          (flat/push-container :DECORATED)
                          (#(reduce walk-element % element-nodes)))
        [c1 c2]                (:children (peek (:stack built)))
        [grace-part main-part] (if after? [c2 c1] [c1 c2])
        [repo' grace' main']   (borrow-grace-duration (:repo built) grace-part main-part)
        grace''                ((grace-tag (or grace-type "grace")) grace')
        new-children           (if after? [main' grace''] [grace'' main'])]
    (-> built
        (assoc :repo repo')
        (flat/set-children! new-children)
        flat/pop-container)))

;; ============================================================
;; Command handlers — Iterator
;; ============================================================

(defn- walk-repeat
  "(repeat unfold/volta/tremolo N [body] (alternative [altbody])?) --
   one rule covers all three repeat-types (a repeat-type value,
   'unfold'/'volta'/'tremolo'), rather than tremolo being a separate
   rule that happens to share the same keyword -- see musics.ebnf's own
   comment on its repeat rule for why that unification was deliberate.
   Field names: `count` (the repeat count), `alternative` (the volta-
   only trailing wrapper). seq-node's own find-child :Sequence lookup
   requires a real Sequence body for all three types uniformly -- a
   deliberate grammar-level tightening (an earlier version of this
   grammar allowed a looser bare Element for unfold/volta at the
   grammar level even though the walker itself only ever recognized a
   Sequence body regardless; requiring Sequence here at the grammar
   level closes that gap, and needed no corresponding change in this
   function to do it)."
  [state children]
  (let [repeat-type  (some #{"unfold" "volta" "tremolo"} children)
        count-node   (find-child children :count)
        count-int    (when count-node (find-child (rest count-node) :Int))
        count-val    (when count-int (Integer/parseInt (second count-int)))
        seq-node     (find-child children :Sequence)
        alt-node     (find-child children :alternative)]
    (if (and count-val seq-node)
      (if (= repeat-type "tremolo")
        ;; Measured tremolo: (repeat tremolo N [ seq ]) -> Iterator,
        ;; absorbed from flat-tree-walker's own walk-tremolo.
        (let [s1  (flat/push-container state :SEQ)
              s2  (walk-children s1 (rest seq-node))
              s3  (update s2 :stack pop)
              src (flat/ensure-id s2 (peek (:stack s2)))]
          (make-iterator s3 :TREMOLO src {:count count-val}))
        ;; unfold / volta -> Iterator, unchanged from flat-tree-walker's
        ;; own walk-repeat apart from :repeats -> :count, :volta ->
        ;; :alternative field names.
        (let [s1            (flat/push-container state :SEQ)
              s2            (walk-children s1 (rest seq-node))
              seq-composite (flat/ensure-id s2 (peek (:stack s2)))
              s3            (update s2 :stack pop)
              [s4 alt]
              (if alt-node
                (let [alt-seq (find-child (rest alt-node) :Sequence)]
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
          (make-iterator s4 :REPEAT seq-composite params)))
      state)))

;; ============================================================
;; Public API
;; ============================================================

(defn walk
  "Walk a raw instaparse tree (musics.ebnf's own) and build domain
   objects. Returns {:tree map :root-id keyword :auto-ids map} where
   :tree is the id->container map. input is the original parsed text
   (for token ID extraction via insta/span). session, if given, is an
   existing {:repo :auto-ids} to continue building onto (same :ROOT, id
   counters picking up where they left off) instead of starting fresh."
  [tree & [input session]]
  (let [state            (initial-state input session)
        program-children (rest tree)]
    (loop [st state remaining (vec program-children)]
      (if (seq remaining)
        (recur (walk-element st (first remaining)) (rest remaining))
        (flat/finish st)))))
