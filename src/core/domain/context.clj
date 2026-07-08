;; context.clj
;; Clojure port of the musics domain model.
;;
;; Types: Point, Envelope, Context
;;
;; CHANGE FROM PREVIOUS VERSION:
;;   Context no longer stores :parent. A Context only ever holds its own
;;   locally-authored envelope data. "What's the enclosing context" is a
;;   visit-dependent question (a container can be referenced from multiple
;;   places once IDs are reused), so it can no longer be baked into the
;;   data itself -- it must be supplied by whoever is traversing (see
;;   resolve.clj / pre-resolve), as an explicit chain argument.
;;
;; Usage:
;;   (require '[core.domain.context :as c])

(ns core.domain.context
  (:refer-clojure :exclude [get]))

;; ============================================================
;; IP: Interpolation types (ported from envelope.py IP enum)
;; ============================================================

(def ip-easing
  "Map of ip keyword -> easing function.
   Each easing fn takes t in [0,1] and returns eased weight in [0,1].
   FIXED and STEP are nil -- handled specially in sampling."
  {:fixed       nil
   :step        nil
   :lin-up      (fn [t] t)
   :lin-down    (fn [t] t)
   :smooth      (fn [t] (* t t (- 3 (* 2 t))))
   :ease-in     (fn [t] (* t t))
   :ease-out    (fn [t] (- 1 (* (- 1 t) (- 1 t))))
   :ease-in-out (fn [t]
                  (if (< t 0.5)
                    (* 2.0 t t)
                    (- 1 (* 0.5 (- (* -2.0 t) 2.0) (- (* -2.0 t) 2.0)))))})

(defn easing
  "Look up the easing function for an IP keyword."
  [ip]
  (ip-easing ip))

(def ^:private ip-reverse-map
  "Swap directional IPs for time reversal: up<->down, in<->out."
  {:fixed       :fixed
   :step        :step
   :lin-up      :lin-down
   :lin-down    :lin-up
   :smooth      :smooth
   :ease-in     :ease-out
   :ease-out    :ease-in
   :ease-in-out :ease-in-out})

(defn- ip-reverse [ip] (ip-reverse-map ip ip))

;; ============================================================
;; Point (ported from envelope.py Point NamedTuple)
;; ============================================================

(defrecord Point [time value ip])

(defn point
  "Create a Point. ip defaults to :fixed."
  ([time value] (->Point time value :fixed))
  ([time value ip] (->Point time value ip)))

;; ============================================================
;; Envelope (ported from envelope.py Envelope)
;; ============================================================

;; Points stored in an atom -- mutation is thread-safe via compare-and-swap.
;; No explicit lock needed.

(defrecord Envelope [points-atom])

(defn envelope
  "Create an empty Envelope."
  []
  (->Envelope (atom [])))

(defn envelope-from
  "Create an Envelope from a seq of point maps [{:time :value :ip} ...]."
  [point-maps]
  (->Envelope (atom (mapv (fn [{:keys [time value ip]}]
                            (->Point time value (or ip :fixed)))
                          point-maps))))

(defn env-duration
  "Duration of the envelope = time of the last point, or 0."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (seq pts)
      (:time (last pts))
      0.0)))

(defn env-empty?
  "True if the envelope has no points."
  [^Envelope env]
  (empty? @(:points-atom env)))

(defn env-append
  "Append a point to the envelope. Mutates in place (swap! on atom).
   If time matches the last point, replace it.
   Returns env for chaining."
  [^Envelope env time value ip]
  (swap! (:points-atom env)
         (fn [pts]
           (if (and (seq pts) (= (:time (last pts)) time))
             (conj (vec (butlast pts)) (->Point time value ip))
             (conj pts (->Point time value ip)))))
  env)

(defn env-get
  "Sample the envelope at the given time.
   Returns the interpolated value, or nil if empty."
  [^Envelope env time]
  (let [pts @(:points-atom env)]
    (cond
      (empty? pts) nil
      (<= time (:time (first pts))) (:value (first pts))
      (>= time (:time (last pts))) (:value (last pts))
      :else
      (let [times (mapv :time pts)
            ;; Manual bisect -- find index of segment start
            idx (loop [lo 0 hi (dec (count times))]
                  (if (>= lo hi)
                    (min lo (dec (count times)))
                    (let [mid (quot (+ lo hi 1) 2)]
                      (if (<= (nth times mid) time)
                        (recur mid hi)
                        (recur lo (dec mid))))))
            prev (nth pts idx)
            nxt (nth pts (inc idx))
            ip (:ip prev)]
        (if (or (= ip :fixed) (= ip :step))
          (:value prev)
          (let [t (/ (- time (:time prev))
                     (- (:time nxt) (:time prev)))
                ease (easing ip)]
            (if (and (number? (:value prev)) (number? (:value nxt)))
              (+ (* (- 1 (ease t)) (:value prev))
                 (* (ease t) (:value nxt)))
              (:value prev))))))))

(defn env-reverse
  "Return a new Envelope with points reversed in time.
   Directional IPs (lin-up/lin-down, ease-in/ease-out) are swapped."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (empty? pts)
      (envelope)
      (let [d (env-duration env)
            rev (vec (reverse pts))]
        (envelope-from
          (map-indexed
            (fn [i p]
              {:time  (- d (:time p))
               :value (:value p)
               :ip    (if (zero? i)
                        (:ip p)
                        (ip-reverse (:ip (rev (dec i)))))})
            rev))))))

