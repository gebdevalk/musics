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
- **`:model`** (a trained Markov model, for `markov-gen`), **`:part`**
  (parallel parts; `layer` picks one) and **`:index`** (one weighted
  choice, from `pick`).

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
`zip` or a blend step) -- except `:index`, which nothing takes, and
`:part`, whose taker `layer` gives `:any`, so the types lose track
there; take a layer, and it is a stream of its own type again.

**4. Gaps.**

- **`:instrument` has no producer**: instruments come from literals
  (`(cycle> ["violin" "flute"])`).
- **`:volume` and `:articulation`** come only from weights.
- **`:stroke`** comes only from `konnakol`.

**5. Ten algos still give `:any`** (step 4 of the leaf principle).
Run with their defaults, they give:

| algo | takes | gives (observed) | suggestion |
|---|---|---|---|
| `markov-rhythm` | — | `0 1 0 1 ...` | `:pulse` |
| `text-rhythm` | — | `1 0 1 1 ...` | `:pulse` |
| `trend-rhythm` | number | `0 0 0 0 0 2 ...` (0, 1, 2) | `:pulse` if a 2 is an accented onset, else `:number` |
| `tiling` | pulse, pulse | `[[1 0 1 1 ...] false]` | give the grid alone (`:pulse`), the flag separately |
| `pocket` | pulse | `{:time :accent :beat-position}` maps | a timed-event type (suggestion 2) |
| `humanize` | onset | `{:time :velocity :original-time}` maps | the same |
| `tala` | — | `{:matra :vibhag :accent :time}` maps | the same |
| `djembe` | — | `{:time :stroke :accent}` maps | the same, or `:stroke` |
| `patch` | — | `{:pitch :velocity :duration :bend}` maps, some `nil` | a note-event type, or `:leaf` through a converter |
| `chain` | — | `67 60 67 60 ...` (its states) | stays `:any`: it walks whatever states it is given |
| `layer` | part | `1 0 0 0 1 0 ...` (one layer) | stays `:any`: a layer is whatever the layers hold |

Four of these return maps with a `:time` (timed events with accents or
strokes), which no type covers; with a bridge to durations or onsets
they would reach leaves.
