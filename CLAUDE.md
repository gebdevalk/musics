# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Fluidsynth via a virtual ALSA MIDI port)
or rendered to a MIDI file. It's a REPL-driven project, not an app with a CLI —
the primary interface is `src/musics.clj`, evaluated interactively.

## Repo state — read this first

**The flat-model migration is essentially complete.** The domain model was
rewritten from a mutable, atom-based tree (parent-linked contexts, `Composite`
records holding `children-atom`) to a flat, immutable one (a single `repo` map
of `id -> container`, contexts with no parent pointer), and the old model has
since been removed rather than kept around as reference:

- `src/core/domain/flat_domain.clj` + `src/core/domain/context.clj` +
  `src/core/domain/resolve.clj` are **the** domain model — there is no other.
- `src/core/domain/music_domain.clj` (the old model) and
  `src/input/reader/tree_walker.clj` (the old walker) are both gone from disk.
  Nothing requires them anymore: `src/output/ornaments.clj` only requires
  `core.domain.context`/`core.domain.flat-domain`, `grammar_parser.clj` only
  requires `instaparse.core`, and `src/musics.clj` requires
  `input.reader.flat-tree-walker` directly. `(require 'musics)` loads
  end-to-end and `lein test` is green.
- `test/domain_model_test.clj.bak` (disabled test for the old model) is gone.
- `src/core/engine/engine.clj` (a `ScheduledExecutorService`-per-track
  engine) and `src/output/midi/engine.clj` (its long-commented-out MIDI
  dispatch, requiring the old model) are both gone too, replaced by
  `src/core/engine/async_engine.clj` — see the Architecture section below.
- `doc/domain.md`, `doc/parsing.md`, `instructions.md` describe an **earlier**
  bracket scheme (`{ }` sequence, `<< >>` parallel, `tree-walker.clj`). The
  grammar has since changed brackets around (see below) — treat
  `src/input/reader/parser/musics.ebnf` as the source of truth over those
  docs when they disagree. `doc/FLAT_TREE_PLAN.md`, `doc/walk-trace.txt`,
  `doc/usage new domain.txt`, `src/core/domain/doc.txt` are working notes from
  this migration and describe the *current* flat model accurately.

If you find something that still assumes the old model exists, that's stale —
update or remove it rather than working around it.

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

### Pipeline (current / flat)

```
text
  ├─ vars/extract-vars, expand-vars    (src/input/reader/parser/vars.clj)
  ├─ strip-comments
  ├─ instaparse (musics.ebnf)           → raw parse tree
  ├─ flat-tree-walker/walk              → {:tree repo-map :root-id kw}
  │    (uses flat-core-builder for the push/pop container stack)
  └─ core.engine.async-engine/play      → walks repo directly, just-in-time
       └─ core.domain.resolve/resolve-event (per leaf, at fire-time) → MIDI-ish maps
```

`core.domain.resolve` also has `form-unroll`/`form-unroll-lazy` (eager/lazy
whole-tree-to-tracks flattening) — still present and tested, but no longer
called by the engine; `async-engine` walks the repo tree directly instead
(see below), so treat these two as available-but-currently-unused utilities
rather than part of the live pipeline.

`src/musics.clj` is the REPL entry point: `(parse text)` builds a `Score` and
adds it to an in-memory `book` atom; parts are addressed by keyword id
thereafter (`(inspect :verse)`, `(ctx :verse :tempo 0.0)`, etc.) — ids are
first-class handles, resolved via `resolve-id` (keyword/string/int/map all
accepted).

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
- **`core.domain.resolve`** provides `form-unroll`/`form-unroll-lazy`
  (structural — expands Iterators, threads ctx-chains, produces per-track
  event seqs with no baked-in offsets; not currently called by the engine,
  see above) and `resolve-event` (actualization — called by the engine per
  leaf at fire-time with the current structural time, samples tempo/volume
  from the ctx-chain, and reads frozen leaf fields like
  articulation/pitch/program to build a MIDI-ish event map).
- **`core.engine.async-engine`** is the (sole) real-time playback engine,
  built on `core.async` goroutines rather than a `ScheduledExecutorService`.
  It walks the repo tree directly and just-in-time -- no pre-flattening
  step -- so `:SEQ` runs its children one after another inside one voice
  (a go-block), `:PAR` forks each child into a sibling voice the parent
  awaits on, and each leaf is resolved via `resolve-event` right as it
  fires. This also means live REPL edits to repo (an atom) and `:count
  :infinite` Iterators fall out for free, with no separate lazy/eager
  code path needed for either. `*engine*` is a dynamic var so REPL calls
  (`play`, `stop!`, `pause!`, `resume!`) don't need to thread an engine
  value around; `pause!`/`stop!` are checked in ~20ms increments even
  mid-note, so pause freezes a sounding note in place (no retrigger on
  resume) and stop sends note-off promptly instead of waiting out the
  full duration. `play`'s args are a small mini-language (bare keyword =
  repo reference; `[optional :par/:seq tag, then a leading run of
  context-refs, then material]`, tag defaults to `:seq` if omitted, and
  is obligatory for parallel playback) -- see the docstrings in
  `async_engine.clj` for the full grammar and examples. Real MIDI output
  goes through `output.midi.midi-live`'s `Receiver`, passed in as the
  engine's `fs` (`nil` is fine too -- playback just sends no MIDI, useful
  for tests).

### Grammar (`src/input/reader/parser/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

Current bracket scheme (differs from the older docs — check the `.ebnf` when
in doubt):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `[ ]`     | `Sequence`    | musical sequence                  |
| `{ }`     | `Parallel`    | simultaneous parts                |
| `( )`     | `Unit`        | grouped elements, no context of its own |
| `'[ ]`    | `Data`        | data container                    |
| `@'[ ]`   | `AtomicAlgo`  | algorithm over data                |
| `@[ ]`    | `ElementAlgo` | algorithm over elements            |
| `^[ ]`    | `Context`     | named context/envelope definition |

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT` whose envelope
points get replayed onto the current container's context at the current beat
offset — see `apply-context-ref` in `flat_tree_walker.clj`).

### Other modules worth knowing about

- `common/data/music_data.clj` — big reference-data tables (pitch names,
  note-length ratios, dynamics, scales, drum name → MIDI, etc.), ported from
  an earlier Python implementation.
- `common/elements/music_elements.clj`, `common/tools/music_tools.clj` — key
  parsing and other music-theory helpers used by the walker/ornaments.
- `input/reader/parser/leaf_parser.clj` — pitch/duration/articulation/dynamic
  parsing at the leaf level, independent of the grammar/lexer.
- `output/ornaments.clj` — expands a `Leaf`'s ornament/grace/tremolo modifier
  into replacement sub-leaves at resolve time (needs the active `Key` from
  context for scale-relative ornaments like `prall`).
- `output/midi/midi_file.clj` / `output/midi/midi_live.clj` — the two MIDI
  backends (file-based `aplaymidi` playback vs. live Fluidsynth via VirMIDI).
  `midi_live.clj`'s `Receiver` (`open-receiver`/`note-on`/`note-off`/
  `program-change`/`control-change`) is what `core.engine.async-engine`
  uses for real sound; `midi_file.clj` is a separate, unused-so-far offline
  batch renderer (build a `Sequence`, write/play a `.mid` file), not wired
  into the live engine.
- `algorithm/` — generative helpers (e.g. `lorentz.clj`, a chaotic map used as
  a modulation source).
- `java-reference/`, `julia-reference/`, `kotin-referenvce/`, `python-reference/`
  — ports/prior implementations of this same system in other languages, kept
  for cross-checking behavior, not part of the build.
