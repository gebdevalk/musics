(ns core.domain.flat-domain
  "Domain model for musical parts.
   - Leaf, Rest, Drum, Bar are plain, :type-tagged maps (see fold-node
     below) -- no print-method of their own, so pr-str/EDN-round-trip
     their real data directly, same as containers already did.
   - Iterator remains an immutable record.
   - Containers are plain maps with :type, :id, :context, :children (vector).
   - No atoms inside nodes — all data is plain."
  (:require [core.domain.context :as c]
            [common.music-elements :as el]))

;; ============================================================
;; Leaf types
;;
;; Plain :type-tagged maps, not records -- deliberately: a record needs a
;; print-method override to avoid dumping raw field data at the REPL/into
;; pr-str, and that override is exactly what made core.domain.persist's
;; freeze/thaw necessary for these four types in the first place. A plain
;; map has no such override to begin with, so pr-str already emits real,
;; readable data -- see the fold-node/persist docstrings for the other
;; half of this (Context still holds atoms, so it's still not directly
;; EDN-readable; that's an orthogonal, still-real obstacle these four
;; types never had).
;; ============================================================

(defn leaf
  "Create a Leaf (pitched note or chord).
   duration should be a Ratio (Clojure fraction).
   pitches is a vector of ints (MIDI note numbers)."
  ([id context duration pitches]
   {:type :LEAF :id id :context context :duration duration
    :pitches (vec pitches) :articulation nil :dynamic nil
    :modifiers [] :tied false})
  ([id context duration pitches articulation dynamic modifiers tied]
   {:type :LEAF :id id :context context :duration duration
    :pitches (vec pitches) :articulation articulation :dynamic dynamic
    :modifiers (vec modifiers) :tied (boolean tied)}))

(defn rest*
  "Create a Rest (silent duration)."
  ([id context duration] {:type :REST :id id :context context :duration duration}))

(defn drum
  "Create a Drum (unpitched percussive event)."
  ([id context duration program]
   {:type :DRUM :id id :context context :duration duration :program program}))

(defn bar
  "Create a Bar (unpitched zero duration signal event)."
  ([count] {:type :BAR :count count :duration 0}))

;; ============================================================
;; Iterator (deferred-expansion wrapper)
;; ============================================================

(defrecord Iterator [type id context source params])

(defn iterator
  "Create an Iterator — a lazy traversal wrapper around a source Composite.
   type   — :REPEAT, :TREMOLO, etc.
   source — the walked Composite (sequence content)
   params — e.g. {:count 4, :repeat-type :volta, :alternative Composite}"
  [type id context source params]
  (->Iterator type id context source (or params {})))

(defn iterator? [x] (instance? Iterator x))

(defmethod print-method Iterator [^Iterator it ^java.io.Writer w]
  (.write w "#<Iterator ")
  (.write w (name (:type it)))
  (.write w " ")
  (.write w (pr-str (:id it)))
  (when-let [c (:count (:params it))]
    (.write w (str " ×" c)))
  (.write w ">"))

;; ============================================================
;; Predicates
;; ============================================================

(defn leaf? [x] (= :LEAF (:type x)))
(defn rest? [x] (= :REST (:type x)))
(defn drum? [x] (= :DRUM (:type x)))
(defn bar?  [x] (= :BAR  (:type x)))

(defn container? [x] (and (map? x) (contains? x :children)))

(defn part?
  "True if x is any musical part (leaf, rest, drum, container, or iterator)."
  [x]
  (or (leaf? x) (rest? x) (drum? x) (container? x) (iterator? x)))

;; ============================================================
;; Container helpers
;; ============================================================

(defn children
  "Return children of a container, resolving keyword IDs via repo."
  [repo x]
  (mapv (fn [child]
          (if (keyword? child)
            (get repo child)
            child))
        (:children x)))

