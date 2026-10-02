# Algorithms in `musics`: how they reach sound

Every generative function under `algo/` is a plain Clojure function,
and every one in `algo/{indisp,metric,melodic,random,rhythmic}` is also
a tree algo (its own `:algo` metadata; `(algo.tree/algos)` lists all of
them). `algo.tree` is the one way to combine them and to play them, live
or not. `CLAUDE.md`'s "Simple composition: `algo.tree`" section is the
reference; `doc/algo-cookbook.html` (a PDF via `scripts/docs.sh`) has 47
worked recipes, each run for real; `src/examples/tree_tour.clj` is a
walkthrough to evaluate form by form.

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
(def tctx (t/tctx riff))       ; an atom of settings, every param at its default
(t/describe tctx)               ; key, value, range, default, algo, doc
(t/setp! tctx :k 5)        ; checked against 0..32
(t/run riff tctx)
(t/trace riff tctx)             ; every node's result
```

- **Checked when built:** a wrong child fails at once, e.g.
  `(gate (tilt indisp) scale)` → "gate: child 1 should be :grid".
- **Swapping a stage** is editing the expression; combining two sources
  is a second child.
- **Two instances of one algo:** name one, `(euclid :as :bass)` →
  `:bass/k`.
- **Tree and tctx are separate:** one tree runs against several tctxs,
  and `(t/fit! tctx other-tree)` prepares a tctx for another tree.

## Three ways to sound

- **Compose by drag and drop:** `(build-tree)` opens a canvas and a
  pane of categories -> algos; drop algos onto the tree's open slots
  (only fitting ones are accepted), then Finalize returns `[tree tctx]`.
  `(build-tree :repl)` does the same step by step at the REPL.
- **A window for it:** `(gui tree)` (or `(gui tctx)`, `(gui tree tctx)`)
  opens a settings window — a control per param, a live result preview,
  Play once / Live as — and returns the tctx.
- **Once:** `(t/play! riff tctx)`, or `(play (t/run riff tctx))`. `notes`/
  `pair-notes` produce Leaf/Rest maps `play` walks as a plain Form.
- **Committed:** wrap the same Leaf/Rest maps in a container and
  `core.repo/commit-node!` it, to address it by id like `.mus` material.
- **Live:** a name binds a tree and a tctx in `core.wall`'s registry, so
  any voice can follow it, and every change to the tctx is heard on the
  next note:

  ```clojure
  (t/live! :riff riff tctx)                   ; an endless voice
  (t/setp! tctx :k 3)
  (t/retree! :riff (notes (shuffled scale)))  ; same tctx, fitted to the new tree
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
| `assign-algo!`, per-voice dispatch | `core.engine`, `core.events` |
| Real domain nodes (`d/leaf`, `d/rest*`, ...) | `core.domain.flat-domain` |

## What's in `algo/`

Two generated listings, so neither can go stale:

- **Every tree algo** with its inputs, output and params (range and
  default): `(t/algos)` at the REPL, or the reference tables in the
  cookbook (`doc/algo-cookbook.html`), which are built from the registry.
- **Every `algo/` function**, tree algo or helper, by subdirectory, with
  its docstring: `(show-algos)` at the REPL.

```clojure
(show-algos)                                  ; every category, one-line glosses
(show-algos "rhythmic")                       ; just that category
(show-algos "rhythmic" "euclidean-rhythm")    ; that one function's full doc
```

The subdirectories: `common/` (shared helpers: scales, gating, reshaping,
rotation, trig samplers, filters), `indisp/` (Barlow indispensability),
`melodic/` (melody generators, counterpoint, Slonimsky), `metric/` (pulse
grids from numbers), `random.clj` + `random/` (the RNG, distributions,
walks, chaotic maps), `rhythmic/` (rhythm generators, from Euclidean to
tala), `tree.clj` + `tree/` (composition itself), and `logic/` (the
registry as core.logic facts, below).

### Asking the registry

`algo.logic.tree` (`lt` at the REPL) answers questions about the algos
from their declared types, so the answers never go out of date:

```clojure
(require '[clojure.pprint :refer [print-table]])
(print-table [:short :category :in :out :params]
             (lt/find-algos {:category "rhythmic" :param :k}))  ; also :in :out :short
(map lt/show (lt/how :grid :notes 3))   ; smallest trees from a grid you have to notes
(t/run (lt/->tree (first (lt/how :grid :notes 1))) {:input [1 0 1 1]})
(lt/feeds euclid)                       ; what can take euclid's output
(lt/why-not :scale :grid)               ; why it doesn't fit, and a bridge if one exists
(map lt/show (lt/examples :gate))       ; small complete trees with gate at the root
(lt/show (lt/surprise :notes))          ; a random tree giving notes (follows the seed)
```

The tree builder uses the same facts: a hole under a `:same` node (say
`cycled`) takes the type that node's own slot wants, so only fitting
algos are offered there.
