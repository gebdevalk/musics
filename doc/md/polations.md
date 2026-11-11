# Polations: Slonimsky's insertions

Nicolas Slonimsky's *Thesaurus of Scales and Melodic Patterns* builds
melodies by putting tones **between** the tones of a melody. `musics.algo.melodic.slonimsky`
has them as functions of numbers. Load them in mu:

```
'melodic.slonimsky 's reqalgo
```

An inserted tone always comes **after** a principal tone, never before
it; the last principal tone has nothing after it. A result is a list of
MIDI numbers (`zip` puts it on notes, `mus/slonimsky.mus` does so).

## On a melody you give

```
't '[60 62 64 65] :=
\t '[59] s/infra                       → '[60 59 62 59 64 59 65]
\t '[66] s/inter                       the same, the inter tones
\t '[67] s/ultra                       the same, the ultra tones
\t '[59] '[66] '[67] s/polation2       → '[60 59 66 67 62 59 66 67 64 59 66 67 65]
```

`infra`, `inter` and `ultra` take a melody and the tones to insert (one
or several); `polation2` takes all three, in that order after each tone
(an empty list `'[]` switches one off).

## On an equal division of the octave

`polation` stacks an interval from C4 until a C comes back. The
divisor is that interval in semitones: 2 3 4 5 6 8 9 10 (5 and 10
reach pitch 120, beyond what text can write, so they work as numbers
only).

```
4 7 s/polation                        → '[60 59 61 65 64 63 65 69 68 67 69 73 72]
4 1 1 1 s/polation                    the same, with the three switches written out
```

The switches are one mask, infra 1 + inter 2 + ultra 4 (7 is all
three), or three ints, each 0 or 1.

## As a function of the index

`polation-at` gives the note at one index, `polation-fn` a function of
the index alone, `polation-period` how many notes one pass has before it
starts over. No list is built, so it suits a live voice that asks for
one note at a time (`create-fn`, `musics.algo.common.factory`).

Controls in the GUI: `divisor` and `mask`.
