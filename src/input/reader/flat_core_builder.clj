(ns input.reader.flat-core-builder
  "Hybrid flat tree builder:
   - Containers live in a top-level :repo map (id -> container).
   - Leaves (notes, rests, ints, etc.) stored inline in :children vectors.
   - Child containers referenced by :id keyword in :children.
   - :stack holds actual container maps (not IDs) during building.
   - No atoms inside nodes -- just plain data.

   Container types:
     Musical containers  -- :SEQ :PAR :DATA :ATOMIC_ALGO :ELEMENT_ALGO :ROOT
     Context definitions -- :CONTEXT  (^{ } in grammar)
     Transient           -- :TIMES :TUPLET :TRANSPOSE :DECORATED
     Context-less        -- :UNIT  (( ) in grammar)

   Context definitions (:CONTEXT) are registered in :repo like regular
   containers but are NOT appended to their parent's :children -- they
   are definition forms, not musical content. The walker resolves a
   Reference (:my-context) by looking up :type in repo and dispatching
   accordingly: :CONTEXT -> apply envelopes, anything else -> insert child.

   A context-less container (:UNIT) registers and links like a regular
   container, but has no :context of its own -- its children (and any
   instruction authored directly inside it) share whatever context is
   already in effect from its enclosing container. See current-context
   below for how that's resolved during building, and
   core.domain.resolve/build-chain for how it's resolved during a real
   traversal or locate (it simply contributes nothing to the ctx-chain).

   push-container/pop-container no longer wire a parent context --
   see earlier version for the full reasoning."
  (:require [clojure.string :as str]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.defaults :as defaults]))

;; ============================================================
;; Constants
;; ============================================================

