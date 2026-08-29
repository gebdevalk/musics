# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Fluidsynth via a virtual ALSA MIDI port)
or rendered to a MIDI file. It's a REPL-driven project, not an app with a CLI —
the primary interface is `src/musics.clj`, evaluated interactively.

### Shape of the system

Three tiers, grouped by responsibility and dependency direction, not
by any enforced boundary — no protocol, interface, or seam separates
them at runtime. All three read `core.repo`'s registry directly,
whenever they want; there is no contract a lower tier owes an upper
one beyond "the data has this shape." "Tier" here means "what this
code is *for*," not "what this code can't reach." The top tier reaches
back into the other two rather than data only ever flowing forward:

1. **Material** — `musics.ebnf` (grammar) → `flat_tree_walker.clj`
   (walk) → `core.domain.flat_domain`/`core.repo` (the versioned,
   id-addressed store). Produces durable, addressable content: what
   the music *is*, parsed once at authoring/commit time.
2. **Sound** — `core.async-engine` (voices, one core.async goroutine
   per independent line) + `core.domain.resolve` (context sampling,
   actualization) + `output.midi.midi_live`/`midi_file` (MIDI
   dispatch). Turns committed material into real-time or rendered
   sound.
3. **The playground** — `play`'s own mini-language (`core.async-
   engine`, thin `musics.clj` wrappers) + `core.wall` (per-voice
   algorithms). Sits *above* the other two, not between them: it
   reaches into the repo to select already-committed material, and
   into the engine to spawn voices and assign algorithms, for one
   particular performance rather than describing the music itself.

`core.repo` (the versioned store) is shared plumbing underneath all
three, not a tier of its own — and it's the same global, mutable
registry every tier reads from directly (`core.registries`'s
`^:dynamic` atoms; see "Repo state" below), not something tier 2/3
access through an abstraction tier 1 could change without touching
both callers. That's a deliberate simplicity tradeoff for a
single-user REPL tool, the same one already reasoned through for
global-vs-instance state generally (`review.txt`, point 1) — not an
oversight this grouping is meant to paper over. What the grouping
*does* buy: it names where a given piece of code belongs and which
direction its dependencies run (tier 3 requires tiers 1/2; neither
lower tier requires tier 3), which is genuinely useful for finding
your way around the codebase, just not a claim that the tiers could
be swapped out or evolved independently of each other. Two satellite
capabilities feed material *into* tier 1 rather than belonging to any
tier themselves: `input.midi`/`input.midi-record` (capture a live
performance, emit musics text) and `input.lilypond-import` (convert
real LilyPond text). The GUI (`(musics/gui)`) wraps tier 3 for live
use, plus one satellite directly (its Record MIDI panel).

Tiers 1 and 3 share one *concept* — sequential-vs-parallel grouping —
but spell it differently on each side, and the spellings don't even
agree within a tier: tier 1 (`.mus` text) writes `[ ]` sequential /
`(par ...)` parallel; tier 3 (a `play` call) writes `[]` sequential /
`#{}` *or* `(par ...)` parallel, `#{}` still the shorter everyday
spelling there, `par` only for the one case `#{}` structurally can't
express (see "Wave 7" below). They stay genuinely different
*languages* regardless of surface overlap: tier 1 is text, parsed once
by instaparse into permanent content; tier 3 is Clojure data,
evaluated fresh at every call, describing a performance choice rather
than the music itself. That's why `:algo` tagging (a wall-algorithm
assignment) only ever exists on the tier-3 side, deliberately never
reachable from `.mus` text — see "Wall: per-voice playback algorithms"
below.

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

**Wave 5 — path-keyed voices, per-voice algorithms, MIDI input** (the
engine's fixed `:generation` counter and fixed-size wall-slot array both
became a single, unbounded `:voices` map keyed by each voice's own real
path; a registry of pluggable per-voice playback algorithms was added on
top of that; and the project gained a MIDI *input* side, having only
ever had output before):

