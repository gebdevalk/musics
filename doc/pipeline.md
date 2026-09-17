# A guide into `musics`

`musics` is a Clojure DSL for writing music as text and hearing it played
back live, in real time, from the REPL. This is a practical, top-to-bottom
walkthrough of actually using it — write a piece, play it, inspect it,
change it while it's still sounding. For the architecture underneath any
of this (why it's built the way it is), see `CLAUDE.md`; for a dense
syntax cheat sheet, see `doc/LilypondToMuCheatSheet.txt`; for full domain-
model reference, see `doc/domain.md`.

This is a REPL-driven project, not an app with a CLI — everything below
happens by evaluating forms interactively.

## Setup

Parsing text into the domain model and running tests needs nothing beyond
a JVM and Leiningen. Hearing actual sound additionally needs Fluidsynth +
qsynth + a virtual MIDI port set up once — see `doc/setup.md` and
`doc/startup.md` for that. Everything in this guide up through "Playing
it back" works without any of that; you'll just get silent MIDI-shaped
data instead of sound.

Start a REPL and load the API:

```clojure
lein repl
(require '[musics.core :as m])
```

Everything from here on is called through `m/...`. `(m/help)` lists every
public command with a one-line summary; `(m/help "parse")` prints a
specific one's full docstring.

## Your first piece

```clojure
(def ids (m/parse "[verse: !mf c4 d e f]"))
(m/connect)
(m/play :verse)
```

Three lines, three distinct steps — worth understanding each one, because
this shape (parse → connect → play) is the shape of everything else in
this guide too.

## The core idea: parsing commits immediately

`(m/parse text)` reads your text against whatever's currently
committed, and commits the result right away — one atomic swap!, no
separate stage/commit step, visible to everything (`find`, `play`,
`inspect`, ...) the instant the call returns:

```clojure
(m/parse "[verse: c4 d]")
(m/find :verse)        ;; => it's already there
```

The return value is a map, `{:ids [...]}`, the top-level ids this call
introduced or changed:

```clojure
(:ids (m/parse "[verse: c4 d]"))   ;; => [:verse]
```

If a parse comes out wrong, it's already committed — there's no
"abort" to undo it. Just parse the corrected text under the same id;
the old value is simply gone the moment the new one replaces it (see
"Live coding" below for how to do this without glitching what's
already sounding).

A single `(parse ...)` call can define more than one part at once, and
they land together as one atomic commit:

```clojure
(m/parse "[melody: c4 d e f] [bass: c,4 c c c]")  ;; bass's `,` is a
                                     ;; relative octave-down tick (see
                                     ;; "Notes, octaves, durations" below) --
                                     ;; a bare digit after a lowercase
                                     ;; letter is always a Duration, never
                                     ;; an octave, so bass can't be written
                                     ;; c3 c3 c3 c3 the way it might look
                                     ;; -- :melody and :bass both become
                                     ;; visible together
```

**Committing still doesn't make it audible.** That's a second, separate
knob — see "Playing it back" below. This split (commit vs. make
audible, as two genuinely separate steps) is what lets you prepare an
edit mid-performance without it glitching whatever's currently sounding —
see "Live coding" further down, which is the whole point of it.

## Writing music: a syntax tour

### Notes, octaves, durations

```
c4          quarter note (default duration if omitted, chained from context)
c4 d8 e16   quarter, eighth, sixteenth
c4.         dotted quarter
c4~ c       tied across two notes
```

Absolute vs. relative pitch is decided by the **case of the first
letter** — always, with no exception for a sequence's first note:

```
C4 d e f    C4 is absolute (octave 4); d/e/f are lowercase -> relative,
            each resolved as the nearest fourth/fifth from the previous
            pitch (LilyPond \relative-style)
c d e f     lowercase c with no preceding pitch resolves relative to an
            implicit default of C4
```

Octaves can also be written as ticks instead of a digit: `'` up, `,`
down (`c'` = octave up from the previous pitch, `c,,` = two octaves
down). Accidentals are `#`/`b`/`##`/`bb`, or the Dutch (nederlands)
suffixes read directly (`cis` = `c#`, `des` = `db`, `ceses` = `cbb`, ...)
— see `doc/LilypondToMuCheatSheet.txt` for the full table.

### Dynamics

```
!mf              standalone dynamic instruction
c4\f             glued directly onto a note -- takes effect at that
                 note's own onset, same as a standalone !f just before it
c4\<             crescendo hairpin start, glued
c4\mf\<          chainable: sets volume to mf right here, then starts a
                 real crescendo from that value
```

### Chords, rests, drums

```
<c e g>4     C major triad, quarter note
r4           quarter rest
r            rest, previous duration reused
x8           eighth-note drum hit
x\kick       drum with a name modifier
x4\36        drum with an explicit MIDI note number
```

### Sequences, parallel, grouping

```
[ ... ]      Sequence  -- one voice/line; also reused as-is for
                          times/tuplet/transpose/repeat's own body and a
                          VarDef's value (name = [ ... ]) -- the walker,
                          not the grammar, decides whether a given [ ]
                          gets registered as a real container or
                          spliced/stashed instead
(par ...)    Parallel  -- simultaneous parts; the ONE registrable
                          Composite among the Lisp calls below (it can
                          carry an Id, e.g. (par chorale: [sop: c4]
                          [bass: c,4])) -- see "Playing it back" below
                          for the play mini-language's own separate
                          `#{ }` spelling of the same duality
```

`( )` means three things in this grammar, disambiguated by position
(and, for the Lisp-call cases, which reserved word follows): a slur
mark glued directly onto a note/chord (`c4( d4 e4)`); the `(par ...)`
call just above; and, everywhere else, a Lisp prefix call for the
TRANSIENT structural commands (`(times 2/3 [c8 d8 e8])`, see "Tuplets,
repeats, tremolo, grace, ornaments, slurs" below) -- no longer
LilyPond-conformant spellings like `\times`/`\repeat`, since this
grammar dropped that goal (see CLAUDE.md's "Repo state" section).
`(par ...)` replaced an earlier `#{ }` bracket spelling for a similar
reason `\times`/etc. dropped their own LilyPond spellings, plus a
narrower, concrete one of its own: a literal Clojure `#{ }` can't hold
the same value twice, which `#{ }` inherited as a pure surface-syntax
accident even though `(par :s1 :s1)` was always meaningful (see
CLAUDE.md's "Wave 7" note).

### Ids and references

```clojure
(m/parse "[verse: c4 d e f]")        ;; name: registers an id
(m/parse "[song: :verse :verse]")    ;; :name looks it up -- resolves once
                                     ;; :verse is committed, not before
```

### Key, tempo, meter

```
!key:C.major       key -- also makes a bare pitch letter with no
                   accidental symbol resolve against that key's own
                   implied accidental from here on (D.major c f -> C#
                   F#); an explicit accidental always overrides it.
                   !accidentals:explicit switches back to literal,
                   LilyPond-style resolution (bare letter always
                   natural, key ignored) -- see CLAUDE.md's "Grammar"
                   pitch paragraph.
!tempo:120         tempo, bare BPM (quarter note implied) -- aliases
                   !Tempo:/!T: all work identically
!tempo:4=120       tempo, LilyPond-style note-value=BPM (quarter=120,
                   same as the bare form); !tempo:3/8=120 for a ratio
                   note-value (dotted-quarter=120)
!allegro !andante !largo !presto ...   named tempo marking -- a standard
                   BPM, same as writing !tempo:<its BPM> (see
                   music-data.clj's tempo-markings for the full list)
!Meter:7/8         divisible meter (bare ratio)
!Meter:"7/8(2+2+3)"   additive meter, explicit grouping (quoted; groups
                      must sum to the numerator)
```

`!Meter:`/`!tempo:`/`!key:` above are the only spelling for any of
these now -- LilyPond's own free-standing `\time`/`\tempo`/`\key` were
dropped from the grammar (see CLAUDE.md's "Repo state" section for
why). `\partial 8` (pickup/upbeat) stays -- it's not a LilyPond-
conformity concession, there's no `!`-prefixed equivalent to fall back
to.

See `CLAUDE.md`'s "Meter and indispensability" section for how a meter's
grouping (explicit or defaulted) feeds Barlow indispensability
computation, if you're doing anything generative with pulse weighting.

### Tuplets, repeats, tremolo, grace, ornaments, slurs

```
(tuplet 5/4 [c8 d e f g])              genuine quintuplet -- any ratio
                                        works, independent of the
                                        prevailing meter entirely
(repeat volta 2 [c4 d])                plain repeat
(repeat unfold 4 [c4 d])                unrolled repeat
c4:32                                  note-level tremolo
(acciaccatura c16 d4)                  grace note
c4\trill                               ornament (17 available, see
                                        doc/parsing.md for the full list)
c4( d e f g)                            slur, LilyPond-style (glued to
                                        the start/end note -- the old
                                        standalone !( / !) spelling was
                                        removed, this is the only form now)
```

### Bar lines

```
|  ||  |||  ||||
```

Structural markers (zero duration) — but not inert. Every one fires an
author-placed `:mark` signal during playback, layered on top of the
automatic section/bar signals the engine fires on its own. See "Hooking
into playback" below.

### Comments and variables

```
; line comment
%{ block comment %}

motif = [ c4 d e ]
[melody: \motif f g]
```

Both are real grammar constructs, resolved as part of parsing itself —
not text stripped/substituted beforehand — so a parse error's line and
column always match what you actually typed, comments and variable
expansions included. A variable's value is always a `[ ]` Sequence —
it just never gets *registered* as an addressable container the way an
ordinary `Sequence` does (a walk-time decision, not a grammar-level one;
an earlier design used a dedicated `Scope`/`( )` bracket to signal this
instead, since removed), and `\motif` splices its notes in directly (not
nested) — an
instruction inside the definition (`!f`, or a note-glued `\f`) takes
effect from there and keeps applying afterward, same as writing it
inline would. A variable must be defined before it's referenced, and
only directly at the top level of the file — not nested inside a
`[ ]`/`(par ...)`/`{ }` body (referencing one with `\name` has no such
restriction, and works anywhere). See CLAUDE.md's "Comments and
variables" section for the full design and why.

## Inspecting what you've built

Every one of these reads whatever's currently committed — there's no
history to pin against, only "now":

```clojure
(m/ids)                    ;; every registered id
(m/find :verse)            ;; the raw container/leaf
(m/inspect)                ;; session overview
(m/inspect :verse)         ;; a specific part's structure
(m/children :verse)        ;; direct children, keyword refs resolved
(m/leaves :verse)          ;; just the pitched leaves
(m/ctx :verse)              ;; short-form context chain, :ROOT excluded
(m/ctx-value :verse :volume 0.0) ;; sample a context value at a given time
(m/describe :verse)        ;; abbreviated structural report
(m/print-structure :verse) ;; pretty-printed, using the surface grammar's brackets
(m/locate :verse [0 1])    ;; navigate a path of index/id selectors
```

## Playing it back

```clojure
(m/connect)                        ;; open MIDI, wire up the engine (once)
(m/play :verse)                    ;; single part -- returns a short
                                    ;; track id, e.g. :TAA
(m/play [:verse1 :verse2])         ;; sequentially -- [] is ALWAYS
                                    ;; sequential, same [ ] Sequence
                                    ;; brackets you'd write in text
(m/play #{:melody :bass})          ;; polyphony -- #{} is ALWAYS parallel,
                                    ;; same #{ } Parallel brackets, forked
                                    ;; onto separate MIDI channels, each
                                    ;; voice labeled :TAA/:TAB/... by
                                    ;; ASCENDING MEAN PITCH (lowest -> :TAA)
(m/stop!)                          ;; halt
(m/pause!) (m/resume!)             ;; a sounding note is held in place,
                                    ;; not re-triggered, across pause/resume
(m/all-notes-off)                  ;; silence everything immediately
```

`play`'s argument is a small mini-language — exactly one Form (a bare
keyword is a single part; `[Form+]` is always sequential; `#{Form+}` is
always parallel, groups nest), plus an OPTIONAL trailing `:algo name`.
`play` no longer accepts several top-level forms implicitly sequenced --
`(m/play :verse1 :verse2)` is now `(m/play [:verse1 :verse2])`, matching
the same one-Form discipline every nested level already has. See
`core.async-engine/play`'s own docstring for the full grammar, including
context-refs.

`play` always flushes everything -- every voice anywhere, at any path,
however it got there -- and starts fresh, registering the new voice(s)
under auto-picked short track id(s) (`:TAA`, `:TAB`, ... `:TZZ`) instead
of an explicit path. `play-add` shares the exact same mini-language and
also mints id(s), but never flushes -- it JOINS what's already sounding
instead of replacing it. `play-change` is the third, narrower variant:
supersede only whatever's currently AT a path you pick yourself (its own
older, variadic-args call shape, unchanged), every other path untouched.

