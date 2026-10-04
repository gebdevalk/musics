# Algo audit: category against output

Do the algos' categories say what they produce, and what is each one
good for? This covers all 137 algos in the registry (`(t/algos)`) on
2026-10-04. Each algo was checked on three things:
- its category: the namespace segment after `algo.`, or the
  `:category` set in its `:algo` metadata;
- its declared `:out` type;
- what it actually returns: every algo typed `:any`, plus a sample of
  the others, was run with its defaults.

## Summary

- **A category names where an algo comes from, not what it makes.**
  `random` holds number samplers, pitch samplers, rhythm (onset)
  generators, 2-D/3-D attractors and six list transforms. `rhythmic`
  holds grids, onset times, durations, parallel parts, accent levels,
  syllables and event maps. The only categories that follow output are
  the ones `algo.tree.lib` sets itself (`sources`, `shape`, `bridges`,
  `output`), plus `species` (in `algo.logic`, set to `melodic`).
- **The output type is the better guide, and is mostly right.** Each
  type fits one musical use; the table below groups the algos by it.
- **Eight `rhythmic` algos and two `random` ones are typed `:any`**
  although they return something definite. Six of them return event
  maps (`{:time .. :accent ..}`) that nothing in the registry can take,
  so in a tree they are dead ends.
- **`:layers` means two different things.** It covers parallel parts
  (polyrhythm, drums, counterpoint) and also sets of alternatives
  (necklace, bracelet, crossover), which aren't meant to sound
  together.
- **`:onsets` mixes units.** Some algos give seconds (the physical
  ones, `metric-mod`), others beats (`swing`, `random-onsets`), so one
  `gaps` setting can't fit all of them.

## What each output is good for

| Output | Good for | Taken by | Algos (category) |
|---|---|---|---|
| `:grid` 0/1 per pulse | **rhythm only**: where notes sound. Also usable as an on/off mask (mute, accent) | `gate` (pitches on onsets), `konnakol`, the grid transforms, `swing`, `metric-mod`, `iso-strength` | rhythmic: all-interval bell cantor chain-rhythm csp data-rhythm dragon emi euclid fibonacci fifths-rhythm genetic golden interval-sieve logistic-grid lsys-rhythm mutate oblique polygon primes rnn sieve stochastic theka tuplets · metric: bits cfrac modular · indisp: density · bridges: threshold |
| `:onsets` times | rhythm in time: when events happen | `gaps` (to durations), `humanize` | rhythmic: birdsong bounce cloud heartbeat metric-mod pendulum rain swing · random: poisson random-onsets |
| `:durations` note values | rhythm as note lengths | `color-talea` only | rhythmic: bisect split · bridges: gaps |
| `:weights` per pulse | accent or probability per pulse: rhythm (what sounds), dynamics (how loud) | `density` (to grid), `pick`, `tilt`, `power` | indisp: indisp tilt power · rhythmic: iso-strength |
| `:pitches` | melody, cantus, chords as lists | `notes`, `gate`, `color-talea`, the melodic transforms, `degrees` | melodic: constrained grammar infra inter lsys-melody markov-gen modulating polations ultra · sources: scale · shape: gate · bridges: degrees · random: int-* (9) |
| `:numbers` | **any parameter**, once mapped: pitch (`degrees`), rhythm (`threshold`, `data-rhythm`), dynamics, tempo curves | `degrees`, `threshold`, `data-rhythm`, `trend-rhythm` | random: 20 samplers, walk glide biased-walk logistic · sources: noise · bridges: axis |
| `:points` 2-D/3-D | several parameters moving together (pitch + volume + density) | `axis` | random: henon lorenz |
| `:pairs` [pitch dur] | melody with its own rhythm | `pair-notes` | common: color-talea |
| `:layers` | parallel parts, **or** a set of alternatives | `layer` | rhythmic: polyrhythm polymeter african hemiola duet phases drums · bracelet necklace necklaces crossover (alternatives) · melodic: counterpoint species |
| `:notes` Leaf/Rest maps | playable directly | play / `t/live!` | output: notes pair-notes |
| `:model` | a trained Markov model | `markov-gen` | melodic: markov-train |
| `:index` | one weighted choice | nothing | shape: pick |
| `:strokes` syllables | vocal percussion text | nothing | rhythmic: konnakol |
| `:same` | reorder, repeat, cut or transpose whatever it gets | anything | shape: cycled head shuffled stretch transpose · random: choose-from choose-n cyclic deep-shuffle only sputter |

## Findings

### 1. `random` is five kinds of algo