(def ^:private transient-types
  "Container types inlined on pop: children spliced into parent,
   container itself not registered in :repo."
  #{:TIMES :TUPLET :TRANSPOSE :DECORATED})

(def ^:private definition-types
  "Container types that register in :repo on pop but are NOT appended
   to their parent's :children. They are definition forms, not content.
   Currently only :CONTEXT (^{ } grammar rule)."
  #{:CONTEXT})

(def ^:private context-less-types
  "Container types with no Context of their own -- children (and any
   instruction authored directly inside one) share whatever context is
   already in effect from the enclosing container instead. Currently
   only :UNIT (( ) grammar rule)."
  #{:UNIT})

;; ============================================================
;; State initialization
;; ============================================================

(defn empty-session
  "A pristine session: just the :ROOT container, context built from
   common.defaults/root-defaults, no other content.

   This is the one true root context -- constructed once, here, at
   session-start (or reset), so the rest of the code (resolve/root-seed,
   the engine, musics.clj) can rely on repo always having a real :ROOT
   context instead of separately constructing or being handed one."
  []
  (let [root-ctx (c/context-root (defaults/root-defaults))]
    {:repo     {:ROOT {:type :ROOT :id :ROOT :context root-ctx :children []}}
     :auto-ids {}
     :var-map  {}}))

(defn initial-state
  "Create a builder state. With no session (or nil), starts from a fresh
   empty-session. With an existing session ({:repo :auto-ids :var-map}
   already containing :ROOT), continues it instead: the stack is seeded
   with the session's own :ROOT (so new top-level elements append to the
   same root), :auto-ids continues from the session's counts (so ids
   never collide with what's already in the repo), and :var-map
   continues too (so a variable defined in an earlier parse call is
   still referenceable in a later one, matching the old text-level
   var-registry's persistence)."
  ([input] (initial-state input nil))
  ([input session]
   (let [session (or session (empty-session))
         root    (get-in session [:repo :ROOT])]
     {:repo       (:repo session)
      :stack      [root]
      :auto-ids   (atom (:auto-ids session))
      :var-map    (atom (or (:var-map session) {}))
      :last-pitch (atom nil)
      :last-dur   (atom 1/4)
      :in-slur?   (atom false)
      :input      input})))

;; ============================================================
;; ID generation
;; ============================================================

(def ^:private id-prefixes
  "Short lowercase prefix per container type for auto-generated ids
   (:SEQ -> :s1, :PAR -> :p1, :CONTEXT -> :c1, etc.) instead of the
   verbose :SEQ.1/:CONTEXT.1 style."
  {:SEQ          "s"
   :PAR          "p"
   :UNIT         "u"
   :CONTEXT      "c"
   :DATA         "d"
   :ATOMIC_ALGO  "a"
   :ELEMENT_ALGO "e"})

(defn next-auto-id
  "Generate a unique container ID like :s1, :c1, etc."
  [state type]
  (let [auto-ids @(:auto-ids state)
        n (get auto-ids type 0)
        prefix (get id-prefixes type (str/lower-case (name type)))]
    (swap! (:auto-ids state) assoc type (inc n))
    (keyword (str prefix (inc n)))))

;; ============================================================
;; Core stack operations
;; ============================================================

(defn current-context
  "Return the context an instruction authored right now should mutate.
   Usually just the container on top of the stack -- but a context-less
   container (:UNIT) has no :context of its own, so instructions written
   directly inside one target the nearest enclosing container that does
   have one (skipping any nested Units along the way)."
  [state]
  (some :context (rseq (:stack state))))

(defn current-context-chain
  "The full nearest-first ancestor [context relative-offset] stack,
   right now -- same context-less (:UNIT) frame skipping current-context
   uses, just keeping every match instead of stopping at the first one,
   each paired with how far into THAT ancestor's own local timeline this
   exact point in the walk has reached (d/duration of that container as
   constructed so far -- the same quantity duration/ctx-append already
   use as their own time coordinate, just computed at every stack level
   instead of only the innermost).
   This is what a leaf's own baked :ctx-chain field is snapshotted from
   at walk time (see flat-tree-walker's walk-note et al) -- unlike a
   container's own :context, which stays purely path-built/dynamic (see
   core.domain.context's own docstring on why), a leaf is never
   independently re-referenced by id the way a container can be, so
   baking its whole ancestry in once, here, is safe and lets it resolve
   correctly even once extracted from its container entirely (sq/times/
   cycle/etc.).
   The relative-offset is what makes this actually correct rather than
   just convenient: a container's envelope is authored locally-authored/
   zero-based, and needs re-basing by ITS OWN entry point at play time
   (core.domain.context/ctx-shift) -- but once a leaf is extracted from
   its container, there's no 'entry point' left to read off a live
   traversal, only the leaf's own current structural-time. Naively
   shifting every ancestor's context by that SAME current time (as a
   first attempt at this did) is wrong -- it makes a ramp spanning
   several extracted leaves re-flatten to its start value on every one
   of them instead of interpolating, since 'shift to now' erases their
   relative spacing entirely. Shifting each ancestor by
   (current-structural-time - its-own-relative-offset) instead
   reconstructs exactly the entry point that ancestor's own container
   would have had, so leaves that were originally contiguous stay
   correctly spaced relative to each other even after extraction --
   verified against the existing ramp-rebasing test suite, not just
   reasoned through."
  [state]
  (into [] (keep (fn [container]
                    (when-let [ctx (:context container)]
                      [ctx (d/duration (:repo state) container)])))
        (rseq (:stack state))))

(defn replay-context!
  "Copy every envelope point from src-ctx onto target-ctx, each point re-
   appended at (+ t point's own original time), keeping its original
   interpolation type. Used both for a :CONTEXT reference (flat-tree-
   walker/apply-context-ref, replaying a ^{ } definition's envelope at
   the point it's referenced) and for a transient command's own context
   (pop-container, below, replaying \\times/\\tuplet/\\transpose/a grace
   decoration's instructions onto its parent when the wrapper container
   itself is spliced away) -- same mechanism, same reason: an instruction
   written against src-ctx must still take effect once src-ctx itself is
   discarded, not vanish along with it."
  [target-ctx src-ctx t]
  (doseq [[k env] @(:envelopes-atom src-ctx)]
    (doseq [pt @(:points-atom env)]
      (c/ctx-append target-ctx (keyword k) (+ t (:time pt)) (:value pt) (:ip pt)))))

(defn push-container
  "Create a new container and push it onto the stack.
   Not yet registered in :repo -- that happens on pop. :id starts nil --
   an explicit Id (name:), if the source gives one, overwrites it via
   walk-bareword before pop; ensure-id (below) only spends an auto-id
   counter slot at pop time, and only if no explicit name ever arrived --
   so {verse: ...} never wastes a :s-prefixed slot it will never use, and
   an inlined transient (:TIMES/:TUPLET/...), which is spliced away and
   never registered under any id at all, never spends one either.
   Context holds only locally-authored envelope data (no parent wiring).
   A context-less type (:UNIT) gets no :context at all -- see
   context-less-types and current-context above."
  [state type]
  (let [context-less (boolean (context-less-types type))
        is-trans     (boolean (transient-types type))
        container    (cond-> {:type type :id nil :children []}
                       (not context-less) (assoc :context (c/context))
                       is-trans           (assoc :transient true))]
    (update state :stack conj container)))

(defn ensure-id
  "container's own :id if the source already named it (Id, walked via
   walk-bareword); otherwise mint one now, at the last possible moment --
   see push-container for why this has to be lazy rather than assigned
   up front. Public: walk-repeat/walk-tremolo (flat-tree-walker) need
   this too -- they peek a nested source container straight off the
   stack for an Iterator's :source without ever calling pop-container
   (deliberately: it must not register under its own top-level id or
   link into its parent's :children), but it still needs a real id of
   its own for print-structure/inspection to show, so they can't skip
   this step just because they skip the rest of pop-container."
  [state container]
  (if (:id container)
    container
    (assoc container :id (next-auto-id state (:type container)))))

(defn append-child
  "Append a child to the current parent container on the stack.
   Child can be a leaf map or a container keyword ID."
  [state child]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children] conj child)))

(defn pop-container
  "Pop the current container from the stack.

   Dispatch on container type:
   - Transient      splice children directly into parent (inline, not
                    registered), and replay the transient container's own
                    context onto the parent (see replay-context!) -- an
                    instruction written inside \\times/\\tuplet/\\transpose/
                    a grace decoration must still take effect once the
                    wrapper itself is discarded, same as its children do.
   - Definition     register in :repo, do NOT append id to parent's children.
                    Currently: :CONTEXT -- a definition form, not musical content.
   - Regular        register in :repo, append id to parent's children.
   - Root           register in :repo, clear stack."
  [state]
  (let [container  (peek (:stack state))
        rest-stack (pop (:stack state))
        parent     (peek rest-stack)]
    (cond
      ;; ---- Transient: splice children into parent, replay context ----
      (:transient container)
      (let [child-ids  (:children container)
            target-ctx (current-context (assoc state :stack rest-stack))]
        (when (and target-ctx (:context container))
          (replay-context! target-ctx (:context container)
                            (d/duration (:repo state) parent)))
        (-> state
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children] into child-ids)))

      ;; ---- Definition: register in repo, no child link to parent ----
      ;; :CONTEXT is a definition form -- it lives in repo so References
      ;; can look it up, but it is not inserted into the parent's musical
      ;; content. The walker's walk-reference distinguishes context vs
      ;; container by checking (:type (get repo id)).
      (definition-types (:type container))
      (let [container (ensure-id state container)
            id        (:id container)]
        (-> state
            (assoc :stack rest-stack)
            (assoc-in [:repo id] container)))

      ;; ---- Regular container: register and link ----
      ;; set-container-duration stamps the container's final duration
      ;; (onto its Context, or as a bare :duration key for a context-less
      ;; :UNIT) at pop time, when all children are known.
      ;;
      ;; Only link id onto the parent's :children if it isn't already
      ;; there. A parent's :children can already contain id at this
      ;; point -- not just within one parse (the same explicit name
      ;; declared twice at the same nesting level in the same text), but
      ;; especially for :ROOT: initial-state seeds the builder's stack
      ;; with the *session's own* :ROOT, carrying its existing :children
      ;; forward, so re-parsing (or re-committing) a top-level {verse:
      ;; ...} a second time re-registers :verse's content (the versioned
      ;; :repo entry above already handles that correctly) but must NOT
      ;; append a second :verse onto :ROOT's own children -- confirmed
      ;; directly: without this check, repeatedly (play-file! "some.mus")
      ;; on an unchanged file left root-children with N copies of the
      ;; same id, and since play-file!'s own filtering doesn't dedupe
      ;; either, that id's content played N times over, back to back.
      parent
      (let [container (ensure-id state container)
            dur       (d/duration (:repo state) container)
            container (d/set-container-duration container dur)
            id        (:id container)
            state'    (assoc-in state [:repo id] container)]
        (-> state'
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children]
                       (fn [children]
                         (if (some #{id} children) children (conj children id))))))

      ;; ---- Root: register, clear stack ----
      :else
      (let [container (ensure-id state container)
            id        (:id container)]
        (-> state
            (assoc :stack [])
            (assoc-in [:repo id] container))))))

;; ============================================================
;; Batch mutations for transient commands
;; ============================================================

(defn scale-durations!
  "Multiply all durations of children of the current container by factor."
  [state factor]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children]
               (fn [children]
                 (mapv (fn [child]
                         (if (:duration child)
                           (update child :duration * factor)
                           child))
                       children)))))

