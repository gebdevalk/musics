(ns core.wall
  "Registry of pluggable playback transforms (\"wall\" algorithms).

   A wall fn is always seq-in/seq-out: (nodes ctx-chain voice) -> nodes'.
   It's called identically regardless of granularity -- core.async-
   engine's container branch calls it with a full sibling list (every
   child of a :SEQ/:PAR at once); its leaf/rest/drum branch calls it with
   a singleton wrapping one already-ornament-expanded node. An algo never
   declares which one it 'acts on' -- it just always receives a seq, and
   a fn that only cares about one granularity naturally no-ops (or maps
   trivially) on the other.

   A fn that EXPANDS -- returns more nodes than it was given, e.g. a
   doubling/echo transform -- is safe to write with no special care of
   its own: for one authored note inside a container, it's called at
   most twice, not once per note the way a naive mental model might
   assume -- once on the container's own sibling list (the batch
   containing that note, alongside whatever else is in it), and once
   more per node THAT call produced, singleton-wrapped, when each is
   individually dispatched to actually sound (so a fn that doubles one
   note into two gets invoked a third time overall: the batch call, plus
   one singleton call per one of the two notes it just produced -- three
   calls, four notes actually played, for one note originally written).
   That second wave's OWN output is always played directly from there,
   never fed back through this registry a third time, by construction
   in core.async-engine (play-leaves, not play-seq, handles it) -- so an
   expanding fn can never trigger runaway, ever-doubling growth just by
   being itself; nothing here or in the engine ever re-offers your own
   already-produced output back to you as fresh input past that second
   wave. What this DOES mean for a fn with side effects (a counter, a
   PRNG draw, logging): expect those to fire at both granularities, and
   more than once for a single authored note if you expand -- 'once per
   composer-written note' is not this fn's actual calling contract.

   The algo pipeline, in full (2026-09-09 redesign -- see
   doc/decisions.md and algo-stages.txt for the design discussion that
   led here; this replaces an earlier design where one registry held
   BOTH plain fns and factories, self-tagged with an optional :kind,
   and a separate *preset-registry* held configured instances):

   1. Author a FACTORY -- (fn [name & args] -> name) -- EVERY algo is a
      factory now, even one that takes no configuration at all: name is
      the algo's OWN first argument, the name its result gets stored
      under, not a separate wrapper's concern.
   2. register-factory! name f doc -- park f, PERMANENTLY, in
      *algo-factory-registry* (core.registries). Never overwritten by
      anything in this ns -- a factory, once registered, stays available
      to build any number of independently-named, independently-
      hot-swappable results off of.
   3. Actually build one: call the factory (directly, if you have it in
      hand, or via build! below if you only have its registered name) --
      internally it calls build-algo! as its own last step, storing the
      resolved wall fn under name in *algo-registry* (a SEPARATE store,
      cooked results only).
   4. A voice/track is given just name, baked in once as a plain,
      immutable field on that voice's own map at mint/fork time --
      nothing resolved or cached there at all, and never reassigned
      afterward (core.async-engine's own :algo-prepared, path -> name,
      is a SEPARATE, narrower table consulted only at mint time, for
      preparing a track before it starts -- see that ns's own
      assign-algo! docstring).
   5. Every single node a voice visits, the engine calls (algo name)
      FRESH -- straight into *algo-registry*, no snapshot in between.

   Hot-swapping falls out of steps 3+5 directly: call some factory with
   the SAME name again (step 3), overwriting that name's entry in
   *algo-registry* -- every voice currently pointing at name (there can
   be several) picks up the change on its very next node (step 5), with
   no per-voice action needed at all. This is a genuine tradeoff, not a
   pure improvement: the OLD design froze a resolved fn into a voice's
   own assignment at the moment it was made, specifically so a later
   change to the registry couldn't retroactively affect an
   already-assigned voice. That isolation guarantee is gone now -- in
   its place, isolation is a NAMING choice: a name only one voice ever
   points at behaves exactly as isolated as before, purely because
   nobody else's factory call ever touches it again; a name several
   voices share moves them together on purpose.

   The registry atoms themselves live in core.registries -- see that
   ns's own docstring for why (collecting this project's mutable global
   state in one place, and making it ^:dynamic so a test can give itself
   a fresh, isolated registry via `binding` instead of manually
   resetting the shared one). Every function below reads/writes them
   exactly as if they were still local atoms; nothing about this ns's
   own public API depends on where they happen to live."
  (:require [core.registries :as reg]
            [core.repo :as repo]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn identity-algo
  "The default, no-op wall fn -- (nodes ctx-chain voice) -> nodes,
   unchanged. NOT clojure.core/identity: a wall fn's contract is always
   3-arg (nodes ctx-chain voice), so a genuine 1-arg identity would
   arity-error the moment an unconfigured slot's default is actually
   invoked -- confirmed live, not hypothetical."
  [nodes _ctx-chain _voice]
  nodes)

