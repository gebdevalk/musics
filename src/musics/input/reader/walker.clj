(ns musics.input.reader.walker
  "Post-parse tree walker for musics.ebnf -- the GUIDO-bracketed
   grammar ([ ] sequential, { } parallel, ^{ } Context, '[ ] Data;
   transpose/repeat/volta/grace/reverse/chordmode are bare-word
   commands, LilyPond's names without the backslash) that
   replaced an earlier Lisp-prefix-call/#{ }-parallel/b-flat scheme of
   the same grammar. See musics.ebnf's own header comment for the full
   syntax and the reasoning behind each choice; earlier schemes and
   this file's own prior implementations of them are preserved in git
   history (see the project root CLAUDE.md), not carried forward here.

   Every leaf/instruction/variable rule (Note, Pitch, Assignment,
   VarDef, BangConst, Ramp, etc.) is unaffected by the bracket/command
   spelling -- that part of this file stays the same regardless of
   which era's grammar it's reading. What has no case here at all:
   :Unit/:AtomicAlgo/:ElementAlgo/:musics/algo/:Time/:Tempo/:Key (dropped
   long ago -- !Meter:/!tempo:/!key: remain the only spelling for those
   three) and :times (a tuplet is n:m( ... ), walk-tuplet, or a
   note's own *Ratio duration suffix, resolve-duration+ratio! below).

   walk-repeat covers repeat and measured tremolo as one rule (repeat
   N [ ], repeat tremolo N [ ]) -- repeat's own fields are `count`/
   `alternative`. walk-transpose/walk-reverse/walk-grace never read a
   command's own leading keyword text directly (grace is the one
   exception -- five near-identical shapes share one rule, so ITS own
   keyword text is the only way to tell them apart, see walk-grace) --
   only find-child lookups by tag (from-pitch/to-pitch/Scope) --
   confirmed live, not assumed, when this file was written."
  (:require [musics.domain.context :as c]
            [musics.domain :as d]
            [musics.common.music-data :as data]
            [musics.common.context-keys :as ck]
            [musics.common.music-elements :as el]
            [musics.input.reader.leaf-parser :as leaf]
            [musics.input.reader.builder :as flat]
            [clojure.string :as str]))

;; ============================================================
;; Duration parsing
;; ============================================================

(defn- parse-duration
  "Convert a duration string ('4', '2.', '8..') to a rational.
   longa = 4, breve = 2, otherwise n = 1/n, each dot adding half of what
   the one before it added ('2..' = 1/2 + 1/4 + 1/8)."
  [s]
  (let [s (some-> s (str/replace "\\" ""))]
    (cond
      (nil? s) nil
      (= s "longa") 4
      (= s "breve") 2
      :else
      (let [dots (count (take-while #{\.} (str/replace s #"[^.]+" "")))
            n    (Integer/parseInt (apply str (remove #{\.} s)))]
        (when-not (= 1 (Integer/bitCount n))
          (throw (ex-info (str "/" n " is no note value: a note value is 1, 2, 4, 8, 16, ... -- "
                               "a tuplet is written as one, 3:2( c8 d e ) for three in the time of two")
                          {:duration s})))
        (* (/ 1 n) (- 2 (/ 1 (bit-shift-left 1 dots))))))))

(defn- parse-ratio-str [s]
  (when s
    (let [parts (str/split s #"/")]
      (/ (Integer/parseInt (first parts))
         (Integer/parseInt (second parts))))))

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

(defn- pulse-letter?
  "True if pitch-node's own letter is literally p -- PitchLetterRel = #'[a-gp]'
   (musics.ebnf) already accepts it at the grammar level, but common.
   music-data/diatonic-pcs/diatonic-degree have no p entry, so resolving
   it as an ordinary pitch throws a NullPointerException (confirmed live,
   not hypothetical, in leaf-parser/rel->midi). walk-note checks this
   BEFORE ever calling resolve-pitch-from-tree, building a Pulse instead
   -- see musics.domain/pulse. PitchLetterAbs is uppercase-only
   (#'[A-G]'), so p can only ever arrive via the relative branch; any
   Accidental/OctaveTicks written alongside it (p#4, p'4 -- grammar
   permits both, since Pitch's relative alternative doesn't itself
   distinguish letters) are silently ignored, same tolerance a Rest
   already has for having no pitch to speak of. Only walk-note ever
   calls this -- p can't reach walk-chord at all: musics.ebnf's own
   ChordPitch excludes it with a negative lookahead (!'p'), so <c e p>4
   is a genuine parse error, never something the walker itself has to
   guard against."
  [pitch-node]
  (= "p" (first (pitch-tuple (rest pitch-node)))))

(defn- walk-key-chain
  "Nearest-first vector of every Context still open on the walker's own
   stack right now -- same idea as respell-fn's chain (below) and
   musics.core's full-ctx-chain, but built from the in-progress walk
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

(defn- resolve-pitch-from-tree
  "Resolve one written note against the walk's own running :last-pitch
   ref and key-for-mode's Key (for a bare letter's implied accidental).
   A written accidental is always one of musics.ebnf's symbols."
  [pitch-children state]
  (let [chain (walk-key-chain state)
        t     (duration state)]
    (leaf/resolve-pitch (pitch-tuple pitch-children) @(:last-pitch state)
                         (key-for-mode chain t))))

(defn- written-key
  "The Key this leaf's bare letters resolve against, kept on the leaf
   as :key so playback can read them under another key
   (musics.domain.resolve/rekey) -- nil under :accidentals :explicit,
   where letters are literal and never follow a key. A transpose
   around the leaf adds its interval to :key-shift instead of changing
   :key, which stays comparable with the context's :key."
  [state]
  (let [chain (walk-key-chain state)
        t     (duration state)]
    (when-not (= :explicit (c/ctx-value-chain chain :accidentals t))
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

(defn- extract-duration-ratio
  "The DurationRatio -- a *N/M scale factor glued onto an explicit
   DurationNum (c4*1/3) -- as a plain rational, or nil when this leaf
   has no explicit Duration at all, or an explicit Duration with no
   ratio of its own (DurationSpecial, \\longa/\\breve, never carries
   one). See musics.ebnf's own DurationRatio for the syntax."
  [children]
  (let [dur-node (find-child children :DurationNum)]
    (when dur-node
      (when-let [ratio-node (find-child (rest dur-node) :DurationRatio)]
        (parse-ratio-str (second (find-child (rest ratio-node) :Ratio)))))))

(defn- resolve-duration+ratio!
  "This leaf's own effective (base * ratio) duration -- musics.ebnf's
   entire tuplet mechanism: an explicit Duration here (c4*1/3, or a
   plain c4) always resets BOTH state's own :last-dur and :last-ratio
   -- a written duration with no ratio of its own (c4 after an earlier
   c4*1/3) deliberately clears the inherited ratio back to 1, not just
   the base. With no explicit Duration at all, both are inherited
   unchanged from whatever the previous leaf last wrote. This is the
   walk-time half of the inheritance musics.ebnf's own DurationRatio
   comment documents -- the grammar only defines the syntax."
  [state children]
  (let [base (extract-duration children)]
    (* (or (some-> (:tuplet state) deref) 1)
       (if base
         (let [ratio (or (extract-duration-ratio children) 1)]
           (reset! (:last-dur state) base)
           (reset! (:last-ratio state) ratio)
           (* base ratio))
         (* @(:last-dur state) @(:last-ratio state))))))

(defn- tag-ratio
  "state, its last leaf carrying the duration ratio it was written with
   (c4*1/3, or inherited from it: 1/3) as :ratio when that isn't 1, so
   it prints back as written (leaf-parser/part->mus)."
  [state]
  (let [r   (* @(:last-ratio state) (or (some-> (:tuplet state) deref) 1))
        idx (dec (count (:stack state)))
        leaf (peek (get-in state [:stack idx :children]))]
    (if (or (= 1 r) (not (map? leaf)) (not (:duration leaf)))
      state
      (update-in state [:stack idx :children] #(conj (pop (vec %)) (assoc leaf :ratio r))))))

(defn- extract-articulation [children]
  (let [art-node (find-child children :Articulation)]
    (when art-node
      (let [art-children (rest art-node)
            shorthand    (some-> (find-child art-children :ArticulationShorthand) second)
            name-node    (find-child art-children :ArticulationName)]
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
  "A note's ornament and tremolo, as [kind value] pairs for
   musics.domain.ornaments to expand (c4:32 -> [\"tremolo\" 32]). A
   \\name:value Modifier or a glued dynamic is an override instead, see
   extract-overrides."
  [children]
  (concat
   (for [[_ & sub-children] (find-all-children children :Ornament)]
     ["ornament" (second (find-child sub-children :OrnamentName))])
   (for [[_ & sub-children] (find-all-children children :Tremolo)]
     ["tremolo" (some-> (find-child sub-children :Int) second Integer/parseInt)])))

(defn- instrument-list
  "The numbers of an IntList, for ctx-key, which must be :instrument --
   only an instrument takes a list (prefix: \"!\" or \"\\\" as written)."
  [ctx-key prefix int-nodes]
  (let [nums (mapv #(Integer/parseInt (second %)) int-nodes)]
    (when-not (= :instrument ctx-key)
      (throw (ex-info (str prefix (name ctx-key) ":" (str/join "," nums)
                           " -- only an instrument (!i:, \\i:) takes a list")
                      {:key ctx-key})))
    nums))

(defn- modifier-value
  "A Modifier's value, read as !name:value reads it: a signed number or
   ratio as a number, a Name as a dynamic mark's volume when it is one (\\vol:mf)
   and a keyword otherwise, a string as itself (a Meter parsed)."
  [ctx-key [tag v :as node]]
  (case tag
    :IntList     (instrument-list ctx-key "\\" (rest node))
    :SignedInt   (Long/parseLong v)
    :SignedFloat (Double/parseDouble v)
    :Ratio       (let [[n d] (str/split v #"/")] (/ (Long/parseLong n) (Long/parseLong d)))
    :Name      (or (leaf/resolve-dynamic v) (keyword v))
    :StringLit (if (= ctx-key :Meter) (el/parse-meter-str v) v)
    v))

(defn- extract-overrides
  "A note's own values for context keys, over the context's: its
   \\name:value Modifiers (c4\\vol:90\\i:40 -> {:volume 90 :instrument 40},
   aliases canonicalized as !name: does) and a glued dynamic mark, which
   is the note's volume (c4\\f -> {:volume 70}). nil when there are none."
  [children]
  (not-empty
   (merge
    (into {} (for [[_ [_ mark]] (find-all-children children :Dynamic)]
               [:volume (leaf/resolve-dynamic mark)]))
    (into {} (for [[_ [_ name] val-node] (find-all-children children :Modifier)
                   :let [k (ck/canonical-key (keyword name))]]
               [k (modifier-value k val-node)])))))

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
      :SignedInt       (Integer/parseInt (second node))
      :SignedFloat     (Double/parseDouble (second node))
      :SignedRatio     (let [parts (str/split (second node) #"/")]
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
   builder/replay-context! for the shared mechanism -- also
   used to replay a transient command's own context onto its parent).

   Example: { my-ctx: !tempo:120 } registered at t=0.
   Referenced at beat 4: tempo point added at t=4 in current context."
  [state ref-ctx]
  (let [current-ctx (flat/current-context state)
        t           (d/written-duration (:repo state) (peek (:stack state)))]
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
;; splices the stored children in flat (same shape a tuplet's body
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
      ;; :last-pitch/:last-dur/:last-ratio/:in-slur? are shared, mutable
      ;; atoms threaded through the WHOLE walk, not per-container state
      ;; -- see walker's own walk-var-def for the confirmed-
      ;; live bug this save/restore closes.
      (let [saved-pitch @(:last-pitch state)
            saved-dur   @(:last-dur state)
            saved-ratio @(:last-ratio state)
            saved-slur  @(:in-slur? state)
            saved-drum  @(:last-drum state)
            _           (reset! (:last-drum state) nil)
            _           (reset! (:last-pitch state) nil)
            _           (reset! (:last-dur state) 1/4)
            _           (reset! (:last-ratio state) 1)
            _           (reset! (:in-slur? state) false)
            s1     (flat/push-container state :VARDEF)
            s2     (walk-children s1 (rest seq-node))
            built  (peek (:stack s2))
            s3     (update s2 :stack pop)]
        (reset! (:last-pitch state) saved-pitch)
        (reset! (:last-dur state) saved-dur)
        (reset! (:last-ratio state) saved-ratio)
        (reset! (:in-slur? state) saved-slur)
        (reset! (:last-drum state) saved-drum)
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
          t          (d/written-duration (:repo state) (peek (:stack state)))
          state'     (reduce flat/append-child state (:children entry))]
      (flat/replay-context! target-ctx (:context entry) t)
      state')))

;; ============================================================
;; Main walker dispatch
;; ============================================================

(declare walk-algo-assignment walk-context walk-reference
         walk-bang-const walk-assignment walk-key-assignment walk-invalidate
         walk-partial
         walk-note walk-chord walk-chord-mode-note walk-rest walk-multi-rest walk-drum
         walk-bareword walk-primitive walk-container-field
         walk-transpose walk-reverse walk-tuplet
         walk-repeat walk-repeat-bars walk-grace)

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
   consumer downstream could never
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
        :AlgoAssignment (walk-algo-assignment state children)
        :KeyAssignment (walk-key-assignment state children)
        :Invalidate   (walk-invalidate    state children)
        :Partial      (walk-partial       state children)
        :BarLine      (flat/append-child state (d/bar (count (first children))))
        :Jump         (flat/append-child state (d/jump ({"$" :segno "DS" :ds "DC" :dc "Fine" :fine "Coda" :coda "./." :simile}
                                                        (first children))))
        ;; ---- Leaves ----
        :Note  (tag-ratio (walk-note  state children (node-text state node)))
        :Chord (tag-ratio (walk-chord state children (node-text state node)))
        :ChordModeNote (tag-ratio (walk-chord-mode-note state children (node-text state node)))
        :Rest      (tag-ratio (walk-rest      state children (node-text state node)))
        :MultiRest (walk-multi-rest state children (node-text state node))
        :Drum  (tag-ratio (walk-drum  state children (node-text state node)))
        ;; ---- Id / Primitives ----
        :Id        (walk-bareword  state children)
        :SignedInt   (walk-primitive state :int   children)
        :SignedFloat (walk-primitive state :float children)
        :SignedRatio (walk-primitive state :ratio children)
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
        ;; musics.algo.tree), and the composer calling that
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
        ;; its own -- see the fallback below, same as walker.
        :Articulation
        (-> state
            (check-data-element-type! :articulation)
            (flat/append-child
              (leaf/resolve-articulation
                (or (some-> (find-child children :ArticulationShorthand) second)
                    (some-> (find-child children :Name) second)))))
        ;; ---- Commands ---- (no separate :tremolo case -- repeat's own
        ;; rule covers repeat and tremolo as one rule, see walk-repeat
        ;; below)
        :transpose (walk-transpose state children)
        :repeat    (walk-repeat    state children)
        :RepeatBars (walk-repeat-bars state children)
        :grace     (walk-grace     state children)
        :reverse   (walk-reverse   state children)
        :tuplet    (walk-tuplet    state children)
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

(defn- override-mark
  "state with an override marker appended to the current container: the
   context under ctx-id, written before what follows in this sequence,
   is the first context of all of it (musics.compose/override-of; doc/md/
   decisions.md, 2026-10-15). An instruction-like node, so what doesn't
   play it skips it and the printers write its :raw back. The context
   is also replayed into the container's own (apply-context-ref), so
   what is read while parsing (a key, a meter) sees it."
  [state ctx-id ctx raw]
  (-> (apply-context-ref state ctx)
      (flat/append-child {:type :assignment :key :override :ref ctx-id :raw raw})))

(defn- in-music?
  "Whether the container being built is music (not :ROOT, not a context)."
  [state]
  (let [top (peek (:stack state))]
    (and top (not (#{:ROOT :CONTEXT} (:type top))))))

(defn- walk-context
  "Walk a ^{ } Context definition block.
   Pushes a :CONTEXT container, walks its instructions (which call
   ctx-append on the context's own envelopes), then pops.
   pop-container registers it in repo WITHOUT appending to parent's
   children -- it is a definition form, not musical content. Inside a
   sequence it also applies to what follows there, as its first
   context (override-mark)."
  [state children]
  (let [s   (walk-children (flat/push-container state :CONTEXT) children)
        i   (dec (count (:stack s)))
        s   (update-in s [:stack i] #(flat/ensure-id s %))
        ctx (peek (:stack s))
        s   (flat/pop-container s)]
    (if (in-music? s)
      (override-mark s (:id ctx) (:context ctx)
                     (str "^{ " (when-not (re-matches #"c\d+" (name (:id ctx))) (str (name (:id ctx)) ": "))
                          (str/join " " (keep :raw (:children ctx))) " }"))
      s)))

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
      (if (in-music? state)
        (override-mark state id (:context resolved) (str ":" name-val))
        (apply-context-ref state (:context resolved)))

      ;; Container/iterator reference -- append id as child
      :else
      (flat/append-child state id))))

;; ============================================================
;; Id naming (definition-time: my-name:)
;; ============================================================

(def ^:private nameless
  "Bodies that are never stored under a name of their own -- spliced
   into their parent, or kept as a variable's value -- by what the text
   writes."
  {:TRANSPOSE "transpose ( … )" :REVERSE "reverse ( … )" :DECORATED "grace"
   :TUPLET "a tuplet n:m( … )" :VARDEF "a variable's [ … ]"})

(defn- walk-bareword
  "name: -- names the container it opens. Inside a body that is never
   stored under a name (nameless), the name would be lost, so it is
   refused, pointing to a line of its own inside it."
  [state children]
  (let [name-val (some (fn [c]
                         (cond
                           (string? c) (keyword c)
                           (and (vector? c) (= :Name (first c))) (keyword (second c))))
                       children)]
    (if name-val
      (let [idx (dec (count (:stack state)))]
        (when-let [what (nameless (:type (peek (:stack state))))]
          (throw (ex-info (str (name name-val) ": a name inside " what " would be lost -- "
                               "name a line inside it instead, [" (name name-val) ": … ]")
                          {:id name-val :in what})))
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
  (d/written-duration (:repo state) (peek (:stack state))))

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
                      (ck/canonical-key kw))]
        (c/ctx-invalidate ctx ctx-key t)
        state')
      state)))

(defn- walk-partial
  "partial <duration> -- a plain, :fixed context value under :Partial,
   same shape !Meter:/!tempo: already store their own values as."
  [state children]
  (let [dur (extract-duration children)]
    (if dur
      (let [ctx    (flat/current-context state)
            t      (duration state)
            obj    {:type :instruction :key :Partial :val dur
                    :raw  (str "partial " dur)}
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
            ctx-key   (ck/canonical-key (keyword name-val))
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

          :IntList
          (let [parsed-val (instrument-list ctx-key "!" (rest val-node))
                obj    {:type :assignment :key (keyword name-val)
                        :val parsed-val :raw (str "!" name-val ":" (str/join "," parsed-val))}
                state' (flat/append-child state obj)]
            (c/ctx-append ctx ctx-key t parsed-val :fixed)
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

(defn- algo-value
  "An AlgoAssignment's value as plain data: a number, a string, a
   keyword (a bare name), a vector (a comma list or a ( ) list)."
  [[tag v :as node]]
  (case tag
    :SignedInt     (Integer/parseInt v)
    :SignedFloat   (Double/parseDouble v)
    :Ratio         (let [[n d] (str/split v #"/")] (/ (Integer/parseInt n) (Integer/parseInt d)))
    :StringLit     v
    :QualifiedName (keyword (str/join "." (map second (filter #(tag? % :Name) (rest node)))))
    :IntList       (mapv #(Integer/parseInt (second %)) (rest node))
    :StructValue   (extract-struct-values node)
    (throw (ex-info (str "@: not a value an algo param takes: " (pr-str node)) {:node node}))))

(defn- walk-algo-assignment
  "@name:value -- an algo param, stored in the current context under
   \"@name\", as a point at the current beat (one value, no ramp)."
  [state children]
  (let [nm  (second (find-child children :AlgoName))
        vn  (first (remove #(tag? % :AlgoName) children))
        v   (algo-value vn)
        raw (str "@" nm ":" (or (node-text state vn) (pr-str v)))]
    (c/ctx-append (flat/current-context state) (str "@" nm) (duration state) v :fixed)
    (flat/append-child state {:type :assignment :key (str "@" nm) :val v :raw raw})))

(defn- walk-key-assignment [state children]
  (let [key-node (find-child children :KeySpec)
        key-val  (when key-node (second key-node))]
    (if key-val
      (let [ctx    (flat/current-context state)
            ks     (or (el/parse-key key-val) (el/parse-key (str key-val ".major")))
            obj    {:type :assignment :key :key :val key-val :raw (str "!key:" key-val)}
            t      (duration state)
            state' (flat/append-child state obj)]
        (when-not ks
          (throw (ex-info (str "!key:" key-val " names no key -- write a tonic, # or & and a scale: !key:B&.minor, !key:F#.dorian")
                          {:key key-val})))
        (c/ctx-append ctx :key t ks :fixed)
        state')
      state)))

;; ============================================================
;; Leaf nodes
;; ============================================================

(defn- walk-note [state children token]
  (let [ctx        (flat/current-context state)
        chain       (flat/current-context-chain state)
        pitch-node (find-child children :Pitch)
        dur        (resolve-duration+ratio! state children)
        art        (extract-articulation children)
        slur-marks (extract-slur-marks children)
        modifiers  (extract-modifiers children)
        overrides  (extract-overrides children)
        tied       (has-tie? children)]
    (cond
      (and pitch-node (pulse-letter? pitch-node))
      (flat/append-child state
                         (assoc (d/pulse (or token (str "pulse-" dur)) (or ctx (c/context)) dur 1)
                                :ctx-chain chain))

      pitch-node
      (let [[midi new-last] (resolve-pitch-from-tree (rest pitch-node) state)
            ks              (written-key state)]
        (reset! (:last-pitch state) new-last)
        (flat/append-child state
                           (cond-> (assoc (d/leaf (or token (str "note-" midi))
                                                  (or ctx (c/context)) dur (if midi [midi] [])
                                                  (slur-articulation! state (articulation-ratio art) slur-marks)
                                                  (when (map? art) (:dynamic art)) modifiers tied)
                                          :ctx-chain chain)
                             ks        (assoc :key ks)
                             overrides (assoc :overrides overrides))))

      :else state)))

(defn- walk-chord [state children token]
  (let [ctx       (flat/current-context state)
        chain     (flat/current-context-chain state)
        pitches   (filter #(tag? % :Pitch) children)
        dur       (resolve-duration+ratio! state children)
        art       (extract-articulation children)
        slur-marks (extract-slur-marks children)
        modifiers (extract-modifiers children)
        overrides (extract-overrides children)
        tied      (has-tie? children)]
    (if (seq pitches)
      (let [midis     (atom [])
            first-ref (atom nil)
            ks        (written-key state)]
        (doseq [p pitches]
          (let [[m l] (resolve-pitch-from-tree (rest p) state)]
            (swap! midis conj m)
            (when (nil? @first-ref) (reset! first-ref l))
            (reset! (:last-pitch state) l)))
        (reset! (:last-pitch state) @first-ref)
        (flat/append-child state
                           (cond-> (assoc (d/leaf (or token (str "chord-" (str/join "-" @midis)))
                                                  (or ctx (c/context)) dur (vec @midis)
                                                  (slur-articulation! state (articulation-ratio art) slur-marks)
                                                  (when (map? art) (:dynamic art)) modifiers tied)
                                          :ctx-chain chain)
                             ks        (assoc :key ks)
                             overrides (assoc :overrides overrides))))
      state)))

(defn- apply-chord-addition
  "One ChordAddition node ('.step' or '.step+'/'.step-') -- OVERRIDES
   step-map's entry for that step number with a value computed FRESH
   from musics.common.music-data/chord-step-defaults (+/-1 for a raised/
   lowered suffix), never relative to whatever step-map already held
   for that step -- LilyPond's own documented behaviour ('later entries
   for a given step override earlier entries'), confirmed via its own
   worked example (c:3.5.5-.5+ resolving to an augmented triad, not a
   cumulative adjustment)."
  [step-map addition-node]
  (let [addition-children (rest addition-node)
        step   (Integer/parseInt (second (find-child addition-children :Step)))
        alter  (first (remove #(tag? % :Step) addition-children))
        base   (get data/chord-step-defaults step)]
    (when-not base
      (throw (ex-info (str "step " step " has no default interval for a chordmode addition")
                       {:step step :expected (set (keys data/chord-step-defaults))})))
    (assoc step-map step (case alter "+" (inc base) "-" (dec base) base))))

(defn- apply-chord-removal
  "One ChordRemoval node ('^step(.step)*') -- drops every named step
   number from step-map outright, LilyPond's own documented removal
   syntax, applied AFTER every addition per its own ordering ('Following
   any steps to be added, a series of steps to be removed...')."
  [step-map removal-node]
  (let [steps (map (comp #(Integer/parseInt %) second)
                    (find-all-children (rest removal-node) :Step))]
    (apply dissoc step-map steps)))

(defn- walk-chord-mode-note
  "Walk one chordmode entry -- root Pitch + optional Duration + ':'
   quality (LilyPond's own chordmode modifier word -- see
   musics.common.music-data/chord-qualities, not invented), optional
   '.step[+/-]' additions/overrides and a '^step(.step)*' removal
   (LilyPond's own general alteration mechanism -- see
   apply-chord-addition/apply-chord-removal above), and an optional
   /bass -- into the exact same multi-pitch Leaf shape walk-chord above
   already builds from an explicit <c e g> Chord. Only the ROOT is
   actually written; every other pitch is root-midi + a semitone offset
   read off the quality's own step-keyed table, as adjusted by any
   addition/removal.

   Bass note (ChordBass, /pitch or /+pitch): resolved the same way any
   other written pitch is (continuing the relative-pitch chain off the
   root), then always pushed below every other chord tone by
   transposing it down whole octaves as needed -- genuinely 'moved to
   the bottom', not just left at whatever octave was typed, since this
   DSL plays the result rather than merely engraving it. A plain
   /pitch, when its pitch CLASS already sounds somewhere in the built
   chord, replaces that occurrence (a real inversion, no duplicate
   note); /+pitch always adds a new note regardless -- both exactly
   LilyPond's own documented distinction (Notation Reference, 'Chords
   in other parts'), not a new one."
  [state children token]
  (let [ctx        (flat/current-context state)
        chain      (flat/current-context-chain state)
        root-node  (find-child children :Pitch)
        dur        (resolve-duration+ratio! state children)
        quality-node (find-child children :Quality)
        ;; No ':quality' at all -- LilyPond's own bare-note default
        ;; inside chordmode (a colon-less entry is still a full major
        ;; triad, not just a single note -- see musics.ebnf's own
        ;; comment on ChordModeNote's optional ChordQuality).
        quality-kw (if quality-node (keyword (second quality-node)) :major)
        base-steps (get data/chord-qualities quality-kw)
        additions  (find-all-children children :ChordAddition)
        removal    (find-child children :ChordRemoval)
        bass-node  (find-child children :ChordBass)
        art        (extract-articulation children)
        slur-marks (extract-slur-marks children)
        modifiers  (extract-modifiers children)
        overrides  (extract-overrides children)
        tied       (has-tie? children)]
    (when-not base-steps
      (throw (ex-info (str "'" quality-kw "' is not a recognized chordmode quality")
                       {:given quality-kw :expected (set (keys data/chord-qualities))})))
    (let [step-map    (cond-> (reduce apply-chord-addition base-steps additions)
                         removal (apply-chord-removal removal))
          intervals   (vec (sort (vals step-map)))
          [root-midi root-last] (resolve-pitch-from-tree (rest root-node) state)
          ks          (written-key state)
          chord-midis (mapv #(+ root-midi %) intervals)
          _           (reset! (:last-pitch state) root-last)
          final-midis
          (if bass-node
            (let [bass-children (rest bass-node)
                  always-add?   (= "+" (first bass-children))
                  bass-pitch    (find-child bass-children :Pitch)
                  [bass-midi _] (resolve-pitch-from-tree (rest bass-pitch) state)
                  same-class?   #(= (mod % 12) (mod bass-midi 12))
                  kept          (if always-add? chord-midis (remove same-class? chord-midis))
                  floor         (apply min kept)
                  bass-below    (loop [b bass-midi] (if (< b floor) b (recur (- b 12))))]
              (vec (cons bass-below kept)))
            chord-midis)]
      (flat/append-child state
                          (cond-> (assoc (d/leaf (or token (str "chordmode-" (str/join "-" final-midis)))
                                                 (or ctx (c/context)) dur final-midis
                                                 (slur-articulation! state (articulation-ratio art) slur-marks)
                                                 (when (map? art) (:dynamic art)) modifiers tied)
                                         :ctx-chain chain)
                            ;; a chord symbol follows the key by its root:
                            ;; :key-root tells rekey to move every tone by
                            ;; the root's step, keeping the quality
                            ks        (assoc :key ks :key-root root-midi)
                            overrides (assoc :overrides overrides))))))

(defn- walk-rest [state children token]
  (let [ctx   (flat/current-context state)
        chain (flat/current-context-chain state)
        dur   (resolve-duration+ratio! state children)]
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
        dur      (resolve-duration+ratio! state children)
        art      (extract-articulation children)
        overrides (extract-overrides children)
        drum-mod (find-child children :DrumMod)
        dkeys    (mapv #(data/resolve-drum (second %)) (rest drum-mod))
        prog     (if drum-mod
                   (reset! (:last-drum state) (if (next dkeys) dkeys (first dkeys)))
                   @(:last-drum state))
        prev     (->> (:children (peek (:stack state))) rseq (remove d/bar?) first)
        held?    (and (map? prev) (d/drum? prev) (:tied prev) (= prog (:program prev)))]
    (flat/append-child state
                       (cond-> (assoc (d/drum (or token (str "drum-" (or prog "?")))
                                              (or ctx (c/context)) (or dur 1/4) prog)
                                      :ctx-chain chain)
                         (:dynamic art)       (assoc :dynamic (:dynamic art))
                         overrides            (assoc :overrides overrides)
                         (has-tie? children)  (assoc :tied true)
                         held?                (assoc :held true)))))

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

(defn- make-iterator
  "Create an Iterator and append it to the current parent.
   No parent context wiring -- enclosing context is visit-dependent
   and resolved by whatever traversal visits it (the engine, or
   musics.domain.resolve/locate) at traversal time."
  [state iter-type source params]
  (let [repo    (:repo state)
        stamp   #(d/set-container-duration % (d/duration repo %))
        source  (stamp source)
        params  (cond-> params (:alternative params) (update :alternative stamp))
        iter-id (flat/next-auto-id state iter-type)
        iter    (d/iterator iter-type iter-id (c/context) source params)
        iter    (update iter :context c/set-duration (d/duration repo iter))]
    (flat/append-child state iter)))

;; ============================================================
;; Command handlers — Transient
;; ============================================================
;; transpose/reverse never read a command's own leading keyword text --
;; only find-child lookups by tag -- so neither cares whether the
;; source spelled a Scope's own body with a nested Sequence/Reference/
;; leaf run.

(defn- walk-reverse
  "reverse ( ... ) -- the body backwards, at any depth: a nested line, a
   referenced part and a repeat's body are reversed too (a parallel's
   parts each, in their order); a referenced part is reversed as a copy
   (builder/deep-children!). A repeat with volta endings is refused."
  [state children]
  (let [scope-node (find-child children :Scope)]
    (if scope-node
      (-> state
          (flat/push-container :REVERSE)
          (walk-children (rest scope-node))
          (flat/deep-children! (:repo state) identity (comp vec reverse))
          flat/pop-container)
      state)))

(defn- walk-tuplet
  "n:m( ... ) -- n notes in the time of m: every note inside, at any
   depth of tuplets, plays at m/n of its written value (and keeps the
   factor as :ratio, see tag-ratio, so it prints back as a tuplet). The
   body is spliced into the parent like reverse's."
  [state children]
  (let [[n m]  (map #(Long/parseLong %) (str/split (second (find-child children :TupletCount)) #":"))
        scope  (find-child children :Scope)
        outer  @(:tuplet state)]
    (when (or (zero? n) (zero? m)) (throw (ex-info (str n ":" m " is no tuplet") {:tuplet [n m]})))
    (reset! (:tuplet state) (* outer (/ m n)))
    (try
      (-> state (flat/push-container :TUPLET) (walk-children (rest scope)) flat/pop-container)
      (finally (reset! (:tuplet state) outer)))))

(def ^:private pitch-token-re
  #"^([A-Ga-g])(##|&&|[#&n])?((?:[1-8](?:/|(?!\d)))|(?:'+|,+))?(.*)$")

(defn- pitch-token-parts
  "Split a note token into its pitch-prefix (letter, accidental, octave
   marker) and everything after it -- see walker's own version
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
  "Build a respell fn for
   transpose: each transposed note's name spelled under ks, the key
   transposed along with it (format preserved either way, single-pitch
   children only)."
  [ks]
  (fn [child new-pitches]
    (when (= 1 (count new-pitches))
      (when-let [{:keys [absolute? octave suffix]} (pitch-token-parts (:id child))]
        (let [[_ nl na nO] (re-matches #"^([a-g])([#&]*)(\d+)$"
                                        (el/key-pitch-name ks (first new-pitches)))]
          (if absolute?
            (str (str/upper-case nl) na nO "/" suffix)
            (str nl na octave suffix)))))))

(defn- transpose-leaf
  "A note moved by interval, respelled by respell (see respell-fn)."
  [interval respell]
  (fn [leaf]
    (if (and (= :LEAF (:type leaf)) (seq (:pitches leaf)))
      (let [ps (mapv #(+ interval %) (:pitches leaf))
            id (respell leaf ps)]
        (cond-> (assoc leaf :pitches ps)
          id              (assoc :id id)
          (:key leaf)     (update :key-shift (fnil + 0) interval)
          (:key-root leaf) (update :key-root + interval)))
      leaf)))

(defn- walk-transpose [state children]
  (let [from-node   (find-child children :from-pitch)
        to-node     (find-child children :to-pitch)
        scope-node  (find-child children :Scope)]
    (if (and from-node to-node scope-node)
      (let [from-pitch (find-child (rest from-node) :Pitch)
            to-pitch   (find-child (rest to-node)   :Pitch)
            from-midi  (leaf/resolve-fixed-pitch (pitch-tuple (rest from-pitch)))
            to-midi    (leaf/resolve-fixed-pitch (pitch-tuple (rest to-pitch)))
            interval   (- to-midi from-midi)
            s1         (flat/push-container state :TRANSPOSE)
            s2         (walk-children s1 (rest scope-node))
            ctx-chain  (keep :context (rseq (:stack s2)))
            t          (d/written-duration (:repo s2) (peek (:stack s2)))
            ks         (el/transpose-key (key-for-mode ctx-chain t) interval)]
        (-> s2
            ;; every note at any depth -- nested lines, referenced parts (as
            ;; copies), repeats -- moves by the interval, its name spelled in
            ;; the key transposed with it (C -> D for transpose c d);
            ;; :key-shift records by how much, so playback transposes the
            ;; playing key the same way before comparing
            ;; (musics.domain.resolve/rekey). A drum stays a drum.
            (flat/deep-children! (:repo state) (transpose-leaf interval (respell-fn ks)) identity)
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
   identical to walker's own, see that ns for the full
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
   vs. visible command keywords)."
  [state children]
  (let [grace-type    (first (filter string? children))
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
  "(repeat N [body] (volta [endings])?), or (repeat tremolo N [body])
   -- one rule, tremolo marked by its word. Field names: `count` (the
   repeat count), `alternative` (the endings, :repeat-type :volta).
   Both take a real Sequence body. extra goes into a repeat's params."
  ([state children] (walk-repeat state children nil))
  ([state children extra]
  (let [repeat-type  (some #{"volta" "tremolo"} children)
        count-node   (find-child children :count)
        count-int    (when count-node (find-child (rest count-node) :Int))
        count-val    (cond count-int (Integer/parseInt (second count-int))
                           (some #{"~"} (rest count-node)) :infinite)
        seq-node     (find-child children :Sequence)
        alt-node     (find-child children :alternative)]
    (when (and (= :infinite count-val) alt-node)
      (throw (ex-info "an endless repeat (repeat ~) has no volta endings" {})))
    (if (and count-val seq-node)
      (if (= repeat-type "tremolo")
        ;; Measured tremolo: (repeat tremolo N [ seq ]) -> Iterator,
        ;; absorbed from walker's own walk-tremolo.
        (let [s1  (flat/push-container state :SEQ)
              s2  (walk-children s1 (rest seq-node))
              s3  (update s2 :stack pop)
              src (flat/ensure-id s2 (peek (:stack s2)))]
          (make-iterator s3 :TREMOLO src {:count count-val}))
        ;; a repeat -> Iterator, its endings as :alternative
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
                              :repeat-type :volta}
                             alt (assoc :alternative alt))]
          (make-iterator s4 :REPEAT seq-composite (merge params extra))))
      state))))

(defn- walk-repeat-bars
  "[: ... :] (volta [...])? -- repeat 2 [ ... ], marked
   :bars so it prints back in repeat signs."
  [state children]
  (let [alt (find-child children :alternative)]
    (walk-repeat state
                 (cond-> [[:count [:Int "2"]] (into [:Sequence] (remove #(tag? % :alternative) children))]
                   alt (conj alt))
                 {:bars true})))

;; ============================================================
;; Public API
;; ============================================================

(def ^:private top-level-leaf-tags
  "Tags musics.ebnf's own Leaf rule can produce (Chord | Note | Rest |
   Drum | MultiRest) -- exactly what walk-element itself dispatches on
   for a leaf. A bare one of these at Program's own top level now
   parses (TopElement includes Leaf), but must not be walked directly
   against whatever's on top of the builder stack -- at bare top level
   that's :ROOT itself. See wrap-bare-leaf below. (:ChordModeNote is
   deliberately excluded -- that tag only appears inside chordmode's
   own body, never reachable through Leaf/TopElement.)"
  #{:Note :Chord :Rest :MultiRest :Drum})

(defn- needs-wrapper?
  "A bare top-level Leaf, repeat or tuplet (mu reads one): walked
   directly, a repeat's Iterator would sit inline in :ROOT's :children
   with no id of its own in :repo, a tuplet's notes in :ROOT itself, so
   parse would report nothing and nothing could play it. Wrapped, it's
   an ordinary addressable part."
  [node]
  (and (vector? node)
       (or (contains? top-level-leaf-tags (first node)) (#{:repeat :RepeatBars :tuplet} (first node)))))

(defn- wrap-bare-leaf
  "A bare top-level Leaf is walked into a freshly pushed, ordinary :SEQ
   container instead of directly against whatever's on top of the
   stack -- same push/walk/pop idiom walk-element's own :Sequence case
   uses above, just wrapping exactly one Element instead of a
   Sequence's own child list. The result is an ordinary auto-id'd
   one-child Sequence, registered in :repo and linked into the parent's
   (here, :ROOT's) :children same as any other top-level part, with a
   :context of its own -- so nothing a leaf brings ever lands on :ROOT."
  [state node]
  (->> (walk-element (flat/push-container state :SEQ) node) flat/pop-container))

(defn- written-id
  "The id written on a container node (its Id child, name:), or nil."
  [node]
  (some (fn [c] (when (tag? c :Id)
                  (some-> (find-child (rest c) :Name) second keyword)))
        (rest node)))

(defn walk-each
  "Walk top-level nodes one by one in ONE walk's state -- so a later
   node's relative pitch and elided duration follow the one before it,
   exactly as across a Program's top-level elements -- and record, per
   node, the id it produced: its container's, a bare leaf's wrapper's,
   or an unnamed definition's (a ^{ } context). Returns walk's result
   plus :ids, one per node, in order. For mu, which turns each literal
   of an input into a value of its own."
  [nodes input session]
  (loop [st (initial-state input session) remaining (seq nodes) ids []]
    (if-let [node (first remaining)]
      (let [n-kids (count (:children (peek (:stack st))))
            known  (set (keys (:repo st)))
            st'    (if (needs-wrapper? node) (wrap-bare-leaf st node) (walk-element st node))
            kids   (:children (peek (:stack st')))
            id     (or (let [k (last kids)] (when (and (> (count kids) n-kids) (keyword? k)) k))
                       (first (remove known (keys (:repo st'))))
                       (written-id node))]
        (recur st' (rest remaining) (conj ids id)))
      (assoc (flat/finish st) :ids ids))))

(defn walk
  "Walk a raw instaparse tree (musics.ebnf's own) and build domain
   objects. Returns {:tree map :root-id keyword :auto-ids map} where
   :tree is the id->container map. input is the original parsed text
   (for token ID extraction via insta/span). session, if given, is an
   existing {:repo :auto-ids} to continue building onto (same :ROOT, id
   counters picking up where they left off) instead of starting fresh.
   A bare top-level Leaf or repeat (see musics.ebnf's TopElement) is
   auto-wrapped in its own one-child Sequence rather than walked
   directly -- see wrap-bare-leaf above."
  [tree & [input session]]
  (let [state            (initial-state input session)
        program-children (rest tree)]
    (loop [st state remaining (vec program-children)]
      (if (seq remaining)
        (let [node (first remaining)]
          (recur (if (needs-wrapper? node)
                   (wrap-bare-leaf st node)
                   (walk-element st node))
                 (rest remaining)))
        (flat/finish st)))))
