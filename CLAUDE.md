# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Fluidsynth via a virtual ALSA MIDI port)
or rendered to a MIDI file. It's a REPL-driven project, not an app with a CLI —
the primary interface is `src/musics.clj`, evaluated interactively.

## Repo state — read this first

**The flat-model migration is complete, and a versioned store + live
signaling layer has been built on top of it since.** Several separate waves
of change, all worth knowing about:

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

**Wave 4 — tx became per-voice** (`core.repo/play-tx` stopped being a
single pointer live playback continuously read; each voice now carries
its own `:tx`, and `schedule-tx!` moved out of `core.conductor` into
`core.async-engine` to redirect one voice at a time):

- Motivated by a real limitation surfaced while diagramming the engine's
  architecture (see `core.async-engine`'s own docstring): a single
  shared `play-tx` conflated every voice's cutover timing, so two
  uneven-length parts scheduled independently couldn't each redirect on
  their *own* boundary without one flipping the other's still-playing
  content early — whichever part's boundary was reached first moved the
  pointer for everyone.
- `core.repo/play-tx` now only seeds a brand-new top-level voice's own
  `:tx`, once, at the moment `play`/`warm-up!` creates it (see
  `core.async-engine/fresh-tx`) — it's no longer re-read continuously.
  `(play-tx!)`/`(play-latest!)` therefore only affect what the *next*
  `play` call starts at, not anything already running.
- `core.async-engine/schedule-tx!` (moved from `core.conductor`, which
  never depends on the engine and still doesn't — it just hands the
  whole signal event to whatever's registered) resets ONE voice's own
  `:tx` directly: `(reset! (:tx (:voice event)) target-tx)`. `signal!`'s
  event gained a `:voice` key to make this possible, carried exactly as
  opaquely as every other key already in that map. `core.conductor`
  itself lost its only reason to require `core.repo` as a result — see
  "Conductor: signals and scheduled actions" below.

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
lein test :parsing      # just one architectural layer -- :parsing/:domain/
                         # :engine/:repl/:forth (test-selectors in project.clj,
                         # grouped per this file's own module boundaries)
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
  └─ core.async-engine/play      → each voice walks its OWN :tx's view,
       │                                   just-in-time (seeded once from
       │                                   core.repo/play-tx at birth --
       │                                   commit! never moves it on its
       │                                   own, and neither does a live
       │                                   voice's own :tx after birth)
       ├─ core.domain.resolve/resolve-event (per leaf, at fire-time) → MIDI-ish maps
       └─ core.conductor/signal!         (per section/bar/mark boundary,
            → registered actions           :voice carried opaquely)
              (e.g. core.async-engine/schedule-tx!, cutting ONE voice
               over to a new commit)
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
`(ctx-value :verse :tempo 0.0)`, etc.) — ids are first-class handles, resolved via
`resolve-id` (keyword/string/map all accepted). `(ctx :verse)` is a separate,
display-only helper — the part's own context chain (every ancestor's own
authored envelope points, nearest first, `:ROOT` excluded), not a value
lookup.

`(mu!)` drops into a nested `clojure.main/repl` loop where a bare (quoted)
musics string stages itself, via `music-eval` as its `:eval` hook — no
`(s! "...")` wrapper call needed, though the quotes themselves still are:
only bare strings are intercepted, so an *unquoted* `{verse: ...}` reads
as an ordinary (and invalid) Clojure map before `music-eval` ever sees
it, same as it would at the outer REPL. `(c1!)` commits whatever the
previous `mu!` entry staged — `(c! (:sid *1))`, leaning on
`clojure.main/repl`'s own `*1` binding rather than tracking a sid by
hand. Leaving needs its own hook too, `music-read` (`mu!`'s `:read`):
`reply`'s `(exit)`/`(quit)` (the ones `lein repl`'s own banner
advertises) are handled client-side, entirely outside
`clojure.main/repl`'s read/eval loop, so a bare nested loop never saw
them on its own — typing `(exit)` inside `mu!` failed with an
unresolved-symbol error instead of leaving, a real bug caught only by
actually running `mu!` in a real session, not by the earlier piped-stdin
smoke tests that had silently assumed it worked. `music-read` recognizes
`(exit)`/`(quit)`/`:repl/quit` and turns them into `clojure.main/repl`'s
own `request-exit`, the same mechanism plain EOF (Ctrl+D) already used
successfully. That fix was itself only fully confirmed against a real
pty (not just piped stdin, which closes the whole stream on EOF and so
can't distinguish that from "just this one nested read ended") — Ctrl+D
inside `mu!` leaves only the inner loop, the outer session and any
state committed via `mu!`/`c1!` both survive it.

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
  `leaves`/`inspect`/`ctx`/`ctx-value`/`locate`/`describe`/`print-structure`) defaults to
  when no explicit `tx` argument is given (they all accept one, for looking
  at any point in history instead).
- **`core.repo/play-tx`** — seeds a brand-new top-level voice's own `:tx`
  the moment `(play ...)`/`(warm-up! ...)` creates it (see
  `core.async-engine`'s own docstring) — it is **not** re-read
  continuously the way it used to be; each already-running voice reads
  through its own private `:tx` from then on (forked at `:PAR` exactly
  like `:clock`/`:structural`/`:bar` are, seeded from the parent's
  current value, never incremented). **Committing never moves it.**
  `(commit! sid)` folds a batch into history; you still have to call
  `(play-tx! tx)` or `(play-latest!)` yourself to point the *next*
  `play` call at it, or `(schedule-tx! id phase target-tx)` (see below)
  to redirect ONE already-running voice at a chosen boundary. This is
  deliberate: it's what lets you prepare an edit mid-performance without
  it glitching whatever's currently sounding — and, since `:tx` is
  per-voice, without one part's cutover glitching a *different*,
  still-playing part either (the failure mode a single shared pointer
  couldn't avoid — see Wave 4 above).

`write`/`load` persist/replace the whole committed history (via
`core.repo/seed!`), not just the current session's `:repo`; `reset` wipes
`core.repo` entirely and re-bootstraps a fresh `:ROOT`.

### Conductor: signals and scheduled actions

`core.conductor` (`src/core/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `async-engine` depends on
it (a plain synchronous function call, `conductor/signal!`, from
`play-node`/`advance-bar!`/`mark!`); `core.conductor` depends on nothing
else at all, not even `core.repo` anymore — a fully generic dispatcher,
deliberately one-way. It used to also require `core.repo`, for
`schedule-tx!` living directly in this file; that moved to
`core.async-engine` once cutover became per-voice (see Wave 4 above) --
`schedule-tx!` still builds on `register-action!`/`schedule!` from here
unchanged, it's just no longer defined here, since it needs to know what
a voice is and this namespace still never does. The `event` map
`signal!` hands to a triggered action is opaque to every function in
this file, `:voice` included — conductor never interprets it, just
passes it through.

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
- **`core.async-engine/schedule-tx!`** — the primary use case, built on
  the two pieces above but living in `core.async-engine` now, not here
  (see Wave 4): `(schedule-tx! id phase target-tx)` cuts the ONE voice
  whose own boundary crossing triggers it over to `target-tx` (or
  `:latest`, resolved at the moment it actually fires, not when it was
  scheduled) the next time `[id phase]` is signaled — `(reset! (:tx
  (:voice event)) target-tx)`, reaching the right voice via `:voice` in
  the signal event. Other voices, and `core.repo/play-tx` itself, are
  untouched.

### AtomicAlgo: pointing musics text at a pre-existing algorithm

`@[ name Arg... ]` (`AtomicAlgo`) is wired to real execution —
`input.reader.flat-tree-walker/run-algo` looks `name` up in
`input.algo-registry/atomic-algo-registry` and calls the registered `:fn`
positionally with exactly the args written in the text; `walk-atomic-algo`
(the top-level entry point, called directly from `walk-element`'s
`:AtomicAlgo` case) is what requires the *result* to be a seq of `[pitch
duration]` pairs, splicing them straight into the enclosing container as
real `Leaf` children. `@{ name Element... }` (`ElementAlgo`) is untouched
and still inert — parses into a plain container holding its `Element`
children unexecuted, nothing dispatches on its `algo` name at all.
`AtomicAlgo` used to share `Data`'s own `'[` opening bracket (`@'[ ]`,
mnemonic "algo over data") with `ElementAlgo` on `@[ ]` ("algo over
elements") — renamed since to `@[ ]`/`@{ }` respectively (matching
`Sequence`'s own `{ }`, since `ElementAlgo` holds `Element`s the same way
a `Sequence` does), freeing `AtomicAlgo`, the one that's actually wired,
onto the shorter of the two spellings.

Each `Arg` is a `Data` literal (`[ ... ]`, walked into a plain seq of
bare values via `walk-data-values`), a bare `Primitive` (`Int`/`Float`/
`Ratio`, walked into a single scalar via `walk-single-value`), **or
another `AtomicAlgo` call** — genuinely recursive (`<AlgoArg> = Data |
Primitive | AtomicAlgo` in `musics.ebnf`): an `Arg` can itself be
`@[ someAlgo ... ]`, and `walk-algo-arg` calls `run-algo` on it right
back, recursively, to any depth. A nested call's raw return value is
passed through to the outer call **exactly as returned — no flattening,
no reinterpretation at that boundary**. This is what lets a combinator
(a `zip`, say) be fed entirely by other algorithms rather than literal
`Data`: `@[ zip @[ pitchGen 60 4 ] @[ durGen [/4 /8] ] ]`, where
`pitchGen`/`durGen` each return a plain flat value seq (not `[pitch
duration]` pairs at all) and `zip` combines them into pairs itself. The
`[pitch duration]`-pairs contract only binds whatever ends up at the
*top level* (the call `walk-atomic-algo` itself splices into musical
content) — an intermediate/nested call just has to return whatever
shape its own caller (another algo fn) expects, nothing more specific
than that. All `Arg` kinds — `Data`, `Primitive`, nested `AtomicAlgo` —
can be freely mixed in whatever order the target fn's own parameter list
expects (a rhythm generator's pulse/step counts alongside a pitch cycle,
say). `algo` itself has its own hyphen-permitting token (`AlgoName`, not
the shared, hyphen-free `Name` `type`/most other identifiers use — see
the comment on `algo`'s own rule in `musics.ebnf`), specifically so a
registered name can match a Clojure fn's own kebab-case symbol directly
(`@[ color-talea ... ]`), no camelCase alias required.

`input.algo-registry` (`src/input/algo_registry.clj`) owns the registry
itself — a peer of `input.grammar-parser`/`input.lilypond-import` under
`input/`, not nested inside `reader/`: like those two, it's about
interpreting an *input-language* construct, but its own lifetime spans
the whole session rather than one parse call, so it doesn't belong
inside the walker any more than they do. `flat-tree-walker` only reads
from it (a single `require`, used read-only by `run-algo`). Deliberately
**not** a generic plugin system: musics text only ever points at an
algorithm that already exists as real Clojure code, never defines one
itself. `atomic-algo-registry` is a plain `defonce` atom — the same
shape as `core.conductor`'s `action-registry` above (`"a parked
toolbox"`), and not touched by `write`/`load`/`reset` any more than
`action-registry` is, since it's runtime configuration, not musical
content or session state. Each entry is `{:fn f :doc doc}`, not a bare
fn — `doc` (an optional plain string, not Clojure docstring/arglist
metadata) is what `(algos)`/`(algos name)` show, since a Clojure arglist
alone (`[color talea]`) can't say which params want a `Data` literal vs
a bare scalar, only the registerer knows that. `register-algo!`/
`unregister-algo!`/`algos` (`musics.clj`, thin wrappers over
`input.algo-registry`'s own) let a user park their own fn under a new
name directly from the REPL, no walker/grammar change or recompile
needed: `(register-algo! "myAlgo" my-fn "optional doc")` and
`@[ myAlgo ... ]` works the same session; `(algos)` lists every
registered name with its doc's first line, `(algos "name")` shows the
full doc.

`walk-atomic-algo`/`run-algo` never push/pop/register `AtomicAlgo` as a
container of its own at all, at any nesting depth — it's purely a
compute-then-splice (top level) or compute-then-pass-through (nested)
step (the splice case is the same shape a transient command like
`\times`/`\tuplet` already has, see "Transient containers" below), so it
can never be independently addressed or referenced the way a real
`Sequence`/`Data` container can. Its `Data` args are walked via
`walk-data-values`, which deliberately only *peeks* the scratch `:DATA`
container it builds rather than popping it — popping is what registers a
container in `:repo` and links it onto a parent's `:children`, neither
of which is wanted for an operand that only exists to feed a function
call. A bare `Primitive` arg goes through the analogous
`walk-single-value` instead (same scratch-and-peek trick, one node
instead of a whole `Data` node's children); a nested `AtomicAlgo` arg
goes through `run-algo` itself, recursively.

To repeat an `AtomicAlgo` call's output rather than baking repetition
into the algorithm itself, wrap it in `\repeat unfold N { ... }` — the
existing `Iterator` mechanism (see "Grammar" below) replays whatever the
algorithm generated once, N times, at play time. The braces are
required, not decorative: `walk-repeat` only recognizes a body that's
literally a `{ }` `Sequence` (`find-child children :Sequence`) even
though the grammar's own `Element` allows far more — a bare `AtomicAlgo`
call as `\repeat`'s direct body currently parses but silently does
nothing at the walker level, an existing narrow gap, not something this
wiring changed.

`algo.common.isorhythm/color-talea` (registered as `"colorTalea"` by
default — the hyphenated `"color-talea"` would work exactly as well
under the `AlgoName` token, it just isn't also pre-registered under
that spelling) is the one built-in example — see "Meter and
indispensability"-adjacent isorhythm docs in that namespace itself for
the color/talea technique. It leans on `BareDuration` (`musics.ebnf`,
a duration value with no pitch attached, `/4`/`/8.`/etc.) for authoring a
talea as pure data (`[/4 /8 /8 /4]`) independent of any color
(`[C4 D4 E4 F4 G4 A4 B4]`) — the durational counterpart of a bare
`Pitch` atom, both walking to the same `{:type :pitch/:duration :val
...}` shape via generic dispatch in `walk-element`'s `:Data`-child
cases.

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
- **`Unit` (`'{ }`) is a context-less container**: structurally a regular,
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
  no separate container survives in the tree. `\times`/`\tuplet`/
  `\transpose` spell their body with `Scope` (`( )`, see the bracket table
  below) precisely because it's transient in this sense, not a real
  `Sequence`; a grace decoration has no dedicated bracket at all -- it
  takes two bare `Element`s directly (`\grace c8 d4`), so there's nothing
  to distinguish there. They still get their own
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
- **Context has no parent pointer** (`core/domain/context.clj`), for
  **containers**. The "enclosing scope" is visit-dependent — the same
  container can be reached through different parents if its id is reused
  — so lookups take an explicit `ctx-chain` (nearest-first vector of
  `Context`s) built by the traversal doing the walking, not stored on
  the data. `ctx-value-chain` walks that chain and only accepts a
  context's envelope if it has a point at-or-before the query time;
  otherwise it falls through to the next context, so a later instruction
  can't retroactively hide a still-valid outer value.
  **Leaves are the one deliberate exception** (`Leaf`/`Rest`/`Drum`, both
  still plain maps): each carries a baked-in `:ctx-chain`, a nearest-
  first vector of `[Context relative-offset]` pairs snapshotted by the
  walker at the moment it's built (`flat-core-builder/current-context-
  chain`) — every ancestor Context on the stack at that point, paired
  with how far into THAT ancestor's own local timeline this leaf sits
  (`d/duration` of that container as constructed so far, the same
  quantity `duration`/`ctx-append` already use as their own time
  coordinate). This is safe specifically because a leaf, unlike a
  container, is never independently re-referenced by a different path
  (`\repeat`'s body is always a real container, never a bare leaf) — so
  "baked once, correct forever" doesn't reintroduce the problem the
  no-parent-pointer design exists to avoid for containers.
  Motivation: `sq` (`musics.clj`) returns a container's bare `:children`
  — none of that container's own `:context` (its `!instrument:`/
  `!tempo:`/`!mf`/etc.) travels with it once extracted, so a leaf played
  standalone (`(play (times 12 (sq :verse)))`) used to resolve against
  whatever minimal `ctx-chain` the *new* top-level play call built (often
  just `[ROOT-ctx]`), silently losing `:verse`'s own values entirely —
  confirmed live with a mock MIDI receiver: `(play :verse)` sent
  `[:program-change 0 32]` correctly, `(play (times 12 (sq :verse)))`
  sent `[:program-change 0 0]` (piano) and velocity 50 (ROOT's raw
  default, not `!mf`'s). `core.domain.resolve/effective-chain` re-bases
  each baked ancestor by `(structural-time - relative-offset)` at
  resolve time, which reconstructs exactly the entry point that
  ancestor's own container would have had — numerically identical to
  `build-chain`'s own per-container shifting for ordinary playback, and
  correct for a standalone/extracted leaf too. The relative-offset
  subtraction is load-bearing, not a simplification skipped for
  convenience: shifting every baked ancestor uniformly by
  structural-time alone (no offset) was tried first and broke ramp
  interpolation for ordinary playback — a ramp spanning several leaves
  collapsed to its start value on every one of them, since shifting
  them all to "right now" erases their relative spacing. Verified live
  both ways: a `!vol:30<l ... !vol:80` ramp across 4 notes plays
  `[30 43 55 68]` normally, and `(times 2 (sq :verse))` on the same
  material plays `[30 43 55 68 30 43 55 68]` — each repeat correctly
  re-interpolating fresh from 30, not flattened, not carrying over
  where the previous repeat left off.
  A leaf built directly (not through the real walker — ornaments'
  expanded sub-leaves, `algo`-registry-generated leaves, `warm-up!`,
  most unit tests) simply has no baked `:ctx-chain`, and
  `effective-chain` falls back to whatever `ctx-chain` was threaded in
  externally, exactly as before this mechanism existed — nothing about
  that path changed. `core.domain.persist`'s freeze/thaw (needed since
  `Context` holds atoms, not directly EDN-readable) was extended the
  same way it already handled a leaf's own `:context`.
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
  Each voice walks its own `:tx`'s view directly and just-in-time -- no
  pre-flattening step -- so `:SEQ` runs its children one after another
  inside one voice (a go-block), `:PAR` forks each child into a sibling
  voice the parent awaits on, and each leaf is resolved via `resolve-event`
  right as it fires. This also means `:count :infinite` Iterators fall
  out for free, no separate lazy/eager code path needed; live redirects
  work too, just per-voice now (a `(schedule-tx! ...)` cutover on ONE
  voice) rather than one shared pointer every voice re-read continuously.
  Each voice also carries its own `:bar`/`:bar-pos`/`:marks`/`:tx` atoms
  alongside `:clock`/`:structural` (forked, not reset, at `:PAR` -- see
  "Conductor" above), advanced by `advance-bar!`/`mark!` right alongside
  the clock. `*engine*` is a dynamic
  var so REPL calls (`play`, `stop!`, `pause!`, `resume!`) don't need to
  thread an engine value around; `pause!`/`stop!` are checked in ~20ms
  increments even mid-note, so pause freezes a sounding note in place (no
  retrigger on resume) and stop sends note-off promptly instead of waiting
  out the full duration. `play`'s args are a small mini-language (bare
  keyword = repo reference; `[optional :par/:seq tag, then a leading run of
  context-refs, then material]`, tag defaults to `:seq` if omitted, and
  is obligatory for parallel playback) -- see the docstrings in
  `async_engine.clj` for the full grammar and examples. A group's tag
  doesn't have to be that literal leading keyword, either: `musics.clj`'s
  `sq` (the one function that turns a container's children into a bare
  seq) tags its own output `{:parallel? bool :id id}` via metadata, since
  flattening a container into a seq leaves no data-level place left to
  carry a `:par`/`:seq` tag the way a literal `[:par ...]` vector has one
  built in -- `form-tag+items` (shared by `play-form`/`validate-ids!`/
  `realize-form`) checks the literal leading keyword first, then falls
  back to that metadata, so `(play (sq :chorale))` on a genuinely `:PAR`
  container plays back in parallel with no `[:par ...]` wrapping needed.
  This only survives an *untransformed* `sq` result, though -- metadata
  isn't preserved across most seq transforms (`map`/`filter`/`times`/
  `transpose`/...), so `(times 2 (sq :chorale))` falls back to plain
  `:seq` dispatch once material has actually been reshaped, which is
  correct: a transformed result no longer claims to *be* the original
  container. A play-arg form of `nil` (most concretely: `sq` itself
  returning `nil` for an id that doesn't resolve to a container) is
  rejected with a clear `ex-info` rather than silently producing no
  sound -- `validate-ids!` for `play` (its own synchronous pre-flight
  guard, run before any voice starts) and `realize-form` for `display`
  (which has no separate guard of its own, being fully synchronous
  already). `play-form`'s own analogous branch stays a silent no-op
  deliberately: a `throw` inside a `go` block never reaches the caller
  (confirmed live -- `(<!!)` on a channel whose go-block body threw just
  returns `nil`, the channel simply closes), so `validate-ids!` catching
  it beforehand is the only place that can actually surface an error.
  This check is deliberately narrower than "reject anything non-
  keyword/non-sequential" -- an earlier version of it was that broad
  and broke real material: `sq`'s own unfiltered output includes inline
  `:assignment` nodes (the walker's record of a written `!tempo:`/`!mf`/
  etc. instruction -- its real effect already landed on its siblings'
  shared context back at parse/walk time), which `play-node` has always
  silently tolerated during an ordinary container walk (its own `:else`
  no-ops on any child shape it doesn't specifically recognize) --
  confirmed live: `(play (times N (sq :verse)))` on material containing
  one of these threw under the broader guard even though `(play :verse)`
  directly, no `sq` involved, never did. Only `nil` is actually rejected;
  anything else unrecognized falls through to the same tolerance
  `play-node`/`realize-node` already have.
  Real MIDI output goes through `output.midi.midi-live`'s `Receiver`,
  passed in as the
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

`indispensability` (`algo/indisp/indispensability.clj`) computes Barlow
indispensability: given an ordered subdivisions factor sequence, every
pulse `0..N-1` gets a rank, downbeat always `N-1`. The combination rule is
one formula for any factor: substitute each level's raw digit through that
factor's base table (2/3/5/7 — see `indispensability-base-tables`) rotated
left by one position, then recombine using the same place-value structure
as the pulse index itself. 2 and 3's rotated tables happen to reduce to the
identity permutation (their reference tables are pure rotations); 5 and 7
don't, which is the actual substance of the theory, not a rounding
artifact — verified against known-correct reference tables, not derived
from scratch. `common/music_elements.clj`'s `meter-indispensability` just
wires a `Meter`'s own `num`/`den`/`subdivisions` into it (`(or subdivisions
(default-subdivisions num den))`), so `common.music-elements` requires
`algo.indisp.indispensability` rather than keeping a second copy of the
algorithm itself — the earlier standalone `algo/` port of this same theory
(`psi`/`psi-fractions`, from pymusics' `indispensability.py`) turned out to
skip the base-table substitution step entirely, agreeing with this
implementation only for factors 2/3 and silently diverging for 5/7; it was
removed in favor of this one rather than kept alongside it. Bar-length
itself (for `core.conductor`'s `:bar` signals) only needs `num`/`den`, not
indispensability — the two are independent consumers of the same `Meter`.

### Grammar (`src/input/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

Current bracket scheme (differs from the older docs — check the `.ebnf` when
in doubt):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `{ }`     | `Sequence`    | musical sequence                  |
| `<< >>`   | `Parallel`    | simultaneous parts                |
| `'{ }`    | `Unit`        | grouped elements, no context of its own — a real, addressable container |
| `( )`     | `Scope`       | `\times`/`\tuplet`/`\transpose`'s body, a `VarDef`'s value — never a container of its own, always spliced/stashed into something else |
| `[ ]`     | `Data`        | data container                    |
| `@[ ]`    | `AtomicAlgo`  | algorithm over data — wired to real execution, see "AtomicAlgo" below |
| `@{ }`    | `ElementAlgo` | algorithm over elements — still inert, see "AtomicAlgo" below |
| `^{ }`    | `Context`     | named context/envelope definition |

**Every top-level program needs at least one real wrapping container.**
`TopElement` (`Program`'s own top-level element list) is `Composite |
repeat | tremolo | VarDef` — a bare, un-nested `c4 d4 e4` with no `{ }`
around it is not valid `Program` text on its own. This is deliberately
narrower than `Element` (used everywhere *inside* a container, where
`Leaf`/`Instruction`/`Reference`/`VarRef`/transient `Command` are all
still completely ordinary): every one of those, if reachable bare at
`Program`'s own top level, can write directly into whatever context is
on top of the builder stack — before any real container has been
entered, that's `:ROOT` itself, which is meant to be a read-only
endpoint with a guaranteed value for every key (`common.defaults/
root-defaults`, `core.domain.context/context-root`). Three separate,
independently-confirmed-live write paths existed before this
restriction: a bare `Instruction` (`!vol<...!vol>`, no container of its
own); a bare *transient* `Command` (`\times`/`\tuplet`/`\transpose`/
`\grace` — not `\repeat`/`\tremolo`, which persist as real retained
containers and were never affected — `pop-container` replays any
instruction written inside one onto whatever's on the stack once its
wrapper splices away); and a bare `Leaf`/`Chord` with a note-glued
dynamic (`c4\f`, ordinary surface syntax — `apply-note-dynamics!`
writes through the same mechanism a standalone `!f` does). A bare
`Reference` (when it resolves to a `^{ }` `:CONTEXT` block) and a bare
`VarRef` replay a stashed envelope onto current-context the same way.
`Part` is `Composite | Leaf | Reference | VarRef` — since three of its
four alternatives can each reach `:ROOT` this way, and the third
(note-glued dynamics) can't be split out of `Leaf`'s own grammar rule
without much deeper surgery, `TopElement` keeps only `Composite`
(a real container) reachable, plus `repeat`/`tremolo` (safe for the
same reason `Composite` is: each gets its own genuine, persistent
context before anything nested is walked) and `VarDef`. See
`musics.ebnf`'s own comment on `TopElement` for the full detail and
exactly which live test confirmed each path.

`:ROOT` being grammar-guaranteed write-once is also what lets its own
context values skip the general `Envelope`/`Point`/atom machinery
entirely: `core.domain.context/ValueSource` (`sample-at`/`shift`,
`extend-protocol`'d over `Envelope` and a bare-value fallback) lets
`context-root` store each default as a plain value directly — no
allocation for something that, by construction, can never receive a
second point. Any other context still builds a real `Envelope` as
before (a `!tempo:90` inside `{verse: ...}` could still legitimately
grow into a ramp later); the protocol dispatch is what lets
`ctx-value-chain`/`ctx-shift` treat both shapes uniformly without
needing to know in advance which one a given key holds.

`Unit` and `Scope` used to share one bracket (`( )`), which was genuinely
confusing: `Unit` is a real, registered, addressable container (keeps an
id, appears in `:children`, just with no `:context` of its own), while a
`Scope` — `\times`/`\tuplet`/`\transpose`'s body, or a `VarDef`'s value —
looks identical on the page but is never registered at all; its content is
always spliced into the parent (the transient command types) or stashed
into `:var-map` for a later `VarRef` to splice (`VarDef`), never surviving
as a node of its own. Splitting them onto different brackets makes that
distinction visible instead of requiring you to already know which of the
two any given `{ }`-shaped-looking thing actually is. `\repeat`'s own body
and `\alternative`/measured tremolo's body are deliberately **not**
`Scope` — those genuinely persist as real, retained containers (an
`Iterator`'s `:source`/`:alternative`, kept around to replay on each
iteration), so `{ }` (`Sequence`) is the correct bracket for them, same as
always.

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT` whose envelope
points get replayed onto the current container's context at the current beat
offset — see `apply-context-ref` in `flat_tree_walker.clj`). `VarDef` is
`name = ( ... )`; `VarRef` is `\name` — see "Comments and variables" below.

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
onto a note/chord (`c4\f`, `c4\<`, `c4\mf<` chainable) reads the same as
writing the equivalent standalone `!f`/`!vol<` just before it, taking
effect from that note's own onset. Absolute octaves need a **capital**
pitch letter (`C5`); lowercase is always relative pitch resolution (nearest
fourth/fifth, LilyPond `\relative`-style) even as a sequence's first note —
there's no position-based exception.

A `Ramp` (`!key<...`, any context key) has four shapes: bare open-ended
(`!vol<`, `!vol>` — marks a ramp-start with no target, interpolating
toward whatever value comes next), bare with a curve (`!vol<s` —
`l`/`s`/`i`/`o` = linear/smooth/ease-in/ease-out), timed (`!vol<16:ff` —
duration, a raw whole-note count/product/ratio, not a note-value
reciprocal the way a note's own Duration digit is, then a target), and
timed with a curve (`!vol<s:16:ff`). `!key:value<` (`!vol:mf<`,
optionally `!vol:mf<s`) sets the value *and* marks a ramp-start in one
instruction — the standalone-Assignment equivalent of a note-glued
`c4\mf<` chain (see above), generalized to any key rather than just
volume, and not tied to a note. `c4\mf<` itself is the newer, shorter
spelling of the same note-glued idea — `c4\mf\<` (two backslashes, one
per suffix) still parses unchanged, since `Hairpin`'s own leading `\`
means it's never ambiguous with `Dynamic`'s new bare trailing direction.
Deliberately *not* mirrored onto `BangConst` (`!mf<` was considered and
rejected) — `Name` has no exclusion list, so a trailing direction there
would collide with `Assignment`'s own bare-Ramp alternative: `!p<`
already parses today as `Assignment`(`AssignName` "p") + `Ramp`(bare
"<"), since `p` is a registered `:panning` alias, and `p` is also a real
`DynamicMark` word (pianissimo) — a directly demonstrable ambiguity, not
a hypothetical one. Standalone direction is always bare (`<`/`>`, no
`\`) since `!` already marks "this is an instruction"; note-glued
direction keeps its own `\` whenever it's *not* immediately chained onto
a `Dynamic` mark (a bare `Hairpin`, `c4\<`) — that's LilyPond's own
spelling for a hairpin, unrelated to the newer shorthand.

A bare (unmarked) pitch letter resolves against the active `Key`'s own
implied accidental by default — real staff-notation behavior: under
`!key:D.major`, a bare `f`/`c` sounds sharped without writing so, same as
a key signature implies on a real staff, and an explicit accidental
(`fn`/`f#`/`fes`/...) always overrides that outright. This is a
deliberate departure from LilyPond itself, whose input is always
literal (key affects printing only) — gated by a context key,
`:accidentals` (`!accidentals:`), `:implied` by default or `:explicit`
for literal/LilyPond-style resolution, rather than tying the behavior to
`!key:` itself (which already has an unrelated existing use — ornaments'
scale-relative resolution — that shouldn't gain a silent side effect). C
major (the context default when no `!key:` is ever set) implies nothing
either way, so a piece that never sets a key is completely unaffected.
`lilypond_import.clj` emits `!accidentals:explicit` once, ahead of
everything else, on every converted piece — imported content's meaning
should never depend on this format's own default, since real LilyPond
source is always already literal.

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
- `algo/` (renamed from `algorithm/`) — generative helpers, organized into
  topic subdirs: `indisp/` (Barlow indispensability), `metric/` (modular/
  binary/continued-fraction pulse generators), `rithmic/` (Euclidean/
  Fibonacci/prime/L-system/Markov rhythm generators), `melodic/` (scales,
  generative melody methods, constraint-satisfaction walks), `random/`
  (distributions, chance/weighted-pick helpers, chaotic maps like
  `lorenz.clj`), and `common/` (`reshape.clj`'s sequence-reshaping
  recipes, general enough not to fit any one of the others). Mostly still
  standalone/unwired into the grammar or engine, same as before the
  reorg — `algo.indisp.indispensability` is the one exception:
  `common.music-elements/meter-indispensability` requires it directly
  (see "Meter and indispensability" above), so that one namespace is a
  real, live dependency now, not just a kept-for-reference algorithm.
- `java-reference/`, `julia-reference/`, `kotin-referenvce/`, `python-reference/`
  — ports/prior implementations of this same system in other languages, kept
  for cross-checking behavior, not part of the build.

## Known rough edges (found, not yet fixed)

One pre-existing quirk is still there — noted so it isn't silently
rediscovered as something new:

- **An `Id` inside a transient/scratch container's body is silently
  discarded**: `\times`/`\tuplet`/`\transpose`/a grace decoration's body,
  and a `VarDef`'s value, all walk their `Scope`'s (or, for a grace
  decoration, bare `Element`'s) children directly into a container that's
  never registered under its own id (transient ones get spliced into the
  parent and discarded; `VarDef`'s scratch container is popped by hand
  and never touches `:repo` at all). If that body happens to contain an
  `Id` (`\times 2/3 (myname: c4 d)`, or `motif = (myname: c4 d)`),
  `walk-bareword` still renames the container currently on the stack —
  it just renames a container that's about to vanish either way, so the
  name has no effect and produces no error. Same underlying mechanism,
  both places.
