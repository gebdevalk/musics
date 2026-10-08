# Grids

What still has to be decided about grids, with the context each
question needs, and step sequencers as the background that shows what
a grid can be. Nothing here is decided: it is material to read and
come back to. What *is* settled about grids is in `doc/algo-space.md`:

- a grid is atoms aligned position by position on a common pulse
  frame, in one or more rows;
- a rhythm pattern is a grid, even with one row;
- a row can hold any atom;
- each grid content has its own type (`:pulse-grid`, `:weight-grid`);
- the generated `grid/` namespace holds what makes grids.

## The grid questions

### 1. What a row may hold

**Context.** Today a grid holds pulses (polyrhythm, hemiola), and
weights (indisp, tala) are one-row grids. On a step sequencer a track
also carries **parameter lanes** under its trigger row: a velocity per
step, a note per step, a gate length per step (below).

**Options:**
- **Atoms only:** a grid stays rhythm and accent material, and
  parameters live in streams.
- **Atoms and parameters:** a pitch row aligned to a pulse row means
  "on this step, this pitch". That is a sequencer track.

**Consequence.** With parameters, a grid can carry a complete pattern
before anything is zipped, and leaves can be read *down* a track. With
atoms only, a melody can never be laid out per step: it is always a
stream, zipped afterwards, its pitches following the onsets rather than
the steps. This is the step-indexed versus note-indexed distinction
(below).

### 2. Homogeneous grids or tracks

**Context.** One type per content (`:pulse-grid`, `:weight-grid`)
makes every row of a grid the same kind. A sequencer track is mixed:
one trigger row plus lanes of different kinds.

**Options:**
- **Homogeneous only:** simple types; a track is several grids side by
  side.
- **Tracks** as a type of their own (a trigger row with named lanes):
  closer to how musicians think, and a new arrangement.

Depends on question 1: if rows hold atoms only, tracks don't arise.

### 3. Equal or own row lengths

**Context.** A grid says position *n* in one row sounds with position
*n* in every other: rows of equal length on one frame. Sequencers often
let each row have its own length: a 3-step hat against a 4-step kick
drift apart and realign after 12 steps. That is polymeter, which
`polymeter` makes today by writing the rows out to a common length.

**Options:**
- **Equal length:** the grid is a rectangle; polymeter is written out.
- **Own lengths, each cycling:** the grid is a set of loops on one
  pulse, and the alignment emerges.

**Consequence.** Own lengths make a grid endless by nature, unless it
is cut at the least common multiple. That touches extent (D6) and
locality (D5).

### 4. Is a one-row grid a stream?

**Context.** A rhythm pattern is a one-row grid, typed as its row
(`:pulse`). That makes "pulse stream" and "one-row pulse grid" the same
thing in the types.

**Options:**
- **Yes, identical:** `pulses->durations` takes euclid directly; no
  `row` is needed for the common case.
- **No, a grid is always a grid:** cleaner in principle, but every
  rhythm needs a `row` before it reaches durations.

**Consequence.** "Yes" is where we leaned. It puts the grid/stream
distinction in the arrangement words (and the `grid/` namespace), not
in the types, for the one-row case.

### 5. What a grid becomes

**Context.** A pulse grid has to reach leaves, in one of two quite
different ways:
- **each row becomes its own line**, together a parallel: the drum
  machine, kick row → kick line, hat row → hat line;
- **the rows merge into one row** (union, intersection, a weighted
  vote) and become one line: a composite rhythm.

**The question:** which of these are operations we want, and what are
they called? The first is a grid → parallel step, close to the "over
rows" gap. The second is the pulse algebra (union, intersection,
complement).

### 6. Picking a row

**Context.** With several rows, one has to be singled out to go on as a
stream. With one type per content, a picker must give the right row
type.

**Options:**
- **One `row` operation**, with a rule in the type checker like `:same`,
  mapping `:pulse-grid` to `:pulse`.
- **One picker per grid type.**

Also open: whether picking by index (`:index`, with named picks for
several) is enough, or whether question 5's "each row its own line"
makes picking rare anyway.

### 7. How grids come into being

**Context.** Today grids come only from generators (`polyrhythm`,
`hemiola`, `african`) and from expansion (`duet`, `phases` turn one
pattern into rows).

**The question:** should streams also be stackable into a grid,
`(rows (euclid) (euclid :as :b) (cantor))`? That is a variadic
operation, the grid counterpart of `parts`. Without it a grid can only
be taken apart, never built from one's own rows.

### 8. What a step is worth in time

**Context.** A grid is positions, not time. How long one position lasts
(1/16, 1/8) is decided later, by `pulses->durations` and its `:pulse`
param.

**Options:**
- **The bridge decides:** today's design; the grid itself is outside
  time, in Xenakis's sense.
- **The grid carries its step value:** the grid is already temporal, and
  every row agrees on it.

**Consequence.** The first keeps grids abstract and reusable at any
speed. The second guarantees that rows taken from one grid stay aligned
in time; the first leaves that to the user (the same `:pulse` for every
`pulses->durations`).

### How they hang together

- 1 and 2 decide **what a grid is**.
- 3 and 8 decide **how it relates to time**.
- 4, 5, 6 and 7 decide **how grids connect to the rest**: streams in,
  streams and parallels out.

