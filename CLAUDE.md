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
global-vs-instance state generally (`doc/md/why/audits/review.txt`, point 1) — not an
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
can't express (see `doc/md/why/decisions.md`'s Wave 7 entry, and its
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
`doc/md/why/decisions.md` instead, as a short, standalone entry — check there
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

See `doc/md/why/decisions.md` for the dated history of how each of these
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
`org.clojure/core.logic` (species counterpoint, `musics.algo.counterpoint.jeppesen`, and `musics.assist`). A Factor-style
hosted REPL language (musics.lang) lives on the `concat` branch / the
`frepl-standby` tag, not here — see `doc/md/why/decisions.md`, 2026-09-28.

```bash
lein mu                 # mu, the musical REPL -- with the GUI (gui), the interface
scripts/build.sh        # target/mu.jar (lein uberjar with the version baked in:
                        # musics/version.txt, git-ignored) and bin/mu, its launcher:
                        # java 25 + the JVM options, -jar; AOT classes go to
                        # target/uberjar-classes, off the dev classpath
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
lein verify             # lint, then the full test suite (CI runs it: .github/workflows/ci.yml)
lein test musics.input.reader.command-walk-test         # run a single test namespace
lein test :only musics.input.reader.command-walk-test/duration-ratio-scales-and-is-inherited   # single test var
scripts/docs.sh         # render README + doc/md/*.md + CLAUDE.md to styled
                         # HTML in doc/html/ and PDF in doc/pdf/ (both
                         # git-ignored; the .md stays the source)
                         # -- open doc/html/index.html
lein run -m clojure.main scripts/algo-cookbook.clj  # the algo cookbook: doc/md/algo-cookbook.md, every recipe run
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
  │    see doc/md/why/decisions.md's Wave 3 entry)
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
(`lein mu`) and the **GUI** (`gui` in mu, `musics.gui.core/launch!`; `:tctx gui` or its Settings... button opens `musics.gui.settings`, a control for each setting of the tctx); see
`doc/md/why/decisions.md`, 2026-10-23. `musics.registries/session` holds the
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

`lein mu` runs **mu**, the musical REPL (`musics.mu`, design in `doc/md/mu-repl.md`, every
word in `doc/md/mu-words.md` and mu's own `words`/`help`/`see`/`about`): a stack-based, Forth-like
REPL in which musics text is first-class. A literal (`[ ]` a `:SEQ`, `{ }` a `:PAR`, `'[ ]` data) is
a value that does not enter the repo (`musics.mu.value`; all literals of one input are read
together so durations and relative pitch carry over); `R!` stores, `R@` fetches, `->` plays one
without storing it. A bare symbol acts on each element, the same symbol in brackets on the whole
seq (`+` transposes every note, `[*]` repeats). Words are plain Clojure functions, one namespace
per mu namespace (`musics.kernel`, `.compare`, `.arith`, `.tool`, `.music`, `.play`, `.rate`,
`.soundfont`), registered by `musics.mu.library` under their name (a trailing `!` left off) and
run by `musics.mu.eval`, which takes their arguments from the stack and names an error by the
word and where it was written (file:line:col, innermost first); the words that read the stack's
shape are stack words in `musics.mu.kernel` and `musics.mu.tools`. `: name ( $a b -- c ) … ;`
defines a word (a `$` input is a local, results are checked), `'( … )` is a quotation, `( … )` a
group run now and gathered, `|` a mark that gathering words (`zip`, `par`, `>ctx`) read above.
The algorithms are plain functions called by name (`"rhythmic.pattern" reqalgo`, `3 8 pattern/euclid`)
that make raw material, which `zip` puts onto a seq (see the musics-internals skill, "The
algorithms"). Playback: `->` now, `-|>` at the next boundary, `->+`/`-|>+` joining, a voice's id
on top of a play word is the voice to play as, `stop`, `pause`. The prompt is `mu<n>` (the
stack's depth); `bye` or Ctrl+D leaves. `"x.mus" load` reads a file (`.ly`/`.abc`/`.gmn`
converted), `x "x.mid" render` writes a MIDI file, `"x.edn" save-session`/`restore-session` keep
what is stored and mu's own words and bindings.

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
  *different*, still-playing part either (see `doc/md/why/decisions.md`'s Wave
  4 and 2026-09-17 entries). A container's children are looked up when
  the voice enters it, so a cutover reaches material the voice reads
  after the boundary — typically the next pass of a loop or the next
  item of a `[...]` play arg.

mu's `forget` and `rename` (`musics.repo/forget!`/`rename!`) tidy the store: `R_` is `forget`'s sign (`R!` stores, `R@` fetches, `R_` deletes); `rename` rewrites a name in one swap, wherever it is held (`:children`, nested ones, an override marker's `:ref` and `:raw`, an inline context's definition, a recipe's `:text`); `forget` refuses a name another id still refers to (`referrers`, wider than `usages`, which is direct children only).

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
performance isn't a document (`doc/md/why/decisions.md`, 2026-10-30).

## Where the rest is

Loaded only when it is needed, so a session that never touches it never pays for it:

- **`resources/musics/CLAUDE.md`** (loads when working under `resources/musics/`): the grammar
  (`musics.ebnf`, `mu.ebnf`): brackets, comments, notes, chord symbols, modifiers, ramps, keys,
  repeats and jumps, `\time`/`\tempo`/`\key`, multi-measure rests, pickups, pitch languages.
- **The `musics-internals` skill** (`.claude/skills/musics-internals/SKILL.md`): the conductor, the
  wall and the play-arg mini-language, live voices and voices that are a function of onset, the
  tctx, `musics.compose`, `musics.events`, `musics.engine`, MIDI input, the flat domain model and
  context chains, meter and indispensability, micro-timing, algorithms, harmonic and counterpoint
  algos. Load it before changing the engine, events, compose, conductor, wall, domain or resolve.
- **`doc/md/`**: `namespace.md` (the namespace map), `mu-words.md`, `mu-repl.md`,
  `pull-voice.md`.
- **`doc/md/why/`**: the technical files about implementation choices: `decisions.md` (why things are
  as they are; check it before re-proposing), `audits/`, the design notes (`core-logic.md`,
  `assist-with-logic.md`, `algo-audit.md`, `other-libs.md`, `ideas.md`, `scoped-tctx.md` (a proposal for a
  tctx with a scope for each voice)).

## Gotchas by module

- Dependencies run one way: `musics.repo` requires only `musics.registries`; `musics.conductor`
  nothing at all; `musics.wall` not the engine; `musics.compose` and `musics.events` are
  engine-free and the engine requires them; the playground (tier 3) requires tiers 1 and 2.
- `musics.common.music-data/quantities` is the one source of truth for numeric ranges and defaults
  (`:scale :log` marks quantities heard as ratios: tempo, note value, ratio); the context-key
  registry (`musics.common.context-keys`) and the GUI sliders read it, so they agree by
  construction. Instruments, drums and kits are found by name, short name or an unambiguous start
  (`smart-get`; an ambiguous start throws, naming the candidates).
- `musics.algo.rhythmic.indispensability` is the one algo namespace that is a live dependency:
  `musics.common.music-elements/meter-indispensability` requires it. Everything else under
  `musics/algo/` is standalone, called by name from mu.
- Importers (`musics.input.lilypond-import`, `.abc-import`, `.guido-import`) are independent
  text-to-text converters that never touch `musics.domain.*`. The LilyPond one emits
  `!acc:explicit` once and writes absolute-where-needed relative pitches; ABC and GUIDO write
  absolute pitches with every duration written out. A duration digit straight after an octave digit
  with no `/` silently reparses as octave 4 plus a merged duration (it once passed every
  string-content test): GUIDO's `/` after the octave is mandatory. ABC's key-signature accidentals
  come from `music-elements`' `scale-steps`, which once had three wrong entries; all 15 major keys
  are covered by a test. GUIDO does not imply an accidental from `\key` onto a bare note.
- Gervill's 30 ms latency needs `:jvm-opts`' `--add-exports java.desktop/com.sun.media.sound`; an
  output that does not open within 5 s is an error, not a wait. `scripts/setup.sh` and
  `doc/md/setup.md` cover Fluidsynth and the virtual MIDI port.
- Tests mirror the source tree under `test/musics/`, shared fixtures in `musics.test-support`,
  fixture files in `dev-resources/fixtures/`, example scores in `corpus/`.