```clojure
(m/play-add [:extra :harmony])       ;; join what's already sounding, own
                                      ;; auto id, doesn't touch anything else
(m/play-change :bass :new-bass)      ;; supersede only whatever's AT :bass
                                      ;; right now
```

Either `play` or `play-add` can take an OPTIONAL algorithm too, via a
trailing `:algo name` on the call itself (`nil` for none), or a
`[Form :algo name]` tag anywhere in the tree -- a `algos`-registered name
run on every node that voice plays, assigned before its very first node
runs:

```clojure
(m/play :verse :algo :my-algo)             ;; whole call, one voice
(m/play #{[:a :algo :algo-a] [:b :algo :algo-b]}) ;; each branch its own
```

The return value mirrors wherever `#{}` was actually written, recursively
-- `(m/play #{:melody :bass})` -> `#{:TAA :TAB}`, every id a real,
directly usable top-level path on its own.

A voice's own algorithm is baked in ONCE, at the moment it's minted --
immutable for that voice's whole life, never reassigned afterward.
`(m/assign-algo! path name)` does NOT reach an already-playing voice at
all; it only PREPARES `path` so that the *next* voice minted there
(a `play-change` call with no `:algo` of its own, or a `play`/
`play-add` call that happens to auto-mint into that path) picks `name`
up. To change what's already playing, either supersede it outright
(`play-change path new-form :algo name`), or rebuild what the SAME name
already resolves to (`m/build!`, below) -- every voice already pointing
at that name picks up the rebuild on its very next node, with nothing
about the voice itself touched. `(m/algo-assignments)` reads back
whatever's currently PREPARED (not what's currently playing). See
`CLAUDE.md`'s "Wall: per-voice playback algorithms" section for the
full design.