;; ============================================================
;; Context (ported from context.py Context NamedTuple)
;;
;; A Context holds:
;;   envelopes-atom -- locally-authored time-variant envelopes
;;                     (tempo, volume, panning). Mutable via atom.
;;   duration       -- the total duration of the owning container.
;;                     Set at pop-container time once all children are
;;                     known. nil until then.
;;                     Immutable once set (plain value, not an atom).
;;
;; No :parent field -- enclosing scope is supplied at lookup time as
;; an explicit chain (see ctx-value-chain), because the same container
;; can be reached via different enclosing contexts when IDs are reused
;; (DAG-shaped repo).
;;
;; Having :duration directly on the Context lets form-unroll quickly
;; sum offsets by walking the chain without going back to the repo:
;;   offset = sum of (:duration ctx) for all enclosing contexts above.
;; ============================================================

(defrecord Context [envelopes-atom duration])

(defn context
  "Create a Context with empty envelopes and no duration yet.
   Duration is set later via set-duration when the owning container
   is popped from the builder stack and its full duration is known."
  []
  (->Context (atom {}) nil))

(defn set-duration
  "Return a new Context with duration set to dur.
   Called at pop-container time:
     (update container :context set-duration (d/duration repo container))
   Since duration is a plain value (not an atom), this returns a new
   Context record -- the container map must be re-stored in :repo after."
  [ctx dur]
  (assoc ctx :duration dur))

(defn context-root
  "Create a root Context from a map of key -> value.
   Each value becomes a single FIXED point at time 0.
   Root context has no owning container, so :duration is nil.
   'Root-ness' is determined by how it's used in a traversal
   (passed as the last element of a chain), not by anything
   stored on the Context itself."
  [data]
  (let [ctx (context)]
    (doseq [[k v] data]
      (let [env (envelope)]
        (env-append env 0.0 v :fixed)
        (swap! (:envelopes-atom ctx) assoc (name k) env)))
    ctx))

;; --- Hierarchical key lookup, via an explicit chain ---
;;
;; chain = vector/seq of Contexts, NEAREST FIRST. The chain is built and
;; threaded by the traversal in resolve.clj's pre-resolve step, not
;; reconstructed here. This function only knows how to search a chain
;; it's handed; it has no notion of "go to my parent."

(defn ctx-value-chain
  "Sample the value for key at time, searching the chain nearest-first.
   A context's envelope for key only applies if it has at least one
   point at-or-before the query time (an 'active' point). Otherwise
   the search continues to the next context in the chain -- the
   instruction at that level hasn't taken effect yet.

   chain should normally end with the root Context, whose envelopes
   (built via context-root) always have a point at time 0, guaranteeing
   the search terminates with a value for any key root defines."
  [chain key time]
  (let [k (name key)]
    (some (fn [ctx]
            (when-let [env (clojure.core/get @(:envelopes-atom ctx) k)]
              (let [pts @(:points-atom env)]
                (when (some #(<= (:time %) time) pts)
                  (env-get env time)))))
          chain)))

(defn ctx-append
  "Add a point to the envelope for key in this context.
   If key exists locally: append to it.
   If key exists in this context's own envelopes: append.
   Otherwise: create a new local envelope.
   Never touches any other context -- a Context only ever mutates itself."
  [ctx key time value ip]
  (let [k (name key)]
    (if-let [env (clojure.core/get @(:envelopes-atom ctx) k)]
      (env-append env time value ip)
      (let [env (envelope)]
        (env-append env time value ip)
        (swap! (:envelopes-atom ctx) assoc k env)))
    ctx))

(comment
  ;; --- Envelope ---
  (def env (envelope))
  (env-append env 0.0 0.5 :fixed)
  (env-append env 2.0 1.0 :lin-up)
  (env-append env 4.0 2.0 :smooth)
  (env-duration env)                                        ;; => 4.0
  (env-get env 1.0)                                         ;; => 0.5 (fixed)
  (env-get env 3.0)                                         ;; => 1.5 (lin-up)

  ;; --- Context (chain-based lookup, active-point validity) ---
  ;; A point in a nearer-context envelope is only valid at time t when
  ;; that envelope contains at least one point at-or-before t. Without
  ;; this rule, the mere presence of a nearer envelope would hide a
  ;; still-valid value further up the chain -- e.g. a tempo instruction
  ;; at beat 2 would retroactively override an enclosing tempo at beat 0.
  (def root-ctx (context-root {"tempo" 120 "volume" 0.8 "timbre" 42}))
  (ctx-value-chain [root-ctx] :tempo 0.0)                   ;; => 120

  (def child-ctx (context))
  (ctx-append child-ctx :tempo 2.0 80 :lin-up)
  ;; chain is nearest-first: child before root
  (ctx-value-chain [child-ctx root-ctx] :tempo 0.0)         ;; => 120 (no point <= 0 in child -> root)
  (ctx-value-chain [child-ctx root-ctx] :tempo 3.0)         ;; => 80  (point at t=2 valid -> local)
  )