| Kind | Algos | By output it is |
|---|---|---|
| Number samplers and walks | 20 samplers, walk, glide, biased-walk, logistic | sources of `:numbers` |
| Integer samplers | int-arcsine … int-triangular (9) | sources of `:pitches` (default range 60–72); really integers in a range |
| Onset generators | poisson, random-onsets | rhythm |
| Attractors | henon, lorenz | sources of `:points` |
| List transforms | choose-from choose-n cyclic deep-shuffle only sputter | shape (`:same`) |

`chain` (a Markov walk over states, pitches by default) and `patch`
(note-like maps `{:pitch :velocity :duration :bend}`, or nil) are typed
`:any`. `patch` makes events nothing can take; `chain` gives pitches
with its defaults.

### 2. `rhythmic` outputs that aren't rhythm grids

| Algo | Declared | Actually returns | Fit |
|---|---|---|---|
| markov-rhythm | `:any` | 0/1 list | should be `:grid` |
| text-rhythm | `:any` | 0/1 list | should be `:grid` |
| trend-rhythm | `:any` | 0/1/2 levels | `:weights` |
| polymeter | `:layers` | layers of 0/1/2 (accent levels) | layers of weights, not grids |
| tiling | `:any` | `[grid found?]` | a grid plus a flag |
| tala | `:any` | `{:matra :vibhag :accent :time :tali-khali}` maps | event maps: dead end |
| djembe | `:any` | `{:time :stroke :accent}` maps | event maps: dead end |
| humanize | `:any` | `{:time :velocity :original-time}` maps | event maps: dead end |
| pocket | `:any` | `{:time :accent :beat-position}` maps | event maps: dead end |
| konnakol | `:strokes` | syllables and "-" | text: dead end |
| drums | `:layers` | Drum/Rest maps per kit piece | already notes; playable |
| bisect, split | `:durations` | note values | rhythm, but only `color-talea` takes them |

