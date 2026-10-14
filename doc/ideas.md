# Ideas

Collected ideas, not yet built. Each one as it was written, with a note
on what it does and where it would fit.

## Speech stress for text-rhythm

`text-rhythm` (`musics.algo.rhythmic.sonification/text-to-rhythm`) counts
syllables with a rule of thumb (vowel groups, a final `e` dropped) and
has no idea which syllable is stressed: its `"stress"` mode stresses a
word's first syllable and alternates from there, and every word gets a
beat. For *the train to chicago*:

| | the | train | to | chi | ca | go |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| spoken | | **x** | | | **x** | |
| `"stress"` now | 1 | 1 | 1 | 1 | 0 | 1 |
| wanted | 0 | 1 | 0 | 0 | 1 | 0 |

Two additions would make it follow speech:

- **A pronouncing dictionary for stress.** The CMU Pronouncing
  Dictionary (free, BSD-style licence, about 134,000 English words)
  gives every word's syllables with their stress: `CHICAGO  SH AH0 K
  AA1 G OW0` — `1` primary stress, `2` secondary, `0` none. Read as a
  data file (a few MB) into a word → stress-pattern map; a word not in
  it falls back to the current vowel-group count.
- **Unstressed small words.** Articles, prepositions, pronouns and
  auxiliaries (*the, a, to, of, and, in, is, ...*) carry no stress in
  running speech, though the dictionary lists them alone as stressed:
  a short stop list keeps them at `0`.

The result could keep the levels instead of only 0/1 — `2` primary,
`1` secondary, `0` unstressed — like `"words"` mode's strong `2`: a
grid with accents (see `doc/bridge-table.md`, point 4, on typing
`text-rhythm` and `trend-rhythm`). With durations from the syllables
it would also give speech-like note lengths, not just onsets.

## Several instruments: what's left (parked)

A context or a note already takes several instruments (`!i:40,73,0`,
`c4\i:40,73`: the note sounds once per instrument, each on its own
channel), and a drum several keys (`x8\kick,clap`). Still open:
- **Per-instrument settings.** Does each instrument get its own volume
  or transposition (an octave doubling), or do they all share the
  leaf's?
- **Algo trees.** With instrument as an end material (see
  `doc/algo-audit.md`, decision 4), a tree could produce a list of
  instruments per leaf as easily as one.
