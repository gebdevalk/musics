# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Fluidsynth via a virtual ALSA MIDI port)
or rendered to a MIDI file. It's a REPL-driven project, not an app with a CLI —
the primary interface is `src/musics.clj`, evaluated interactively.

## Repo state — read this first

**The flat-model migration is complete, and a versioned store + live
signaling layer has been built on top of it since.** Two separate waves of
change, both worth knowing about:

**Wave 1 — flat model** (domain model rewritten from a mutable, atom-based
tree — parent-linked contexts, `Composite` records holding `children-atom`
— to a flat, immutable one: a single `repo` map of `id -> container`,
contexts with no parent pointer):

- `src/core/domain/flat_domain.clj` + `src/core/domain/context.clj` +
  `src/core/domain/resolve.clj` are **the** domain model — there is no other.
- `src/core/domain/music_domain.clj` (the old model), `src/input/reader/tree_walker.clj`
  (the old walker), `src/core/engine/engine.clj` (a `ScheduledExecutorService`-
  per-track engine) and `src/output/midi/engine.clj` (its old MIDI dispatch)
  are all gone from disk, replaced by `src/core/async_engine.clj`.
- `doc/domain.md`, `doc/parsing.md`, `instructions.md` used to describe this
  earlier bracket scheme and model; they've since been brought up to date
  (see the note at the top of each) — `src/input/musics.ebnf`
  remains the source of truth over any doc when they disagree.

**Wave 2 — versioned repo, conductor, meter** (this repo's `id -> container`
map moved from a single mutable atom to `core.repo`, a versioned/staged
store; a signal-and-schedule layer, `core.conductor`, was added on top of
the live engine; and `Meter` went from an unwired bare string to a real,
computed part of the context system):

- **`core.repo`** (`src/core/repo.clj`) is now the one true store — every
  container id lives under `id -> tx -> node`, not a single current value.
  `musics.clj`'s `session` atom only holds `:auto-ids` and `:var-map` now,
  nothing else. See "Session, the versioned repo, and playback" below.
- **`core.conductor`** (`src/core/conductor.clj`) bridges the engine's
  structural boundaries (section enter/exit, bar crossings, author-placed
  `|`/`||`/`|||`/`||||` marks) to named, schedulable actions — the primary
  use case being cutting playback over to a newly-committed tx at a chosen
  boundary rather than instantly. See "Conductor: signals and scheduled
  actions" below.
- **`Meter`** (`common/music_elements.clj`) is now a real record
  (`num`/`den`/`subdivisions`), properly parsed from `!Meter:N/D` or
  `!Meter:"N/D(a+b+c)"` text and wired into the context system; Barlow
  indispensability is computed from it. See "Meter and indispensability"
  below.
- `core/domain/ornaments.clj` moved from `output/ornaments.clj` (it's a
  domain-model transform, never touches MIDI). `core.domain.resolve`'s
  `form-unroll`/`form-unroll-lazy` (dead since the engine switched to
  walking the repo tree directly, just-in-time) have been removed entirely,
  not just left unused.

**Wave 3 — comments and variables became grammar-native** (`vars.clj` and
`pre_parse.clj`, both gone from disk now, used to strip comments and
extract/expand variables as text substitution *before* instaparse ever
ran; both are real grammar rules now, resolved by `flat-tree-walker` in
the same walk as everything else):

- Motivated by a real, confirmed bug: any text-shape-changing transform
  before parsing (a comment collapsing lines, a variable insertion/
  removal) shifted everything after it, so a later parse error's line/
  column stopped matching the file actually written. Parsing the
  original text end to end removes the cause instead of working around
  it. See "Comments and variables" below for the full design.

If you find something that still assumes the old (pre-flat, or pre-`core.repo`)
model exists, that's stale — update or remove it rather than working around it.

## Commands

Leiningen project (`project.clj`), Clojure 1.12, two dependencies:
`instaparse` (parsing) and `org.clojure/core.async` (the playback engine).

```bash
lein repl              # start a REPL (init-ns is `user`)
lein test               # run the full test suite (test/ dir)
lein test command-walk-test         # run a single test namespace
lein test :only command-walk-test/times-scales-durations   # single test var
```

Audio playback requires system setup (Fluidsynth + qsynth + a virtual MIDI
port) — see `doc/setup.md` and `scripts/setup.sh` / `scripts/reconnect.sh`.
None of that is needed to parse text into the domain model or run tests.

## Architecture