;; ============================================================
;; Factories: PERMANENT, name -> {:fn f :doc doc}, f always
;; (fn [name & args] -> name). Never overwritten by anything here.
;; ============================================================

(defn register-factory!
  "Park f, PERMANENTLY, under factory-name in *algo-factory-registry* --
   usable thereafter to build any number of independently-named cooked
   algos (build!/calling f directly). f is ALWAYS (fn [name & args] ->
   name): name is f's OWN first argument -- the name f's own result
   gets stored under, via build-algo! below, as f's own last step --
   not a separate wrapper's concern the way an older design's
   configure-algo!/configure-preset! split had it.
   doc (a plain string, optional) is shown by (factories)/(factories
   factory-name)."
  ([factory-name f] (register-factory! factory-name f nil))
  ([factory-name f doc]
   (swap! reg/*algo-factory-registry* assoc factory-name {:fn f :doc doc})
   factory-name))

(defn unregister-factory!
  "Forget factory-name's parked factory. Factories are meant to be
   permanent (see this ns's own docstring) -- this exists for test
   isolation/genuine cleanup, not routine use. Any algo already built
   from factory-name keeps running unaffected; only a LATER reference to
   factory-name is affected."
  [factory-name]
  (swap! reg/*algo-factory-registry* dissoc factory-name)
  nil)

(defn factory
  "The registered factory for factory-name, or nil if nothing's
   registered under it."
  [factory-name]
  (:fn (get @reg/*algo-factory-registry* factory-name)))

(defn factories
  "With no arg: {factory-name -> doc} for every registered factory. With
   factory-name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*algo-factory-registry*))
  ([factory-name] (:doc (get @reg/*algo-factory-registry* factory-name))))

;; ============================================================
;; Cooked algos: name -> {:fn f :doc doc}, f always an already-resolved
;; wall fn. What a voice/track actually points at; what a hot-swap
;; overwrites in place.
;; ============================================================

(defn build-algo!
  "Store an already-resolved wall fn f under name in *algo-registry* --
   the shared, low-level step every factory calls as its OWN last line
   (see e.g. algo.melodic.slonimsky/mixed-polations-algo), so 'store my
   result under the name I was given, ready to be pointed at and
   hot-swapped' isn't boilerplate every individual factory has to
   reinvent. Overwrites name's own prior entry if there was one --
   THIS is the hot-swap mechanism itself: any voice/track already
   pointing at name picks up f on its very next node (see
   core.async-engine/voice-algo-slot-fn, which reads this registry fresh
   every time, never a cached copy). doc (optional) is shown by
   (algos)/(algos name)."
  ([name f] (build-algo! name f nil))
  ([name f doc]
   (swap! reg/*algo-registry* assoc name {:fn f :doc doc})
   name))

(defn unregister-algo!
  "Forget name's parked cooked algo. A voice/track pointing at name now
   sees identity (core.wall/algo returns nil, apply-algo's own (or
   slot-fn identity-algo) catches it) starting its very next node --
   NOT frozen at whatever it last resolved to, unlike the old (pre-
   2026-09-09) design's assign-algo!, which resolved once and copied the
   result into the voice's own assignment. That freeze-on-assign
   behavior is gone project-wide now -- see this ns's own header
   docstring on the isolation tradeoff that change makes."
  [name]
  (swap! reg/*algo-registry* dissoc name)
  nil)

(defn algo
  "The registered (cooked, ready-to-play) fn for name, or nil if nothing
   is registered under it. What core.async-engine/voice-algo-slot-fn
   reads FRESH, every single node a voice visits -- never cached on the
   voice, never resolved once and forgotten -- so overwriting name's own
   entry (build-algo!, whether called directly or via some factory) is
   the entire hot-swap mechanism, no separate signal or invalidation
   needed at this layer (core.async-engine's own look-ahead cache is the
   one place that DOES need an explicit invalidation watch on this
   registry -- see that ns's own engine constructor)."
  [name]
  (:fn (get @reg/*algo-registry* name)))

(defn algos
  "With no arg: {name -> doc} for every registered cooked algo. With
   name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*algo-registry*))
  ([name] (:doc (get @reg/*algo-registry* name))))

(defn registered
  "The raw {name -> {:fn f :doc doc}} cooked-algo registry map, for a
   caller that genuinely needs every entry at once (core.async-engine's
   own look-ahead invalidation watch, the one place outside this ns that
   needs this) -- rather than reaching directly into
   core.registries/*algo-registry* and duplicating this ns's own
   knowledge of what an entry's shape is."
  []
  @reg/*algo-registry*)

(defn apply-algo
  "Run nodes (always a seq) through slot-fn, or return nodes unchanged
   if slot-fn is nil (an unconfigured slot -- the default). ctx-chain
   and voice are passed through untouched, for a wall fn that wants to
   condition its own transform on either."
  [slot-fn ctx-chain voice nodes]
  ((or slot-fn identity-algo) nodes ctx-chain voice))

(defn- resolve-config-form
  "Resolve one build!/factory-call arg against repo-view, the SAME play-
   arg-mini-language shapes play itself accepts for a Form -- bare
   keyword = repo reference, [Form+]/#{Form+} = resolve every item,
   preserving whichever collection type was actually written -- but
   deliberately NOT play's own machinery: no context-ref peeling, no
   :algo tags, no seq-vs-par distinction (this fn's caller doesn't care
   -- config data is never itself 'played'), and resolving a keyword is
   never coerced into a sequence the way sq coerces a container for
   playback. A :DATA container's own d/children IS the value returned
   -- a talea authored as '[ /4 /8 /8 /4 ] resolves straight to
   [1/4 1/8 1/8 1/4], not to anything voice- or Leaf-shaped.

   A bare keyword resolves against repo-view ONLY IF it actually names
   something there -- an id that doesn't resolve falls through to the
   literal branch below and is returned AS the keyword itself, not an
   error. This is deliberate: a factory's own args are routinely plain
   keyword FLAGS (:major, :up, a dynamic mark) that were never meant to
   be repo references at all, and build! has no pre-flight pass to make
   a hard failure safe/early the way play's validate-ids! does --
   silently trying the repo first and falling back to the literal is far
   less surprising here than erroring on every ordinary flag argument.

   A resolved container's own children are run back through this same
   fn, recursively -- confirmed live against REAL .mus text (not just
   hand-built repo maps): every DataElement the walker puts inside a
   :DATA container's own :children -- Pitch/Duration/Articulation/Int/
   Float/Ratio/String/Keyword/Name alike -- is a PLAIN value (a MIDI
   int, a Ratio, ...), never a wrapper map. A :SEQ/:PAR container's
   children CAN still be further keyword ids on top of that, so
   recursing here still does the right thing for those too, without a
   second, separate code path."
  [repo-view form]
  (cond
    (vector? form) (mapv (partial resolve-config-form repo-view) form)
    (set? form)    (into #{} (map (partial resolve-config-form repo-view)) form)
    (keyword? form)
    (if-let [node (get repo-view form)]
      (resolve-config-form repo-view (if (d/container? node) (d/children repo-view node) node))
      form)
    :else form))

(defn build!
  "Look up factory-name in *algo-factory-registry* and call it with
   (name & args) -- exactly (apply (factory factory-name) name args),
   just saves a manual factory lookup, and resolves args first (see
   resolve-config-form above): everything play's own Form mini-language
   can express (a bare keyword repo reference, [Form+]/#{Form+} groups,
   nested) is accepted here too, resolved against the latest committed
   repo ONCE, right now -- a bare keyword pointing at a :DATA container
   resolves straight to that container's own raw values (a talea, a
   color); a literal value (a number, a ratio, a plain collection with
   nothing keyword-shaped inside it) passes through unchanged. This is
   what lets a factory's args be fed EITHER inline literals OR real,
   committed, versioned Material -- a composer's own choice per call,
   not a fork in the mechanism.

   Degrades to building identity-algo under name, with a console
   warning, rather than throwing, if factory-name isn't registered or
   its factory throws applying args -- same 'degrade and warn, never
   throw from inside a live voice' policy every other resolution in this
   project already has, since build! can be reached from inside an
   already-running voice's own go-block (a tagged Form mid-sequence),
   not just at a voice's birth.

   Returns name.

     (register-factory! :slonimsky mixed-polations-algo)
     (build! :myVoiceAlgo :slonimsky nil [:turn] nil)
     (build! :myOtherAlgo :slonimsky nil [:sigh] nil)  ; same factory,
                                                         ; independent name"
  [name factory-name & args]
  (let [repo-view     (repo/view (repo/latest-tx))
        resolved-args (mapv (partial resolve-config-form repo-view) args)]
    (if-let [f (factory factory-name)]
      (try
        (apply f name resolved-args)
        (catch Exception e
          (println "core.wall:" factory-name "threw building" name "--" (.getMessage e) "-- falling back to identity")
          (build-algo! name identity-algo)))
      (do (println "core.wall: no factory registered as" factory-name "-- falling back to identity")
          (build-algo! name identity-algo)))
    name))

(defn resolve-name
  "name -> a concrete wall fn. nil -> identity-algo; a bare name -> algo
   name, falling back to identity-algo (with a console warning) if
   nothing is registered under it. No other shapes -- unlike the older
   (pre-2026-09-09) design, a Name is never itself [factory-name arg...]
   anymore: applying a factory to args now ALWAYS happens through build!
   (or calling the factory directly), which requires an explicit target
   name to store the result under -- there's no more transient,
   un-named, registry-touching-nothing resolution path. A play-time
   :algo tag's Name is therefore always just a name, already built.

   Deliberately degrade-and-warn here, never throw: a caller resolving a
   Name mid-performance (core.async-engine/voice-algo-slot-fn's own
   fresh, every-node lookup, or the per-branch #{} case in
   play-form-par) may be running from inside a live voice's own
   go-block, where a thrown
   exception never reaches the caller -- it just silently kills that
   voice's goroutine, a worse failure than degrading to identity-algo
   and carrying on. A LOUD, immediate failure for a mistyped Name is
   core.async-engine/validate-algo-name!'s own job instead -- called
   synchronously, before any voice starts."
  [name]
  (cond
    (nil? name) identity-algo
    :else (or (algo name)
              (do (println "core.wall: no algorithm registered as" name "-- falling back to identity")
                  nil)
              identity-algo)))

;; ============================================================
;; Distributions: a SEPARATE registry -- name -> {:fn f :doc doc},
;; a plain (lo hi) -> value sampler (e.g. algo.random/lo-emph), never a
;; wall-fn itself. Exists so a composite wall-fn FACTORY can accept a
;; distribution BY NAME as one of its own args and resolve it here,
;; rather than only ever accepting a literal Clojure fn value (which
;; couldn't be named from a play-arg :algo tag or build! call at all)
;; -- see algo.common.reshape/weighted-shuffle-algo for the first real
;; consumer. See core.registries/*distribution-registry*'s own
;; docstring for the fuller rationale.
;; ============================================================

(defn register-distribution!
  "Park f (a plain (lo hi) -> value sampler, e.g. algo.random/lo-emph)
   under name -- usable thereafter by a composite factory that accepts
   a distribution by name, e.g. algo.common.reshape/weighted-shuffle-
   algo. doc (optional) is shown by (distributions)/(distributions
   name)."
  ([name f] (register-distribution! name f nil))
  ([name f doc]
   (swap! reg/*distribution-registry* assoc name {:fn f :doc doc})
   name))

(defn unregister-distribution!
  "Forget name's parked distribution. Anything that already resolved it
   (a factory applied earlier) keeps whatever fn it already resolved to
   -- only a LATER reference to name is affected, same invariant every
   other registry in this ns already has."
  [name]
  (swap! reg/*distribution-registry* dissoc name)
  nil)

(defn distribution-fn
  "The registered (lo hi) -> value fn for name, or nil if nothing's
   registered under it."
  [name]
  (:fn (get @reg/*distribution-registry* name)))

(defn distributions
  "With no arg: {name -> doc} for every registered distribution. With
   name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*distribution-registry*))
  ([name] (:doc (get @reg/*distribution-registry* name))))

;; ============================================================
;; Criteria: a THIRD registry alongside algo/distribution --
;; name -> {:fn f :doc doc}, f a FACTORY (fn [args...] -> select-fn),
;; select-fn being (part raw-prev) -> boolean. Exists so
;; algo.common.gate/gate-algo can accept a criterion BY NAME as one of
;; its own args, resolved here, the same way weighted-shuffle-algo
;; resolves a distribution by name.
;; ============================================================

(defn register-criterion!
  "Park f (a FACTORY, (fn [args...] -> select-fn)) under name -- usable
   thereafter by algo.common.gate/gate-algo, e.g. [:lo 67] resolving
   name :lo and applying 67 to its own registered factory. doc
   (optional) is shown by (criteria)/(criteria name)."
  ([name f] (register-criterion! name f nil))
  ([name f doc]
   (swap! reg/*criteria-registry* assoc name {:fn f :doc doc})
   name))

(defn unregister-criterion!
  "Forget name's parked criterion. Anything that already resolved it
   keeps whatever select-fn it already resolved to -- only a LATER
   reference to name is affected, same invariant every other registry
   in this ns already has."
  [name]
  (swap! reg/*criteria-registry* dissoc name)
  nil)

(defn criterion-fn
  "The registered (fn [args...] -> select-fn) factory for name, or nil
   if nothing's registered under it."
  [name]
  (:fn (get @reg/*criteria-registry* name)))

(defn criteria
  "With no arg: {name -> doc} for every registered criterion. With
   name: just that one's doc (nil if unregistered)."
  ([] (into {} (map (fn [[k v]] [k (:doc v)])) @reg/*criteria-registry*))
  ([name] (:doc (get @reg/*criteria-registry* name))))

(defn resolve-criterion
  "spec -> a select-fn ((part raw-prev) -> boolean), resolving spec
   against *criteria-registry*. Always [name arg...] shaped -- unlike
   resolve-name's own Name (which can be bare, e.g. a plain algo), a
   criterion inherently needs its own args to mean anything (there's no
   sensible bare :lo with no cutoff). An unregistered name degrades to
   a select-fn that rejects everything, with a console warning, same
   'degrade and warn, never throw from inside a live voice' policy
   resolve-name already has -- 'reject everything' rather than 'accept
   everything' specifically because a filter that silently passes
   everything through, indistinguishable from working correctly, is a
   much easier bug to miss than one that visibly rejects everything."
  [[name & args]]
  (if-let [f (criterion-fn name)]
    (apply f args)
    (do (println "core.wall: no criterion registered as" name "-- rejecting everything")
        (fn [_part _raw-prev] false))))

;; ============================================================
;; stateful-generator -- shared boilerplate for a GENERATOR wall fn
;; (one that ignores its own placeholder nodes and synthesizes fresh
;; content instead, see algo.common.isorhythm/color-talea-algo for the
;; original, hand-written example this generalizes). Extracted once two
;; genuinely non-obvious pieces -- the double-call idempotency dance and
;; the non-leaf passthrough -- turned out to be exactly the same for
;; every such generator, not specific to color-talea's own logic.
;; ============================================================

(defn stateful-generator
  "Build a wall fn (a ready-to-store RESULT, not a factory itself -- see
   this ns's own docstring on the pipeline) from next-fn (a 0-arg fn
   that advances its OWN internal state and returns the next raw value
   -- exactly the shape algo.random.logistic/logistic-function's and
   algo.random.lorenz/lorenz-attractor's own :value closures already
   are, or a hand-rolled index-into-a-vector closure like
   color-talea-algo's own) and render-fn (raw-value -> {:pitches [...]
   :duration r}, mapping whatever next-fn returns onto the two fields a
   real Leaf needs beyond id/context).

   Handles the two pieces of boilerplate every stateful generator wall
   fn needs, so wiring the next one doesn't have to reinvent them:
   - a node that isn't a leaf/rest/drum (a Bar, an :assignment marker)
     passes straight through untouched, consuming no step -- same
     tolerance play-node itself already has for these shapes elsewhere;
   - every freshly-built node is tagged ::step -- core.wall's own
     documented double-call contract (a container's full sibling batch,
     then again per already-produced node singleton-wrapped, see this
     ns's own header docstring) calls a wall fn TWICE for what's
     really one note; without this tag, next-fn would be called twice
     for it too, double-advancing whatever state it carries. An
     already-tagged node is passed straight through instead of being
     rebuilt a second time.

   next-fn is therefore called AT MOST ONCE per genuinely new
   placeholder, never re-entered for an already-tagged node -- safe to
   give it real, mutating internal state (an atom, same as logistic-
   function/lorenz-attractor's own :value already carry) without this
   wall fn's own double-call contract ever corrupting it.

   pre-step-fn (optional, 3rd arg, defaults nil) is called AT MOST ONCE
   per genuinely new placeholder too -- same guard as next-fn, same
   guarantee of never double-firing across the batch/singleton pair --
   right BEFORE next-fn, as (pre-step-fn ctx-chain structural-time),
   structural-time being @(:structural voice): the voice's own real,
   already-tracked elapsed musical time, the SAME time coordinate every
   ordinary note's own :micro/:humanization/:Tempo sampling already
   uses (core.domain.resolve/resolve-event). This is the hook that lets
   a generator's OWN parameters (logistic-function's r, henon-
   attractor's a/b, lorenz-attractor's sigma/rho/beta) be driven by a
   committed context envelope instead of staying fixed for the whole
   voice -- a caller wanting that samples ctx-chain itself
   (core.domain.context/ctx-value-chain chain key structural-time) and
   pushes the result into whatever :r!/:params! setter next-fn's own
   generator returned, e.g.:
     (let [gen (logistic/logistic-function 3.8 0.5)]
       (stateful-generator
         (:value gen)
         render-fn
         (fn [ctx-chain t]
           ((:r! gen) (c/ctx-value-chain ctx-chain :chaosR t)))))
   No accumulator of any kind is needed for this -- @(:structural voice)
   is already correct, tracked by the engine the whole time, for both a
   domain-data-consuming transform (which reads it via voice directly,
   already having voice in hand) and a domain-data-ignoring generator
   like this one (which previously had no way to reach it at all, next-
   fn being a bare 0-arg fn with no path back to ctx-chain/voice --
   pre-step-fn is exactly that missing path, nothing more). Omitting
   pre-step-fn (the 2-arg call, or passing nil explicitly) leaves every
   existing generator -- logistic-algo/lorenz-algo/henon-algo, all
   still calling the 2-arg form -- completely unchanged.

   Pair the result with a :count :infinite Iterator as the placeholder
   source (see CLAUDE.md's Wall section, or color-talea-algo's own
   docstring for the full pattern) to get a voice that plays forever,
   generating fresh content from next-fn every step:
     (register-factory! :logisticPitch
       (fn [name r x]
         (build-algo! name
           (stateful-generator (:value (logistic/logistic-function r x))
                                (fn [x] {:pitches [(+ 48 (int (* x 36)))]
                                         :duration 1/8})))))
     (build! :myLogistic :logisticPitch 3.8 0.5)
     (play :verse :algo :myLogistic)"
  ([next-fn render-fn] (stateful-generator next-fn render-fn nil))
  ([next-fn render-fn pre-step-fn]
   (fn [nodes ctx-chain voice]
     (map (fn [node]
            (cond
              (contains? node ::step) node
              (not (or (d/leaf? node) (d/rest? node) (d/drum? node))) node
              :else
              (do
                (when pre-step-fn
                  (pre-step-fn ctx-chain @(:structural voice)))
                (let [{:keys [pitches duration]} (render-fn (next-fn))]
                  (-> (d/leaf (:id node) (:context node) duration pitches)
                      (assoc ::step true))))))
          nodes))))

(defn context-params-pre-step-fn
  "Build a pre-step-fn (see stateful-generator above) that samples EACH
   entry of param->key -- e.g. {:r :chaosR}, {:a :chaosA :b :chaosB} --
   against ctx-chain at structural-time via core.domain.context/
   ctx-value-chain, then calls setter with the resulting {param value}
   map. setter is usually a generator's own :params! straight (a
   map-merge setter -- algo.random.henon/henon-attractor's and
   algo.random.lorenz/lorenz-attractor's own already are this shape), or
   a small caller-side adapter for a single-scalar setter like
   algo.random.logistic/logistic-function's own :r! (e.g. (fn [m]
   ((:r! gen) (:r m)))).

   Only keys actually present in param->key are ever sampled -- a
   partial map leaves every other parameter exactly whatever fixed value
   the generator was built with, so context-driving is opt-in per
   parameter, never all-or-nothing. Every step re-samples ctx-chain
   fresh, no caching of any kind -- a ramp mid-envelope, or a whole
   different committed value after a :tx redirect on this voice, both
   take effect starting the very next step, which is the entire point:
   this is what makes a generator's own parameter genuinely LIVE rather
   than fixed once at assignment time. If key names something never set
   anywhere in ctx-chain, ctx-value-chain returns nil for it, same as an
   ordinary unset custom context key anywhere else in this project --
   the composer is responsible for actually authoring a value for it
   somewhere on the chain (a root-level default, an ambient !key:
   instruction, ...)."
  [param->key setter]
  (fn [ctx-chain t]
    (setter (into {} (map (fn [[param key]] [param (c/ctx-value-chain ctx-chain key t)])) param->key))))
