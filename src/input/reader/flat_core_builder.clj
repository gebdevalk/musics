(ns input.reader.flat-core-builder
  "Hybrid flat tree builder:
   - Containers live in a top-level :repo map (id -> container).
   - Leaves (notes, rests, ints, etc.) stored inline in :children vectors.
   - Child containers referenced by :id keyword in :children.
   - :stack holds actual container maps (not IDs) during building.
   - No atoms inside nodes — just plain data."
  (:require [core.domain.context :as c]
            [common.data.defaults :as defaults]))

;; ============================================================
;; Constants
;; ============================================================

(def ^:private transient-types
  "Container types that are inlined on pop: their children are spliced
   directly into the parent, and the container itself is not registered."
  #{:LIST :TIMES :TUPLET :TRANSPOSE :DECORATED})

;; ============================================================
;; State initialization
;; ============================================================

(defn initial-state
  "Create a fresh builder state with an empty root container on the stack."
  [input]
  (let [root-ctx (c/context-root (defaults/root-defaults))
        root {:type :ROOT :id :ROOT :context root-ctx :children []}]
    {:repo       {}                                         ; id -> container (only non-transient containers)
     :stack      [root]                                     ; holds actual container maps
     :auto-ids   (atom {})                                  ; counters for generating container IDs
     :last-pitch (atom nil)
     :last-dur   (atom 1/4)
     :input      input}))

;; ============================================================
;; ID generation
;; ============================================================

(defn- next-auto-id
  "Generate a unique container ID like :SEQ.1, :PAR.2, etc."
  [state type]
  (let [auto-ids @(:auto-ids state)
        n (get auto-ids type 0)]
    (swap! (:auto-ids state) assoc type (inc n))
    (keyword (str (name type) "." (inc n)))))

;; ============================================================
;; Core stack operations
;; ============================================================

(defn current-context
  "Return the context of the container currently on top of the stack."
  [state]
  (:context (peek (:stack state))))

(defn accumulated-time
  "Sum of durations of all children already appended to the current container.
   Used for context envelope timestamps."
  [state]
  (let [container (peek (:stack state))
        children (:children container)]
    (reduce (fn [acc child]
              (+ acc (or (:duration child) 0)))
            0
            children)))

(defn push-container
  "Create a new container, push it onto the stack.
   The container is NOT yet registered in :repo — that happens on pop."
  [state type]
  (let [parent-ctx (current-context state)
        ctx (c/context parent-ctx)
        id (next-auto-id state type)
        is-trans (boolean (transient-types type))
        container {:type type :id id :context ctx :children []}
        container (if is-trans (assoc container :transient true) container)]
    (update state :stack conj container)))

(defn append-child
  "Append a child to the current parent container on the stack.
   Child can be:
     - A leaf map (note, rest, int, float, etc.)
     - A container ID string (already registered in :repo)"
  [state child]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children] conj child)))

(defn pop-container
  "Pop the current container from the stack.
   - If transient: splice its children directly into the parent (inline).
   - If regular: register it in :repo, then append its ID to the parent.
   - If root: just register it in :repo and clear the stack."
  [state]
  (let [container (peek (:stack state))
        rest-stack (pop (:stack state))
        parent (peek rest-stack)]
    (cond
      ;; ---- Transient: splice children into parent ----
      (:transient container)
      (let [child-ids (:children container)]
        (-> state
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children] into child-ids)))

      ;; ---- Regular container: register and link ----
      parent
      (let [id (:id container)
            state' (assoc-in state [:repo id] container)]
        (-> state'
            (assoc :stack rest-stack)
            (update-in [:stack (dec (count rest-stack)) :children] conj id)))

      ;; ---- Root: just register, no parent to link to ----
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
                           (update child :pitches (fn [pitches]
                                                    (mapv #(+ interval %) pitches)))
                           child))
                       children)))))

(defn decorate-children!
  "Apply a decorating function to every child of the current container."
  [state decorate-fn]
  (let [idx (dec (count (:stack state)))]
    (update-in state [:stack idx :children]
               (fn [children]
                 (mapv decorate-fn children)))))

;; ============================================================
;; Finalisation
;; ============================================================

(defn finish
  "Pop all remaining containers until only the root remains, then register it.
   Returns {:tree map, :root-id keyword} where :tree is the id->container map."
  [state]
  (let [final-state (loop [s state]
                      (if (> (count (:stack s)) 1)
                        (recur (pop-container s))
                        s))]
    ;; Pop the root one last time to register it
    (let [registered (pop-container final-state)]
      {:tree    (:repo registered)
       :root-id (:id (get-in registered [:repo :ROOT]))})))