- `core.async-engine`'s `:voices` (path -> voice) is now the one general,
  always-queryable live-voice registry AND the mechanism `play`/
  `play-change`/`play-add` all supersede/coexist through — replacing the
  earlier single engine-wide `:generation` counter. See "Session, the
  versioned repo, and playback" below for how this differs from `:tx`
  (Wave 4's own per-voice concern, untouched by this).
- `core.wall` + `core.async-engine`'s `:algo-assignments` let a composer
  assign a real algorithm (seq-in/seq-out, same shape `input.algo-
  registry` already uses) to a specific voice by its own path, hot-
  swappable mid-performance — `play`/`play-add` both mint a short, real
  track id (`:TAA`, `:TAB`, ...) and take an OPTIONAL algorithm via a
  tagged `[:algo name]` marker anywhere in their args, and every `:PAR`
  fork's own children get labeled from that same alphabet by ASCENDING
  MEAN PITCH. See "Wall: per-voice playback algorithms" below for the
  full design.
- `input.midi`/`input.midi-record` (`overtone/midi-clj`) add real-time
  MIDI input — `midi-through` (hear a plugged-in keyboard live) and
  `record-midi` (record a performance, quantize it, and spell it back as
  musics text). See "MIDI input: midi-through and record-midi" below.

**Wave 6 — the play mini-language and musics.ebnf converged on one
vocabulary** (the Clojure-side `play` mini-language and the text-side
grammar used to speak two unrelated bracket dialects; a long,
deliberate design pass unified their surface vocabulary — not their
semantics, see "Shape of the system" above — end to end):

- **`play`'s own mini-language was redesigned first**, independently of
  any grammar change: `[Form+]` is now always sequential (mirrors a
  Clojure vector), `#{Form+}` always parallel (mirrors a Clojure set) —
  no more `:par`/`:seq` leading-keyword tags, no more untagged-vector-
  defaults-to-`:par` guessing. `[Form :algo Name]` tags one Form with an
  optional walls-registered algorithm, recognized by fixed shape, not a
  marker scanned for anywhere in args. `play`/`play-add` take exactly
  one `Form` plus an optional trailing `:algo Name` now, not several
  top-level forms implicitly sequenced, and their return value
  recursively mirrors wherever `#{}` was actually written — see "Wall:
  per-voice playback algorithms" below for the full mechanism.
- **Parameterized and installable wall algorithms** followed:
  `[registered-name arg...]` feeds a registered algorithm concrete
  parameters inline, and `core.wall/configure-wall!` lets a factory be
  installed once under a fixed name and fed data independently of any
  `play` call, any number of times — deliberately one store, not a
  second cache, a documented simplicity tradeoff. See "Parameterized
  algorithms" under "Wall" below.
- **`musics.ebnf` itself was then migrated onto the same vocabulary**:
  `[ ]`/`#{ }`/`{ }`/`'[ ]` replaced `{ }`/`<< >>`/`^{ }`/`[ ]`, and
  `times`/`tuplet`/`transpose`/`repeat`/`grace` became Lisp prefix
  calls (`(times 2/3 [c8 d8 e8])`) instead of backslash-keywords.
  `Unit`, `AtomicAlgo`/`ElementAlgo` (grammar-native algorithm
  invocation — see "Algorithm registries" below for what replaced it),
  and `\time`/`\tempo`/`\key` (LilyPond-conformity concessions) were
  all dropped, motivated directly by `input.lilypond-import` having
  become a real, actively-maintained converter — this grammar no
  longer needs to double as a LilyPond superset itself. The whole
  project was migrated onto the new syntax in the same pass: every
  real `.mus` file (`mus/`, `data/`, test fixtures), the full test
  suite, and `lilypond_import.clj`'s own emitter.

**Wave 7 — `#{ }`/`Parallel` moved to `(par ...)`, closing the one gap
Wave 6 left behind** (a literal Clojure set can't hold the same value
twice — `#{:s1 :s1}` is a reader error, not just discouraged — which
`#{ }` inherited on both the grammar side and the play-arg mini-
language side as a pure surface-syntax accident: nothing about a real
`:PAR` container, `{:type :PAR :id id :context ctx :children [...]}`
with `:children` a plain, duplicate-tolerant vector built via `mapv`,
ever required set semantics in the first place):

- **`core.async-engine/par`** (`(par & forms)`) is the mini-language's
  own fix — a plain vector tagged `{:parallel? true}` in its own
  metadata, the exact mechanism `sq` already used to mark an extracted
  `:PAR` container's own children, just exposed as a constructor rather
  than only ever reached by extracting an existing container.
  `par-form?` (the one place deciding "is this Form a parallel group")
  and `form-tag+items` (already metadata-aware, for `sq`'s sake) both
  recognize it identically to a literal `#{...}` — checked every
  `set?` call site in `async_engine.clj` before changing anything, and
  only `par-form?` itself needed widening; `mint-branches!`/
  `play-form-par`/`realize-form-group`/`validate-ids!` all already
  worked correctly on a tagged vector with zero further changes. Plain
  Clojure `#{...}` play-arg literals still work (real, unrestricted
  Clojure remains real, unrestricted Clojure) — this is additive, not a
  breaking change on that side — but they're no longer how anything in
  this project's own docs/examples spells "parallel."
- **`musics.ebnf`'s own `Parallel` rule moved to the same spelling**:
  `<'('> ws? <'par'> ws (Id ws)? ... ws? <')'>`, replacing `#{ }`
  entirely (not left alongside it) — the walker needed zero changes
  (it dispatches on the `:Parallel` tag, never on which literal
  characters produced it), confirmed directly before relying on it.
  `par` slots into the exact same "reserved word right after `(`
  disambiguates" mechanism `times`/`tuplet`/`transpose`/`repeat`/
  `grace` already use — one more instance of an existing pattern, not a
  new kind of ambiguity — with one real difference: `par` is the only
  member of that Lisp-call family that's a registrable `Composite`
  (`(par chorale: ...)` registers `:chorale`, exactly like `Sequence`
  can), since the rest are all transient/spliced and were never
  addressable to begin with. The whole project was migrated in the same
  pass, same discipline as Wave 6: every real `.mus` file, the full
  test suite, `lilypond_import.clj`'s own emitter (which used to emit
  `#{ }` for LilyPond's `<< >>`), and `flat_domain.clj`'s
  `print-structure` bracket table.
- **`input/forth.clj`'s own bare-musics-text recognition needed a real
  fix, not just a find-replace**, discovered live, not anticipated:
  Forth already claims bare `(` for its own `( comment )` syntax,
  checked before musics-text recognition, so `(par ...)` needed an
  explicit carve-out (`musics-open-at` now recognizes `"(par "`
  specifically, the one member of the family that's a valid whole
  `Program` on its own, so it's the only one that needs bare top-level
  recognition at all) — `#{ }` never collided with anything Forth
  already used, so this exact class of collision never had to be
  solved before. A second, subtler bug followed directly from `)` now
  being a real closer in this grammar: a `Command`/`StructValue` sitting
  **directly** as a `Parallel` element (`ParElement` allows this, no
  wrapping `[...]` required) could have its own closing `)` mistaken
  for the enclosing `(par ...)`'s, ending the Forth scanner's bracket-
  tracking early — confirmed live with `(par (times 2 [c4 d4]) [w:
  e4])` actually mis-tokenizing into three tokens before the fix, not
  just reasoned about. Fixed generically (`scan-musics-chunk` now
  tracks ANY bare `(` as needing its own `)`, not one `musics-openers`
  entry per reserved word) rather than enumerated, so it also covers
  `StructValue` and any future Command spelling with the same one
  branch. A slur glued onto a note turned out **not** to need this at
  all, confirmed by testing it, not assumed: a slur can only ever be
  reached through a wrapping `Sequence` first (it's glued to a Leaf,
  and `ParElement` doesn't include bare `Leaf`), so `]` stays the
  currently-expected closer the whole time a slur's own `)` appears —
  structurally safe, not just empirically lucky.

If you find something that still assumes the old (pre-flat, pre-
`core.repo`, pre-Wave-6-grammar, or pre-Wave-7-`(par ...)`) model
exists, that's stale — update or remove it rather than working around
it.

## Commands

Leiningen project (`project.clj`), Clojure 1.12, two dependencies:
`instaparse` (parsing) and `org.clojure/core.async` (the playback engine).

```bash
lein repl              # start a REPL (init-ns is `user`)
lein test               # run the full test suite (test/ dir)
lein test command-walk-test         # run a single test namespace
lein test :only command-walk-test/times-scales-durations   # single test var
lein test :parsing      # just one architectural layer -- :parsing/:domain/
                         # :engine/:repl/:forth/:algo (test-selectors in
                         # project.clj, grouped per this file's own module
                         # boundaries -- :algo is algo/'s own generative-
                         # material tree, split out from :domain since it's
                         # a different layer, not core.domain.*/common.*
                         # (the real domain model, which stayed :domain)
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
  │    name, so [verse: ...] never wastes a :s-prefixed slot it won't use)
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
only bare strings are intercepted, so an *unquoted* `[verse: ...]` reads
as an ordinary (and invalid) Clojure vector before `music-eval` ever
sees it, same as it would at the outer REPL. `(c1!)` commits whatever the
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
  `musics.clj/parse` uses — a single `(parse "[a: ...] [b: ...]")` call
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
`core.repo` entirely and re-bootstraps a fresh `:ROOT`. `write`/`load`
only ever round-trip material, though — not the performance layered on
top of it (a voice's `:algo` assignment can transform pitch/duration
wholesale, so this was a real, confirmed gap: reopening a saved piece
could sound nothing like what was actually saved while listening to
it). `persist-session`/`restore-session` are the fuller pair for that —
same repo+auto-ids round-trip as `write`/`load`, plus the current
engine's `algo-assignments` (path -> Name, the composer-typed `:algo`
tag/`assign-algo!` argument — EDN-safe by construction, unlike the
resolved wall fn itself, a live closure that can never survive a
round-trip; `assign-algo!` keeps both, `{:name Name :fn resolved-fn}`,
specifically so persist-session has something real to read). Restoring
replays each Name through `assign-algo!` again against whatever's
registered in the CURRENT process — a Name whose algorithm isn't
re-registered yet degrades to `identity-wall` with a console warning,
same as `assign-algo!` always has, not a new failure mode.
Deliberately does NOT also cover `core.wall/configure-wall!`'s own
last-applied factory+args (once resolved, the factory identity is gone
by design — "one store, not two" — see `core.wall`'s own docstring) or
`core.conductor`'s schedule/repeating tables (pending cues in ONE
specific live performance, not composed material — closer to a paused
breakpoint than a saved document) — both left as documented, deliberate
gaps rather than silently declared solved. `write`/`load` themselves
are unchanged, not superseded — `core.persist` (moved from
`core.domain.persist`, see that ns's own docstring for why) grew a
second, additive `session->edn`/`edn->session` pair alongside its
original `repo->edn`/`edn->repo` rather than either function changing
shape.

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

### Wall: per-voice playback algorithms

See `doc/algorithms.md` for the practical, use-it-from-the-REPL guide
(the taxonomy of algorithm shapes, which ones this mechanism actually
reaches vs. which are plain Clojure calls, worked examples) — this
section stays the mechanism's own architecture reference.

`core.wall` (`src/core/wall.clj`) is a registry of pluggable playback
transforms — a parked toolbox: `name -> {:fn f :doc doc}`,
nothing more. A wall fn is always seq-in/seq-out: `(nodes ctx-chain
voice) -> nodes'`, called identically regardless of granularity —
`core.async-engine`'s container branch calls it once, on the WHOLE
sibling list, before either `play-par`/`play-seq` or ornament expansion
ever sees it; its leaf/rest/drum branch calls it with a singleton
wrapping one already-ornament-expanded node. An algo never declares
which one it "acts on" — it just always receives a seq.
`register-wall!`/`unregister-wall!`/`walls`/`configure-wall!` (thin
`musics.clj` wrappers over the registry) manage it — `core.wall/wall-fn`
itself (the raw name -> fn lookup) has no `musics.clj` wrapper, `require`
`core.wall` directly if you need it. Only the verbs that actually assign
a registered fn to a specific voice are named `*-algo*` (below), not
this registry itself; a naming inconsistency left as-is for now, not
swept.

**Voice paths, not slot numbers**: every voice's own registry key
(`core.async-engine`'s `:voices` AND `:algo-assignments` atoms — one
address space, not two) is a vector, root-first, one segment per level
of forking. `assign-algo!`/`algo-assignments` (`core.async-engine`,
thin `musics.clj` wrappers of the same name — renamed from
`assign-wall!`/`wall-assignments`, the earlier "wall slot" framing
being, per the user, "a bit pompous" for what's really just a plain
path -> algorithm map) set/read a path's own concrete fn, resolved ONCE
at assignment time (not re-looked-up by name later, so a later
`unregister-wall!` doesn't retroactively affect an already-assigned
path) — default (path unassigned) is `identity-wall`, a no-op. The
stored value is `{:name Name :fn resolved-fn}`, not a bare fn — `:name`
is kept alongside `:fn` specifically so it's EDN-serializable
(`persist-session`, see "Session, the versioned repo, and playback"
above), the resolved fn itself never being able to survive that on its
own. Hot-swappable at any moment, mid-performance: `voice-wall-slot-fn`
re-reads `:algo-assignments` fresh on every single node a voice visits,
never once at fork time.

**Mean-pitch-ranked `:PAR` children**: every fork — a real repo `:PAR`
container's children (`play-par`), or a `#{...}` play-arg group handed
to `play`/`play-add`/etc. (`play-form-par`, and `mint-branches!` for a
bare top-level `#{}` — see below) — labels its own children
`:TAA`/`:TAB`/`...` by ASCENDING MEAN PITCH, lowest pitch getting the
lowest id ("lowest voice lands in slot 0", the mixing-desk convention
this project has always used for `:PAR` ordering). `rank-segments`
backs `play-par`/`play-form-par`; `mint-branches!` inlines the
equivalent sort itself, since it also has to decide, per branch, whether
to mint a real voice or recurse (see below) — a real container's mean
pitch is `core.domain.flat-domain/mean-pitch`, an O(1) read off
`:pitch-sum`/`:pitch-n` baked onto every container at parse time
(`flat-core-builder/pop-container`, alongside duration); a play-arg
group's own children resolve a bare keyword against the live repo first
(`form-pitch-source`) — anything else (a nested group, already-`sq`'d
raw seq material) has no single node to measure, so it sorts last, same
as silent content does.

**The play-arg mini-language: `[]`=sequential, `#{}`=parallel, tags.**
A `Form` is a bare keyword (a repo reference), `[Form+]` (sequential —
mirrors `Sequence` in `musics.ebnf`), `#{Form+}`/`(par Form+)`
(parallel — mirrors `Parallel`; `par` is the canonical spelling now,
see "Wave 7" above and `core.async-engine/par`'s own docstring for why
— `#{...}` still works identically for its own common case, just can't
express a repeated Form the way `par` can), or `[Form :algo Name]`
(exactly one Form, optionally
tagged with a walls-registered name or `nil`). This replaced an earlier
scheme where an untagged vector defaulted to `:par` unless an explicit
`:par`/`:seq` leading keyword said otherwise (`form-tag+items`'s own
former literal-keyword branch, since removed) — deliberately harmonized
with the text grammar's own bracket duality instead: the collection
type alone is the tag now, vector always `:seq`, set always `:par`, no
guessing. `musics.ebnf`'s own container brackets were later brought
into line with this same mini-language (Wave 6, `[ ]` on both sides;
Wave 7 then moved Parallel's own spelling again, on both sides
together, from `#{ }` to `(par ...)` — see "Grammar" below and "Wave
7" above), not just a mirrored shape under different brackets, so the
two are literally the same vocabulary today, not just structurally
analogous — a plain Clojure `#{...}` set literal still works as a
play-arg (see `core.async-engine/par`'s own docstring for why it's
additive, not a breaking removal on that side), it's just no longer
the spelling either side actually documents or uses by default.
`musics.clj/sq`'s own `{:parallel? bool}` seq
metadata is untouched by this and still wins FIRST in `form-tag+items` —
sq's output is always a plain vector, never a set, so without that
metadata check winning first a genuinely parallel container would
silently play back sequentially once flattened through `sq`. Context-ref
peeling differs by shape too: a `[]` group still peels only a *leading
run* (order matters, same as always); a `#{}` group has no "leading" to
speak of, so every item resolving to a `:CONTEXT` is pulled out
regardless of position (`split-contexts-unordered`).
`tagged-form?`/`split-tag` recognize `[Form :algo Name]` by FIXED SHAPE
(a vector, exactly 3 elements, `:algo` at index 1) — replacing an
earlier `[:algo name]`-marker-scanned-for-anywhere-in-args scheme
(`algo-marker?`/`extract-algo`) now that tagging is part of the Form
grammar itself, recursive at every level, rather than a special
top-level-only marker. A tag's algorithm always goes through the SAME
mechanism every voice already goes through — `:algo-assignments` +
`assign-algo!` + `voice-wall-slot-fn`, no separate one-shot/direct-apply
path — in one of two temporal patterns: **permanent**, for a voice being
freshly minted/forked right here (`play`/`play-add`'s own top-level tag,
and each `#{}` branch's own tag, via `resolve-form-tag`), covering that
voice's entire remaining life; or **temporary push/pop**, for a tag
sitting inside an ongoing `[]` walk where the same voice continues on to
more material afterward (`play-form-tagged`) — the CURRENT voice's own
path is reassigned for exactly the span of playing the tagged Form, then
restored to whatever was there BEFORE (not unconditionally to identity,
so a tag nested inside an already-tagged outer span correctly falls back
to the outer tag afterward, not identity). A `#{}` tagged as a whole
applies its algorithm to every branch as that branch's own DEFAULT — a
branch's own closer tag still wins (`resolve-form-tag`, shared by
`mint-branches!` and `play-form-par` alike, so a `#{}`'s own tag behaves
identically whether it's at `play`'s own top level or nested inside
other material).

**`play`/`play-add` mint one or more track ids from a SINGLE Form, plus
an OPTIONAL trailing `:algo Name`.** `(play Form)` or `(play Form :algo
Name)` — both `core.async-engine` fns (thin `musics.clj` wrappers),
neither accepting several top-level forms implicitly sequenced anymore
(`(play :verse1 :verse2)` is now `(play [:verse1 :verse2])`, matching
the same one-Form discipline every nested level already has —
`split-call-args` parses the call's own `& args` against this same
`:algo`-at-a-fixed-position discipline `tagged-form?` uses one level
down). `mint-branches!` recursively mints a real, addressable top-level
voice (`mint-leaf!`, using the SAME free short track id allocation as
before — `next-track-id`/`track-ids`, `:TAA`.."`:TZZ`") for every part of
`Form` that isn't itself an immediate `#{}` — a `#{}` branch whose own
content is IMMEDIATELY just another `#{}`, with nothing else of its own
to play, never gets an intermediate wrapping voice for that fact alone;
it recurses straight into its own children instead, which pull ids from
the exact same shared, occupancy-checked pool the outer level does, not
an independent range — "every voice/track gets an id, not subparts."
The return value mirrors this exactly: a single id for a plain Form, or
(recursively) a `#{}` of ids for a `#{}` Form, matching wherever `#{}`
was actually written — `(play #{:melody :bass})` -> `#{:TAA :TAB}`,
`(play #{:melody #{:a :b}})` -> `#{:TAA #{:TAB :TAC}}` — every entry a
real, directly usable top-level path on its own, no reconstruction
needed, unlike the earlier scheme where a `:PAR` group's own children
were only reachable by manually appending a rank-segments-assigned
segment onto the ONE id `play` returned. Both still return
straight-back-into-`assign-algo!`/`voice-at`/`play-change`/`play-add`-
usable ids/paths.
`play` flushes EVERYTHING first, same as it always has — a solo call
deterministically lands on `:TAA`, since nothing else survives the
flush; `play-add` never flushes, same as it always has — joining what's
already there means a later call has to skip whatever's already
occupying an earlier id. Args are validated (`validate-args!`) BEFORE
either one's own mutation (the flush, or any algorithm assignment) —
`play-top-level!` runs it before `pre-fn`/`mint-branches!` ever touch
anything — a rejected/typo'd call still can never disturb `:voices` or
leave an orphaned `:algo-assignments` entry behind, exactly the same
tested invariant this project already held for `play`'s own flush before
this change. `play-change` keeps its own older explicit-path/variadic-
args shape (via `start-top-level-voice!`, unchanged) rather than
`play`/`play-add`'s newer single-Form-plus-`:algo` one — it always
targets exactly one already-known path, so none of `mint-branches!`'s
"how many voices, and which ids, does this call need to invent" logic
applies to it. `display` (`core.async-engine`'s fully synchronous,
`*engine*`-free preview of what `play` would do) mirrors the same
`[]`/`#{}`/tag dispatch (`realize-form`/`realize-form-par`/
`realize-form-group`) but keeps its own older variadic-args shape too,
same reasoning as `play-change`; its `realize-form-par` now explicitly
mean-pitch-ranks its own children before showing them; a real `[:PAR]`
container never needed that (a literal, ordered `[:par ...]` vector
used to just get walked in written order), but `#{}` has no reliable
order of its own to fall back on. A tag has no visible effect on
`display`'s own output — it's purely structural/timing preview, with no
`:algo-assignments` to model at all — `realize-form`'s `tagged-form?`
branch just unwraps and realizes the inner Form.
A literal `#{}` still can't hold the same value twice (`#{:s1 :s1}` is a
reader error, not just unusual, and neither does two identically-tagged
branches save it — `#{[:s1 :algo :a] [:s1 :algo :a]}` collides too, since
the two tag vectors are `=`) — but "the same part against itself in
parallel" (Reich-style phase music, a canon voice imitating itself, two
untransformed copies) no longer needs a workaround for that: `(par
:s1 :s1)`, or `(par [:s1 :algo :a] [:s1 :algo :a])` for two copies
running the identical algorithm, both illegal as a literal `#{...}` and
both fine via `par` — see `core.async-engine/par`. `par`'s own Form is
an ordinary vector (never restricted on duplicate values) tagged
`:parallel?` in its own metadata, the exact mechanism `sq` already uses
to mark an extracted `:PAR` container's own children — not a new
mechanism, just exposed as a constructor rather than only ever reached
by extracting an existing container. `par-form?` (`core.async-engine`'s
one place deciding "is this Form a parallel group") and
`form-tag+items` both recognize either shape identically; `#{}` itself
is unchanged and still the natural, terser spelling whenever branches
are naturally already distinct.

**Parameterized algorithms: inline args, or install-once/configure-later.**
`Name` in a tag (or `assign-algo!`'s own `name` argument) was bare-name-
or-`nil` only; it can now also be `[registered-name arg1 arg2 ...]`, to
feed `registered-name`'s own registered **factory** — `(fn [arg1 arg2
...] -> wall-fn)`, not a plain 3-arg wall fn — concrete parameters right
at the point of use: `(play :melody :algo [:transpose 5])`. Which shape a
given `register-wall!` call uses (plain fn vs. factory) is the
registerer's own choice, undetected by the system — it only matters once
something actually calls that name WITH args. `core.wall/apply-factory`
is the one shared resolution step (`resolve-algo-name` in
`core.async-engine`, used by `assign-algo!` — every `Name`-consuming call
site, `play-form-tagged`/`play-form-par`/`mint-leaf!` included, funnels
through `assign-algo!` itself, so this one change covers all of them):
an unregistered name, a factory that throws applying its args, or a
result that isn't itself a fn all print a plain console warning and fall
back to `identity-wall` rather than erroring — including a plain
unregistered bare name now too (previously silent; made consistent with
the two new failure cases rather than left quietly different).

`core.wall/configure-wall!` (thin `musics.clj` wrapper) is the OTHER way
to reach a parameterized algorithm — install a factory under a fixed,
known name ahead of time (`register-wall!`, nothing new needed for that
half), then feed it args independently of any `play`/`assign-algo!` call,
any time, any number of times: `(configure-wall! :verseColor talea1
color1)`, then `(play :verse :algo :verseColor)` — every future bare-name
reference to `:verseColor` picks up whatever was most recently
configured, while an already-running voice is untouched either way (same
resolved-once-at-assignment invariant as everywhere else in this file).
Deliberately **one store** — `configure-wall!` re-registers the resolved
wall fn back into `wall-registry` under the same name (preserving its
existing doc), rather than a second cache atom separating "the current
configuration" from "the original factory." The real tradeoff that buys:
after `configure-wall!` runs once, the name holds a concrete fn, not the
factory anymore — reconfiguring the SAME name again needs the factory
re-registered first, and a name used this way shouldn't also be reached
for inline args (`[name arg...]`) with a different parameter set at the
same time — register the factory under two distinct names if both are
wanted at once. A deliberately minimal design, not a full parameter-store
abstraction — this project has more than enough moving parts already.

### MIDI input: midi-through and record-midi

`input.midi` (`src/input/midi.clj`) is the mirror image of
`output.midi.midi-live` — real-time MIDI INPUT via `overtone/midi-clj`
(already a dependency; `output.midi.midi-live`'s own device discovery
was also switched onto it this session — see that ns's own docstring).
`open-midi` does two things at once, both starting the instant it's
called: forwards every NOTE_ON/NOTE_OFF straight to musics' own
connected output receiver on a fixed channel (`midi-through` — hear a
plugged-in keyboard live, through the same Fluidsynth setup
`(musics/connect)` already opened, no second MIDI-out connection of its
own), and puts the same events onto a `core.async` channel
(`input.midi-record` listens on this). `close-midi` stops both.

`input.midi-record`'s `open-record` blocks the calling thread from the
first NOTE_ON it reads until either a NOTE_ON below MIDI 24 (this DSL's
own C1, `(inc octave)*12` with `octave=1` — NOT General MIDI/Yamaha's
differing C1=36) or a `stop-record!` call, then quantizes what it
captured into musics text: a duration-weighted grid search
(`find-pulse`) picks a single best-fit tempo for the whole recording,
then each segment is rounded to the nearest of this DSL's own plain
note values at that tempo (`round-duration` — triplets are deliberately
out of scope, a recorded triplet just rounds to the nearest plain
value). Notes onset within 30ms of each other record as one chord; a
gap between them becomes an explicit rest. Pitch spelling uses a new
public `input.reader.leaf-parser/midi->spelling` (a black key always
spells as a sharp of the letter below, same convention `midi->ref`
already used internally, just also surfacing the accidental that fn
drops since it only ever fed a relative reference point).

The generated text's `!tempo:`/`!instrument:` header has to sit INSIDE
the `[ ]` Sequence, not before it — a bare top-level instruction isn't a
valid `TopElement` (see "ROOT read-only" above); confirmed live before
being fixed, an earlier version's leading `!tempo:120\n[ ... ]` failed
to parse at all.

`(musics/gui)`'s "Record MIDI" panel (`gui/lib/*`) wraps this: Start/
Stop buttons, an instrument field, an editable text area showing the
generated text, a name field, and Write (saves `<name>.mus` to disk
only — no separate stage/commit step). `scripts/setup-midi-in.sh` +
`doc/setup.md`'s "MIDI input" section cover the (much lighter than
output's) system setup — a real USB keyboard needs no kernel module,
unlike `snd-virmidi`.

### Algorithm registries: no longer reachable from musics text

Text-level algorithm invocation — `@[ name Arg... ]` (`AtomicAlgo`,
"algo over data") and `@{ name Primitive... Element... }` (`ElementAlgo`,
"algo over elements") — has been removed from the grammar entirely, not
dropped lightly: reconsidered directly once almost everything under
`algo/` turned out to already be unwired from any grammar entry point
(only `color-talea`/`split-leaf-voice`, below, were ever registered),
making the `AlgoArg`/`ElementAlgo`-args machinery (`walk-atomic-algo`/
`run-algo`/`walk-algo-arg`/`walk-element-algo`/`run-element-algo`/
`walk-data-values`/`walk-single-value`, all gone from
`flat-tree-walker` now) a lot of surface area justified by two working
examples. Parameterized playback algorithms now live entirely on the
`play`/`core.wall` side — `assign-algo!`'s `[registered-name arg...]`
form, and `core.wall/configure-wall!` for an install-once/configure-
later location — never in text; see "Wall: per-voice playback
algorithms" above.

`input/algo_registry.clj` — `atomic-algo-registry`/
`element-algo-registry` (plain `defonce` atoms, `name -> {:fn f :doc
doc}`), `register-algo!`/`unregister-algo!`/`algos`/
`register-element-algo!`/`unregister-element-algo!`/`element-algos`
(`musics.clj`, thin wrappers over each) — has since been removed
entirely, not just left unreachable from text: once `@[ ]`/`@{ }` were
gone from the grammar, this was a registry with no entry point left to
serve (its only two readers, `walk-atomic-algo`/`walk-element-algo`,
were already gone from `flat-tree-walker` per the paragraph above), so
keeping it around as a parked-fn convenience wasn't earning its keep
either. Call an algorithm directly as a Clojure function instead
(`(color-talea color talea)`), or register it as a *wall* algorithm
(`core.wall/register-wall!`) if you want it reachable as a per-voice
playback transform — see "Wall: per-voice playback algorithms" above.
The corresponding Forth words (`ALGOS`/`ALGOS?`/`REGISTER-ALGO!`/
`REGISTER-ALGO-DOC!`/`UNREGISTER-ALGO!`) are gone from `input/forth.clj`
too, for the same reason; `REGISTER-WALL!`/`WALLS`/`ASSIGN-ALGO!` and
the rest of the wall-side words are untouched.

`algo.common.isorhythm/color-talea` and `algo.common.split/
split-leaf-voice` are unaffected as Clojure functions — only their
former text-reachability via `@[ ]`/`@{ }` is gone. `color-talea`
combines a color (pitch sequence) and a talea (duration sequence) into
the classic isorhythmic pairing (event `i`'s pitch is `(nth color (mod
i (count color)))`, its duration `(nth talea (mod i (count talea)))` —
the two cycle independently); `split-leaf-voice` splits a melody into
`n` faster, octave-shifted voices, each built from the previous split
so every voice's total duration matches the original's.

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
- **Transient containers** (`:TIMES`/`:TUPLET`/`:TRANSPOSE`/`:DECORATED`,
  i.e. `\times`/`\tuplet`/`\transpose`/a grace decoration) are notationally
  invisible: `flat-core-builder/pop-container` splices their `:children`
  straight into the parent and never registers them under an id at all --
  no separate container survives in the tree. `times`/`tuplet`/
  `transpose` are Lisp prefix calls (`(times 2/3 [c8 d8 e8])`) spelling
  their body with `[ ]` -- the same `Sequence` grammar rule reused as-is
  (see the bracket table below); this replaced the earlier `\times 2/3 {
  c8 d8 e8 }` LilyPond-matching spelling once staying a close LilyPond
  superset stopped being a goal for this grammar (see "Grammar" below).
  Transience is a walk-time decision (splice, never register), not a
  grammar-level one -- a grace decoration has no dedicated bracket at
  all -- it takes two bare `Element`s directly (`(grace c8 d4)`), so
  there's nothing to distinguish there. They still get their own
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
  that path changed. `core.persist`'s freeze/thaw (needed since
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

### Multi-measure rests, pickups, and pitch languages

Three real LilyPond-superset gaps, closed together in one pass:

- **`R` (multi-measure rest)** — `MultiRest` in `musics.ebnf`, walked by
  `flat-tree-walker/walk-multi-rest`. With an explicit `Duration`
  (`R1*4`), this is exactly LilyPond's own spelling: the composer picks
  the note-value matching one bar in the current meter, `*n` multiplies
  it, same responsibility a bare `r`'s own duration already carries --
  LilyPond doesn't derive this from the meter automatically either,
  despite the name. With NO duration at all (`R`, or `R*4`), this DSL
  goes one step further and derives one bar's length from whatever
  `Meter` is actually active right there (`core.domain.context/
  ambient-value` against the full chain, current context included) --
  a genuine extension with no LilyPond equivalent, motivated by a real,
  confirmed transcription bug: `r1` written to mean "rest one bar" in
  3/8 time is actually ~2.67 bars (a bare whole note), not 1.
- **`\partial <duration>`** — a new `Instruction` alternative
  (`Partial` in `musics.ebnf`), LilyPond's own literal spelling, not a
  `!`-prefixed Assignment. Walked to a plain `:fixed` value under
  `:Partial`, sampled per leaf in the same batched `c/sample-many` pass
  `:Meter` already rides in (see `core.domain.resolve/
  common-keys+defaults`). Applied lazily, not by pre-seeding anything
  at voice-creation time: `core.async-engine/advance-bar!` consults a
  per-voice `:partial-pending?` flag (seeded fresh, `true`, in `play`/
  `fork-voice`/`warm-up!`'s own voice literals) and, the FIRST time
  only, adds `(bar-length - partial)` to that voice's own `:bar-pos`
  before its ordinary `+dur` -- so the first `:bar` crossing lands after
  just the pickup's own length, not a full bar. Fresh per forked voice,
  not inherited, same "no central authority" philosophy the rest of
  bar-tracking already has: a `\partial` inside one `:PAR` branch only
  ever affects that branch's own bar count.
- **`!language:` (pitch languages)** — `common.music-data/
  accidental-tables` is an extensible `{language-kw {suffix semitones}}`
  map, `:nederlands` (Dutch, LilyPond's own default and this DSL's own
  prior hardcoded behavior) alongside `:english` (`s`/`ss`/`x`/`f`/`ff`).
  `musics.ebnf`'s `Accidental` regex accepts the UNION of every
  supported language's own letter-suffix spellings unconditionally --
  the same "grammar recognizes the shape, walker decides the meaning"
  split `:accidentals:implied`/`:explicit` already uses, not a
  parser-level language switch (instaparse can't do that mid-file
  anyway, and doesn't need to: nothing here is genuinely ambiguous,
  since MEANING is resolved entirely at walk time by whichever
  `!language:` -- `flat-tree-walker/language-for-mode`, mirroring
  `key-for-mode` -- is actually active). This is exactly why English's
  own `s` (sharp) and Dutch's own `s` (elided flat after a/e) can safely
  share one grammar token even though they mean opposite things.
  `leaf-parser/accidental-semitones` takes the active language as a
  parameter now, threaded through the same `resolve-pitch`/`rel->midi`/
  `abs->midi`/`letter+octave->midi` chain `ks` (the active Key) already
  runs through, defaulting to `:nederlands` everywhere it isn't given
  explicitly, so no existing caller's behavior changed. Adding another
  letter-based language (deutsch, norsk, svenska -- ones that keep
  `c`/`d`/`e`/... as the letters themselves) is one more table entry
  plus its own suffixes in the `Accidental` regex, not a redesign; the
  solfège languages (italiano, español, français, português, català --
  which replace the letters with do/re/mi/... entirely) are a genuinely
  bigger, separate change (`PitchLetterAbs`/`PitchLetterRel` themselves
  would need widening), deliberately out of scope here.

Scheme (`#(...)`) stays unrecognized by the grammar entirely -- not a
new restriction, confirmed directly: no rule anywhere matches a leading
`#(`, so embedding one is a hard, clear parse failure, the same
behavior every other unsupported construct already gets, never a
silent misinterpretation.

### `\time`/`\tempo`/`\key` — removed

`Time`/`Tempo`/`Key` (LilyPond's own free-standing `\time`/`\tempo`/
`\key` command spellings, an additional surface spelling alongside
`!Meter:`/`!tempo:`/`!key:`) have been removed from the grammar
entirely -- they existed purely so `musics.ebnf` doubled as a closer
LilyPond superset, a goal this grammar no longer has now that
`input.lilypond-import` is a real, actively-maintained LilyPond ->
musics-text converter (see "Repo state" above). `!Meter:`/`!tempo:`/
`!key:` (`Assignment`/`KeyAssignment`) are unaffected and remain the
only spelling for any of these. `VarName`'s own reserved-word exclusion
list shrank accordingly -- `time`/`tempo`/`key` no longer collide with
anything (there's no `\time`/`\tempo`/`\key` left for a `\name` VarRef
to be mistaken for), leaving just the 17 ornament names genuinely
reserved.

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
| `[ ]`     | `Sequence`    | musical sequence — also reused as-is for `times`/`tuplet`/`transpose`/`repeat`'s body and a `VarDef`'s value (see below); the walker, not the grammar, decides whether a given `[ ]` is registered or spliced/stashed |
| `'[ ]`    | `Data`        | data container |
| `{ }`     | `Context`     | named context/envelope definition — a genuine Clojure map-literal echo, a Context being a bag of key/value settings |

`( )` means three things, disambiguated entirely by position (and, for
the Lisp-call case, which reserved word follows), never ambiguous with
each other: a slur mark glued directly onto a Note/Chord (`c4( d4
e4)`), LilyPond-style, at a note's own trailing suffix position; a Lisp
prefix call for `(par ...)` (`Parallel` — simultaneous parts, the ONE
registrable `Composite` among the Lisp calls, since it can carry an
`Id` exactly like `Sequence` can); and a Lisp prefix call for the
TRANSIENT structural commands (`(times 2/3 [c8 d8 e8])` and friends,
never individually addressable, always spliced into the parent). `par`
replaced an earlier `#{ }` bracket spelling for exactly the same reason
`\keyword`-prefixed commands were dropped below: this DSL no longer
needs to stay a close LilyPond superset (see "Repo state" above), so
`\keyword`-prefixed commands and `AtomicAlgo`/`ElementAlgo` (`@[ ]`/
`@{ }`, grammar-native algorithm invocation) were both dropped in favor
of syntax closer to the play mini-language itself — see
`src/input/musics.ebnf`'s own header comment for the full rationale and
the "Algorithm registries" note above for what replaced the latter.
`par`'s own motivation was narrower and more concrete than that
original pass, though, not just consistency for its own sake: a
literal Clojure `#{ }` can't hold the same value twice (a genuine
reader error, not just discouraged), which the mini-language's own
`#{}` inherited directly, and the text grammar's `#{ }` inherited as a
pure surface-syntax accident on top of that (nothing about `:children`
being a plain vector ever required it) — `(par :s1 :s1)` was always
meaningful, `#{ }` just structurally couldn't spell it. See "The
play-arg mini-language" below for `core.async-engine/par`, the
identical fix on the Clojure side, and `core.wall`'s own docs for why
this specifically matters for phase-music-style writing (the same
material against itself, offset).

**Every top-level program needs at least one real wrapping container.**
`TopElement` (`Program`'s own top-level element list) is `Composite |
repeat | VarDef` — a bare, un-nested `c4 d4 e4` with no `[ ]` around it
is not valid `Program` text on its own (`repeat` alone covers
unfold/volta/tremolo now, tremolo folded in as a third `repeat-type`
rather than a sibling rule). This is deliberately narrower than
`Element` (used everywhere *inside* a container, where `Leaf`/
`Instruction`/`Reference`/`VarRef`/transient `Command` are all still
completely ordinary): every one of those, if reachable bare at
`Program`'s own top level, can write directly into whatever context is
on top of the builder stack — before any real container has been
entered, that's `:ROOT` itself, which is meant to be a read-only
endpoint with a guaranteed value for every key (`common.defaults/
root-defaults`, `core.domain.context/context-root`). Three separate,
independently-confirmed-live write paths existed before this
restriction: a bare `Instruction` (`!vol<...!vol>`, no container of its
own); a bare *transient* `Command` (`times`/`tuplet`/`transpose`/
`grace` — not `repeat`, which persists as a real retained container and
was never affected — `pop-container` replays any instruction written
inside one onto whatever's on the stack once its wrapper splices away);
and a bare `Leaf`/`Chord` with a note-glued dynamic (`c4\f`, ordinary
surface syntax — `apply-note-dynamics!` writes through the same
mechanism a standalone `!f` does). A bare `Reference` (when it resolves
to a `{ }` `:CONTEXT` block) and a bare `VarRef` replay a stashed
envelope onto current-context the same way. `Part` is `Composite | Leaf
| Reference | VarRef` — since three of its four alternatives can each
reach `:ROOT` this way, and the third (note-glued dynamics) can't be
split out of `Leaf`'s own grammar rule without much deeper surgery,
`TopElement` keeps only `Composite` (a real container) reachable, plus
`repeat` (safe for the same reason `Composite` is: it gets its own
genuine, persistent context before anything nested is walked) and
`VarDef`. See `musics.ebnf`'s own comment on `TopElement` for the full
detail and exactly which live test confirmed each path.

`:ROOT` being grammar-guaranteed write-once is also what lets its own
context values skip the general `Envelope`/`Point`/atom machinery
entirely: `core.domain.context/ValueSource` (`sample-at`/`shift`,
`extend-protocol`'d over `Envelope` and a bare-value fallback) lets
`context-root` store each default as a plain value directly — no
allocation for something that, by construction, can never receive a
second point. Any other context still builds a real `Envelope` as
before (a `!tempo:90` inside `[verse: ...]` could still legitimately
grow into a ramp later); the protocol dispatch is what lets
`ctx-value-chain`/`ctx-shift` treat both shapes uniformly without
needing to know in advance which one a given key holds.

`repeat`'s own body and `alternative`/measured tremolo's body all
persist as a real, retained container (an `Iterator`'s own `:source`/
`:alternative` to replay on each iteration), so `[ ]` (`Sequence`) has
always been the correct, unambiguous bracket for them, same as
`times`/`tuplet`/`transpose`'s own (transient, spliced-not-registered)
body.

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT` whose envelope
points get replayed onto the current container's context at the current beat
offset — see `apply-context-ref` in `flat_tree_walker.clj`). `VarDef` is
`name = [ ... ]` (reuses `Sequence`'s own `[ ]` — see the bracket table
above); `VarRef` is `\name` — see "Comments
and variables" below.

`BarLine` (`|`, `||`, `|||`, `||||`) walks to a `Bar` record (`d/bar`,
zero duration) inline in `:children` — purely a structural marker on disk,
but no longer inert at playback: `async-engine`'s `play-node` fires a
`core.conductor` `:mark` signal for each one it hits (see "Conductor"
above), so `|`/`||`/etc. are exactly how a composer places an extra,
author-controlled cue on top of the automatic `:section`/`:bar` signals.
Reachable only through `Sep`/`EdgeBar` (`Sequence`/`Parallel`'s own
element-list separator and edge-of-body marker), both built on a shared
`BarRun` (`BarLine (ws? BarLine)*`) so a *run* of consecutive bar lines —
not just one — is legal wherever a single one is, e.g. `c4 | | d4` (two
adjacent single-pipe checks with nothing between them). This was a real,
confirmed gap, not a hypothetical one: `input.lilypond-import`'s own
`\repeat`/`\relative` body-flattening can legitimately produce exactly
this shape — a nested `\relative` block contributes no wrapping container
of its own (`relative-block-text`'s own text has no surrounding
brackets), so its own leading edge bar ends up sitting directly next to
whatever bar line the enclosing stream already had, with no `Element`
between them for the old `Element (Sep Element)*` structure to accept.
Each `BarLine` in a run still surfaces as its own separate sibling node
(`BarLine` itself isn't hidden) — `flat-tree-walker`'s own `:BarLine`
case needed no change at all, it already appends one zero-duration `Bar`
record per occurrence regardless of how many arrive in a row.
`lilypond-import`'s own `append-relative-block` (a sibling of
`push-barline`) additionally dedupes the specific case it CAN see across
(a `\relative` block's own leading bar against the immediately preceding
one in the same accumulator) so the common case doesn't emit visibly
redundant text at all — a `VarRef`'s own stored body is opaque to that
check, so that case relies on the grammar's own tolerance instead.

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
- `core/wall.clj` — the per-voice playback-algorithm registry (see "Wall:
  per-voice playback algorithms" above); a parked toolbox, no dependency
  on `core.async-engine` at all (that dependency runs the other way).
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
  OUTPUT backends (file-based `aplaymidi` playback vs. live Fluidsynth via
  VirMIDI). `midi_live.clj`'s `Receiver` (`open-receiver`/`note-on`/
  `note-off`/`program-change`/`control-change`) is what `core.async-engine`
  uses for real sound; `midi_file.clj` is a separate, unused-so-far offline
  batch renderer (build a `Sequence`, write/play a `.mid` file), not wired
  into the live engine. `midi_live.clj`'s own device discovery
  (`find-writable-device`) is backed by `overtone.midi` now, not a
  hand-rolled `MidiSystem` walk — see "MIDI input" above, which uses that
  same library directly for the opposite direction (`input/midi.clj`/
  `input/midi_record.clj`); everything else about `midi_live.clj`
  (auto-connect, byte clamping, its public API) is unchanged.
- `algo/` (renamed from `algorithm/`) — generative helpers, organized into
  topic subdirs: `indisp/` (Barlow indispensability); `metric/` (modular/
  binary/continued-fraction pulse generators); `rithmic/` (Euclidean/
  Fibonacci/prime/L-system/Markov generators in `rhythm.clj`, plus ten
  more files ported from `python-reference`'s `advanced_rhythm.py` --
  `phase-sieve`/`poly`/`necklace`/`stochastic`/`physical`/`transform`/
  `sonification`/`constraint`/`fractal-geometric`/`world`, covering
  Reich phase music, Xenakis sieve theory, polyrhythm/polymeter, rhythm
  necklaces and Vuza canons, genetic/Markov/RNN rhythm generation,
  physical-simulation and natural-process rhythms, EMI-style/Oblique
  Strategies transforms and groove/humanization, data/text sonification,
  constraint satisfaction, fractal and geometric rhythms, and Indian
  tala/West African timeline patterns); `melodic/` (scales, generative
  melody methods, and constraint-satisfaction walks in `melody.clj`,
  plus `counterpoint.clj` -- a multi-voice motif-imitation + species-
  counterpoint generator ported from the same source); `random/` --
  split into `random/core.clj` (`algo.random.core`, the pure xorshift32
  engine, mostly public, plus atom-backed seeding/state) and the parent
  `random.clj` (`algo.random`, the basic primitives and everything
  built on them: distributions, chance/weighted-pick helpers), sitting
  alongside `random/logistic.clj`/`random/lorenz.clj` (chaotic maps,
  no PRNG, untouched by that split) -- see `algo/random/core.clj`'s own
  header comment for why it's atom-backed rather than a dynamic var
  (wall-algorithm safety, the same core.async concern as everywhere
  else in this file); and `common/` (`reshape.clj`'s sequence-reshaping
  recipes, `isorhythm.clj`'s color-talea pairing, `split.clj`'s voice-
  splitting, plus `farey.clj`/`trig.clj`/`scaling.clj`, small math
  utilities also ported from the reference dirs). Mostly still
  standalone/unwired into the grammar or engine, same as before the
  reorg — `algo.indisp.indispensability` is the one exception:
  `common.music-elements/meter-indispensability` requires it directly
  (see "Meter and indispensability" above), so that one namespace is a
  real, live dependency now, not just a kept-for-reference algorithm.
  The `java-reference/`/`kotlin-reference/`/`julia-reference/`/
  `python-reference/` directories these ports came from have all since
  been removed — see the note where they used to be listed, just below.
- `java-reference/`, `julia-reference/`, `kotlin-reference/`, `python-reference/`
  — prior implementations of this same system in other languages, once kept
  on disk (`.gitignore`d, never part of the build) for cross-checking
  behavior. All four were fully surveyed against the current `algo/` tree
  and removed once nothing further was worth porting: `algo.common.farey`/
  `trig`/`scaling` and the whole of `algo/rithmic/` beyond `rhythm.clj`/
  `metric.clj` (`phase-sieve`, `poly`, `necklace`, `stochastic`, `physical`,
  `transform`, `sonification`, `constraint`, `fractal-geometric`, `world`)
  and `algo.melodic.counterpoint` all trace back to one of them -- see
  each file's own header comment for its specific source. Everything else
  in those trees was either already ported (the reference data tables,
  `algo.indisp.indispensability`, `algo.melodic.melody`, `algo.common.isorhythm`)
  or superseded architecture (the old domain model, parser, MIDI engine,
  and GUI `python-reference` itself was ported *from*, and the old
  Context/Leaf/Source/Decorator class hierarchy `java-reference`/
  `kotlin-reference` carried) -- not algorithms to translate.

## Known rough edges (found, not yet fixed)

One pre-existing quirk is still there — noted so it isn't silently
rediscovered as something new:

- **An `Id` inside a transient/scratch container's body is silently
  discarded**: `times`/`tuplet`/`transpose`/a grace decoration's body,
  and a `VarDef`'s value, all walk their `[ ]`'s (or, for a grace
  decoration, bare `Element`'s) children directly into a container that's
  never registered under its own id (transient ones get spliced into the
  parent and discarded; `VarDef`'s scratch container is popped by hand
  and never touches `:repo` at all). If that body happens to contain an
  `Id` (`(times 2/3 [myname: c4 d])`, or `motif = [myname: c4 d]`),
  `walk-bareword` still renames the container currently on the stack —
  it just renames a container that's about to vanish either way, so the
  name has no effect and produces no error. Same underlying mechanism,
  both places.
