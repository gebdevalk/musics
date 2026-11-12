# Start here

`musics` has many documents. You do not need most of them at once. Find
the row that says where you are, read what it names, and stop when you
have what you need.

## Where are you?

| You | Read | You can then |
|---|---|---|
| Never started it | [README.md](../../README.md) quick start | run `lein mu`, play a part, store it, stop |
| Want sound that works | [setup.md](setup.md) | use Fluidsynth (Gervill needs no setup) |
| New, want a tour | [tutorial.md](tutorial.md) | write, play, change and keep a piece; open the GUI |
| Know a notation | [lilypond.md](lilypond.md) | translate what you know, construct by construct |
| Want every note, rest, chord, mark | [parsing.md](parsing.md) | write any piece as text |
| Need an instrument or drum name | [instruments.md](instruments.md) | pick sounds by name |
| Want to *drive* it | [mu-tutorial.md](mu-tutorial.md), sections 0-2 | use the stack, the words, the play words |
| Want the signs on one page | [cheat-sheet.md](cheat-sheet.md) | recall `[R]`, `->`, `T!`, `R_` at a glance |
| Looking for one word | `words`, `"dup" see`, `"chord" help` in mu; [mu-words.md](mu-words.md) | find what a word takes and gives (the design is in [mu-repl.md](mu-repl.md)) |
| Want algorithms | [raw-algo-cookbook.md](raw-algo-cookbook.md) | call `3 8 p/euclid` and put it on notes |
| Want Slonimsky's polations | [polations.md](polations.md) | insert tones between a melody's tones |
| Want to know how material is read (time, next) | [algo-access.md](algo-access.md) | see which algorithms are lazy, seekable or stateful |
| Want one technique | [blending.md](blending.md), [counterpoint.md](counterpoint.md), [row-forms.md](row-forms.md), [grid.md](grid.md) | use it on its own |
| Want something that changes while it plays | [pull-voice.md](pull-voice.md), [hoketus-dynamic.md](hoketus-dynamic.md), `mus/hoketus2.mus` | write a voice as a function of the beat |
| Want to know how it works | [walk-through.md](walk-through.md), [domain.md](domain.md), [grammar-notes.md](grammar-notes.md) | follow one piece from text to sound |
| Want to change the code | [../../CLAUDE.md](../../CLAUDE.md), [namespace.md](namespace.md), [decisions.md](decisions.md) | know where things live and why |

## The path, in order

1. **First minute.** The README quick start, each line typed at the
   prompt. You have heard something.
2. **A guided tour.** `tutorial.md`: values and the store, the notation
   in small pieces, playing, changing a voice while it plays, files, the
   GUI.
3. **The notation in full.** `parsing.md`, with `instruments.md` beside it.
4. **The words.** `mu-tutorial.md` from section 0. Ask mu itself when
   stuck: `help`, `words`, `"name" see`, Tab.
5. **Algorithms.** The cookbook's recipes, each one run for real. An
   algorithm is a function that makes numbers; `zip` puts them on notes.
6. **Settings and live voices.** The tctx holds named values (`8 :steps
   T!`) that a voice looks up as it plays, so a change is heard at the
   next note. `mus/hoketus2.mus` is a piece written this way.
7. **Under the hood.** The walk-through, the domain model, the
   architecture in `CLAUDE.md`, and the reasons in `decisions.md`.

## Things worth knowing before you start

- The text is the same everywhere: a `.mus` file, the GUI editor and the
  mu prompt read alike. Comments are `//` and `/* */`.
- There is no Clojure API to learn; the program is mu and the GUI.
- `doc/md/` is the source; `scripts/docs.sh` renders it to HTML and PDF.
- The cookbook's recipes are each run when it is generated; the
  tutorial's examples are being re-checked as it is rewritten.