### Pipeline (current)

```
text
  ├─ instaparse (musics.ebnf)           → raw parse tree
  │    (no text-level pre-processing at all -- comments and variables
  │    are both real grammar constructs now, Comment/VarDef/VarRef, so
  │    instaparse always parses exactly what was written; nothing is
  │    stripped or substituted beforehand, which is what makes a later
  │    parse error's line/column always match the original text -- see
  │    "Comments and variables" below)
  ├─ flat-tree-walker/walk              → {:tree repo-map :auto-ids ... :var-map ...}
  │    (uses flat-core-builder for the push/pop container stack; id
  │    assignment is lazy -- ensure-id only spends an auto-id counter
  │    slot at pop time, and only if the source never gave an explicit
  │    name, so {verse: ...} never wastes a :s-prefixed slot it won't use)
  ├─ core.repo/changed-ids + stage-many!, then commit-staged!  → new/
  │    changed ids land in the versioned store as one atomic tx
  │    (musics.clj/parse only stages; musics.clj/commit! is the separate
  │    step that actually commits)
  └─ core.async-engine/play      → walks core.repo/play-tx's view,
       │                                   just-in-time (a *separate*,
       │                                   explicitly-set tx -- commit!
       │                                   never moves it on its own)
       ├─ core.domain.resolve/resolve-event (per leaf, at fire-time) → MIDI-ish maps
       └─ core.conductor/signal!         (per section/bar/mark boundary)
            → registered actions (e.g. cutting play-tx over to a new commit)
```

`core.domain.resolve` used to also have `form-unroll`/`form-unroll-lazy`
(eager/lazy whole-tree-to-tracks flattening), from before `async-engine`
switched to walking the repo tree directly, just-in-time. They were unused
once that switch happened and have since been removed — if you find a
reference to either in an older doc or comment, that's stale.

`src/musics.clj` is the REPL entry point. `session` is now just
`{:auto-ids {...} :var-map {...}}` — `core.repo` is the actual store (see
below), not a `book`/`Score` atom. `(parse text)` walks against the latest *committed*
repo and stages the result (nothing is visible yet); `(commit! sid)` makes
it visible. Parts are addressed by keyword id thereafter (`(inspect :verse)`,
`(ctx :verse :tempo 0.0)`, etc.) — ids are first-class handles, resolved via
`resolve-id` (keyword/string/map all accepted).

### Session, the versioned repo, and playback

`core.repo` (`src/core/repo.clj`) is an id-addressed, versioned node store:
every id lives under `registry` as `id -> (sorted-map tx -> node)`, not a
single current value, so history is queryable and nothing is ever mutated
in place. Three ways to write:

- **`commit-node!`** — immediate single-node commit, mints a new tx.
- **Staged batch** — `begin-staged-tx!` → `stage!` (repeatable, per id) →
  `commit-staged!` (folds every staged edit into *one* atomic tx) or
  `abort-staged!` (discard without ever making it visible). This is what
  `musics.clj/parse` uses — a single `(parse "{a: ...} {b: ...}")` call
  can stage several ids at once, committed (or not) together.
- Reading: `as-of`/`current`/`history`/`latest-tx`, and **`view`** — a
  read-only, tx-pinned `{id -> node}` adapter (`get`/`keys`/`seq` all work
  normally, backed by `as-of`, nothing pre-materialized) that every reader
  (inspection, playback) uses uniformly instead of a flattened copy.

Two *separate* tx pointers matter, and conflating them is the most common
mistake here:

- **Latest-committed** (`core.repo/latest-tx`) — what `parse` walks against,
  and what every `musics.clj` inspection fn (`find`/`ids`/`children`/
  `leaves`/`inspect`/`ctx`/`locate`/`describe`/`print-structure`) defaults to
  when no explicit `tx` argument is given (they all accept one, for looking
  at any point in history instead).
