# Bridges between algo types

Every algo tree ends in **leaves** (notes, chords, rests, drums), made
from end material: duration, pitch, volume, articulation, instrument
(`doc/algo-audit.md`, the leaf principle). A type names one value; a
tree carries streams of them. A **bridge** turns a raw type into end
material, one way only (`musics.algo.bridge`; stream bridges are named in the
plural, `pulses->durations`). This page shows how the types connect,
read from the registry through `musics.algo.logic.tree` (core.logic):

```clojure
(lt/steps :pulse :leaf)            ; 2 -- the fewest algos between two types
(map lt/show (lt/how :number :leaf 2))
;; ((zip (numbers->durations input) (grammar)) (zip (numbers->durations input) (int-arcsine)))
```

## The types

**End material**, what a leaf is made of:

- **`:duration`**: how long a note lasts, as a note value (`1/4` a
  quarter), or a Rest for silence. From `pulses->durations`,
  `onsets->durations`, `numbers->durations`, `strokes->durations`,
  `points->durations`, `bisect`, `split`.
- **`:pitch`**: a MIDI note, a collection of them (a chord), or nil (a
  rest). From the melodic generators, `scale`, the integer samplers and
  `degrees->pitches`/`numbers->pitches`/`points->pitches`.
- **`:volume`** (0–100), **`:articulation`** (a name from
  `musics.common.music-data/articulations`) and **`:instrument`** (a MIDI
  program, a General MIDI name or a drum): blended onto leaves by
  `+volume`, `+articulation` and `+instrument`.

**The product**: **`:leaf`**, from `zip` (duration + pitch), the blend
steps, and the generators that make leaves themselves (`drums`,
`counterpoint`, `species`).

**Raw types**, which reach end material through a bridge:

- **`:pulse`**: 0/1 per pulse, the onset grid most rhythm generators
  make; `pulses->durations` makes durations of it.
- **`:onset`**: event times (seconds or beats); `onsets->durations`.
- **`:number`**: plain numbers (samplers, walks, chaos, noise);
  `numbers->durations`, `numbers->pitches`, or `scale>` then
  `degrees->pitches`; `threshold` makes pulses of them.
- **`:point`**: a vector per step (`henon`, `lorenz`);
  `points->pitches`, `points->durations`, or `axis` → `:number`.
- **`:weight`**: a weight per pulse (indispensability);
  `weights->pulses`, `weights->volumes`, `weights->articulations`.
- **`:stroke`**: syllables (`konnakol`); `strokes->durations`.
- **`:layer`**: pulse layers meant to sound together (`polyrhythm`,
  `polymeter`, `hemiola`, `african`, `duet`, `phases`); `layer` picks
  one as `:pulse` -- name each pick (`:as`) to route each layer into
  its own part.
- **`:part`**: parallel parts, each a stream of leaves (`drums`,
  `counterpoint`, `species`, and `parts`/`+part`, which join leaf
  streams); playable as they are, `part` picks one back as `:leaf`.
- **`:model`** (a trained Markov model, for `markov-gen`) and
  **`:index`** (one weighted choice, from `pick`).

## The table

The fewest algos in a chain from the row's type to the column's; the
other inputs of those algos may come from anywhere. `·` the same type,
`–` no chain within four. Regenerate it with `lt/steps` over every pair.

