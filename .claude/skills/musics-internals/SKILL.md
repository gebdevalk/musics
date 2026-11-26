---
name: musics-internals
description: Internals of the musics engine and domain model -- conductor signals, the wall and the play-arg mini-language, live voices and voices that are a function of onset, the tctx, musics.compose, musics.events, musics.engine, MIDI input, the flat domain model and context chains, meter and indispensability, micro-timing, algorithms. Use before changing the engine, events, compose, conductor, wall, domain, resolve or mu live voices.
---

# musics internals

Reference moved out of the always-loaded CLAUDE.md; the project overview, commands and pipeline stay there.

### Conductor: signals and scheduled actions

`musics.conductor` (`src/musics/conductor.clj`) bridges the engine's structural
boundaries to arbitrary, named, reusable actions. `musics.engine` depends
on it (a plain synchronous call, `conductor/signal!`, from the sender
thread, as each `:section`/`:bar`/`:mark` event of a stream comes due);
`musics.conductor` depends on nothing else at all, not even `musics.repo` —
a fully generic dispatcher, deliberately one-way (see
`doc/md/why/decisions.md`'s Wave 4 entry for why `schedule-tx!` itself lives
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
  `:t` 0 is that moment (or, joining, adds one) — mu's `-|>`/`-|>+`
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
  that catches the voice's next crossing (see `doc/md/why/decisions.md`,
  2026-09-30).

### Wall: per-voice playback algorithms

This section is the mechanism's architecture reference.

`musics.wall` (`src/musics/wall.clj`) is one registry, `*algo-registry*`:
name -> `{:fn f :doc doc ...}`. A wall fn is seq-in/seq-out:
`(nodes ctx-chain voice) -> nodes'`, called identically regardless of
granularity — `musics.events`' container branch calls it once, on the
WHOLE sibling list, before the children are walked or ornaments
expanded; its leaf/rest/drum branch calls it again with a singleton of
each node, so a fn must pass through what it already produced (mark
it, as `musics.mu.voice` does). What fills the registry is
`musics.mu.voice/follow!` (a live voice's id bound to what it follows — see "The
algorithms" below); `build-algo!` stores a hand-written wall fn directly. `musics.wall/registered` surfaces the full entry.
A voice that is a function of onset does not use it (see "A voice can be a function of onset").

**Voice paths, not slot numbers**: every voice's own registry key
(`musics.engine`'s `:voices` atom) is a vector, root-first, one segment
per level of forking — the same path also addresses that voice's own
`:algo-prepared` entry, if any (see below). A voice's own `:algo` is a
plain, IMMUTABLE value in its stream state, set when the voice starts
or forks and never reassigned — `musics.events/apply-wall` resolves it
via `musics.wall/algo` FRESH at every node (never cached), so
hot-swapping works exactly one way: re-registering the
SAME `name`'s own entry in `*algo-registry*` (an input stored,
`retree`, or `build-algo!` again) — every voice whose own `:algo` already points
at `name` picks up the change on the next node the sender reads (a
lookahead ahead of hearing it), with nothing on the voice itself ever
touched.

`assign-algo!`/`algo-assignments` (`musics.engine`) are a SEPARATE, narrower
mechanism: `:algo-prepared`, `path -> name`, consulted ONLY when a
top-level voice starts (`start-voice!`), and only when that call's
own `:algo` argument is `nil`. `assign-algo!` never reaches an
already-live voice — it only affects a mint that hasn't happened yet
(preparing a track before you start it, the GUI's Wall window). See `doc/md/why/decisions.md` for why a
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
`par`) always `:par`, no guessing (see `doc/md/why/decisions.md`'s Wave 6
entry for why). This is Clojure data, a separate vocabulary from
`musics.ebnf`'s own text brackets (`[ ]` sequential, `{ }` parallel —
see "Grammar" below); the two once shared one spelling, and `#{ }`
was a text-grammar bracket for a while, but the text grammar has since
moved on while the play args kept `#{}` (see `doc/md/why/decisions.md`'s Wave
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
doc/md/why/decisions.md, 2026-10-15). mu's play words with a context on
top (`x ^{ !mf } ->`) build the same `[ctx x]` form.
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

