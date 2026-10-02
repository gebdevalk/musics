# Bridges between algo types

Every tree algo declares what it takes and what it gives (`:in`, `:out`
in its `:algo` metadata): 13 data types, from `:grid` to `:notes`. A
**bridge** is a chain of algos that turns one type into another — what
you need when you have a grid and want notes, or a melody and want a
rhythm. This page shows which bridges exist, which are missing, and
what would close the gaps.

The numbers come from the registry itself, through `algo.logic.tree`
(core.logic), so they can be checked or regenerated at the REPL:

```clojure
(lt/steps :grid :notes)            ; 2 -- the fewest algos between two types
(map lt/show (lt/how :grid :notes 3))
;; ((notes (gate input (grammar))) (notes (gate input (int-arcsine))) ...)
```

Counted: algos with a declared type. Not counted: the 11 `:same` algos
(`cycled`, `head`, `transpose`, ...), which keep whatever type they are
given and so never turn one type into another, and the 11 algos whose
output is `:any` (point 4), which the type rule lets go anywhere but
which say nothing about what they give.

## The types

What travels between algos is plain Clojure data; the type says which
shape and meaning it has. Each example below is the real output of the
algo named, run with its defaults.

**Rhythm**

- **`:grid`** — a pulse grid: one cell per equal step (say, a
  sixteenth), `1` = an onset on that step, `0` = silence. Where it
  sits in time comes later, from the note length the `notes` algo is
  given. `(euclid)` → `1 0 0 1 0 0 1 0` (3 onsets in 8 steps).
- **`:onsets`** — when things happen, as times rather than steps: a
  rising list of numbers in an abstract time unit (how long one unit
  is gets decided when they become durations; `gaps` takes it as a
  quarter note by default). Not tied to a grid, so onsets can fall
  anywhere. `(poisson)` → `0.52 1.51 1.53 1.61 2.10 ...` (random
  arrivals); `(swing (all-interval))` → `0.0 0.6 3.6 5.0 ...`.
- **`:durations`** — how long things last, as note values: fractions
  of a whole note, the same as musics text (`1/4` a quarter, `1/8` an
  eighth). `(bisect)` → `1/2 1/2`; `(gaps (birdsong))` → `1/32 1/32
  1/8 ...` (the time between successive onsets).
- **`:strokes`** — a drum-language line, one syllable per step, `-`
  for a rest. `(konnakol (all-interval))` → `"Ta" "-" "-" "Ka" "Di" ...`
  (South Indian vocal percussion).

**Pitch**

- **`:pitches`** — MIDI note numbers (60 = middle C), one per note.
  `(scale)` → `60 62 64 67 69` (C major pentatonic).
- **`:pairs`** — notes as `[pitch duration]` pairs: pitch and rhythm
  already joined, one step short of notes. `(color-talea (grammar)
  (bisect))` → `[60 1/2] [64 1/2] [65 1/2] ...` (an isorhythm: a
  pitch row and a duration row cycling independently).
- **`:model`** — a trained Markov model of a melody: for each run of
  `order` pitches, the pitches that followed it in the training melody
  (repeats count as weight). Nothing to play by itself; `markov-gen`
  walks it to make pitches. `(markov-train (scale))` →
  `{:order 1, :transitions {[60] [62], [62] [64], [64] [67], [67] [69]}}`.

**Numbers and shapes**

- **`:numbers`** — plain numbers with no musical meaning yet: noise,
  walks, chaotic maps, data. A bridge gives them one — `threshold`
  turns them into a grid, `degrees` into pitches. `(walk)` → `58.8
  59.9 59.0 57.2 59.0 ...`; `(normal)` → `-1.97 -0.10 -0.65 ...`.
- **`:weights`** — one importance value per pulse of a bar, higher =
  more important: Barlow indispensability, where the downbeat gets the
  highest. Used to decide which pulses sound (`density`) or to draw one
  (`pick`). `(indisp)` for 12 pulses → `11 0 4 8 2 6 10 1 5 9 3 7`.
