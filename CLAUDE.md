# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`musics` is a Clojure DSL for writing music as text, parsed into a domain model,
and played back as MIDI in real time (Gervill, or Fluidsynth via a virtual
ALSA MIDI port) or rendered to a MIDI file. It is used through **mu**, the
musical REPL (`lein mu`, `src/musics/mu.clj`), and the **GUI** beside it
(mu's `gui`); there is no Clojure REPL API.

### Shape of the system

Three tiers, grouped by responsibility and dependency direction, not
by any enforced boundary — no protocol, interface, or seam separates
them at runtime. All three read `musics.repo`'s registry directly,
whenever they want; there is no contract a lower tier owes an upper
one beyond "the data has this shape." "Tier" here means "what this
code is *for*," not "what this code can't reach." The top tier reaches
back into the other two rather than data only ever flowing forward:

1. **Material** — `musics.ebnf` (grammar) → `walker.clj`
   (walk) → `musics.domain`/`musics.repo` (the flat,
   id-addressed store). Produces durable, addressable content: what
   the music *is*, parsed once at authoring/commit time.
2. **Sound** — `musics.events` (a Form's performance as a lazy stream
   of timed events) + `musics.engine` (one sender thread reading those
   streams a lookahead ahead of the clock) + `musics.domain.resolve`
   (context sampling, actualization) + `musics.midi.live`/
   `musics.midi.file` (MIDI dispatch). Turns committed material into real-time or rendered
   sound.
3. **The playground** — `play`'s own mini-language (`musics.engine`,
   mu's play words) + `musics.wall` (per-voice
   algorithms). Sits *above* the other two, not between them: it
   reaches into the repo to select already-committed material, and
   into the engine to spawn voices and assign algorithms, for one
   particular performance rather than describing the music itself.

`musics.repo` (the flat store) is shared plumbing underneath all
three, not a tier of its own — and it's the same global, mutable
registry every tier reads from directly (`musics.registries`'s
`^:dynamic` atoms; see "Repo state" below), not something tier 2/3
access through an abstraction tier 1 could change without touching
both callers. That's a deliberate simplicity tradeoff for a
single-user REPL tool, the same one already reasoned through for
global-vs-instance state generally (`doc/md/audits/review.txt`, point 1) — not an
oversight this grouping is meant to paper over. What the grouping
*does* buy: it names where a given piece of code belongs and which
direction its dependencies run (tier 3 requires tiers 1/2; neither
lower tier requires tier 3), which is genuinely useful for finding
your way around the codebase, just not a claim that the tiers could
be swapped out or evolved independently of each other. Four satellite
capabilities feed material *into* tier 1 rather than belonging to any
tier themselves: `musics.input.midi`/`musics.input.midi-record` (capture a live
performance, emit musics text), `musics.input.lilypond-import` (convert real
LilyPond text), `musics.input.abc-import` (convert real ABC notation text),
and `musics.input.guido-import` (convert real GUIDO Music Notation text). The
GUI (`gui` in mu) wraps tier 3 for live use, plus one satellite
directly (its Record MIDI panel).

Tiers 1 and 3 share one *concept* — sequential-vs-parallel grouping —
but spell it differently on each side, and always have: tier 1
(`.mus` text) writes `[ ]` sequential / `{ }` parallel / `^{ }` a named
context/envelope definition; tier 3 (a `play` call) writes `[]`
sequential / `#{}` *or* `(par ...)` parallel, `#{}` still the shorter
everyday spelling there, `par` only for the one case `#{}` structurally
can't express (see `doc/md/decisions.md`'s Wave 7 entry, and its
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
`doc/md/decisions.md` instead, as a short, standalone entry — check there
before re-proposing something that looks like an obvious improvement.
This is a going-forward convention, not a retroactive one: the "Repo
state" section right below, and other historical asides elsewhere in
this file, are not being rewritten under this policy on their own —
they get brought in line with it if and when whatever they describe is
next touched anyway, not as a dedicated pass.

## Repo state — read this first