- **`core.repo/play-tx`** — the tx live playback actually reads through
  (`core.async-engine`'s `repo` argument *is* this atom). **Committing
  never moves it.** `(commit! sid)` folds a batch into history; you still
  have to call `(play-tx! tx)` or `(play-latest!)` yourself to make it
  audible — directly, right now, or scheduled (see below) to happen exactly
  when playback reaches a chosen boundary. This is deliberate: it's what
  lets you prepare an edit mid-performance without it glitching whatever's
  currently sounding.

`write`/`load` persist/replace the whole committed history (via
`core.repo/seed!`), not just the current session's `:repo`; `reset` wipes
`core.repo` entirely and re-bootstraps a fresh `:ROOT`.

### Conductor: signals and scheduled actions

`core.conductor` (`src/core/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `async-engine` depends on
it (a plain synchronous function call, `conductor/signal!`, from
`play-node`/`advance-bar!`/`mark!`); `core.conductor` depends only on
`core.repo`, never back on the engine — a deliberate one-way dependency.

- **`action-registry`**: `id -> f`, a parked toolbox — `register-action!`/
  `trigger!` work standalone, no boundary involved (a human can `trigger!`
  one from the REPL directly).
- **`schedule`**: `[id phase] -> action-id`, one-shot (consumed the moment
  it fires) — `schedule!`/`unschedule!`, consulted by `signal!`, the
  engine's single entry point for every boundary kind. Three kinds fire,
  with deliberately disjoint `:id` shapes so all three share one schedule
  table with no collision risk:
  - **`:section`** — a container's own `:enter`/`:exit` (`:id` a keyword,
    the container's id).
  - **`:bar`** — a voice crossing its own bar boundary (`:id` a bare
    integer, that voice's new bar number; see "Meter and indispensability").
    **No central authority**: each voice counts its own bars against
    whatever `Meter` its own ctx-chain has in scope, so `(schedule! 8 :enter
    ...)` fires on whichever voice reaches its own bar 8 *first*, not "the
    piece's bar 8" as a single notion.
  - **`:mark`** — a voice hitting an author-placed `BarLine` (`|`/`||`/
    `|||`/`||||`, see Grammar below) — `:id` a `[:mark count n]` vector,
    `count` the pipe-count (1-4) and `n` that voice's own running count of
    markers *at that same strength*. Zero duration on its own; purely an
    extra cue layered on top of the automatic `:section`/`:bar` signals.
- **`schedule-tx!`** — the primary use case, built on the two pieces above:
  `(schedule-tx! id phase target-tx)` cuts playback over to `target-tx`
  (or `:latest`, resolved at the moment it actually fires, not when it was
  scheduled) the next time `[id phase]` is signaled.

### Domain model — flat repo, not a tree of pointers

- **Containers are plain maps**: `{:type :SEQ :id :s1 :context ctx :children [...]}`.
  No atoms inside nodes. `:children` holds a mix of inline leaf values and
  keyword ids that must be resolved against the `repo` map
  (`d/children repo container`). Auto-generated ids are short,
  type-prefixed (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`), assigned by
  `flat-core-builder/next-auto-id`.
- **Leaves are immutable records**: `Leaf`, `Rest`, `Drum` (pitches/duration/
  articulation/dynamic/modifiers/tied), plus `Iterator` (deferred expansion
  for `\repeat`/tremolo, holding a `:source` container + `:params`).
- **`Unit` (`( )`) is a context-less container**: structurally a regular,
  addressable container (keeps an id, registers in `repo`, holds an ordered
  `:children` list like `Sequence`), but has no `:context` of its own —
  its children, and any instruction written directly inside it, see
  whatever context is already in effect from its enclosing container
  (`flat-core-builder/current-context` skips context-less stack frames when
  building; `resolve/build-chain` simply never conjoins one onto the
  ctx-chain). Its purpose is to let an algorithm reorder elements within a
  `Sequence` while keeping a `Unit`'s contents glued together as one atomic
  block. Deliberately not valid inside `Parallel` (not part of
  `ParElement`) -- `Parallel`'s children are simultaneous, not sequential,
  so there's no order there for a `Unit` to preserve.
- **Transient containers** (`:TIMES`/`:TUPLET`/`:TRANSPOSE`/`:DECORATED`,
  i.e. `\times`/`\tuplet`/`\transpose`/a grace decoration) are notationally
  invisible: `flat-core-builder/pop-container` splices their `:children`
  straight into the parent and never registers them under an id at all --
  no separate container survives in the tree. They still get their own
  `:context` while being built, though (same as any regular container), so
  an instruction written directly inside one -- a standalone `!f`, or a
  note-suffix dynamic like `c4\f` -- has to go somewhere once that context
  is about to be discarded at pop time: `pop-container` replays every one
  of its envelope points onto the parent's context first (via
  `flat-core-builder/replay-context!`, the same mechanism `apply-context-ref`
  uses for a `:CONTEXT` reference), each point re-offset by the beat the
  transient block started at. The result takes effect from that beat and
  sticks forward -- past the end of the transient block, into whatever
  comes next in the enclosing sequence -- exactly as if the wrapping
  command had never been there at all, consistent with its children
  already being spliced flat. Contrast a genuine nested `Sequence`, which
  gets its own real, retained context and does *not* leak a dynamic set
  inside it to a sibling outside its brackets.
