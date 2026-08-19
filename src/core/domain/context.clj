;; context.clj
;; Clojure port of the musics domain model.
;;
;; Types: Envelope, Context (a "point" is just a [time [value ip]] entry
;; in an Envelope's own sorted-map, not a distinct record)
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

(ns core.domain.context)

;; ============================================================
;; IP: Interpolation types (ported from envelope.py IP enum)
;; ============================================================

(def ip-easing
  "Map of ip keyword -> easing function.
   Each easing fn takes t in [0,1] and returns eased weight in [0,1].
   FIXED, STEP and INVALID are nil -- handled specially in sampling."
  {:fixed       nil
   :step        nil
   :invalid     nil
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
   :invalid     :invalid
   :lin-up      :lin-down
   :lin-down    :lin-up
   :smooth      :smooth
   :ease-in     :ease-out
   :ease-out    :ease-in
   :ease-in-out :ease-in-out})

(defn- ip-reverse [ip] (ip-reverse-map ip ip))

;; ============================================================
;; Envelope (ported from envelope.py Envelope)
;; ============================================================

;; Points stored in an atom, sorted-map keyed by :time -> [value ip] --
;; mutation is thread-safe via compare-and-swap. No explicit lock needed,
;; no separate Point record either: time IS the key (so "same instant
;; replaces" is a plain assoc, not a hand-rolled comparison -- see
;; env-append), and [value ip] is the only per-point payload left once
;; it isn't. sorted-map orders by `compare`, not `=` -- (compare 0 0.0)
;; is 0, so a Ratio/int-typed time and a double-typed time for the same
;; instant collapse to one entry exactly the way this envelope's own
;; time values need them to (context-root seeds 0, everything else sums
;; Ratios/ints via core.domain.flat-domain/duration -- see the note
;; env-append used to carry here about == vs =, now handled by the map
;; itself rather than hand-documented and hand-implemented).
(defrecord Envelope [points-atom])

(defn envelope
  "Create an empty Envelope."
  []
  (->Envelope (atom (sorted-map))))

(defn envelope-from
  "Create an Envelope from a seq of point maps [{:time :value :ip} ...]."
  [point-maps]
  (->Envelope (atom (into (sorted-map)
                           (map (fn [{:keys [time value ip]}] [time [value (or ip :fixed)]]))
                           point-maps))))

(defn env-duration
  "Duration of the envelope = time of the last point, or 0."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (seq pts)
      (key (last pts))
      0.0)))

(defn env-empty?
  "True if the envelope has no points."
  [^Envelope env]
  (empty? @(:points-atom env)))

(defn env-append
  "Append a point to the envelope. Mutates in place (swap! on atom).
   If time matches an existing point (by `compare`, the sorted-map's own
   key-equality -- see the Envelope docstring above on why that already
   means what == used to mean here), assoc replaces it directly; there's
   no separate 'same instant' case to hand-roll anymore.
   Returns env for chaining."
  [^Envelope env time value ip]
  (swap! (:points-atom env) assoc time [value ip])
  env)

(defn env-get
  "Sample the envelope at the given time.
   Returns the interpolated value, or nil if empty."
  [^Envelope env time]
  (let [pts @(:points-atom env)]
    (cond
      (empty? pts) nil
      (<= time (key (first pts))) (first (val (first pts)))
      (>= time (key (last pts))) (first (val (last pts)))
      :else
      (let [[pt [pv ip]] (first (rsubseq pts <= time))
            [nt [nv _]]  (first (subseq pts > time))]
        (if (or (= ip :fixed) (= ip :step))
          pv
          (let [t (/ (- time pt) (- nt pt))
                ease (easing ip)]
            (if (and (number? pv) (number? nv))
              (+ (* (- 1 (ease t)) pv) (* (ease t) nv))
              pv)))))))

(defn env-reverse
  "Return a new Envelope with points reversed in time.
   Directional IPs (lin-up/lin-down, ease-in/ease-out) are swapped."
  [^Envelope env]
  (let [pts @(:points-atom env)]
    (if (empty? pts)
      (envelope)
      (let [d   (env-duration env)
            rev (vec (reverse pts))]
        (->Envelope
          (atom
            (into (sorted-map)
                  (map-indexed
                    (fn [i [t [v ip]]]
                      [(- d t)
                       [v (if (zero? i) ip (ip-reverse (second (val (rev (dec i))))))]])
                    rev))))))))