**The flat-model migration is complete, and a live signaling layer has
been built on top of it since.** In its current shape: a single, flat,
id-addressed `repo` map (`musics.domain`, no parent pointers)
sits under `musics.repo`, itself just as flat — `id -> node`, no history,
no tx-numbering, only ever the CURRENT value under each id; `musics.conductor`
bridges structural boundaries (section enter/exit, bar crossings,
author-placed `|`/`||`/`|||`/`||||` marks) to named, schedulable
actions; every voice carries its own material/`:algo`/bar count/clock
in its own `musics.events` stream, addressed by its own real path
(`:TA`, `:TB`, ...) — its material a snapshot of the repo taken when
it starts, so a later commit never glitches a voice already
mid-performance; `musics.engine` performs every voice's stream from one
sender thread, a lookahead (100 ms) ahead;
`musics.wall` gives a composer pluggable, hot-swappable per-voice
playback algorithms; and `musics.ebnf` itself has settled on a
GUIDO-flavored bracket/accidental scheme — `[ ]` sequential / `{ }`
parallel / `^{ }` a named context/envelope definition / `'[ ]` data,
and GUIDO-only accidental symbols (`#`/`##`/`&`/`&&`/`n`, no Dutch/
English letter suffixes or LilyPond's own `b`/`bb` anymore) — separate
from, and no longer unified with, the `play` mini-language's own
`[]`/`#{}`/`(par ...)` vocabulary on the Clojure-arg side (see "Shape
of the system" above).

See `doc/md/decisions.md` for the dated history of how each of these
arrived (search for "Wave" there for the seven stages the grammar/
play-mini-language convergence went through, its 2026-09-17 entries for
how `musics.repo` itself went from tx-versioned history down to a flat
map, and its 2026-09-19 entry for the later GUIDO-flavored pass that
moved tier 1's own bracket scheme again, away from that convergence) —
this file describes the system as it stands today, not how it got
here.

If you find something that still assumes the old (pre-flat, pre-
`musics.repo`, pre-GUIDO-flavored-`musics.ebnf`, or tx-versioned-
`musics.repo`) model exists, that's stale — update or remove it rather
than working around it.

## Commands

Leiningen project (`project.clj`), Clojure 1.12, on JDK 25: `:jvm-opts`
runs generational ZGC (no GC pauses holding up the sender thread),
compact object headers (JDK 25 only — JDK 21 refuses to start) and
native access for JavaFX, which is pinned to 25.0.4 over cljfx's own
17.0.2. Dependencies:
`instaparse` (parsing), `org.clojure/core.async` (MIDI input),
`cljfx` (the GUI), `overtone/midi-clj` (MIDI I/O), and
`org.clojure/core.logic` (questions to the algo registry,
`musics.algo.logic.tree`, and species counterpoint, `musics.algo.logic.counterpoint`). A Factor-style
hosted REPL language (musics.lang) lives on the `concat` branch / the
`frepl-standby` tag, not here — see `doc/md/decisions.md`, 2026-09-28.

```bash
lein mu                 # mu, the musical REPL -- with the GUI (gui), the interface
lein mu --version       # mu 0.3.<merges>.<commits>: develop's merges into main
                        # (first parent) and its commits, counted by git
                        # (musics.version); the banner shows it too
scripts/repl-start.sh   # a headless nREPL on 7888 unless one is running, for
                        # development checks only (no Clojure REPL API)
                        # (run by the SessionStart hook in .claude/settings.json)
scripts/nrepl.py '(+ 1 2)'          # evaluate in it (port from .nrepl-port);
scripts/nrepl.py -n musics.assist '…' # -n for a namespace, code on stdin works too
lein test               # run the full test suite (test/ dir)
lein lint               # clj-kondo over src/, test/ and dev/ (no cache written;
                        # the editors' on-save linting is off for this project)
lein verify             # lint, then the full test suite
lein test musics.input.reader.command-walk-test         # run a single test namespace
lein test :only musics.input.reader.command-walk-test/duration-ratio-scales-and-is-inherited   # single test var
scripts/docs.sh         # render README + doc/md/*.md + CLAUDE.md to styled
                         # HTML in doc/html/ and PDF in doc/pdf/ (both
                         # git-ignored; the .md stays the source, except
                         # doc/html/algo-cookbook.html, which is generated
                         # and kept) -- open doc/html/index.html
lein test :parsing      # just one architectural layer -- :parsing/:domain/
                         # :engine/:repl/:algo (test-selectors in
                         # project.clj, grouped per this file's own module
                         # boundaries -- :algo is musics/algo/'s own generative-
                         # material tree, split out from :domain since it's
                         # a different layer, not musics.domain.*/common.*
                         # (the real domain model, which stayed :domain)
```

Audio plays through Gervill, the JVM's own synthesizer, by default
(mu's `gervill`, nothing to set up); Fluidsynth (`fluidsynth`)
needs qsynth and a virtual MIDI port — see `doc/md/setup.md` and
`scripts/setup.sh` / `scripts/reconnect.sh`. Neither is needed to parse
text into the domain model or run tests.

## Architecture

### Pipeline (current)

```
text (a mu input, a .mus file, the GUI editor's text)
  ├─ instaparse (musics.ebnf + mu.ebnf, start MuInput) → raw parse tree
  │    (no text-level pre-processing at all -- comments and variables
  │    are both real grammar constructs, Comment/VarRef, so instaparse
  │    always parses exactly what was written, which is what makes a
  │    parse error's line/column always match the original text --
  │    see doc/md/decisions.md's Wave 3 entry)
  ├─ musics.mu.eval: the literals read up to each :=, then that part
  │    runs on the stack
  │    └─ walker/walk-each (musics.mu.value/read-literals) → a value per
  │         literal (uses builder for the push/pop container stack; id
  │         assignment is lazy -- an auto-id slot is spent only at pop
  │         time, and only if the source gave no name)
  ├─ musics.mu.material/store-all! → the music stored: new/changed ids
  │    land in the flat store in one atomic swap! (R! at the prompt;
  │    musics.mu.store/read! stores what a file or the editor leaves)
  └─ musics.engine/play           → one musics.events stream per top-level
       │                           voice, walked lazily from a snapshot of
       │                           the repo taken when it starts
       ├─ musics.domain.resolve/resolve-event (per leaf, as the stream
       │    reaches it) → timed MidiEvent maps
       └─ musics.engine's sender thread: reads each stream up to 100 ms
            ahead of now, queues note-on/off, conductor signals and
            voice start/end by time, performs each when due
            → musics.conductor/signal! (per section/bar/mark boundary,
              :voice carried opaquely) → registered actions
```

There is no Clojure REPL API: the program is used through **mu**
(`lein mu`) and the **GUI** (`gui` in mu, `musics.gui.core/launch!`); see
`doc/md/decisions.md`, 2026-10-23. `musics.registries/session` holds the
auto-id counters (`{:auto-ids {...}}`) — `musics.repo` is the actual store
(see below). `musics.mu.store/read!` runs text as mu input on a stack of
its own and stores the music it leaves, against whatever is *currently
committed*, visible the instant the call returns (mu's `load`, the GUI's
Editor and Record MIDI, `.ly`/`.abc`/`.gmn` converted first). Parts are
addressed by keyword id thereafter (`musics.repo/lookup`,
`musics.domain.resolve/ctx-value` -- keyword/string/map all accepted);
`musics.domain.resolve/ctx` (mu's `ctx`) prints a part's own context chain
(every ancestor's own authored envelope points, nearest first, `:ROOT`
excluded).

`lein mu` runs **mu**, the musical REPL (`musics.mu`, design in
`doc/md/mu-repl.md`): a stack-based, Forth-like REPL in which musics text
is first-class -- `[ ]` is a `:SEQ`, `{ }` a `:PAR`, every input parsed
by instaparse through `resources/musics/mu.ebnf` appended to
`musics.ebnf` (`musics.mu.grammar`), so a literal means in mu what it
means in a `.mus` file. A literal becomes a value without entering the
repo (`musics.mu.value`, all literals of one input walked together so
durations and relative pitch carry over); `R!` stores in the repo, `R@` fetches,
`play` plays a value without storing it. A bare symbol acts on each
element (`+` transposes every note, writing the moved key, `!K:`, at
the start of the line), the same symbol in brackets on
the whole line (`[*]` n times, `[/]` parts, `[+]` join, `[2]` `[3]` … the top n
one after another; `{2}` `{3}` … side by side, `{+}` two parallels'
parts in one, `[%]` rotate now (`rotating`: as it plays, `rotate n [ ]`), `[?]` shuffle as it plays (`shuffling`, `shuffle [ ]`), `[~]` loop, the row forms `[R]`
`[I]` `[RI]` (exactly `[R]`; `[ R ]` is a rest), `[M]` inversion around
the mean, `[:]` repeat signs (`repeat`, `times` and `[*]` count
plays, as LilyPond does), `[!]`
written out as it plays, `[||]` barred by the meter, `[|]` one of
several each time it plays (`choose [ ]`), `[\]` them in turn
(`alternate [ ]`), `[[]]` split by it
into one line per measure). Selection takes a
quotation as its test (`'( pitch 65 lt ) filter`, `remove`, `mute` --
the notes it keeps become rests of their length), `map` runs one on
each element, `within` on a chunk (`x 1 4 '( [R] ) within`, start
up to end, put back where it came from; `slice`/`splice` are its
halves), and the leaf readers read one note (`pitch`, `duration`, `volume`,
...) while their plurals (`pitches`, `durations`, ...) give every
note's as a stream, a repeat or loop around the line kept around the
values (`[c d] [~] pitches` is `'[60 62] [~]`; two loops zip into a
loop as long as they take to line up), so `x durations x pitches zip`
is `x` again; above a `|` mark `zip` takes every part of a note
(`| d p :volume v :articulation a zip`, `musics.mu.material/zip-parts`),
each stepping once per note, a rest's values unused; an unnamed line
among them is written on, keeping its form, a part given replacing its
own (`x volume/walk zip`), and without durations and pitches it gives a
bundle, the parts as columns, each as long as it is (`'[:pitch '[60
62 64] :volume '[90 70]]`), which zip takes again as its parts and
which can't sound. What changes over time is a context, not a note's
part: `>ctx` (`musics.mu.material/control->ctx`) turns a control above a
`|` -- `:duration` and one or more context keys (`:volume`, `:Tempo`,
`:pan`, `:Key` …), each a stream, and an optional `:curve` (`step` `lin`
`smooth` `in` `out`) -- into a `^{ }` of points at their times (`|
:duration '[1/1 ./. 2/1] :volume '[50 70 90] >ctx` is `^{ !volume:50
!volume:70@1 !volume:90@2 }`), the longest finite column setting how
many steps, the shorter ones cycling; `with` lays it under a line. The algos are words by what they deliver
(`musics.mu.algos`, generated from the registry): `duration/euclid`,
`pitch/lorenz`, `duration/emi`, `parallel/drums` -- a line taken apart
into the algo's inputs (a repeat or loop around them kept around the
result), an `@^{ k:5 }` on top as its own settings -- an algo's settings
are a value of their own, `:TCONTEXT`, apart from the music's `^{ }`,
which an algo word refuses -- then a live voice's (`set`), then the
head of the recipe it runs in (the `@^{ }` a quotation opens with,
`musics.mu.eval/*head*`, which reaches every word in it), then the
defaults; only a key or meter param reads the music (the line a live
voice plays in, else `:ROOT`'s); `seed:7` among them runs it on that
seed, so two words with one seed read one run -- and `zip` puts such a stream back
onto a line, a value per note. Instruments and drums stay apart: `instruments`/
`:instrument` are programs (a drum has none), `drums`/`:drum`
a drum's key of the kit, `as-drums` turns notes into drums; `!kit:brush`
(`:kit`, the drum channel's program, by name from `musics.common.
music-data/drum-kits`) picks the kit, sent by the engine and `render`
when it changes. Playback: `>`
now, stopping what plays; `>>` in its place at the next boundary
(`x | >>` at the next `|`); `>+` joining now, `>>+` at the next
boundary; `stop` (Alt+.), `pause` (Alt+,; again resumes). Named music
is a voice of that name (`[bass: c d] >`, a loop of it too): playing it
replaces only that voice (`>` now, `>>` at the end of its own pass), the
others going on (`musics.engine/play-at!`); unnamed music stops
everything or joins under an engine id. `voices` (`<<`), `:bass stop`,
`:bass playing` (`:bass <`), `:bass hush`, `:bass solo`; an index
addresses a voice too, by mean pitch (1 the lowest with pitch .. n, the
drum voices 0, -1 … by lowest drum key: `musics.mu.material/voice-id`),
and a voice's id or index on top of a play word is the voice to play as
(`3 < 12 + 3 >>`) (`engine/stop-voice!`,
`hush!`, `solo!`: a hushed voice keeps its place, sounding nothing). Every sign
has a named twin, a name Clojure could carry too (`[R]` `retrograde`,
`[~]` `cycle`, `>>` `play-next`, `[*]` `times`, `[H]` `head`, `+` `up`, `*` `stretch`, `**` `spread` -- a value's elements onto the stack, `[/]`'s parts too, ...). Printing is isomorphic to
reading (`musics.mu.print`). `musics.mu.eval` runs the stack, words in
namespaces (late binding), `: name ( a -- b ) … ;` with `$a` locals and
`'( … )` quotations, `( … )` a group (run now, what it leaves
gathered into a line, `| … seq`). Most words are plain Clojure
functions, one namespace per mu namespace: `musics.kernel` (numbers,
comparison, the repo, help, files), `musics.tool`, `musics.music`,
`musics.play` and `musics.rate` (and any namespace mu's `require`
loads while it runs, `"musics.algo.random" 'r require`, a `.clj` file
too) -- `musics.mu.library` registers each
public function under its name, a trailing `!` left off, and its
`:mu :names`, signs like `[R]` and `+`, and
`musics.mu.eval` runs such a `{:var v}` word by taking its arguments
from the stack, music first (the longest arity whose extra arguments
pass their `:mu :optional` test), pushing the one value it returns --
nothing for a `!` function -- and naming an error by the word; it
calls the var, so a redefinition changes the word everywhere. The words that read the stack's shape stay stack
words: the stack, quotations, `:=` and printing the stack, and the play
words with their optional context, mark and voice (`>`, `>>`, …) in
`musics.mu.kernel`; `split-at` (two values), `seq` `par` `{n}` `[n]`
`<>` `volta` `vary` `[|]` `[\]` in `musics.mu.tools`; the algo words,
generated from the registry (`musics.mu.algos`). `musics.mu.material`
holds what they all share. The prompt is `mu<n>` (the
stack's depth); an unfinished input continues (JLine, decided by the
grammar); `bye` or Ctrl+D leaves. An input's literals are read up to
each `:=`, just before that part runs, so `'m [c d] := [\m e]` works in
one input or file. At the top level a text command (`transpose c d ( … )`,
`R ( … )`, `grace …`) is refused (`TopCommand`), pointing at the line it
belongs in or the postfix word. `"x.edn" save-session` saves what is
stored and mu's words and bindings, `restore-session` puts them back in
place of mu's own. `"x.mus" load` reads a file (`.ly`,
`.abc`, `.gmn` converted), `x "x.mid" render` writes a MIDI file.
Sounds are rated as you hear them (`doc/md/feedback.md`): `like`/`meh`/
`dislike` or Alt+l Alt+k Alt+j at the prompt (`musics.mu.feedback`), `audition`
plays a line's sounds one by one in the background, and
`musics.feedback` appends each rating (the sound as mu prints it, the
synth and soundfont) to `~/.mu-feedback.edn` and summarizes them.

### Session, the repo, and playback

`musics.repo` (`src/musics/repo.clj`) is an id-addressed, flat node store:
every id lives under `registry` as `id -> node`, always just the CURRENT
value — no history retained, once a commit lands the old value under that
id is simply gone. Two ways to write:

- **`commit-node!`** — immediate single-node commit.
- **`commit-many!`** — several ids in one atomic `swap!`/`merge`. This is
  what `musics.mu.material/store-all!` uses — a file's `[a: ...] [b: ...]`
  is committed together, visible the instant the read returns.
- Reading: **`current`** (one id's value, or `nil`) and **`registry`** —
  the live `{id -> node}` atom itself, what a brand-new voice's
  snapshot is taken from. `registry` is a thin FUNCTION, not a bare
  var alias, specifically so it still re-resolves
  `musics.registries/*repo-registry*`'s CURRENT dynamic binding at the
  moment it's called (a bare `(def registry reg/*repo-registry*)` would
  instead freeze onto whatever the ROOT binding was at namespace-load
  time, silently ignoring a test's own `binding`).

There's no history to pin against — reading text and every inspection fn
(`musics.repo/lookup`/`ids`/`children`/`usages`, `musics.domain.resolve/
ctx`/`ctx-value`/`locate`, `musics.domain/describe`/`print-structure`)
always reads whatever's committed right now, with no `tx` argument to accept. A voice is the one
thing that still needs isolation from LATER edits, though, and gets it a
different way:

- **A voice's snapshot** — the registry as it was the moment `(play
  ...)` started the voice, held in its own `musics.events` stream state
  (`:repo`), inherited by every branch it forks. **Committing never
  moves it.** A brand-new voice always starts from whatever's current,
  automatically — but an already-running voice keeps its snapshot until
  `(schedule-tx! id phase)` (see below) moves it at a chosen boundary.
  This is deliberate: it's what lets you prepare an edit mid-performance
  without it glitching whatever's currently sounding — and, since the
  snapshot is per-voice, without one part's cutover glitching a
  *different*, still-playing part either (see `doc/md/decisions.md`'s Wave
  4 and 2026-09-17 entries). A container's children are looked up when
  the voice enters it, so a cutover reaches material the voice reads
  after the boundary — typically the next pass of a loop or the next
  item of a `[...]` play arg.

`musics.persist`'s `write`/`load` (the GUI's Persistence window)
persist/replace whatever's currently committed (via `musics.repo/seed!`),
not the performance layered on top of it (a voice's `:algo` assignment
can transform pitch/duration wholesale). `musics.mu.store/reset-all!`
(the GUI's Reset) wipes `musics.repo` entirely and re-bootstraps a fresh
`:ROOT`. `persist-session`/`restore-session` (mu's `"x.edn"
save-session`/`restore-session`, the GUI's Persist/Restore Session)
are the fuller pair for that — same repo+auto-ids round-trip as
`write`/`load`, plus mu's own state as mu text, `:mu` in the file
(`musics.mu.session`: the namespaces required and in use, the words of
`: … ;`, each literal written as its value prints so it reads alone as
it read in its input, and the bindings of `:=` whose value reads back
as itself -- one holding a word's locals is skipped, named; not the
stack), which restoring runs after the repo in place of mu's words and
bindings (a file without `:mu` leaves them). Nothing of what plays is
kept -- a voice's algo, its position, the conductor's pending cues: a
performance isn't a document (`doc/md/decisions.md`, 2026-10-30).

### Conductor: signals and scheduled actions

`musics.conductor` (`src/musics/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `musics.engine` depends
on it (a plain synchronous call, `conductor/signal!`, from the sender
thread, as each `:section`/`:bar`/`:mark` event of a stream comes due);
`musics.conductor` depends on nothing else at all, not even `musics.repo` —
a fully generic dispatcher, deliberately one-way (see
`doc/md/decisions.md`'s Wave 4 entry for why `schedule-tx!` itself lives
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
    markers *at that same strength*; `(schedule! [:mark count] :enter …)`
    waits for the next mark of that strength, whatever its `n`. Zero duration on its own; purely an
    extra cue layered on top of the automatic `:section`/`:bar` signals.
- Every signal carries `:at`, when it is due (`System/nanoTime`), so
  an action can start material exactly there:
  `musics.engine/play-from!` replaces what plays with a voice whose
  `:t` 0 is that moment (or, joining, adds one) — mu's `>>`/`>>+`
  (`musics.mu.material/stage!`).
- **`musics.engine/schedule-tx!`** — the primary use case: `(schedule-tx!
  id phase)` moves every voice that crosses `[id phase]` onto whatever
  is committed at that moment, each at its own crossing and at most once
  per voice; voices that never cross it are untouched. It arms a
  non-consuming entry in the conductor's repeating table (cancel with
  `unschedule-repeating!`); the engine's `cutover` hook, which
  `musics.events` calls at every boundary, checks that table and swaps
  the voice's snapshot. The decision is made as the boundary is
  computed — a lookahead before it sounds — so one armed later than
  that catches the voice's next crossing (see `doc/md/decisions.md`,
  2026-09-30).

### Wall: per-voice playback algorithms

See `doc/md/algorithms.md` for the practical guide; this section is the
mechanism's architecture reference.

`musics.wall` (`src/musics/wall.clj`) is one registry, `*algo-registry*`:
name -> `{:fn f :doc doc ...}`. A wall fn is seq-in/seq-out:
`(nodes ctx-chain voice) -> nodes'`, called identically regardless of
granularity — `musics.events`' container branch calls it once, on the
WHOLE sibling list, before the children are walked or ornaments
expanded; its leaf/rest/drum branch calls it again with a singleton of
each node, so a fn must pass through what it already produced (mark
it, as `musics.algo.tree.live` does). What fills the registry is
`musics.algo.tree/live!` (a name bound to a tree + a tctx — see "Simple
composition: `musics.algo.tree`" below); `build-algo!` stores a hand-written
wall fn directly. `musics.wall/registered` surfaces the full entry.

**Voice paths, not slot numbers**: every voice's own registry key
(`musics.engine`'s `:voices` atom) is a vector, root-first, one segment
per level of forking — the same path also addresses that voice's own
`:algo-prepared` entry, if any (see below). A voice's own `:algo` is a
plain, IMMUTABLE value in its stream state, set when the voice starts
or forks and never reassigned — `musics.events/apply-wall` resolves it
via `musics.wall/algo` FRESH at every node (never cached), so
hot-swapping works exactly one way: re-registering the
SAME `name`'s own entry in `*algo-registry*` (a change to its tctx,
`musics.algo.tree/retree!`, or `build-algo!` again) — every voice whose own `:algo` already points
at `name` picks up the change on the next node the sender reads (a
lookahead ahead of hearing it), with nothing on the voice itself ever
touched.

`assign-algo!`/`algo-assignments` (`musics.engine`) are a SEPARATE, narrower
mechanism: `:algo-prepared`, `path -> name`, consulted ONLY when a
top-level voice starts (`start-voice!`), and only when that call's
own `:algo` argument is `nil`. `assign-algo!` never reaches an
already-live voice — it only affects a mint that hasn't happened yet
(preparing a track before you start it, the GUI's Wall window). See `doc/md/decisions.md` for why a
voice's own `:algo` is immutable once minted rather than a live,
externally-reassignable table.

**Mean-pitch-ranked `:PAR` children**: every fork — a real repo `:PAR`
container's children (`musics.events/walk-par`), or a `#{...}` play-arg
group handed to `play`/`play-add`/etc. (`walk-form-par`, and
`musics.engine/mint!` for a
bare top-level `#{}` — see below) — labels its own children
`:TA`/`:TB`/`...` by ASCENDING MEAN PITCH, lowest pitch getting the
lowest id ("lowest voice lands in slot 0", the mixing-desk convention
this project has always used for `:PAR` ordering). `rank-segments`
(`musics.compose`) backs `walk-par`/`walk-form-par`; `mint!` inlines the
equivalent sort itself, since it also has to decide, per branch, whether
to mint a real voice or recurse (see below) — a real container's mean
pitch is `musics.domain/mean-pitch`, an O(1) read off
`:pitch-sum`/`:pitch-n` baked onto every container at parse time
(`builder/pop-container`, alongside duration); a play-arg
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
`musics.compose/par`), or `[Form :algo Name]` (exactly one Form,
optionally tagged with a algos-registered name or `nil`). The
collection type alone is the tag — vector always `:seq`, set (or
`par`) always `:par`, no guessing (see `doc/md/decisions.md`'s Wave 6
entry for why). This is Clojure data, a separate vocabulary from
`musics.ebnf`'s own text brackets (`[ ]` sequential, `{ }` parallel —
see "Grammar" below); the two once shared one spelling, and `#{ }`
was a text-grammar bracket for a while, but the text grammar has since
moved on while the play args kept `#{}` (see `doc/md/decisions.md`'s Wave
6/7 and 2026-09-19 entries).
`musics.compose/sq`'s own `{:parallel? bool}` seq
metadata is untouched by this and still wins FIRST in `form-tag+items` —
sq's output is always a plain vector, never a set, so without that
metadata check winning first a genuinely parallel container would
silently play back sequentially once flattened through `sq`. Context-ref
peeling differs by shape too: a `[]` group still peels only a *leading
run* (order matters, same as always); a `#{}` group has no "leading" to
speak of, so every item resolving to a `:CONTEXT` is pulled out
regardless of position (`split-contexts-unordered`). A peeled context
is an **override**: it comes first in the chain of everything in the
group, in front of the music's own contexts and each note's baked
ones, so `(play [:loud :verse])` plays `:loud`'s settings over
`:verse`'s own (`musics.compose/over?`/`override`/`behind-overrides`:
`build-chain`, a group's fresh context and
`musics.domain.resolve/chain-links` all insert behind the overrides;
doc/md/decisions.md, 2026-10-15). mu's play words with a context on
top (`x ^{ !mf } >`) build the same `[ctx x]` form.
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
on to more material afterward (`musics.events/walk-form`'s tagged branch,
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
Name)` — both `musics.engine` fns (`play-connected`/`play-add-connected`
connect first, as the GUI does),
neither accepting several top-level forms implicitly sequenced anymore
(`(play :verse1 :verse2)` is now `(play [:verse1 :verse2])`, matching
the same one-Form discipline every nested level already has —
`split-call-args` parses the call's own `& args` against this same
`:algo`-at-a-fixed-position discipline `tagged-form?` uses one level
down). `mint!` recursively starts a real, addressable top-level
voice (`start-voice!`, with a free short track id —
`next-track-id`/`track-ids`, `:TA`..`:TZ`) for every part of
`Form` that isn't itself an immediate `#{}` — a `#{}` branch whose own
content is IMMEDIATELY just another `#{}`, with nothing else of its own
to play, never gets an intermediate wrapping voice for that fact alone;
it recurses straight into its own children instead, which pull ids from
the exact same shared, occupancy-checked pool the outer level does, not
an independent range — "every voice/track gets an id, not subparts."
The return value mirrors this exactly: a single id for a plain Form, or
(recursively) a `#{}` of ids for a `#{}` Form, matching wherever `#{}`
was actually written — `(play #{:melody :bass})` -> `#{:TA :TB}`,
`(play #{:melody #{:a :b}})` -> `#{:TA #{:TB :TC}}` — every entry a
real, directly usable top-level path on its own, no reconstruction
needed. Both still return straight-back-into-`voice-at`/
`play-change`/`play-add`-usable ids/paths.
`play` flushes EVERYTHING first, same as it always has — a solo call
deterministically lands on `:TA`, since nothing else survives the
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
(`musics.compose`'s fully synchronous, `*engine*`-free preview of
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
both fine via `par` — see `musics.compose/par`. `par`'s own Form is
an ordinary vector (never restricted on duplicate values) tagged
`:parallel?` in its own metadata, the exact mechanism `sq` already uses
to mark an extracted `:PAR` container's own children — not a new
mechanism, just exposed as a constructor rather than only ever reached
by extracting an existing container. `par-form?` (`musics.compose`'s
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

### Simple composition: `musics.algo.tree`

`musics.algo.tree` (`src/musics/algo/tree.clj`) is the one way to combine this
project's algorithms and to play them (see `doc/md/decisions.md`,
2026-09-27/28). Two separate things: a **tree** (an immutable value —
what is computed) and a **tctx** (its settings, `{:params-atom a}`
holding values by name, also the GUI's model):

```clojure
(require '[musics.algo.tree :as t] '[musics.algo.tree.lib :refer :all])
(def riff (zip (pulses->durations euclid) (cycle> scale)))   ; prints #node (zip (pulses->durations (euclid)) ...)
(def tctx (t/tctx riff))                          ; {:params-atom (atom {})}, the specs as its meta
(t/setp! tctx :k 5)                           ; = (swap! (:params-atom tctx) assoc "k" 5), validated
(t/run riff tctx)                                  ; or (t/run riff {:k 5}), a plain map
(t/run riff [local tctx] [music-ctx])              ; sources nearest first; key/meter from the music
```

**Algorithms describe themselves.** An algorithm is an ordinary `defn`
whose attr-map carries `:algo {:short :in :out :params}`; nothing else
about it changes. `musics.algo.tree.registry/register!` introspects the var:
`:arglists` gives param names and order (keyword args' `:or` defaults
too), `:doc` the description; the first `(count :in)` args are children
(or the args `:children` names), the rest params; a multi-arity fn names
its `:arity`. A fn yielding ONE value per call becomes a sequence source
through metadata alone: `:repeat :len` calls it `:len` times (the
samplers: `normal`, `triangular`, `int-range`, ...), `:pull {:via :value
:args [..]}` calls it once for a generator and pulls `:len` values (the
closures: `walk` (`:boundary` clamp, reflect or wrap), `pink` (Voss's 1/f dice), `glide`, `cyclic`, `chain`, `logistic`, `henon`,
`lorenz`); the count is a param like any other. Every param spec must
carry `:type` (`:int :double :ratio :string :keyword :vector :map :fn
:bool :any`) and `:default` (`##NaN` = required — a fitness fn, say), and
a number `:min`/`:max` (`##-Inf`/`##Inf` for an open end); `:choices`
limits a string/keyword — `register!` throws on an incomplete spec. Rule
maps, constraint vectors and fitness fns are params too, so they live in
the tctx like any number. The registry maps short ↔ full names
(`t/algo`, `t/full-name`, `t/short-name`, `t/algos`). `t/defalgo` is
`defn` + register: the raw fn becomes `name*`, `name` the node
constructor. `musics.algo.tree.lib` `expose-ns`es every annotated fn in
`musics.algo.{indispensability,metric}`, `musics/algo/{melodic,random,rhythmic}` and the bridges — about
130 algos, each under a short name (`euclid`, `cantor`, `tala`,
`markov-gen`, `counterpoint`, `normal`, `walk`, `lorenz`, ...; from Dodge &
Jerse's chapter 8, `pink`, `tendency`, `ca` (an elementary cellular
automaton as pulses) and `musics.algo.melodic.serial`: `row` gives pitch
classes as raw numbers, so the bridges serialise any parameter --
`classes->pitches`, `time-points->durations` (Babbitt), `numbers->durations` --
with `row-form`, and `matrix`/`combinatorial`/`prime-form`/`interval-vector`
as plain fns) — and
`defalgo`s the tools -- within one type, any material, marked `>` so
they never shadow `clojure.core` (`cycle>`, `shuffle>`, `take>`,
`map>`/`filter>` with a `:fn` param, `scale>` a range onto another,
`stretch>` durations) -- the helpers (`scale`, `transpose`, `pick`) and
the raw-to-raw steps (`threshold`: numbers → pulses, `layer`, `axis`,
`noise`). Trees end in
leaves (`doc/md/algo-audit.md`, the leaf principle): `musics.algo.bridge`'s
bridges turn raw material into half products, one way only. A type names
one value; a tree carries streams of them, so the tree algos are stream
bridges named in the plural (`pulses->durations`, `strokes->durations`,
`onsets->durations`, `numbers->durations`, `points->durations`,
`degrees->pitches`, `numbers->pitches`, `points->pitches`,
`weights->pulses`, `weights->volumes`, `weights->articulations`,
`pulses->volumes`/`pulses->articulations`: a rhythm's accents, `:accent
:metric` by place in the music's meter or `:agogic` by length;
`numbers->instruments`/`points->instruments`: a number's place in its
range as one of a `:palette`, programs or General MIDI names, all 128
when empty; `leaves->durations`/`leaves->pitches`: music taken apart,
a line under a tree recipe read as the half product a slot wants), each
mapping a singular value bridge where one exists (`degree->pitch`,
`number->pitch`, `number->duration`), with how it works in its
metadata (`:works` -- `:value` and `:shape` are lazy, `:whole` reads a
finite stream first; registry entries carry it, `show-algos` prints it).
Keys are spelled as `!key:` spells them; a number bridge takes its input
range as `:from-lo`/`:from-hi`. `zip` joins a note's parts, two at a
time -- `(zip durs pitches vols)` is `(zip (zip durs pitches) vols)`,
folded when built and printed as written (`:join` in its metadata):
durations and pitches (or drums) into notes, chords and rests (a Rest in
the durations, or a nil pitch, uses the other values unwritten, so
every stream's n-th value is the n-th note's; `pulses->volumes`/
`pulses->articulations` give nil for the leading Rest
`pulses->durations` makes); notes and a part into the notes with it
written on, replacing their own -- an articulation (a name from
`musics.common.music-data/articulations`, the leaf's `:articulation`/
`:dynamic` as `c4->` sets them), a volume (0-100), an instrument (a
MIDI program, a General MIDI name or its short name; a drum has none),
a drum (a note made a drum, a key of the kit or a drum's name) or any
key playback reads per note (`t/overridable`); anything else into a
bundle, `{:type :BUNDLE :parts {part stream}}`, typed by its parts
(`[:bundle :pitch :volume]`, `t/bundle-type`), joined again later. A
child's part is its type; a literal gives durations, then pitches, and
`(as-part :volume x)` (or `:leaf`, `:panning` …) names another. The
composer shows a zip as one node with a `+` for one more child
(`builder/widen`); `parts`/`+part` join leaf streams as parallel parts
(`:part`, which `t/play!` plays at once and `part` picks one of), and
`layer` picks one pulse layer of a `:layer` (`polyrhythm`, `hemiola`,
...) -- named with `:as`, each pick routes its layer into its own part -- written into the leaf's `:overrides`, exactly what
a note's `\name:value` Modifier holds, so `part->mus` writes them back
as `\volume:90`.
`test/musics/algo/catalog_test.clj` holds the line: every public fn in those
trees is an algo or listed with the reason it isn't (data tables, the
RNG engine, single draws, constraint builders), every algo runs with
its own defaults and gives its declared `:out`, and no lib name shadows
`clojure.core`.
`remove>`/`mute>` (with `filter>`) select by a `:fn` test: on a stream
of one material, or on leaves, where `remove>` drops a note (later
ones move earlier) and `mute>` makes it a rest of its duration.

**Algos deliver half products** (`musics.algo.tree.half-products`,
`doc/md/algo-space.md`). Pitch, duration, volume, articulation and
instrument are half products, leaves/lines/parallels products, numbers/pulses/onsets/
points/weights raw material. Every source of raw material is also
registered as each half product a bridge makes of it -- a specialised
algo, derived at load time (`register-all!`, end of `musics.algo.tree.lib`),
named by half product: `:pitch/lorenz`, `:duration/euclid`,
`:volume/walk`, `:articulation/tala`, and `:pitch/walk-degrees` for
number→pitch's second bridge. Its entry carries `:via {:source :bridge
:split}` (`musics.algo.tree.registry/call` runs the source, then the
bridge); its params are the source's, then the bridge's prefixed with
the half product (`:pitch-lo`, `:duration-pulse`), so two half
products of one source never share a bridge setting; a bridge's
`:from-lo`/`:from-hi` default to the range the source gives at its own
defaults (probed over eight seeds, so a random walk's range is where its runs go, not one run's band). Two with the same `:as` and source read one
run of it within a `t/run` -- x as pitch, y as volume of one attractor.
An algo that varies pulses (pulses in, pulses out: `emi`, `mutate`,
`oblique`, `rnn`, `tuplets`, `necklace`, `bracelet`, `crossover`,
`tiling`) is lifted onto durations the same way (`half-products/lift`):
`duration/emi` takes durations, its `:via :lift` lays them on a grid of
`:duration-pulse` (`half-products/durations->pulses`), and `pulses->durations`
reads the result back.
A specialised algo's registry key is its mu word: `:duration/euclid`
is `duration/euclid` (`musics.mu.algos`).
`musics.algo.tree.registry/shelf` places every algo in the catalogue by
what it delivers -- `pitch`/`duration`/`volume`/`articulation`/`leaf`/
`parallel`, `tools`, or `workshop` for anything that takes or gives
raw material (raw sources, bridges, `tilt`, `threshold`, ...); the
builder's pane and `show-algos` group by shelf.

**Trees are checked when built.** A child may be a node, a bare
constructor (`scale` = `(scale)`), a keyword (a param read at run time)
or a literal. Child count and `:in`/`:out` types are checked at once
(`:pulse`/`:weight`/`:pitch`/`:number`/`:duration`/`:onset`/
`:pair`/`:point`/`:layer`/`:part`/`:model`/`:stroke`/`:leaf`/`:index`,
`:any`, and `:same` = the first child's type), errors naming both nodes.
A child of the wrong type first gets the smallest chain of one-input
bridges from its type to the slot's (`musics.algo.logic.tree/bridge`,
from `how`, cached per registry state), so `(zip euclid scale)` is built
as `(zip (pulses->durations (euclid)) (scale))` and prints so; in a
join the bridge goes to the part neither child gives yet, durations then
pitches. The builder places an algo inside its bridges the same way
(`builder/takes?`, `place`), so the composer offers it there.
`(euclid :as :bass)` names an instance.

**Keys are a pure function of the tree** (`t/param-keys`): a param keeps
its bare name unless two different algos read it with different specs
(then `:<short>.<name>`); a named instance's are `:<as>/<name>`. Each is
written under a name -- `"k"`, `"euclid.k"`, `"bass.k"` -- in text
`@^{ k:5 euclid.k:3 bass.k:2 }` (see Grammar).
**The tctx** is its own shape, `{:params-atom a}`, `a` holding only what
is set as plain values under those names; the tree's specs ride on the
atom's metadata (`t/specs`), never the tree itself: one tree can run
against several tctxs, and `(t/fit! tctx tree)` adds another tree's
specs, keeping the values. The atom's validator checks every value
against its spec (a name no param has is refused, but for `seed`), so
even a plain `swap!` can't store a bad one; `setp!` coerces a value as
text writes it first. `t/run`/`t/settings` take `(tree src chain)`:
`src` a tctx, a plain map (by key or by name) or a vector of them
nearest first, then each param's default; in each the most specific
name wins (`bass.k`, then `euclid.k`, then `k`); values are read once
per run, coerced from text (`key:D.major`, a bare name, reads as a
string where a string is wanted) and checked. `chain`, the music's
contexts nearest first (a voice's chain), is read only by a param whose
spec names a music key (`:context :Key`/`:Meter` -- the bridges' key and
meter, so `degrees->pitches`, `points->pitches`, `pulses->volumes` and
their specialised algos), after the sources, so it plays in the music's
`!key:` unless the settings give a `key`. `t/params` is every param's
value, `t/values` what the tctx sets itself, `t/unsetp!` lets a param
fall through again. A live voice (`t/live!`, or `play` with `:algo`)
runs with what its tctx sets, then -- for key and meter -- the chain of
the music it plays in, then the defaults: a generating tree reads the
chain at a voice's first note and again after each change.
`t/describe` prints key/value/range/default/algo/doc; `t/trace`
every node's result (lazy seqs previewed, never walked); an algo that
throws is reported with its node's expression.

**Live** (`musics.algo.tree.live`, reached as `t/live!`/`t/retree!`/`t/stop!`/
`t/play!`): `(t/live! :riff tree tctx)` binds a name to a tree + a tctx in
`musics.wall`'s registry (so `(play :verse :algo :riff)` works too) and
starts an endless voice; the name watches its tctx, so every change
re-registers it and is heard on the next note. `retree!` swaps the tree,
`fit!`-ing the same tctx. A tree that doesn't read `:nodes` GENERATES:
each note a voice plays becomes the next element of its data, every
voice keeping its own cursor (re-running the tree once per change, at
the same position; a failing run keeps the last good material). A tree
that reads `:nodes` TRANSFORMS the voice's own notes. The GUI's Wall
window shows each live name with a control per param: a slider for a
finite range, a dropdown for `:choices`, a note for a fn, a text field
otherwise. `(t/gui tctx)`/`(t/gui tree)`/`(t/gui tree tctx)`
(`musics.gui.params`) opens just a settings window with the same
controls, no rest of the GUI: the tctx is its model both ways (a control
calls `t/setp!`, a `setp!` moves the control), and given a tree it also previews the result (debounced,
first 32 items of an endless one) with Play once / Live as buttons.
`(t/gui tree)` makes the tctx and returns it. `(t/build-tree)`
(`musics.gui.composer`) composes a tree by drag and
drop and returns `[tree tctx]` (blocking until Finalize, nil when closed):
a canvas showing the tree with its brackets and numbered holes, and a
pane of shelves -> algos on the right (`musics.algo.tree.registry/
shelf`: half products first, the workshop last); what doesn't fit the active slot is dimmed and refused as a
drop target — decided over the whole draft by `musics.algo.logic.tree`, which
types the draft in core.logic, so a hole under a `:same` node (`cycle>`,
`take>`, ...) takes the type that node's own slot wants, and an algo
whose own holes nothing could fill is dimmed too — a drop on a hole fills it, on a node replaces it, and the
active slot moves on to the next hole, so a tree grows root to leaves.
It runs on `musics.algo.tree.builder`, a pure draft model
(`place`/`place-literal`/`remove`/`undo`/`redo`/`select`, `fits?`,
`->tree`, `from-tree`, `render`; Ctrl+Z/Ctrl+Y in the window);
`(t/build-tree tree tctx)` edits an existing tree. In mu (`musics.mu.live`)
a play word given a **recipe** -- a quotation, or a composer tree
(`compose`) -- plays live: `x '( 5 + ) :up >`, or from nothing with a mark
`|` (or nothing) under the recipe, `| '( duration/euclid [c e g] pitches
zip [~] ) :riff >`. Each note the voice reaches is the next note of what
the recipe gives now (`musics.algo.tree.live/bind-run!`, the generator's
cursor per voice); its settings are a tctx per live name, fitted to the
trees the recipe's algo words build (`musics.mu.algos/*live*`, read
first in their chain), so `set`/`unset`/`settings`, `retree` (same
settings, same position), `gui` on a voice and the GUI's Wall window
work on them (`doc/md/decisions.md`, 2026-10-23). A change runs the
recipe again, but an algo word whose inputs and settings are as they
were gives its last result (`*live*`'s `:cache`, by word and
occurrence), so only the words the change reaches run, and the others
keep their random values. `musics.algo.tree.lib/notes->mus` renders generated notes as musics
text, ready to read. `doc/html/algo-cookbook.html` (generated and
verified by `scripts/algo-cookbook.clj`, which runs every recipe;
printed to `doc/pdf/algo-cookbook.pdf`) is the worked guide — 53 recipes plus reference tables
read from the registry;
`.clj-kondo/hooks/defalgo.clj` teaches clj-kondo what `defalgo` defines
(`hooks/core_logic.clj` does the same for core.logic's `run`).
`musics.algo.logic.tree` also answers questions from the
registry's types: `find-algos` (by category, input, output, param),
`how` (smallest trees from one type to another, `:input` for what you
have), `feeds`, `why-not`, `examples` and `surprise` (a random tree
that type-checks) — see `doc/md/algorithms.md`, "Asking the registry".

### Recipes: `musics.recipe`

A tree recipe is an algo tree written concatenatively, as mu is, between
`@(` and `)` -- the algo twin of `'( )`, as `@^{ }` is of `^{ }` -- a
value of mu's (`musics.mu.value/Recipe`, read by `mu.ebnf`'s
`TreeRecipe`, built by `musics.recipe/build` from mu's evaluator):

```
@( riff: ^{ seed:7 } k:3 n:8 euclid len:8 pitch/walk zip )  a generator, named
@^{ k:3 n:8 len:8 } @( euclid pitch/walk zip ) run          its settings given from outside
[c8 d e f g a b c] @( dup duration/emi zip ) run            music from the stack, changed
```

Inside, the words build the tree on a stack of their own: an algo (its
registry name, `euclid`, `pitch/walk`, or a mu word's `shelf/name`)
takes the params (`k:3`) and the `'alias` lying on top of it, then as
many inputs as it has, and pushes its node, bridges put in where a type
doesn't fit (the trees' constructors); `zip` and `par` join the top two,
or everything above a `|`; `dup` `swap` `over` `rot` arrange, a `dup`'d
node one node, read once per run (`:share`, `musics.algo.tree/evaluate`);
data `'[ ]` (`'[ ] [~]` a loop), numbers and strings are leaves; a head
`^{ }` comes first. No music is written inside: what the tree takes from
below its own stack are its inputs (`:in0` …, their count the recipe's
`:arity`), the values under the recipe when it runs, a line taken apart
by the slot's type (`leaves->durations`, `leaves->pitches`). One tree
must be left. As in the music domain, a param is a leaf and an algo a
composite. Built in three phases: **collect** every param into one flat
tctx -- the head's under the names it writes, a node's own under its
specific name (`bass.k`, else `euclid.k`, so it wins over a plain `k`),
every name checked against the specs of the params that read it (a
typo, a value out of range, or two un-aliased nodes of one algo writing
one param are refused); **associate** the tree with that tctx, the
recipe value; **run**, its params resolved by the naming rule -- the
alias's, the algo's, the plain name, the default (`musics.algo.tree`'s
`:at` names). An `@^{ }` under a recipe is merged into a copy of its
tctx at use (`with-settings`), checked the same way. `musics.recipe/result`
is lazy, a `seed` in the settings starting the draws there; mu's `run`
(`musics.mu.tools`) takes the recipe's inputs from the stack and puts a
finite result there (a line, a `{ }`), refusing an endless one; `see`
prints the tree it builds; a play word plays one live -- a generator
from nothing (`| @( … ) :riff >`), one with an input on the music
under it -- the voice pulling the lazy result note by note, its tctx
the voice's settings (`set`, `settings`, `gui`, the Wall), a copy of the
recipe's, so `set` never reaches the recipe; a named recipe plays as the
voice of its name. `'( … )` stays the simple recipe, any mu words run as
written.

A recipe is material of the repo: an id first names it (`@( riff: … )`),
as a part names itself, and `R!` stores it under that name, an id given
or an auto one (`:r1`), as its text -- a `:RECIPE` node, `{:type :RECIPE
:id :riff :text "…"}` (`musics.recipe/node`), not one of `:ROOT`'s
children -- which `R@` rebuilds (`musics.recipe/stored`, by mu's
evaluator), so `write`/`load` keep it as they keep any node. Everything
written inside comes along -- the head, a node's own params, aliases;
what is given at use (`@^{ }`) or set on a live voice is not. A live
voice playing a recipe stored under an id follows it: `R!` of a new one
there (`musics.mu.live/follow!`) plays from the voice's next note, at
its position, its settings the new recipe's.

### Chord progressions and walking bass: `musics.algo.harmonic`

`musics.algo.harmonic.progression` writes roman-
numeral schemes as chord symbols: `prog/blues` holds twelve-bar blues
(`:basic` `:quick-change` `:minor` `:jazz`), `musics.common.music-data/
choruses` the choruses of `doc/md/lilypond-chordmode-chords.md`
(`:blues-chorus`, `:jazz-chorus` -- rhythm changes, 32 bars AABA --
`:jazz-ii-V-I-chorus`), each with the key it's written in as its
default `:tonic`, and `(prog/->mus :jazz
{:tonic "F" :name :blues})` gives `[blues: !M:4/4 F3/1:7 | B&3:7 | ...]`,
roots spelled in the key, a duration only where it changes, a closing
bar on the tonic (`:end`, true; none when the scheme ends on it), `:hit` to strike each chord per beat,
`:repeat n` the choruses as `repeat n [ ] volta [ [turnaround] [tonic] ]`.
`musics.algo.harmonic.walking-bass` walks a quarter-note bass under a
line of chords: the root on each chord's first beat, the beat before
the next an approach to its root (a half step, a scale step or its
fifth), the beats between on the chord's scale, every step at most a
fifth, drawn back towards the middle of its range; `:choruses` new
lines joined end to start, `:end` the last chord held. `wb/->mus id`
reads a part (`form-of`: a line, or one repeat with its volta endings,
walked in the same form) and writes `[bass: !i:acousticBass ...]`;
then `(play #{id :bass})`. In mu the word `walking-bass` ( chords [n] --
bass ) makes it from the line on the stack as it runs, every pass of a
repeat walked anew: with n as long as the chords n times, each walked
anew, the last chord held (`x 4 times x 4 walking-bass {2} >`); without,
an ostinato leading back into its own start, to loop (`x dup
walking-bass {2} [~] >`). Both draw from
`musics.algo.random`, so a seed repeats them.

### Species counterpoint: `musics.algo.logic.counterpoint`

`(cp/counterpoint {:cantus [...] :voices 2-4 :kind 1-5})` writes
counterpoint after Jeppesen's *Kontrapunkt* against a given cantus
prius factus (whole notes): with 3–4 voices the top added part in the
kind, the others in 1st species (`:kinds` per voice), in a church mode
(or major/minor) taken from the cantus' final. Notes carry their
diatonic step (`counterpoint/intervals.clj`), so intervals keep their
quality; the hard rules (`counterpoint/rules.clj`, `violations`) prune
a depth-first core.logic search bar by bar, dissonances judged as
figures (passing, lower neighbour, cambiata, suspension); soft rules
pick the best of several short searches, run in parallel (one per
core, each its own thread). `cp/check` reports the rules
a score breaks, `cp/check-cantus` warns about a cantus, `cp/->mus`
gives musics text spelled in the mode, and `:species` is the tree algo.
`doc/md/counterpoint.md` has the rules, the design and its limits.

### Help: `musics.assist`

`musics.assist/explain` (the GUI's Assist popup) says what holds, what
you can do now and the way to hearing something; `(explain :live)` the
steps to a fact or an action, or why an action can't be taken yet;
`(explain :pulse :pitch)` hands two types to `musics.algo.logic.tree/how`. `musics.assist` works it out
from facts: `actions` is a table of REPL actions (`:needs`, `:gives`,
`:call`, `:var`); the facts are the state read now (`:committed`
`:connected` `:playing` `:paused` `:live`) plus history -- the needs
and gives of the actions in `musics.registries`' activity log (30
entries, cleared by `reset`) that no state shows (`:tree`, `:tctx`,
`:rendered`). core.logic relates them (`achieveo` plans, `missingo`
why-not), shortest plan first, ties to the plan redoing least. Every
action's `:var` calls `musics.registries/log!` (`test/musics/assist_test.clj`
checks the source). The GUI enables, colours and tooltips its transport
buttons from the same facts (`musics.gui.core/action-button`), and colours
the panel opener for the next step. `(show-algos)` lists the algo
registry by category.

### Composing vs. performing: `musics.compose`

`musics.compose` (`src/musics/compose.clj`) holds the play-arg mini-
language's own Form-shape grammar — `tagged-form?`/`split-tag`/
`resolve-form-tag`/`par-form?`/`par`/`form-tag+items`/
`peel-group-contexts`, plus `live-repo`/`build-chain`/
`mean-pitch-rank`/`form-pitch-source`/`rank-segments`/
`top-level-voices` — and the mini-language's two previews. `display`
shows which voice `play` would give which material, as musics text: one
line per top-level voice, labelled as `play` names it, each `{ }`/`#{}`
branch with its own voice label, an `:algo` where it applies, notes
spelled absolutely (`musics.input.reader.leaf-parser/part->mus`) — no time,
nothing transformed (`show-form`/`show-node`). `display-timed` resolves
every note with its onset, nesting a `:PAR` as `{:kind :par :voices
[...]}` (`realize-form`/`realize-node`/`realize-iterator`/etc., all
private) — a second, independent walk of the timing rules that
`musics.events` is tested against. Deliberately engine-free:
nothing here touches `*engine*`, a voice, `core.async`, or MIDI.

This is a **shared toolkit**, not a pipeline stage — `musics.events`'
walk and this ns's own `display`/`display-timed` each walk a Form on
their own, calling INTO these functions at every node/group they
visit; neither hands the other a pre-computed result.

`musics.events` and `musics.engine` require `musics.compose`; `musics.compose`
requires only `musics.domain.*` (and `musics.algo.random` for `play-order`,
`musics.input.reader.leaf-parser`, to spell
notes) — never the engine — so the dependency runs exactly one way.
A purely-functional grammar+preview layer has no business in the
real-time playback engine. See `doc/md/decisions.md` for the full
reasoning behind the split (including why this ISN'T a third tier
alongside Material/Sound/The playground — it's a sub-piece of tier 3,
the part of the play-arg mini-language that's execution-agnostic, not
a new architectural layer).

### Performance as data: `musics.events`

`musics.events/events` (`(events repo form)`; mu's `musics.mu.material/
events` for a value) is what `play` would perform, as a lazy, time-ordered seq of maps: `(events
form)` or `(events form :algo name)`, the same Form `play` takes. Each
carries `:t` (seconds), `:beat` (structural time, exact) and `:path`
(the voice, named as `play` names it), and a `:kind`: `:note`/`:drum`/
`:rest` (a `resolve-event` MidiEvent; a note's `:channel` is left to
the consumer), `:section` (`:id :type :phase`), `:bar` (`:n`, at the
end of the note that crossed), `:mark` (`:count :n`) — the boundaries
the engine signals to `musics.conductor`, here as data.

The walk threads a voice's position as a plain state map (`:repo` its
snapshot, `:path`, `:algo`, `:t`, `:beat`, bar/mark counts) with a
continuation per step, so nothing is walked past what is read: `:count
:infinite` and live generators are fine with `take`/`take-while`. A
`:PAR`/`#{}` merges its branches by `:t` and then continues where the
branch that ends last stopped (`display-timed` follows the same rule).
Wall fns run at the container's children and then at each leaf, so
reading events moves a live `musics.algo.tree` voice's cursor. `:voice` events
(`:phase :start`/`:end`, `:algo`) mark each voice, forked ones included,
beginning and finishing. `voice-events` is one top-level voice's
stream, optionally with a `:cutover` hook `(st id phase) -> st'` called
at every boundary, AFTER that boundary's own event — so a reader that
reads a boundary only when it is due to act on it decides when the hook
runs (`musics.engine/schedule-tx!`). No clock, `core.async`, MIDI or
`*engine*`.

`musics.midi.file/render` writes a form to a `.mid` file through it
(`:until` seconds for endless material, `:seed` for `:humanization`);
mu's `render` (`x "x.mid" render`, seconds for endless music) writes a
value.

### Live playback: `musics.engine`

`musics.engine` plays `musics.events` streams in real time. `play`/
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
  Under `!i:40,73,0` (or a note's own `\i:40,73`) its `:program` is a vector: the note sounds
  once per program at the same moment, each a layer of the voice with
  its own channel (`[path layer]`); `musics.midi.file` renders it the
  same way.
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
- `echo`, an atom holding nil or a fn, is called with each note's MIDI
  event as it sounds, on the sender thread (mu's `echo` word hands it
  to an agent that prints above the prompt).

See `doc/md/decisions.md`, 2026-09-29/30, for why a forward-only stream
and one sender rather than a voice per go-block or a separate scheduler
process.

### MIDI input: midi-through and record-midi

`musics.input.midi` (`src/musics/input/midi.clj`) is the mirror image of
`musics.midi.live` — real-time MIDI INPUT via `overtone/midi-clj`
(already a dependency; `musics.midi.live`'s own device discovery
is also built on it — see that ns's own docstring). `open-midi` does
two things at once, both starting the instant it's
called: forwards every NOTE_ON/NOTE_OFF straight to musics' own
connected output receiver on a fixed channel (`midi-through` — hear a
plugged-in keyboard live, through the same Fluidsynth setup
`musics.engine/connect!` already opened, no second MIDI-out connection of its
own), and puts the same events onto a `core.async` channel
(`musics.input.midi-record` listens on this). `close-midi` stops both.

`musics.input.midi-record`'s `open-record` blocks the calling thread from the
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
public `musics.input.reader.leaf-parser/midi->spelling` (a black key always
spells as a sharp of the letter below, same convention `midi->ref`
already used internally, just also surfacing the accidental that fn
drops since it only ever fed a relative reference point).

The generated text's `!tempo:`/`!instrument:` header has to sit INSIDE
the `[ ]` Sequence, not before it — a bare top-level instruction is
refused (see "Grammar" below).

The GUI's "Record MIDI" panel (`musics/gui/*`) wraps this: Start/
Stop buttons, an instrument field, an editable text area showing the
generated text, a name field, and Write (saves `<name>.mus` to disk
only — no separate stage/commit step). `scripts/setup-midi-in.sh` +
`doc/md/setup.md`'s "MIDI input" section cover the (much lighter than
output's) system setup — a real USB keyboard needs no kernel module,
unlike `snd-virmidi`.

### Algorithm registries: not reachable from music

No line or `^{ }` invokes an algorithm: music text holds music. An
algo tree is written as a mu value of its own, a tree recipe (`@( … )`,
`musics.recipe`, see "Recipes" above), and
played by mu's play words; playback algorithms otherwise live on the
`play`/`musics.wall` side, a tree bound under its own name
(`musics.algo.tree/live!`). See `doc/md/decisions.md` for why an
earlier `@[ ]`/`@{ }` invocation inside music and
`musics/input/algo_registry.clj` were removed.

`musics.algo.common.isorhythm/color-talea` and `musics.algo.common.split/
split-leaf-voice` are two ordinary Clojure functions worth knowing by
name here: `color-talea` combines a color (pitch sequence) and a talea
(duration sequence) into the classic isorhythmic pairing (event `i`'s
pitch is `(nth color (mod i (count color)))`, its duration `(nth talea
(mod i (count talea)))` — the two cycle independently); `split-leaf-voice`
splits a melody into `n` faster, octave-shifted voices, each built from
the previous split so every voice's total duration matches the
original's. Call either directly; in an `musics.algo.tree` tree the same
isorhythm is `zip` over two `cycle>`d streams, `(take> (zip (cycle> talea)
(cycle> color)))`.

### Domain model — flat repo, not a tree of pointers

- **Containers are plain maps**: `{:type :SEQ :id :s1 :context ctx :children [...]}`.
  No atoms inside nodes. `:children` holds a mix of inline leaf values and
  keyword ids that must be resolved against the `repo` map
  (`d/children repo container`). Auto-generated ids are short,
  type-prefixed (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`), assigned by
  `builder/next-auto-id`.
- **Leaves are plain, `:type`-tagged maps**: `Leaf`, `Rest`, `Drum`
  (pitches/duration/articulation/dynamic/modifiers/tied), plus `Pulse`
  (`:duration`/`:value` only — no pitch at all; a duration/value cell for
  pulse-grid-shaped generative material, e.g. `musics.algo.common.pulse/
  grid->pulses`, and reachable directly from text too: `p<Duration>`
  builds one, `PitchLetterRel`'s own long-reserved-but-unwired `p` slot
  in `musics.ebnf` — `value` is always a fixed `1` for now, deliberately;
  see `doc/md/decisions.md` for why). `Iterator` (a real record, deferred
  expansion for `repeat`/tremolo, holding a `:source` container +
  `:params`) is the one exception to "plain map."
- **Transient containers** (`:TRANSPOSE`/`:REVERSE`/`:DECORATED`, i.e.
  `transpose`, the row forms, `shuffle`/`rotate n ( … )` and a grace
  decoration) are notationally
  invisible: `builder/pop-container` splices their `:children`
  straight into the parent and never registers them under an id at all
  -- no separate container survives in the tree. They are bare-word
  commands, LilyPond's names without the backslash (`transpose c d (
  c8 d8 e8 )` or `transpose 5 ( … )` by semitones, `retrograde ( c8 d8
  e8 )`), taking a `Scope` (`( )`) body
  -- not `[ ]` `Sequence`, and not a bare Lisp-call spelling either; see
  the bracket table below. A tuplet is a count before a group, with no
  word: `3:2( c8 d e )`, three in the time of two (`:TUPLET`, also
  transient), every note inside at 2/3 of its written value and keeping
  that factor as `:ratio`, so the printers write the group back; a
  single note can carry its own factor, `c8*2/3`, inherited by later
  notes that omit their own Duration until an explicit new one appears.
  A note value is a power of two, dotted or not (`c3` is refused). See
  `doc/md/decisions.md`, 2026-10-11. The bracket says when: a `( … )`
  body is changed now, while parsing; a `[ … ]` body is a part that
  plays that way each time (`repeat`, `shuffle [ ]`, `rotate n [ ]`).
  The row forms are `retrograde`/`R`, `inversion`/`I` (around the first
  note), `retrograde-inversion`/`RI` and `mirror`/`M` (around the mean),
  with an optional transposition (`I 5 ( … )` is I5); `R` alone, `R1`
  or `R*4` stays a bar's rest. The retrograde turns ties round and
  writes what the body's instructions change into each note as its
  override (the value it has where it's written), so it sounds
  backwards; jumps are refused. `shuffle ( … )` and `rotate n ( … )`
  reorder once, now. Short forms: `TRP` for `transpose`, `SFL` for
  `shuffle`, `ROT` for `rotate` (also mu words of those names); the
  printers write the long names. `transpose` and the row forms reach
  into their whole body (`builder/deep-children!`): a nested line or
  parallel, a referenced part and a repeat's body move or turn round
  too (a parallel keeps its parts' order, reversing each); a part
  referenced from outside the command is changed as a copy under a new
  id, so its other uses keep it as written. The retrograde refuses a repeat
  with `volta` endings (no repeat plays its passes backwards). A drum
  isn't transposed.
  Transience is a walk-time decision (splice, never register), not a
  grammar-level one -- a grace decoration has no dedicated bracket at
  all -- it takes two bare `Element`s directly (`(grace c8 d4)`), so
  there's nothing to distinguish there. They still get their own
  `:context` while being built, though (same as any regular container), so
  an instruction written directly inside one -- a standalone `!f`, or a
  note-suffix dynamic like `c4\f` -- has to go somewhere once that context
  is about to be discarded at pop time: `pop-container` replays every one
  of its envelope points onto the parent's context first (via
  `builder/replay-context!`, the same mechanism `apply-context-ref`
  uses for a `:CONTEXT` reference), each point re-offset by the beat the
  transient block started at. The result takes effect from that beat and
  sticks forward -- past the end of the transient block, into whatever
  comes next in the enclosing sequence -- exactly as if the wrapping
  command had never been there at all, consistent with its children
  already being spliced flat. Contrast a genuine nested `Sequence`, which
  gets its own real, retained context and does *not* leak a dynamic set
  inside it to a sibling outside its brackets.
- **Context has no parent pointer** (`musics/domain/context.clj`), for
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
  walker at the moment it's built (`builder/current-context-
  chain`) — every ancestor Context on the stack at that point, paired
  with how far into THAT ancestor's own local timeline this leaf sits
  (`d/duration` of that container as constructed so far, the same
  quantity `duration`/`ctx-append` already use as their own time
  coordinate). This is safe specifically because a leaf, unlike a
  container, is never independently re-referenced by a different path
  (`repeat`'s body is always a real container, never a bare leaf) — so
  "baked once, correct forever" doesn't reintroduce the problem the
  no-parent-pointer design exists to avoid for containers.
  Motivation: `sq` (`musics.compose`) returns a container's bare `:children`
  — none of that container's own `:context` (its `!instrument:`/
  `!tempo:`/`!mf`/etc.) travels with it once extracted, so a leaf played
  standalone (`(play (times 12 (sq :verse)))`) would otherwise resolve
  against whatever minimal `ctx-chain` the *new* top-level play call
  built (often just `[ROOT-ctx]`), silently losing `:verse`'s own values
  entirely. `musics.domain.resolve/chain-links` re-bases each baked
  ancestor by `(structural-time - relative-offset)` at resolve time,
  which reconstructs exactly the entry point that ancestor's own
  container would have had — numerically identical to `build-chain`'s
  own per-container shifting for ordinary playback, and correct for a
  standalone/extracted leaf too. See `doc/md/decisions.md` for why the
  relative-offset subtraction specifically is load-bearing, not a
  simplification skipped for convenience.
  Only the baked ancestors the current walk did NOT pass through go in
  front of the walk's own chain (`chain-links`, matched by
  `:envelopes-atom`, never the baked `:ROOT`), so a referencing
  container's settings (`[fast: !tempo:240 :mot]`) still reach what the
  referenced container doesn't set itself, and a reused container
  leaves its original parent's settings behind. `musics.persist` keeps
  that atom sharing across `write`/`load` (`:ref` per context).
  A leaf built directly (not through the real walker — ornaments'
  expanded sub-leaves, `algo`-registry-generated leaves, `warm-up!`,
  most unit tests) simply has no baked `:ctx-chain`, and
  `chain-links` falls back to whatever `ctx-chain` was threaded in
  externally, exactly as before this mechanism existed — nothing about
  that path changed. `musics.persist`'s freeze/thaw (needed since
  `Context` holds atoms, not directly EDN-readable) was extended the
  same way it already handled a leaf's own `:context`.
- **Envelopes** (`Point`/`Envelope` in `context.clj`) are time-value curves
  with an interpolation type per point (`:fixed :step :lin-up :lin-down
  :smooth :ease-in :ease-out :ease-in-out`); the *left* point's IP governs
  the curve to the next point. `env-reverse` swaps directional IPs
  (up↔down, in↔out) for time-reversed playback.
- **`musics.domain.resolve`** provides `resolve-event` (actualization —
  called by the engine per leaf at fire-time with the current structural
  time, samples tempo/volume from the ctx-chain, and reads frozen leaf
  fields like articulation/pitch/program to build a MIDI-ish event map)
  and `locate` (navigation — walks the repo from a root along an explicit
  path of selectors, threading the ctx-chain the same way a real
  traversal would, for REPL inspection/addressing).
- **`musics.engine`** is the (sole) real-time playback engine: one sender
  thread performing `musics.events` streams (see "Live playback" above).
  `*engine*` is a dynamic var so callers (`play`, `stop!`, `pause!`,
  `resume!`) don't need to thread an engine value around. `play`'s args
  are the play-arg mini-language (see "The play-arg mini-language"
  under "Wall" above). A group's parallel-ness doesn't have to be a set
  literal: `musics.compose/sq` (the one function that turns a
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
  MIDI output goes through `musics.midi.live`'s `Receiver`, passed
  in as the engine's `fs` (`nil` plays silently, for tests).

### Multi-measure rests, pickups, and LilyPond pitch languages

Three real LilyPond-superset gaps, closed together in one pass:

- **`R` (multi-measure rest)** — `MultiRest` in `musics.ebnf`, walked by
  `walker/walk-multi-rest`. With an explicit `Duration`
  (`R1*4`), this is exactly LilyPond's own spelling: the composer picks
  the note-value matching one bar in the current meter, `*n` multiplies
  it, same responsibility a bare `r`'s own duration already carries --
  LilyPond doesn't derive this from the meter automatically either,
  despite the name. With NO duration at all (`R`, or `R*4`), this DSL
  goes one step further and derives one bar's length from whatever
  `Meter` is actually active right there (`musics.domain.context/
  ambient-value` against the full chain, current context included) --
  a genuine extension with no LilyPond equivalent, motivated by a real,
  confirmed transcription bug: `r1` written to mean "rest one bar" in
  3/8 time is actually ~2.67 bars (a bare whole note), not 1.
- **`partial <duration>`** — a new `Instruction` alternative
  (`Partial` in `musics.ebnf`), LilyPond's own literal spelling, not a
  `!`-prefixed Assignment. Walked to a plain `:fixed` value under
  `:Partial`, sampled per leaf in the same batched `c/sample-many` pass
  `:Meter` already rides in (see `musics.domain.resolve/
  common-keys+defaults`). Applied lazily, not by pre-seeding anything
  at voice-creation time: `musics.events/advance-bar` consults a
  per-voice `:partial-pending?` flag (`true` in a top-level voice's
  starting state) and, the FIRST time only, adds `(bar-length - partial)` to that voice's own `:bar-pos`
  before its ordinary `+dur` -- so the first `:bar` crossing lands after
  just the pickup's own length, not a full bar. A forked voice inherits
  the flag from the voice it forks from, so a `{ }` after the pickup
  doesn't count it again; a `partial` that opens a `:PAR` branch before
  any note still applies there.
- **LilyPond pitch languages** — musics text has only GUIDO's
  accidental symbols (`#`/`##`/`&`/`&&`/`n`); there is no `!language:`
  key. `musics.common.music-data/accidental-tables` (`{language {suffix
  semitones}}`, `:nederlands` and `:english`) exists for reading real
  LilyPond source: `musics.input.lilypond-import` detects a file's `\language`
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
`KeyAssignment`) are the only spelling for any of these (the three
significant keys are capitals: `Tempo` `T`, `Key` `K` -- also `key`,
`k` -- and `Meter` `M`; the key's context name is `:Key`) — see
`doc/md/decisions.md`'s Wave 6 entry for why LilyPond's own free-standing
`\time`/`\tempo`/`\key` spelling was dropped rather than kept as an
alternative. `VarName`'s own reserved-word exclusion list is just the
17 ornament names — `time`/`tempo`/`key` don't collide with anything a
`\name` VarRef could be mistaken for.

### Meter and indispensability

`Meter` (`musics/common/music_elements.clj`) is a record: `num`/`den`/
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

Set it via `!Meter:N/D` (bare ratio -- e.g. `!Meter:7/8`),
`!Meter:"N/D(a+b+c)"` (quoted, additive grouping, groups must sum to the
numerator), or as the factors of its pulses, indispensability's own
form: `!M:223` is 2×2×3 eighths (12/8, `subdivisions` `[2 2 3]`),
`!M:232` the same length grouped otherwise, `!M:2232/16` sixteenths --
two or more digits, each 2, 3, 5 or 7, an eighth's pulse unless `/n`
(`el/parse-meter-str`, which `meter->str` prints back) — the `:M` and `:meter` aliases
also work. Both forms reach the context
correctly now (`walker.clj`'s `walk-assignment` has explicit
`:Ratio` and `:StringLit` cases for the canonical `:Meter` key); this used
to silently no-op for bare-ratio meters before `Meter` was stabilized.

`indispensability` (`musics/algo/indispensability.clj`) computes Barlow
indispensability: given an ordered subdivisions factor sequence, every
pulse `0..N-1` gets a rank, downbeat always `N-1`. The combination rule is
one formula for any factor: substitute each level's raw digit through that
factor's base table (2/3/5/7 — see `indispensability-base-tables`) rotated
left by one position, then recombine using the same place-value structure
as the pulse index itself. 2 and 3's rotated tables happen to reduce to the
identity permutation (their reference tables are pure rotations); 5 and 7
don't, which is the actual substance of the theory, not a rounding
artifact — verified against known-correct reference tables, not derived
from scratch. `musics/common/music_elements.clj`'s `meter-indispensability`
wires a `Meter`'s own `num`/`den`/`subdivisions` into it (`(or subdivisions
(default-subdivisions num den))`) -- for an additive meter (`7/8(2+2+3)`,
`additive?`: groups that sum to the numerator but don't multiply to it)
it ranks the groups' starts first, among themselves as a group of k
pulses is, then the rest by their place in their own group
(`additive-ranks`), and `metric-weights` (the accents', the groove's)
follows it -- so `musics.common.music-elements` requires
`musics.algo.indispensability` rather than keeping a second copy of the
algorithm itself — the earlier standalone `musics/algo/` port of this same theory
(`psi`/`psi-fractions`, from pymusics' `indispensability.py`) turned out to
skip the base-table substitution step entirely, agreeing with this
implementation only for factors 2/3 and silently diverging for 5/7; it was
removed in favor of this one rather than kept alongside it. Bar-length
itself (for `musics.conductor`'s `:bar` signals) only needs `num`/`den`, not
indispensability — the two are independent consumers of the same `Meter`.

`musics.algo.indispensability` also carries an "adherence" layer on top
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
`musics.algo.random/shuffle`, `musics.algo.common.rotate/rotate`) before finally
handing the result to `musics.algo.common.pulse/grid->pulses` or
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
same shape every other `musics/algo/rhythmic/` generator produces) — governing
how MANY pulses sound, not how strongly rank predicts which ones do.

### Grammar (`resources/musics/input/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

The parsing layer's own detail -- comments, variables and how the
walker handles them -- is in `doc/md/grammar-notes.md`; read it before
changing the grammar or the walker.

Current bracket scheme — GUIDO-inspired brackets/accidentals,
LilyPond-inspired leaf/command spelling otherwise (checked directly
against `resources/musics/input/musics.ebnf`'s own header comment, always the
source of truth when this section and that comment could drift apart
again):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `[ ]`     | `Sequence`    | musical sequence — also reused as-is for `repeat`/`volta`'s own body; the walker, not the grammar, decides whether a given `[ ]` becomes a real, addressable container or is spliced/stashed, never how it's spelled |
| `{ }`     | `Parallel`    | simultaneous parts (GUIDO's own bracket for this) — can carry an `Id` exactly like `Sequence` can, so a parallel group is individually addressable the same way |
| `^{ }`    | `Context`     | named context/envelope definition — a genuine Clojure map-literal echo, a Context being a bag of key/value settings; the leading `^` sets it apart from Parallel's plain `{ }` |
| `@^{ }`    | `Settings`    | an algo's settings, `@^{ k:5 bass.k:3 }` -- a value of mu's (`:TCONTEXT`), never inside a line or a `^{ }`; a recipe's head |
| `@( )`   | `TreeRecipe`  | (`mu.ebnf`) an algo tree in RPN: `@( name:? ^{ … }? words … )`, stored by `R!` as a `:RECIPE` |
| `'[ ]`    | `Data`        | data container (`nil` no value, a `'[ ]` inside a list, `'[ ] [~]` a list that loops, `./.` idem -- the value before once more, expanded when read -- a `:keyword` beside any value) |
| `[: :]`   | `RepeatBars`  | a sequence played twice, `repeat 2 [ ]` |

`( )` means two things, never ambiguous with each other since they're
reachable from totally different positions: a slur mark glued directly
onto a Note/Chord (`c4( d4 e4)`), LilyPond-style, at a note's own
trailing suffix position (always glued with no separator onto an
existing note/chord, never a fresh element of its own); and `Scope`,
the transient body of the bare-word structural commands below
(`transpose c g ( c4 d4 e4 )`) — never a container of its own, always
spliced/stashed at pop time, same as `[ ]` is for `repeat`/
`volta`'s own body. (`StructValue`'s own unrelated `( )`, a
parenthesized `Value` after `!name:`, is a third, textually-distinct
use reached only from that one position — see `musics.ebnf` directly
if you need it, not covered further here.)

No algorithm is invoked inside music (the old `AtomicAlgo`/`ElementAlgo`
are gone); a tree recipe, `@( … )`, is a mu value of its own, read
by `mu.ebnf` -- see the "Algorithm registries" note above. `(par ...)` is likewise not this grammar's own spelling for
anything — that's tier 3's play-arg mini-language (see "The play-arg
mini-language" below for `musics.compose/par`, a genuinely different,
Clojure-data-side mechanism with its own reasons for existing,
unrelated to this text grammar's own `{ }` Parallel).

**The top level is mu's.** `musics.ebnf` defines the literals; `mu.ebnf`,
appended to it (`musics.mu.grammar`), defines `MuInput`, the one start
rule -- a `.mus` file, the GUI editor's text and the mu prompt are read
alike. At the top level a literal (`Composite`, `Leaf`, `repeat`, ...)
is a value, a name a word, `:name` an id, `\name` the bound value;
nothing written there reaches `:ROOT`'s context, which stays a read-only
endpoint with a guaranteed value for every key
(`musics.common.context-keys/root-defaults`,
`musics.domain.context/context-root`): a bare `Instruction` is parsed
only to be refused (`LooseInstruction`), and so is a transient command
(`TopCommand`: `transpose`, the row forms, `grace`, `shuffle ( )`,
`rotate n ( )`), which belongs inside a line. A bare leaf is a value of
its own: `walker/walk-each` walks it inside an ordinary auto-id'd
one-child `:SEQ` (`wrap-bare-leaf`), and stored it is a one-note line --
`c4 d4` stores two, `[ ]` groups. See `doc/md/decisions.md`, 2026-10-23.

`:ROOT` being grammar-guaranteed write-once is also what lets its own
context values skip the general `Envelope`/`Point`/atom machinery
entirely: `musics.domain.context/ValueSource` (`sample-at`/`shift`,
`extend-protocol`'d over `Envelope` and a bare-value fallback) lets
`context-root` store each default as a plain value directly — no
allocation for something that, by construction, can never receive a
second point. Any other context still builds a real `Envelope` as
before (a `!tempo:90` inside `[verse: ...]` could still legitimately
grow into a ramp later); the protocol dispatch is what lets
`ctx-value-chain`/`ctx-shift` treat both shapes uniformly without
needing to know in advance which one a given key holds.

`repeat`'s own body, its `volta` endings and measured tremolo's body all
persist as a real, retained container (an `Iterator`'s own `:source`/
`:alternative` to replay on each iteration), so `[ ]` (`Sequence`) has
always been the correct, unambiguous bracket for them — unlike
`transpose`'s and the row forms' own (transient, spliced-not-registered) `( )`
`Scope` body, which is never registered at all.

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT`). A context
written in a sequence -- a `:loud` reference or an inline `^{ !mf }` --
is the first context of everything after it there, nested lines
included: `[c d ^{ !mf } e [!ff f g]]` plays e, f and g at mf. The
walker leaves an override marker among the children (`{:type
:assignment :key :override :ref id :raw ...}`, `override-mark`), which
`musics.events`' `walk-children` and `display-timed` put in front of
the chain (`musics.compose/override-of`); it also replays the context's
points into the current container (`apply-context-ref`), so what is
read while parsing (a key, a meter) sees them. Printers write the
marker's `:raw` back; `write`/`load` keep it (plain data, the id). A
variable is mu's: `'name value :=` binds, `\name` (`VarRef`) is the
value -- see "Comments and variables" in `doc/md/grammar-notes.md`.

`repeat ~ [ … ]` repeats endlessly (`:count :infinite`, no `volta`).
`rotate n [ … ]` (`:SEQ` marked `:rotate n`) moves them n places further
each time it is entered (the walk counts entries per voice). `choose [ … ]`
(`CHS`, `:choose`) plays one of its elements, picked at random, each
time it is entered (`choose 3,1 [ … ]` weighs them, one weight per
element, kept as `:weights`); its length, where one is needed, is its longest
element's (`d/duration`); `alternate [ … ]` (`ALT`) plays its elements
in turn, one each time it is entered, counted per voice like `rotate`.
`shuffle [ … ]` is a line (`:SEQ` marked `:shuffle`) whose notes, lines
and repeats play in a new random order each time it is entered, so
`repeat 4 [ shuffle [c d e] ]` shuffles every pass; instructions, bar
lines and jumps keep their places (`musics.compose/play-order`, used by
`musics.events` and `display-timed`; `musics.algo.random`, so a seed
repeats it). mu's `[?]` makes one.
`[: … :]` (`RepeatBars`) is `repeat 2 [ … ]` in repeat signs,
an Iterator marked `:bars` so the printers write it back that way;
a `volta [ … ]` (its endings) follows it as it follows `repeat`. `Jump` marks,
written inside a sequence, are zero-duration nodes (`d/jump`): `$` the
segno, `DS`/`DC` the jump back to it or to that sequence's start,
`Fine` where the return pass stops, `Coda` twice (the return pass
leaves at the first for the second), `./.` (alone in its bar: a bar line before
it, a bar line or the end after) the bar before it once more; a return pass goes on past the
jump without taking it again. `musics.domain/play-segments` gives a
sequence's children in the order they play (index ranges), and
throws, at read time, on marks that can't be played; `d/duration`,
`musics.events` and `display-timed` follow it, a return pass
re-entering the sequence's context where it is written. A `[: :]`
inside the part jumped back to repeats again. While reading, positions
are measured as written (`d/written-duration`).

`BarLine` (`|`, `||`, `|||`, `||||`) walks to a `Bar` record (`d/bar`,
zero duration) inline in `:children` — purely a structural marker on disk,
but no longer inert at playback: `musics.events` emits a `:mark` event
for each one a voice reaches, which the engine signals to
`musics.conductor` (see "Conductor"
above), so `|`/`||`/etc. are exactly how a composer places an extra,
author-controlled cue on top of the automatic `:section`/`:bar` signals.
Reachable only through `Sep`/`EdgeBar` (`Sequence`/`Parallel`'s own
element-list separator and edge-of-body marker), both built on a shared
`BarRun` (`BarLine (ws? BarLine)*`) so a *run* of consecutive bar lines —
not just one — is legal wherever a single one is, e.g. `c4 | | d4` (two
adjacent single-pipe checks with nothing between them). This was a real,
confirmed gap, not a hypothetical one: `musics.input.lilypond-import`'s own
`repeat`/`\relative` body-flattening can legitimately produce exactly
this shape — a nested `\relative` block contributes no wrapping container
of its own (`relative-block-text`'s own text has no surrounding
brackets), so its own leading edge bar ends up sitting directly next to
whatever bar line the enclosing stream already had, with no `Element`
between them for the old `Element (Sep Element)*` structure to accept.
Each `BarLine` in a run still surfaces as its own separate sibling node
(`BarLine` itself isn't hidden) — `walker`'s own `:BarLine`
case needed no change at all, it already appends one zero-duration `Bar`
record per occurrence regardless of how many arrive in a row.
`lilypond-import`'s own `append-relative-block` (a sibling of
`push-barline`) additionally dedupes the specific case it CAN see across
(a `\relative` block's own leading bar against the immediately preceding
one in the same accumulator) so the common case doesn't emit visibly
redundant text at all — a `VarRef`'s own stored body is opaque to that
check, so that case relies on the grammar's own tolerance instead.

Tempo takes either a bare BPM (`!tempo:120`, quarter note implied) or a
LilyPond-style `TempoMark` — note-value `=` BPM (`!tempo:4=120`, a
dotted one, `!tempo:4.=120`, or a ratio, `!tempo:3/8=120`). The
walker (`walker.clj`'s `walk-assignment` `:TempoMark` case)
converts a `TempoMark` to the quarter-note-equivalent BPM `resolve.clj`'s
tempo sampling expects (`el/tempo->quarter-bpm`, e.g. `8=120` → `60`,
since an eighth note is half a quarter, so eighth=120 is the same speed as
quarter=60) before storing it — `resolve-event` never sees the note-value
side at all, only the normalized BPM. `!tempo:`/`!Tempo:`/`!T:` all
canonicalize to the same `:Tempo` context key (`musics/common/context_keys.clj`)
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

Accidentals are GUIDO's symbols only — `#`/`##`/`&`/`&&`/`n`, a key's
tonic too (`!key:B&.minor`, any case, any of the 23 scales); LilyPond's
Dutch suffixes have to be rewritten (see `doc/md/lilypond.md`). A dynamic mark
glued onto a note/chord (`c4\f`, `<c e g>4\mf`) is that note's own
volume, a per-note override exactly like `c4\vol:70`; the next note is
back at the context's. A note can't carry a crescendo or decrescendo:
that spans time, so it is an instruction (`!vol<`, `!vol:mf<`,
`!vol<16:ff`). The LilyPond importer, where a dynamic holds until the
next one, writes `!f` before the note and turns `\<`/`\>` into
`!vol<`/`!vol>` (joined to the note's dynamic as `!vol:mf<`). Absolute octaves need a **capital**
pitch letter (`C5`); lowercase is always relative pitch resolution (nearest
fourth/fifth, LilyPond `\relative`-style) even as a sequence's first note —
there's no position-based exception. A capital letter without an octave
takes the octave number of the pitch before it (`leaf-parser/resolve-
pitch`, its `last-ref`), as a duration carries over -- notes, chord tones
(a chord's next starting from its first tone, as relative chords do),
chord-symbol roots and bass notes alike; octave 4 at the start.

An algo's settings are their own value, `@^{ name:value … }`
(`Settings`), never among the music's keys: `@^{ k:5 n:16 bass.k:3 }`.
The walker (`walk-settings`) makes a `:TCONTEXT` node holding `:params`,
`[name value]` pairs as written, and `:text`, which prints it back; it
is a definition form like a `^{ }`, plain data that `R!` stores and
`write`/`load` keep. A qualified name (`euclid.k`, `bass.k`,
`pitch/walk.lo`) names an algo or a named instance. Values: numbers,
strings, a bare name (a keyword), a comma list or a `( )` list (a
vector). No ramps -- an algo reads its params once per run. No line or
`^{ }` holds one: `?name:value` (`AlgoAssignment`) is parsed only to be
refused, "an algo's settings are @^{ k:… } -- music holds ! keys only".
A name bound to another name in mu (`'dp 'duration-pulse :=`) is a
short name: `musics.mu.value` hands such bindings to the walker as
`:param-aliases`, and `@^{ dp:1/8 }` is kept and written back as
`@^{ duration-pulse:1/8 }`. Trees read them as their settings (`t/run
tree [local tctx head] chain`; see "Simple composition" below).

A chord symbol is a note with a `:` quality (`ChordSymbol`: `F3/1:7`,
`c:m7`, `G3:7.9-/B`), LilyPond's chordmode spelling as a leaf of its
own, walked (`walker/walk-chord-symbol`) to an ordinary multi-pitch Leaf. The
qualities are `musics.common.music-data/chord-qualities`: LilyPond's
words, `.step[+/-]`/`^step` alterations, `/pitch`/`/+pitch` bass notes,
and the shorthand `m` `M` (`M` the triad, `M7`.. the major sevenths)
`d` `A` `s`; a bare `7` is dominant. The quality is required (a note
without one is a note; `:M` is the major triad). A tremolo is `:8` `:16` `:32` `:64` or `:128` (`Tremolo`'s
lookahead), which keeps every `:` quality from reading as one.

A note or chord can override any context key for itself with a
Modifier, `\name:value` (`c4\vol:90\i:40`, `e4\pan:-1.0`): the value
reads as `!name:value` reads it (signed numbers, ratios, a dynamic mark
for volume, strings), the name is canonicalized the same way, and the
walker stores the lot as the leaf's `:overrides` map, which
`musics.domain.resolve/resolve-common` merges over the sampled context for
that note only (ornament and tremolo sub-notes keep it). A glued
dynamic (`c4\f`) is the same override, for volume.

A `Ramp` (`!key<...`, any context key) has four shapes: bare open-ended
(`!vol<`, `!vol>` — marks a ramp-start with no target, interpolating
toward whatever value comes next), bare with a curve (`!vol<s` —
`l`/`s`/`i`/`o` = linear/smooth/ease-in/ease-out), timed (`!vol<16:ff` —
duration, a raw whole-note count/product/ratio, not a note-value
reciprocal the way a note's own Duration digit is, then a target), and
timed with a curve (`!vol<s:16:ff`). `!key:value<` (`!vol:mf<`,
optionally `!vol:mf<s`) sets the value *and* marks a ramp-start in one
instruction, for any key. Deliberately *not* mirrored onto `BangConst` (`!mf<` was considered and
rejected) — `Name` has no exclusion list, so a trailing direction there
would collide with `Assignment`'s own bare-Ramp alternative: `!p<`
already parses today as `Assignment`(`AssignName` "p") + `Ramp`(bare
"<"), since `p` is a registered `:panning` alias, and `p` is also a real
`DynamicMark` word (pianissimo) — a directly demonstrable ambiguity, not
a hypothetical one. A direction is always bare (`<`/`>`, no `\`), since
`!` already marks "this is an instruction".

A point can carry its time, `@t` (`At`), after a value form --
`!vol:mf@3/8`, `!vol:50<@1`, `!mf@1`, `!K:D.major@2` -- in whole notes
from the start of the container it is written in, as a timed ramp's
duration counts them (`@4` four whole notes, `@3/8` three eighths): so a
`^{ }`, which holds no notes and had every point at its start, can hold
values that change later, `^{ !vol:50< !vol:70@1 !vol:90@2 }`, a ramp
mark gliding to the next point wherever its `@` puts it. The walker's
`point-time` reads it, and `with-at` keeps it on the instruction's
`:raw`, so the context prints back as written. In a line it counts from
the line's start too -- where `with` puts a context's instructions.

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
against (`:key`, `walker/written-key`; none under
`:explicit`), and `musics.domain.resolve/rekey` — run per leaf before the
wall and ornaments, by `musics.events` and `display-timed` — moves each
pitch that is a degree of that key to the playing key's accidental for
that diatonic step (`musics.common.music-elements/rekey`, `key-step`):
`[mot: c d e f g]` plays `c d e f# g` inside `[!key:G.major :mot]`. A pitch outside the leaf's
key (written with its own accidental) stays; so does a container's own
`!key:` (nearest wins) and anything under `!acc:explicit` where it's
played. A written accidental that the key already implies (`fn` in C)
can't be told from a bare letter and follows too. `transpose` adds
its interval to the leaf's `:key-shift`, and `rekey` transposes both
keys by it before comparing, so transposed notes follow the playing key
transposed the same way; their printed names are spelled in the
transposed key (`musics.common.music-elements/transpose-key`). A chord
symbol moves by its root (`:key-root`), keeping its written quality
(`F4` in C plays F# major under G). Generated notes carry no `:key` and
never move.

### Micro-timing: `:micro`/`:humanization` context keys

Two context keys — `:micro` (a direct per-note onset offset, seconds,
range -0.5..0.5) and `:humanization` (a random-spread magnitude,
0.0..1.0) — move a note's real wall-clock onset, sampled in the same
batched `common-keys+defaults`/`c/sample-many` pass `:Meter`/`:Partial`
already ride in. `musics.domain.resolve/humanize` turns them into an
onset offset and a velocity: `:micro`, plus a random onset shift and
velocity change of up to the `:humanization` quantity's `:spread`
(`musics.common.music-data/quantities`) either way, × `:humanization`. The engine (`musics.engine/schedule!`, drawing with `rand`)
and `musics.midi.file/render` (`midi-file/events->sequence`, a seeded
Random) both call it. Both keys default to `0.0`, so a piece that never
sets either is unaffected. A negative
`:micro` moves a note EARLY, by at most the engine's lookahead (100 ms)
live — a note is queued no sooner than that before its nominal time —
and by the full amount in `render`. The offset belongs to
that one note: the voice's own `:t` still advances by the note's
unperturbed duration, so one note's delay never drifts the ones after
it.

`:swing` is the long note's share of a pair of `:swingUnit`s
(`!swing:0.7`; 0.5 straight, the default; `!swing` 0.6, jazz swing;
`!shuffle` 2/3, a strict triplet feel; 0.75 dotted; `!swingUnit:16` or `1/16` swings sixteenths,
eighths by default). It is heard, not written: `musics.events/swung`
warps each note's start and end by where it sits in its bar (the
first unit of a pair stretched to the ratio, the second squeezed into
the rest, a pickup counted in), folding the start's move into `:micro`
and the length into `:dur-played` -- so an off-beat starts late, the
note before it is held, and a note on pairs' boundaries is untouched;
the voice's `:t`, its bars and signals stay straight, and engine and
`render` play it alike. `:groove` (-1..1) lays a note back (+) or
pushes it (-) by the weakness of its place in the bar -- the meter's
weight at a sixteenth's resolution (`musics.common.music-elements/
metric-weights`, Barlow's) -- up to a 32nd at 1.0, so the downbeat
stays (live, a push is capped at the lookahead, like `:micro`); `!laidBack` is 0.5, `!pushed` -0.5. `:accent` weighs each note's
velocity as heard: `!accent:metric` by its level in the meter's grid
at a sixteenth (`musics.common.music-elements/metric-levels`: each level
a clear step, every pulse of a level alike, so `!M:223` and `!M:232`
sound different), `!accent:barlow` by Barlow's finer ranking (the
groove's `metric-weights`), `!accent:agogic` by its length, the
weakest losing and the strongest gaining up to a quarter of the
velocity at `!accentDepth:1` (0.5 by default: an eighth); drums too. `musics.events/felt`
applies swing, then groove, then the accent.

### Other modules worth knowing about

Every namespace sits under `musics.` (`src/musics/`): `musics.mu` (mu,
the entry point, `lein mu`), the domain and the store (`musics.domain`,
`musics.repo`), the sound and playground namespaces (`musics.events`,
`musics.engine`, `musics.wall`, ...), and `musics.algo.*`,
`musics.common.*`, `musics.input.*`, `musics.gui.*`, `musics.midi.*`.
Tests mirror it under `test/musics/`: a source ns's tests are
`<ns>-test` (`musics.algo.bridge-test`), extra test files for one ns
sit beside it under a descriptive name (`musics.algo.random.seed-test`),
and `musics.test-support` holds the shared fixtures.
The grammar is a classpath resource (`resources/musics/input/musics.ebnf`);
test fixture files are in `dev-resources/fixtures/` (read with
`io/resource`); `dev/` holds `musics.midi-probe`, a standalone tool;
example scores in
other formats are in `corpus/` (`abc/`, `guido/`, `mus/`, `data/`).

- `musics/repo.clj` — the flat `{id -> node}` store (see "Session, the
  repo, and playback" above); zero dependencies on the domain model or
  anything else in the project, deliberately (it requires only
  `musics.registries`, the leaf namespace holding its actual atom).
- `musics/assist.clj` — REPL help worked out from action facts (see
  "Help" above).
- `musics/conductor.clj` — the signal/schedule layer (see "Conductor" above);
  depends on nothing else in the project at all, not even `musics.repo`.
- `musics/wall.clj` — the per-voice playback-algorithm registry (see "Wall:
  per-voice playback algorithms" above); a parked toolbox, no dependency
  on `musics.engine` at all (that dependency runs the other way).
- `musics/compose.clj` — the play-arg Form grammar + `display`/
  `display-timed` (see
  "Composing vs. performing" above); engine-free, `musics.events`/
  `musics.engine` depend on it, never the reverse.
- `musics/events.clj` — a Form's performance as a lazy seq of timed
  events (see "Performance as data" above); engine-free, like
  `musics.compose`, which it builds on.
- `musics/engine.clj` — live playback: one sender thread performing
  `musics.events` streams (see "Live playback" above).
- `musics/common/music_data.clj` — requires nothing; everything else in
  `musics/common/` and every algo builds on it. Its `quantities` table is the
  one source of truth for numeric ranges and defaults: name →
  `{:type :min :max :default :scale :doc}`, the same shape as an algo
  param spec. `:scale :log` marks quantities heard as ratios — `:tempo`
  (30..300, default 100), `:note-value` (1/64..4, default 1/4), `:ratio`
  (1/16..16, default 1) — each default at the log middle of its range;
  `:pitch` (24..119, what musics text writes) and `:semitones` (±60) are
  linear. `(quantity :pitch {:doc ..})` returns a spec with overrides, for
  algo metadata. The rest is reference data ported from an earlier Python
  implementation (pitch names, note lengths, dynamics, scales, drum name
  → MIDI, …). Instruments, drums and kits are found by name, short name
  or the start of one that can only mean one (`program-of`/`resolve-drum`/
  `kit-of` through `smart-get`: `trump`, `cow`, `elec`; an ambiguous start
  throws, naming the candidates; `instrument-matches`/`drum-matches`/
  `kit-matches` list them, mu's `find-instrument`/`find-drum`/`find-kit`;
  `musics.mu.complete` is mu's Tab completion of them, a JLine completer,
  which also completes context keys and `!` instructions, a note's `\`
  tags, dynamics after `!vol:`, scales after `!K:F.`, stored parts after
  `:` (named ones, not `:s12`) and mu's words, qualified ones after `ns/`).
- `musics/common/context_keys.clj` — the context-key registry (`!key:` names,
  aliases, types): `reg!` takes each key's range, default and scale from
  a quantity (`:Tempo` → `:tempo`, `:rate`/`:durScale` → `:ratio`,
  `:transposition` → `:semitones`), so the GUI's context sliders and the
  algo params agree by construction. The GUI shows a slider only for a
  key playback actually reads (`musics.domain.resolve/played-keys`). A `:log` quantity's slider moves in
  equal ratios (`musics.gui.components/slider`, `:scale :log`).
- `musics/common/music_elements.clj`, `musics/common/music_tools.clj` — key
  parsing, `Meter`/indispensability (see above), and other music-theory
  helpers used by the walker/ornaments. `musics/common/` is flat — no `data`/
  `elements`/`tools` subdirs — since each held only one or two files.
- `musics/input/reader/leaf_parser.clj` — pitch/duration/articulation/dynamic
  parsing at the leaf level, independent of the grammar/lexer.
  `musics/input/lilypond_import.clj` sits at the top level of
  `musics/input/`, not nested under `reader/` — a peer of, not a
  sub-concern of, the walker: `lilypond_import` is a fully independent LilyPond→musics-text converter
  that never touches `musics.domain.*` or `musics.input.reader.flat-*` at all.
  `musics/input/abc_import.clj` is its sibling for ABC notation (a compact,
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
  explicit`) via `musics.common.music-elements`'s own `scale-steps` table
  rather than a second hand-copied circle-of-fifths one — an EARLIER
  hand-typed version of that table had three real transcription errors
  (Gb missing its own C, Db carrying an extra one, Cb missing its own
  F), caught only by running all 15 major keys through it and checking
  every one of the 7 letters, not by spot-checking a couple of common
  ones; see `abc_import_test.clj`'s own `key-signature-covers-all-15-
  major-keys-with-correct-counts` for the regression coverage this left
  behind. mu's `load` converts a `.ly`, `.abc` or `.gmn` file in memory
  and reads the text (`musics.kernel/load!`); `abc-to-mus`/`from-ly-to-mus`/
  `guido-to-mus` write a sibling `.mus` file instead.
- `musics/input/guido_import.clj` is a third sibling, for GUIDO Music Notation
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
  reasoning behind it.
- `musics/domain/ornaments.clj` — expands a `Leaf`'s ornament/grace/tremolo
  modifier into replacement sub-leaves at resolve time (needs the active
  `Key` from context for scale-relative ornaments like `prall`); lives with
  the rest of the domain model, not under `musics/midi/`, since it never touches
  MIDI itself.
- `musics/midi/gervill.clj` — Gervill, the JDK's own software
  synthesizer, as the other live output: `(musics.engine/connect!
  :gervill)` plays through it instead of Fluidsynth/VirMIDI, its
  `Receiver` going to the engine as VirMIDI's does;
  `musics.engine/soundfont!`/`soundfonts` load any `.sf2` (mu: `gervill`
  `fluidsynth` `soundfont` `soundfonts`).
  Its 30 ms latency needs `:jvm-opts`' `--add-exports
  java.desktop/com.sun.media.sound`. An output that doesn't open
  within 5 s (a Bluetooth speaker asleep) is an error, not a wait
  without end. `open-stream` renders into an
  `AudioInputStream` instead of the speakers (its tests).
- `musics/midi/file.clj` / `musics/midi/live.clj` — the two MIDI
  OUTPUT backends (file-based `aplaymidi` playback vs. live Fluidsynth via
  VirMIDI). `midi_live.clj`'s `Receiver` (`open-receiver`/`note-on`/
  `note-off`/`program-change`/`control-change`) is what `musics.engine`
  uses for real sound; `midi_file.clj` writes `.mid` files —
  `events->sequence`/`write-events` render `musics.events` output (one
  track per voice, 1000 ticks a second, channels pooled by
  `[program cc]` like the live engine's), behind `musics.midi.file/render`. `midi_live.clj`'s own device discovery
  (`find-writable-device`) is backed by `overtone.midi` now, not a
  hand-rolled `MidiSystem` walk — see "MIDI input" above, which uses that
  same library directly for the opposite direction (`musics/input/midi.clj`/
  `musics/input/midi_record.clj`); everything else about `midi_live.clj`
  (auto-connect, byte clamping, its public API) is unchanged.
- `musics/algo/` (renamed from `algorithm/`) — generative helpers, organized into
  topics: `indispensability.clj` (Barlow indispensability); `metric.clj`
  (modular/binary/continued-fraction pulse generators); `rhythmic/` (Euclidean/
  Fibonacci/prime/L-system/Markov generators in `rhythm.clj`, plus ten
  more files ported from `python-reference`'s `advanced_rhythm.py` --
  `phase-sieve`/`poly`/`necklace`/`stochastic`/`physical`/`transform`/
  `sonification`/`constraint`/`fractal-geometric`/`world`, covering
  Reich phase music, Xenakis sieve theory, polyrhythm/polymeter, rhythm
  necklaces and Vuza canons, genetic/Markov/RNN rhythm generation,
  physical-simulation and natural-process rhythms, EMI-style/Oblique
  Strategies transforms and groove/humanization, data/text sonification,
  constraint satisfaction, fractal and geometric rhythms, and Indian
  tala/West African timeline patterns -- and `drums.clj`, style drum-kit
  grooves as a `par` group of Drum/Rest layers, each hit ghosted/plain/
  accented/marcato -- the articulation a written drum takes, `x8\38->`,
  stored as the Drum's `:dynamic`); `melodic/` (scales, generative
  melody methods, and constraint-satisfaction walks in `melody.clj`,
  plus `counterpoint.clj` -- a multi-voice motif-imitation + species-
  counterpoint generator ported from the same source); `random/` --
  split into `random/core.clj` (`musics.algo.random.core`, the pure xorshift32
  engine, mostly public, plus atom-backed seeding/state) and the parent
  `random.clj` (`musics.algo.random`, the basic primitives and everything
  built on them: distributions, chance/weighted-pick helpers), sitting
  alongside `random/logistic.clj`/`random/lorenz.clj` (chaotic maps,
  no PRNG, untouched by that split) -- see `musics/algo/random/core.clj`'s own
  header comment for why it's atom-backed rather than a dynamic var
  (wall-algorithm safety, the same core.async concern as everywhere
  else in this file); and `musics/common/` (`reshape.clj`'s sequence-reshaping
  recipes, `isorhythm.clj`'s color-talea pairing, `split.clj`'s voice-
  splitting, plus `farey.clj`/`trig.clj`/`scaling.clj`, small math
  utilities also ported from the reference dirs). Mostly still
  standalone/unwired into the grammar or engine, same as before the
  reorg — `musics.algo.indispensability` is the one exception:
  `musics.common.music-elements/meter-indispensability` requires it directly
  (see "Meter and indispensability" above), so that one namespace is a
  real, live dependency now, not just a kept-for-reference algorithm.
  `java-reference/`, `julia-reference/`, `kotlin-reference/`, and
  `python-reference/` — prior implementations of this same system in
  other languages that these ports came from — are no longer part of
  the repo at all; see `doc/md/decisions.md` for why (each file under
  `musics/algo/` that traces back to one of them still says so in its own
  header comment).
