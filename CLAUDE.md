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
2. **Sound** — `core.async-engine` (voices, one core.async goroutine
   per independent line) + `core.domain.resolve` (context sampling,
   actualization) + `output.midi.midi_live`/`midi_file` (MIDI
   dispatch). Turns committed material into real-time or rendered
   sound.
3. **The playground** — `play`'s own mini-language (`core.async-
   engine`, thin `musics.core` wrappers) + `core.wall` (per-voice
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
but spell it differently on each side, and the spellings don't even
agree within a tier: tier 1 (`.mus` text) writes `[ ]` sequential /
`(par ...)` parallel; tier 3 (a `play` call) writes `[]` sequential /
`#{}` *or* `(par ...)` parallel, `#{}` still the shorter everyday
spelling there, `par` only for the one case `#{}` structurally can't
express (see `doc/decisions.md`'s Wave 7 entry). They stay genuinely different
*languages* regardless of surface overlap: tier 1 is text, parsed once
by instaparse into permanent content; tier 3 is Clojure data,
evaluated fresh at every call, describing a performance choice rather
than the music itself. That's why `:algo` tagging (a wall-algorithm
assignment) only ever exists on the tier-3 side, deliberately never
reachable from `.mus` text — see "Wall: per-voice playback algorithms"
below.

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
actions; every voice carries its own `:view`/`:algo`/`:bar`/`:clock`
state rather than sharing one engine-wide pointer or counter, addressed
by its own real path (`:TAA`, `:TAB`, ...) in an unbounded `:voices`
map — `:view` a frozen snapshot of the repo captured once at birth, so
a later commit never glitches a voice already mid-performance; `core.wall`
gives a composer pluggable, hot-swappable per-voice playback algorithms;
and the `play` mini-language and `musics.ebnf` itself share one
vocabulary — `[]`/`[ ]` always sequential, `#{}`/`(par ...)` always
parallel, on both the Clojure-arg side and the text-grammar side.

See `doc/decisions.md` for the dated history of how each of these
arrived (search for "Wave" there for the seven stages the grammar/
play-mini-language convergence went through, and its 2026-09-17 entries
for how `core.repo` itself went from tx-versioned history down to a
flat map) — this file describes the system as it stands today, not how
it got here.

If you find something that still assumes the old (pre-flat, pre-
`core.repo`, pre-unified-`[]`/`#{}`/`(par ...)`-vocabulary, or
tx-versioned-`core.repo`) model exists, that's stale — update or remove
it rather than working around it.

## Commands

Leiningen project (`project.clj`), Clojure 1.12, two dependencies:
`instaparse` (parsing) and `org.clojure/core.async` (the playback engine).

```bash
lein repl              # start a REPL (init-ns is `user`)
lein test               # run the full test suite (test/ dir)
lein test command-walk-test         # run a single test namespace
lein test :only command-walk-test/times-scales-durations   # single test var
lein test :parsing      # just one architectural layer -- :parsing/:domain/
                         # :engine/:repl/:lang/:algo (test-selectors in
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
  └─ core.async-engine/play      → each voice walks its OWN :view,
       │                                   just-in-time (a frozen
       │                                   snapshot of the repo,
       │                                   captured once at birth --
       │                                   a later commit never moves
       │                                   it on its own)
       ├─ core.domain.resolve/resolve-event (per leaf, at fire-time) → MIDI-ish maps
       └─ core.conductor/signal!         (per section/bar/mark boundary,
            → registered actions           :voice carried opaquely)
              (e.g. core.async-engine/schedule-tx!, redirecting ONE
               voice's own :view to whatever's currently committed)
```

`core.domain.resolve` used to also have `form-unroll`/`form-unroll-lazy`
(eager/lazy whole-tree-to-tracks flattening), from before `async-engine`
switched to walking the repo tree directly, just-in-time. They were unused
once that switch happened and have since been removed — if you find a
reference to either in an older doc or comment, that's stale.

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
  the live `{id -> node}` atom itself, what a brand-new voice's own
  `:view` snapshots at birth. `registry` is a thin FUNCTION, not a bare
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

