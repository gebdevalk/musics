# Algorithms in `musics`: how they reach sound

Every generative function under `musics/algo/` is a plain Clojure function,
and every one in `musics/algo/{indisp,metric,melodic,random,rhythmic}` is also
a tree algo (its own `:algo` metadata; `(musics.algo.tree/algos)` lists all of
them). `musics.algo.tree` is the one way to combine them and to play them, live
or not.

This page is the guide in code: how an algo is written and registered, and
how trees are built, set and run in Clojure (in `musics.algo.*`, tests and
scripts). In mu every algo is a word (`duration/euclid`, `pitch/walk`), and
`doc/md/algo-tree-tutorial.md` shows how to combine them, from words to
recipes playing live. `CLAUDE.md`'s "Simple composition: `musics.algo.tree`"
section is the reference; `doc/html/algo-cookbook.html` (a PDF via
`scripts/docs.sh`) has 53 worked recipes, each run for real.

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

Then `(musics.algo.tree/expose ns/the-fn)` defines its constructor under the
short name (`expose-ns` does every annotated fn of a namespace — how
`musics.algo.tree.lib` exposes `musics/algo/`). For a new function, `defalgo` does both at once (the raw fn
is kept as `name*`):

```clojure
(t/defalgo up "Shift pitches."
  {:algo {:in [:pitch] :out :pitch
          :params {:by {:type :int :min -48 :max 48 :default 12}}}}
  [pitches by] (map #(some-> % (+ by)) pitches))
```

## Compose, set, run

```clojure
(require '[musics.algo.tree :as t] '[musics.algo.tree.lib :refer :all])

(def riff (zip (pulses->durations euclid) (up (cycle> scale))))   ; #node (zip (pulses->durations (euclid)) ...)
(def tctx (t/tctx riff))       ; its settings, {:params-atom a}: empty, so every param at its default
(t/describe tctx)               ; key, value, range, default, algo, doc
(t/setp! tctx :k 5)        ; checked against 0..32
(t/run riff tctx)
(t/trace riff tctx)             ; every node's result
```

- **Checked when built:** a child of the wrong type gets the smallest
  chain of bridges that turns it into what its slot wants --
  `(zip euclid scale)` is built as `(zip (pulses->durations (euclid))
  (scale))`, `(zip (tilt indisp) scale)` with `weights->pulses` too --
  and where no chain does, it fails at once: `(zip [1/4] (pick indisp))`
  → "zip: child 2, (pick (indisp)), gives :index, not a part of a
  note". The composer places an algo inside its bridges the same way.
- **Swapping a stage** is editing the expression; combining two sources
  is a second child.
- **Two instances of one algo:** name one, `(euclid :as :bass)` →
  `:bass/k`.
- **Tree and tctx are separate:** one tree runs against several tctxs,
  and `(t/fit! tctx other-tree)` prepares a tctx for another tree.
- **Settings are their own:** a tctx holds an algo's settings under the
  names an `@^{ }` writes (`"k"`, `"euclid.k"`, `"bass.k"`), apart from
  the music's context. `run` takes a tctx, a plain map, or a vector of
  them nearest first (`(t/run riff [mine written])`); a param none of
  them sets takes its default. In each the most specific name wins
  (`bass.k` before `euclid.k` before `k`), once per run. Only a param
  that means a music key reads the music: `degrees->pitches` plays in
  the `!key:` of the contexts given as `run`'s chain, unless the
  settings give a `key`.

## Playing a tree

In mu:

- **A word, or a recipe:** every algo is a word, and a tree is a run of
  words, or a tree recipe `@<< … >>`. `run` puts a finite result on the
  stack, and a play word plays it: `>`, or live from nothing with
  `| '( … ) :riff >`. `doc/md/algo-tree-tutorial.md` goes through it.
- **Compose by drag and drop:** `compose` opens a canvas and a pane of
  shelves -> algos (what each delivers: pitch, duration, volume,
  articulation, leaf, parallel, tools, and the workshop of raw material).
  You drop algos onto the tree's open slots (only fitting ones are
  accepted), and Finalize leaves the tree on the stack as a recipe:
  `compose :riff >`.
- **A window for it:** `:riff gui` opens a live voice's settings, a
  control per param; the GUI's Wall window shows every live voice's.
- **Live:** a voice playing a recipe plays each next note of what the
  recipe gives now: `:riff @^{ k:3 } set` is heard on the next note,
  `'( … ) :riff retree` swaps the recipe and keeps the settings and the
  position, and `:riff stop` ends it.