(defn env-shift
  "Return a new Envelope with every point's time increased by offset."
  [^Envelope env offset]
  (->Envelope
    (atom (into (sorted-map)
                (map (fn [[t [v ip]]] [(+ offset t) [v ip]]))
                @(:points-atom env)))))

;; ============================================================
;; Context (ported from context.py Context NamedTuple)
;;
;; A plain map, not a record: {:envelopes-atom atom :duration val}.
;; Nothing anywhere dispatches on a Context's own type (no `instance?`,
;; no protocol extended onto it -- unlike Envelope, which genuinely
;; needs to be `instance?`-checkable to tell a real, ramping envelope
;; apart from a bare ValueSource value; see that type's own comment),
;; so the record was ceremony nothing was reading, same reasoning that
;; already dropped Point/Leaf/Rest/Drum in favor of plain maps.
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
;; Having :duration directly on the Context lets a traversal quickly
;; sum offsets by walking the chain without going back to the repo:
;;   offset = sum of (:duration ctx) for all enclosing contexts above.
;;   (see core.domain.resolve/chain-offset)
;; ============================================================

(defn context
  "Create a Context with empty envelopes and no duration yet.
   Duration is set later via set-duration when the owning container
   is popped from the builder stack and its full duration is known."
  []
  {:envelopes-atom (atom {}) :duration nil})

(defn set-duration
  "Return a new Context with duration set to dur.
   Called at pop-container time:
     (update container :context set-duration (d/duration repo container))
   Since duration is a plain value (not an atom), this returns a new
   Context record -- the container map must be re-stored in :repo after."
  [ctx dur]
  (assoc ctx :duration dur))

