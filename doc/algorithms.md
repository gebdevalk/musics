# Algorithms in `musics`: how they reach sound

Every generative function under `algo/` is a plain Clojure function.
`algo.tree` is the one way to combine them and to play them, live or
not. `CLAUDE.md`'s "Simple composition: `algo.tree`" section is the
reference; `src/examples/tree_tour.clj` is a walkthrough to evaluate
form by form.

## Wrap, compose, run

`defalgos` turns a function into an **algo**. Called with its children,
an algo becomes a **node**; nodes nest into a **tree**. The first arg of
the raw fn is the vector of its children's data; the rest are params,
read by name from the params map the tree runs against:

```clojure
(require '[algo.tree :as tr :refer [defalgos]]
         '[algo.tree.lib :as lib]
         '[algo.rhythmic.rhythm :as rhythm])

(defalgos
  up    (fn [[xs] ^{:default 12} by] (map #(when % (+ % by)) xs))
  eucl  [rhythm/euclidean-rhythm k n])      ; lift an existing fn as-is

(def t (lib/notes (up (lib/gate eucl (lib/cycled lib/scale)))))
t                                            ; => #node (notes (up (gate (eucl) ...)))
(tr/params t)                                ; what it reads, with defaults/ranges
(tr/run t {:k 3 :n 8 :root 60 :intervals [0 4 7]})
(tr/trace t {:k 3 :n 8 :root 60 :intervals [0 4 7]})   ; every node's result
```

Swapping a stage is editing the expression; combining two sources is a
second child; `(tr/with {:k 5} eucl)` gives one instance its own params.

## Three ways to sound

- **Once:** `(play (tr/run tree params))` -- `lib/notes`/`lib/pair-notes`
  produce Leaf/Rest maps `play` walks as a plain Form.
- **Committed:** wrap the same Leaf/Rest maps in a container and
  `core.repo/commit-node!` it, to address it by id like `.mus` material.
- **Live:** `algo.tree.live` installs a tree under a name in `core.wall`'s
  registry, so any voice can follow it and every change is heard on the
  next note:

  ```clojure
  (require '[algo.tree.live :as live])
  (live/play! :riff t {:k 3 :n 8 :root 60 :intervals [0 4 7]})
  (live/param! :riff :k 5)
  (live/retree! :riff (lib/notes (lib/shuffled lib/scale)))
  (live/stop! :riff)
  ```

  A tree that reads `:nodes` transforms a voice's own notes instead:
  `(live/install! :up7 (lib/transpose :nodes) {:semitones 7})`, then
  `(play :verse :algo :up7)`.

## Where things live

| What | Namespace |
|---|---|
| Composition: `defalgos`, `run`, `show`, `params`, `trace`, `with` | `algo.tree` |
| Ready-made algos (`euclid`, `scale`, `gate`, `transpose`, `notes`, indispensability, ...) | `algo.tree.lib` |
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
| `tree.clj` | `defalgos`, nodes, `run`/`show`/`params`/`missing`/`trace`/`with` |
| `tree/lib.clj` | Ready-made algos lifting the files above, plus `notes`/`pair-notes` |
| `tree/live.clj` | Trees as `core.wall` algos: `install!`/`play!`/`param!`/`retree!`/`stop!` |

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