- **Context has no parent pointer** (`core/domain/context.clj`). The
  "enclosing scope" is visit-dependent — the same container can be reached
  through different parents if its id is reused — so lookups take an
  explicit `ctx-chain` (nearest-first vector of `Context`s) built by the
  traversal doing the walking, not stored on the data. `ctx-value-chain`
  walks that chain and only accepts a context's envelope if it has a point
  at-or-before the query time; otherwise it falls through to the next
  context, so a later instruction can't retroactively hide a still-valid
  outer value.
- **Envelopes** (`Point`/`Envelope` in `context.clj`) are time-value curves
  with an interpolation type per point (`:fixed :step :lin-up :lin-down
  :smooth :ease-in :ease-out :ease-in-out`); the *left* point's IP governs
  the curve to the next point. `env-reverse` swaps directional IPs
  (up↔down, in↔out) for time-reversed playback.
- **`core.domain.resolve`** provides `resolve-event` (actualization —
  called by the engine per leaf at fire-time with the current structural
  time, samples tempo/volume from the ctx-chain, and reads frozen leaf
  fields like articulation/pitch/program to build a MIDI-ish event map)
  and `locate` (navigation — walks the repo from a root along an explicit
  path of selectors, threading the ctx-chain the same way a real
  traversal would, for REPL inspection/addressing).
- **`core.async-engine`** is the (sole) real-time playback engine,
  built on `core.async` goroutines rather than a `ScheduledExecutorService`.
  It walks `core.repo/play-tx`'s view directly and just-in-time -- no
  pre-flattening step -- so `:SEQ` runs its children one after another
  inside one voice (a go-block), `:PAR` forks each child into a sibling
  voice the parent awaits on, and each leaf is resolved via `resolve-event`
  right as it fires. This also means live edits (a `(play-tx! ...)`
  repoint) and `:count :infinite` Iterators fall out for free, with no
  separate lazy/eager code path needed for either. Each voice also carries
  its own `:bar`/`:bar-pos`/`:marks` atoms alongside `:clock`/`:structural`
  (forked, not reset, at `:PAR` -- see "Conductor" above), advanced by
  `advance-bar!`/`mark!` right alongside the clock. `*engine*` is a dynamic
  var so REPL calls (`play`, `stop!`, `pause!`, `resume!`) don't need to
  thread an engine value around; `pause!`/`stop!` are checked in ~20ms
  increments even mid-note, so pause freezes a sounding note in place (no
  retrigger on resume) and stop sends note-off promptly instead of waiting
  out the full duration. `play`'s args are a small mini-language (bare
  keyword = repo reference; `[optional :par/:seq tag, then a leading run of
  context-refs, then material]`, tag defaults to `:seq` if omitted, and
  is obligatory for parallel playback) -- see the docstrings in
  `async_engine.clj` for the full grammar and examples. Real MIDI output
  goes through `output.midi.midi-live`'s `Receiver`, passed in as the
  engine's `fs` (`nil` is fine too -- playback just sends no MIDI, useful
  for tests).

### Meter and indispensability

`Meter` (`common/music_elements.clj`) is a record: `num`/`den`/
`subdivisions`. `subdivisions`, when given, is an explicit additive
grouping (`7/8(2+2+3)` → `[2 2 3]`); when omitted, `default-subdivisions`
derives the conventional grouping from `num`/`den` alone — compound meters
(`num/3` main beats, each dividing into 3) prime-factor their main-beat
count ascending with a final `3` appended (`12/8` → `[2 2 3]`); simple
meters just prime-factor `num` directly (`4/4` → `[2 2]`, `5/4` → `[5]`).
Irregular meters like `5/8`/`7/8` deliberately stay flat rather than
guessing a grouping (real practice groups them in genuinely
piece/convention-dependent ways) — write the explicit form if you want a
specific feel.

Set it via `!Meter:N/D` (bare ratio -- e.g. `!Meter:7/8`) or
`!Meter:"N/D(a+b+c)"` (quoted, additive grouping, groups must sum to the
numerator) — the `:M` alias also works. Both forms reach the context
correctly now (`flat_tree_walker.clj`'s `walk-assignment` has explicit
`:Ratio` and `:StringLit` cases for the canonical `:Meter` key); this used
to silently no-op for bare-ratio meters before `Meter` was stabilized.