;; ============================================================
;; ValueSource -- what a context's own envelopes-atom can hold per key
;; ============================================================
;;
;; Every OTHER context's own values are real Envelopes (atom + vector of
;; Points), because they're built incrementally as the walker processes
;; instructions one at a time, and any of them could turn out to be a
;; genuine ramp with more points appended later. :ROOT is different: it's
;; grammar-guaranteed to be write-once, built entirely from
;; common.defaults/root-defaults at session-start and never touched again
;; (TopElement excludes both Instruction and every transient Command --
;; see musics.ebnf's own comment on that rule -- so nothing can ever
;; write a second point into it). Every one of ROOT's own values is
;; permanently a single :fixed point at time 0, so building the full
;; Envelope/Point/atom machinery for each of them is ceremony with
;; nothing to earn it: this protocol lets context-root store a bare
;; value directly instead, while ctx-value-chain/ctx-shift (the only two
;; functions that read a context's own envelopes-atom generically,
;; without knowing in advance what's stored under each key) keep working
;; unchanged for BOTH shapes via ordinary protocol dispatch.
(defprotocol ValueSource
  (sample-at [this time]
    "The value active at time, or nil if this source has nothing to say
     (no point yet, or its active point was explicitly invalidated).")
  (shift [this offset]
    "A version of this source re-based by offset -- a no-op for
     anything that isn't genuinely time-based."))

(defn- latest-point-at-or-before
  "The most recent [time [value ip]] entry in env at-or-before time, or
   nil if none -- one rsubseq call against the sorted-map instead of a
   linear filter+last."
  [^Envelope env time]
  (first (rsubseq @(:points-atom env) <= time)))

(defn- resolve-in-env-map
  "Resolve key against an ALREADY-DEREFED envelopes map (some context's
   own :envelopes-atom, deref'd once by the caller -- see sample-many's
   own docstring on why that has to happen once per ancestor, not once
   per key) at time. Returns [value] if this context has something
   active to say about key at time, nil if it doesn't (no envelope for
   key at all, no point yet, or its active point was explicitly
   invalidated) -- the caller should keep searching the rest of the
   chain in that case. Wrapped in a vector so 'found, and the value
   happens to be nil' stays distinguishable from 'not found' (values
   are never actually nil in practice, but the contract shouldn't rely
   on that).

   The one 'resolve this key against ONE context' step ctx-value-chain
   and sample-many both need, factored out once here instead of typed
   out twice -- they used to carry two independent copies of the same
   cond tree (nil source / Envelope with an invalid or not-yet-active
   point / Envelope with a real one / bare ValueSource value), one
   wrapped in a single-key loop, one in a per-key reduce. time should
   already be whatever LOCAL time is correct for this one context (see
   sample-many's own offset handling) -- a bare ValueSource ignores it
   entirely (env's Object/nil extend-protocol impls below both take
   time and never look at it), so passing the offset-adjusted local
   time uniformly is always safe, never just 'usually right'."
  [env-map key time]
  (when-let [source (get env-map (name key))]
    (if (instance? Envelope source)
      (let [latest (latest-point-at-or-before source time)]
        (when (and latest (not= (second (val latest)) :invalid))
          [(env-get source time)]))
      (when-let [v (sample-at source time)]
        [v]))))

(extend-protocol ValueSource
  Envelope
  (sample-at [env time]
    (when-let [latest (latest-point-at-or-before env time)]
      (when-not (= (second (val latest)) :invalid)
        (env-get env time))))
  (shift [env offset] (env-shift env offset))

  Object
  (sample-at [v _time] v)
  (shift [v _offset] v)

  nil
  (sample-at [_ _time] nil)
  (shift [_ _offset] nil))

(defn context-root
  "Create a root Context from a map of key -> value -- each value stored
   BARE (see ValueSource above), not wrapped in an Envelope/Point/atom:
   :ROOT is grammar-guaranteed write-once, so there's never a second
   point to accommodate.
   Root context has no owning container, so :duration is nil.
   'Root-ness' is determined by how it's used in a traversal
   (passed as the last element of a chain), not by anything
   stored on the Context itself."
  [data]
  (let [ctx (context)]
    (doseq [[k v] data]
      (swap! (:envelopes-atom ctx) assoc (name k) v))
    ctx))

(defn ctx-shift
  "Return a new Context with every envelope's points shifted by offset --
   never mutates ctx itself. A container's own envelope is always built
   locally-authored, zero-based (see :duration above and
   flat-tree-walker's (duration state)) -- correct for that container in
   isolation, but not directly comparable to another ctx-chain link's own
   points unless first rebased into the same absolute frame. offset is
   that container's own start position for THIS traversal (e.g. the
   engine's structural-time at the moment it's entered) -- necessarily a
   play-time quantity, since the same repo container can be played
   alone, after other material, or forked under a :PAR, so it can't be
   baked in at build time. :duration itself is a span, not a position,
   so it's carried over unchanged. Same non-mutating,
   return-a-new-value shape as env-reverse -- the original (repo-stored,
   possibly reused by another traversal) Context is never touched."
  [ctx offset]
  (if (zero? offset)
    ctx
    {:envelopes-atom (atom (into {} (map (fn [[k v]] [k (shift v offset)])
                                          @(:envelopes-atom ctx))))
     :duration (:duration ctx)}))

;; --- Hierarchical key lookup, via an explicit chain ---
;;
;; chain = vector/seq of Contexts, NEAREST FIRST. The chain is built and
;; threaded by the traversal in resolve.clj's pre-resolve step, not
;; reconstructed here. This function only knows how to search a chain
;; it's handed; it has no notion of "go to my parent."

(defn ctx-value-chain
  "Sample the value for key at time, searching the chain nearest-first.
   A context's own value for key only applies if it's active at time
   (see ValueSource above -- a bare value is simply always active).
   Otherwise the search continues to the next context in the chain --
   either the instruction at this level hasn't taken effect yet (no
   active point at all), or it has been explicitly invalidated (active
   point has ip :invalid, see ctx-invalidate) and this context should be
   treated as if it said nothing about key from that time on.

   A bare, open-ended ramp (!vol</a bare hairpin with no local value of
   its own to start from) used to store an unresolved :ramp-start
   sentinel here, requiring this fn to recurse into the rest of the
   chain, at query time, to find whatever was already ambient before the
   ramp opened -- see git history for that version and why it existed
   (a genuinely bare crescendo has to ramp from whatever's already
   active, not sit flat until its target's own instant and jump).
   That ambient value no longer needs resolving here at all: it's
   resolved ONCE, eagerly, at the moment the ramp is first walked (see
   input.reader.flat-tree-walker's apply-note-dynamics!/walk-assignment,
   and context.clj's own `ambient-value`), and stored as a real point
   directly -- so by the time anything queries this chain, a bare ramp's
   own envelope already holds two ordinary points like any other ramp,
   and this fn never needs to special-case it. This is strictly more
   than a simplification: the old sentinel was re-resolved from scratch
   on every single note within the ramp's span (same ancestors, same
   sentinel time, same answer, every time) -- baking it in once at walk
   time turns that into a one-time cost instead of a per-note one.

   chain should normally end with the root Context, whose own values
   (built via context-root) are always active from time 0, guaranteeing
   the search terminates with a value for any key root defines.

   The actual per-context resolution (Envelope vs. bare ValueSource,
   validity/invalidation check) is resolve-in-env-map's job -- this fn
   is just the chain walk around it, the same walk sample-many does for
   multiple keys at once."
  [chain key time]
  (loop [cs chain]
    (when (seq cs)
      (if-let [[v] (resolve-in-env-map @(:envelopes-atom (first cs)) key time)]
        v
        (recur (rest cs))))))

(defn ambient-value
  "What key is already active from ctx-chain (nearest-first [context
   local-time] pairs -- ancestors only; see
   input.reader.flat-core-builder/current-context-chain, called with its
   own first, innermost pair dropped, since that's whichever context a
   new open-ended-ramp point is about to be appended to, not one of its
   ancestors -- appending it there first and querying at its own exact
   moment would just find the very sentinel this is resolving, before it
   even exists yet in the pre-refactor version, or would trivially find
   itself in this one), each queried at ITS OWN local time -- there's no
   single shared clock to query them all at once the way build-chain's
   own ctx-shift rebases everything into structural-time for live
   playback; at walk time there's no structural-time yet, only however
   far each ancestor's own local timeline has separately progressed by
   this point in the walk.

   Used at INSERTION time (see input.reader.flat-tree-walker's
   apply-note-dynamics!/walk-assignment) to resolve a bare, open-ended
   ramp's own starting value once, immediately, instead of storing an
   unresolved :ramp-start sentinel for ctx-value-chain/sample-many to
   re-derive from scratch on every later note within the ramp's span --
   see ctx-value-chain's own docstring for the full story. Returns nil
   if nothing in ctx-chain has ever said anything about key (only
   possible for an unregistered, custom key with no root default at
   all -- every registered key's chain terminates at :ROOT, which
   always has one); the caller skips inserting a start point in that
   case, same as ctx-value-chain already treats 'nothing found' anywhere
   else in the chain."
  [ctx-chain key]
  (some (fn [[ctx t]] (ctx-value-chain [ctx] key t)) ctx-chain))