(defn transpose-pitches!
  "Add interval to all pitches of children of the current container.

   respell-fn, if given, is called with (child new-pitches) -- the
   child as it was *before* the shift (so its original :id text is
   still there to consult) and its post-shift :pitches vector -- and
   should return a new display :id, or nil to leave :id unchanged.
   Used by flat-tree-walker/walk-transpose so a transposed note's
   printed name reflects its new pitch, the same way LilyPond's own
   \\transpose respells notes. Deliberately not done for \\times/
   \\tuplet's scale-durations! above -- LilyPond leaves a tuplet's
   notated duration exactly as written (the bracket alone communicates
   the real-time scaling), so there's no equivalent respelling to do
   there."
  ([state interval] (transpose-pitches! state interval nil))
  ([state interval respell-fn]
   (let [idx (dec (count (:stack state)))]
     (update-in state [:stack idx :children]
                (fn [children]
                  (mapv (fn [child]
                          (if (:pitches child)
                            (let [new-pitches (mapv #(+ interval %) (:pitches child))
                                  new-id       (when respell-fn (respell-fn child new-pitches))]
                              (cond-> (assoc child :pitches new-pitches)
                                new-id (assoc :id new-id)))
                            child))
                        children))))))

(defn decorate-children!
  "Apply a decorating function to every child of the current container."
  [state decorate-fn]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children]
               (fn [children]
                 (mapv decorate-fn children)))))