| from \ to | articulation | duration | index | instrument | leaf | model | number | onset | part | pitch | point | pulse | stroke | volume | weight |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| **articulation** | · | – | – | – | 1 | – | – | – | – | – | – | – | – | – | – |
| **duration** | – | · | – | – | 1 | – | – | – | – | – | – | – | – | – | – |
| **index** | – | – | · | – | – | – | – | – | – | – | – | – | – | – | – |
| **instrument** | – | – | – | · | 1 | – | – | – | – | – | – | – | – | – | – |
| **leaf** | – | – | – | – | · | – | – | – | – | – | – | – | – | – | – |
| **model** | – | – | – | – | 2 | · | – | – | 2 | 1 | – | – | – | – | – |
| **number** | 3 | 1 | 3 | – | 1 | 2 | · | 2 | 2 | 1 | – | 1 | 2 | 3 | 2 |
| **onset** | – | 1 | – | – | 2 | – | – | · | – | – | – | – | – | – | – |
| **part** | – | – | – | – | – | – | – | – | · | – | – | – | – | – | – |
| **pitch** | – | – | – | – | 1 | 1 | – | – | 1 | · | – | – | – | – | – |
| **point** | 4 | 1 | 4 | – | 2 | 2 | 1 | 3 | 2 | 1 | · | 2 | 3 | 4 | 3 |
| **pulse** | 2 | 1 | 2 | – | 2 | – | – | 1 | 1 | – | – | · | 1 | 2 | 1 |
| **stroke** | – | 1 | – | – | 2 | – | – | – | – | – | – | – | · | – | – |
| **volume** | – | – | – | – | 1 | – | – | – | – | – | – | – | – | · | – |
| **weight** | 1 | 2 | 1 | – | 2 | – | – | 2 | 2 | – | – | 1 | 2 | 1 | · |

How many algos give and take each type:

| type | given by | taken by |
|---|:-:|:-:|
| articulation | 1 | 1 |
| duration | 7 | 1 |
| index | 1 | 0 |
| instrument | 0 | 1 |
| leaf | 5 | 4 |
| model | 1 | 1 |
| number | 26 | 7 |
| onset | 10 | 2 |
| part | 13 | 1 |
| pitch | 22 | 9 |
| point | 2 | 3 |
| pulse | 30 | 17 |
| stroke | 1 | 1 |
| volume | 1 | 1 |
| weight | 4 | 6 |

## What the table shows

**1. One way.** Every end material reaches `:leaf` in one step, and none
reaches another end material: no duration becomes a pitch, no pitch a
duration. `pitch → model` and `pitch → part` are generators that take a
melody (`markov-train`, `counterpoint`), not conversions.

**2. Rhythm never makes pitches, nor pitches rhythm.** The `pulse` row
has no `pitch`, the `pitch` row no `pulse`. Durations and pitches meet
only in `zip`.

**3. Every raw type reaches leaves within two steps** (a bridge, then
`zip` or a blend step) -- except `:index`, which nothing takes;
`:layer` takes one more (`layer`), and `:part` is already leaves.

**4. Gaps.**

- **`:instrument` has no producer**: instruments come from literals
  (`(cycle> ["violin" "flute"])`).
- **`:volume` and `:articulation`** come only from weights.
- **`:stroke`** comes only from `konnakol`.

**5. No algo gives `:any`** (step 4 of the leaf principle). The ones
that did found their types:

| algo | gives | how |
|---|---|---|
| `markov-rhythm` | `:pulse` | its 0/1 states |
| `tiling` | `:pulse` | the laid grid (the plain `rhythmic-tiling` keeps the tiled? flag) |
| `text-rhythm`, `trend-rhythm` | `:weight` | accent levels (2/1/0, -1 for a falling trend), for `weights->pulses`, `->volumes`, `->articulations` |
| `tala` | `:weight` | the accent per matra (3 sam, 2 tali, 1 khali) |
| `chain` | `:number` | its states, scale degrees by default, for `degrees->pitches` |
| `djembe` | `:leaf` | Drum leaves: bass, tone, slap on the conga keys, accents as articulation |
| `pocket` | `:leaf` from `:leaf` | each note laid back by its place in the beat, as its own `:micro` |

`layer` takes `:layer` and gives `:pulse`; the sets of alternatives
(`necklace`, `bracelet`, `necklaces`, `crossover`) give the one pattern
their `:index` picks.

`humanize` and `patch` are plain functions now: the `:humanization`
context key humanizes per note, and `patch`'s timed events with pitch
bend have no place in a tree.
