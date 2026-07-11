(ns input.reader.flat-core-builder
  "Hybrid flat tree builder:
   - Containers live in a top-level :repo map (id -> container).
   - Leaves (notes, rests, ints, etc.) stored inline in :children vectors.
   - Child containers referenced by :id keyword in :children.
   - :stack holds actual container maps (not IDs) during building.
   - No atoms inside nodes -- just plain data.

   Container types:
     Musical containers  -- :SEQ :PAR :DATA :ATOMIC_ALGO :ELEMENT_ALGO :ROOT
     Context definitions -- :CONTEXT  (^[ ] in grammar)
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
   core.domain.resolve/build-chain for how it's resolved during
   form-unroll/locate (it simply contributes nothing to the ctx-chain).

   push-container/pop-container no longer wire a parent context --
   see earlier version for the full reasoning."
  (:require [clojure.string :as str]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.data.defaults :as defaults]))

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
   Currently only :CONTEXT (^[ ] grammar rule)."
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
   common.data.defaults/root-defaults, no other content.

   This is the one true root context -- constructed once, here, at
   session-start (or reset), so the rest of the code (resolve/root-seed,
   the engine, musics.clj) can rely on repo always having a real :ROOT
   context instead of separately constructing or being handed one."
  []
  (let [root-ctx (c/context-root (defaults/root-defaults))]
    {:repo     {:ROOT {:type :ROOT :id :ROOT :context root-ctx :children []}}
     :auto-ids {}}))

(defn initial-state
  "Create a builder state. With no session (or nil), starts from a fresh
   empty-session. With an existing session ({:repo :auto-ids} already
   containing :ROOT), continues it instead: the stack is seeded with the
   session's own :ROOT (so new top-level elements append to the same
   root) and :auto-ids continues from the session's counts (so ids never
   collide with what's already in the repo)."
  ([input] (initial-state input nil))
  ([input session]
   (let [session (or session (empty-session))
         root    (get-in session [:repo :ROOT])]
     {:repo       (:repo session)
      :stack      [root]
      :auto-ids   (atom (:auto-ids session))
      :last-pitch (atom nil)
      :last-dur   (atom 1/4)
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

(defn push-container
  "Create a new container and push it onto the stack.
   Not yet registered in :repo -- that happens on pop.
   Context holds only locally-authored envelope data (no parent wiring).
   A context-less type (:UNIT) gets no :context at all -- see
   context-less-types and current-context above."
  [state type]
  (let [context-less (boolean (context-less-types type))
        id           (next-auto-id state type)
        is-trans     (boolean (transient-types type))
        container    (cond-> {:type type :id id :children []}
                       (not context-less) (assoc :context (c/context))
                       is-trans           (assoc :transient true))]
    (update state :stack conj container)))

(defn append-child
  "Append a child to the current parent container on the stack.
   Child can be a leaf map or a container keyword ID."
  [state child]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children] conj child)))

(defn pop-container
  "Pop the current container from the stack.

   Dispatch on container type:
   - Transient      splice children directly into parent (inline, not registered).
   - Definition     register in :repo, do NOT append id to parent's children.
                    Currently: :CONTEXT -- a definition form, not musical content.
   - Regular        register in :repo, append id to parent's children.
   - Root           register in :repo, clear stack."
  [state]
  (let [container  (peek (:stack state))
        rest-stack (pop (:stack state))
        parent     (peek rest-stack)]
    (cond
      ;; ---- Transient: splice children into parent ----
      (:transient container)
      (let [child-ids (:children container)]
        (-> state
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children] into child-ids)))

      ;; ---- Definition: register in repo, no child link to parent ----
      ;; :CONTEXT is a definition form -- it lives in repo so References
      ;; can look it up, but it is not inserted into the parent's musical
      ;; content. The walker's walk-reference distinguishes context vs
      ;; container by checking (:type (get repo id)).
      (definition-types (:type container))
      (let [id (:id container)]
        (-> state
            (assoc :stack rest-stack)
            (assoc-in [:repo id] container)))

      ;; ---- Regular container: register and link ----
      ;; set-container-duration stamps the container's final duration
      ;; (onto its Context, or as a bare :duration key for a context-less
      ;; :UNIT) at pop time, when all children are known.
      parent
      (let [dur       (d/duration (:repo state) container)
            container (d/set-container-duration container dur)
            id        (:id container)
            state'    (assoc-in state [:repo id] container)]
        (-> state'
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children] conj id)))

      ;; ---- Root: register, clear stack ----
      :else
      (let [id (:id container)]
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
  "Add interval to all pitches of children of the current container."
  [state interval]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children]
               (fn [children]
                 (mapv (fn [child]
                         (if (:pitches child)
                           (update child :pitches
                                   (fn [pitches] (mapv #(+ interval %) pitches)))
                           child))
                       children)))))

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
   Returns {:tree map :root-id keyword :auto-ids map} where :tree is the
   id->container map and :auto-ids is the final id-counter snapshot (to be
   threaded into the next session's initial-state, so ids keep counting up
   instead of restarting)."
  [state]
  (let [final-state (loop [s state]
                      (if (> (count (:stack s)) 1)
                        (recur (pop-container s))
                        s))
        registered  (pop-container final-state)]
    {:tree     (:repo registered)
     :root-id  (:id (get-in registered [:repo :ROOT]))
     :auto-ids @(:auto-ids registered)}))