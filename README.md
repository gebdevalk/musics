# musics

[![CI](https://github.com/gebdevalk/musics/actions/workflows/ci.yml/badge.svg)](https://github.com/gebdevalk/musics/actions/workflows/ci.yml)

Write music as text, compose it with algorithms, and play it live, in
**mu**, a musical REPL, and a GUI beside it.

- **Text.** A compact notation, LilyPond- and GUIDO-flavoured, for parts
  you can name and address: `[verse: !mf c4 d e f]`.
- **mu.** A stack-based REPL in which that text is a literal. A word
  takes what lies under it: `[c4 d e] 2 + [R] ->` transposes, reverses
  and plays. A `.mus` file is mu input too.
- **Algorithms.** Euclidean rhythms, Barlow indispensability, Markov and
  L-system melodies, tala and bell patterns, chaotic maps, counterpoint,
  Slonimsky's polations and more: plain functions called by name,
  `3 8 p/euclid`, whose results `zip` puts onto notes.
- **Live.** Real-time MIDI through Gervill or Fluidsynth. A voice can be
  a function of the beat that looks its settings up as it plays, so a
  value changed with `T!`, or a slider in the GUI, is heard on the next
  note.
- **Edit and ask.** A seq is a list you can address (`[c d e] 1 g !`
  puts a note at an index) and question: `duration`, `at`, `bar`,
  `find`, `pulses`, `prime-form`, `key-of`, `chord-name`.
- **Import.** LilyPond, ABC and GUIDO files read as musics text.

## Quick start

Needs Java 25 (the JVM options in `project.clj` use its compact object
headers; on Debian `apt install openjdk-25-jdk`) and
[Leiningen](https://leiningen.org). Sound comes from Gervill, the JVM's
own synthesizer, with nothing to set up. Fluidsynth needs a virtual MIDI
port (`./scripts/setup.sh` on Linux; see [doc/md/setup.md](doc/md/setup.md)).

```bash
lein mu
```

The first minute, each line typed at the `mu<n>` prompt (`n` is how many
values are on the stack):

```
gervill                                  connect Java's synthesizer
[verse: !mf c4 d e f] ->                 write a part and play it
[verse: !mf c4 d e f] R!                 store it in the repo: :verse
:verse R@ 5 + ->                         fetch it, up a fifth, play it
clear                                    empty the stack (R! left :verse on it)
'rhythmic.pattern 'p reqalgo             make an algorithm's words available
'bridge reqalgo                          and the bridges that turn its numbers into music
3 8 p/euclid 1/8 bridge/pulses->durations '[60 64 67] [~] zip [~] ->
                                         a Euclidean rhythm on three pitches, looped
stop                                     silence
help                                     mu in a few lines
"song.mus" load                          read a file (.mus, .ly, .abc, .gmn)
x "x.mid" render                         write a MIDI file
gui                                      the GUI beside mu
```

And one that plays by itself and changes while it sounds:

```
"mus/hoketus2.mus" load                  a piece written as voices that are functions of the beat
start                                    hear it
6 :steps T!                              change a setting: heard at the next note
stop-hoketus                             stop
```

### As a single file

`scripts/build.sh` builds `target/mu.jar`, the whole of mu in one file
(about 70 MB, with JavaFX for the GUI), and `bin/mu` runs it:

```bash
scripts/build.sh          # about half a minute; a tagged commit's jar carries the tag as its version
bin/mu                    # or bin/mu --version, bin/mu --help
```

Copy `bin/mu`, `target/mu.jar` and the `soundfonts/` folder anywhere
together. It needs Java 25 (the launcher checks and says so) but not
Leiningen or the source. Sound needs a General MIDI soundfont:
[soundfonts/](soundfonts/README.md) holds the one mu uses by default,
FluidR3_GM under the MIT license (the `.sf2` itself is git-ignored, 148 MB;
`apt install fluid-soundfont-gm` provides it too), and mu also looks in
`/usr/share/sounds/sf2` and `~/Music/sf` (see
[doc/md/setup.md](doc/md/setup.md)). The jar is built for the platform it
is built on (JavaFX brings native libraries).

## Where to go from here

The documents are Markdown in `doc/md/`; `scripts/docs.sh` renders them
to HTML in `doc/html/` (open `doc/html/index.html`) and PDF in `doc/pdf/`.
[doc/md/start-here.md](doc/md/start-here.md) says which one fits where you
are. Read them roughly in this order, stopping when you have what you need.

1. **Getting started.** [setup.md](doc/md/setup.md) (only for Fluidsynth),
   then [tutorial.md](doc/md/tutorial.md): from starting mu to writing,
   playing and changing a piece while it plays, files and the GUI.
2. **Writing music.** [parsing.md](doc/md/parsing.md) is the notation;
   [instruments.md](doc/md/instruments.md) the instrument, drum and kit
   names; [lilypond.md](doc/md/lilypond.md) if you come from LilyPond.
3. **Using mu.** [mu-tutorial.md](doc/md/mu-tutorial.md) goes through the
   words, sections 0-2 (the stack, writing, listening) first;
   [mu-words.md](doc/md/mu-words.md) has every word, [mu-repl.md](doc/md/mu-repl.md) the design. In mu itself: `words`,
   `"dup" see`, and Tab completion.
4. **Composing with algorithms.**
   [raw-algo-cookbook.md](doc/md/raw-algo-cookbook.md) has 53 recipes, each
   run for real, from `3 8 p/euclid` to a word playing live;
   [blending.md](doc/md/blending.md), [counterpoint.md](doc/md/counterpoint.md)
   and [row-forms.md](doc/md/row-forms.md) cover single topics.
5. **Live voices and settings.** [pull-voice.md](doc/md/pull-voice.md) and
   [hoketus-dynamic.md](doc/md/hoketus-dynamic.md) explain a voice that is
   a function of the beat; `mus/hoketus2.mus` is one, written out.
6. **How it works.** [walk-through.md](doc/md/walk-through.md) follows one
   piece from text to sound, [domain.md](doc/md/domain.md) is the model,
   [CLAUDE.md](CLAUDE.md) the architecture reference and
   [decisions.md](doc/md/decisions.md) why things are the way they are.

## Editor support

`editor/mus/` is a TextMate grammar for `.mus` files and mu input (comments
`//` and `/* */`, notes, ids, instructions, words), for VS Code and
IntelliJ: see [editor/README.md](editor/README.md).

## Tests

```bash
lein test       # the suite
lein verify     # lint, then the suite -- what CI runs
```

CI (`.github/workflows/ci.yml`) runs `lein verify` on every push to `main`
and `develop` and every pull request, then builds the jar and starts it
once; the jar is kept as a download of that run for 14 days.

## License

Copyright © 2026 geb. Distributed under the
[Eclipse Public License 2.0](LICENSE): use it, change it, build on it;
changes you distribute to these files stay under the same license.