;(defmethod print-method Composite [^Composite c ^java.io.Writer w]
;  (let [inner (str/join " " (map #(str (type %)) (composite-children c)))]
;    (case (:type c)
;      :SEQ (str "[ " inner " ]")
;      :PAR (str "<< " inner " >>")
;      :ALGO (str "'[ " inner " ]'")
;      :QLIST (str "'( " inner " )")
;      :LIST (str "( " inner " )")
;      :SCORE (str "Score[" (:id c) " " inner " ]")
;      (str "( " inner " )"))))

;; ============================================================
;; Transform / mutate (immutable helpers)
;; ============================================================

(defn mutate
  "Return a new version of part with updated fields.
   Like Python's dataclasses.replace."
  [part & kvs]
  (apply assoc part kvs))

(defn transform
  "Apply fns left-to-right to part."
  [part & fns]
  (reduce (fn [p f] (f p)) part fns))

(defn transpose
  "Return a fn that transposes pitches by semitones."
  [semitones]
  (fn [part]
    (if (:pitches part)
      (update part :pitches #(mapv (partial + semitones) %))
      part)))

(defn invert
  "Return a fn that inverts pitches around an axis pitch (mirror:
   new = 2*axis - old) -- the classical inversion transform, transpose's
   usual partner. With no axis given, each part is inverted around the
   rounded mean of its own pitches instead -- a chord folds around its
   own center; a single-pitch leaf is unchanged (its only pitch is its
   own mean)."
  ([]
   (fn [part]
     (if (seq (:pitches part))
       (let [mean (Math/round (double (/ (reduce + (:pitches part)) (count (:pitches part)))))]
         ((invert mean) part))
       part)))
  ([axis]
   (fn [part]
     (if (:pitches part)
       (update part :pitches #(mapv (partial - (* 2 axis)) %))
       part))))

;; ============================================================
;; Scale-degree helpers -- public: shared by tonal-invert/tonal-
;; transpose/tonal-harmonize/snap-to-scale below, and directly useful on
;; their own for algorithmic composition working in degree-space (a
;; generator reasons in plain degree-index integers -- ordinary +/map/
;; iterate, no leaf/model involvement at all -- and only touches these
;; two at the boundary: pitch->degree-index to start from existing
;; material, degree-index->pitch/degree-leaf to materialize real leaves
;; once it's done). Degree is deliberately never *stored* on a leaf --
;; it's only meaningful relative to a key, and a leaf doesn't own one
;; (the context above it does, and that can change), so a stored degree
;; would either silently go stale under a key change or need the key
;; stored redundantly alongside it. Computing it fresh from :pitches on
;; demand, same principle ctx-value already uses for context, avoids
;; that entirely -- there's nothing here expensive enough to be worth
;; caching the way part-duration is.
;; ============================================================

(defn scale-pitch-classes
  "ks's scale (a common.music-elements Key) as a sorted, deduplicated
   0..11 pitch-class vector. key-pitches walks scale steps cumulatively
   from the tonic's own pitch-class WITHOUT wrapping at 12 (e.g. G major
   -> [7 9 11 12 14 16 18], not [7 9 11 0 2 4 6]) -- correct for reading
   off absolute intervals above the tonic, but not directly comparable
   to an arbitrary pitch's own (mod 12) pitch-class without normalizing
   it the same way first, which is what this does."
  [ks]
  (vec (sort (distinct (map #(mod % 12) (el/key-pitches ks))))))

(defn pitch->degree-index
  "Absolute MIDI pitch -> scale-degree index against scale-pcs (from
   scale-pitch-classes) -- spans octaves, so consecutive scale degrees
   are consecutive integers. A pitch not on the scale snaps up to the
   nearest scale tone first, wrapping to the next octave's lowest degree
   if it's above every scale tone in its own octave."
  [scale-pcs pitch]
  (let [n      (count scale-pcs)
        pc     (mod pitch 12)
        octave (quot pitch 12)
        idx    (.indexOf scale-pcs (int pc))]
    (if (neg? idx)
      (let [nearest (some #(when (>= % pc) %) scale-pcs)]
        (if nearest
          (+ (* octave n) (.indexOf scale-pcs nearest))
          (+ (* (inc octave) n) 0)))
      (+ (* octave n) idx))))

(defn degree-index->pitch
  "The inverse of pitch->degree-index: a scale-degree index -> absolute
   MIDI pitch, against scale-pcs (from scale-pitch-classes)."
  [scale-pcs index]
  (+ (* (Math/floorDiv (int index) (count scale-pcs)) 12)
     (nth scale-pcs (Math/floorMod (int index) (count scale-pcs)))))

(defn degree-leaf
  "Create a Leaf from scale-degree index/indices instead of absolute
   pitches -- ks is a common.music-elements Key, degree-or-degrees a
   single scale-degree index (a note) or a vector of them (a chord), in
   the same octave-spanning space pitch->degree-index/degree-index->
   pitch use. Converts immediately via degree-index->pitch and returns
   an ordinary Leaf, :pitches only -- ks and the degrees themselves are
   never retained on it, same shape as any other leaf (see the section
   comment above for why)."
  ([id context ks duration degree-or-degrees]
   (degree-leaf id context ks duration degree-or-degrees nil nil [] false))
  ([id context ks duration degree-or-degrees articulation dynamic modifiers tied]
   (let [scale-pcs (scale-pitch-classes ks)
         degrees   (if (sequential? degree-or-degrees) degree-or-degrees [degree-or-degrees])]
     (leaf id context duration (mapv (partial degree-index->pitch scale-pcs) degrees)
           articulation dynamic modifiers tied))))

(defn tonal-invert
  "Return a fn that inverts pitches around axis in SCALE STEPS rather
   than semitones: each pitch's degree-distance from axis within ks's
   scale (a common.music-elements Key) is reflected to the other side
   and mapped back onto the scale. Unlike invert, the semitone span of
   an inverted interval can differ from the original -- it follows
   whatever interval pattern ks's scale actually has at that point
   (e.g. a major third above the axis can reflect to a minor third
   below it, if the scale isn't symmetric there -- this is the real,
   textbook behavior of tonal inversion, not an approximation of it).
   A pitch not already on the scale snaps up to the nearest scale tone
   first (see pitch->degree-index). A no-op if ks's scale is empty."
  [ks axis]
  (let [scale-pcs (scale-pitch-classes ks)]
    (if (zero? (count scale-pcs))
      (fn [part] part)
      (let [axis-idx (pitch->degree-index scale-pcs axis)]
        (fn [part]
          (if (:pitches part)
            (update part :pitches
                    #(mapv (fn [p]
                             (degree-index->pitch
                               scale-pcs (- (* 2 axis-idx) (pitch->degree-index scale-pcs p))))
                           %))
            part))))))

(defn tonal-transpose
  "Return a fn that transposes pitches by steps SCALE DEGREES rather
   than semitones -- diatonic transposition, tonal-invert's partner. The
   actual semitone span depends on where in ks's scale (a common.music-
   elements Key) each pitch sits (e.g. transposing up a third in C
   major: C->E is 4 semitones, D->F is 3) -- this is the real behavior
   of a classical melodic \"sequence\" (a motif repeated at successive
   scale degrees, e.g. (iterate (tonal-transpose ks 1) motif)), which
   plain transpose (semitone-based) only approximates. Same out-of-
   scale/empty-scale handling as tonal-invert."
  [ks steps]
  (let [scale-pcs (scale-pitch-classes ks)]
    (if (zero? (count scale-pcs))
      (fn [part] part)
      (fn [part]
        (if (:pitches part)
          (update part :pitches
                  #(mapv (fn [p]
                           (degree-index->pitch
                             scale-pcs (+ steps (pitch->degree-index scale-pcs p))))
                         %))
          part)))))

(defn snap-to-scale
  "Return a fn that quantizes each pitch onto ks's scale (a common.music-
   elements Key) -- a pitch already on the scale is unchanged, one that
   isn't snaps up to the nearest scale tone (pitch->degree-index's own
   policy). Useful after a chromatic transform (plain invert/transpose)
   to pull the result back onto the key. A no-op if ks's scale is empty."
  [ks]
  (let [scale-pcs (scale-pitch-classes ks)]
    (if (zero? (count scale-pcs))
      (fn [part] part)
      (fn [part]
        (if (:pitches part)
          (update part :pitches
                  #(mapv (fn [p] (degree-index->pitch scale-pcs (pitch->degree-index scale-pcs p))) %))
          part)))))

(defn tonal-harmonize
  "Return a fn that adds a scale-relative harmony pitch (steps scale
   degrees above -- or below, for negative steps) to each of a part's
   own pitches, turning a single note into a dyad (or thickening an
   existing chord by one more voice per pitch). The original pitches
   are kept, not replaced -- contrast tonal-transpose, which moves them
   instead of adding to them. Result pitches come back sorted low to
   high. A no-op if ks's scale is empty."
  [ks steps]
  (let [scale-pcs (scale-pitch-classes ks)]
    (if (zero? (count scale-pcs))
      (fn [part] part)
      (fn [part]
        (if (:pitches part)
          (update part :pitches
                  (fn [ps]
                    (vec (sort (mapcat (fn [p]
                                          [p (degree-index->pitch
                                               scale-pcs (+ steps (pitch->degree-index scale-pcs p)))])
                                        ps)))))
          part)))))

(defn times
  "Return a fn that multiplies the duration."
  [factor]
  (fn [part]
    (if (:duration part)
      (update part :duration #(* % factor))
      part)))

(defn to-tuplet
  "Scale duration by factor — e.g. 2/3 for triplet."
  [factor]
  (fn [part]
    (if (:duration part)
      (update part :duration #(/ % factor))
      part)))

(defn to-triplet
  "Shorthand for (to-tuplet 3/2) — 3 notes in the time of 2."
  []
  (to-tuplet 3/2))

(defn dotted
  "Return a fn that dots the duration."
  []
  (fn [part]
    (if (:duration part)
      (update part :duration #(* % 3/2))
      part)))

(defn dynamic
  "Return a fn that shifts a leaf's own :dynamic offset (added on top of
   the sampled context volume at resolve time -- see resolve-leaf in
   core.domain.resolve) by delta. No-op for anything without a :dynamic
   field (Rest/Drum/containers)."
  [delta]
  (fn [part]
    (if (contains? part :dynamic)
      (update part :dynamic (fnil + 0) delta)
      part)))

;; ============================================================
;; Duration calculation (recursive, computed on demand)
;; ============================================================

(defn duration
  "Return the total duration of any part (leaf or container).

   If a repo is provided, it resolves keyword IDs to containers.
   If no repo is given, it assumes the part is fully self-contained (or a leaf).

   (duration leaf)                     ; => 1/4
   (duration repo :s1)                 ; => duration of container with that ID
   (duration repo container-map)       ; => sum of all children
   (duration repo child-id-keyword)    ; => resolves recursively"
  ([part]
   (duration nil part))
  ([repo part]
   (cond
     ;; --- Leaf / Rest / Drum ---
     (or (leaf? part) (rest? part) (drum? part))
     (:duration part 0)

     ;; --- Container: dispatch on type ---
     (container? part)
     (case (:type part)
       ;; Parallel — all children start at same time, total = max
       :PAR (reduce (fn [acc child] (max acc (duration repo child)))
                    0
                    (children repo part))
       ;; Default: sequential — sum children durations (SEQ, LIST, ALGO, DATA, QUOTE, etc.)
       (reduce (fn [acc child] (+ acc (duration repo child)))
               0
               (children repo part)))

     ;; --- Keyword ID (look it up in repo, then recurse) ---
     (keyword? part)
     (if repo
       (recur repo (get repo part))
       0)

     ;; --- Any other map with a :duration field ---
     (map? part)
     (:duration part 0)

     ;; --- Fallback ---
     :else 0)))

(defn set-container-duration
  "Stamp a container's final duration, whether it has its own Context to
   cache it on or not. Regular containers (:SEQ/:PAR/etc.) hold it on
   :context, read back via Context's own :duration; a context-less
   container (:UNIT -- see context.clj/flat-core-builder) has no Context
   to stash it on, so it's kept as a bare top-level :duration key instead.
   part-duration reads either shape."
  [container dur]
  (if (:context container)
    (update container :context c/set-duration dur)
    (assoc container :duration dur)))

(defn part-duration
  "Get the duration of a part -- O(1), no repo traversal.
   Containers/Iterators: reads pre-computed :duration from Context,
                         set at pop-container time. A context-less
                         container (:UNIT) has no Context, so falls back
                         to a bare top-level :duration key instead (see
                         set-container-duration above).
   Leaves/Rest/Drum:     reads :duration field directly.
   Returns 0 if not yet set."
  [part]
  (cond
    (container? part) (or (get-in part [:context :duration]) (:duration part) 0)
    (iterator?  part) (or (get-in part [:context :duration]) 0)
    :else             (or (:duration part) 0)))

;; ============================================================
;; Pitch statistics (recursive, computed once at pop-container time)
;;
;; A simple, unweighted mean pitch per voice -- each chord tone counted
;; as its own data point, not duration-weighted -- used by
;; core.async-engine to order simultaneous voices (a :PAR's children, or
;; a play-arg #{...}/(par ...) group) low-to-high before assigning each one a
;; fixed wall-index. {:sum :n} rather than a bare mean is what's baked,
;; so a parent container can (in principle) recombine several children's
;; stats correctly -- a mean of means is wrong unless weighted by count,
;; sum+count composes with plain addition.
;; ============================================================

(defn pitch-stats
  "Recursively sum pitches and count pitched events under part --
   {:sum s :n n}. Mirrors duration's own dispatch shape/style (always
   fully recomputes on container descent rather than trusting a child's
   own already-baked stats, same tradeoff duration itself already makes
   -- called once per pop-container, not a hot path).
   Only a Leaf's own :pitches contribute (a chord's tones each counted
   individually). Rest/Bar contribute nothing (no pitch at all); Drum is
   excluded too -- its :program is a GM percussion note number, not a
   musical pitch, so averaging it in would be meaningless. An Iterator
   contributes its :source's own stats (its :alternative, if any, is
   ignored -- a rare volta-ending case, not worth the extra recursion
   here)."
  ([part] (pitch-stats nil part))
  ([repo part]
   (cond
     (leaf? part)
     {:sum (reduce + (:pitches part)) :n (count (:pitches part))}

     (or (rest? part) (drum? part) (bar? part))
     {:sum 0 :n 0}

     (iterator? part)
     (pitch-stats repo (:source part))

     (container? part)
     (reduce (fn [acc child] (merge-with + acc (pitch-stats repo child)))
             {:sum 0 :n 0}
             (children repo part))

     (keyword? part)
     (if repo (recur repo (get repo part)) {:sum 0 :n 0})

     :else {:sum 0 :n 0})))

(defn set-container-pitch-stats
  "Bake stats ({:sum :n}, see pitch-stats) onto container as plain
   top-level keys (:pitch-sum/:pitch-n) -- computed once, at pop-
   container time (input.reader.flat-core-builder/pop-container,
   alongside set-container-duration), so reading it back later
   (part-pitch-stats/mean-pitch, at :PAR-fork time) is an O(1) field
   read, never a repo-walking recomputation."
  [container stats]
  (assoc container :pitch-sum (:sum stats) :pitch-n (:n stats)))

(defn part-pitch-stats
  "O(1) pitch stats for part -- {:sum :n}. A container reads its own
   baked :pitch-sum/:pitch-n (set-container-pitch-stats); an Iterator
   defers to its :source's baked stats; a Leaf is computed directly
   (cheap -- just its own :pitches); anything else (Rest/Drum/Bar, or a
   keyword not yet resolved) contributes {:sum 0 :n 0}."
  [part]
  (cond
    (container? part) {:sum (get part :pitch-sum 0) :n (get part :pitch-n 0)}
    (iterator? part)  (part-pitch-stats (:source part))
    (leaf? part)      {:sum (reduce + (:pitches part)) :n (count (:pitches part))}
    :else             {:sum 0 :n 0}))

(defn mean-pitch
  "Simple, unweighted mean pitch of part (see part-pitch-stats) -- nil if
   it has no pitched content at all (all rests/drums, or empty)."
  [part]
  (let [{:keys [sum n]} (part-pitch-stats part)]
    (when (pos? n) (/ sum n))))

(defn scale-duration
  "Recursively multiply the duration of a part by factor.
   part may be a Leaf/Rest/Drum, a container map, or a keyword id into repo.
   Returns [repo' part'] -- repo' has any nested repo-registered containers
   rescaled in place (by id); part' is the value to store at the call site
   (the same keyword if part was a keyword, otherwise the scaled value)."
  [repo part factor]
  (cond
    (or (leaf? part) (rest? part) (drum? part))
    [repo (update part :duration * factor)]

    (keyword? part)
    (let [[repo' scaled] (scale-duration repo (get repo part) factor)]
      [(assoc repo' part scaled) part])

    (container? part)
    (let [[repo' new-children]
          (reduce (fn [[r acc] child]
                    (let [[r' child'] (scale-duration r child factor)]
                      [r' (conj acc child')]))
                  [repo []]
                  (:children part))]
      [repo' (-> part
                 (assoc :children new-children)
                 (set-container-duration (* factor (part-duration part))))])

    :else [repo part]))

;; ============================================================
;; fold-node -- generic catamorphism over a domain node
;; ============================================================

(defn- node-kind
  "Classify an already-resolved node for fold-node's own dispatch --
   nil for anything that's neither a recognized part nor a container/
   iterator (e.g. a Data container's plain Int/Float/String/etc values).

   Also recognizes a not-yet-thawed frozen Iterator, tagged
   :record-type :iterator by core.domain.persist's freeze -- a plain map
   just read back from EDN can never satisfy (instance? Iterator x), so
   a fold walking frozen/serialized data (thaw) needs this second way to
   still dispatch it to the :iterator handler. Never set on anything
   live, so this never affects a fold over the real domain tree (describe,
   or freeze itself)."
  [x]
  (cond
    (container? x)                         :container
    (or (iterator? x)
        (= :iterator (:record-type x)))    :iterator
    (leaf? x)      :leaf
    (rest? x)      :rest
    (drum? x)      :drum
    (bar? x)       :bar
    :else          nil))

(defn fold-node
  "Generic catamorphism (fold) over a domain node -- a functional
   Visitor: fold-node itself owns structure (recursing into a
   container's :children / an iterator's :source+:alternative,
   resolving and classifying each child); handlers is the algebra, one
   function per node-kind, deciding what that node's own data becomes in
   whatever shape this particular fold produces. Missing keys default to
   returning the node unchanged (:missing defaults to (constantly nil)).

     :container (fn [node folded-children] ...) -- folded-children is a
                 vector of {:kind :child :result}, in :children order,
                 kind one of :container :iterator :leaf :rest :drum :bar
                 :ref :missing. :ref is a keyword child resolve-ref chose
                 not to resolve (see below) -- :result is that keyword,
                 unchanged.
     :iterator  (fn [node {:keys [source alternative]}] ...) -- source/
                 alternative are already folded the same way (alternative
                 nil if the iterator has none).
     :leaf :rest :drum :bar
                 (fn [node] ...) -- terminal, nothing to recurse into.
     :missing   (fn [raw-child] ...) -- raw-child is the original keyword
                 a genuine resolution attempt (see resolve-ref) came back
                 nil for.

   resolve-ref (default identity) resolves a keyword child. A caller
   walking an already-resolved, self-contained subtree (freeze/thaw)
   leaves it as identity, so a keyword child is never touched and comes
   back as :kind :ref -- distinguished from :missing by resolve-ref
   returning the same keyword back unchanged (declined) vs nil (a real
   lookup that failed). A caller walking a live repo (describe) supplies
   real lookup, so :ref never arises there -- every keyword either
   resolves or is genuinely :missing.

   Returns nil for a nil node (an unresolvable root -- the one case a
   caller resolves before ever calling fold-node)."
  [node handlers & {:keys [resolve-ref] :or {resolve-ref identity}}]
  (letfn [(run [n k]
            (case k
              :container (let [folded (mapv fold-child (:children n))]
                           ((:container handlers) n folded))
              :iterator  (let [alt (get-in n [:params :alternative])]
                           ((:iterator handlers) n
                            {:source      (:result (fold-child (:source n)))
                             :alternative (when alt (:result (fold-child alt)))}))
              ((get handlers k (fn [node] node)) n)))
          (fold-child [raw]
            (if (keyword? raw)
              (let [resolved (resolve-ref raw)]
                (cond
                  (= raw resolved) {:kind :ref :child raw :result raw}
                  (nil? resolved)  {:kind :missing :child raw
                                    :result ((get handlers :missing (constantly nil)) raw)}
                  :else            (let [k (node-kind resolved)]
                                     {:kind k :child resolved :result (run resolved k)})))
              (let [k (node-kind raw)]
                {:kind k :child raw :result (run raw k)})))]
    (when (some? node)
      (run node (node-kind node)))))

;; ============================================================
;; Structure report
;; ============================================================

(def ^:private describe-handlers
  "fold-node algebra for `describe` -- containers/iterators are the only
   structural kinds; everything else (leaf/rest/drum/bar, or a Data
   container's plain scalar values, which fold-node classifies as nil)
   is atomic content: counted in :leaf-count, never listed. A dangling
   reference reports as a :MISSING placeholder instead of silently
   vanishing or crashing the caller."
  {:container (fn [node folded]
                (let [structural? (comp #{:container :iterator :missing} :kind)
                      structural  (filterv structural? folded)]
                  {:type       (:type node)
                   :id         (:id node)
                   :duration   (part-duration node)
                   :leaf-count (- (count folded) (count structural))
                   :children   (mapv :result structural)}))
   :iterator  (fn [node {:keys [source alternative]}]
                (cond-> {:type        :ITER
                         :id          (:id node)
                         :iter-type   (:type node)
                         :count       (:count (:params node))
                         :repeat-type (:repeat-type (:params node))
                         :duration    (part-duration node)
                         :source      source}
                  alternative (assoc :alternative alternative)))
   :missing   (fn [raw-child] {:type :MISSING :id raw-child})})

(defn describe
  "Abbreviated structural report from root-id: containers and iterators
   only -- leaves/rests/drums are counted, never listed individually.
   Returns a nested map (not a printed string), so callers can pr-str,
   pretty-print, or diff it:

     {:type :SEQ :id :verse :duration 1/2 :leaf-count 4 :children [...]}
     {:type :ITER :id :R.1 :iter-type :REPEAT :repeat-type :unfold
      :count 4 :source {...}}
     {:type :ITER :id :R.2 :iter-type :REPEAT :repeat-type :volta
      :count 2 :source {...} :alternative {...}}
     {:type :MISSING :id :verse}  ;; a reference not yet resolvable in repo"
  [repo root-id]
  (fold-node (get repo root-id) describe-handlers
             :resolve-ref (fn [child] (get repo child))))

(def ^:private brackets
  "Same bracket scheme as the surface grammar (musics.ebnf) -- see that
   grammar's own header comment. Types with no surface bracket of their
   own fall back to a generic ( ) -- the transient command-wrapper types
   (:TIMES/:TUPLET/:TRANSPOSE/:DECORATED, spliced into their parent at
   pop-container time), none of which are ever actually reachable here
   in practice (print-structure only ever walks registered repo
   containers, and none of these are ever registered). Unit and
   AtomicAlgo/ElementAlgo no longer exist as grammar constructs at all
   (see musics.ebnf's own header comment on what was dropped and why),
   so :UNIT/:ATOMIC_ALGO/:ELEMENT_ALGO have no entries here any more --
   nothing ever builds a container of those types for this to look up.
   :PAR's own entry isn't a genuine open/close bracket pair the way the
   others are -- '(par' is the whole reserved-word-inclusive opening
   token (musics.ebnf's own Parallel rule, a Lisp prefix call like
   (repeat ...) below, not a bracket -- see that grammar's own header
   comment on why) -- but line's own (str open \" \" (:id node) ...)
   construction below doesn't care, it just concatenates whatever
   string is here, so '(par' as a literal, multi-character 'open'
   value renders correctly with zero other changes needed, same as
   iter-header's own '(repeat ...' does for :ITER."
  {:SEQ     ["[" "]"]
   :PAR     ["(par" ")"]
   :DATA    ["'[" "]"]
   :CONTEXT ["{" "}"]
   :ROOT    ["[" "]"]})

(defn- bracket-for [type]
  (get brackets type ["(" ")"]))

(defn- iter-header
  "Render an :ITER node's header using the same surface syntax the grammar
   accepts for it -- (repeat unfold/volta N ...) or (repeat tremolo N ...)
   -- so the report reads like something you could paste back in, not an
   internal type name."
  [node]
  (case (:iter-type node)
    :REPEAT  (str "(repeat " (name (:repeat-type node)) " " (:count node))
    :TREMOLO (str "(repeat tremolo " (:count node))
    (str (name (:iter-type node)) (when (:count node) (str " ×" (:count node))))))

(defn print-structure
  "Pretty-print (describe repo root-id) as an indented tree using the same
   brackets as the surface grammar, e.g.:

     [ :song  dur 3/2
       [ :verse  dur 1/2  (4 leaves) ]
       (repeat unfold 2  dur 1/2
         [ :chorus  dur 1/4  (2 leaves) ]
       )
     ]

   A (repeat volta ...) with an (alternative ...) ending is rendered with
   the alternative as a sibling block after the main source, same as it's
   written in text. A dangling/forward reference (an id not yet resolvable
   in repo) is rendered as \"?? :id (unresolved)\" rather than crashing --
   including root-id itself not resolving to anything."
  [repo root-id]
  (letfn [(line [node depth]
            (let [pad (apply str (repeat (* 2 depth) " "))]
              (cond
                (nil? node)
                (str pad "?? " root-id "  (unresolved)\n")

                (= :MISSING (:type node))
                (str pad "?? " (:id node) "  (unresolved)\n")

                (= :ITER (:type node))
                (str pad (iter-header node)
                     "  dur " (:duration node) "\n"
                     (line (:source node) (inc depth))
                     (when-let [alt (:alternative node)]
                       (str pad "(alternative\n"
                            (line alt (inc depth))
                            pad ")\n"))
                     pad ")\n")

                :else
                (let [[open close] (bracket-for (:type node))
                      header       (str open " " (:id node)
                                         "  dur " (:duration node)
                                         (when (pos? (:leaf-count node))
                                           (str "  (" (:leaf-count node) " leaves)")))]
                  (if (seq (:children node))
                    (str pad header "\n"
                         (apply str (map #(line % (inc depth)) (:children node)))
                         pad close "\n")
                    (str pad header " " close "\n"))))))]
    (print (line (describe repo root-id) 0))))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Leaf / transforms ---
  (def n (leaf "c4" (c/context) 1/4 [60]))
  ((times 2) n)                                             ;; => duration 1/2
  ((to-tuplet 3/2) n)                                       ;; => duration 1/6 (triplet)
  ((dotted) n)                                              ;; => duration 3/8
  (transform n (transpose 7) (times 2))                     ;; => pitches [67], duration 1/2

  ;; --- Container (plain map) ---
  (def c {:type :SEQ :id :s1 :context (c/context) :children []})
  (container? c)                                            ;; => true
  (children nil c)                                          ;; => [] (no children to resolve)
  )