- **A voice's own `:view`** — a frozen snapshot of the registry, captured
  once, the moment `(play ...)`/`(warm-up! ...)` creates it (see
  `core.async-engine`'s own docstring) — it is **not** re-read
  continuously; each already-running voice reads through its own private
  `:view` from then on (forked at `:PAR` exactly like
  `:clock`/`:structural`/`:bar` are, seeded from the parent's current
  value). **Committing never moves it.** A brand-new voice always starts
  from whatever's current, automatically — no extra step needed — but an
  already-running voice's own `:view` stays exactly where it was until
  `(schedule-tx! id phase)` (see below) redirects ONE voice at a chosen
  boundary. This is deliberate: it's what lets you prepare an edit
  mid-performance without it glitching whatever's currently sounding —
  and, since `:view` is per-voice, without one part's cutover glitching a
  *different*, still-playing part either (the failure mode a single
  shared pointer couldn't avoid — see `doc/decisions.md`'s Wave 4 and
  2026-09-17 entries).

`write`/`load` persist/replace whatever's currently committed (via
`core.repo/seed!`), not the performance layered on top of it (a voice's
`:algo` assignment can transform pitch/duration wholesale). `reset` wipes
`core.repo` entirely and re-bootstraps a fresh `:ROOT`.
`persist-session`/`restore-session` (`core.persist`) are the fuller pair
for that — same repo+auto-ids round-trip as `write`/`load`,
plus whatever's CURRENTLY LIVE right now (`core.async-engine/live-algos`,
path -> Name read straight off each live voice's own immutable `:algo`
field — deliberately NOT `algo-assignments`/`:algo-prepared`, a separate,
narrower table that an ordinary `:algo`-tagged `play`/`play-add` call
never even writes to; see "Wall" below). Name is EDN-safe by
construction — always `nil` or a bare keyword, never a resolved wall fn
itself, a live closure that can never survive a round-trip. Restoring
replays each Name through `assign-algo!` — into the PREP table, not
onto any voice directly, since restoring never recreates a live voice
itself — so a later, untagged `play`/`play-change` call at that same
path picks it back up automatically. A Name whose algorithm isn't
re-built yet degrades to `identity-algo` with a console warning, same
as `assign-algo!` always has, not a new failure mode.
Deliberately does NOT also cover which factory+args originally BUILT a
given `*algo-registry*` entry (the factory itself stays registered —
factories are permanent now, see `core.wall`'s own docstring — but the
registry only ever stores the RESOLVED fn a `build!` call produced, not
the recipe that produced it, so there's nothing to read back out and
replay) or `core.conductor`'s schedule/repeating tables (pending cues in ONE
specific live performance, not composed material — closer to a paused
breakpoint than a saved document) — both left as documented, deliberate
gaps rather than silently declared solved. See `doc/decisions.md` for
why `persist-session`/`restore-session` exist as a separate pair from
`write`/`load` rather than `write`/`load` themselves changing shape.

### Conductor: signals and scheduled actions