### The algorithms: plain functions

An algorithm of `musics/algo/` is an ordinary Clojure function with clear
argument names (`euclid [onsets pulses]`); there is no registry, tree or
settings object between it and its caller. mu calls it by name with its
arguments on the stack: `"rhythmic.pattern" reqalgo` (or `"musics.algo.rhythmic.pattern"
require`, a quoted name with dots too: `'rhythmic.pattern 'p reqalgo`)
makes its public functions words, `pattern/euclid`, and `3 8 pattern/euclid`
runs one (`musics.kernel/require!`, `run-var`, `see` shows the stack effect).
A function with several arities that names its full call in
`:algo :arity` takes all of that many arguments (`arity-for`); a keyword tail
(`& {:keys [length]}`) takes `:key value` pairs written before the word; a
`:pre` that fails is a refusal naming the function.

What a function makes is *raw material* -- a plain list of pulses, numbers,
weights, onsets or points -- and the **bridges** of `musics.algo.bridge`
(`pulses->durations`, `numbers->pitches`, `degrees->pitches`, `weights->pulses` …)
turn it into durations, pitches, volumes, each with the range or grid it is given
as an argument, one way only; mu's `zip` puts the parts together into notes. A
function that gives one number per call (a sampler, `walk`, the logistic map,
`lorenz` with its `:value`) is called n times by `pull` (`musics.tool/pull`):
`'( 0.0 1.0 random/normal ) 12 pull`, `60.0 2.0 walk/walk 16 pull`. A mu
quotation is a Clojure function (`Quotation` implements `IFn`), so it can be a
fitness function; a `( … )` group of quotations is a list of them (the
constraints of `melody/constrained`); a list in a list is `'[ '[0 1] '[2] ]`.
`N rng/seed` makes the random words after it repeat. Each function's
`:algo {:params …}` metadata (type, range, default, choices, doc) is what the
GUI reads for a control (below); `:in`/`:out` types and `:arity` ride along.

**Live voices follow a function of their inputs** (`musics.mu.voice`,
`musics.mu.inputs`, `musics.mu.live`). Two things play live: a quotation under a
play word (`x '( 5 + ) :up ->`, `| '( [c d e] ) :riff ->`; `retree` gives the voice
another and it goes on at its position), and a word written with `$` inputs,
`: riff ( $onsets $pulses -- notes ) … ;`, started by `5 8 'riff :bass ->` (the quoted
name is held, not run; the voice needs a name; every input a `$`). A word voice's
inputs are `:bass/onsets`, entries of the voice's scope in the tctx (`IdName` in `mu.ebnf` takes
`/input`; it reads as a qualified keyword): `T@` reads one, `7 :bass/onsets T!` stores it, heard
from the voice's next note and checked against the input's spec when the voice has one
(`inputs/put!`: type, range, choices). `musics.mu.inputs` keeps what is not in the tctx, the order
of the stack effect's names and a spec per input, and watches the tctx for a change of one of
them; the values are the scope's, which stay when the voice ends, and a voice that has no entry of
its own for an input reads the shared tctx's. An onset voice reads its inputs through the lookup
it is given at each leaf (`[scope tctx]`, so an input may be a function of time or a computed key);
`musics.mu.voice/follow!` registers a wall fn in `musics.wall` under
the voice's id that plays the next leaf of what the run gives, per-voice cursors
kept, re-registering when an input changes (the engine notices on the next note) --
a short result starts over, a run that fails keeps the last good material and says
why. A spec is that of the algo parameter of the same name among the loaded
`musics.algo.*` namespaces (`live/param-spec`: the widest range when several algos
read the name, owned by the algo only when one does; no match: a spec from the
input's value). `settings` and `gui` (`musics.gui.params/open-inputs!`, a control
per input under the algo it belongs to) read them; the Wall window lists every
voice the same way; `:bass ->` starts it again with the inputs as they are; the
inputs of a voice that has ended are refused and forgotten (`inputs/alive!`).

