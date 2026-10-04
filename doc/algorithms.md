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
  {:algo {:short :density :in [:weight] :out :pulse
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
  {:algo {:in [:pitch] :out :pitch
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
  `(gate (tilt indisp) scale)` → "gate: child 1 should be :pulse".
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

Every tree algo, read from the registry, so the listing can't go stale:
`(show-algos)` at the REPL, or the reference tables in the cookbook
(`doc/algo-cookbook.html`). Helper functions that aren't algos are found
through `doc` and their namespaces.

```clojure
(show-algos)              ; every category, one line per algo
(show-algos "rhythmic")   ; one category
(show-algos :euclid)      ; one algo: doc, types and params
```

The subdirectories: `common/` (shared helpers: scales, gating, reshaping,
rotation, trig samplers, filters), `indisp/` (Barlow indispensability),
`melodic/` (melody generators, counterpoint, Slonimsky), `metric/` (pulse
grids from numbers), `random.clj` + `random/` (the RNG, distributions,
walks, chaotic maps), `rhythmic/` (rhythm generators, from Euclidean to
tala), `tree.clj` + `tree/` (composition itself), and `logic/` (the
registry as core.logic facts, below).

### Species counterpoint

`algo.logic.counterpoint` writes two- to four-part counterpoint after
Jeppesen against a cantus you give, in any of the five kinds, and
checks counterpoint against the same rules:

```clojure
(require '[algo.logic.counterpoint :as cp])
(def r (cp/counterpoint {:cantus [62 65 64 62 67 65 69 67 65 64 62] :voices 3 :kind 2}))
(cp/check r)                 ; [] -- every hard rule kept
(parse (cp/->mus r :cpt))    ; then (play :cpt)
```

As a tree algo: `(species cantus)` with `:voices`/`:kind`/... params.
The rules and how the search works: [counterpoint.md](counterpoint.md).

### Glue and leaves

Every tree ends in leaves (notes, chords, rests, drums), made from end
material: dur, pitch, volume, articulation, instrument. Glue
(`algo.glue`) turns a raw type into end material, one way only. `zip`
zips durations and pitches into leaves, and each blend step adds one more material:

```clojure
(def mel (+volume (+articulation (zip (pulse->dur euclid)
                                      (degree->pitch (cycled [0 2 4 7])))
                                 (cycled (weight->articulation indisp)))
                  (cycled (weight->volume indisp))))
(notes->mus (t/run mel {:k 5 :n 8 :key "D.major"}))
;; "[ !acc:explicit D4/8 F#4/16 A4/8 D5/16 D4/8 ]"
```

| Glue | From → to |
|---|---|
| `weight->pulse` | the strongest `:density` of the pulses on |
| `pulse->dur` | each onset lasts until the next (`:pulse` = one pulse's note value); 0s lengthen, leading 0s are one rest |
| `onset->dur`, `number->dur`, `stroke->dur`, `point->dur` | times, numbers, syllables or one coordinate as note values |
| `degree->pitch` | scale steps of `:key` (spelled as `!key:` writes it) from the tonic in `:octave` |
| `range->pitch`, `point->pitch` | a range onto `:lo..:hi`, or onto a key's steps |
| `weight->volume`, `weight->articulation` | accent levels as volumes (0–100) or articulation names |

- **`zip`** zips the two streams into leaves: a collection is a chord and nil a
  rest. A Rest in the durations uses no pitch. It ends with the shorter
  stream, so cycle a source for an isorhythm.
- **`+articulation`** sets a name from `common.music-data/articulations`
  (`:accent`, `:staccato`, `:ghost`, ...), as `c4->` does.
- **`+volume`** sets each note's own volume (0–100), overriding the
  context's, as `c4\vol:90` does.
- **`+instrument`** sets a MIDI program (0–127) or a General MIDI name
  (`c4\i:40`). A drum name (or a number with `:drum?`) turns the note
  into that drum.
- **`+override`** sets any other key playback reads, chosen by `:key`
  (`:panning`, `:transposition`, `:Tempo`, ...), as `c4\pan:-1.0` does.
- These are the note's own overrides, the same thing a written
  `\name:value` holds. `notes->mus` writes them back, so generated
  leaves read back the same from text.
- **Rests** take no volume, articulation or instrument.
- **Endless input:** glue that works value by value is lazy. Glue that
  maps a range needs a finite input, so cycle its result instead.

### Drum grooves

`(drums)` (`algo.rhythmic.drums/drum-pattern`) makes a drum-kit groove of
`:bars` bars of 4/4. The `:style` param is one of `:rock` `:pop` `:funk`
`:hiphop` `:trap` `:jazz` `:house` `:techno` `:metal`. Each groove has a
backbone, time-keeping, ghost notes, and a fill every 4th bar whose
crash lands on the next downbeat. The pattern loops: after the last bar,
that next downbeat is bar 1's. `:density` thins the hats and ghost notes,
`:swing` delays the off-beat 16ths by up to a 32nd, and `:seed` makes it
reproducible. The result is a `par` group with one layer per kit piece,
each layer a vector of Drum/Rest maps, so it plays as a whole:

```clojure
(play (t/run (drums) {:style :funk :swing 0.2}))
(t/play! (drums) {:style :jazz :bars 4})
```

Each hit is ghosted, plain, accented or marcato: the same accents
a written drum takes (`x8\38\ghost`, `x8\38->`, `x8\38-^`). They are
offsets on the context's volume, so `!f` around a groove still makes it
louder, and `part->mus` writes a groove out as text that reads back
the same.
Timing and velocity spread come from `:humanization`.

### Asking the registry

`algo.logic.tree` (`lt` at the REPL) answers questions about the algos
from their declared types, so the answers never go out of date:

```clojure
(require '[clojure.pprint :refer [print-table]])
(print-table [:short :category :in :out :params]
             (lt/find-algos {:category "rhythmic" :param :k}))  ; also :in :out :short
(map lt/show (lt/how :pulse :leaf 3))   ; smallest trees from a grid you have to notes
(t/run (lt/->tree (first (lt/how :pulse :leaf 1))) {:input [1 0 1 1]})
(lt/feeds euclid)                       ; what can take euclid's output
(lt/why-not :scale :pulse)               ; why it doesn't fit, and a bridge if one exists
(map lt/show (lt/examples :gate))       ; small complete trees with gate at the root
(lt/show (lt/surprise :leaf))          ; a random tree giving notes (follows the seed)
```

The tree builder uses the same facts: a hole under a `:same` node (say
`cycled`) takes the type that node's own slot wants, so only fitting
algos are offered there. `lt/steps` gives the fewest algos between two
types; [bridge-table.md](bridge-table.md) (and `bridge-table.pdf`)
tabulates it for every pair, with the gaps and what would close them.