### Feeding an algorithm its own parameters

A `:algo name` in a tag or on `play`/`play-add`/`play-change` is
ALWAYS just a bare, already-built name — never a place to apply
parameters inline. Every algo, parameterized or not, goes through the
same two-step build first, `params` ALWAYS a plain map, the one
uniform shape every factory takes (see `doc/decisions.md` for why):

```clojure
(m/register-factory! :transpose (fn [name {:keys [n]}] (m/build-algo! name (fn [nodes _ctx _voice] ...))))
                                          ;; 1. park the factory, once,
                                          ;;    permanently -- name is
                                          ;;    the factory's OWN first
                                          ;;    arg, the name its
                                          ;;    result gets stored under
(m/build! :transposed5 :transpose {:n 5}) ;; 2. actually build it -- looks
                                          ;;    up :transpose, calls it
                                          ;;    with (:transposed5 {:n 5}),
                                          ;;    stores the result

(m/play :melody :algo :transposed5)      ;; a bare, already-built name,
                                          ;;    same as any other
```

Each VALUE in `params` (here, `5`) can be an inline literal or a real
`build!` arg resolving against the latest committed repo (a bare
keyword pointing at a `'[ ]` `:DATA` container's own raw values) —
composer's choice per call. `(m/registered :transposed5)` (or
`core.wall/registered`) also remembers `:factory-name`/`:params` for
anything built this way — the recipe, not just the resolved fn.