**A voice can be a function of onset** (`musics.compose/source`,
`musics.events/walk-source`, `musics.mu.live/play-onset-word!`). A word with `$onset`
in its stack effect, `: hoke ( $onset $phase $side -- leaf ) … ;`, started like a
live word (`0.0 -1 'hoke :a ->`; the stack gives the other inputs, `$onset` is the
voice's own and has no slider), is asked by `musics.events` for **one leaf at the
onset the voice has reached**: the walk plays it, and the onset it leaves is asked
next, lazily, as far as the stream is read. There is no skeleton, wall fn or
cursor for such a voice. Every lookup made for a leaf, producing or resolving it,
is made at that leaf's onset: `walk-source` hands the source a memoized lookup
(`context/getter`) bound at the walk's `:onset`, `resolve` reads the contexts at the
same `:onset`, and the other inputs are read as they are at that leaf
(`:voice/input` `!` is heard on the next leaf). Under `^{ !tempo:120 }` it plays as
`[ctx source]`; it starts now (`->`, not `-|>`) or `1/8 later` (`'hoke $ctx 1/8 later :B ->+`: a start
delay in whole notes, taken beside the context, at the tempo it plays under; the voice's onset counts
from its own start, and the voices one input starts share one start moment, `eval/*start-moment*`, so
the delay is exact), and a start that fails is refused
before any sound. The keyword `:onset` is answered too, so a word reads the onset as
`:onset`. See `doc/md/pull-voice.md` and `doc/md/hoketus-dynamic.md` for the
call order, and `mus/hoketus2.mus` for a piece written this way.

**The tctx and keyword arguments.** While a voice (or a computed key) runs, a *lookup* is in
force (`eval/*lookup*`, bound by `eval/with-lookup`; unbound at the prompt, so nothing below happens
there). Under it, a word that is a Clojure function takes, for each of its arguments that is a keyword
the lookup holds, that value: `:onsets 8 euclid` is `3 8 euclid` while the tctx holds `:onsets 3`,
`:pass :reps /` divides two settings, and `:onset` is the onset. The rule is deliberately implicit and
has edges: (1) it applies to Clojure-function words only, not to stack words (`dup`, `T!`), `: … ;`
words' `$` inputs, or quotation bodies' own words once a stack word has taken the keyword; (2) a
keyword the lookup does not hold goes through (`:hoketus R@`), and so does one the function's own
keyword tail reads (`3 8 :rotation 2 euclid`) -- but if the tctx *does* hold a key spelled like an id,
that keyword is replaced, so keep setting names distinct from stored ids; (3) what lies under the
arguments is untouched. Where the rule does not reach, read a setting with `T@@`. `T!`
(`8 :steps T!`; a quotation, `'( :steps 2 * ) :twice T!`, is a computed key: it runs at the onset of
the asker, with `:onset` and every tctx key as keywords), `T@` (`:steps T@`: what is stored, a
computed key as its quotation, not run), `T@@` (`:twice T@@`: the value, a computed key run) and `T_`
(`:steps T_`: take a key out) fill, read and erase the session's tctx
(`musics.registries/tctx`, one `Context` for the session, also the repo's `:CONTEXT` node `:tctx`: `musics.mu.live/tctx-node!` puts it there). A saved session keeps it (`musics.persist`): plain values as they are, a computed entry as its quotation's printed text, read again when first asked, so the words it uses may be restored after the repo; a function of time is not saved. Loading fills the same object, never replaces it, so a voice already playing sees the loaded values.

**A voice's scope in the tctx.** A key written with the voice before the slash (`5 :bass/steps T!`)
is a qualified keyword in the same table (`musics.registries/tctx`), so a saved session and the
GUI's Settings window hold it with no change. `context/tctx-scope tctx voice` is a *view*: a
Context whose keys are the entries of that voice, under their own name (`:steps`), read from the
table when asked. A voice that is a function of onset starts with `[scope tctx]` as its `Source`'s
`:tctx` (`live/tctx-scope`, `events/walk-source` takes one Context or a vector), so a lookup finds
the voice's value first, then the shared one, and a computed key written once for all (`:twice`)
reads what it asks (`(get :steps)`) through the asking voice's chain. `T@` gives the voice's entry,
else the shared one; `T@@` runs through `[scope tctx]`; `T_` takes out the voice's own. In mu,
`:voice/name` is the qualified keyword, always (an input of a word voice is such an entry too, see
"Live voices"). Design and open points: `doc/md/why/scoped-tctx.md`.

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
`:repeat n` the choruses as `repeat n [...] volta [[turnaround] [tonic]]`.
`musics.algo.harmonic.walking-bass` walks a quarter-note bass under a
seq of chords: the root on each chord's first beat, the beat before
the next an approach to its root (a half step, a scale step or its
fifth), the beats between on the chord's scale, every step at most a
fifth, drawn back towards the middle of its range; `:choruses` new
seqs joined end to start, `:end` the last chord held. `wb/->mus id`
reads a part (`form-of`: a seq, or one repeat with its volta endings,
walked in the same form) and writes `[bass: !i:acousticBass ...]`;
then `(play #{id :bass})`. In mu the word `walking-bass` ( chords [n] --
bass ) makes it from the seq on the stack as it runs, every pass of a
repeat walked anew: with n as long as the chords n times, each walked
anew, the last chord held (`x 4 times x 4 walking-bass {2} ->`); without,
an ostinato leading back into its own start, to loop (`x dup
walking-bass {2} [~] >`). Both draw from
`musics.algo.numeric.random`, so a seed repeats them.

### Species counterpoint: `musics.algo.counterpoint.jeppesen`

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
steps to a fact or an action, or why an action can't be taken yet.
`musics.assist` works it out
from facts: `actions` is a table of REPL actions (`:needs`, `:gives`,
`:call`, `:var`); the facts are the state read now (`:committed`
`:connected` `:playing` `:paused` `:live`) plus history -- the needs
and gives of the actions in `musics.registries`' activity log (30
entries, cleared by `reset`) that no state shows (`:played`,
`:rendered`). core.logic relates them (`achieveo` plans, `missingo`
why-not), shortest plan first, ties to the plan redoing least. Every
action's `:var` calls `musics.registries/log!` (`test/musics/assist_test.clj`
checks the source). The GUI enables, colours and tooltips its transport
buttons from the same facts (`musics.gui.core/action-button`), and colours
the panel opener for the next step.