In code, the same steps are `musics.algo.tree`'s:

```clojure
(t/play! riff tctx)                         ; once
(t/live! :riff riff tctx)                   ; an endless voice; the name is bound in musics.wall
(t/setp! tctx :k 3)                         ; heard on the next note
(t/retree! :riff (zip (pulses->durations euclid) (shuffle> scale)))  ; same tctx, fitted
(t/stop! :riff)
```

A tree ends in leaves (Leaf/Rest/Drum maps), which the engine walks as
a plain Form; a part (drums, counterpoint) plays its voices at once. A
tree that reads `:nodes` transforms a voice's own notes instead, which
is what a mu recipe with music under it does (`[c d e] '( 5 + ) :up >`).

## Where things live

| What | Namespace |
|---|---|
| Trees, tctx, `run`/`trace`/`describe`, `defalgo`/`expose`, live entry points | `musics.algo.tree` |
| Introspection and the short ↔ full registry | `musics.algo.tree.registry` |
| Ready-made algos (`euclid`, `scale`, `zip` and `as-part`, the tools, `transpose`, indispensability, ...) | `musics.algo.tree.lib` |
| Bridges (`pulses->durations`, `degrees->pitches`, ...) | `musics.algo.bridge` (exposed through `musics.algo.tree.lib`) |
| Live playback by name | `musics.algo.tree.live` |
| The name -> wall fn registry the engine reads per note | `musics.wall` |
| `assign-algo!`, per-voice dispatch | `musics.engine`, `musics.events` |
| Real domain nodes (`d/leaf`, `d/rest*`, ...) | `musics.domain` |

## What's in `musics/algo/`

Every tree algo, read from the registry, so the listing can't go stale:
in mu, `words` lists the algo words by shelf and `"pitch/walk" see` says
what one does and which settings it reads; the cookbook's reference
tables (`doc/html/algo-cookbook.html`) list them all. In code,
`musics.algo.tree/show-algos` prints the same:

```clojure
(show-algos)              ; every shelf, one line per algo
(show-algos "pitch")      ; one shelf
(show-algos :euclid)      ; one algo: doc, types and params
```

The subdirectories: `musics/common/` (shared helpers: scales, gating, reshaping,
rotation, trig samplers, filters), `indisp/` (Barlow indispensability),
`melodic/` (melody generators, counterpoint, Slonimsky), `metric/` (pulse
grids from numbers), `random.clj` + `random/` (the RNG, distributions,
walks, chaotic maps), `rhythmic/` (rhythm generators, from Euclidean to
tala), `tree.clj` + `tree/` (composition itself), and `logic/` (the
registry as core.logic facts, below).

### Species counterpoint

`musics.algo.logic.counterpoint` writes two- to four-part counterpoint after
Jeppesen against a cantus you give, in any of the five kinds, and
checks counterpoint against the same rules:

```
[D4/1 F E D G F A G F E D] @^{ voices:3 kind:2 } parallel/species >
```

In mu, `parallel/species` takes the cantus from the stack, its settings
on top. In code:

```clojure
(require '[musics.algo.logic.counterpoint :as cp])
(def r (cp/counterpoint {:cantus [62 65 64 62 67 65 69 67 65 64 62] :voices 3 :kind 2}))
(cp/check r)                 ; [] -- every hard rule kept
(cp/->mus r :cpt)            ; musics text, spelled in the mode
```

As a tree algo: `(species cantus)` with `:voices`/`:kind`/... params.
The rules and how the search works: [counterpoint.md](counterpoint.md).

### Bridges and leaves

Every tree ends in leaves (notes, chords, rests, drums), the
**product**, made from **half products**: duration, pitch, volume,
articulation, instrument. A **bridge** (`musics.algo.bridge`) turns
**raw material** (numbers, pulses, onsets, points, weights) into a half
product, one way only. A type names one value (`:pitch`); a tree carries **streams** of
them, so the bridges in a tree are stream bridges, named in the plural.
`zip` joins a note's parts -- durations and pitches into leaves, and
any more parts written on them:

```clojure
(def mel (zip (pulses->durations euclid)
              (degrees->pitches (cycle> [0 2 4 7]))
              (cycle> (weights->articulations indisp))
              (cycle> (weights->volumes indisp))))
(notes->mus (t/run mel {:k 5 :n 8 :key "D.major"}))
;; "[ !acc:explicit D4/8-^\\volume:80.0 F#4/16\\ghost\\volume:40.0 A4/8\\volume:54.5… D5/16->\\volume:69.0… D4/8\\ghost\\volume:47.2… ]"
```

