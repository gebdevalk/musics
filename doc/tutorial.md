# A guide into `musics`

`musics` is a Clojure DSL for writing music as text and hearing it played
back live, in real time, from the REPL. This is a practical, top-to-bottom
walkthrough of actually using it — write a piece, play it, inspect it,
change it while it's still sounding. For the architecture underneath any
of this (why it's built the way it is), see `CLAUDE.md`; for a dense
notation reference, see `doc/parsing.md`; coming from LilyPond, see
`doc/lilypond.md`; for the full domain model, see `doc/domain.md`.

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

The essentials; `doc/parsing.md` has the full notation.

### Notes, octaves, durations

```mus
[c4 d8 e16 f]        % quarter, eighth, sixteenth; f keeps the sixteenth
[c4. d8 e2~ e8]      % dotted quarter; a tie
[C4/4 G4/4 C5/2]     % absolute pitches: uppercase + octave digit (+ / before a duration)
[c4 f# b& cn']       % accidentals # ## & && n; ' and , move an octave
```

A lowercase letter is always relative: the nearest pitch to the previous
one (a fourth or fifth away at most), starting from C4. An uppercase
letter is absolute. A digit after a lowercase letter is a duration,
never an octave — `c3` is a C lasting 1/3 of a whole note — so move
octaves with ticks (`c,`) or write an absolute pitch (`C3/4`).

### Dynamics

```mus
[!mf c4 d e f]       % a dynamic instruction
[c4\f d e\< f g\mf]  % glued to a note: from that note on; \< starts a crescendo
```

### Chords, rests, drums

```mus
[<c e g>4 r4 r <d f# a>2]    % chord, rest, rest (previous duration), chord
[x8 x\kick x4\36]            % drums: plain, by name, by MIDI number
```

### Sequences and parallel parts

```mus
[melody: c4 d e f]                     % [ ]  one line after another
{duet: [sop: c'4 d e] [alto: e4 f g]}  % { }  simultaneous parts
```

Both can carry a name (`melody:`) that registers them as a part, and
`:name` uses a registered part inside another:

```mus
[verse: c4 d e f]
[song: :verse :verse g2]
```

### Key, tempo, meter

```mus
[!key:D.major f4 c]            % F# C#: a bare letter takes the key's accidental
[!acc:explicit !key:D.major f4] % F: every bare letter literal
[!tempo:120 !Meter:7/8 c8 d e f g a b]
[!tempo:3/8=90 !allegro c4]    % note value = BPM; a named tempo
```

### Tuplets, repeats, grace notes, ornaments, slurs

```mus
[c8*2/3 d e f4]              % a triplet: the *2/3 carries on until a new duration
\repeat volta 2 [c4 d e f]
\repeat unfold 4 [c8 d]
[\acciaccatura c16 d4 e]     % a grace note
[c4\trill d\mordent e( f g)]  % ornaments, a slur
[c4:32]                      % a tremolo
```

### Bar lines

```mus
[c4 d e f | g a b c' || c1]
```

Zero-duration markers — but each fires a `:mark` signal during playback
that you can hook actions on; see "Hooking into playback" below.

### Comments and variables

```mus
% a line comment; %{ a block comment %}
motif = [c4 d e]
[melody: \motif f g \motif]
```

A variable is defined at the top level, before use, and its notes are
spliced in where it's referenced; an instruction inside it (`!f`) takes
effect there and continues after.

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
(m/play #{:melody :bass})          ;; polyphony -- #{} is ALWAYS parallel
                                    ;; (the text notation writes it { }),
                                    ;; forked onto separate MIDI channels, each
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
(`play-change path new-form :algo name`), or re-register what the SAME
name resolves to (a change to its tctx, or `t/retree!`, below) -- every voice already pointing
at that name picks up the rebuild on its very next node, with nothing
about the voice itself touched. `(m/algo-assignments)` reads back
whatever's currently PREPARED (not what's currently playing). See
`CLAUDE.md`'s "Wall: per-voice playback algorithms" section for the
full design.

### Feeding an algorithm its own parameters

A `:algo name` in a tag or on `play`/`play-add`/`play-change` is ALWAYS
just a bare, already-registered name. `algo.tree/live!` binds a name to
a tree and a tctx (an atom of the tree's settings); every change to the
tctx is heard on the next note by every voice following the name:

```clojure
(require '[algo.tree :as t] '[algo.tree.lib :refer [transpose]])
(def up (t/tctx (transpose :nodes) {:semitones 5}))
(t/live! :up5 (transpose :nodes) up)   ;; a transform: binds the name only
(m/play :melody :algo :up5)
(t/setp! up :semitones 7)         ;; heard on the next note
```

See `doc/algorithms.md` for composing trees. A bare Name that was never
registered plays as-is (identity), never throws.

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
(m/ly-to-mus "/path/to/piece.ly")       ;; best-effort conversion, writes
                                         ;; a sibling .mus file, returns its path
(m/parse (slurp (m/ly-to-mus "/path/to/piece.ly")))
(m/play-ly-file "/path/to/piece.ly")    ;; convert + parse + play, in memory
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
- **`doc/lilypond.md`** — LilyPond to musics, construct by construct.
- **`doc/setup.md`** — MIDI output (Fluidsynth/qsynth/VirMIDI) and MIDI
  input (a real keyboard, `midi-through`/`record-midi`) system setup.
- **`test/pipeline_test.clj`** — a complete, tested, runnable example of
  the full parse → play → mutate → cut-over cycle.