mu's lookup words read the word after them (`musics.mu.eval/name-asked-for`),
as Forth's `see` does: `help dup`, `see dup`, `about tempo`, `marks tempo`,
and `settings tempo` when no voice (keyword or integer) is on top.
`musics.mu.about` answers `about` with one sentence for each thing the name is
(a musical parameter, a property of a note, a mark, a word, an instrument, a
drum, a kit, a scale, a chord quality, an articulation, a grammar rule), and
where it can appear is read from the grammar (`homes`: the brackets `^{ }`,
`{ }`, `[ ]` that allow the rule directly). `settings-text` gives a
parameter's range, default and how it is written; `marks-text` the marks that
set it, by value.

### Composing vs. performing: `musics.compose`

`musics.compose` (`src/musics/compose.clj`) holds the play-arg mini-
language's own Form-shape grammar — `tagged-form?`/`split-tag`/
`resolve-form-tag`/`par-form?`/`par`/`form-tag+items`/
`peel-group-contexts`, plus `live-repo`/`build-chain`/
`mean-pitch-rank`/`form-pitch-source`/`rank-segments`/
`top-level-voices` — and the mini-language's two previews. `display`
shows which voice `play` would give which material, as musics text: one
seq per top-level voice, labelled as `play` names it, each `{ }`/`#{}`
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
requires only `musics.domain.*` (and `musics.algo.numeric.random` for `play-order`,
`musics.input.reader.leaf-parser`, to spell
notes) — never the engine — so the dependency runs exactly one way.
A purely-functional grammar+preview layer has no business in the
real-time playback engine. `compose/source` (a `Source` record, `{:f :tctx :ctx}`)
is a Form of its own, a voice fed by a function of onset; `events` walks it
(`walk-source`) and `engine/play` accepts it, but `display`/`display-timed` do not
know it. See `doc/md/why/decisions.md` for the full
reasoning behind the split (including why this ISN'T a third tier
alongside Material/Sound/The playground — it's a sub-piece of tier 3,
the part of the play-arg mini-language that's execution-agnostic, not
a new architectural layer).

