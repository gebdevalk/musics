# LilyPond chordmode chords

Oct 20, 2026 · @Geb

## Syntax

In `\chordmode`, a chord is a root, an optional duration, then a colon and modifiers: `c4:m7`. Without a colon it is a major triad; a duration carries over to later chords that omit one. Chords are written in absolute pitch names (Dutch by default: `cis` is C#, `ees` is E♭).

```
\chordmode { c1 c:m c:7 c:maj7 }
```

The general shape is `root[duration]:quality[number][additions][^removals][/bass]`. Use `\new ChordNames` to print the symbols and `\new Staff` to print the notes.

## Chord qualities

Each quality is shown on C. Replace `c` with any root (`fis:m7`, `bes:maj7`).

| Chord | LilyPond | Notes |
| --- | --- | --- |
| Major | `c` | C E G |
| Minor | `c:m` | C E♭ G |
| Diminished | `c:dim` | C E♭ G♭ |
| Augmented | `c:aug` | C E G♯ |
| Suspended 2nd | `c:sus2` | C D G |
| Suspended 4th | `c:sus4` | C F G |
| Power chord | `c:5` | C G |
| Sixth | `c:6` | C E G A |
| Minor sixth | `c:m6` | C E♭ G A |
| Dominant seventh | `c:7` | C E G B♭ |
| Major seventh | `c:maj7` | C E G B |
| Minor seventh | `c:m7` | C E♭ G B♭ |
| Minor-major seventh | `c:m7+` | C E♭ G B |
| Diminished seventh | `c:dim7` | C E♭ G♭ B♭♭ |
| Half-diminished | `c:m7.5-` | C E♭ G♭ B♭ |
| Seventh sus4 | `c:7sus4` | C F G B♭ |
| Dominant ninth | `c:9` | C E G B♭ D |
| Major ninth | `c:maj9` | C E G B D |
| Minor ninth | `c:m9` | C E♭ G B♭ D |
| Dominant eleventh | `c:11` | C G B♭ D F (the third is dropped automatically) |
| Dominant thirteenth | `c:13` | C E G B♭ D A |

## Additions, alterations, removals and bass notes

After the quality, steps are separated by dots. A `+` raises a step by a semitone, a `-` lowers it. A caret `^` removes steps. A slash sets the bass.

| Goal | LilyPond | Notes |
| --- | --- | --- |
| Add a ninth to a triad | `c:3.5.9` | C E G D |
| Added sixth and ninth | `c:6.9` | C E G A D |
| Flat ninth | `c:7.9-` | C E G B♭ D♭ |
| Sharp ninth | `c:7.9+` | C E G B♭ D♯ |
| Raised fifth | `c:7.5+` | C E G♯ B♭ |
| Lowered fifth | `c:7.5-` | C E G♭ B♭ |
| Sharp eleventh | `c:maj7.11+` | C E G B F♯ |
| Remove the third | `c:7^3` | C G B♭ |
| Remove fifth and third | `c:9^3.5` | C B♭ D |
| First inversion | `c/e` | E C G (bass E) |
| Second inversion | `c/g` | G C E (bass G) |
| Bass note added, nothing moved | `c/+g` | G C E G |
| Minor seventh over a bass | `c:m7/bes` | B♭ C E♭ G |

## Example: ii–V–I in C with chord names

The same `chordmode` music feeds both the chord symbols and the staff.

```
harmony = \chordmode {
  d1:m7 g:7 c:maj7 a:7.9-
}

\score {
  <<
    \new ChordNames \harmony
    \new Staff { \harmony }
  >>
}
```

In musics, import real LilyPond text with `musics.input.lilypond-import`; GUIDO accidentals replace the Dutch suffixes (see `doc/md/lilypond.md`).

## Blues chorus: 12 bars in C

The basic blues is three four-bar phrases on I, IV and V, all dominant sevenths. The quick change (F7 in bar 2) and the V in the last bar are the common variants.

| Bar | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Chord | C7 | F7 | C7 | C7 | F7 | F7 | C7 | C7 | G7 | F7 | C7 | G7 |

```
bluesChorus = \chordmode {
  c1:7 f:7 c:7 c:7
  f:7 f:7 c:7 c:7
  g:7 f:7 c:7 g:7
}

\score {
  <<
    \new ChordNames \bluesChorus
    \new Staff { \bluesChorus }
  >>
}
```

## Jazz chorus: rhythm changes in B♭

The 32-bar AABA form from "I Got Rhythm" is the standard jazz chorus. The A section circles I–vi–ii–V; the bridge is a cycle of dominants (D7–G7–C7–F7), two bars each.

| Section | Bars | Chords |
| --- | --- | --- |
| A (×3) | 1–2 | B♭maj7 G7 \| Cm7 F7 |
|  | 3–4 | Dm7 G7 \| Cm7 F7 |
|  | 5–6 | Fm7 B♭7 \| E♭maj7 E°7 |
|  | 7–8 | Dm7 G7 \| Cm7 F7 (last A: B♭maj7) |
| B (bridge) | 17–24 | D7 \| G7 \| C7 \| F7, two bars each |

```
rhythmA = \chordmode {
  bes2:maj7 g:7 c:m7 f:7
  d:m7 g:7 c:m7 f:7
  f:m7 bes:7 ees:maj7 e:dim7
  d:m7 g:7 c:m7 f:7
}

rhythmB = \chordmode {
  d1:7 d:7 g:7 g:7 c:7 c:7 f:7 f:7
}

rhythmChanges = { \rhythmA \rhythmA \rhythmB \rhythmA }

\score {
  <<
    \new ChordNames \rhythmChanges
    \new Staff { \rhythmChanges }
  >>
}
```

The `\chordmode` variables hold chords, so `rhythmChanges` joins them with plain braces. Change the last bar of the final `\rhythmA` to `bes1:maj7` to end on the tonic.

## Jazz ii–V–I turnaround chorus in C

The ii–V–I is jazz's basic cadence. This 8-bar chorus plays it four times with the usual substitutions, so each pass sounds different while the harmony stays in C.

| Bars | Chords | Variant |
| --- | --- | --- |
| 1–2 | Dm7 G7 \| Cmaj7 | Plain ii–V–I |
| 3–4 | Dm7 G7 \| Cmaj7 A7♭9 | Secondary dominant leads back to the ii |
| 5–6 | Dm7 G7 \| Cmaj7 | Plain again |
| 7–8 | Dm7 D♭7 \| Cmaj7 G7 | Tritone substitution, then a turnaround |

```
iiVIChorus = \chordmode {
  d2:m7 g:7  c1:maj7
  d2:m7 g:7  c:maj7 a:7.9-
  d2:m7 g:7  c1:maj7
  d2:m7 des:7  c:maj7 g:7
}

iiVIMinor = \chordmode {
  d2:m7.5- g:7.9-  c1:m6
}

\score {
  <<
    \new ChordNames { \iiVIChorus \iiVIMinor }
    \new Staff { \iiVIChorus \iiVIMinor }
  >>
}
```

The minor version (`iiVIMinor`) swaps in a half-diminished ii, a flat-nine V and a minor-sixth tonic. The tritone substitute `des:7` shares its third and seventh with `g:7`, so it resolves to C the same way.
