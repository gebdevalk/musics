# musics

Write music as text, compose it with algorithms, and play it live -- in
mu, the musical REPL, and a GUI beside it.

- **Text** — a compact notation (LilyPond- and GUIDO-flavoured) for
  addressable parts: `[verse: !mf c4 d e f]`.
- **mu** — a stack-based musical REPL (`lein mu`) in which that text is
  a literal: `[c4 d e] 2 + [R] >` transposes, reverses and plays. A
  `.mus` file is mu input too.
- **Algorithms** — Euclidean rhythms, Barlow indispensability, Markov
  and L-system melodies, tala and bell patterns, chaotic maps and more,
  as mu words named by what they deliver: `duration/euclid`,
  `pitch/lorenz`.
- **Live** — real-time MIDI playback through Gervill or Fluidsynth; the
  GUI (`gui` in mu) shows every setting as a slider, heard on the next
  note.
- **Import** — LilyPond, ABC and GUIDO files read as musics text.

## Quick start

Needs Java 25 (the JVM options in `project.clj` use its compact object
headers; on Debian `apt install openjdk-25-jdk`) and
[Leiningen](https://leiningen.org). Sound plays through Gervill, the
JVM's own synthesizer, with nothing to set up; Fluidsynth needs a
virtual MIDI port (`./scripts/setup.sh` on Linux; see
[doc/md/setup.md](doc/md/setup.md)).

```bash
lein mu
```

```
[verse: !mf c4 d e f] R!          store a part; its name, :verse, is left
R@ >                              fetch it and play it
@^{ k:3 n:8 duration-pulse:1/8 } duration/euclid [c e g] pitches zip >
stop
"song.mus" load                   read a file (.mus, .ly, .abc, .gmn)
x "x.mid" render                  write a MIDI file
gui                               the GUI beside mu
```

## Documentation

The documents are Markdown in `doc/md/`. `scripts/docs.sh` renders
them to HTML in `doc/html/` (open `doc/html/index.html`) and PDF in
`doc/pdf/`.

### Reading order

To get playing:

1. This README: what musics is, and the quick start above.
2. [doc/md/setup.md](doc/md/setup.md), only for Fluidsynth. Gervill,
   the default, needs no setup: type `gervill` in mu.
3. [doc/md/tutorial.md](doc/md/tutorial.md): the guided tour, from
   starting mu to writing, playing and changing a piece while it plays,
   files and the GUI.
4. [doc/md/mu-tutorial.md](doc/md/mu-tutorial.md): sections 0–2 (the
   stack, writing music, listening) first, the rest as you need it.
5. [doc/md/algo-tree-tutorial.md](doc/md/algo-tree-tutorial.md): when
   you want algorithms, from `duration/euclid` to recipes playing live.

Keep at hand: [parsing.md](doc/md/parsing.md) (the notation),
[instruments.md](doc/md/instruments.md) (instrument, drum and kit
names), [lilypond.md](doc/md/lilypond.md) (coming from LilyPond), and in
mu itself `words`, `"dup" see` and Tab completion.

To understand how it works: [walk-through.md](doc/md/walk-through.md),
[CLAUDE.md](CLAUDE.md), [domain.md](doc/md/domain.md),
[algorithms.md](doc/md/algorithms.md) and
[decisions.md](doc/md/decisions.md). The design and history documents
(`algo-space.md`, `grid.md`, `core-logic.md`, `ideas.md`,
`doc/md/audits/`) can wait.

### All documents

| Document | What it covers |
|---|---|
| [doc/md/setup.md](doc/md/setup.md) | Audio setup: once, and every boot |
| [doc/md/mu-tutorial.md](doc/md/mu-tutorial.md) | mu, step by step |
| [doc/md/algo-tree-tutorial.md](doc/md/algo-tree-tutorial.md) | Algo trees in mu: word by word, to a recipe playing live and the GUI |
| [doc/md/tutorial.md](doc/md/tutorial.md) | A guided tour: write, play, inspect, change while it plays |
| [doc/md/algorithms.md](doc/md/algorithms.md) | Algorithms in code: writing and registering an algo, trees in Clojure |
| [doc/html/algo-cookbook.html](doc/html/algo-cookbook.html) | 53 worked recipes, each run for real (on GitHub, open it locally) |
| [doc/md/mu-repl.md](doc/md/mu-repl.md) | mu, the musical REPL: its words and design |
| [doc/md/parsing.md](doc/md/parsing.md) | The text notation and its parser |
| [doc/md/lilypond.md](doc/md/lilypond.md) | Coming from LilyPond: construct by construct |
| [doc/md/domain.md](doc/md/domain.md) | The domain model: parts, contexts, envelopes |
| [doc/md/walk-through.md](doc/md/walk-through.md) | One piece followed from text to sound, stage by stage |
| [doc/md/decisions.md](doc/md/decisions.md) | Why things are the way they are |
| [CLAUDE.md](CLAUDE.md) | The full architecture reference |

## Tests

```bash
lein test
```

## License

Copyright © 2026 geb. Distributed under the
[Eclipse Public License 2.0](LICENSE): use it, change it, build on it;
changes you distribute to these files stay under the same license.
