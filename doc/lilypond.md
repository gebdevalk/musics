# From LilyPond to musics

For converting by hand, or reading a converted file. To convert a whole
file automatically: `(m/ly-to-mus "piece.ly")` writes `piece.mus` beside
it, and `(m/play-ly-file "piece.ly")` converts, parses and plays in one
step.

## Pitches

| LilyPond | musics | |
|---|---|---|
| `cis`, `fis` | `c#`, `f#` | sharp |
| `cisis` | `c##` | double sharp |
| `bes`, `es`, `as` | `b&`, `e&`, `a&` | flat |
| `beses` | `b&&` | double flat |
| (key-implied) | `n` | natural: `fn` |
| `\relative c' { c d e }` | `[c4 d e]` | relative is the default |
| `c'''` absolute | `C6/4` | uppercase letter + octave digit |

musics accepts only the symbols `#`, `##`, `&`, `&&`, `n`; the Dutch
suffixes (`is`, `es`, …) have to be rewritten.

**Relative pitch is the default**, with LilyPond's `\relative` rule: a
lowercase letter is the nearest pitch to the previous one. What
`\relative bes { … }` supplies as its anchor, musics takes from its
first note: write that one absolute (`B&4/4`), and the lowercase notes
after it follow relatively. Ticks `'` and `,` work the same.

**Key signatures imply accidentals.** LilyPond input is always literal
(the key only affects printing); in musics a bare letter takes its key's
accidental by default. Write `!acc:explicit` once at the top to keep
pitches literal — the importers always do.

## Structure

| LilyPond | musics |
|---|---|
| `{ c d e }` | `[c4 d e]` — a sequence |
| `<< \upper \lower >>` | `{:upper :lower}` — parallel parts |
| `frag = { … }`, then `\frag` | `frag = [ … ]`, then `\frag` — a variable |
| a named part to play or reuse | `[frag: …]`, then `:frag` |
| `\header`, `\score`, `\layout`, `\midi` | drop the wrappers; a `%{ … %}` comment can keep the title |

## Header instructions

| LilyPond | musics |
|---|---|
| `\tempo 4 = 68` | `!tempo:4=68` (or `!tempo:68`) |
| `\time 15/16` | `!Meter:15/16` |
| `\key f \minor` | `!key:F.minor` — uppercase letter, dotted mode |
| `\partial 8` | `\partial 8` |
| `\clef`, `\break` | drop: engraving only |

## Notes, rests, dynamics

| LilyPond | musics |
|---|---|
| `<c e g>4`, `r2.`, `c4~ c` | the same |
| `c4\f` (holds until the next dynamic) | `!f c4` — in musics `c4\f` is that note's alone |
| `c4\<`, `c4\>` | `!vol< c4`, `!vol> c4` — a ramp toward the next level |
| `c4\mf\<` | `!vol:mf< c4` |
| `\!` | nothing: the ramp ends at the next level |
| `\f` on its own | `!f` |
| `c4-.`, `c4\trill`, `c4( d e)` | the same |
| `\tuplet 3/2 { c8 d e }` | `c8*2/3 d e` |
| `\repeat volta 2 { … }` | `\repeat volta 2 [ … ]` |
| `\grace c16 d4` | `\grace c16 d4` |
| `\transpose c d { … }` | `\transpose c d ( … )` |

## A small example

LilyPond:

```
frag = \relative bes { <as bes des f>8. <bes c es g> c }
line = { \tempo 4 = 68 \key f \minor \frag \frag }
```

musics:

```mus
[frag: !acc:explicit <a& b& d& f>8. <b& c e& g> c]
[line: !tempo:4=68 !key:F.minor :frag :frag]
```
