# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Fluidsynth via a virtual ALSA MIDI port)
or rendered to a MIDI file. It's a REPL-driven project, not an app with a CLI —
the primary interface is `src/musics/core.clj`, evaluated interactively.

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
2. **Sound** — `core.events` (a Form's performance as a lazy stream
   of timed events) + `core.engine` (one sender thread reading those
   streams a lookahead ahead of the clock) + `core.domain.resolve`
   (context sampling, actualization) + `output.midi.midi_live`/
   `midi_file` (MIDI dispatch). Turns committed material into real-time or rendered
   sound.
3. **The playground** — `play`'s own mini-language (`core.engine`,
   thin `musics.core` wrappers) + `core.wall` (per-voice
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
be swapped out or evolved independently of each other. Four satellite
capabilities feed material *into* tier 1 rather than belonging to any
tier themselves: `input.midi`/`input.midi-record` (capture a live
performance, emit musics text), `input.lilypond-import` (convert real
LilyPond text), `input.abc-import` (convert real ABC notation text),
and `input.guido-import` (convert real GUIDO Music Notation text). The
GUI (`(musics.core/gui)`) wraps tier 3 for live use, plus one satellite
directly (its Record MIDI panel).

Tiers 1 and 3 share one *concept* — sequential-vs-parallel grouping —
but spell it differently on each side, and always have: tier 1
(`.mus` text) writes `[ ]` sequential / `{ }` parallel / `^{ }` a named
context/envelope definition; tier 3 (a `play` call) writes `[]`
sequential / `#{}` *or* `(par ...)` parallel, `#{}` still the shorter
everyday spelling there, `par` only for the one case `#{}` structurally
can't express (see `doc/decisions.md`'s Wave 7 entry, and its
2026-09-19 entry for why tier 1 moved a second time — `(par ...)`
briefly WAS tier 1's own Parallel spelling too, see Wave 7, before a
later GUIDO-flavored pass moved Parallel back onto bare `{ }` and
`{ }`'s own former job, Context, onto `^{ }` instead). These stay
genuinely different *languages* regardless of any past surface overlap:
tier 1 is text, parsed once by instaparse into permanent content; tier
3 is Clojure data, evaluated fresh at every call, describing a
performance choice rather than the music itself. That's why `:algo`
tagging (a wall-algorithm assignment) only ever exists on the tier-3
side, deliberately never reachable from `.mus` text — see "Wall:
per-voice playback algorithms" below.

## Documentation conventions

New or edited documentation (this file included) should describe the
*current* system, not narrate how it got there — no "this used to X,
now it's Y" framing for new writing. The reasoning behind a design
choice, and any rejected alternative worth remembering, goes in
`doc/decisions.md` instead, as a short, standalone entry — check there
before re-proposing something that looks like an obvious improvement.
This is a going-forward convention, not a retroactive one: the "Repo
state" section right below, and other historical asides elsewhere in
this file, are not being rewritten under this policy on their own —
they get brought in line with it if and when whatever they describe is
next touched anyway, not as a dedicated pass.

## Repo state — read this first

**The flat-model migration is complete, and a live signaling layer has
been built on top of it since.** In its current shape: a single, flat,
id-addressed `repo` map (`core.domain.flat_domain`, no parent pointers)
sits under `core.repo`, itself just as flat — `id -> node`, no history,
no tx-numbering, only ever the CURRENT value under each id; `core.conductor`
bridges structural boundaries (section enter/exit, bar crossings,
author-placed `|`/`||`/`|||`/`||||` marks) to named, schedulable
actions; every voice carries its own material/`:algo`/bar count/clock
in its own `core.events` stream, addressed by its own real path
(`:TAA`, `:TAB`, ...) — its material a snapshot of the repo taken when
it starts, so a later commit never glitches a voice already
mid-performance; `core.engine` performs every voice's stream from one
sender thread, a lookahead (100 ms) ahead;
`core.wall` gives a composer pluggable, hot-swappable per-voice
playback algorithms; and `musics.ebnf` itself has settled on a
GUIDO-flavored bracket/accidental scheme — `[ ]` sequential / `{ }`
parallel / `^{ }` a named context/envelope definition / `'[ ]` data,
and GUIDO-only accidental symbols (`#`/`##`/`&`/`&&`/`n`, no Dutch/
English letter suffixes or LilyPond's own `b`/`bb` anymore) — separate
from, and no longer unified with, the `play` mini-language's own
`[]`/`#{}`/`(par ...)` vocabulary on the Clojure-arg side (see "Shape
of the system" above).

See `doc/decisions.md` for the dated history of how each of these
arrived (search for "Wave" there for the seven stages the grammar/
play-mini-language convergence went through, its 2026-09-17 entries for
how `core.repo` itself went from tx-versioned history down to a flat
map, and its 2026-09-19 entry for the later GUIDO-flavored pass that
moved tier 1's own bracket scheme again, away from that convergence) —
this file describes the system as it stands today, not how it got
here.

If you find something that still assumes the old (pre-flat, pre-
`core.repo`, pre-GUIDO-flavored-`musics.ebnf`, or tx-versioned-
`core.repo`) model exists, that's stale — update or remove it rather
than working around it.

## Commands

Leiningen project (`project.clj`), Clojure 1.12. Dependencies:
`instaparse` (parsing), `org.clojure/core.async` (MIDI input),
`cljfx` (the GUI), `overtone/midi-clj` (MIDI I/O), and
`org.clojure/core.logic` (questions to the algo registry,
`algo.logic.tree`, and species counterpoint, `algo.logic.counterpoint`). A Factor-style
hosted REPL language (musics.lang) lives on the `concat` branch / the
`frepl-standby` tag, not here — see `doc/decisions.md`, 2026-09-28.

```bash
lein repl              # start a REPL (init-ns is `user`)
lein test               # run the full test suite (test/ dir)
lein lint               # clj-kondo over src/ and test/ (no cache written;
                        # the editors' on-save linting is off for this project)
lein verify             # lint, then the full test suite
lein test command-walk-test         # run a single test namespace
lein test :only command-walk-test/duration-ratio-scales-and-is-inherited   # single test var
scripts/docs.sh         # render README + doc/*.md + CLAUDE.md to styled
                         # HTML and PDF in doc/html/ (git-ignored; the .md
                         # stays the source) -- open doc/html/index.html
lein test :parsing      # just one architectural layer -- :parsing/:domain/
                         # :engine/:repl/:algo (test-selectors in
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
  │    "Grammar" below for `VarDef`/`VarRef`, and `doc/decisions.md`'s
  │    Wave 3 entry for why)
  ├─ flat-tree-walker/walk              → {:tree repo-map :auto-ids ... :var-map ...}
  │    (uses flat-core-builder for the push/pop container stack; id
  │    assignment is lazy -- ensure-id only spends an auto-id counter
  │    slot at pop time, and only if the source never gave an explicit
  │    name, so [verse: ...] never wastes a :s-prefixed slot it won't use)
  ├─ core.repo/changed-ids + commit-many!  → new/changed ids land in
  │    the flat store immediately, one atomic swap! (musics.core/parse
  │    commits right away -- no separate stage/commit step)
  └─ core.engine/play           → one core.events stream per top-level
       │                           voice, walked lazily from a snapshot of
       │                           the repo taken when it starts
       ├─ core.domain.resolve/resolve-event (per leaf, as the stream
       │    reaches it) → timed MidiEvent maps
       └─ core.engine's sender thread: reads each stream up to 100 ms
            ahead of now, queues note-on/off, conductor signals and
            voice start/end by time, performs each when due
            → core.conductor/signal! (per section/bar/mark boundary,
              :voice carried opaquely) → registered actions
```

`src/musics/core.clj` is the REPL entry point. `session` is now just
`{:auto-ids {...} :var-map {...}}` — `core.repo` is the actual store (see
below), not a `book`/`Score` atom. `(parse text)` walks against whatever's
*currently committed* and commits the result immediately — visible the
instant the call returns, no separate commit step. Parts are addressed
by keyword id thereafter (`(inspect :verse)`,
`(ctx-value :verse :tempo 0.0)`, etc.) — ids are first-class handles, resolved via
`resolve-id` (keyword/string/map all accepted). `(ctx :verse)` is a separate,
display-only helper — the part's own context chain (every ancestor's own
authored envelope points, nearest first, `:ROOT` excluded), not a value
lookup.

`(mu!)` drops into a nested `clojure.main/repl` loop where a bare (quoted)
musics string commits itself, via `music-eval` as its `:eval` hook — no
`(s! "...")` wrapper call needed, though the quotes themselves still are:
only bare strings are intercepted, so an *unquoted* `[verse: ...]` reads
as an ordinary (and invalid) Clojure vector before `music-eval` ever
sees it, same as it would at the outer REPL. Leaving needs its own hook
too, `music-read` (`mu!`'s `:read`):
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
inside `mu!` leaves only the inner loop, the outer session and anything
committed via `mu!` both survive it.

### Session, the repo, and playback

`core.repo` (`src/core/repo.clj`) is an id-addressed, flat node store:
every id lives under `registry` as `id -> node`, always just the CURRENT
value — no history retained, once a commit lands the old value under that
id is simply gone. Two ways to write:

- **`commit-node!`** — immediate single-node commit.
- **`commit-many!`** — several ids in one atomic `swap!`/`merge`. This is
  what `musics.core/parse` uses — a single `(parse "[a: ...] [b: ...]")`
  call can commit several ids together, visible the instant it returns.
- Reading: **`current`** (one id's value, or `nil`) and **`registry`** —
  the live `{id -> node}` atom itself, what a brand-new voice's
  snapshot is taken from. `registry` is a thin FUNCTION, not a bare
  var alias, specifically so it still re-resolves
  `core.registries/*repo-registry*`'s CURRENT dynamic binding at the
  moment it's called (a bare `(def registry reg/*repo-registry*)` would
  instead freeze onto whatever the ROOT binding was at namespace-load
  time, silently ignoring a test's own `binding`).

There's no history to pin against — `parse` and every `musics.core`
inspection fn (`find`/`ids`/`children`/`leaves`/`inspect`/`ctx`/
`ctx-value`/`locate`/`describe`/`print-structure`) always reads whatever's
committed right now, with no `tx` argument to accept. A voice is the one
thing that still needs isolation from LATER edits, though, and gets it a
different way:

- **A voice's snapshot** — the registry as it was the moment `(play
  ...)` started the voice, held in its own `core.events` stream state
  (`:repo`), inherited by every branch it forks. **Committing never
  moves it.** A brand-new voice always starts from whatever's current,
  automatically — but an already-running voice keeps its snapshot until
  `(schedule-tx! id phase)` (see below) moves it at a chosen boundary.
  This is deliberate: it's what lets you prepare an edit mid-performance
  without it glitching whatever's currently sounding — and, since the
  snapshot is per-voice, without one part's cutover glitching a
  *different*, still-playing part either (see `doc/decisions.md`'s Wave
  4 and 2026-09-17 entries). A container's children are looked up when
  the voice enters it, so a cutover reaches material the voice reads
  after the boundary — typically the next pass of a loop or the next
  item of a `[...]` play arg.

`write`/`load` persist/replace whatever's currently committed (via
`core.repo/seed!`), not the performance layered on top of it (a voice's
`:algo` assignment can transform pitch/duration wholesale). `reset` wipes
`core.repo` entirely and re-bootstraps a fresh `:ROOT`.
`persist-session`/`restore-session` (`core.persist`) are the fuller pair
for that — same repo+auto-ids round-trip as `write`/`load`,
plus whatever's CURRENTLY LIVE right now (`core.engine/live-algos`,
path -> Name read straight off each live voice's own immutable `:algo`
field — deliberately NOT `algo-assignments`/`:algo-prepared`, a separate,
narrower table that an ordinary `:algo`-tagged `play`/`play-add` call
never even writes to; see "Wall" below). Name is EDN-safe by
construction — always `nil` or a bare keyword, never a resolved wall fn
itself, a live closure that can never survive a round-trip. Restoring
replays each Name through `assign-algo!` — into the PREP table, not
onto any voice directly, since restoring never recreates a live voice
itself — so a later, untagged `play`/`play-change` call at that same
path picks it back up automatically. A Name not re-registered yet plays
unchanged (identity), same as `assign-algo!` always has.
Deliberately does NOT also cover what a name in `*algo-registry*` holds
(a tree or a hand-written fn is code, re-run by the user like any other
definition) or `core.conductor`'s schedule/repeating tables (pending cues in ONE
specific live performance, not composed material — closer to a paused
breakpoint than a saved document) — both left as documented, deliberate
gaps rather than silently declared solved. See `doc/decisions.md` for
why `persist-session`/`restore-session` exist as a separate pair from
`write`/`load` rather than `write`/`load` themselves changing shape.

### Conductor: signals and scheduled actions

`core.conductor` (`src/core/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `core.engine` depends
on it (a plain synchronous call, `conductor/signal!`, from the sender
thread, as each `:section`/`:bar`/`:mark` event of a stream comes due);
`core.conductor` depends on nothing else at all, not even `core.repo` —
a fully generic dispatcher, deliberately one-way (see
`doc/decisions.md`'s Wave 4 entry for why `schedule-tx!` itself lives
in the engine, not here, even though it builds on this file's tables). The
`event` map `signal!` hands to a triggered action is opaque to every
function in this file, `:voice` included — conductor never interprets
it, just passes it through.

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
- **`core.engine/schedule-tx!`** — the primary use case: `(schedule-tx!
  id phase)` moves every voice that crosses `[id phase]` onto whatever
  is committed at that moment, each at its own crossing and at most once
  per voice; voices that never cross it are untouched. It arms a
  non-consuming entry in the conductor's repeating table (cancel with
  `unschedule-repeating!`); the engine's `cutover` hook, which
  `core.events` calls at every boundary, checks that table and swaps
  the voice's snapshot. The decision is made as the boundary is
  computed — a lookahead before it sounds — so one armed later than
  that catches the voice's next crossing (see `doc/decisions.md`,
  2026-09-30).

### Wall: per-voice playback algorithms

See `doc/algorithms.md` for the practical guide; this section is the
mechanism's architecture reference.

`core.wall` (`src/core/wall.clj`) is one registry, `*algo-registry*`:
name -> `{:fn f :doc doc ...}`. A wall fn is seq-in/seq-out:
`(nodes ctx-chain voice) -> nodes'`, called identically regardless of
granularity — `core.events`' container branch calls it once, on the
WHOLE sibling list, before the children are walked or ornaments
expanded; its leaf/rest/drum branch calls it again with a singleton of
each node, so a fn must pass through what it already produced (mark
it, as `algo.tree.live` does). What fills the registry is
`algo.tree/live!` (a name bound to a tree + a tctx — see "Simple
composition: `algo.tree`" below); `build-algo!` stores a hand-written
wall fn directly. `core.wall/registered`/`musics.core`'s `registered`
surfaces the full entry.

**Voice paths, not slot numbers**: every voice's own registry key
(`core.engine`'s `:voices` atom) is a vector, root-first, one segment
per level of forking — the same path also addresses that voice's own
`:algo-prepared` entry, if any (see below). A voice's own `:algo` is a
plain, IMMUTABLE value in its stream state, set when the voice starts
or forks and never reassigned — `core.events/apply-wall` resolves it
via `core.wall/algo` FRESH at every node (never cached), so
hot-swapping works exactly one way: re-registering the
SAME `name`'s own entry in `*algo-registry*` (a change to its tctx,
`algo.tree/retree!`, or `build-algo!` again) — every voice whose own `:algo` already points
at `name` picks up the change on the next node the sender reads (a
lookahead ahead of hearing it), with nothing on the voice itself ever
touched.

`assign-algo!`/`algo-assignments` (`core.engine`, thin
`musics.core` wrappers of the same name) are a SEPARATE, narrower
mechanism: `:algo-prepared`, `path -> name`, consulted ONLY when a
top-level voice starts (`start-voice!`), and only when that call's
own `:algo` argument is `nil`. `assign-algo!` never reaches an
already-live voice — it only affects a mint that hasn't happened yet
(preparing a track before you start it, or `core.persist`'s own
`restore-session` replaying a saved snapshot — see "Session, the repo,
and playback" above). See `doc/decisions.md` for why a
voice's own `:algo` is immutable once minted rather than a live,
externally-reassignable table.

**Mean-pitch-ranked `:PAR` children**: every fork — a real repo `:PAR`
container's children (`core.events/walk-par`), or a `#{...}` play-arg
group handed to `play`/`play-add`/etc. (`walk-form-par`, and
`core.engine/mint!` for a
bare top-level `#{}` — see below) — labels its own children
`:TAA`/`:TAB`/`...` by ASCENDING MEAN PITCH, lowest pitch getting the
lowest id ("lowest voice lands in slot 0", the mixing-desk convention
this project has always used for `:PAR` ordering). `rank-segments`
(`core.compose`) backs `walk-par`/`walk-form-par`; `mint!` inlines the
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
conceptually mirrors `Sequence` in `musics.ebnf`, same
sequential-vs-parallel grouping, its own separate spelling — see "Shape
of the system" above), `#{Form+}` (parallel — the everyday spelling)
or `(par Form+)` (parallel too, for the one shape a set can't hold: the
same Form more than once, e.g. `(par :s1 :s1)` — see
`core.compose/par`), or `[Form :algo Name]` (exactly one Form,
optionally tagged with a algos-registered name or `nil`). The
collection type alone is the tag — vector always `:seq`, set (or
`par`) always `:par`, no guessing (see `doc/decisions.md`'s Wave 6
entry for why). This is Clojure data, a separate vocabulary from
`musics.ebnf`'s own text brackets (`[ ]` sequential, `{ }` parallel —
see "Grammar" below); the two once shared one spelling, and `#{ }`
was a text-grammar bracket for a while, but the text grammar has since
moved on while the play args kept `#{}` (see `doc/decisions.md`'s Wave
6/7 and 2026-09-19 entries).
`musics.core/sq`'s own `{:parallel? bool}` seq
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
top-level-only marker. A tag's algorithm always reaches the exact same
`:algo` field/`apply-wall` every voice already goes through, no
separate one-shot/direct-apply path — in one of two temporal patterns:
**permanent**, for a voice being freshly minted/forked right here
(`play`/`play-add`'s own top-level tag, and each `#{}` branch's own
tag, via `resolve-form-tag`) — set in the voice's stream state when it
starts, covering that voice's entire remaining life; or a **local
shadow** of the CURRENT voice's state (`(assoc st :algo name)`), for a
tag sitting inside an ongoing `[]` walk where the same voice continues
on to more material afterward (`core.events/walk-form`'s tagged branch,
which restores the outer `:algo` when the span ends)
— no shared state touched at all, restoration is automatic, ordinary
lexical scoping once the shadowed call returns, so a tag nested inside
an already-tagged outer span correctly falls back to the outer tag
afterward, not identity, with nothing explicit tracking "what was there
before." A `#{}` tagged as a whole
applies its algorithm to every branch as that branch's own DEFAULT — a
branch's own closer tag still wins (`resolve-form-tag`, shared by
`mint!` and `walk-form-par` alike, so a `#{}`'s own tag behaves
identically whether it's at `play`'s own top level or nested inside
other material).

**`play`/`play-add` mint one or more track ids from a SINGLE Form, plus
an OPTIONAL trailing `:algo Name`.** `(play Form)` or `(play Form :algo
Name)` — both `core.engine` fns (thin `musics.core` wrappers),
neither accepting several top-level forms implicitly sequenced anymore
(`(play :verse1 :verse2)` is now `(play [:verse1 :verse2])`, matching
the same one-Form discipline every nested level already has —
`split-call-args` parses the call's own `& args` against this same
`:algo`-at-a-fixed-position discipline `tagged-form?` uses one level
down). `mint!` recursively starts a real, addressable top-level
voice (`start-voice!`, with a free short track id —
`next-track-id`/`track-ids`, `:TAA`..`:TZZ`) for every part of
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
needed. Both still return straight-back-into-`voice-at`/
`play-change`/`play-add`-usable ids/paths.
`play` flushes EVERYTHING first, same as it always has — a solo call
deterministically lands on `:TAA`, since nothing else survives the
flush; `play-add` never flushes, same as it always has — joining what's
already there means a later call has to skip whatever's already
occupying an earlier id. A voice's own `:algo` is baked in once at mint
time: the call's own `:algo` tag if it supplied one, else whatever's
currently prepared for the freshly-minted path in `:algo-prepared`
(`assign-algo!` called ahead of time — see "Voice paths, not slot
numbers" above), else `nil`/identity. Args are validated
(`validate-args!`) BEFORE either one's own mutation (the flush, or
minting itself) — `play-top-level!` runs it before `drop-all!`/
`mint!` ever touch anything — a rejected/typo'd call still can
never disturb `:voices` or mint an orphaned voice, exactly the same
tested invariant this project already held for `play`'s own flush
before this change. `play-change` keeps its own older
explicit-path/variadic-args shape (via `start-voice!` directly)
rather than `play`/`play-add`'s newer single-Form-plus-`:algo` one — it
always targets exactly one already-known path, so none of
`mint!`'s "how many voices, and which ids, does this call need
to invent" logic applies to it — but it takes the same OPTIONAL
trailing `:algo Name` too (`split-change-args`, stripping it off the
tail of its own variadic args rather than `split-call-args`'s
exactly-one-Form discipline), so a chosen track can be started with an
algorithm in one call: `(play-change :myTrack form :algo :bright)`,
with no separate `assign-algo!` step needed. `display-timed`
(`core.compose`'s fully synchronous, `*engine*`-free preview of
what `play` would do — see "Composing vs. performing" below) mirrors the same `[]`/`#{}`/tag
dispatch (`realize-form`/`realize-form-par`/`realize-form-group`) but keeps its
own older variadic-args shape too, same reasoning as `play-change`; its
`realize-form-par` now explicitly mean-pitch-ranks its own children
before showing them; a real `[:PAR]` container never needed that (a
literal, ordered `[:par ...]` vector used to just get walked in written
order), but `#{}` has no reliable order of its own to fall back on. A
tag has no visible effect on `display-timed`'s own output — it's purely
structural/timing preview, with no `*engine*`/voice at all —
`realize-form`'s `tagged-form?` branch just unwraps and realizes the
inner Form.
A literal `#{}` still can't hold the same value twice (`#{:s1 :s1}` is a
reader error, not just unusual, and neither does two identically-tagged
branches save it — `#{[:s1 :algo :a] [:s1 :algo :a]}` collides too, since
the two tag vectors are `=`) — but "the same part against itself in
parallel" (Reich-style phase music, a canon voice imitating itself, two
untransformed copies) no longer needs a workaround for that: `(par
:s1 :s1)`, or `(par [:s1 :algo :a] [:s1 :algo :a])` for two copies
running the identical algorithm, both illegal as a literal `#{...}` and
both fine via `par` — see `core.compose/par`. `par`'s own Form is
an ordinary vector (never restricted on duplicate values) tagged
`:parallel?` in its own metadata, the exact mechanism `sq` already uses
to mark an extracted `:PAR` container's own children — not a new
mechanism, just exposed as a constructor rather than only ever reached
by extracting an existing container. `par-form?` (`core.compose`'s
one place deciding "is this Form a parallel group") and
`form-tag+items` both recognize either shape identically; `#{}` itself
is unchanged and still the natural, terser spelling whenever branches
are naturally already distinct.

**Names are registered ahead of time.** `Name` in a tag is always a
bare, already-registered name or `nil` — checked eagerly, at the `play`
call itself (`validate-algo-name!`). `assign-algo!`'s own `name`
argument is looser: it's stored as-is in `:algo-prepared`, unresolved,
until whatever it eventually mints reads it; an unregistered name there
plays unchanged (identity).

### Simple composition: `algo.tree`

`algo.tree` (`src/algo/tree.clj`) is the one way to combine this
project's algorithms and to play them (see `doc/decisions.md`,
2026-09-27/28). Two separate things: a **tree** (an immutable value —
what is computed) and a **tctx** (an atom of settings for it, also the
GUI's model):

```clojure
(require '[algo.tree :as t] '[algo.tree.lib :refer :all])
(def riff (notes (gate euclid (cycled scale))))     ; prints #node (notes (gate (euclid) ...))
(def tctx (t/tctx riff))                          ; {:params {:k 3 ...} :specs {:k {...} ...}}
(t/setp! tctx :k 5)                           ; = (swap! tctx assoc-in [:params :k] 5), validated
(t/run riff tctx)                                  ; or (t/run riff {:k 5}), a plain map
```

**Algorithms describe themselves.** An algorithm is an ordinary `defn`
whose attr-map carries `:algo {:short :in :out :params}`; nothing else
about it changes. `algo.tree.registry/register!` introspects the var:
`:arglists` gives param names and order (keyword args' `:or` defaults
too), `:doc` the description; the first `(count :in)` args are children
(or the args `:children` names), the rest params; a multi-arity fn names
its `:arity`. A fn yielding ONE value per call becomes a sequence source
through metadata alone: `:repeat :len` calls it `:len` times (the
samplers: `normal`, `triangular`, `int-range`, ...), `:pull {:via :value
:args [..]}` calls it once for a generator and pulls `:len` values (the
closures: `walk`, `glide`, `cyclic`, `chain`, `logistic`, `henon`,
`lorenz`); the count is a param like any other. Every param spec must
carry `:type` (`:int :double :ratio :string :keyword :vector :map :fn
:bool :any`) and `:default` (`##NaN` = required — a fitness fn, say), and
a number `:min`/`:max` (`##-Inf`/`##Inf` for an open end); `:choices`
limits a string/keyword — `register!` throws on an incomplete spec. Rule
maps, constraint vectors and fitness fns are params too, so they live in
the tctx like any number. The registry maps short ↔ full names
(`t/algo`, `t/full-name`, `t/short-name`, `t/algos`). `t/defalgo` is
`defn` + register: the raw fn becomes `name*`, `name` the node
constructor. `algo.tree.lib` `expose-ns`es every annotated fn in
`algo/{indisp,metric,melodic,random,rhythmic}` and `color-talea` — about
120 algos, each under a short name (`euclid`, `cantor`, `tala`,
`markov-gen`, `counterpoint`, `normal`, `walk`, `lorenz`, ...) — and
`defalgo`s the helpers (`scale`, `cycled`, `shuffled`, `head`, `gate`,
`transpose`, `stretch`, `pick`, `notes`, `pair-notes`) and the type
bridges (`degrees`: numbers onto a scale, `threshold`: numbers → grid,
`gaps`: onsets → durations, `layer`, `axis`, `noise`).
`test/algo_catalog_test.clj` holds the line: every public fn in those
trees is an algo or listed with the reason it isn't (data tables, the
RNG engine, single draws, constraint builders), every algo runs with
its own defaults and gives its declared `:out`, and no lib name shadows
`clojure.core` or `musics.core` — `lein repl`'s `user` ns has
`algo.tree` as `t` and every lib constructor referred.