| Stream bridge | From → to | Works |
|---|---|---|
| `pulses->durations` | each onset lasts until the next (`:pulse` = one pulse's note value); 0s lengthen, leading 0s are one rest | by shape |
| `strokes->durations` | a syllable starts a note, "-" lengthens it | by shape |
| `onsets->durations` | the time between onsets, as note values | whole stream |
| `numbers->durations`, `points->durations` | each value's place in `:from-lo..:from-hi` as a note value in `:lo..:hi` | value by value |
| `degrees->pitches` | scale steps of `:key` (as `!key:` writes it) from the tonic in `:octave` | value by value |
| `numbers->pitches`, `points->pitches` | each value's place in `:from-lo..:from-hi` onto `:lo..:hi`, or onto a key's steps | value by value |
| `weights->pulses` | the strongest `:density` of the pulses on | whole stream |
| `numbers->volumes`, `points->volumes` | each value's place in `:from-lo..:from-hi` as a volume in `:lo..:hi` (0–100) | value by value |
| `weights->volumes`, `weights->articulations` | a meter's weights as volumes (0–100) or articulation names | whole stream |

- **How a bridge works** is in its metadata (`:works`) and in
  `(show-algos :pulses->durations)`:
  - **value by value** and **by shape** are lazy, so an endless stream
    (a `cycle>` source, a walk) can feed them;
  - **whole stream** reads everything first, so give it a finite one and
    cycle its result.
- **Value bridges** convert one value and are named in the singular:
  `degree->pitch`, `number->pitch`, `number->duration`, `number->volume`. The stream
  bridges map them; call them directly in code.
- **`zip`** joins two children at a time; more fold from the left,
  `(zip d p v)` being `(zip (zip d p) v)`, and print as written.
  - **Durations and pitches** give leaves: a collection is a chord and
    nil a rest. A Rest in the durations, or a nil pitch, uses the other
    values unwritten, so every stream's n-th value belongs to the n-th
    note. It ends with the shortest stream, so cycle a source for an
    isorhythm.
  - **Leaves and a part** give the leaves with that part written on,
    replacing their own: an articulation from
    `musics.common.music-data/articulations` (`:accent`, `:staccato`,
    `:ghost`, ..., as `c4->` does), a volume (0–100, as `c4\vol:90`), an
    instrument (a MIDI program 0–127 or a General MIDI name, as
    `c4\i:40`), a drum (the note made that drum), or any other key
    playback reads (`:panning`, `:transposition`, `:Tempo`, ..., as
    `c4\pan:-1.0` does).
  - **Anything else** gives a bundle of the parts, typed by them
    (`[:bundle :pitch :volume]`), for a later zip to join.
  - A child's part is its type (`weights->volumes` gives volumes); a
    literal gives durations, then pitches, and `(as-part :articulation
    (cycle> [:staccato]))` names another -- `:leaf` for notes, or any
    key above.
- These are the note's own overrides, the same thing a written
  `\name:value` holds. `notes->mus` writes them back, so generated
  leaves read back the same from text.
- **Rests** take no volume, articulation or instrument.

### Algos as half products

Pitch, duration, volume and articulation are **half products**; leaves,
lines and parallels **products**; numbers, pulses, onsets, points and
weights **raw material**. An algo is chosen by what it delivers, not by
how it works inside: every source of raw material is also offered as
each half product a bridge makes of it, a **specialised algo**
(`musics.algo.tree.half-products`), named by that half product:

```clojure
(def riff (zip (duration/euclid) (pitch/lorenz)))     ; euclid as durations, lorenz as pitches
(t/run riff {:k 5 :n 16})
(def coupled (zip (cycle> [1/8]) (pitch/walk :as :w)
                  (volume/walk :as :w)))             ; one :as, one run: higher is louder
```

- **Names:** `pitch/<source>`, `duration/<source>`, `volume/<source>`,
  `articulation/<source>`; a raw type's second bridge to the same half
  product adds its input word: `pitch/walk` maps a range,
  `pitch/walk-degrees` takes scale steps. The name is its registry key
  (`:duration/euclid`) and its mu word (`duration/euclid`).
- **Params:** the source's, then the bridge's, prefixed with the half
  product (`:pitch-lo`, `:pitch-key`, `:duration-pulse`). The input
  range (`:pitch-from-lo`/`:pitch-from-hi`) defaults to what the source
  gives at its own defaults, so the defaults already sound.
- **Coupled half products:** two specialised algos with the same `:as`
  and source read one run of it (x as pitch and y as volume of one
  Lorenz attractor: `:pitch-axis 0`, `:volume-axis 1`).
- **Varying durations:** every algo that varies, elaborates or
  continues pulses is offered on durations too -- `duration/emi`,
  `duration/mutate`, `duration/oblique`, `duration/rnn`,
  `duration/tuplets`, `duration/necklace`, `duration/bracelet`,
  `duration/crossover`, `duration/tiling`. Its durations are laid on a
  grid of `:duration-pulse` (each rounds to whole pulses), varied, and
  read back as durations: `(zip (duration/emi (duration/euclid)) (cycle>
  scale))`. It reads the whole stream, so give it a finite one.
- **The catalogue** (mu's `words`, `compose`'s pane, `show-algos` in code) shows algos
  by shelf: what they deliver (`pitch`, `duration`, `volume`,
  `articulation`, `leaf`, `parallel`), `tools`, and the **workshop**
  with everything that takes or gives raw material -- raw sources,
  bridges, `tilt`, `threshold` -- still usable, just out of the way.

### Tools

Tools work within one type, on any material, and are marked `>` so they
never shadow `clojure.core`:

| Tool | Does |
|---|---|
| `cycle>`, `shuffle>` | repeat forever / reshuffle every pass (lazy) |
| `take>` | the first `:len` items |
| `map>`, `filter>` | each item through `:fn` / the items `:fn` keeps (`:fn` set in code; in mu, `map` and `filter` take a quotation) |
| `remove>` | the items `:fn` doesn't drop; on leaves the line gets shorter |
| `mute>` | leaves: each note `:fn` is true for becomes a rest of its duration, the rhythm stays |
| `scale>` | numbers from `:from-lo..:from-hi` onto `:to-lo..:to-hi` (lazy) |
| `stretch>` | every duration times `:factor` |

A test reads one material (`#(< % 70)` on pitches) or the whole leaf
(`#(and (:pitches %) (< (:duration %) 1/8) (< 72 (first (:pitches %))))`,
short high notes): `(mute> riff)` with that `:fn` thins a melody and
keeps its pulse.

A number range onto a scale is `scale>` then `degrees->pitches`:
`(degrees->pitches (scale> walk))` with `:from-lo 55 :from-hi 65 :to-hi 7`
puts a walk between 55 and 65 onto one octave of steps.

### Drum grooves

`(drums)` (`musics.algo.rhythmic.drums/drum-pattern`) makes a drum-kit groove of
`:bars` bars of 4/4. The `:style` param is one of `:rock` `:pop` `:funk`
`:hiphop` `:trap` `:jazz` `:house` `:techno` `:metal`. Each groove has a
backbone, time-keeping, ghost notes, and a fill every 4th bar whose
crash lands on the next downbeat. The pattern loops: after the last bar,
that next downbeat is bar 1's. `:density` thins the hats and ghost notes,
`:swing` delays the off-beat 16ths by up to a 32nd, and `:seed` makes it
reproducible. The result is a `par` group with one layer per kit piece,
each layer a vector of Drum/Rest maps, so it plays as a whole:

```
@^{ style:funk swing:0.2 } parallel/drums >
@^{ style:jazz bars:4 } parallel/drums [~] >
```

In code: `(t/play! (drums) {:style :jazz :bars 4})`.

Each hit is ghosted, plain, accented or marcato: the same accents
a written drum takes (`x8\38\ghost`, `x8\38->`, `x8\38-^`). They are
offsets on the context's volume, so `!f` around a groove still makes it
louder, and `part->mus` writes a groove out as text that reads back
the same.
Timing and velocity spread come from `:humanization`.

### Asking the registry

`musics.algo.logic.tree` (`lt` below) answers questions about the algos
from their declared types, so the answers never go out of date. It is
asked in code:

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
`cycle>`) takes the type that node's own slot wants, so only fitting
algos are offered there. `lt/steps` gives the fewest algos between two
types; [bridge-table.md](bridge-table.md) (and `bridge-table.pdf`)
tabulates it for every pair, with the gaps and what would close them.
