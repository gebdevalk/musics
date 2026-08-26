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
  `algo/rithmic/`, the pulse generators in `algo/metric/`.
- **Transformer**: `algo.common.split/split-leaf-voice` — takes real
  `Leaf`/`Rest`/`Drum` content and reshapes it into `n` faster,
  octave-shifted voices. Not currently registered as a *wall* fn
  (its home today is `ElementAlgo`'s old text contract, now gone —
  see "Algorithm registries" in `CLAUDE.md`), but its shape is exactly
  a Transformer's, and it's a real, working Clojure function you can
  call directly or register as a wall algorithm yourself.
- **Filter**: no concrete example registered yet — this is the shape
  a rhythmic gate or texture-thinning operation would take (real
  material in, a boolean/probability pattern deciding what survives).
- **Randomizer**: `algo/random/`'s distributions, chance/weighted-pick
  helpers, and chaotic maps (`lorenz.clj`) — not a distinct shape of
  its own, a dimension that cuts across the other five (a stochastic
  generator, a randomly-perturbing transformer, a probabilistic
  filter).
- **Combinator**: no concrete example registered as such — in plain
  Clojure this is just higher-order function composition (feed one
  generator's output into another), nothing special needed.
- **Walker**: `algo/melodic/`'s constraint-satisfaction walks.

## Wall algorithms: writing and using one

A wall fn's contract, always: `(fn [nodes ctx-chain voice] -> nodes')`
— seq-in/seq-out. It's called identically regardless of granularity:
once with a whole container's own sibling list, and once per leaf with
a singleton `[node]`. A well-behaved algorithm doesn't need to know or
care which — a transform that only makes sense at one granularity just
no-ops or maps trivially on the other.

```clojure
(require '[musics :as m])

;; register: a plain fn, no parameters of its own
(m/register-wall! :retrograde (fn [nodes _ctx-chain _voice] (reverse nodes)))

;; use it from a play call
(m/play :verse :algo :retrograde)
```

**Parameterized** — register a *factory* instead (`(fn [args...] ->
wall-fn)`), and feed it concrete data either inline at the point of
use, or once, ahead of time, from a fixed name:

```clojure
(m/register-wall! :transpose-by (fn [n] (fn [nodes _ctx _voice]
                                            (map #(update % :pitches
                                                    (partial mapv (partial + n)))
                                                 nodes))))

(m/play :melody :algo [:transpose-by 5])        ;; inline, this call only

(m/configure-wall! :transpose-by 5)             ;; install once, feed data later
(m/play :melody :algo :transpose-by)            ;; every future reference picks
                                                 ;; up whatever was last configured
```

See `doc/pipeline.md`'s "Feeding an algorithm its own parameters" for
the full inline-vs-`configure-wall!` tradeoff, and `CLAUDE.md`'s "Wall"
section for exactly how `assign-algo!`/`play`'s own `:algo` tag resolve
a name, including the console-warning-then-identity failure behavior.

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
| Wall registry, `apply-factory`, `configure-wall!` | `core.wall` |
| `assign-algo!`, `algo-assignments`, per-voice dispatch | `core.async-engine` |
| Generative helpers (mostly standalone Clojure, unwired) | `algo/indisp`, `algo/metric`, `algo/rithmic`, `algo/melodic`, `algo/random`, `algo/common` |
| The old text-invocation registries (`@[ ]`/`@{ }`, grammar removed) | `input.algo-registry` |
| Real domain nodes (`d/leaf`, `d/part?`, ...) | `core.domain.flat-domain` |
| Committing generated content as a real part | `core.repo/commit-node!` |
