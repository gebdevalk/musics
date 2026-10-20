# Row forms

The serial row forms, in mu and in `.mus` text alike. Why they are
spelled this way: doc/md/decisions.md, "mu and the text, one
vocabulary" (2026-10-17).

## The forms

| Letter | Name | Does |
|---|---|---|
| P | prime | the line as written |
| R | retrograde | backwards |
| I | inversion | every interval mirrored around the first note |
| RI | retrograde inversion | the inversion, backwards |
| M | mirror | the inversion around the mean pitch, keeping the register |

German: G, K (Krebs), U (Umkehrung), KU. Latin (canon): rectus,
inversus, cancrizans.

- With transposition, IR adds nothing: it is RI transposed by twice the
  first-to-last interval. Hence 48 forms (4 x 12), not 60.
- The inversion's axis (first note or mean) adds nothing either: the
  two differ by a transposition. Around the mean, R and I commute.
- Not modulo 12: with octaves, +12 is another line.

## In mu

Words on the line on top, applied now:

| Word | Twin | Example |
|---|---|---|
| `[R]` | `retrograde` | `[c d e f] [R]` → `[F4/4 E4 D4 C4]` |
| `[I]` | `inversion` | `[c d e f] [I]` → `[C4/4 A#3 G#3 G3]` |
| `[RI]` | `retrograde-inversion` | `[c d e f] [RI]` → `[G3/4 G#3 A#3 C4]` |
| `[M]` | `mirror` | `[c d e f] [M]` → `[F#4/4 E4 D4 C#4]` |

Transposed with `+`: `x [I] 5 +` is I5. `[R]` exactly is the word;
`[ R ]`, `[R1]` or `[R*2]` is a line with a bar's rest. In Clojure:
`(mu/R x)`, `(mu/I x)`, `(mu/RI x)`, `(mu/M x)`.

## In text

Commands with a `( )` body, changed while parsing; an optional number
transposes the result:

```
retrograde ( c d e f )          R ( … )
inversion ( c d e f )           I ( … )      I 5 ( … ) is I5
retrograde-inversion ( … )      RI ( … )
mirror ( … )                    M ( … )
```

`R` alone stays a bar's rest. The forms reach into nested lines,
parallels (keeping their parts' order), referenced parts (changed as a
copy) and repeats' bodies. The retrograde turns ties and dynamics round
with the notes, writing what the body's instructions change into each
note; it refuses jumps and a repeat with `volta` endings.

The LilyPond importer reads `\retrograde` and `\inversion` as these
forms.