(defn- link->ctx+offset
  "A chain-links element is EITHER a [ctx offset] pair (the extracted/
   sq-times-cycle case, a genuinely distinct offset per ancestor) OR a
   bare Context (ordinary, non-extracted playback, where every
   ancestor's own offset is always 0 -- core.domain.resolve's own
   chain-links fn returns ctx-chain completely unwrapped in that case
   specifically so nothing has to allocate a vector of trivial [ctx 0]
   pairs on every single ordinary note just to say so). Normalizes
   either shape to [ctx offset] right here, the one place that needs
   to tell them apart."
  [link]
  (if (vector? link) link [link 0]))

(defn sample-many
  "Sample MULTIPLE keys from chain-links in ONE pass over the chain,
   instead of one ctx-value-chain call per key (each of which
   independently re-derefs and re-walks the whole thing on its own).

   chain-links is a nearest-first seq whose elements are EITHER a bare
   Context (implicitly offset 0 -- ordinary, non-extracted playback,
   where every ancestor is already in the right time frame) OR a
   [ctx offset] pair (offset non-zero, for an ancestor whose own
   points are still in THEIR OWN local, as-authored frame) -- see
   link->ctx+offset, which normalizes either shape. core.domain.resolve's
   own chain-links fn returns ctx-chain completely unwrapped for
   ordinary playback specifically so nothing has to allocate a vector
   of trivial [ctx 0] pairs on every single note just to say so; only
   sq/times/cycle-extracted material, which has a genuinely distinct
   offset per ancestor, builds real pairs (see that ns for the full
   story).

   keys+defaults is {key default-val}; returns {key value}, with every
   key never found by the time chain-links runs out keeping its own
   default-val -- the same per-key fallback contract ctx-value-chain
   already gives, just resolved together.

   Per ancestor: ONE deref of its own envelopes-atom, not one per
   still-pending key -- and each pending key drops out of the pending
   set the moment it's resolved, so an ancestor far from where most
   keys actually live gets checked against fewer of them each step,
   not all of them every time the way N separate ctx-value-chain walks
   would (each re-deref-ing, and re-checking every key against, every
   ancestor on its own).

   NEVER touches or copies an ancestor's own envelope points, at all,
   for any key, found or not -- shifting the QUERY time (time - offset,
   right at the point of touching that ancestor's own data) is
   mathematically identical to what core.domain.resolve's old
   effective-chain used to do (ctx-shift, physically rebuilding every
   Point of every key in an ancestor's whole envelope map, eagerly,
   before any sampling even started).

   No per-key recursion into the rest of the chain anymore either: a
   bare, open-ended ramp used to store an unresolved :ramp-start
   sentinel that had to be re-derived, per key, on every single note
   within the ramp's span, by delegating to a separate recursive
   resolver against the rest of chain-links. That ambient value is now
   resolved ONCE, eagerly, at the moment the ramp is first walked (see
   context.clj's own `ambient-value` and input.reader.flat-tree-walker's
   apply-note-dynamics!/walk-assignment) and stored as a real point
   directly, so every key this fn ever sees is an ordinary
   Envelope/ValueSource lookup -- resolve-in-env-map's own job, the same
   flat per-key resolution ctx-value-chain uses for a single key,
   called once per still-pending key per ancestor rather than typed out
   a second time here."
  [chain-links keys+defaults time]
  (loop [links   (seq chain-links)
         pending keys+defaults
         found   {}]
    (if (or (empty? links) (empty? pending))
      (merge pending found)
      (let [[ctx offset] (link->ctx+offset (first links))
            env-map (deref (:envelopes-atom ctx))
            local-time (- time offset)
            step (reduce
                   (fn [acc [k default]]
                     (if-let [[v] (resolve-in-env-map env-map k local-time)]
                       (update acc :found assoc k v)
                       (update acc :pending assoc k default)))
                   {:pending {} :found found}
                   pending)]
        (recur (rest links) (:pending step) (:found step))))))

(defn ctx-append
  "Add a point to the envelope for key in this context.
   If key exists locally: append to it.
   If key exists in this context's own envelopes: append.
   Otherwise: create a new local envelope.
   Never touches any other context -- a Context only ever mutates itself."
  [ctx key time value ip]
  (let [k (name key)]
    (if-let [env (get @(:envelopes-atom ctx) k)]
      (env-append env time value ip)
      (let [env (envelope)]
        (env-append env time value ip)
        (swap! (:envelopes-atom ctx) assoc k env)))
    ctx))

(defn ctx-invalidate
  "Mark key as no-longer-active in this context from time on -- e.g. a
   slur-end reverting the legato override a slur-start pushed, without
   knowing (or caring) what value should apply instead: ctx-value-chain
   will fall through to the next context in the chain from this time on,
   exactly as if this context had never set key at all. A no-op turning
   into a real envelope if key wasn't set locally yet (there's nothing
   to invalidate, but this keeps the call site simple)."
  [ctx key time]
  (ctx-append ctx key time nil :invalid))

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
  (def root-ctx (context-root {"Tempo" 120 "volume" 0.8 "timbre" 42}))
  (ctx-value-chain [root-ctx] :Tempo 0.0)                   ;; => 120

  (def child-ctx (context))
  (ctx-append child-ctx :tempo 2.0 80 :lin-up)
  ;; chain is nearest-first: child before root
  (ctx-value-chain [child-ctx root-ctx] :tempo 0.0)         ;; => 120 (no point <= 0 in child -> root)
  (ctx-value-chain [child-ctx root-ctx] :tempo 3.0)         ;; => 80  (point at t=2 valid -> local)

  ;; --- ctx-invalidate: bounding a temporary local override ---
  ;; Without invalidation, a local override is permanent -- once set, it
  ;; hides the enclosing chain forever, even long after whatever pushed
  ;; it (e.g. a slur) has ended.
  (def slur-ctx (context))
  (ctx-append slur-ctx :articulation 1.0 1.0 :fixed)        ;; !( at beat 1: force legato
  (ctx-value-chain [slur-ctx root-ctx] :articulation 1.5)   ;; => 1.0 (slur active)
  (ctx-invalidate slur-ctx :articulation 3.0)                ;; !) at beat 3: end the slur
  (ctx-value-chain [slur-ctx root-ctx] :articulation 1.5)   ;; => 1.0 (still inside the slur)
  (ctx-value-chain [slur-ctx root-ctx] :articulation 4.0)   ;; => nil, or root's own value if set
  )