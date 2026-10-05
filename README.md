# musics

Write music as text, compose it with algorithms, and play it live from
the Clojure REPL.

- **Text** — a compact notation (LilyPond- and GUIDO-flavoured) parsed
  into addressable parts: `[verse: !mf c4 d e f]`.
- **Algorithms** — Euclidean rhythms, Barlow indispensability, Markov
  and L-system melodies, tala and bell patterns, chaotic maps and more,
  combined as ordinary Clojure trees with their settings in one place.
- **Live** — real-time MIDI playback through Fluidsynth; change a
  setting and hear it on the next note. A small GUI shows every setting
  as a slider.
- **Import** — LilyPond, ABC and GUIDO text converted to musics text.

## Quick start

Needs Java 25 (the JVM options in `project.clj` use its compact object
headers; on Debian `apt install openjdk-25-jdk`), [Leiningen](https://leiningen.org), and for sound Fluidsynth
with a virtual MIDI port (`./scripts/setup.sh` sets it up on Linux; see
[doc/setup.md](doc/setup.md)). Parsing, composing and the tests need
none of the audio setup.

```bash
lein repl
```

```clojure
(m/parse "[verse: !mf c4 d e f]")   ; commit a part
(m/connect)                          ; open MIDI (once per session)
(m/play :verse)
(m/stop!)
```

Composing with algorithms — a tree, and the settings it runs with:

```clojure
(def riff (notes (gate euclid (cycle> scale))))   ; Euclidean rhythm over a pentatonic scale
(def tctx (t/tctx riff))                           ; its settings
(t/setp! tctx :k 5 :n 16)
(t/run riff tctx)                                  ; the notes
(gui riff)                                         ; a window with a slider per setting
(t/live! :riff riff tctx)                          ; play it endlessly; every change is heard
```

In `lein repl`, `musics.core` is `m`, `algo.tree` is `t`, and every
ready-made algorithm is referred.

## Documentation

`scripts/docs.sh` renders every document below to HTML and PDF in
`doc/html/` (open `doc/html/index.html`).

| Document | What it covers |
|---|---|
| [doc/setup.md](doc/setup.md) | Audio setup: once, and every boot |
| [doc/tutorial.md](doc/tutorial.md) | A guided tour: write, play, inspect, change while it plays |
| [doc/algorithms.md](doc/algorithms.md) | Algorithms: composing, settings, live playback |
| [doc/algo-cookbook.html](doc/algo-cookbook.html) | 47 worked recipes, each run for real (on GitHub, open it locally) |
| [doc/parsing.md](doc/parsing.md) | The text notation and its parser |
| [doc/lilypond.md](doc/lilypond.md) | Coming from LilyPond: construct by construct |
| [doc/domain.md](doc/domain.md) | The domain model: parts, contexts, envelopes |
| [doc/walk-through.md](doc/walk-through.md) | One piece followed from text to sound, stage by stage |
| [doc/decisions.md](doc/decisions.md) | Why things are the way they are |
| [CLAUDE.md](CLAUDE.md) | The full architecture reference |

## Tests

```bash
lein test
```

## License

Copyright © 2026 geb. Distributed under the
[Eclipse Public License 2.0](LICENSE): use it, change it, build on it;
changes you distribute to these files stay under the same license.