1 comes first, since 2 follows from it; then 4 and 5, which decide what
operations grids need.

## Step sequencers

### The basic machine

A step sequencer is the oldest and plainest way to write a pattern on a
grid.

- **Analog sequencers** (Moog 960, 1960s): a row of knobs, one per step,
  each setting a pitch, read left to right by a clock.
- **Drum machines** (Roland TR-808, 1980): 16 buttons for the steps, and
  one row per instrument (kick, snare, hat).
- **The TB-303** (bass, 1981): per step a pitch, plus an *accent* flag
  and a *slide* flag.
- **Today:** the Elektron Digitakt and Octatrack, Ableton Push, Polyend,
  Squarp Pyramid, and the step views in Bitwig and Ableton keep the same
  core and add a lot on top.

The vocabulary:
- **step:** one position in the pattern, typically a 1/16. A pattern
  has 16, 32 or 64 of them.
- **track:** one sound or instrument (or one MIDI channel). A pattern has
  several tracks, all advancing together.
- **trig** (trigger): "something happens on this step".
- **playhead:** moves one step per clock tick and plays whatever every
  track has on that step.

### A track: a trigger row and its lanes

Under a track's trigger row, every step can carry values, shown as
lanes (rows) aligned to the steps:

| Lane | Per step | In musics |
|---|---|---|
| **note** | which pitch | pitch (parameter) |
| **velocity** | how loud | volume (parameter) |
| **length / gate** | how long the note holds, within or beyond the step | articulation: staccato, legato |
| **accent** (303) | stressed or not | articulation, or a weight |
| **slide / tie** (303) | glide into the next note, or hold through it | legato, tie |
| **probability** | the chance the trig fires | a weight, turned into pulses by chance |
| **micro-timing** | early or late within the step | `:micro` |
| **retrig / ratchet** | repeat the note 2, 3, 4 times inside the step | tremolo, repeat |
| **condition** (Elektron) | fire only on pass 1 of 4, every 2nd pass, only in a fill … | depends on the pass, like a volta |
| **parameter lock** (Elektron) | this step alone has another cutoff, sample or pan | a per-note override (`c4\pan:-1.0`) |
| **lock trig** (Elektron) | change a setting on this step *without* playing a note | a context instruction at a point (`!pan:-1`) |

Per track there is usually also:
- **its own length** (a 12-step track against a 16-step track:
  polymeter, by construction);
- **its own speed** (×2, ×3/4).

Per pattern:
- **swing**;
- **chaining**: patterns follow one another (song mode), which is
  succession.

### Step-indexed and note-indexed

The distinction that matters most for algo space:

- **In a sequencer, lanes are indexed by step.** Step 5 holds pitch E.
  If step 5 triggers, E sounds; if step 5 is turned off, E stays *on
  step 5*, silent. Change the rhythm and **each pitch stays where it is
  in the bar**.
- **In a tree, streams are indexed by note.** `zip` gives the 5th pitch
  to the 5th onset, wherever that onset falls. Change the rhythm and
  **the pitches keep their order** and move to wherever the notes now
  are.

Both are musically real:
- **step-indexed** is how grooves and basslines think: a note belongs to
  its position in the bar (the 303, a drum pattern);
- **note-indexed** is how melodies and isorhythm think: the color goes
  to the talea, whatever its rhythm (Machaut, `cycle` over a pitch
  stream).

Algo space today is note-indexed only. Allowing parameter rows in a grid
(question 1) would add step-indexed parameters next
to the note-indexed streams. A track would then be a trigger row plus
step-indexed lanes, and turning it into leaves would mean reading
*down* the steps ("where the trigger is on, take this step's pitch,
velocity, gate") rather than *along* a stream, as `zip` does.

### What it suggests for algo space

- **Grids could gain parameter rows**, and with them step-indexed music,
  which trees can't express today.
- **A track** (a trigger row plus lanes) is a natural unit, perhaps the
  link between grid and line: "track → line" reads down the steps.
- **Much is already there under other names:** parameter locks are
  per-note overrides, lock trigs are instructions, micro-timing is
  `:micro`, ratchets are tremolo. The sequencer confirms the parameters
  musics already has rather than asking for new ones.
- **Two things musics doesn't have:**
  - conditions by pass ("every 2nd time"), which depend on repetition,
    as the voltas in musics text do;
  - per-row length and speed: polymeter by construction.

### The sequencer's angle on each question

The grid questions above, with what a sequencer suggests:

| Question | What the sequencer suggests |
|---|---|
| What may a row hold: atoms only, or parameters too? | both: a trigger row with parameter lanes |
| Homogeneous grids (`:pulse-grid`), or tracks with mixed lanes? | tracks |
| Rows of equal length, or each its own length? | each its own, cycling |
| Is a one-row grid a stream? | — (a sequencer has no streams) |
| What does a grid become: one line per row, or one merged line? | one line per track: each track is an instrument |
| Picking a row: one `row` operation, or one per grid type? | — |
| Stacking streams into a grid (`rows`)? | yes: tracks are added freely |
| Does a step have a duration in the grid, or only once bridged? | in the grid: the pattern has one step resolution |