**Trees are checked when built.** A child may be a node, a bare
constructor (`scale` = `(scale)`), a keyword (a param read at run time)
or a literal. Child count and `:in`/`:out` types are checked at once
(`:grid`/`:weights`/`:pitches`/`:numbers`/`:durations`/`:onsets`/
`:pairs`/`:points`/`:layers`/`:model`/`:strokes`/`:notes`/`:index`,
`:any`, and `:same` = the first child's type), errors naming both nodes.
`(euclid :as :bass)` names an instance.

**Keys are a pure function of the tree** (`t/param-keys`): a param keeps
its bare name unless two different algos read it with different specs
(then `:<short>.<name>`); a named instance's are `:<as>/<name>`.
**The tctx** holds `{:params :specs}`, never the tree: one tree can run
against several tctxs, and `(t/fit! tctx tree)` prepares a tctx for
another tree, keeping its values. Its validator checks every value
against its spec on every change, so even a plain `swap!` can't store a
bad one; `t/run` checks a plain map's values the same way.
`t/describe` prints key/value/range/default/algo/doc; `t/trace`
every node's result (lazy seqs previewed, never walked); an algo that
throws is reported with its node's expression.

**Live** (`algo.tree.live`, reached as `t/live!`/`t/retree!`/`t/stop!`/
`t/play!`): `(t/live! :riff tree tctx)` binds a name to a tree + a tctx in
`core.wall`'s registry (so `(play :verse :algo :riff)` works too) and
starts an endless voice; the name watches its tctx, so every change
re-registers it and is heard on the next note. `retree!` swaps the tree,
`fit!`-ing the same tctx. A tree that doesn't read `:nodes` GENERATES:
each note a voice plays becomes the next element of its data, every
voice keeping its own cursor (re-running the tree once per change, at
the same position; a failing run keeps the last good material). A tree
that reads `:nodes` TRANSFORMS the voice's own notes. The GUI's Wall
window shows each live name with a control per param: a slider for a
finite range, a dropdown for `:choices`, a note for a fn (set at the
REPL), a text field otherwise. `(gui tctx)`/`(gui tree)`/`(gui tree
tctx)` (`musics.core/gui`, also `t/gui`; `gui.lib.params`) opens just a
settings window with the same controls, no rest of the GUI: the tctx is
its model both ways (a control calls `t/setp!`, a REPL `setp!` moves the
control), and given a tree it also previews the result (debounced,
first 32 items of an endless one) with Play once / Live as buttons.
`(gui tree)` makes the tctx and returns it. `(build-tree)` (`musics.core`,
also `t/build-tree`; `gui.lib.composer`) composes a tree by drag and
drop and returns `[tree tctx]` (blocking until Finalize, nil when closed):
a canvas showing the tree with its brackets and numbered holes, and a
pane of categories -> algos on the right (each registry entry's
`:category` — its `:algo` metadata's, else the namespace segment after
`algo.`); what doesn't fit the active slot is dimmed and refused as a
drop target — decided over the whole draft by `algo.logic.tree`, which
types the draft in core.logic, so a hole under a `:same` node (`cycled`,
`head`, ...) takes the type that node's own slot wants, and an algo
whose own holes nothing could fill is dimmed too — a drop on a hole fills it, on a node replaces it, and the
active slot moves on to the next hole, so a tree grows root to leaves.
`(build-tree :repl)` is its REPL twin, step for step the same. Both run
on `algo.tree.builder`, a pure draft model (`place`/`place-literal`/
`remove`/`undo`/`redo`/`select`, `fits?`, `->tree`, `from-tree`,
`render`; Ctrl+Z/Ctrl+Y in the window, `u`/`y` at the REPL), so
the two can't drift apart; `(build-tree tree tctx)` edits an existing
tree. `algo.tree.lib/notes->mus` renders generated notes as musics
text, ready for `parse`. `doc/algo-cookbook.pdf` (source `.html` beside
it, generated and verified by `scripts/algo-cookbook.clj`, which runs
every recipe) is the worked guide — 47 recipes plus reference tables
read from the registry; `src/examples/tree_tour.clj` walks through all of it;
`.clj-kondo/hooks/defalgo.clj` teaches clj-kondo what `defalgo` defines
(`hooks/core_logic.clj` does the same for core.logic's `run`).
`algo.logic.tree` (`lt` at the REPL) also answers questions from the
registry's types: `find-algos` (by category, input, output, param),
`how` (smallest trees from one type to another, `:input` for what you
have), `feeds`, `why-not`, `examples` and `surprise` (a random tree
that type-checks) — see `doc/algorithms.md`, "Asking the registry".

### Species counterpoint: `algo.logic.counterpoint`

`(cp/counterpoint {:cantus [...] :voices 2-4 :kind 1-5})` writes
counterpoint after Jeppesen's *Kontrapunkt* against a given cantus
prius factus (whole notes): with 3–4 voices the top added part in the
kind, the others in 1st species (`:kinds` per voice), in a church mode
(or major/minor) taken from the cantus' final. Notes carry their
diatonic step (`counterpoint/intervals.clj`), so intervals keep their
quality; the hard rules (`counterpoint/rules.clj`, `violations`) prune
a depth-first core.logic search bar by bar, dissonances judged as
figures (passing, lower neighbour, cambiata, suspension); soft rules
pick the best of several short searches. `cp/check` reports the rules
a score breaks, `cp/check-cantus` warns about a cantus, `cp/->mus`
gives musics text spelled in the mode, and `:species` is the tree algo.
`doc/counterpoint.md` has the rules, the design and its limits.

### Composing vs. performing: `core.compose`

`core.compose` (`src/core/compose.clj`) holds the play-arg mini-
language's own Form-shape grammar — `tagged-form?`/`split-tag`/
`resolve-form-tag`/`par-form?`/`par`/`form-tag+items`/
`peel-group-contexts`, plus `live-repo`/`build-chain`/
`mean-pitch-rank`/`form-pitch-source`/`rank-segments`/
`top-level-voices` — and the mini-language's two previews. `display`
shows which voice `play` would give which material, as musics text: one
line per top-level voice, labelled as `play` names it, each `{ }`/`#{}`
branch with its own voice label, an `:algo` where it applies, notes
spelled absolutely (`input.reader.leaf-parser/part->mus`) — no time,
nothing transformed (`show-form`/`show-node`). `display-timed` resolves
every note with its onset, nesting a `:PAR` as `{:kind :par :voices
[...]}` (`realize-form`/`realize-node`/`realize-iterator`/etc., all
private) — a second, independent walk of the timing rules that
`core.events` is tested against. Deliberately engine-free:
nothing here touches `*engine*`, a voice, `core.async`, or MIDI.

This is a **shared toolkit**, not a pipeline stage — `core.events`'
walk and this ns's own `display`/`display-timed` each walk a Form on
their own, calling INTO these functions at every node/group they
visit; neither hands the other a pre-computed result.

`core.events` and `core.engine` require `core.compose`; `core.compose`
requires only `core.domain.*` (and `input.reader.leaf-parser`, to spell
notes) — never the engine — so the dependency runs exactly one way.
A purely-functional grammar+preview layer has no business in the
real-time playback engine. See `doc/decisions.md` for the full
reasoning behind the split (including why this ISN'T a third tier
alongside Material/Sound/The playground — it's a sub-piece of tier 3,
the part of the play-arg mini-language that's execution-agnostic, not
a new architectural layer).

### Performance as data: `core.events`

`core.events/events` (`musics.core/events`, repo implied) is what
`play` would perform, as a lazy, time-ordered seq of maps: `(events
form)` or `(events form :algo name)`, the same Form `play` takes. Each
carries `:t` (seconds), `:beat` (structural time, exact) and `:path`
(the voice, named as `play` names it), and a `:kind`: `:note`/`:drum`/
`:rest` (a `resolve-event` MidiEvent; a note's `:channel` is left to
the consumer), `:section` (`:id :type :phase`), `:bar` (`:n`, at the
end of the note that crossed), `:mark` (`:count :n`) — the boundaries
the engine signals to `core.conductor`, here as data.

The walk threads a voice's position as a plain state map (`:repo` its
snapshot, `:path`, `:algo`, `:t`, `:beat`, bar/mark counts) with a
continuation per step, so nothing is walked past what is read: `:count
:infinite` and live generators are fine with `take`/`take-while`. A
`:PAR`/`#{}` merges its branches by `:t` and then continues where the
branch that ends last stopped (`display-timed` follows the same rule).
Wall fns run at the container's children and then at each leaf, so
reading events moves a live `algo.tree` voice's cursor. `:voice` events
(`:phase :start`/`:end`, `:algo`) mark each voice, forked ones included,
beginning and finishing. `voice-events` is one top-level voice's
stream, optionally with a `:cutover` hook `(st id phase) -> st'` called
at every boundary, AFTER that boundary's own event — so a reader that
reads a boundary only when it is due to act on it decides when the hook
runs (`core.engine/schedule-tx!`). No clock, `core.async`, MIDI or
`*engine*`.

`musics.core/render` writes a form to a `.mid` file through it
(`:until` seconds for endless material, `:seed` for `:humanization`).

### Live playback: `core.engine`

`core.engine` plays `core.events` streams in real time. `play`/
`play-add`/`play-change` start one stream per top-level voice (from a
snapshot of the repo); one sender thread per engine (`sender-loop`,
started on demand, stopped when nothing is left) repeatedly takes
`:lock`, reads every stream up to `:lookahead-ns` (100 ms by default,
`(engine fs repo root-id lookahead-ms)`) ahead of now, turns what it
reads into timed actions in one priority queue — note-on (at `:t` plus
`:micro`, with `:humanization`'s spread of onset and velocity), note-off (`:dur-played` later,
none for a tied note), `:section`/`:bar`/`:mark` signals, voice
start/end — and performs each as it comes due, parking until the next
one. At one moment a note-off goes before signals, and signals before a
note-on, so a repeated pitch is released before it sounds again.

- Channels: a pool keyed by `[program cc]`, claimed when a voice's note
  first needs one and released when the voice ends; 15 timbres at once,
  a 16th drops its notes rather than take a channel (`channel-for!`).
- `stop!` drops everything (note-off for sounding notes, an `:exit`
  signal for every section still open); `play` does the same first;
  `play-change path` drops just that voice. `pause!` holds the queue
  (sounding notes keep sounding); `resume!` moves every pending action
  and stream origin on by the pause.
- `voice-at` returns `{:path :root-path :algo :t :beat}` (`:t`/`:beat`
  as of its latest note); a top-level voice is registered as soon as
  `play` returns, forked ones when their `:voice :start` comes due.
  `playing-ids` is the ids some voice is inside (from `:section`
  signals); `live-algos` is path -> `:algo`.
- A stream that throws (a wall fn, say) ends that voice with a printed
  message; a conductor action that throws is reported; neither stops
  the others.
- A change is heard once the sender reads that far: a live tree's new
  settings within the lookahead; a note's `:micro` may move it early by
  at most the lookahead.
- `warm-up!` plays 16 near-silent notes after connecting, so the first
  real notes don't pay for JIT warm-up.

See `doc/decisions.md`, 2026-09-29/30, for why a forward-only stream
and one sender rather than a voice per go-block or a separate scheduler
process.

### MIDI input: midi-through and record-midi

`input.midi` (`src/input/midi.clj`) is the mirror image of
`output.midi.midi-live` — real-time MIDI INPUT via `overtone/midi-clj`
(already a dependency; `output.midi.midi-live`'s own device discovery
is also built on it — see that ns's own docstring). `open-midi` does
two things at once, both starting the instant it's
called: forwards every NOTE_ON/NOTE_OFF straight to musics' own
connected output receiver on a fixed channel (`midi-through` — hear a
plugged-in keyboard live, through the same Fluidsynth setup
`(musics.core/connect)` already opened, no second MIDI-out connection of its
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
valid `TopElement` (see "Grammar" below, "Every top-level program needs
at least one real wrapping container").

`(musics.core/gui)`'s "Record MIDI" panel (`gui/lib/*`) wraps this: Start/
Stop buttons, an instrument field, an editable text area showing the
generated text, a name field, and Write (saves `<name>.mus` to disk
only — no separate stage/commit step). `scripts/setup-midi-in.sh` +
`doc/setup.md`'s "MIDI input" section cover the (much lighter than
output's) system setup — a real USB keyboard needs no kernel module,
unlike `snd-virmidi`.

### Algorithm registries: not reachable from musics text

Text-level algorithm invocation isn't part of the grammar. Playback
algorithms live entirely on the `play`/`core.wall` side — a tree
bound ahead of time under its own name (`algo.tree/live!`)
— never in text; see "Wall: per-voice playback algorithms" above. See
`doc/decisions.md` for why (`@[ ]`/`@{ }` grammar-native invocation and
`input/algo_registry.clj` both existed once and were deliberately
removed, not just left unreachable).

`algo.common.isorhythm/color-talea` and `algo.common.split/
split-leaf-voice` are two ordinary Clojure functions worth knowing by
name here: `color-talea` combines a color (pitch sequence) and a talea
(duration sequence) into the classic isorhythmic pairing (event `i`'s
pitch is `(nth color (mod i (count color)))`, its duration `(nth talea
(mod i (count talea)))` — the two cycle independently); `split-leaf-voice`
splits a melody into `n` faster, octave-shifted voices, each built from
the previous split so every voice's total duration matches the
original's. Call either directly, or give it `:algo` metadata (or
`defalgo` it) to compose and play it in an `algo.tree` tree.

### Domain model — flat repo, not a tree of pointers

- **Containers are plain maps**: `{:type :SEQ :id :s1 :context ctx :children [...]}`.
  No atoms inside nodes. `:children` holds a mix of inline leaf values and
  keyword ids that must be resolved against the `repo` map
  (`d/children repo container`). Auto-generated ids are short,
  type-prefixed (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`), assigned by
  `flat-core-builder/next-auto-id`.
- **Leaves are plain, `:type`-tagged maps**: `Leaf`, `Rest`, `Drum`
  (pitches/duration/articulation/dynamic/modifiers/tied), plus `Pulse`
  (`:duration`/`:value` only — no pitch at all; a duration/value cell for
  pulse-grid-shaped generative material, e.g. `algo.common.pulse/
  grid->pulses`, and reachable directly from text too: `p<Duration>`
  builds one, `PitchLetterRel`'s own long-reserved-but-unwired `p` slot
  in `musics.ebnf` — `value` is always a fixed `1` for now, deliberately;
  see `doc/decisions.md` for why). `Iterator` (a real record, deferred
  expansion for `\repeat`/tremolo, holding a `:source` container +
  `:params`) is the one exception to "plain map."
- **Transient containers** (`:TRANSPOSE`/`:REVERSE`/`:DECORATED`, i.e.
  `\transpose`/`\reverse`/a grace decoration) are notationally
  invisible: `flat-core-builder/pop-container` splices their `:children`
  straight into the parent and never registers them under an id at all
  -- no separate container survives in the tree. `\transpose`/`\reverse`
  are backslash-prefixed commands, LilyPond-style (`\transpose c d (
  c8 d8 e8 )`, `\reverse ( c8 d8 e8 )`), taking a `Scope` (`( )`) body
  -- not `[ ]` `Sequence`, and not a bare Lisp-call spelling either; see
  the bracket table below. There is no `\times`/`\tuplet` command at
  all anymore: a note's own Duration can carry an optional `*Ratio`
  suffix instead (`c4*1/3`, a triplet eighth spelled as a quarter run
  at 1/3 speed), inherited by later notes that omit their own Duration
  until an explicit new one appears -- see "Grammar" below's Duration
  section, and `doc/decisions.md`'s 2026-09-19 entry for why. `reverse`
  is pure reordering, no per-child value transform at all -- see "Known
  rough edges" below for the one behavior it shares with `transpose`:
  neither recurses into a nested container reference sitting in its own
  body.
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
  Motivation: `sq` (`musics.core`) returns a container's bare `:children`
  — none of that container's own `:context` (its `!instrument:`/
  `!tempo:`/`!mf`/etc.) travels with it once extracted, so a leaf played
  standalone (`(play (times 12 (sq :verse)))`) would otherwise resolve
  against whatever minimal `ctx-chain` the *new* top-level play call
  built (often just `[ROOT-ctx]`), silently losing `:verse`'s own values
  entirely. `core.domain.resolve/chain-links` re-bases each baked
  ancestor by `(structural-time - relative-offset)` at resolve time,
  which reconstructs exactly the entry point that ancestor's own
  container would have had — numerically identical to `build-chain`'s
  own per-container shifting for ordinary playback, and correct for a
  standalone/extracted leaf too. See `doc/decisions.md` for why the
  relative-offset subtraction specifically is load-bearing, not a
  simplification skipped for convenience.
  Only the baked ancestors the current walk did NOT pass through go in
  front of the walk's own chain (`chain-links`, matched by
  `:envelopes-atom`, never the baked `:ROOT`), so a referencing
  container's settings (`[fast: !tempo:240 :mot]`) still reach what the
  referenced container doesn't set itself, and a reused container
  leaves its original parent's settings behind. `core.persist` keeps
  that atom sharing across `write`/`load` (`:ref` per context).
  A leaf built directly (not through the real walker — ornaments'
  expanded sub-leaves, `algo`-registry-generated leaves, `warm-up!`,
  most unit tests) simply has no baked `:ctx-chain`, and
  `chain-links` falls back to whatever `ctx-chain` was threaded in
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
- **`core.engine`** is the (sole) real-time playback engine: one sender
  thread performing `core.events` streams (see "Live playback" above).
  `*engine*` is a dynamic var so REPL calls (`play`, `stop!`, `pause!`,
  `resume!`) don't need to thread an engine value around. `play`'s args
  are the play-arg mini-language (see "The play-arg mini-language"
  under "Wall" above). A group's parallel-ness doesn't have to be a set
  literal: `musics.core`'s `sq` (the one function that turns a
  container's children into a bare seq) tags its output `{:parallel?
  bool :id id}` via metadata, and `form-tag+items` checks that first,
  so `(play (sq :chorale))` on a `:PAR` container plays in parallel.
  That metadata doesn't survive most seq transforms (`map`/`times`/
  `transpose`/...), so `(times 2 (sq :chorale))` plays sequentially —
  correct, since a transformed result no longer claims to *be* the
  container. `validate-ids!` rejects, before any voice starts, what
  play can't play: an id that isn't there, an unregistered `:algo`,
  `nil` (e.g. `sq` of an id that isn't a container) and a bare fn (did
  you mean `play-xf`?); `display-timed`'s `realize-form` rejects `nil`
  the same way. Anything else unrecognized — notably the inline
  `:assignment` nodes in `sq`'s output (a written `!tempo:`/`!mf`,
  whose effect already landed on its siblings' context at parse time)
  — plays as nothing, as it always has inside a container walk. Real
  MIDI output goes through `output.midi.midi-live`'s `Receiver`, passed
  in as the engine's `fs` (`nil` plays silently, for tests).

### Multi-measure rests, pickups, and LilyPond pitch languages

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
  at voice-creation time: `core.events/advance-bar` consults a
  per-voice `:partial-pending?` flag (fresh, `true`, in every voice's
  and every forked branch's starting state) and, the FIRST time only, adds `(bar-length - partial)` to that voice's own `:bar-pos`
  before its ordinary `+dur` -- so the first `:bar` crossing lands after
  just the pickup's own length, not a full bar. Fresh per forked voice,
  not inherited, same "no central authority" philosophy the rest of
  bar-tracking already has: a `\partial` inside one `:PAR` branch only
  ever affects that branch's own bar count.
- **LilyPond pitch languages** — musics text has only GUIDO's
  accidental symbols (`#`/`##`/`&`/`&&`/`n`); there is no `!language:`
  key. `common.music-data/accidental-tables` (`{language {suffix
  semitones}}`, `:nederlands` and `:english`) exists for reading real
  LilyPond source: `input.lilypond-import` detects a file's `\language`
  and passes it to `leaf-parser/accidental-semitones`, which handles the
  symbols first and looks only a letter suffix (`is`/`es`, `s`/`f`) up
  in the table. Another letter-based language is one more table entry;
  solfège languages (do/re/mi) would need the importer's note-name
  parsing widened too.

Scheme (`#(...)`) stays unrecognized by the grammar entirely -- not a
new restriction, confirmed directly: no rule anywhere matches a leading
`#(`, so embedding one is a hard, clear parse failure, the same
behavior every other unsupported construct already gets, never a
silent misinterpretation.

### `\time`/`\tempo`/`\key`

Not part of the grammar. `!Meter:`/`!tempo:`/`!key:` (`Assignment`/
`KeyAssignment`) are the only spelling for any of these — see
`doc/decisions.md`'s Wave 6 entry for why LilyPond's own free-standing
`\time`/`\tempo`/`\key` spelling was dropped rather than kept as an
alternative. `VarName`'s own reserved-word exclusion list is just the
17 ornament names — `time`/`tempo`/`key` don't collide with anything a
`\name` VarRef could be mistaken for.

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

`algo.indisp.indispensability` also carries an "adherence" layer on top
of the raw ranks — how strongly a pulse's own indispensability
predicts its probability of sounding, tunable across `-1.0..+1.0`, not
just the ranks themselves. `normalize-weights` divides any weight
vector by its own max, landing it in `[0,1]` regardless of how many
pulses there are or how large the raw values are — the shared first
step every adherence fn takes, so the same `adherence` value means the
same thing whether the meter has 4 pulses or 12.
`normalized-indispensability` is `indispensability` +
`normalize-weights` in one call, a plain `[0,1]`-float vector meant as
a chainable starting point for ordinary seq transforms (`reverse`,
`algo.random/shuffle`, `algo.common.rotate/rotate`) before finally
handing the result to `algo.common.pulse/grid->pulses` or
`density-grid` — no dedicated pipeline mechanism needed, since a
normalized weight vector is just a plain vector. Two distinct
reshaping mechanisms consume `adherence`, genuinely different in kind,
not just curve shape: `tilt-probabilities` is a softmax (temperature-
scaled by adherence, with a tiny irrational position-based tie-break
term so it never collapses to an exact uniform tie at adherence=0) —
`exp(anything)` is always positive, so it never assigns a pulse
literal zero probability, however extreme adherence gets.
`power-law-probabilities` instead raises the normalized weight (or, for
negative adherence, its complement `1 - weight`) to an adherence-driven
exponent — always order-preserving (or order-reversing), never
re-ranks by blending, and CAN assign a pulse exactly zero probability
(whichever position's own base is exactly `0`). `density-grid` is a
separate, orthogonal control — deterministic top-K thinning of a
meter down to its N% most indispensable pulses (a binary `0/1` grid,
same shape every other `algo/rhythmic/` generator produces) — governing
how MANY pulses sound, not how strongly rank predicts which ones do.

### Grammar (`src/input/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

Current bracket scheme — GUIDO-inspired brackets/accidentals,
LilyPond-inspired leaf/command spelling otherwise (checked directly
against `src/input/musics.ebnf`'s own header comment, always the
source of truth when this section and that comment could drift apart
again):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `[ ]`     | `Sequence`    | musical sequence — also reused as-is for `repeat`/`alternative`'s own body and a `VarDef`'s value (see below); the walker, not the grammar, decides whether a given `[ ]` becomes a real, addressable container or is spliced/stashed, never how it's spelled |
| `{ }`     | `Parallel`    | simultaneous parts (GUIDO's own bracket for this) — can carry an `Id` exactly like `Sequence` can, so a parallel group is individually addressable the same way |
| `^{ }`    | `Context`     | named context/envelope definition — a genuine Clojure map-literal echo, a Context being a bag of key/value settings; the leading `^` sets it apart from Parallel's plain `{ }` |
| `'[ ]`    | `Data`        | data container |

`( )` means two things, never ambiguous with each other since they're
reachable from totally different positions: a slur mark glued directly
onto a Note/Chord (`c4( d4 e4)`), LilyPond-style, at a note's own
trailing suffix position (always glued with no separator onto an
existing note/chord, never a fresh element of its own); and `Scope`,
the transient body of the backslash-prefixed structural commands below
(`\transpose c g ( c4 d4 e4 )`) — never a container of its own, always
spliced/stashed at pop time, same as `[ ]` is for `repeat`/
`alternative`'s own body. (`StructValue`'s own unrelated `( )`, a
parenthesized `Value` after `!name:`, is a third, textually-distinct
use reached only from that one position — see `musics.ebnf` directly
if you need it, not covered further here.)

`AtomicAlgo`/`ElementAlgo` (`@[ ]`/`@{ }`, grammar-native algorithm
invocation) don't exist in this grammar at all — see the "Algorithm
registries" note above for what replaced them (parameterized playback
algorithms live entirely on the `play`/`core.wall` side now, never in
text). `(par ...)` is likewise not this grammar's own spelling for
anything — that's tier 3's play-arg mini-language (see "The play-arg
mini-language" below for `core.compose/par`, a genuinely different,
Clojure-data-side mechanism with its own reasons for existing,
unrelated to this text grammar's own `{ }` Parallel).

**A bare top-level program element still can't write into `:ROOT`, but
a bare `Leaf` no longer needs a wrapping container to parse at all.**
`TopElement` (`Program`'s own top-level element list) is `Composite |
Leaf | repeat | VarDef` — a bare, un-nested `c4` now parses as valid
`Program` text on its own (`repeat` alone covers unfold/volta/tremolo
now, tremolo folded in as a third `repeat-type` rather than a sibling
rule). This is still deliberately narrower than `Element` (used
everywhere *inside* a container, where `Leaf`/`Instruction`/
`Reference`/`VarRef`/transient `Command` are all still completely
ordinary): `Instruction`, transient `Command` (`transpose`/`grace` —
not `repeat`, which persists as a real retained container and was
never affected), `Reference` (when it resolves to a `^{ }` `:CONTEXT`
block), and `VarRef` all still write directly into
whatever context is on top of the builder stack if reached bare at
`Program`'s own top level — before any real container has been
entered, that's `:ROOT` itself, meant to stay a read-only endpoint with
a guaranteed value for every key (`common.context-keys/root-defaults`,
`core.domain.context/context-root`). Three separate, independently-
confirmed-live write paths existed before `TopElement` was first
restricted: a bare `Instruction`; a bare transient `Command`
(`pop-container` replays any instruction written inside one onto
whatever's on the stack once its wrapper splices away); and a bare
`Leaf`/`Chord` with a note-glued dynamic (`c4\f`, ordinary surface
syntax — `apply-note-dynamics!` writes through the same mechanism a
standalone `!f` does). The first two of those three are still excluded
from `TopElement` for exactly that reason. The third is now handled
differently instead of by exclusion: `flat-tree-walker/walk` auto-wraps
a bare top-level `Leaf` in its own ordinary, auto-id'd one-child
`:SEQ` Sequence before walking it (the same `push-container`/walk/
`pop-container` idiom the walker's own `:Sequence` case already uses)
— the wrapper gets a genuine `:context` of its own, so `c4\f`'s own
dynamic lands there, never on `:ROOT`, exactly as safe as writing
`[c4\f]` yourself, confirmed live (parsing a bare `c4\f`, then a second
unrelated bare note, leaves `:ROOT`'s own `:volume` at its unmodified
default both times). `Reference`/`VarRef` were not changed — they have
a different risk (replaying a whole stashed envelope, not just one
dynamic mark) and stay excluded, same as `Instruction`/transient
`Command`. One real consequence of the new behavior: several bare
leaves typed back-to-back with no `[ ]` (`c4 d4`) parse as **two**
separate one-note top-level Sequences, not one combined two-note
Sequence — `[ ]` is still required to group more than one `Leaf`
together. See `musics.ebnf`'s own comment on `TopElement` for the full
detail, and `doc/decisions.md` for why `Leaf` was handled this way
instead of by carving a dynamic-free-only exception out of its own
grammar rule.

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
always been the correct, unambiguous bracket for them — unlike
`transpose`/`reverse`'s own (transient, spliced-not-registered) `( )`
`Scope` body, which is never registered at all.

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT` whose envelope
points get replayed onto the current container's context at the current beat
offset — see `apply-context-ref` in `flat_tree_walker.clj`). `VarDef` is
`name = [ ... ]` (reuses `Sequence`'s own `[ ]` — see the bracket table
above); `VarRef` is `\name` — see "Comments
and variables" below.

`BarLine` (`|`, `||`, `|||`, `||||`) walks to a `Bar` record (`d/bar`,
zero duration) inline in `:children` — purely a structural marker on disk,
but no longer inert at playback: `core.events` emits a `:mark` event
for each one a voice reaches, which the engine signals to
`core.conductor` (see "Conductor"
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
canonicalize to the same `:Tempo` context key (`common/context_keys.clj`)
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

Accidentals are GUIDO's symbols only — `#`/`##`/`&`/`&&`/`n`; LilyPond's
Dutch suffixes have to be rewritten (see `doc/lilypond.md`). A dynamic mark or hairpin glued directly
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
(`fn`/`f#`/`f&`/...) always overrides that outright. This is a
deliberate departure from LilyPond itself, whose input is always
literal (key affects printing only) — gated by a context key,
`:accidentals` (`!acc:`), `:implied` by default or `:explicit`
for literal/LilyPond-style resolution, rather than tying the behavior to
`!key:` itself (which already has an unrelated existing use — ornaments'
scale-relative resolution — that shouldn't gain a silent side effect). C
major (the context default when no `!key:` is ever set) implies nothing
either way, so a piece that never sets a key is completely unaffected.
`lilypond_import.clj` emits `!acc:explicit` once, ahead of
everything else, on every converted piece — imported content's meaning
should never depend on this format's own default, since real LilyPond
source is always already literal.

The key also applies where a note is played, not only where it was
written: a note/chord leaf keeps the Key its letters were resolved
against (`:key`, `flat-tree-walker/written-key`; none under
`:explicit`), and `core.domain.resolve/rekey` — run per leaf before the
wall and ornaments, by `core.events` and `display-timed` — moves each
pitch that is a degree of that key to the playing key's accidental for
that diatonic step (`common.music-elements/rekey`, `key-step`):
`[mot: c d e f g]` plays `c d e f# g` inside `[!key:G.major :mot]`. A pitch outside the leaf's
key (written with its own accidental) stays; so does a container's own
`!key:` (nearest wins) and anything under `!acc:explicit` where it's
played. A written accidental that the key already implies (`fn` in C)
can't be told from a bare letter and follows too. `\transpose` adds
its interval to the leaf's `:key-shift`, and `rekey` transposes both
keys by it before comparing, so transposed notes follow the playing key
transposed the same way; their printed names are spelled in the
transposed key (`common.music-elements/transpose-key`). A chordmode
chord moves by its root (`:key-root`), keeping its written quality
(`F4` in C plays F# major under G). Generated notes carry no `:key` and
never move.

### Micro-timing: `:micro`/`:humanization` context keys

Two context keys — `:micro` (a direct per-note onset offset, seconds,
range -0.5..0.5) and `:humanization` (a random-spread magnitude,
0.0..1.0) — move a note's real wall-clock onset, sampled in the same
batched `common-keys+defaults`/`c/sample-many` pass `:Meter`/`:Partial`
already ride in. `core.domain.resolve/humanize` turns them into an
onset offset and a velocity: `:micro`, plus a random onset shift and
velocity change of up to the `:humanization` quantity's `:spread`
(`common.music-data/quantities`) either way, × `:humanization`. The engine (`core.engine/schedule!`, drawing with `rand`)
and `musics.core/render` (`midi-file/events->sequence`, a seeded
Random) both call it. Both keys default to `0.0`, so a piece that never
sets either is unaffected. A negative
`:micro` moves a note EARLY, by at most the engine's lookahead (100 ms)
live — a note is queued no sooner than that before its nominal time —
and by the full amount in `musics.core/render`. The offset belongs to
that one note: the voice's own `:t` still advances by the note's
unperturbed duration, so one note's delay never drifts the ones after
it.

`:swing`/`:groove` (metric-grid-aware, beat-subdivision-dependent
timing deformation, as opposed to `:micro`/`:humanization`'s flat
per-note offset) are deliberately NOT covered by this — `:swing` is
already a registered context key (`common/context_keys.clj`, with named
shortcuts `!straight`/`!swing`/`!shuffle`) but nothing samples or
applies it yet; doing so correctly needs real beat/subdivision-position
detection against the active `Meter`, a genuinely bigger, separate
piece of work than the flat per-note offset above.

### Other modules worth knowing about

- `core/repo.clj` — the flat `{id -> node}` store (see "Session, the
  repo, and playback" above); zero dependencies on the domain model or
  anything else in the project, deliberately (it requires only
  `core.registries`, the leaf namespace holding its actual atom).
- `core/conductor.clj` — the signal/schedule layer (see "Conductor" above);
  depends on nothing else in the project at all, not even `core.repo`.
- `core/wall.clj` — the per-voice playback-algorithm registry (see "Wall:
  per-voice playback algorithms" above); a parked toolbox, no dependency
  on `core.engine` at all (that dependency runs the other way).
- `core/compose.clj` — the play-arg Form grammar + `display`/
  `display-timed` (see
  "Composing vs. performing" above); engine-free, `core.events`/
  `core.engine` depend on it, never the reverse.
- `core/events.clj` — a Form's performance as a lazy seq of timed
  events (see "Performance as data" above); engine-free, like
  `core.compose`, which it builds on.
- `core/engine.clj` — live playback: one sender thread performing
  `core.events` streams (see "Live playback" above).
- `common/music_data.clj` — requires nothing; everything else in
  `common/` and every algo builds on it. Its `quantities` table is the
  one source of truth for numeric ranges and defaults: name →
  `{:type :min :max :default :scale :doc}`, the same shape as an algo
  param spec. `:scale :log` marks quantities heard as ratios — `:tempo`
  (30..300, default 100), `:note-value` (1/64..4, default 1/4), `:ratio`
  (1/16..16, default 1) — each default at the log middle of its range;
  `:pitch` (24..119, what musics text writes) and `:semitones` (±60) are
  linear. `(quantity :pitch {:doc ..})` returns a spec with overrides, for
  algo metadata. The rest is reference data ported from an earlier Python
  implementation (pitch names, note lengths, dynamics, scales, drum name
  → MIDI, …).
- `common/context_keys.clj` — the context-key registry (`!key:` names,
  aliases, types): `reg!` takes each key's range, default and scale from
  a quantity (`:Tempo` → `:tempo`, `:rate`/`:durScale` → `:ratio`,
  `:transposition` → `:semitones`), so the GUI's context sliders and the
  algo params agree by construction. The GUI shows a slider only for a
  key playback actually reads (`core.domain.resolve/played-keys`). A `:log` quantity's slider moves in
  equal ratios (`gui.lib.components/slider`, `:scale :log`).
- `common/music_elements.clj`, `common/music_tools.clj` — key
  parsing, `Meter`/indispensability (see above), and other music-theory
  helpers used by the walker/ornaments. `common/` is flat — no `data`/
  `elements`/`tools` subdirs — since each held only one or two files.
- `input/reader/leaf_parser.clj` — pitch/duration/articulation/dynamic
  parsing at the leaf level, independent of the grammar/lexer. `input/grammar_parser.clj`
  and `input/lilypond_import.clj` sit at the top level of `input/`, not
  nested under `reader/` — both are peers of, not sub-concerns of, the
  walker: `grammar_parser` is the actual pipeline entry point (`musics.core`
  calls it directly, and it's the one that calls *into*
  `input.reader.flat-tree-walker`, not the other way around), and
  `lilypond_import` is a fully independent LilyPond→musics-text converter
  that never touches `core.domain.*` or `input.reader.flat-*` at all.
  `input/abc_import.clj` is its sibling for ABC notation (a compact,
  plain-text folk/traditional-tune format) instead of LilyPond — a
  simpler implementation than `lilypond_import.clj` since ABC's own
  grammar is flatter (no `{ }`/`<< >>` nesting, no `\relative` pitch
  mode), so it uses ABSOLUTE pitch spelling (uppercase + explicit octave
  digit) rather than `lilypond_import`'s own relative-pitch-favoring
  style — a deliberate scope choice documented in its own ns docstring,
  not an oversight, since ABC's own note spelling is already fully
  absolute. Computes key-signature-implied accidentals itself (ABC
  source is literal, like a real staff, exactly the same problem
  `lilypond_import.clj` already solves with its own `!acc:
  explicit`) via `common.music-elements`'s own `scale-steps` table
  rather than a second hand-copied circle-of-fifths one — an EARLIER
  hand-typed version of that table had three real transcription errors
  (Gb missing its own C, Db carrying an extra one, Cb missing its own
  F), caught only by running all 15 major keys through it and checking
  every one of the 7 letters, not by spot-checking a couple of common
  ones; see `abc_import_test.clj`'s own `key-signature-covers-all-15-
  major-keys-with-correct-counts` for the regression coverage this left
  behind. `musics.core`'s `abc-to-mus` (writes a sibling `.mus` file,
  mirrors `ly-to-mus`) and `play-abc-file`/`play-ly-file` (convert +
  stage/commit/play in one step, entirely in memory — no file written)
  are the REPL-facing entry points; see their own docstrings for the
  exact `play!`-recipe shape they share.
- `input/guido_import.clj` is a third sibling, for GUIDO Music Notation
  (GMN) — a plain-text score format whose own accidental symbols (`#`/
  `##`/`&`/`&&`) and Parallel bracket (`{ }`) are what musics.ebnf's own
  choices were inspired by in the first place (see that grammar's own
  header comment), so this converter has an unusually close, near-1:1
  relationship to its source format: GUIDO accidentals need no
  translation at all, and GUIDO octave 1 (containing `a1`, the 440Hz A)
  is this DSL's own octave 4 — both are the middle-C octave, so every
  GUIDO octave number converts by a flat +3. `{ }` disambiguates a
  chord from parallel voices entirely by shape (comma-separated bare
  notes vs. comma-separated `[ ]`-wrapped sequences), matching GUIDO's
  own rule exactly. Every note/rest is emitted with an explicit,
  unelided duration digit (never relying on this grammar's own elision,
  the same deliberate choice `abc_import.clj` makes) — and, unlike its
  two siblings, that digit is followed by a mandatory `/` even when
  musics.ebnf's own comment on `OctaveAbs` calls the slash optional:
  omitting it once actually did compile and pass every existing test
  (string-content assertions only), because a duration digit
  immediately following an octave digit with no `/` silently reparses
  as no-octave (defaulting to octave 4) plus a wrong, merged-together
  Duration instead of a parse error — confirmed live in both
  `guido_import.clj` and (once checked) already-shipped
  `abc_import.clj` code, fixed in both; see `note->pitch-text`'s own
  docstring in either file, and `abc_import_test.clj`'s own domain-
  level (`:pitches`/`:duration`, not just substring) regression test
  for exactly the case this closes — `guido_import_test.clj` carries
  the same kind of domain-level check for this converter directly.
  Unlike `abc_import.clj`, this converter does NOT imply an accidental
  from `\key` onto a bare note — every note is emitted exactly as
  written, LilyPond-style — a judgment call, not a confirmed spec fact
  (GUIDO's own documentation doesn't say either way); see this file's
  own `KEY-IMPLIED ACCIDENTALS` docstring section for the structural
  reasoning behind it. `musics.core`'s `guido-to-mus` and
  `play-guido-file` are the REPL-facing entry points, same `play!`-
  recipe shape as `ly-to-mus`/`abc-to-mus`/`play-ly-file`/
  `play-abc-file`.
- `core/domain/ornaments.clj` — expands a `Leaf`'s ornament/grace/tremolo
  modifier into replacement sub-leaves at resolve time (needs the active
  `Key` from context for scale-relative ornaments like `prall`); lives with
  the rest of the domain model, not under `output/`, since it never touches
  MIDI itself.
- `output/midi/midi_file.clj` / `output/midi/midi_live.clj` — the two MIDI
  OUTPUT backends (file-based `aplaymidi` playback vs. live Fluidsynth via
  VirMIDI). `midi_live.clj`'s `Receiver` (`open-receiver`/`note-on`/
  `note-off`/`program-change`/`control-change`) is what `core.engine`
  uses for real sound; `midi_file.clj` writes `.mid` files —
  `events->sequence`/`write-events` render `core.events` output (one
  track per voice, 1000 ticks a second, channels pooled by
  `[program cc]` like the live engine's), behind `musics.core/render`. `midi_live.clj`'s own device discovery
  (`find-writable-device`) is backed by `overtone.midi` now, not a
  hand-rolled `MidiSystem` walk — see "MIDI input" above, which uses that
  same library directly for the opposite direction (`input/midi.clj`/
  `input/midi_record.clj`); everything else about `midi_live.clj`
  (auto-connect, byte clamping, its public API) is unchanged.
- `algo/` (renamed from `algorithm/`) — generative helpers, organized into
  topic subdirs: `indisp/` (Barlow indispensability); `metric/` (modular/
  binary/continued-fraction pulse generators); `rhythmic/` (Euclidean/
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
  `java-reference/`, `julia-reference/`, `kotlin-reference/`, and
  `python-reference/` — prior implementations of this same system in
  other languages that these ports came from — are no longer part of
  the repo at all; see `doc/decisions.md` for why (each file under
  `algo/` that traces back to one of them still says so in its own
  header comment).

## Known rough edges (found, not yet fixed)

Two pre-existing quirks are still there — noted so neither is silently
rediscovered as something new:

- **An `Id` inside a transient/scratch container's body is silently
  discarded**: `transpose`/`reverse`/a grace decoration's `Scope`/bare-
  `Element` body, and a `VarDef`'s `Sequence` value, all walk their
  children directly into a container that's never registered under its
  own id (transient ones get spliced into the parent and discarded;
  `VarDef`'s scratch container is popped by hand and never touches
  `:repo` at all). If that body happens to contain an `Id`
  (`\transpose c d ( myname: c4 d )`, or `motif = [myname: c4 d]`),
  `walk-bareword` still renames the container currently on the stack —
  it just renames a container that's about to vanish either way, so the
  name has no effect and produces no error. Same underlying mechanism,
  both places.

- **A nested container reference inside `transpose`/`reverse`'s own
  body is never recursed into — only that body's own immediate
  leaf-shaped children are affected, confirmed live, not just
  suspected**: `flat-core-builder/transpose-pitches!`/`reverse-
  children!` both operate on ONLY the current (transient) container's
  own top-level `:children`, `transpose-pitches!` guarded by `(if
  (:pitches child) ...)` (a bare keyword reference or a nested
  container has no `:pitches` field of its own, so it's silently
  skipped, left completely untouched) — `reverse-children!` has no such
  guard at all, since reordering the whole list is already well-defined
  regardless of what each child is, but the same limitation still
  applies to what reordering DOESN'T reach: a referenced sub-
  container's own position among its siblings moves along with
  everything else, but its own internal content is never itself
  reversed. Concretely, confirmed live: `\transpose c d ( c4 [inner:
  d4] )` transposes the bare `c4` but leaves `:inner`'s own `d4` at its
  original, as-written pitch; `\reverse ( c4 [inner: d4 e4] f4 )`
  reverses the top-level order (`f4`, `:inner`, `c4`) but `:inner`'s own
  children stay `[d4 e4]`, neither reordered nor recursed into. Not a
  bug so much as an unenforced boundary — the grammar happily accepts a
  full `Element` list as either command's own `Scope` body, so nesting
  a reference/sub-sequence there parses fine and produces no error, it
  just silently doesn't do what nesting it might suggest.