The event maps (tala, djembe, humanize, pocket, and random's patch)
share a shape: a time plus accent/velocity/stroke. That is a real type
(timed events) the registry doesn't have. With one bridge to `:notes`
or `:weights` they would become usable.

### 3. `metric` and `indisp` are rhythm by output

`metric`'s three algos (bits, cfrac, modular) make grids from number
theory, the same output as `rhythmic`'s generators. `indisp` makes
weights per pulse plus one grid (`density`). Both are rhythm tools with
a separate origin. Their weights also suit dynamics (accent strength).

### 4. `common` holds one algo

`color-talea` joins pitches and durations into pairs. By role it is a
bridge (like `degrees` and `gaps`), not a category of its own.

### 5. `:layers` is two types

- **Parallel parts**, meant to sound together: polyrhythm, polymeter,
  african, hemiola, duet, phases, drums, counterpoint, species.
- **Alternatives**, a set to choose from: necklace, bracelet,
  necklaces (every pattern of a length) and crossover (two offspring).

`layer` treats both the same: pick one by index. That is right for
alternatives and fine for one part, but a type checker can't tell
"play these together" from "pick one of these".

### 6. `:onsets` has no unit

- **Seconds:** birdsong, bounce, heartbeat, pendulum, rain,
  metric-mod (all say "seconds" in their docs).
- **Beats:** swing ("beat-duration units, not seconds") and
  random-onsets ("within num-beats").
- **A duration you give:** poisson and cloud.

`gaps` converts with a `:unit` param, so a tree has to know which
kind it has.

### 7. Outputs with no consumer

`:strokes` (konnakol), `:index` (pick) and the `:any` event maps lead
nowhere in a tree. `:durations` has one consumer (`color-talea`), so
`bisect`/`split` can't become notes without pitches.

## Guiding principle

Status (2026-10-04):
- **Step 1 done:** types named by their element.
- **Step 2a done:** `algo.glue`, `zip` (dur and pitch into leaves), and the `+volume`,
  `+articulation` and `+instrument` blend steps.
- **Still to do:**
  - **2b:** move the recipes and docs onto the glue, and remove
    `notes`/`gate`/`degrees`/... .
  - **3:** marked tools.
  - **4:** categories, typing the `:any` algos, splitting `:part`.


**The product of every tree is leaves**: note, chord, rest, drum. A leaf
is made of five materials:
- **dur**
- **pitch** (a chord is several at once)
- **volume**
- **articulation**
- **instrument** (a MIDI program, or a drum; decision 4)

A tree produces these materials and blends them into leaves. Material
is never transformed into another material: no pitch becomes a dur, no
dur a volume. Each is end material.

Everything else an algo makes (grids, onsets, weights, numbers, points,
strokes, degrees) is raw. It reaches the end material through
**one-way glue**, and never comes back.

```
generators ─► raw types ──glue──► dur          ─┐
                                  pitch        ─┤
                                  volume       ─┼─► leaves
                                  articulation ─┤
                                  instrument   ─┘
```

### Glue (one way, raw to end material)

Each type is named by its single element (decision 5): a list of 0/1
pulses is `pulse`, of durations `dur`.

| Glue | From → to | Does | Replaces or covers |
|---|---|---|---|
| weight->pulse | weight → pulse | a meter thinned to its strongest pulses | `density` |
| pulse->dur | pulse → dur, at a pulse value (tempo) | each onset lasts until the next; a 0 lengthens the note before it, leading 0s are a rest | `gaps` for grids, `gate`'s rhythm half |
| onset->dur | onset → dur, at a tempo | the time between onsets | `gaps` |
| number->dur | number → dur | quantised to note values | — |
| stroke->dur | stroke → dur | one stroke per pulse, "-" lengthens | konnakol's dead end |
| point->dur | point → dur, at a tempo | one axis as time | `axis` + `gaps` |
| point->pitch | point → pitch, mapped onto a scale | one axis as pitch | `axis` + `degrees` |
| degree->pitch | degree → pitch, in a key and scale | scale steps to MIDI | `scale`'s offsets, `degrees` |
| range->pitch | number → pitch, a range mapped onto a pitch range | samplers, walks, noise to pitch | `degrees` |
| weight->volume | weight → volume | strong pulses louder | — |
| weight->articulation | weight → articulation | accent levels to ghost/plain/accent/marcato | — |

### Tools (within one type)

zip · filter · map (one value range onto another) · scale · stretch ·
cycle · take · shuffle · random

A tool keeps the type it's given, so it can be used on any material.
`zip` is what blends materials into leaves.

### Consequences for today's algos

- **`zip`** takes dur and pitch, with volume, articulation
  and instrument optional. It replaces `notes`, `pair-notes`,
  `color-talea` and `gate`:
  - `gate` mixes rhythm into pitch, so it becomes `pulse->dur`;
  - `color-talea` already is a zip of pitches and durations.
- **Generators stay as they are.** They make raw types; the glue table
  covers every raw type in use.
- **The `:any` event makers** (tala, djembe, humanize, pocket, patch)
  either give a raw type (times → onsets, accents → weights) or go.
- **`:layers`** stays for parallel parts, each one a stream of leaves
  (drums, counterpoint). Sets of alternatives (necklace, bracelet,
  crossover) are a choice made before the tree, not a type in it.
- **Transforms within a type stay**: grid to grid (mutate, emi,
  oblique), pitches to pitches (Slonimsky, constrained), weights to
  weights (tilt, power).
- **The category follows the end material** an algo serves (dur,
  pitch, volume, articulation, instrument), or names it as glue or a
  tool.

### Naming convention

- **Types** are named by their single element:
  - `dur`, `pitch`, `volume`, `articulation`, `instrument` (end
    material);
  - `pulse`, `onset`, `weight`, `number`, `point`, `stroke`, `degree`
    (raw).

  Never a plural, never two names for one thing. Today's `:grid`
  becomes `:pulse`, `:durations` becomes `:dur`, `:pitches` becomes
  `:pitch`, and so on.
- **Glue** is named `from->to` with those names: `pulse->dur`,
  `weight->volume`.
- **Tools** are single verbs: `zip`, `map`, `take`.
- **Generators** are named for their method (`euclid`, `cantor`,
  `lorenz`).

## Decisions (2026-10-04)

1. **Rests.** A 0 always lengthens the note before it. Leading 0s,
   before the first onset, have no note before them and become one rest.
   That is the only rest `pulse->dur` makes.
2. **Volume.** Add `weight->volume`.
3. **Articulation.** Use glue wherever a raw type carries it
   (`weight->articulation` from accent levels); otherwise a fixed value.
4. **Drums.** A drum is not pitch material. It is the instrument: a
   timed MIDI program (or drum key) chosen per leaf. That makes
   **instrument** a fifth end material, alongside dur, pitch, volume and
   articulation.
5. **Names.** One element, no plurals, in type names and glue names
   alike: `weight->volume`, `pulse->dur` (not `grid->dur`).

Parked, not part of this: several instruments on one leaf or one
context, all playing it at once. See `doc/ideas.md`.