`core.conductor` (`src/core/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `async-engine` depends on
it (a plain synchronous function call, `conductor/signal!`, from
`play-node`/`advance-bar!`/`mark!`); `core.conductor` depends on nothing
else at all, not even `core.repo` — a fully generic dispatcher,
deliberately one-way (see `doc/decisions.md`'s Wave 4 entry for why
`schedule-tx!` itself lives in `core.async-engine`, not here, even
though it builds on `register-action!`/`schedule!` from this file). The
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
- **`core.async-engine/schedule-tx!`** — the primary use case, built on
  the two pieces above but living in `core.async-engine` now, not here
  (see `doc/decisions.md`'s Wave 4 entry): `(schedule-tx! id phase)`
  redirects the ONE voice whose own boundary crossing triggers it over
  to whatever's CURRENTLY committed, resolved at the moment it actually
  fires, not when it was scheduled, the next time `[id phase]` is
  signaled — `(reset! (:view (:voice event)) @(core-repo/registry))`,
  reaching the right voice via `:voice` in the signal event. Other
  voices are untouched.

### Wall: per-voice playback algorithms

See `doc/algorithms.md` for the practical, use-it-from-the-REPL guide
(the taxonomy of algorithm shapes, which ones this mechanism actually
reaches vs. which are plain Clojure calls, worked examples) — this
section stays the mechanism's own architecture reference.

`core.wall` (`src/core/wall.clj`) holds TWO registries, not one. A wall
fn is always seq-in/seq-out: `(nodes ctx-chain voice) -> nodes'`, called
identically regardless of granularity — `core.async-engine`'s container
branch calls it once, on the WHOLE sibling list, before either
`play-par`/`play-seq` or ornament expansion ever sees it; its leaf/rest/
drum branch calls it with a singleton wrapping one already-ornament-
expanded node. An algo never declares which one it "acts on" — it just
always receives a seq.

Every algo is a **factory** — `(fn [name params] -> name)`, `params`
ALWAYS a plain map, the one uniform shape every factory takes (see
`doc/decisions.md` for why: a positional arg list whose shape differs
per factory wouldn't be toolable the way a `{key value}` map is) — even
one that takes no configuration at all: `name` is the factory's OWN
first argument, the name its result
gets stored under, not a separate wrapper's concern. `register-factory!`
(`core.wall`, thin `musics.core` wrapper) parks a factory PERMANENTLY in
`*algo-factory-registry*` — nothing in `core.wall` ever overwrites an
existing entry there, so a factory stays available to build any number
of independently-named results off of. Calling a factory (directly, or
via `build!` if you only have its registered name) builds a real,
resolved wall fn and stores it — via `build-algo!`, the shared low-level
step every factory calls as its own last line — under `name` in a
SEPARATE store, `*algo-registry*`: cooked, ready-to-play results only,
one entry per built name. `build!` additionally stamps `:factory-name`/
`:params` (the resolved params) onto that same entry once the factory
call returns — the recipe, not just the resolved fn, closing a
previously-documented gap (a factory called directly still stamps
nothing). `core.wall/algo` (the raw name -> fn lookup into THAT store)
has no `musics.core` wrapper, `require` `core.wall` directly if you need
it; `core.wall/registered`/`musics.core`'s own `registered` wrapper
surfaces the FULL entry (including `:factory-name`/`:params`) for every
built algo at once.

**Voice paths, not slot numbers**: every voice's own registry key
(`core.async-engine`'s `:voices` atom) is a vector, root-first, one
segment per level of forking — the same path also addresses that
voice's own `:algo-prepared` entry, if any (see below), though an
already-minted voice's own algorithm doesn't need that lookup at all.
A voice's own `:algo` is a plain, IMMUTABLE value, baked onto its voice
map once, at mint/fork time, and never reassigned afterward —
`voice-algo-slot-fn` reads it straight off the voice (`(:algo voice)`),
then resolves it via `core.wall/algo` FRESH every single node (never
cached), so hot-swapping still works exactly one way now: rebuilding
the SAME `name`'s own entry in `*algo-registry*` (`build!`/calling a
factory directly again) — every voice whose own `:algo` already points
at `name` picks up the change on its very next node, with nothing on
the voice itself ever touched.

`assign-algo!`/`algo-assignments` (`core.async-engine`, thin
`musics.core` wrappers of the same name) are a SEPARATE, narrower
mechanism: `:algo-prepared`, `path -> name`, consulted ONLY at mint
time (`mint-leaf!`/`start-top-level-voice!`), and only when that call's
own `:algo` argument is `nil`. `assign-algo!` never reaches an
already-live voice — it only affects a mint that hasn't happened yet
(preparing a track before you start it, or `core.persist`'s own
`restore-session` replaying a saved snapshot — see "Session, the repo,
and playback" above). See `doc/decisions.md` for why a
voice's own `:algo` is immutable once minted rather than a live,
externally-reassignable table.

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
see `doc/decisions.md`'s Wave 7 entry and `core.compose/par`'s own docstring for why
— `#{...}` still works identically for its own common case, just can't
express a repeated Form the way `par` can), or `[Form :algo Name]`
(exactly one Form, optionally
tagged with a algos-registered name or `nil`). The collection type
alone is the tag — vector always `:seq`, set always `:par`, no
guessing (see `doc/decisions.md`'s Wave 6 entry for why). `musics.ebnf`'s
own container brackets share this same mini-language (Wave 6, `[ ]` on
both sides; Wave 7 then moved Parallel's own spelling again, on both
sides together, from `#{ }` to `(par ...)` — see "Grammar" below and
`doc/decisions.md`'s Wave 6/7 entries), not just a mirrored shape under different brackets, so the
two are literally the same vocabulary today, not just structurally
analogous — a plain Clojure `#{...}` set literal still works as a
play-arg (see `core.compose/par`'s own docstring for why it's
additive, not a breaking removal on that side), it's just no longer
the spelling either side actually documents or uses by default.
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
`:algo` field/`voice-algo-slot-fn` every voice already goes through, no
separate one-shot/direct-apply path — in one of two temporal patterns:
**permanent**, for a voice being freshly minted/forked right here
(`play`/`play-add`'s own top-level tag, and each `#{}` branch's own
tag, via `resolve-form-tag`) — baked directly into the voice map at
construction, covering that voice's entire remaining life; or a
**local, immutable-update shadow** of the CURRENT voice (`(assoc voice
:algo name)`), for a tag sitting inside an ongoing `[]` walk where the
same voice continues on to more material afterward (`play-form-tagged`)
— no shared state touched at all, restoration is automatic, ordinary
lexical scoping once the shadowed call returns, so a tag nested inside
an already-tagged outer span correctly falls back to the outer tag
afterward, not identity, with nothing explicit tracking "what was there
before." A `#{}` tagged as a whole
applies its algorithm to every branch as that branch's own DEFAULT — a
branch's own closer tag still wins (`resolve-form-tag`, shared by
`mint-branches!` and `play-form-par` alike, so a `#{}`'s own tag behaves
identically whether it's at `play`'s own top level or nested inside
other material).

**`play`/`play-add` mint one or more track ids from a SINGLE Form, plus
an OPTIONAL trailing `:algo Name`.** `(play Form)` or `(play Form :algo
Name)` — both `core.async-engine` fns (thin `musics.core` wrappers),
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
minting itself) — `play-top-level!` runs it before `pre-fn`/
`mint-branches!` ever touch anything — a rejected/typo'd call still can
never disturb `:voices` or mint an orphaned voice, exactly the same
tested invariant this project already held for `play`'s own flush
before this change. `play-change` keeps its own older
explicit-path/variadic-args shape (via `start-top-level-voice!`)
rather than `play`/`play-add`'s newer single-Form-plus-`:algo` one — it
always targets exactly one already-known path, so none of
`mint-branches!`'s "how many voices, and which ids, does this call need
to invent" logic applies to it — but it takes the same OPTIONAL
trailing `:algo Name` too (`split-change-args`, stripping it off the
tail of its own variadic args rather than `split-call-args`'s
exactly-one-Form discipline), so a chosen track can be started with an
algorithm in one call: `(play-change :myTrack form :algo :bright)`,
with no separate `assign-algo!` step needed. `display`
(`core.compose`'s fully synchronous, `*engine*`-free preview of
what `play` would do — moved out of `core.async-engine` entirely,
see "Composing vs. performing" below) mirrors the same `[]`/`#{}`/tag
dispatch (`realize-form`/`realize-form-par`/`realize-form-group`) but keeps its
own older variadic-args shape too, same reasoning as `play-change`; its
`realize-form-par` now explicitly mean-pitch-ranks its own children
before showing them; a real `[:PAR]` container never needed that (a
literal, ordered `[:par ...]` vector used to just get walked in written
order), but `#{}` has no reliable order of its own to fall back on. A
tag has no visible effect on `display`'s own output — it's purely
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
by extracting an existing container. `par-form?` (`core.async-engine`'s
one place deciding "is this Form a parallel group") and
`form-tag+items` both recognize either shape identically; `#{}` itself
is unchanged and still the natural, terser spelling whenever branches
are naturally already distinct.

**Parameterized algorithms: always built ahead of time, under their own
name.** `Name` in a tag is always a bare, already-built,
`algos`-registered name or `nil` — checked eagerly, at the `play` call
itself (`validate-algo-name!`), never a Name-shaped place to apply a
factory to params inline anymore. `assign-algo!`'s own `name` argument is
looser still — it doesn't have to already be built at all, since it's
only ever stored as-is in `:algo-prepared`, unresolved, until whatever
it eventually mints actually reads it. Applying a
factory happens earlier, as its own explicit step: `build!` (thin
`musics.core` wrapper) looks up `factory-name` in
`*algo-factory-registry*` and calls it with `(name params)` — `params`
ALWAYS a plain map, the one uniform shape every factory takes — the
factory's own last line stores the result via `build-algo!` — or, if you
already have the factory in hand (not just its registered name), call it
directly the same way:
```clojure
(register-factory! :transpose (fn [name {:keys [n]}] (build-algo! name (fn [nodes ctx voice] ...))))
(build! :transposed5 :transpose {:n 5})
(play :melody :algo :transposed5)
```
Each VALUE in `params` goes through the SAME `resolve-config-form`
resolution `configure-preset!` used to (a bare keyword resolves against
the latest committed repo, straight to a `:DATA` container's own raw
values if it names one; everything else passes through as a literal),
so a factory's params can be fed either inline literals or real,
committed Material, composer's choice per call. On success, `build!`
also stamps `:factory-name`/`:params` (the resolved params) onto `name`'s
own `*algo-registry*` entry — the recipe, not just the resolved fn,
readable back via `(registered name)`/`core.wall/registered` — closing a
previously-documented gap (a factory called directly, bypassing `build!`,
still stamps nothing). An unregistered `factory-name`, or a factory that
throws applying its params, prints a plain console warning and builds
`identity-algo` under `name` instead of erroring (with no `:factory-name`/
`:params` stamped in that case either) — same "degrade and warn, never
throw" policy this mechanism has everywhere else — and a bare,
unregistered `name` referenced later in a tag/`assign-algo!` call
degrades to `identity-algo` the same way.

**Hot-swapping replaces reconfiguring.** Because factories are
PERMANENT and `build!` always targets an explicit `name`, there's no
more "install once, configure later" duality, and no more "reconfiguring
needs the factory re-registered first" limitation — call `build!` again
with the SAME `name` (the same `factory-name`, or a different one) any
number of times, and every voice/track currently pointing at `name`
picks up the change on its very next node:
```clojure
(build! :verseColor :colorTalea {:color color1 :talea talea1})
(play :verse :algo :verseColor)
(build! :verseColor :colorTalea {:color color2 :talea talea2})   ; hot-swapped
                                                  ; in place, :verse picks
                                                  ; it up on its next node
```
Several independent, coexisting presets built off one factory need no
second registry — `(build! :bright :colorTalea ...)` and
`(build! :dark :colorTalea ...)` off the same factory already coexist
in `*algo-registry*` alone, since a build always needs its own explicit
target name (see `doc/decisions.md` for why an earlier, separate
`configure-preset!`/`*preset-registry*` store was merged away rather
than kept alongside this).

### Composing vs. performing: `core.compose`

`core.compose` (`src/core/compose.clj`) holds the play-arg mini-
language's own Form-shape grammar — `tagged-form?`/`split-tag`/
`resolve-form-tag`/`par-form?`/`par`/`form-tag+items`/
`peel-group-contexts`, plus `live-repo`/`build-chain`/
`mean-pitch-rank`/`form-pitch-source` — and `display`, the mini-
language's fully synchronous preview (`realize-form`/`realize-node`/
`realize-iterator`/etc., all private). Deliberately engine-free:
nothing here touches `*engine*`, a voice, `core.async`, or MIDI.

This is a **shared toolkit**, not a pipeline stage — `core.async-
engine`'s `play`/`play-node` and this ns's own `display` each walk a
Form on their own, live, calling INTO these functions at every node/
group they visit, not once up front. Neither one ever hands the other
a pre-computed result to consume; there's no intermediate "compose
produces X, engine plays X" moment, matching this project's own long-
standing "nothing is materialized between parse and play" principle
(see "Pipeline (current)" above) — a real compose-then-execute
pipeline would mean materializing something ahead of time, which this
project has deliberately never done anywhere else either.

`core.async-engine` requires `core.compose` (for the Form grammar its
own `play-form*` family needs); `core.compose` requires only
`core.repo`/`core.domain.*` — never `core.async-engine` — so the
dependency runs exactly one way, verified directly (every function
moved here was checked for an engine/voice/MIDI dependency before the
move, not assumed) rather than just intended.

Lives separately from `core.async-engine` — a purely-functional
grammar+preview layer has no business in a file whose own job is being
*the* real-time playback engine. See `doc/decisions.md` for the full
reasoning behind the split (including why this ISN'T a third tier
alongside Material/Sound/The playground — it's a sub-piece of tier 3,
the part of the play-arg mini-language that's execution-agnostic, not
a new architectural layer).

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

Text-level algorithm invocation isn't part of the grammar. Parameterized
playback algorithms live entirely on the `play`/`core.wall` side —
every algo built ahead of time, under its own explicit name, via
`core.wall/build!` (or calling a registered factory directly) — never
in text; see "Wall: per-voice playback algorithms" above. See
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
original's. Call either directly, or wrap one as a factory
(`core.wall/register-factory!`/`build!`) to reach it as a per-voice
playback transform.

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
- **Transient containers** (`:TIMES`/`:TUPLET`/`:TRANSPOSE`/`:REVERSE`/
  `:DECORATED`, i.e. `\times`/`\tuplet`/`\transpose`/`reverse`/a grace
  decoration) are notationally invisible: `flat-core-builder/pop-container`
  splices their `:children` straight into the parent and never registers
  them under an id at all -- no separate container survives in the tree.
  `times`/`tuplet`/`transpose`/`reverse` are Lisp prefix calls
  (`(times 2/3 [c8 d8 e8])`) spelling their body with `[ ]` -- the same
  `Sequence` grammar rule reused as-is (see the bracket table below);
  this replaced the earlier `\times 2/3 { c8 d8 e8 }` LilyPond-matching
  spelling once staying a close LilyPond superset stopped being a goal
  for this grammar (see "Grammar" below). `reverse` is pure reordering,
  no per-child value transform at all -- see "Known rough edges" below
  for the one behavior it shares with `times`/`tuplet`/`transpose`:
  none of the four recurse into a nested container reference sitting in
  their own body.
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
  entirely. `core.domain.resolve/effective-chain` re-bases each baked
  ancestor by `(structural-time - relative-offset)` at resolve time,
  which reconstructs exactly the entry point that ancestor's own
  container would have had — numerically identical to `build-chain`'s
  own per-container shifting for ordinary playback, and correct for a
  standalone/extracted leaf too. See `doc/decisions.md` for why the
  relative-offset subtraction specifically is load-bearing, not a
  simplification skipped for convenience.
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
  Each voice walks its own `:view` (a frozen snapshot of the repo,
  captured once at birth) directly and just-in-time -- no
  pre-flattening step -- so `:SEQ` runs its children one after another
  inside one voice (a go-block), `:PAR` forks each child into a sibling
  voice the parent awaits on, and each leaf is resolved via `resolve-event`
  right as it fires. This also means `:count :infinite` Iterators fall
  out for free, no separate lazy/eager code path needed; live redirects
  work too, just per-voice now (a `(schedule-tx! ...)` cutover on ONE
  voice, resetting its own `:view` to a fresh snapshot) rather than one
  shared pointer every voice re-read continuously.
  Each voice also carries its own `:bar`/`:bar-pos`/`:marks`/`:view` atoms
  alongside `:clock`/`:structural` (forked, not reset, at `:PAR` -- see
  "Conductor" above), advanced by `advance-bar!`/`mark!` right alongside
  the clock. `*engine*` is a dynamic
  var so REPL calls (`play`, `stop!`, `pause!`, `resume!`) don't need to
  thread an engine value around; `pause!`/`stop!` are checked in ~20ms
  increments even mid-note, so pause freezes a sounding note in place (no
  retrigger on resume) and stop sends note-off promptly instead of waiting
  out the full duration. `play`'s args are a small mini-language (bare
  keyword = repo reference; `[Form+]` always sequential; `#{Form+}`/
  `(par Form+)` always parallel; `[Form :algo Name]` tags one Form with
  an algorithm -- see "The play-arg mini-language" under "Wall" above
  for the full grammar, or the docstrings in `async_engine.clj`). A
  group's tag
  doesn't have to be that literal leading keyword, either: `musics.core`'s
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
play-arg mini-language" below for `core.compose/par`, the
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