**Hot-swapping replaces "reconfiguring."** Because factories are
PERMANENT and `build!` always targets an explicit name, call `build!`
again with the SAME name any number of times — every voice/track
currently pointing at it picks up the change on its very next node, no
per-voice action needed:

```clojure
(m/build! :verseColor :colorTalea {:color color1 :talea talea1})
(m/play :verse :algo :verseColor)
(m/build! :verseColor :colorTalea {:color color2 :talea talea2})   ;; hot-swapped
                                                    ;; in place -- :verse
                                                    ;; picks it up on
                                                    ;; its very next node
```

No separate "install once, configure later" step, and no re-
registration needed between rebuilds — `build!` always targets a
factory-name + a target name together, so `(build! :bright :colorTalea
...)` and `(build! :dark :colorTalea ...)` off the SAME factory already
coexist as independent names, no second registry needed for that.

Any resolution failure — an unregistered factory-name, a factory that
throws applying its params, or a bare Name that was never built —
prints a console warning and falls back to playing as-is (identity),
never throws.

A brand-new voice always starts from whatever's currently committed —
`(m/connect)` never needs to be redone after a later commit, and
neither does any subsequent `(m/play ...)` call; see "Live coding"
below for what a voice that's already playing does instead.

## Playing in from a MIDI keyboard

Needs a real MIDI input device — see `doc/setup.md`'s "MIDI input"
section (`./scripts/setup-midi-in.sh`); unlike output, no kernel module
is needed for a real USB keyboard.

```clojure
(require '[input.midi :as midi])
(midi/open-midi "your-keyboard-name")   ;; or (midi/open-midi) for a GUI
                                         ;; picker -- starts midi-through
                                         ;; immediately: play the keyboard,
                                         ;; hear it live through the same
                                         ;; Fluidsynth setup (m/connect) uses

(require '[input.midi-record :as rec])
(rec/open-record)   ;; blocks -- play a phrase, end on a note below C1
                     ;; (this DSL's own C1, MIDI 24) to stop; quantizes
                     ;; and returns the phrase as musics text

(midi/close-midi)   ;; stops midi-through, releases the device
```

