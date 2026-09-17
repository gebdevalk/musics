(ns core.compose
  "The play-arg mini-language's own grammar -- Form-shape decomposition,
   context/pitch resolution -- plus display, its synchronous preview.
   Deliberately engine-free: nothing here touches *engine*, a voice,
   core.async, or MIDI. This is a SHARED TOOLKIT, not a pipeline stage
   -- core.async-engine's play/play-node and this ns's own display each
   walk a Form on their own, live, calling INTO these functions at
   every node/group they visit, not once up front. Neither one ever
   hands the other a pre-computed result to consume; there is no
   intermediate 'compose produces X, engine plays X' moment, matching
   this project's own long-standing 'nothing is materialized between
   parse and play' principle (see CLAUDE.md's pipeline description) --
   a real compose-then-execute pipeline would mean materializing
   something ahead of time, which this project has deliberately never
   done anywhere else either.

   core.async-engine requires this ns (for the Form grammar its own
   play-form* family needs); this ns requires only core.repo/
   core.domain.* -- never core.async-engine -- so the dependency runs
   exactly one way. Moved out of core.async-engine on 2026-09-10:
   before this, the engine's own file mixed real-time execution
   (async, voices, MIDI) with this purely-functional grammar+preview
   layer, which needed none of it -- see doc/decisions.md for the
   fuller reasoning."
  (:require [core.repo :as core-repo]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.context :as c]
            [core.domain.ornaments :as orn]))

