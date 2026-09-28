# Algorithms in `musics`: how they reach sound

Every generative function under `algo/` is a plain Clojure function,
and every one in `algo/{indisp,metric,melodic,random,rhythmic}` is also
a tree algo (its own `:algo` metadata; `(algo.tree/algos)` lists all of
them). `algo.tree` is the one way to combine them and to play them, live
or not. `CLAUDE.md`'s "Simple composition: `algo.tree`" section is the
reference; `doc/algo-cookbook.pdf` has 47 worked recipes, each run for
real; `src/examples/tree_tour.clj` is a walkthrough to evaluate form by
form.

## Make an algorithm usable in a tree

Give the function an `:algo` attr-map. Its signature, body and callers
stay as they are; the tree reads everything else by introspection:

```clojure
(defn density-grid
  "Binary onset grid ..."
  {:algo {:short :density :in [:weights] :out :grid
          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5
                             :doc "fraction of pulses kept"}}}}
  [ranks density] ...)
```

- `:in` — the types of the leading args, which become the node's
  children; every later arg is a param, named by the arg itself.
  `:children [:coll]` names the child args when they aren't the leading
  ones.
- `:out` — what it produces (`:same` = its first child's type).
- `:params` — per param: `:type` (`:int :double :ratio :string :keyword
  :vector :map :fn :bool :any`), `:default` (`##NaN` = required), for a
  number `:min`/`:max` (`##-Inf`/`##Inf` for an open end), and optionally
  `:choices`. A rule map, a constraint vector or a fitness fn is a param
  like any number: it lives in the tctx. Registration refuses an
  incomplete spec.
- `:repeat :len` — the fn yields one value per call (a sampler): the
  node calls it `:len` times. `:pull {:via :value :args [:target]}` — it
  returns a generator (under `:via` in a returned map, or itself): the
  node calls it once and pulls `:len` values, passing `:args` each time.
  `:len` gets a default spec; it's an ordinary param.
- `:short` — the tree's name for it; `:arity` picks one arity of a
  multi-arity fn.

Then `(algo.tree/expose ns/the-fn)` defines its constructor under the
short name (`expose-ns` does every annotated fn of a namespace — how
`algo.tree.lib` exposes `algo/`). For a new function, `defalgo` does both at once (the raw fn
is kept as `name*`):

```clojure
(t/defalgo up "Shift pitches."
  {:algo {:in [:pitches] :out :pitches
          :params {:by {:type :int :min -48 :max 48 :default 12}}}}
  [pitches by] (map #(some-> % (+ by)) pitches))
```

## Compose, set, run

```clojure
(require '[algo.tree :as t] '[algo.tree.lib :refer :all])

(def riff (notes (up (gate euclid (cycled scale)))))   ; #node (notes (up (gate (euclid) ...)))
(def ctx  (t/tctx riff))       ; an atom of settings, every param at its default
(t/describe ctx)               ; key, value, range, default, algo, doc
(t/set-param! ctx :k 5)        ; checked against 0..32
(t/run riff ctx)
(t/trace riff ctx)             ; every node's result
```

- **Checked when built:** a wrong child fails at once, e.g.
  `(gate (tilt indisp) scale)` → "gate: child 1 should be :grid".
- **Swapping a stage** is editing the expression; combining two sources
  is a second child.
- **Two instances of one algo:** name one, `(euclid :as :bass)` →
  `:bass/k`.
- **Tree and tctx are separate:** one tree runs against several tctxs,
  and `(t/fit! ctx other-tree)` prepares a tctx for another tree.

## Three ways to sound

- **Once:** `(t/play! riff ctx)`, or `(play (t/run riff ctx))`. `notes`/
  `pair-notes` produce Leaf/Rest maps `play` walks as a plain Form.
- **Committed:** wrap the same Leaf/Rest maps in a container and
  `core.repo/commit-node!` it, to address it by id like `.mus` material.
- **Live:** a name binds a tree and a tctx in `core.wall`'s registry, so
  any voice can follow it, and every change to the tctx is heard on the
  next note:

  ```clojure
  (t/live! :riff riff ctx)                   ; an endless voice
  (t/set-param! ctx :k 3)
  (t/retree! :riff (notes (shuffled scale)))  ; same ctx, fitted to the new tree
  (t/stop! :riff)
  ```

  A tree that reads `:nodes` transforms a voice's own notes instead:
  `(t/live! :up7 (transpose :nodes) (t/tctx (transpose :nodes) {:semitones 7}))`,
  then `(play :verse :algo :up7)`. The GUI's Wall window shows a slider
  per ranged param of every live name.

## Where things live

| What | Namespace |
|---|---|
| Trees, tctx, `run`/`trace`/`describe`, `defalgo`/`expose`, live entry points | `algo.tree` |
| Introspection and the short ↔ full registry | `algo.tree.registry` |
| Ready-made algos (`euclid`, `scale`, `gate`, `transpose`, `stretch`, `notes`, indispensability, ...) | `algo.tree.lib` (referred in `lein repl`'s `user` ns) |
| Live playback by name | `algo.tree.live` |
| The name -> wall fn registry the engine reads per note | `core.wall` |
| `assign-algo!`, per-voice dispatch | `core.async-engine` |
| Real domain nodes (`d/leaf`, `d/rest*`, ...) | `core.domain.flat-domain` |

## The `algo/` index

For the always-current version, call `(show-algos)` at the REPL
(`musics.core`): category (one per `algo/` subdirectory) -> algo name ->
full docstring, built fresh from `ns-publics` every call.

```clojure
(show-algos)                                  ; every category, one-line glosses
(show-algos "rhythmic")                       ; just that category
(show-algos "rhythmic" "euclidean-rhythm")    ; that one algo's full doc
```

### `algo/common/` — shared math + reshaping toolbox

| File | What it does |
|---|---|
| `farey.clj` | Best rational approximation (Stern-Brocot mediant search) |
| `gate.clj` | General filter engine (`gate`) + criterion constructors |
| `isorhythm.clj` | Color/talea cycling (medieval isorhythm), `zip-parts` |
| `numeric.clj` | gcd and friends |
| `pitch.clj` | Scale building from root + intervals |
| `pulse.clj` | grid→pulses (0/1 or weighted → domain `Pulse` records) |
| `reshape.clj` | invert/retrograde/arpeggiate/hocket + weighted-shuffle |
| `rotate.clj` | Cyclic rotation |
| `scaling.clj` | clamp and friends |
| `split.clj` | Voice-splitting canon/heterophony generator |
| `transient_ops.clj` | `times`/`tuplet`/`transpose` on plain material |
| `trig.clj` | Discrete, beat-indexed `cos`/`sin`/`tan`/triangle/square/saw samplers |
| `zfilter.clj` | IIR-style recurrence (Z-transform) filters |

### `algo/indisp/` — Barlow indispensability

| File | What it does |
|---|---|
| `indispensability.clj` | Indispensability ranks + the adherence layer (tilt/power-law probabilities, density-grid); `common.music-elements/meter-indispensability` calls it directly |

### `algo/melodic/` — pitch/voice generators

| File | What it does |
|---|---|
| `counterpoint.clj` | Multi-voice motif imitation + species-counterpoint rules |
| `melody.clj` | Scales, generative melody methods, Markov/constraint walks, key-modulating melody |
| `slonimsky.clj` | *Thesaurus of Scales* interpolation techniques |

### `algo/metric/` — pulse-grid generators

| File | What it does |
|---|---|
| `metric.clj` | Binary-decomposition / continued-fraction pulse generators |

### `algo/random.clj` + `algo/random/` — RNG, distributions, chaotic maps

| File | What it does |
|---|---|
| `random/core.clj` | The pure xorshift32 engine, atom-backed seeding/state |
| `random.clj` | Basic primitives + distributions, chance/weighted-pick, Markov |
| `random/henon.clj` | Hénon-map chaotic generator |
| `random/logistic.clj` | Logistic-map chaotic generator |
| `random/lorenz.clj` | Lorenz-attractor chaotic generator |

### `algo/tree.clj` + `algo/tree/` — composition

| File | What it does |
|---|---|
| `tree.clj` | nodes (checked when built), `param-keys`, the tctx atom, `run`/`trace`/`describe`, `defalgo`/`expose` |
| `tree/registry.clj` | introspection of `:algo` metadata; short ↔ full names |
| `tree/lib.clj` | Ready-made algos lifting the files above, plus `notes`/`pair-notes` |
| `tree/live.clj` | Names binding a tree + a watched tctx in `core.wall`: `live!`/`retree!`/`stop!`/`play!` |

### `algo/rhythmic/` — rhythm generators

| File | What it does |
|---|---|
| `constraint.clj` | All-interval / constraint-satisfaction patterns |
| `decompose.clj` | Binary/split duration decomposition (Barlow-style) |
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