`(m/gui)`'s "Record MIDI" panel wraps the same thing: Start, play, Stop
(or the low note), hand-edit the generated text in place, name it,
Write — saves `<name>.mus` to disk (`(m/parse-file ...)` it yourself
afterward, same as any other `.mus` file).

## Live coding: mutating a piece while it plays

This is the feature everything above was building toward. Because
committing and "what's actually playing" are two separate things —
each voice reads through its own private snapshot, captured once at
birth, not the live repo — you can prepare a change mid-performance two
different ways — `test/pipeline_test.clj` is a full runnable, tested
example of both, side by side, on the same material.

**Direct — nothing is playing yet, so a fresh `play` just picks it up:**

```clojure
(m/parse "[melody: g4 a b c5]")    ;; redefine an existing part -- c5 is
                                    ;; a duration change (fifth-note),
                                    ;; not an octave -- committed
                                    ;; immediately
(m/play :melody)                   ;; a brand-new voice always starts
                                    ;; from whatever's current -- no
                                    ;; extra step needed
```

**Scheduled — a voice is ALREADY sounding the old content, and you want
to cut it over at a chosen boundary rather than glitch it mid-note:**

```clojure
(m/schedule-tx! :melody :exit)   ;; the next time :melody's own section
                                  ;; finishes, redirect that ONE voice to
                                  ;; whatever's currently committed --
                                  ;; resolved at the moment it actually
                                  ;; fires, not when it was scheduled
(m/parse "[melody: g4 a b c5]")  ;; commit the edit whenever you like,
                                  ;; before or after the schedule call
```

Either way, nothing sounding gets interrupted or glitched — the old
content keeps playing until the exact moment you (or the schedule) says
otherwise.

## Hooking into playback: the conductor

Three kinds of signal fire during playback, all going through the same
mechanism (`core.conductor`) that `schedule-tx!` above is built on:

- **`:section`** — a container's own start/end (`:enter`/`:exit`).
- **`:bar`** — a voice crossing its own bar boundary, computed from
  whatever `Meter` is in scope. No central authority: each voice counts
  its own bars, so this is per-voice, not "the piece's" bar count.
- **`:mark`** — an author-placed `|`/`||`/`|||`/`||||` bar line.

You can register and fire your own named actions directly, independent of
any of this:

```clojure
(m/register-action! :flash-lights (fn [& _] (println "!")))
(m/trigger! :flash-lights)
```

Or tie one to a boundary:

```clojure
(m/schedule! :verse :exit :flash-lights)   ;; fires once, the next time
                                            ;; :verse's section ends
```

`schedule-tx!` (above) is just this same mechanism with the action being
"redirect one voice's own :view." See `CLAUDE.md`'s "Conductor" section
for the full signal shapes (`:id`/`:phase` for each kind) if you want
to hook `:bar` or `:mark` directly.

## Persistence

```clojure
(m/write "session.edn")          ;; whatever's currently committed
(m/load "session.edn")           ;; replaces it wholesale
```

## Importing LilyPond

```clojure
(m/from-ly-to-mus "/path/to/piece.ly")   ;; best-effort conversion, writes
                                          ;; a sibling .mus file
(m/parse (slurp (m/from-ly-to-mus "/path/to/piece.ly")))
```

Doesn't touch the current session on its own — load the result yourself.
See `input.lilypond-import` for what's handled and what's known to
be out of scope (markup, lyrics, engraving overrides).

## Starting over

```clojure
(m/reset)   ;; wipes session, variables, MIDI connection, and everything
             ;; committed to core.repo -- a genuinely fresh start
```

## Where to go next

- **`CLAUDE.md`** — the architecture underneath everything above:
  `core.repo`'s flat store, `core.conductor`'s signal/schedule
  design, the flat domain model, Barlow indispensability, and a "Known
  rough edges" section worth reading before you go looking for a bug that
  might already be a known one.
- **`doc/domain.md`** — full domain-model reference (Context/Envelope,
  container shapes, the transform functions).
- **`doc/algorithms.md`** — what kinds of algorithm this project
  supports (generators, transformers, filters, ...), which ones `play`'s
  own `:algo` tag can reach and which are plain Clojure calls instead,
  and how to write and hook up your own.
- **`doc/parsing.md`** — full grammar/syntax reference.
- **`doc/LilypondToMuCheatSheet.txt`** — a dense, example-driven cheat
  sheet, especially useful if you already know LilyPond.
- **`doc/setup.md`** — MIDI output (Fluidsynth/qsynth/VirMIDI) and MIDI
  input (a real keyboard, `midi-through`/`record-midi`) system setup.
- **`test/pipeline_test.clj`** — a complete, tested, runnable example of
  the full parse → play → mutate → cut-over cycle.