(defn set-children!
  "Replace the children vector of the current container on the stack."
  [state new-children]
  (let [idx (dec (count (:stack state)))]
    (assoc-in state [:stack idx :children] new-children)))

(defn decorate-last-child!
  "Apply a decorating function to only the LAST child of the current
   container, leaving earlier children untouched."
  [state decorate-fn]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children]
               (fn [children]
                 (if (seq children)
                   (conj (vec (butlast children)) (decorate-fn (last children)))
                   children)))))

;; ============================================================
;; Finalisation
;; ============================================================

(defn finish
  "Pop all remaining containers until only root remains, then register it.
   Returns {:tree map :root-id keyword :auto-ids map :var-map map} where
   :tree is the id->container map, :auto-ids is the final id-counter
   snapshot, and :var-map is the final {name -> {:children :context}}
   snapshot (both to be threaded into the next session's initial-state,
   so ids keep counting up and variables stay defined instead of
   resetting)."
  [state]
  (let [final-state (loop [s state]
                      (if (> (count (:stack s)) 1)
                        (recur (pop-container s))
                        s))
        registered  (pop-container final-state)]
    {:tree     (:repo registered)
     :root-id  (:id (get-in registered [:repo :ROOT]))
     :auto-ids @(:auto-ids registered)
     :var-map  @(:var-map registered)}))