- **`:points`** — a path in more than one dimension: one vector per
  step. The chaotic attractors give these; `axis` takes one coordinate
  out as numbers. `(lorenz)` → `[1.01 1.26 0.98] [1.05 1.52 0.97] ...`
  (x, y, z).
- **`:index`** — a single whole number: a position chosen from
  something, not a sequence. `(pick (indisp))` → `0` (one pulse drawn
  with the weights as odds; the downbeat, 0, is the likeliest).

**Several voices, and the end**

- **`:layers`** — several parallel parts at once, each one a sequence
  of its own: grids, or `[pitch duration]` lines. `(necklaces)` →
  `[1 1 1 0 0 0 0 0] [1 1 0 1 0 0 0 0] ...`; `(counterpoint
  (grammar))` → two voices of `[pitch duration]`. `layer` takes one
  out.
- **`:notes`** — what plays: musics' own Leaf and Rest maps (pitch,
  duration, articulation, ...), the same as a parsed `c4`. The end of
  every tree that is meant to sound. `(notes (scale))` → five quarter
  notes, C D E G A.

Two markers are not types of data but rules: **`:any`** (an input that
takes anything, or an output that says nothing about what it gives)
and **`:same`** (an output of the same type as the first input: `head`
of pitches is pitches, `head` of a grid is a grid).

## The table

The fewest algos from the row type to the column type, up to 4;
**–** means no chain of 4 or fewer exists.

| from \ to | durations | grid | index | layers | model | notes | numbers | onsets | pairs | pitches | points | strokes | weights |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| **durations** | · | – | – | – | – | 2 | – | – | 1 | – | – | – | – |
| **grid** | 2 | · | 2 | 1 | 2 | 2 | – | 1 | 2 | 1 | – | 1 | 1 |
| **index** | – | – | · | – | – | – | – | – | – | – | – | – | – |
| **layers** | – | – | – | · | – | – | – | – | – | – | – | – | – |
| **model** | – | – | – | 2 | · | 2 | – | – | 2 | 1 | – | – | – |
| **notes** | – | – | – | – | – | · | – | – | – | – | – | – | – |
| **numbers** | 3 | 1 | 3 | 2 | 2 | 2 | · | 2 | 2 | 1 | – | 2 | 2 |
| **onsets** | 1 | – | – | – | – | 3 | – | · | 2 | – | – | – | – |
| **pairs** | – | – | – | – | – | 1 | – | – | · | – | – | – | – |
| **pitches** | – | – | – | 1 | 1 | 1 | – | – | 1 | · | – | – | – |
| **points** | 4 | 2 | 4 | 3 | 3 | 3 | 1 | 3 | 3 | 2 | · | 3 | 3 |
| **strokes** | – | – | – | – | – | – | – | – | – | – | – | · | – |
| **weights** | 3 | 1 | 1 | 2 | 3 | 3 | – | 2 | 3 | 2 | – | 2 | · |

How many algos give each type, and how many algo inputs take it:

| type | given by | taken by |
|---|:-:|:-:|
| durations | 3 | 1 |
| grid | 30 | 17 |
| index | 1 | 0 |
| layers | 11 | 1 |
| model | 1 | 1 |
| notes | 2 | 0 |
| numbers | 26 | 4 |
| onsets | 10 | 2 |
| pairs | 1 | 1 |
| pitches | 21 | 11 |
| points | 2 | 1 |
| strokes | 1 | 0 |
| weights | 4 | 4 |

## Points