### Performance as data: `musics.events`

`musics.events/events` (`(events repo form)`; mu's `musics.mu.material/
events` for a value) is what `play` would perform, as a lazy, time-ordered seq of maps: `(events
form)` or `(events form :algo name)`, the same Form `play` takes. Each
carries `:t` (seconds), `:onset` (structural time, exact) and `:path`
(the voice, named as `play` names it), and a `:kind`: `:note`/`:drum`/
`:rest` (a `resolve-event` MidiEvent; a note's `:channel` is left to
the consumer), `:section` (`:id :type :phase`), `:bar` (`:n`, at the
end of the note that crossed), `:mark` (`:count :n`) — the boundaries
the engine signals to `musics.conductor`, here as data.

The walk threads a voice's position as a plain state map (`:repo` its
snapshot, `:path`, `:algo`, `:t`, `:onset`, bar/mark counts) with a
continuation per step, so nothing is walked past what is read: `:count
:infinite` and live generators are fine with `take`/`take-while`. A
`:PAR`/`#{}` merges its branches by `:t` and then continues where the
branch that ends last stopped (`display-timed` follows the same rule).
Wall fns run at the container's children and then at each leaf, so
reading events moves a live voice's cursor. A `Source` form (`compose/source`,
`walk-source`) is the other way a live voice plays: it is asked for one leaf at
the onset the walk is at, and `fire` resolves that leaf at the same `:onset`. `:voice` events
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

- Ties: a tied note holds one note open per voice (`:open`, scheduled in `schedule!`). The
  note after a tied one -- a note, or a Rest with `:tied` -- is a continuation: no note-on,
  its pitch unused. The one that is not tied ends it with a note-off carrying the box of
  the note struck, so the remembered pitches are released; a rest with nothing open is
  silence, and a voice ending closes what it holds. `musics.midi.file` does the same.
