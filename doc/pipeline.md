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
(require '[musics :as m])
```

Everything from here on is called through `m/...`. `(m/help)` lists every
public command with a one-line summary; `(m/help "parse")` prints a
specific one's full docstring.

## Your first piece

```clojure
(def r (m/parse "{verse: !mf c4 d4 e4 f4}"))
(m/commit! (:sid r))
(m/play-latest!)
(m/connect)
(m/play :verse)
```

Five lines, five distinct steps — worth understanding each one, because
this shape (parse → commit → point playback → connect → play) is the
shape of everything else in this guide too.

## The core idea: parsing stages, it doesn't apply

`(m/parse text)` does **not** make anything visible or playable by
itself. It reads your text against whatever's currently committed,
figures out what's new or changed, and *stages* it under a fresh id
(`sid`) — invisible to everything (`find`, `play`, `inspect`, ...) until
you explicitly commit it:

```clojure
(def r (m/parse "{verse: c4 d4}"))
(m/find :verse)        ;; => nil -- staged, not committed yet
(m/pending (:sid r))   ;; => {:verse #object[...]} -- what committing would apply
(m/commit! (:sid r))
(m/find :verse)        ;; => now it's there
```

If you don't want it after all, `(m/abort! (:sid r))` discards it —
nothing was ever visible, so there's nothing to undo.

A single `(parse ...)` call can define more than one part at once, and
they commit together as one atomic batch:

```clojure
(def r (m/parse "{melody: c4 d4 e4 f4} {bass: c3 c3 c3 c3}"))
(m/commit! (:sid r))   ;; :melody and :bass both become visible together
```

**Committing still doesn't make it audible.** That's a second, separate
knob — see "Playing it back" below. This split (stage → commit → make
audible, as three genuinely separate steps) is what lets you prepare an
edit mid-performance without it glitching whatever's currently sounding —
see "Live coding" further down, which is the whole point of it.

## Writing music: a syntax tour

### Notes, octaves, durations

```
c4          quarter note (default duration if omitted, chained from context)
c4 d8 e16   quarter, eighth, sixteenth
c4.         dotted quarter
c4~ c4      tied across two notes
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
{ ... }      Sequence  -- one voice/line
<< ... >>    Parallel  -- simultaneous parts
( ... )      Unit      -- grouped elements, shares its enclosing
                          container's context (no context of its own);
                          lets an algorithm reorder elements while
                          keeping a group glued together
```

### Ids and references

```clojure
(m/parse "{verse: c4 d4 e4 f4}")     ;; name: registers an id
(m/parse "{song: :verse :verse}")    ;; :name looks it up -- resolves once
                                     ;; :verse is committed, not before
```

### Key, tempo, meter

```
!key:C.major       key
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

See `CLAUDE.md`'s "Meter and indispensability" section for how a meter's
grouping (explicit or defaulted) feeds Barlow indispensability
computation, if you're doing anything generative with pulse weighting.

### Tuplets, repeats, tremolo, grace, ornaments, slurs

```
\tuplet 5/4 { c8 d8 e8 f8 g8 }        genuine quintuplet -- any ratio
                                       works, independent of the
                                       prevailing meter entirely
\repeat volta 2 { c4 d4 }              plain repeat
\repeat unfold 4 { c4 d4 }              unrolled repeat
c4:32                                  note-level tremolo
\acciaccatura c16                      grace note
c4\trill                               ornament (17 available, see
                                        doc/parsing.md for the full list)
c4 !( d4 e4 f4 !) g4                   slur start/end
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
% line comment
%{ block comment %}

motif = c4 d4 e4
{melody: \motif f4 g4}
```

## Inspecting what you've built

Every one of these defaults to the latest committed tx, and accepts an
optional trailing `tx` to look at any point in history instead (more on
that under "Time travel"):

```clojure
(m/ids)                    ;; every registered id
(m/find :verse)            ;; the raw container/leaf
(m/inspect)                ;; session overview
(m/inspect :verse)         ;; a specific part's structure
(m/children :verse)        ;; direct children, keyword refs resolved
(m/leaves :verse)          ;; just the pitched leaves
(m/ctx :verse :volume 0.0) ;; sample a context value at a given time
(m/describe :verse)        ;; abbreviated structural report
(m/print-structure :verse) ;; pretty-printed, using the surface grammar's brackets
(m/locate :verse [0 1])    ;; navigate a path of index/id selectors
```

## Playing it back

```clojure
(m/connect)                        ;; open MIDI, wire up the engine (once)
(m/play :verse)                    ;; single part
(m/play :verse1 :verse2)           ;; sequentially
(m/play [:par :melody :bass])      ;; polyphony -- forked onto separate
                                    ;; MIDI channels
(m/stop!)                          ;; halt
(m/pause!) (m/resume!)             ;; a sounding note is held in place,
                                    ;; not re-triggered, across pause/resume
(m/all-notes-off)                  ;; silence everything immediately
```

`play`'s arguments are a small mini-language — a bare keyword is a single
part; a vector is a group, `:par`/`:seq` tagged (defaults to `:seq`);
groups nest. See `core.engine.async-engine/play`'s own docstring for the
full grammar, including leading context-refs.

`(m/connect)` reads through `core.repo/play-tx`, not a snapshot — so a
later commit *and* an explicit `(play-tx!)`/`(play-latest!)` call are
picked up live, without reconnecting.

## Live coding: mutating a piece while it plays

This is the feature everything above was building toward. Because
staging, committing, and "what's actually playing" are three separate
steps, you can prepare a change mid-performance two different ways —
`test/pipeline_test.clj` is a full runnable, tested example of both, side
by side, on the same material.

**Direct — cut over right now:**

```clojure
(def r (m/parse "{melody: g4 a4 b4 c5}"))  ;; redefine an existing part
(m/commit! (:sid r))                       ;; committed, but not playing yet
(m/play-latest!)                           ;; ...cut over instantly
```

**Scheduled — prepare it, let playback trigger it exactly when you want:**

```clojure
(m/commit! (:sid r))
(m/schedule-tx! :melody :exit :latest)   ;; the next time :melody's own
                                          ;; section finishes, cut over
                                          ;; automatically -- :latest
                                          ;; resolves at that moment, not now
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
"move `play-tx`." See `CLAUDE.md`'s "Conductor" section for the full
signal shapes (`:id`/`:phase` for each kind) if you want to hook `:bar`
or `:mark` directly.

## Time travel

Since `core.repo` never overwrites anything, every inspection function
can look at any point in history, not just the latest commit:

```clojure
(m/history :verse)      ;; every [tx node] ever committed for :verse
(m/as-of :verse 3)      ;; :verse's value as of tx 3
(m/ids 3)               ;; every id that existed as of tx 3
(m/children :verse 3)   ;; :verse's children as of tx 3
```

Playback's own position (`play-tx`) is completely independent of this —
see "Live coding" above.

## Persistence

```clojure
(m/write "session.edn")          ;; the whole committed history, as of
                                  ;; the latest tx (or an explicit one)
(m/load "session.edn")           ;; replaces history wholesale, points
                                  ;; playback at it
```

## Importing LilyPond

```clojure
(m/from-ly-to-me "/path/to/piece.ly")   ;; best-effort conversion, writes
                                          ;; a sibling .mus file
(m/parse (slurp (m/from-ly-to-me "/path/to/piece.ly")))
```

Doesn't touch the current session on its own — load the result yourself.
See `input.reader.lilypond-import` for what's handled and what's known to
be out of scope (markup, lyrics, engraving overrides).

## Starting over

```clojure
(m/reset)   ;; wipes session, variables, MIDI connection, and all
             ;; committed/staged core.repo history -- a genuinely fresh start
```

## Where to go next

- **`CLAUDE.md`** — the architecture underneath everything above:
  `core.repo`'s versioned store, `core.conductor`'s signal/schedule
  design, the flat domain model, Barlow indispensability, and a "Known
  rough edges" section worth reading before you go looking for a bug that
  might already be a known one.
- **`doc/domain.md`** — full domain-model reference (Context/Envelope,
  container shapes, the transform functions).
- **`doc/parsing.md`** — full grammar/syntax reference.
- **`doc/LilypondToMuCheatSheet.txt`** — a dense, example-driven cheat
  sheet, especially useful if you already know LilyPond.
- **`test/pipeline_test.clj`** — a complete, tested, runnable example of
  the full stage → commit → play → mutate → cut-over cycle.