`indispensability`/`meter-indispensability` (same file) compute Barlow
indispensability: given an ordered subdivisions factor sequence, every
pulse `0..N-1` gets a rank, downbeat always `N-1`. The combination rule is
one formula for any factor: substitute each level's raw digit through that
factor's base table (2/3/5/7 — see `indispensability-base-tables`) rotated
left by one position, then recombine using the same place-value structure
as the pulse index itself. 2 and 3's rotated tables happen to reduce to the
identity permutation (their reference tables are pure rotations); 5 and 7
don't, which is the actual substance of the theory, not a rounding
artifact — verified against known-correct reference tables, not derived
from scratch. Bar-length itself (for `core.conductor`'s `:bar` signals)
only needs `num`/`den`, not indispensability — the two are independent
consumers of the same `Meter`.

### Grammar (`src/input/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

Current bracket scheme (differs from the older docs — check the `.ebnf` when
in doubt):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `{ }`     | `Sequence`    | musical sequence                  |
| `<< >>`   | `Parallel`    | simultaneous parts                |
| `( )`     | `Unit`        | grouped elements, no context of its own |
| `'[ ]`    | `Data`        | data container                    |
| `@'[ ]`   | `AtomicAlgo`  | algorithm over data                |
| `@[ ]`    | `ElementAlgo` | algorithm over elements            |
| `^{ }`    | `Context`     | named context/envelope definition |

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT` whose envelope
points get replayed onto the current container's context at the current beat
offset — see `apply-context-ref` in `flat_tree_walker.clj`). `VarDef` is
`name = { ... }`; `VarRef` is `\name` — see "Comments and variables" below.

`BarLine` (`|`, `||`, `|||`, `||||`) walks to a `Bar` record (`d/bar`,
zero duration) inline in `:children` — purely a structural marker on disk,
but no longer inert at playback: `async-engine`'s `play-node` fires a
`core.conductor` `:mark` signal for each one it hits (see "Conductor"
above), so `|`/`||`/etc. are exactly how a composer places an extra,
author-controlled cue on top of the automatic `:section`/`:bar` signals.

Tempo takes either a bare BPM (`!tempo:120`, quarter note implied) or a
LilyPond-style `TempoMark` — note-value `=` BPM (`!tempo:4=120`, or an
explicit ratio note-value, `!tempo:3/8=120` for a dotted-quarter). The
walker (`flat_tree_walker.clj`'s `walk-assignment` `:TempoMark` case)
converts a `TempoMark` to the quarter-note-equivalent BPM `resolve.clj`'s
tempo sampling expects (`el/tempo->quarter-bpm`, e.g. `8=120` → `60`,
since an eighth note is half a quarter, so eighth=120 is the same speed as
quarter=60) before storing it — `resolve-event` never sees the note-value
side at all, only the normalized BPM. `!tempo:`/`!Tempo:`/`!T:` all
canonicalize to the same `:Tempo` context key (`common/defaults.clj`)
and all work identically, for either form.

Named tempo markings (`common/music-data.clj`'s `tempo-markings` —
`:largo`/`:andante`/`:allegro`/`:presto`/... at their standard BPMs) are
usable directly as `BangConst`s (`!allegro`, `!presto`, ...), same as a
dynamic mark (`!mf`/`!ff`) — `instruction-context` merges both tables into
one `keyword -> [context-key value]` map that `walk-bang-const` looks up
generically, so no separate wiring was needed for the single-word ones.
The handful of compound names (`:marcia-moderato`, `:andante-moderato`,
`:allegro-moderato`, `:allegro-vivace`) are kebab-case in `tempo-markings`
itself (ported straight from the Python data), but `BangConst`'s `Name`
token can't contain a hyphen — `instruction-context` adds a camelCase
alias for each (`!marciaModerato`, `!andanteModerato`, `!allegroModerato`,
`!allegroVivace`) pointing at the same value, same convention already
used there for `:commonTime`/`:stageLeft`/etc.

Pitch names accept Dutch (nederlands) accidental suffixes directly
(`is`/`isis`/`es`/`eses`, plus the `a`/`e`-elided `s`/`ses` forms) alongside
`#`/`b` — both resolve to the same semitone offset, see
`doc/LilypondToMuCheatSheet.txt`. A dynamic mark or hairpin glued directly
onto a note/chord (`c4\f`, `c4\<`, `c4\mf\<` chainable) reads the same as
writing the equivalent standalone `!f`/`!vol<` just before it, taking
effect from that note's own onset. Absolute octaves need a **capital**
pitch letter (`C5`); lowercase is always relative pitch resolution (nearest
fourth/fifth, LilyPond `\relative`-style) even as a sequence's first note —
there's no position-based exception.

### Other modules worth knowing about

- `core/repo.clj` — the versioned node store (see "Session, the versioned
  repo, and playback" above); zero dependencies on the domain model or
  anything else in the project, deliberately. Has its own `RepoView`
  deftype (`ILookup`+`Seqable`, backed by `as-of`) so any existing
  consumer expecting a plain `{id -> node}` map works against it unchanged.
- `core/conductor.clj` — the signal/schedule layer (see "Conductor" above);
  depends only on `core.repo`.
- `common/music_data.clj` — big reference-data tables (pitch names,
  note-length ratios, dynamics, scales, drum name → MIDI, etc.), ported from
  an earlier Python implementation.
- `common/music_elements.clj`, `common/music_tools.clj` — key
  parsing, `Meter`/indispensability (see above), and other music-theory
  helpers used by the walker/ornaments. `common/` is flat — no `data`/
  `elements`/`tools` subdirs — since each held only one or two files.
- `input/reader/leaf_parser.clj` — pitch/duration/articulation/dynamic
  parsing at the leaf level, independent of the grammar/lexer. `input/grammar_parser.clj`
  and `input/lilypond_import.clj` sit at the top level of `input/`, not
  nested under `reader/` — both are peers of, not sub-concerns of, the
  walker: `grammar_parser` is the actual pipeline entry point (`musics.clj`
  calls it directly, and it's the one that calls *into*
  `input.reader.flat-tree-walker`, not the other way around), and
  `lilypond_import` is a fully independent LilyPond→musics-text converter
  that never touches `core.domain.*` or `input.reader.flat-*` at all.
- `core/domain/ornaments.clj` — expands a `Leaf`'s ornament/grace/tremolo
  modifier into replacement sub-leaves at resolve time (needs the active
  `Key` from context for scale-relative ornaments like `prall`); lives with
  the rest of the domain model, not under `output/`, since it never touches
  MIDI itself.
- `output/midi/midi_file.clj` / `output/midi/midi_live.clj` — the two MIDI
  backends (file-based `aplaymidi` playback vs. live Fluidsynth via VirMIDI).
  `midi_live.clj`'s `Receiver` (`open-receiver`/`note-on`/`note-off`/
  `program-change`/`control-change`) is what `core.async-engine`
  uses for real sound; `midi_file.clj` is a separate, unused-so-far offline
  batch renderer (build a `Sequence`, write/play a `.mid` file), not wired
  into the live engine.
- `algorithm/` — generative helpers (e.g. `lorentz.clj`, a chaotic map used as
  a modulation source).
- `java-reference/`, `julia-reference/`, `kotin-referenvce/`, `python-reference/`
  — ports/prior implementations of this same system in other languages, kept
  for cross-checking behavior, not part of the build.

## Known rough edges (found, not yet fixed)

Two pre-existing quirks are still there — noted so neither is silently
rediscovered as something new:

- **`:key` context values get silently shadowed**: `flat_tree_walker.clj`'s
  `walk-assignment` `:QualifiedName` case (hit by e.g. `!key:C.major`)
  calls `ctx-append` *twice* at the same timestamp — once with the real
  parsed `Key` record, then again with the bare keyword `parsed-val` — and
  since both land at the same time, the second call wins. `:key` context
  values are therefore always a bare keyword in practice, never the actual
  `Key` record `el/parse-key` produced.
- **An `Id` inside a transient/scratch container's body is silently
  discarded**: `\times`/`\tuplet`/`\transpose`/a grace decoration's body,
  and a `VarDef`'s value, all walk their `Sequence`'s children directly
  into a container that's never registered under its own id (transient
  ones get spliced into the parent and discarded; `VarDef`'s scratch
  container is popped by hand and never touches `:repo` at all). If that
  body happens to contain an `Id` (`\times 2/3 {myname: c4 d4}`, or
  `motif = {myname: c4 d4}`), `walk-bareword` still renames the container
  currently on the stack — it just renames a container that's about to
  vanish either way, so the name has no effect and produces no error.
  Same underlying mechanism, both places.