**1. Nothing makes rhythm from pitches.** The `pitches` row reaches
only layers, a Markov model, pairs and notes. There is no pitches →
numbers (a melody's contour as numbers), which would open the way to
grids through `threshold` and `data-rhythm`, and to every number algo.
A melody can't drive a rhythm, or be measured, scaled or smoothed as
numbers.

**2. The rhythm types don't convert back into each other.** `gaps`
turns onsets into durations, but nothing goes the other way:

- onsets → grid: missing (times onto a pulse grid);
- durations → onsets: missing (a running sum — the inverse of `gaps`);
- durations → grid: missing, so durations reach no rhythm algo; they
  only end in `color-talea` → pairs → notes;
- grid → numbers: missing, so a grid can't feed the number algos.

**3. Dead-end types: made, never taken.**

- `:index` — only `pick` gives it (one integer, a chosen position);
  nothing takes it.
- `:strokes` — only `konnakol` gives it (syllables: `"Ta" "-" ...`);
  nothing takes it.
- `:layers` — 11 algos give it (counterpoint, polyrhythms, necklaces,
  ...), but its one taker, `layer`, gives `:any`, so the types lose
  track right there.
- `:notes` is a dead end too, and rightly: it is the final output.

**4. Eleven algos give `:any`.** The type rule lets an `:any` output fit
every slot, so the builder offers them everywhere and can't catch a
misuse, and a search can't use them as bridges. Run with their
defaults, they give:

| algo | takes | gives (observed) | suggestion |
|---|---|---|---|
| `markov-rhythm` | — | `0 1 0 1 ...` | `:grid` |
| `text-rhythm` | — | `1 0 1 1 ...` | `:grid` |
| `trend-rhythm` | numbers | `0 0 0 0 0 2 ...` (0, 1, 2) | `:grid` if a 2 is an accented onset, else `:numbers` |
| `tiling` | grid, grid | `[[1 0 1 1 ...] false]` | give the grid alone (`:grid`), the flag separately |
| `pocket` | grid | `{:time :accent :beat-position}` maps | a timed-event type (suggestion 2) |
| `humanize` | onsets | `{:time :velocity :original-time}` maps | the same |
| `tala` | — | `{:matra :vibhag :accent :time}` maps | the same |
| `djembe` | — | `{:time :stroke :accent}` maps | the same, or `:strokes` |
| `patch` | — | `{:pitch :velocity :duration :bend}` maps, some `nil` | a note-event type, or `:notes` through a converter |
| `chain` | — | `67 60 67 60 ...` (its states) | stays `:any`: it walks whatever states it is given |
| `layer` | layers | `1 0 0 0 1 0 ...` (one layer) | stays `:any`: a layer is whatever the layers hold |

Four of these return maps with a `:time` — timed events with accents or
strokes — which no type covers today. `:onsets` are plain times
(`0.0 0.15 0.3`), so those maps don't fit it as they are.

**5. Smaller gaps.** weights → numbers is missing, though a weight
vector is numbers already. Notes can't be taken apart again (notes →
pitches or durations); algos that change notes read the voice's
`:nodes` instead, so this may not matter.

## Suggestions

In order of effort against what they open up:

1. **Declare the `:any` outputs that have a definite type** — no new
   algo: `markov-rhythm` and `text-rhythm` as `:grid`; `tiling` giving
   its grid. The builder then places them only where they fit, and the
   searches can use them.
2. **One type for timed events.** Either a new `:events` type (maps
   with a `:time`) for `pocket`, `humanize`, `tala` and `djembe`, with
   an events → onsets bridge (take `:time`), or have those algos give
   `:onsets` directly. The first keeps their accents.
3. **Four small bridges**, each a few lines:
   - onsets → grid (times onto a pulse grid, with a resolution param);
   - durations → onsets (a running sum — the inverse of `gaps`);
   - pitches → numbers (contour: the pitches as numbers, or their
     intervals);
   - grid → numbers (the 0/1 cells as numbers, or the onset positions).

   With these, every rhythm type reaches every other, and a melody can
   drive a rhythm (pitches → numbers → grid).
4. **Give the dead ends a use or an existing type.** `pick`'s index is
   a number (`:numbers`, one item); `konnakol`'s strokes could feed a
   strokes → grid bridge (a syllable is an onset, `-` a rest).
5. **`trend-rhythm`**: decide whether its 2s are onsets with an accent
   (`:grid`) or levels (`:numbers`).

After each step, `lt/steps` checks a pair, and running it over every
pair rebuilds the table.
