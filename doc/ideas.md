# Ideas

Collected ideas, not yet built. Each one as it was written, with a note
on what it does and where it would fit.

## pitch->duration

```clojure
(pitch->duration [n] (/ (- 12 (% n 12)) 12))
```

A note's length from its pitch class: C lasts a whole note, and each
semitone up within the octave is a twelfth shorter, down to 1/12 for B.
The octave doesn't matter (`60` and `72` both give `1`).

| pitch | pitch class | duration |
|---|:-:|:-:|
| C (60) | 0 | 1 |
| D (62) | 2 | 5/6 |
| E (64) | 4 | 2/3 |
| G (67) | 7 | 5/12 |
| B (71) | 11 | 1/12 |

- In Clojure, `%` is `mod` (or `rem`; the same for MIDI numbers, which
  are never negative): `(defn pitch->duration [n] (/ (- 12 (mod n 12)) 12))`.
- As a tree algo it is a **pitches → durations** bridge, the first
  half of what `doc/bridge-table.md` point 1 misses (nothing makes
  rhythm from pitches). With `color-talea` a melody would carry its own
  rhythm: `(color-talea m (map pitch->duration m))`.
- Twelfths are not plain note values (1/12 of a whole is a triplet
  eighth's own length), so the result needs the `*Ratio` duration
  suffix in musics text, or rounding to a grid, when it is written out.

### With a scale factor

`f` scales every duration (1, 2, 3, ...); the shape stays the same, C
longest and B shortest, 12:1:

```clojure
(defn pitch->duration [n f] (* f (/ (- 12 (mod n 12)) 12)))
```

The chromatic octave:

| f | C | C# | D | D# | E | F | F# | G | G# | A | A# | B | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 11/12 | 5/6 | 3/4 | 2/3 | 7/12 | 1/2 | 5/12 | 1/3 | 1/4 | 1/6 | 1/12 | 13/2 |
| 2 | 2 | 11/6 | 5/3 | 3/2 | 4/3 | 7/6 | 1 | 5/6 | 2/3 | 1/2 | 1/3 | 1/6 | 13 |
| 3 | 3 | 11/4 | 5/2 | 9/4 | 2 | 7/4 | 3/2 | 5/4 | 1 | 3/4 | 1/2 | 1/4 | 39/2 |

- With `f = 3` every step is a quarter note: C lasts 12 quarters, B
  one, all plain (tied) note values — no `*Ratio` needed.
- The total is 13/2 × `f`, not 12: an even ramp from 1 down to 1/12
  averages 13/24. A total of 12 with C = 1 and steps of 1/12 is
  impossible (12 equal steps total 12 × the mean of the first and last
  value, so first + last would have to be 2); the alternatives —
  C = 23/12, or steps of 2/11 or 1/13 — were worse, so the total is
  left as it falls.
- As a tree algo `f` is a param; it does what `stretch` with
  `:factor f` does after `f = 1`, built in.

## duration->pitch (the way back)

A **durations → pitches** bridge. Two ways to read a duration as a
pitch:

**a. The exact inverse of pitch->duration.** Solving `d = (12 - pc)/12`
for `pc` gives `pc = 12 - 12d`; `base` sets the octave:

```clojure
(defn duration->pitch [d base] (+ base (mod (Math/round (* 12 (- 1 d))) 12)))
```

| duration | 1 | 5/6 | 1/2 | 1/4 | 1/8 | 1/12 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| pitch class | 0 (C) | 2 (D) | 6 (F#) | 9 (A) | 11 (B, 10.5 rounded) | 11 (B) |

Round trip: `(pitch->duration (duration->pitch d 60))` is `d` again for
every twelfth from 1/12 to 1. Other values round to the nearest
semitone, and anything a whole note or longer wraps (`2` gives C too).
It keeps the idea's own shape: linear, one octave, long = low.

**b. Proportional (log).** Durations are heard as ratios, as pitches
are (`common.music-data/quantities` marks `:note-value` `:scale :log`
for that reason), so map ratio to interval: half the duration = an
octave up, with a quarter note on middle C:

```clojure
(defn duration->pitch-log [d] (- 60 (* 12 (/ (Math/log (* 4 d)) (Math/log 2)))))
(defn pitch->duration-log [p] (* 1/4 (Math/pow 2 (/ (- 60 p) 12))))   ; its inverse
```

| duration | 1 | 1/2 | 3/8 (dotted ¼) | 1/4 | 1/6 (triplet ¼) | 1/8 | 1/16 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| pitch | 36 | 48 | 53 (fifth below) | 60 | 67 (fifth above) | 72 | 84 |

Rhythmic ratios become the intervals with the same ratio: a dotted
note (3:2) sits a fifth below its plain value, a triplet a fifth above
— the pitch–rhythm continuum Stockhausen described in "…how time
passes…". Results are fractional (52.98 for the dotted quarter), so
round to semitones, or to a scale with `degrees`. Going back, a pitch
gives a duration of the form `1/4 · 2^(k/12)`, which only lands on
plain note values at octaves and (nearly) fifths.

Either way: as tree algos, **durations → pitches** (`:in [:durations]
:out :pitches`) and back, with `base` (a) or the reference pitch and
note value (b) as params.

## Speech stress for text-rhythm

`text-rhythm` (`algo.rhythmic.sonification/text-to-rhythm`) counts
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
