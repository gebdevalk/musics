# Algorithms in `musics`: what they are, and how they meet `play`

This is the guide `doc/pipeline.md` doesn't have: not "how to invoke
an already-registered algorithm from a `play` call" (that's covered
there, under "Feeding an algorithm its own parameters"), but "what
kinds of algorithm does this project actually support, which one do I
want, and how do I write and hook up my own." For the mechanism's own
internals (the wall registry, `assign-algo!`'s exact resolution rules),
see `CLAUDE.md`'s "Wall: per-voice playback algorithms" section — this
doc stays practical and defers there for the deep design reasoning.

## The one distinction that decides everything

"Algorithm" covers two completely different kinds of thing in this
project, and which one you're writing determines everything else —
where the code lives, what it's called with, and how it ever reaches
sound:

- **Wall algorithms** — run *live, per-voice, at play time*, reshaping
  or selecting material a voice is already walking. These are the
  *only* kind reachable from `play`'s own `:algo` tag.
- **Everything else** — generators, combinators, walkers, and
  randomizers acting as any of those — run *once, at REPL/authoring
  time*, producing new content from parameters, before `play` ever
  starts. Never reachable from `:algo` directly; you call them as
  ordinary Clojure functions and either splice the result into a
  `play` call as literal material, or commit it into the repo as a
  real, addressable part.

If you remember nothing else from this doc: **"does it need to touch
material a voice is already playing, right now?"** is the only
question that matters for deciding which of these two worlds an
algorithm belongs to.

## The taxonomy

Six shapes, grounded in what's actually in `algo/` and `core.wall`
today, not a hypothetical list:

| Shape | Input | Output | Wall-reachable? |
|---|---|---|---|
| **Generator** | parameters only (scalars, pattern vectors) | new pattern data or real material, from nothing | No |
| **Transformer** | real material + optional parameters | reshaped real material, same shape | **Yes** |
| **Filter** | real material + a keep/drop pattern or predicate | a subset of the input material | **Yes** |
| **Randomizer** | nothing, or real material, + a distribution/seed | new or perturbed material | Only if acting as a Transformer/Filter |
| **Combinator** | multiple already-realized streams | one combined stream | No |
| **Walker** | a rule/constraint set (+ maybe a seed) | new material satisfying the constraints | No |

Worked examples already in the codebase:

- **Generator**: `algo.common.isorhythm/color-talea` (a color/pitch
  sequence + a talea/duration sequence → paired events), the
  Euclidean/Fibonacci/prime/L-system/Markov generators in
  `algo.rhythmic.rhythm`, the pulse generators in `algo/metric/`, and
  the much larger set in `algo/rhythmic/`'s ten other files (Reich
  phase music, Xenakis sieves, polyrhythm/polymeter, genetic/RNN
  rhythm generation, physical-simulation and natural-process rhythms,
  fractal/geometric rhythms, Indian tala and West African timeline
  patterns — see CLAUDE.md's own `algo/` entry for the full list).
- **Transformer**: `algo.common.split/split-leaf-voice` — takes real
  `Leaf`/`Rest`/`Drum` content and reshapes it into `n` faster,
  octave-shifted voices. Not currently registered as a *wall* fn —
  it's a real, working Clojure function you can call directly, or wrap
  as a factory and register it yourself (`core.wall/register-factory!`
  + `build-algo!`, see "Wall algorithms" below) — but its shape is
  exactly a Transformer's.
- **Filter**: no concrete example registered yet — this is the shape
  a rhythmic gate or texture-thinning operation would take (real
  material in, a boolean/probability pattern deciding what survives).
- **Randomizer**: `algo.random`'s distributions, chance/weighted-pick
  helpers (built on `algo.random.core`'s atom-backed RNG), and
  `algo.random.logistic`/`lorenz`'s chaotic maps — not a distinct
  shape of its own, a dimension that cuts across the other five (a
  stochastic generator, a randomly-perturbing transformer, a
  probabilistic filter). Deliberately atom-backed rather than a
  dynamic var, specifically so a Randomizer-shaped algorithm works
  correctly as a *wall* fn too — a `binding` isn't reliably conveyed
  across a parked `core.async` goroutine, but every voice reading the
  same atom sees consistent state regardless of which thread it
  resumes on.
- **Combinator**: no concrete example registered as such — in plain
  Clojure this is just higher-order function composition (feed one
  generator's output into another), nothing special needed.
- **Walker**: `algo/melodic/`'s constraint-satisfaction walks
  (`melody.clj`'s `constraint-melody`, `algo.rhythmic.constraint`'s
  `constraint-satisfaction-rhythm`), and `algo.melodic.counterpoint` —
  a rule set (species-counterpoint: no parallel fifths/octaves,
  consonance against every already-placed voice) walked to produce a
  multi-voice result satisfying it, the richest Walker example in the
  codebase so far.

## Wall algorithms: writing and using one

A wall fn's contract, always: `(fn [nodes ctx-chain voice] -> nodes')`
— seq-in/seq-out. It's called identically regardless of granularity:
once with a whole container's own sibling list, and once per leaf with
a singleton `[node]`. A well-behaved algorithm doesn't need to know or
care which — a transform that only makes sense at one granularity just
no-ops or maps trivially on the other.

**Every algo is a factory now, even one with no configuration at all**
— `(fn [name params] -> name)`, `params` ALWAYS a plain map (2026-09-11
redesign: every factory takes ONE uniform params map now, not a
positional arg list that differs per factory — this is what makes a
built algo genuinely toolable, e.g. by a GUI that can render `{key
value}` pairs generically), `name` the factory's OWN first argument
(the name its result gets stored under), its last line always calling
`build-algo!` to store the result. Two steps, always:

```clojure
(require '[musics.core :as m])

;; 1. park the factory, PERMANENTLY -- name is a parameter, not baked in
(m/register-factory! :retrograde (fn [name _params] (m/build-algo! name (fn [nodes _ctx-chain _voice] (reverse nodes)))))

;; 2. actually build it under a real name
(m/build! :retrograde :retrograde {})   ;; factory-name :retrograde, built
                                      ;; under the SAME target name here
                                      ;; -- they don't have to match
                                      ;; ({} since this factory takes no
                                      ;; configuration)

;; use it from a play call -- always a bare, already-built name
(m/play :verse :algo :retrograde)
```

**Parameterized** — the factory just reads more keys out of `params`:

```clojure
(m/register-factory! :transpose-by
  (fn [name {:keys [n]}] (m/build-algo! name (fn [nodes _ctx _voice]
                                      (map #(update % :pitches
                                              (partial mapv (partial + n)))
                                           nodes)))))

(m/build! :up5 :transpose-by {:n 5})
(m/play :melody :algo :up5)
```

**Hot-swapping** — call `build!` again with the SAME target name (the
same factory, or a different one) any number of times; every
voice/track currently pointing at that name picks up the change on its
very next node, with nothing touched on the voice itself:

```clojure
(m/build! :up5 :transpose-by {:n 7})   ;; :melody, already playing with
                                   ;; :algo :up5, picks this up live
```

`(m/registered :up5)` (or `(m/algos :up5)` for just the doc) now also
remembers `:factory-name`/`:params` — the recipe, not just the resolved
fn — for anything built through `build!` (a factory called directly
still stamps nothing).

There is no "reconfigure the SAME algo in place without a target name"
shape anymore, and no inline `[name arg...]` tag shape either — a
`:algo` tag (or `assign-algo!`'s own argument) is ALWAYS a bare,
already-built name or `nil`; applying a factory to args always happens
earlier, as its own explicit `build!` step. See `doc/pipeline.md`'s
"Feeding an algorithm its own parameters" for the fuller walkthrough,
and `CLAUDE.md`'s "Wall" section for exactly how a voice's own `:algo`
gets set (once, immutably, at mint time) and resolved (fresh, every
node), including the console-warning-then-identity failure behavior.

## Everything else: generators, combinators, walkers

No special contract at all — write a plain Clojure function. The only
real question is how its output reaches `play`. Two ways:

**Splice literal material directly into a `play` call.** `play`
accepts a bare `d/part?` node, or a seq of them, right alongside
ordinary keyword references — no grammar, no registration:

```clojure
(require '[algo.common.isorhythm :as iso]
         '[core.domain.flat-domain :as d]
         '[core.domain.context :as c])

;; a Generator: color-talea returns [pitch duration] pairs, not real
;; Leaf records -- there's no bridging helper for this yet (a real,
;; still-open gap, not an oversight), so build them yourself:
(def pairs  (iso/color-talea [60 64 67] [1/4 1/8 1/8] 2))
(def leaves (map (fn [[pitch dur]] (d/leaf (gensym) (c/context) dur [pitch]))
                  pairs))

(m/play (vec leaves))   ;; plays straight away, no commit needed
```

**Or commit it into the repo, to become a real, addressable part**
(if you want to reference it by id later, the way any hand-authored
`.mus` content is):

```clojure
(require '[core.repo :as repo])

(repo/commit-node! :generated
  {:type :SEQ :id :generated :context (c/context) :children (vec leaves)})

(m/play :generated)
```

A Combinator or Walker works exactly the same way — it's just a
Clojure function whose *output* happens to come from combining other
functions' results, or from a constrained search, rather than a direct
pattern computation. Nothing about reaching `play` changes.

## Relation to `play`, summarized

`play`'s own `:algo` tag is a live, per-voice, hot-swappable
*association* — it doesn't run an algorithm and hand it a static
result the way an `@[ ]` call used to; it points a specific voice's
path at a wall fn that gets re-read fresh on every single node that
voice visits, for as long as that voice is playing. That's precisely
why only Transformer/Filter-shaped algorithms fit there: they're the
only shape whose whole *job* is "reshape whatever a live voice hands
you," matching a mechanism built to re-invoke on every node rather
than compute once.

A Generator/Combinator/Walker has no such live relationship to a voice
at all — it runs to completion once, before anything is playing,
producing ordinary data. `play` never invokes one directly; it only
ever plays whatever content that algorithm already finished producing,
the exact same way it plays hand-typed notes. The distinction isn't a
grammar restriction or a missing feature — it's the same boundary this
whole project draws everywhere else between *what the music is*
(content, decided once) and *how a particular performance renders it*
(a live choice, decided per voice, per call) — see CLAUDE.md's "Shape
of the system" for that boundary stated in full.

## Where things live

| What | Namespace |
|---|---|
| Wall registry (`register-factory!`/`build!`/`build-algo!`) | `core.wall` |
| `assign-algo!`, `algo-assignments`, per-voice dispatch | `core.async-engine` |
| Generative helpers | `algo/indisp`, `algo/metric`, `algo/rhythmic`, `algo/melodic`, `algo/random`, `algo/common` |
| Real domain nodes (`d/leaf`, `d/part?`, ...) | `core.domain.flat-domain` |
| Committing generated content as a real part | `core.repo/commit-node!` |

## The `algo/` index

Every file in `algo/` (39 as of 2026-09-17 — verified by listing the
directory directly, not assumed; 36 at the previous count on
2026-09-10, plus `toolkit.clj`/`algoline.clj`/`dimensions.clj` added
2026-09-12 through 2026-09-14, see their own section below), what it
does in one line, and whether it's LIVE (defines a real `core.wall`
factory, reachable from `play`'s own `:algo` tag once you
`register-factory!` it) or STATIC (a plain Clojure function — call it
directly, or splice/commit its output, per "Everything else" above;
never reachable from `:algo` directly). **None of the LIVE ones are
registered by default** —
`register-factory!` for any of them currently appears only in that
file's own tests, never at any bootstrap/session-setup point, so
"LIVE" here means "wall-shaped and ready to register," not "already
usable this session."

This table is a snapshot — for the live, always-current version, call
`(show-algos)` at the REPL (`musics.core`): root ("algorithms") →
category (one per `algo/` subdirectory, derived from the classpath,
never hand-maintained) → algo name → full documentation, built fresh
every call straight off `ns-publics`/docstrings, one level more
granular than this table (every public function, not just a one-line
gloss per file).
  ```clojure
  (show-algos)                                  ; every category, every
                                                  ; algo name, one-line gloss
  (show-algos "rhythmic")                        ; just that category
  (show-algos "rhythmic" "euclidean-rhythm")     ; that ONE algo's full doc
  ```

### `algo/common/` — shared math + reshaping toolbox

| File | What it does | Kind |
|---|---|---|
| `farey.clj` | Best rational approximation (Stern-Brocot mediant search) | STATIC |
| `gate.clj` | General filter engine + named registered criteria (replaced 6 bespoke filters) | **LIVE** (`gate-algo`) |
| `isorhythm.clj` | Color/talea cycling (medieval isorhythm) | **LIVE** (`color-talea-algo`) |
| `numeric.clj` | gcd and friends | STATIC |
| `pitch.clj` | Scale building from root + intervals | STATIC |
| `pulse.clj` | grid→pulses (0/1 or weighted → domain `Pulse` records) | STATIC |
| `reshape.clj` | invert/retrograde/arpeggiate/hocket + weighted-shuffle/chain | **LIVE** (`weighted-shuffle-algo`, `chain-algo`) |
| `rotate.clj` | Cyclic rotation | STATIC |
| `scaling.clj` | clamp and friends | STATIC |
| `split.clj` | Voice-splitting canon/heterophony generator (octave-up-and-halve, doubled) | STATIC |
| `transient_ops.clj` | `times`/`tuplet`/`transpose` on plain material, mirroring grammar semantics | STATIC |
| `trig.clj` | Discrete, beat-indexed `cos`/`sin`/`tan`/triangle/square/saw samplers | STATIC |
| `zfilter.clj` | IIR-style recurrence (Z-transform) filters | **LIVE** (`smooth-pitch-algo`) |

### `algo/indisp/` — Barlow indispensability

| File | What it does | Kind |
|---|---|---|
| `indispensability.clj` | Indispensability ranks + the adherence layer (tilt/power-law probabilities, density-grid) | STATIC — but the one file with a real, LIVE `core.domain` dependency (`common.music-elements/meter-indispensability` calls it directly) |

### `algo/melodic/` — pitch/voice generators

| File | What it does | Kind |
|---|---|---|
| `counterpoint.clj` | Multi-voice motif imitation + species-counterpoint rules | STATIC |
| `melody.clj` | Scales, generative melody methods, Markov/constraint walks, key-modulating melody | STATIC |
| `slonimsky.clj` | *Thesaurus of Scales* interpolation techniques | **LIVE** (`mixed-polations-algo`) |

### `algo/metric/` — pulse-grid generators

| File | What it does | Kind |
|---|---|---|
| `metric.clj` | Binary-decomposition / continued-fraction pulse generators | STATIC |

### `algo/random.clj` + `algo/random/` — RNG, distributions, chaotic maps

| File | What it does | Kind |
|---|---|---|
| `random/core.clj` | The pure xorshift32 engine (mostly public), atom-backed seeding/state | STATIC (infrastructure) |
| `random.clj` | Basic primitives + everything built on the engine: distributions, chance/weighted-pick, Markov | STATIC (infrastructure — used throughout the project) |
| `random/henon.clj` | Hénon-map chaotic generator | **LIVE** (`henon-algo`) |
| `random/logistic.clj` | Logistic-map chaotic generator | **LIVE** (`logistic-algo`) |
| `random/lorenz.clj` | Lorenz-attractor chaotic generator | **LIVE** (`lorenz-algo`) |

### `algo/toolkit.clj`, `algo/algoline.clj`, `algo/dimensions.clj` — composable-pipeline exploration (top-level, not in a subdir)

| File | What it does | Kind |
|---|---|---|
| `toolkit.clj` | General-purpose building-block fns (re-exports `algo.random`'s own, plus new ones) meant as steps for `algoline` | STATIC — exploratory, unwired |
| `algoline.clj` | Interceptor-shaped composable pipeline (`step`/`dref`/`detached`) for chaining `toolkit` fns into one ordered context-threading pipeline | STATIC — exploratory, unwired |
| `dimensions.clj` | An 11-dimension taxonomy classifying every function across the whole `algo/` tree by input/output shape, state, randomness, coupling, and pipeline role | STATIC — exploratory, unwired |

None of these three defines a `core.wall` factory, and nothing in
`core.wall`/`core.async-engine`/the grammar/`musics.core` requires any
of them — they have **zero** outside references except two demo files
under `src/examples/` (`cyclic_random_algoline.clj`,
`indispensability_algoline.clj`), which exercise the pipeline shape but
don't feed material into playback. Added 2026-09-12 through 2026-09-14,
after this table's original 2026-09-10 survey. Considered finished work
(tested, on `main`), not in-progress scaffolding — but "finished" here
means "the exploration concluded," not "reachable from `play`." See
`doc/audits/algo-composition.txt` for the fuller design history if you
want it. Treat these as exploratory/reference material, not something
you can `:algo` a voice onto without first wiring a `register-factory!`
call yourself.

### `algo/rhythmic/` — the largest subdirectory; entirely STATIC

No file in this directory defines a wall factory — every one is a
plain generator, called directly or spliced/committed into the repo
per "Everything else" above.

| File | What it does |
|---|---|
| `constraint.clj` | All-interval / constraint-satisfaction patterns |
| `decompose.clj` | Binary/split duration decomposition (Barlow-style rhythmic decomposition) |
| `fractal_geometric.clj` | Cantor-set, L-system, polygon-rotation rhythms |
| `micro.clj` | Swing/humanize/pocket-groove |
| `necklace.clj` | Rotation-equivalence classes, Vuza canons |
| `phase_sieve.clj` | Reich phase music, Xenakis sieve theory |
| `physical.clj` | Pendulum/physical-simulation rhythms |
| `poly.clj` | Polyrhythm/polymeter layering |
| `rhythm.clj` | Euclidean/Fibonacci/prime/L-system/Markov generators — the "basics" file |
| `sonification.clj` | Data/text → rhythm |
| `stochastic.clj` | Distribution-sampled binary patterns |
| `transform.clj` | EMI-style/Oblique-Strategies variation |
| `world.clj` | Indian tala / West African timeline patterns |