### Micro-timing: `:micro`/`:humanization` context keys

Two context keys — `:micro` (a direct per-note onset offset, seconds,
range -0.5..0.5) and `:humanization` (a random-jitter magnitude, 0.0..1.0)
— genuinely delay a note's real wall-clock onset, sampled in the same
batched `common-keys+defaults`/`c/sample-many` pass `:Meter`/`:Partial`
already ride in (`core.domain.resolve`), applied in
`core.async-engine/play-event!` as an extra `hold-until!` wait before
sending note-on. Both default to `0.0`, so a piece that never sets
either is completely unaffected — the extra wait is skipped entirely
whenever the computed offset is exactly `0.0`.

**Delay only, never anticipation**: `play-event!` has never had a wait
of its own before note-on — it fires the instant its go-block runs,
relying on the previous note's own hold having landed at the right
wall-clock moment. There's no earlier instant to reach back to, so a
negative `:micro` value (or any other computation that would produce a
negative total offset) clamps to `0.0` rather than being silently
ignored or becoming a negative timeout. `:humanization`'s own jitter is
scaled onto a fixed `humanize-max-jitter-secs` (0.05s at
`:humanization` 1.0) — a deliberately chosen, not rigorously derived,
constant.

The offset is a purely local scheduling target for the ONE note it
applies to — it never touches the voice's own running `:clock`/
`:structural` atoms, which still advance by the note's own unperturbed
duration at the end of `play-event!`. This is what keeps each note's
own offset independent: one note's own delay never compounds into
drift affecting every later note's own nominal position.

