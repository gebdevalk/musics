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