- Channels: `musics.midi.channels`, a pool shared with
  `musics.midi.file` -- a channel carries one timbre, `[program cc]`
  (`cc` `{10 pan}`), voices playing exactly that sharing it, taken when a
  voice's note first needs one and released when the voice ends. A voice
  whose program or pan changes stays on its channel while it is alone
  there, sending only what changed (a program change, a CC 10) before
  the note -- so a pan ramp is one channel and its CCs, and a note still
  sounding there follows the pan -- and one sharing it moves to a
  channel carrying the new timbre, else a free one. 15 timbres at once,
  a 16th drops its notes rather than take a channel (`channel-for!`).
  A drum sounds on channel 9 and takes no pan.
  Under `!i:40,73,0` (or a note's own `\i:40,73`) its `:program` is a vector: the note sounds
  once per program at the same moment, each a layer of the voice with
  its own channel (`[path layer]`); `musics.midi.file` renders it the
  same way.
- `stop!` drops everything (note-off for sounding notes, an `:exit`
  signal for every section still open); `play` does the same first;
  `play-change path` drops just that voice. `pause!` holds the queue
  (sounding notes keep sounding); `resume!` moves every pending action
  and stream origin on by the pause.
- `voice-at` returns `{:path :root-path :algo :t :onset}` (`:t`/`:onset`
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

See `doc/md/why/decisions.md`, 2026-09-29/30, for why a forward-only stream
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

### Music holds music

No seq or `^{ }` invokes an algorithm: music text holds music, and an algorithm is a function called by name in mu (see "The algorithms" above).

`musics.algo.common.isorhythm/color-talea` and `musics.algo.common.split/
split-leaf-voice` are two ordinary Clojure functions worth knowing by
name here: `color-talea` combines a color (pitch sequence) and a talea
(duration sequence) into the classic isorhythmic pairing (event `i`'s
pitch is `(nth color (mod i (count color)))`, its duration `(nth talea
(mod i (count talea)))` — the two cycle independently); `split-leaf-voice`
splits a melody into `n` faster, octave-shifted voices, each built from
the previous split so every voice's total duration matches the
original's. Call either directly; in mu the same isorhythm is `zip` over two looped lists,
`'[1/4 1/8 1/8] [~] '[60 62 64 65 67] [~] zip 15 take`.

### Domain model — flat repo, not a tree of pointers

- **Containers are plain maps**: `{:type :SEQ :id :s1 :context ctx :children [...]}`.
  No atoms inside nodes. `:children` holds a mix of inline leaf values and
  keyword ids that must be resolved against the `repo` map
  (`d/children repo container`). Auto-generated ids are short,
  type-prefixed (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`), assigned by
  `builder/next-auto-id`.
- **Leaves are plain, `:type`-tagged maps**: `Pitched` (`:PITCHED`: one pitch a note, several a chord; `note?` and `chord?` ask which), `Rest`, `Drum`
  (pitches/duration/articulation/dynamic/modifiers/tied; all three derive from `:LEAF` in `d/leaf-types`, and `d/leaf?` asks for any of them, `d/pitched?` for the first), plus the **skeleton** (`:LEAF` itself: `d/skeleton`, `:duration` and a `:tied`
  when needed, an optional `:value` from the grid slot it came from -- no pitch
  at all; the half product a time-based generator makes, e.g.
  `musics.algo.common.pulse/grid->pulses`, and `p<Duration>` in text, whose `value`
  is a fixed `1`). mu's `zip` makes of a skeleton in the duration column a `Pitched`
  (a pitch gives a note or a chord), a `Drum`, or a `Rest`, copying its tie; a
  skeleton is never played as it is. Text spells it `p16~` (`p` a pulse, `~` its tie), a mu value that reads, prints and `zip`s onto a seq (a pitch makes it a `Pitched`, a drum key a `Drum`). A tie is written `~` on a rest too (`r16~`).
  `Iterator` (a real record, deferred
  expansion for `repeat`/tremolo, holding a `:source` container +
  `:params`) is the one exception to "plain map."
- **Transient containers** (`:TRANSPOSE`/`:REVERSE`/`:DECORATED`, i.e.
  `transpose`, the row forms, `shuffle`/`rotate n ( … )` and a grace
  decoration) are notationally
  invisible: `builder/pop-container` splices their `:children`
  straight into the parent and never registers them under an id at all
  -- no separate container survives in the tree. They are bare-word
  commands, LilyPond's names without the backslash (`transpose c d [
  c8 d8 e8 ]` or `transpose 5 [ … ]` by semitones, `retrograde [ c8 d8
  e8 ]`), taking an `OpBody` (`[ ]`) body, spliced into the line; see
  the bracket table below. A tuplet is a count before a group, with no
  word: `3:2[ c8 d e ]`, three in the time of two (`:TUPLET`, also
  transient), every note inside at 2/3 of its written value and keeping
  that factor as `:ratio`, so the printers write the group back; a
  single note can carry its own factor, `c8*2/3`, inherited by later
  notes that omit their own Duration until an explicit new one appears.
  A note value is a power of two, dotted or not (`c3` is refused). See
  `doc/md/why/decisions.md`, 2026-10-11. A transpose or row form changes its body now, while parsing;
  `repeat`, `shuffle [ ]` and `rotate n [ ]` are parts that play that way each time.
  The row forms are `retrograde`, `inversion` (around the first
  note), `retrograde-inversion` and `mirror` (around the mean),
  with an optional transposition (`inversion 5 [ … ]` is I5); the names are written out, no letters. The retrograde turns ties round and
  writes what the body's instructions change into each note as its
  override (the value it has where it's written), so it sounds
  backwards; jumps are refused. `shuffle ( … )` and `rotate n ( … )`
  reorder once, now. `transpose` and the row forms reach
  into their whole body (`builder/deep-children!`): a nested seq or
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
- **A context key is a string, a tctx key a keyword** (`musics/domain/context.clj`).
  A context stores `"volume"`, `"Tempo"`, `"density"`; there is no `(name key)`
  normalization: `ctx-append`, `ctx-invalidate` and `ctx-generate` refuse a keyword,
  and a keyword asked of a chain reaches only the contexts that hold keywords. A
  **tctx** is an ordinary `Context` whose keys are keywords (`T!`):
  whoever builds a chain puts it in front (`[tctx voice … ROOT]`, or `[scope tctx voice … ROOT]`
  for a voice with a scope, below;
  `:ROOT` and the rest are as they were), and the two kinds of key never meet, so it
  hides no context value and `"density"` (ROOT's) and `:density` (a tctx's) are two
  keys. Its values are bare values, `Generator`s (a function of the context's local
  time, `generator`/`ctx-generate`, also usable on a context's string key: a
  volume arc) or `Derived` entries (`T!` with a quotation, `context/tctx-derive`, `(f time get)`): `get` asks the
  chain for another key, a string or a keyword, at the same time, so a lookup is
  **recursive**, a key computed once per pull, a cycle an error naming it
  (`:a -> :b -> :a`). `context/getter chain time` is one memoized pull: every answer,
  a miss too, is remembered, so a leaf sees one value for each key; make one for each
  leaf. mu writes a whole tctx as `@{ :key value … }` (`v/Settings`, `Settings` in `mu.ebnf`): `:tctx R!` of one is `live/set-tctx!` (every entry replaced in one `reset!`, a quotation a `Derived`), `:tctx R@` is `live/tctx->settings` (keys in order; an entry with no text is left out). A tctx is shared by atom, so editing it (a GUI slider, `T!`) is seen at
  the next lookup by every chain that holds it. A derived entry's time is the one
  its context was asked at: right for a tctx at offset 0 in front of a chain, not
  checked for an extracted context with another offset.
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
  standalone/extracted leaf too. See `doc/md/why/decisions.md` for why the
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

`indispensability` (`musics/algo/rhythmic/indispensability.clj`) computes Barlow
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
`musics.algo.rhythmic.indispensability` rather than keeping a second copy of the
algorithm itself — the earlier standalone `musics/algo/` port of this same theory
(`psi`/`psi-fractions`, from pymusics' `indispensability.py`) turned out to
skip the base-table substitution step entirely, agreeing with this
implementation only for factors 2/3 and silently diverging for 5/7; it was
removed in favor of this one rather than kept alongside it. Bar-length
itself (for `musics.conductor`'s `:bar` signals) only needs `num`/`den`, not
indispensability — the two are independent consumers of the same `Meter`.

`musics.algo.rhythmic.indispensability` also carries an "adherence" layer on top
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
`musics.algo.numeric.random/shuffle`, `musics.algo.common.rotate/rotate`) before finally
handing the result to `musics.algo.common.pulse/grid->pulses` or
`density-grid` — no dedicated pipeline mechanism needed, since a
normalized weight vector is just a plain vector. Two distinct
reshaping mechanisms consume `adherence`, genuinely different in kind,
not just curve shape: `tilt` is a softmax (temperature-
scaled by adherence, with a tiny irrational position-based tie-break
term so it never collapses to an exact uniform tie at adherence=0) —
`exp(anything)` is always positive, so it never assigns a pulse
literal zero probability, however extreme adherence gets.
`power` instead raises the normalized weight (or, for
negative adherence, its complement `1 - weight`) to an adherence-driven
exponent — always order-preserving (or order-reversing), never
re-ranks by blending, and CAN assign a pulse exactly zero probability
(whichever position's own base is exactly `0`). `density-grid` is a
separate, orthogonal control — deterministic top-K thinning of a
meter down to its N% most indispensable pulses (a binary `0/1` grid,
same shape every other `musics/algo/rhythmic/` generator produces) — governing
how MANY pulses sound, not how strongly rank predicts which ones do.

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