`:swing`/`:groove` (metric-grid-aware, beat-subdivision-dependent
timing deformation, as opposed to `:micro`/`:humanization`'s flat
per-note offset) are deliberately NOT covered by this — `:swing` is
already a registered context key (`common/defaults.clj`, with named
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
  on `core.async-engine` at all (that dependency runs the other way).
- `core/compose.clj` — the play-arg Form grammar + `display` (see
  "Composing vs. performing" above); engine-free, `core.async-engine`
  depends on it, never the reverse.
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
  `lilypond_import.clj` already solves with its own `!accidentals:
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
  discarded**: `times`/`tuplet`/`transpose`/`reverse`/a grace
  decoration's body, and a `VarDef`'s value, all walk their `[ ]`'s (or,
  for a grace decoration, bare `Element`'s) children directly into a
  container that's never registered under its own id (transient ones get
  spliced into the parent and discarded; `VarDef`'s scratch container is
  popped by hand and never touches `:repo` at all). If that body happens
  to contain an `Id` (`(times 2/3 [myname: c4 d])`, or `motif = [myname:
  c4 d]`), `walk-bareword` still renames the container currently on the
  stack — it just renames a container that's about to vanish either way,
  so the name has no effect and produces no error. Same underlying
  mechanism, both places.

- **A nested container reference inside `times`/`tuplet`/`transpose`/
  `reverse`'s own body is never recursed into — only that body's own
  immediate leaf-shaped children are affected, confirmed live, not just
  suspected**: `flat-core-builder/scale-durations!`/`transpose-pitches!`/
  `reverse-children!` all operate on ONLY the current (transient)
  container's own top-level `:children`, guarded by `(if (:duration
  child) ...)`/`(if (:pitches child) ...)` for the first two (a bare
  keyword reference to a real, registered container has neither field,
  so it's silently skipped, left completely untouched) — `reverse` has
  no such guard at all, since reordering the whole list is already
  well-defined regardless of what each child is, but the same limitation
  still applies to what reordering DOESN'T reach: a referenced
  sub-container's own position among its siblings moves along with
  everything else, but its own internal content is never itself
  reversed. Concretely: `(transpose c d [c4 [inner: d4]])` transposes
  `c4` but leaves `:inner`'s own `d4` at its original pitch;
  `(reverse [c4 [inner: d4 e4] f4])` reverses the top-level order
  (`f4`, `:inner`, `c4`) but `:inner`'s own children stay `[d4 e4]`,
  neither reordered nor recursed into. Not a bug so much as an
  unenforced boundary — the grammar happily accepts a full `Sequence` as
  any of these four commands' own body, so nesting a reference/sub-
  sequence there parses fine and produces no error, it just silently
  doesn't do what nesting it might suggest.