(defn live-repo
  "Turn whatever `repo` handle a voice (or display's own caller) holds
   (normally a voice's own :view, see core.async-engine/fresh-view) into
   something get-able. An IDeref holding a plain map (a voice's own
   :view -- a REALIZED snapshot, seeded once from core.repo/play-tx --
   or a standalone (atom repo) in tests/the REPL smoke-test, with no
   core.repo involved either way) is just dereferenced -- a
   (schedule-tx! ...) redirect of that voice replaces its own :view
   atom's value with a freshly-captured snapshot, picked up the moment
   the traversal visits its next not-yet-read node. An IDeref holding an
   integer (a bare tx number, for a caller that still hands one in
   directly) is resolved through core.repo/view instead. Anything else
   (already a core.repo/view, or a plain map handed in directly, not
   behind an IDeref) is returned as-is."
  [repo]
  (if (instance? clojure.lang.IDeref repo)
    (let [v @repo]
      (if (integer? v) (core-repo/view v) v))
    repo))

(defn build-chain
  "Prepend part's own context onto ctx-chain, as a [ctx offset] pair --
   NOT eagerly rebased via core.domain.context/ctx-shift anymore. part's
   own envelope was built locally-authored, zero-based, so it still
   needs rebasing into the same absolute timeline structural-time is
   already in before any of its points mean anything relative to the
   rest of the chain -- but core.domain.context/sample-many's own
   link->ctx+offset already normalizes a [ctx offset] pair exactly the
   same way core.domain.resolve/chain-links does for extracted (sq/
   times/cycle) material, shifting the QUERY time backward by offset
   right at the point of touching THIS ancestor's own points, never the
   points themselves (see sample-many's own docstring). This used to
   call ctx-shift here instead -- eagerly copying and shifting every
   point of every key this container's context happens to hold, on
   EVERY container descent, for ordinary (non-extracted) playback: the
   overwhelming majority of notes fired, unlike the extracted path that
   already got this same optimization. ctx-shift itself is unchanged
   and still exported (still directly useful, still tested) -- this is
   just no longer its own hot-path caller.
   The only other place a live ctx-chain's own elements get read
   directly rather than through sample-many/link->ctx+offset was
   core.domain.ornaments/expand's :key lookup, updated alongside this
   change to go through sample-many too."
  [part ctx-chain structural-time]
  (if-let [own-ctx (:context part)]
    (into [[own-ctx structural-time]] ctx-chain)
    ctx-chain))

(defn mean-pitch-rank
  "part's own mean-pitch (core.domain.flat-domain/mean-pitch, an O(1)
   read off a container's own baked :pitch-sum/:pitch-n -- see that ns's
   docstring on why this is cheap enough to call at every :PAR fork),
   or Double/MAX_VALUE if part is nil or has no pitched content at all
   (all rests/drums, or unmeasurable -- see form-pitch-source) -- pushes
   anything unmeasurable to the END of the sort rather than crashing or
   arbitrarily landing first."
  [part]
  (or (and part (d/mean-pitch part)) Double/MAX_VALUE))

(defn form-pitch-source
  "The real node a play-arg form refers to, for mean-pitch-rank's sake
   only -- a bare keyword resolves against repo (a live-repo'd view);
   anything else (a nested group, already-sq'd raw seq material) has no
   single node to measure, so nil (sorts last, same as silent content
   does). Takes repo directly, not a voice -- reused both by
   core.async-engine/play-form-par (an already-forked voice's own :tx)
   and mint-branches! (top-level #{} minting, before any voice for that
   branch exists yet, see eng's own :repo)."
  [repo form]
  (when (keyword? form)
    (get repo form)))

(defn- resolve-context-ref
  "If item is a keyword resolving (in repo) to a :CONTEXT container,
   return its Context record; else nil."
  [repo item]
  (when (keyword? item)
    (let [resolved (get repo item)]
      (when (= :CONTEXT (:type resolved)) (:context resolved)))))

(defn- split-leading-contexts
  "Split a group's items into [ctx-refs material] -- ctx-refs is the
   leading run of context-ref items (see resolve-context-ref), material
   is everything from the first non-context item on. Order-dependent --
   used for [] groups only; see split-contexts-unordered for #{}."
  [repo items]
  (loop [items items ctxs []]
    (if-let [ctx (and (seq items) (resolve-context-ref repo (first items)))]
      (recur (rest items) (conj ctxs ctx))
      [ctxs items])))

(defn- split-contexts-unordered
  "Like split-leading-contexts, but order-independent: every item
   resolving to a :CONTEXT is pulled out regardless of position, not
   just a leading run. Used for #{} groups, which have no 'leading' at
   all -- a set can't promise an order for a run to even be defined
   against."
  [repo items]
  (reduce (fn [[ctxs material] item]
            (if-let [ctx (resolve-context-ref repo item)]
              [(conj ctxs ctx) material]
              [ctxs (conj material item)]))
          [[] []] items))

;; ============================================================
;; Five small Form-shape helpers, each called from multiple dispatch
;; sites (core.async-engine's play-form/validate-ids!, and this ns's
;; own realize-form, all independently need to answer the same
;; questions about a Form) -- kept small and shared rather than
;; inlined three times over, which is why there are this many of them
;; for what looks like one job at a glance:
;;   tagged-form?     -- IS this exactly [Form :algo Name]?
;;   split-tag        -- (given tagged-form? is true) pull [Form Name] apart
;;   resolve-form-tag -- tagged-form?/split-tag PLUS "else inherit an
;;                        outer #{}'s own tag" -- the one that actually
;;                        decides a #{} branch's own algo
;;   par-form?        -- IS this a #{...}/(par ...) parallel GROUP at all?
;;                        (unrelated to :algo -- don't confuse with
;;                        tagged-form?)
;;   form-tag+items   -- for a form that's NOT tagged-form?, [:par/:seq
;;                        items] -- sq's own metadata, else vector/set
;; ============================================================

(defn tagged-form?
  "true for a play-arg form that's specifically [Form :algo Name] --
   exactly 3 elements, :algo at index 1 -- never for an ordinary 3-item
   [] group. :algo is reserved here the same way it always has been."
  [x]
  (and (vector? x) (= 3 (count x)) (= :algo (nth x 1))))

(defn split-tag
  "[inner-form algo-name] for a tagged-form? x."
  [x]
  [(nth x 0) (nth x 2)])

(defn resolve-form-tag
  "[inner-form algo] for form -- form's OWN tag wins if it has one
   (tagged-form?); otherwise inner-form is form itself, unchanged, and
   algo is whatever outer-algo was inherited from an enclosing #{}'s own
   whole-group tag (nil if there wasn't one). Used wherever a #{}'s
   branches are resolved -- core.async-engine's mint-branches!
   (top-level) and play-form-par (nested) both share this, so a
   branch's own tag always takes precedence over an inherited one,
   consistently either way."
  [form outer-algo]
  (if (tagged-form? form)
    (split-tag form)
    [form outer-algo]))

(defn par-form?
  "A #{...} literal, OR a vector explicitly tagged {:parallel? true} in
   its own metadata -- see the par fn (below), the constructor for that
   second shape. Both mean the same thing to every consumer here (this
   is the ONE place that decides 'is this Form a parallel group', so
   both call through it or through form-tag+items, which already checks
   the same :parallel? metadata first -- see that fn's own docstring).
   The second shape exists because a literal Clojure set can't hold the
   same value twice (#{:s1 :s1} is a reader error, not just unusual),
   which #{} on its own has no way around -- 'the same part against
   itself in parallel' (Reich-style phase music, a canon voice imitating
   itself, or just two untransformed copies) needs a REAL vector, which
   has never had that restriction, tagged the same way sq already tags
   an extracted :PAR container's own children. par is that constructor,
   spelled out by hand instead of only ever arriving via sq."
  [form]
  (or (set? form) (:parallel? (meta form))))

(defn par
  "A parallel group of forms, as a play-arg Form -- (par :melody :bass)
   means exactly what #{:melody :bass} does (see the play-arg mini-
   language comment above core.async-engine/play-form), EXCEPT it also
   accepts the same Form more than once: (par :melody :melody), or
   (par [:melody :algo :phaseShift] [:melody :algo :phaseShift]) for
   two copies running the SAME algorithm -- both illegal to write as a
   literal #{...} (a Clojure set can't hold two = values at all, so
   even two identically-tagged branches collide, not just two bare
   ids), both fine here, since the underlying collection is a plain
   vector -- never restricted on duplicate values -- tagged
   {:parallel? true} in its own metadata, the exact mechanism sq
   already uses to mark an extracted :PAR container's own children
   (see sq's own docstring) -- not a new mechanism invented for this,
   just exposed as a constructor you can call directly instead of only
   ever reaching it by extracting an existing container.
   #{...} keeps working exactly as before for its own common case
   (branches that are naturally already distinct) -- this doesn't
   replace it, it just stops requiring it for the one shape it
   structurally can't express."
  [& forms]
  (with-meta (vec forms) {:parallel? true}))

(defn form-tag+items
  "[tag items] for a play-arg form that isn't itself a tagged-form? (see
   play-form/realize-form/validate-ids!, which check that shape first).
   sq's own :parallel? seq metadata -- how musics.core/sq marks a
   container's :PAR-vs-:SEQ nature once it's been turned into a bare seq
   of children (mapv'd off the container -- there's no data-level place
   left to carry that at that point, only metadata) -- wins first if
   present: sq ALWAYS sets :parallel? explicitly, true or false, for any
   genuine container it was called on, so this branch is really 'trust
   sq's own answer', not a guess, and it's untouched by the vector/set
   split below.
   Otherwise the collection's own literal type IS the tag now -- no more
   :par/:seq leading keyword, no more untagged-vector-defaults-to-:par:
   a set is always :par, a vector is always :seq. Anything else
   sequential but neither (a LazySeq/list -- concretely, whatever
   musics.core/times or map/filter/etc. produce from sq'd material, which
   never preserves sq's own metadata) still defaults to :seq: that shape
   is already-linear repeated/transformed material, not a fresh grouping
   of separate parts, and this is what keeps (play (times 4 (sq
   :verse))) meaning 'four repeats in a row', not 'four copies stacked
   at once' -- confirmed as a real, not hypothetical, break historically.
   contains? (not just a falsy check on :parallel?'s own value) is what
   lets sq's own explicit false survive the metadata branch unchanged --
   an ordinary :SEQ container's own sq'd material must still play
   sequentially, same as it always did, since a missing key and a false
   value need to land on opposite sides of that check."
  [form]
  (let [m (meta form)]
    [(cond
       (contains? m :parallel?) (if (:parallel? m) :par :seq)
       (set? form)               :par
       :else                     :seq)
     (if (set? form) (seq form) form)]))

(defn peel-group-contexts
  "[material chain] -- the context-ref-peeling + chain-building step
   shared by core.async-engine/play-form-group and this ns's own
   realize-form-group: given tag (:par or :seq) and items, peels
   ctx-refs (unordered for :par via split-contexts-unordered, a leading
   run for :seq via split-leading-contexts) and pushes each onto
   ctx-chain, nearest-first, ahead of this group's own fresh Context.
   Purely functional, no side effects -- safe to share between play
   (live, async, voice-threaded) and display (synchronous preview,
   repo-threaded), which is exactly why it takes repo-now/ctx-chain
   directly rather than a voice."
  [repo-now tag items ctx-chain]
  (let [[ctx-refs material] (if (= tag :par)
                               (split-contexts-unordered repo-now items)
                               (split-leading-contexts repo-now items))
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    [material chain]))

;; ============================================================
;; Display -- greedy, synchronous realization (debugging)
;; ============================================================

;; Mirrors core.async-engine's play-node/play-seq/play-par/
;; play-iterator/play-form* exactly, but purely functionally: no
;; core.async, no voice/atoms, no MIDI, no *engine* -- just (clock,
;; structural) threaded as plain values through the same recursive
;; shape, resolving every leaf via resolve-event instead of scheduling
;; and sending it. A :SEQ (or Iterator, or a plain container)
;; contributes a flat run of steps, since nothing about them forks the
;; timeline; a :PAR contributes exactly one {:kind :par :voices [steps
;; ...]} step, since that's the one place a single timeline genuinely
;; forks into several simultaneous ones. Deliberately matches
;; play-par's actual current behavior, quirks included: the parent's own
;; (clock, structural) are NOT advanced past whatever the forked children
;; took (play-par never touches the parent voice's own atoms either),
;; so a :SEQ sibling placed right after a :PAR currently starts back at
;; the SAME onset the :PAR's children did, not after them. That looks
;; like a real gap in the live engine, not something worth quietly
;; correcting here -- display is meant to show you what play would
;; actually do, warts included.

(declare realize-node realize-form)

(defn- realize-iterator
  "source realizes on EVERY pass; a volta :alternative is appended as a
   SUFFIX after source on the final pass only, never a substitute for it
   -- see play-iterator's own docstring for why (same bug, same fix,
   mirrored here since display must show what play would actually do)."
  [repo iter ctx-chain clock structural]
  (let [source (:source iter)
        params (:params iter)
        n      (get params :count 1)
        volta? (= (:repeat-type params) :volta)
        alt    (:alternative params)
        chain  (build-chain iter ctx-chain structural)]
    (when (= n :infinite)
      (throw (ex-info (str "display can't greedily realize a :count :infinite "
                          "Iterator -- it would never terminate.")
                      {:iterator iter})))
    (loop [i 0 steps [] clock clock structural structural]
      (if (>= i n)
        [steps clock structural]
        (let [[source-steps clock' structural'] (realize-node repo source chain clock structural)
              use-alt? (and volta? alt (= i (dec n)))
              [alt-steps clock'' structural''] (if use-alt?
                                                  (realize-node repo alt chain clock' structural')
                                                  [[] clock' structural'])]
          (recur (inc i) (into steps (into source-steps alt-steps)) clock'' structural''))))))

(defn- realize-node
  "Eagerly resolve part into [steps new-clock new-structural].
   A Leaf goes through core.domain.ornaments/expand first -- see
   play-node's own docstring for why (same fix, mirrored here since
   display must show what play would actually do); [part] unchanged
   (count 1) is the common, no-modifier case and takes the original
   single-resolve-event path directly, no extra looping."
  [repo part ctx-chain clock structural]
  (cond
    (d/leaf? part)
    (let [expanded (orn/expand part ctx-chain)]
      (if (= (count expanded) 1)
        (let [midi (r/resolve-event {:part part :ctx-chain ctx-chain} nil clock structural)]
          [[midi] (+ clock (:dur-secs midi)) (+ structural (d/part-duration part))])
        (loop [ls expanded steps [] clock clock structural structural]
          (if (empty? ls)
            [steps clock structural]
            (let [l    (first ls)
                  midi (r/resolve-event {:part l :ctx-chain ctx-chain} nil clock structural)]
              (recur (rest ls) (conj steps midi)
                     (+ clock (:dur-secs midi)) (+ structural (d/part-duration l))))))))

    (or (d/rest? part) (d/drum? part))
    (let [midi (r/resolve-event {:part part :ctx-chain ctx-chain} nil clock structural)]
      [[midi] (+ clock (:dur-secs midi)) (+ structural (d/part-duration part))])

    (d/bar? part)
    [[{:kind :mark :count (:count part)}] clock structural]

    (d/iterator? part)
    (realize-iterator repo part ctx-chain clock structural)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain structural)
          children (d/children (live-repo repo) part)]
      (if (= (:type part) :PAR)
        (let [voices (mapv (fn [child]
                              (first (realize-node repo child chain clock structural)))
                            children)]
          [[{:kind :par :voices voices}] clock structural])
        (loop [cs children steps [] clock clock structural structural]
          (if (empty? cs)
            [steps clock structural]
            (let [[child-steps clock' structural'] (realize-node repo (first cs) chain clock structural)]
              (recur (rest cs) (into steps child-steps) clock' structural'))))))

    :else [[] clock structural]))

(defn- realize-form-seq
  [repo forms ctx-chain clock structural]
  (loop [fs forms steps [] clock clock structural structural]
    (if (empty? fs)
      [steps clock structural]
      (let [[form-steps clock' structural'] (realize-form repo (first fs) ctx-chain clock structural)]
        (recur (rest fs) (into steps form-steps) clock' structural')))))

(defn- realize-form-par
  "voices in the SAME mean-pitch-ranked order play-form-par's own real
   voices end up in -- required now that #{} (unordered) is the only
   spelling for parallel material: forms arrives as (seq of a set), with
   no reliable order of its own to just mapv over the way a literal,
   ordered [:par ...] vector used to provide for free. Ranking here
   keeps display showing voices in the same order play would actually
   assign them to :TAA/:TAB/..., not an arbitrary hash order."
  [repo forms ctx-chain clock structural]
  (let [ranked (->> (map-indexed vector forms)
                    (sort-by (fn [[i f]] [(mean-pitch-rank (form-pitch-source (live-repo repo) f)) i])))
        voices (mapv (fn [[_ f]] (first (realize-form repo f ctx-chain clock structural))) ranked)]
    [[{:kind :par :voices voices}] clock structural]))

(defn- realize-form-group
  [repo tag items ctx-chain clock structural]
  (let [[material chain] (peel-group-contexts (live-repo repo) tag items ctx-chain)]
    (if (= tag :par)
      (realize-form-par repo material chain clock structural)
      (realize-form-seq repo material chain clock structural))))

(defn- realize-form
  "Mirrors core.async-engine's play-form own dispatch (see that fn/the
   mini-language comment above it), with one deliberate simplification:
   display is purely structural/timing preview, with no *engine*/voice
   at all, so a tagged-form? here just unwraps and realizes inner --
   the algorithm itself has no visible effect on display's own output,
   same as it always implicitly did before tags existed (display never
   modeled wall transforms)."
  [repo form ctx-chain clock structural]
  (cond
    (keyword? form)
    (realize-node repo (get (live-repo repo) form) ctx-chain clock structural)

    (d/part? form)
    (realize-node repo form ctx-chain clock structural)

    (tagged-form? form)
    (realize-form repo (first (split-tag form)) ctx-chain clock structural)

    (or (set? form) (sequential? form))
    (let [[tag items] (form-tag+items form)]
      (realize-form-group repo tag items ctx-chain clock structural))

    ;; See core.async-engine/validate-ids!'s own comment on this same
    ;; distinction -- an :assignment/:BAR/etc. structural node inline in
    ;; sq'd material falls through to realize-node's own :else
    ;; (unchanged, still a silent [[] clock structural] no-op, same
    ;; tolerance realize-node already has for a container's own inline
    ;; children); nil (sq returning nil for an id that doesn't resolve
    ;; to a container) is the one real, confirmed exception.
    (nil? form)
    (throw (ex-info (str "display: don't know how to play nil -- expected"
                          " a part id, a group vector, or material from sq")
                     {:form form}))

    ;; See core.async-engine/validate-ids!'s own comment on this same
    ;; case -- a bare fn used to silently fall through to the :else
    ;; no-op below instead of ever reaching play-xf, the actual entry
    ;; point for this shape.
    (fn? form)
    (throw (ex-info (str "display: don't know how to play a bare function"
                          " -- did you mean play-xf? (play-xf f & args)"
                          " applies f to each keyword id's own (sq id)"
                          " before playing, e.g. (play-xf #(times 5"
                          " (shuffle %)) :verse)")
                     {:form form}))

    :else [[] clock structural]))

(defn display
  "Like core.async-engine/play, but fully synchronous and greedy: walks
   the exact same play-arg mini-language against repo (no *engine*/
   connect needed -- pass core.repo/play-tx to see exactly what
   (play ...) would perform right now), resolving every leaf into a
   MidiEvent via core.domain.resolve/resolve-event instead of
   scheduling/sending it, and returns the whole thing as one realized,
   inspectable data structure -- no core.async, no waiting, no MIDI I/O.

   Returns a flat vector of steps: most are resolved MidiEvent maps; a
   :PAR contributes exactly one {:kind :par :voices [steps ...]} marker
   (see the ns note above this section for the one behavior this
   deliberately reproduces, not corrects); a BarLine contributes a
   {:kind :mark :count n} marker.

   Throws if it hits a :count :infinite Iterator -- greedy realization of
   a genuinely open-ended pattern can never terminate."
  [repo & args]
  (let [root-ctx (:context (get (live-repo repo) :ROOT))]
    (first (realize-form-seq repo args (if root-ctx [root-ctx] []) 0.0 0))))
