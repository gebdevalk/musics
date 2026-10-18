# mu tutorial: the music words

mu is musics' musical REPL: you type music, and it lands on a stack
where words change it, combine it and play it. This tutorial walks
through the words that work on music, from writing a note to building
a color-talea. Every example was run in mu; the line under `→` is what
mu printed.

Start mu with `lein mu` (or `(mu!)` from `lein repl`). `bye` or Ctrl+D
leaves; the stack stays for the session.

## 0. The stack

The prompt `mu<0>` counts what is on the stack. These words move things
around on it, and you'll need them throughout:

| Word | Stack | Does |
|---|---|---|
| `.s` | `( -- )` | show the stack, top first |
| `dup` | `( a -- a a )` | copy the top |
| `swap` | `( a b -- b a )` | exchange the top two |
| `rot` | `( a b c -- b c a )` | the third comes on top |
| `pop` | `( a -- )` | drop the top |
| `clear` | `( … -- )` | clear the stack (`cls` clears the screen) |
| `see` | `( "word" -- )` | what a word does: `"dup" see` |

A **quotation** `'( … )` is a piece of mu held for later, run by the
words that take one (`vary`, `filter`, `within` …). The spaces inside
are optional: `'(12 +)` is `'( 12 + )`.

## 1. Writing music

Anything you can write in a `.mus` file you can type in mu. It becomes
a value on the stack, not stored anywhere yet.

```
c4                  → C4/4
[c d e]             → [C4/4 D4 E4]
[c4 d8 e f4]        → [C4/4 D4/8 E4 F4/4]
<c e g>4            → <C4 E4 G4>4
[c4 r8 e8]          → [C4/4 r8 E4]
{[c d] [e f]}       → {[C4/4 D4] [E4 F4]}
```

- `[ ]` is a line (notes one after another), `{ }` lines at once,
  `< >` a chord, `r` a rest.
- A duration carries over: `d8 e` makes `e` an eighth too.
- A lowercase letter is relative: it lands as close as it can to the
  note before. A capital letter with an octave is absolute: `C5`.
- mu prints back absolutely (`C4/4`), and what it prints reads back
  as the same thing.

## 2. Listening

First connect a synthesizer: `gervill` (Java's own, nothing to set up)
or `fluidsynth` (through qsynth, see `doc/md/setup.md`).

| Word | Does |
|---|---|
| `>` or `play` | play now, stopping what plays |
| `>+` or `play+` | play now, joining what plays |
| `>>` or `play-next` | play in place of what plays, at its next boundary: the end of it, or of its repeat's pass |
| `x \| >>` | the same, at the next bar line (`\|\|` … `\|\|\|\|` for stronger ones) |
| `>>+` or `play-next+` | join at the next boundary |
| `stop` | stop everything (or Alt+., even mid-typing) |
| `pause` | pause; `pause` again goes on (or Alt+,) |

`>` plays now, `>>` at the next boundary; a `+` joins what plays
instead of replacing it.

```
[c d e f g] >
[c d e f g] [~] >        loop it until stop
[e f g a b] >>           takes over at the end of the current pass
```

`echo` prints each note as it sounds; `echo` again stops it.

## 3. Putting music together

| Word | Stack | Does |
|---|---|---|
| `seq`, or `[2]` `[3]` … (`line`) | `( \| … -- line )` or `( a … -- line )` | one after another |
| `par`, or `{2}` `{3}` … (`together`) | `( \| … -- parallel )` or `( a … -- parallel )` | at once |
| `[+]` | `( a b -- line )` | two lines joined into one |
| `{+}` or `beside` | `( a b -- parallel )` | the parts of two parallels in one |
| `[H]` or `head`, `[T]` or `tail` | `( x [n] -- x )` | the first n (1 without a count), and all after them: the same n splits a line in two |
| `<>` or `chord` | `( notes -- chord )` | the notes on top of the stack as a chord |

The pairs differ in what they take and what they make of it:

- `seq` and `par` gather **everything above a `|` mark**, as many as
  you like, each staying an element of its own.
- `[n]` and `{n}` take **the top n**, `[2]`, `[3]`, … and `{2}`, `{3}`,
  …, each staying an element (a part) of its own.
- `[+]` and `{+}` **join** the top two: `[+]` two lines into one flat
  line, `{+}` the parts of two parallels into one `{ }` (a line counts
  as one part).

`[ ]` makes or acts on a line, `{ }` makes or acts on a parallel.

```
| c d e seq             → [C4/4 D4 E4]
| [c d] [e f] seq       → [[C4/4 D4] [E4 F4]]       two lines, one after the other
[c d] [e f] [2]         → [[C4/4 D4] [E4 F4]]       the same, counted
[c d] [e f] [+]         → [C4/4 D4 E4 F4]           one line of four notes
| [c d] [e f] [g a] par → {[C4/4 D4] [E4 F4] [G4 A4]}
[c d] [e f] {2}         → {[C4/4 D4] [E4 F4]}
[c] [e] [g] {3}         → {[C4/4] [E4] [G4]}
{[c] [d]} {[e] [f]} {+} → {[C4/4] [D4] [E4] [F4]}
[c d e f] [H]           → [C4/4]                    the head
[c d e f] [T]           → [D4/4 E4 F4]              the tail
[c d e f] 2 [H]         → [C4/4 D4]
c e g <>                → <C4 E4 G4>4
```

Both `seq` results play the same four notes; the first keeps the two
lines apart, so a word can still treat them as two (`[R]` swaps the
lines, where on the flat line it reverses the notes).

## 4. Changing pitch

A bare symbol works on every note; the same symbol in brackets works
on the line as a whole.

| Word | Stack | Does |
|---|---|---|
| `+` / `-` or `up` / `down` | `( x n -- x )` | every pitch up / down n semitones |
| `transpose` | `( x from to -- x )` | moved by the interval between two notes |
| `[R]` or `retrograde` | `( x -- x )` | backwards, as it sounds: ties and dynamics turn round with it |
| `[I]` or `inversion` | `( x -- x )` | mirrored around the first note, which stays |
| `[RI]` or `retrograde-inversion` | `( x -- x )` | inverted, then backwards |
| `[M]` or `mirror` | `( x -- x )` | mirrored around the mean pitch, keeping the register |

```
[c e g] 7 +                → [G4/4 B4 D5]
[c e g] 12 -               → [C3/4 E3 G3]
[c e g] c d transpose      → [D4/4 F#4 A4]
[c e g] [M]                → [G#4/4 E4 C#4]
[c e g b] [R]              → [B4/4 G4 E4 C4]
[c e g] [I]                → [C4/4 G#3 F3]
[c e g] [I] 5 +            → [F4/4 C#4 A#3]       I5
[c d e g] [RI]             → [F3/4 G#3 A#3 C4]
```

These are the serial row forms: R, I and RI, transposed with `+` (I5 is
`[I] 5 +`). Exactly `[R]` is the word; `[ R ]`, with spaces, is a line
holding one bar's rest.

## 5. Keys

A key is a value too: write it as `!key:` does, a tonic and a scale.

| Word | Stack | Does |
|---|---|---|
| `tonic` | `( key -- note )` | the key's tonic, in octave 4 |
| `scale` | `( key -- line )` | one octave of its scale |
| `degrees` | `( numbers key -- line )` | scale steps from the tonic: 0 the tonic, 7 an octave up |
| `key` | `( note\|line -- key )` | the key a note or line was written in |
| `with` | `( x key -- x )` | played in that key |

```
D.major                         → D.major
D.major tonic                   → D4/4
D.major scale                   → [D4/4 E4 F#4 G4 A4 B4 C#5]
'[0 1 2 3 4] D.major degrees    → [D4/4 E4 F#4 G4 A4]
D.major 2 +                     → E.major
[!key:D.major c d e] key        → D.major
[c d e f g] D.major with        → [!key:D.major C#4/4 D4 E4 F#4 G4]
```

The last one is worth a second look: notes written in C major (the
default) follow the key they're played in, as on a staff, so `c`
plays C♯ and `f` F♯ in D major.

## 6. Changing time

| Word | Stack | Does |
|---|---|---|
| `*` or `stretch` | `( x f -- x )` | every duration times f |
| `/` or `shrink` | `( x f -- x )` | every duration divided by f: `3 /` fits three notes in the time of one |
| `[\|\|]` or `barred` | `( x -- line )` | barred by its meter: a bar line every bar, a note across one cut and tied |
| `[[]]` or `measures` | `( x -- line )` | split by its meter into its measures, each a line of its own |

```
[c d e] 2 *                       → [C4/2 D4 E4]
[c d e] 1/2 *                     → [C4/8 D4 E4]
[c d e] 3 /                       → [3:1(C4/4 D4 E4)]
[c2 d2 e2] [||]                   → [C4/2 D4 | E4]
[!Meter:3/4 c2 d4 e2 f4] [||]      → [!Meter:3/4 C4/2 D4/4 | E4/2 F4/4]
[!Meter:3/4 c2 d2 e2] [||]         → [!Meter:3/4 C4/2 D4/4~ | D4 E4/2]
[!Meter:3/4 c2 d2 e2] [[]]        → [!Meter:3/4 [C4/2 D4/4~] [D4 E4/2]]
```

In the `[||]` example the `d2` doesn't fit in what is left of the first bar
(a quarter), so `[||]` cuts it at the bar line: a quarter, tied (`~`),
and a quarter in the next bar. It still sounds as one half note.
`[[]]` cuts the same way but makes each measure a line of its own,
ready for a word that works per measure (`'( [R] ) map` reverses each
bar). A setting such as `!ff` still carries on into the measures after
it; a ramp (`!vol<`) ends with its measure.

## 7. Dynamics, tempo, instruments: contexts

A context `^{ … }` holds settings, written as in a `.mus` file
(`!tempo:60`, `!ff`, `!i:73`). `with` puts music under one.

| Word | Stack | Does |
|---|---|---|
| `with` | `( x ^{ } -- x )` | the settings in front of the music |
| `cresc` / `dim` | `( x volume -- x )` | a crescendo / diminuendo over it, to a volume 0-100 |
| `defaults` | `( -- ^{ } )` | the context everything starts from |

```
[c d e] ^{ !tempo:60 !ff } with           → [!tempo:60 !ff C4/4 D4 E4]
[c d e] ^{ !i:73 } with                   → [!i:73 C4/4 D4 E4]
[!tempo:90 c d e] ^{ !tempo:60 !ff } with → [!tempo:60 !ff !tempo:90 C4/4 D4 E4]
{[c d] [e f]} ^{ !ff } with               → [!ff {[C4/4 D4] [E4 F4]}]
[c d e f g] 90 cresc                      → a crescendo from where it is to 90
[c d e] 30 dim                            → a diminuendo down to 30
[c8 d e f] ^{ !swing } with               → [!swing C4/8 D4 E4 F4]
```

`!swing` swings the eighths as jazz does: of each pair the first lasts
0.6, the second starts late and lasts the rest. `!shuffle` is the
strict triplet feel of blues and rock (2/3 and 1/3), `!swing:0.75`
harder still (dotted), `!straight` (0.5) not at all, and
`!swingUnit:16` swings sixteenths instead. Only what you hear moves:
the line prints and counts its bars as written. `!laidBack` and
`!pushed` (or `!groove:` from -1 to 1) move the weaker notes a little
late or early, the downbeat staying where it is.

`with` writes the settings at the start of the line, in front of the
line's own. Of two settings for the same thing, the later one counts,
so the line's own still win: in the third example it plays at 90, its
own `!tempo:90` coming after the inserted `!tempo:60`, and takes `!ff`,
because it sets no volume of its own. The result is an ordinary line
of notes, so the words that follow (`rotate`, `within`, `durations` …)
work on its notes as before. A single note becomes a one-note line. A
`{ }` or a repeat has no line to write into, so it is wrapped in one
that holds the settings.

### Filling in, or coming first

`with` fills in what the music leaves open. A context written **before**
the music does the opposite: it comes first, and overrides what the
music says itself. There are two places to write it.

Just before a play word, on top of the stack:

```
[!tempo:90 c d e] ^{ !tempo:60 } >        plays at 60: the context comes first
[c d !ff e f] ^{ !mf } >                  every note mf, the !ff overridden
[c d !ff e f] ^{ !pp } with ^{ !mf } >    every note mf: with filled in pp, > puts mf first
```

Inside a line, from where it is written, nested lines included:

```
[c d ^{ !mf } e [!ff f g]]                c d at the default, e f g mf
```

The difference in one sentence: `with` changes the music (its result
is a new line, stored or played later), a context before `>` or in
front of notes is how that music is played here, and so it wins.

## 8. Repeats

| Word | Stack | Does |
|---|---|---|
| `repeat`, `times` or `[*]` | `( x n -- repeat )` | played n times, as the text's `repeat n [ x ]` and LilyPond's |
| `[:]` or `repeats` | `( x -- [: x :] )` | in repeat signs: played twice |
| `[~]` or `cycle` | `( x -- loop )` | endlessly; data or a stream as an endless stream, printed `'[1 2] [~]` |
| `[?]` or `shuffling` | `( x -- x )` | shuffled as it plays: a new order each time, so each pass of a repeat |
| `rotating` | `( x n -- x )` | rotated n places further each time it plays |
| `[\|]` or `choosing` | `( x q … -- x )` | one of the alternatives each time it plays: the quotations applied to x, or x's own elements |
| `[\]` or `alternating` | `( x q … -- x )` | the alternatives in turn, one each time it plays |

```
[c d e g] '( [I] ) '( [R] ) [|] [~]   → repeat ~ [choose [[C4/4 A#3 G#3 F3] [G4 E4 D4 C4]]]
```

Each pass of that loop plays the inversion or the retrograde, picked at
random: the text's `choose [ … ]`, which plays one of its elements each
time it is entered. `[\]` takes them in turn instead -- I, R, I, R --
the text's `alternate [ … ]`. Weights go on top, one per alternative:
`[[c d] [e f]] '[3 1] [|]` gives `choose 3,1 [ … ]`, the first three
times as often as the second.

`TRP`, `SFL` and `ROT` are short for `transpose`, `shuffle` and `rotate`,
in mu and in a `.mus` file alike (`[c d] 5 TRP`, `TRP 5 ( c d )`).
| `volta` | `( repeat ending … -- repeat )` | endings above a repeat, one after each pass |
| `vary` | `( repeat q … -- line )` | each pass changed by a quotation, one for all or one a pass |
| `[!]` or `unfold` | `( x -- line )` | written out as it plays |

The number is how often it plays: `3 repeat` plays three times, as
`repeat 3 [ ]` does in the text and in LilyPond. `times` is the same
word in plain English.

```
[c d] 3 times                                → repeat 3 [C4/4 D4]
[c d] 3 repeat                               → repeat 3 [C4/4 D4]
[a b c d] 2 times [c2 b a] [a4] volta        → repeat 2 [A3/4 B3 C4 D4] volta [[C4/2 B3 A3] [A3/4]]
[c d] 3 times '( ) '( 12 + ) '( [R] ) vary   → [[C4/4 D4] [C5 D5] [D4 C4]]
[c d] [:]                                    → [: C4/4 D4 :]
[c d] [:] [!]                                → [[C4/4 D4] [C4 D4]]
[c d] cycle 5 take                           → [C4/4 D4 C4 D4 C4]
[c d e f] [?] [~]                            → repeat ~ [shuffle [C4/4 D4 E4 F4]]   a new order every pass
```

`[?]` makes the text's `shuffle [ … ]`: the order is decided each time
it plays, so a loop of it never plays the same way twice in a row
(`shuffle` shuffles once, now).

`vary` runs its first quotation on the first pass, the second on the
second, and so on.

`[!]` writes music out **as it plays**: repeats become their passes,
endings follow the pass they belong to, jumps are taken. What you get
is a plain line with nothing left to follow:

```
[c d] 2 times [e] [f] volta [!]          → [[C4/4 D4] [E4] [C4 D4] [F4]]
[c $ d DS e] [!]                         → [C4/4 D4 D4 E4]       back to the segno $ once
[c d | ./. | e] [!]                      → [C4/4 D4 | C4 D4 | E4]  ./. is the bar before, again
```

Use it to see what a repeat or a jump actually plays, or before a word
that should work on every note as heard (a `[||]`, a `mute`).

## 9. Reading notes

A note is made of half products: its pitch, its duration, its volume,
its articulation, its instrument. Each has two words: the singular
reads **one note**, the plural reads **every note of a line**, as a
stream (a list).

| One note | Every note | Gives |
|---|---|---|
| `pitch` | `pitches` | the pitch: `60`, a chord's pitches `'[60 64 67]`, nil for a rest or drum |
| `duration` | `durations` | the length, in whole notes |
| `volume` | `volumes` | its own volume (`c4\f`, `c4\vol:90`), else nil |
| `articulation` | `articulations` | its articulation (`:accent`, `:staccato` …), else nil |
| `instrument` | `instruments` | its own instrument (`c4\i:40`), a drum's key, else nil |

`note?`, `chord?`, `rest?` and `drum?` tell what one note is.

```
c4 pitch                 → 60
<c e g>4 pitch           → '[60 64 67]
c8. duration             → 3/16
c4\vol:90 volume         → 90
c4\f volume              → 70
c4-> articulation        → :accent
c4\i:40 instrument       → 40
c4 note? <c e>4 chord?   → true   true
```

The singular on a line is an error that names the plural:

```
[c d] pitch              → pitch: reads one note; for every note of a line, use pitches
```

## 10. Taking a line apart

The plurals take a line apart into its half products, and `zip` puts
durations and pitches back together:

```
[c8 <e g>4 r8 d] durations       → '[1/8 1/4 1/8 1/8]
[c8 <e g>4 r8 d] pitches         → '[60 '[64 67] nil 62]
[c d\vol:90 e] volumes           → '[nil 90 nil]
'[/4 /8 /8] '[60 62 64] zip      → [C4/4 D4/8 E4]
```

A chord's list zips back into a chord, nil into a rest. So
`x durations x pitches zip` gives `x` back, and anything you do in
between changes just that half of the line:

```
[c8 d4 e8 g4] dup durations swap pitches zip           → [C4/8 D4/4 E4/8 G4/4]
[c8 d4 e8 g4] dup durations retrograde swap pitches zip   → [C4/4 D4/8 E4/4 G4/8]
```

The second keeps the melody and plays the rhythm backwards. (`dup`
copies the line; `swap` brings the original back on top.)

A loop stays a loop: `[c d e] [~] pitches` is `'[60 62 64] [~]`. Two
loops of different lengths zip into one long loop, the notes they take
to line up again -- three durations against four pitches drift through
twelve notes:

```
[c8 d4 e8] [~] durations [c d g a] [~] pitches zip
    → repeat ~ [C4/8 D4/4 G4/8 A4 C4/4 D4/8 G4 A4/4 C4/8 D4 G4/4 A4/8]
```

## 11. Choosing notes

These take a test, a quotation run on each note that leaves true or
false. `'( pitch 65 lt )` asks "is the pitch below 65?" (`lt` compares
numbers; `eq` compares anything, music by its notes: `[c e g] [C4 E4
G4] eq` is true, however each was written).

| Word | Does |
|---|---|
| `filter` | keeps the notes that pass |
| `remove` | drops them: the line gets shorter, later notes move earlier |
| `mute` | turns them into rests of the same length: the rhythm stays |

```
[c e g c'] '( pitch 65 lt ) filter       → [C4/4 E4]
[c e g c'] '( pitch 65 lt ) remove       → [G4/4 C6]
[c e g c'] '( pitch 65 gt ) mute         → [C4/4 E4 r r]
[c8 d4 e8 f2] '( duration 1/4 lt ) mute  → [r8 D4/4 r8 F4/2]
```

To change just a part of a line, `within` takes the elements from a
start up to (not including) an end, counted from 0, runs a quotation
on them and puts the result back:

```
[c d e f g] 1 4 '( [R] ) within          → [C4/4 F4 E4 D4 G4]
[c d e f g] 1 3 slice                    → [D4/4 E4]
[c d e f g] 1 3 [a b c] splice           → [C4/4 A4 B4 C5 F4 G4]
```

`slice` and `splice` are its two halves, for when the change takes
several steps.

## 12. Instruments and drums

An instrument is a General MIDI program (`\i:40` is a violin), set on
a note or for a whole line; a list plays several at once. A drum is
`x` with its key or name; a bare `x` repeats the last one.

```
[!i:40,73 c d e]                    violin and flute in unison
[x8\36 x x\38 x]                    kick, kick, snare, snare
x8\kick,clap                        → x8\36,39   one stroke, two sounds
```

| Word | Stack | Does |
|---|---|---|
| `sounds` | `( note numbers -- line )` | a copy per number: instruments for a note, keys for a drum |
| `combos` | `( note numbers k -- line )` | a copy per k of the numbers layered |

```
c4 '[40 73 0] sounds        → [C4/4\instrument:40 C4\instrument:73 C4\instrument:0]
x8\36 '[36 38 42] sounds    → [x8\36 x\38 x\42]
x8\36 '[36 38 42] 2 combos  → [x8\36,38 x\36,42 x\38,42]
```

`sounds` is the way to audition a soundfont: build a line of sounds
and play it, or rate them (section 16).

## 13. Keeping things

| Word | Does |
|---|---|
| `'name value :=` | bind a name for this session; `\name` is the value |
| `R!` or `store` | store in the repo, under an id: `[c d e] :mel R!` |
| `R@` or `fetch` | fetch from the repo: `:mel R@` |
| `: name ( a -- b ) … ;` | define a word of your own |

```
'motif [c e g] :=
\motif 5 +              → [F4/4 A4 C5]
[c d e] :mel R!         → :mel
:mel R@ 7 +             → [G4/4 A4 B4]
```

## 14. One tree: the color-talea

A **color-talea** is the medieval isorhythm: a list of pitches (the
*color*) and a list of durations (the *talea*) of different lengths,
each cycling on its own. A color of 5 pitches against a talea of 3
durations only lines up again after 15 notes, so the melody keeps
meeting the rhythm in a new place.

Each half is a stream, cycled forever and then cut to 15, and `zip`
joins them:

```
'[/4 /8 /8] cycle 15 take '[60 62 64 65 67] cycle 15 take zip
→ [C4/4 D4/8 E4 F4/4 G4/8 C4 D4/4 E4/8 F4 G4/4 C4/8 D4 E4/4 F4/8 G4]
```

- `'[/4 /8 /8]` is the talea, written as bare durations in a data list.
- `cycle` repeats it forever (on data, an endless stream), `15 take` keeps the first 15.
- `'[60 62 64 65 67]` is the color; `[c d e f g] pitches` gives the same
  numbers from notes.

It's worth a word of its own. `$talea`, `$color` and `$n` are the
word's inputs, named in its stack effect:

```
: isorhythm ( talea color n -- line ) $talea cycle $n take $color cycle $n take zip ;

'[/4 /8 /8] [c d e f g] pitches 15 isorhythm [||]
→ [C4/4 D4/8 E4 F4/4 G4/8 C4 | D4/4 E4/8 F4 G4/4 C4/8 D4 | E4/4 F4/8 G4]

'[/4. /8 /4 /4] [c e g b d'] pitches 20 isorhythm >
```

Barring it with `[||]` shows the shift: each bar starts on another note
of the color. The result is an ordinary line, so everything above
applies: `5 +`, `[R]`, `'( … ) mute`, `1 4 '( [R] ) within`.

### The same as an algo tree

In Clojure, the same isorhythm is a tree of three tools, with its
settings kept apart from it (`lein repl`, where `t` and the tools are
already referred):

```clojure
(def isorhythm (take> (zip (cycle> [1/4 1/8 1/8]) (cycle> [60 62 64 65 67]))))
isorhythm
;; #node (take> (zip (cycle> [1/4 1/8 1/8]) (cycle> [60 62 64 65 67])))
```

The tree only says what is computed. Its settings live apart from it,
in a **tctx**: an ordinary musics context, the same kind of thing a
`^{ }` makes, holding the algo settings as `@` names:

```clojure
(def tctx (t/tctx isorhythm))
tctx
;; {:envelopes-atom #object[clojure.lang.Atom 0x719de15f {:status :ready, :val {}}], :duration nil}

(t/setp! tctx :len 15)             ; take> keeps 15; returns every param's value
;; {:len 15}
tctx
;; {:envelopes-atom #object[clojure.lang.Atom 0x719de15f {:status :ready, :val {"@len" 15}}], :duration nil}

(t/params tctx)                    ; every param's value, set or default
;; {:len 15}

(notes->mus (t/run isorhythm tctx))
;; "[ !acc:explicit C4/4 D4/8 E4/8 F4/4 G4/8 C4/8 D4/4 E4/8 F4/8 G4/4 C4/8 D4/8 E4/4 F4/8 G4/8 ]"
```

- A new tctx is empty (`:val {}`): every param is at its default
  (`take>` keeps 16).
- `setp!` stores `:len` under its context name, `"@len"`, and checks it
  first: `(t/setp! tctx :len 999)` is refused, the range is 0..256.
- Since it is a context, the same setting can be written in musics
  text, `^{ @len:15 }`, and handed to `t/run` after the tctx.

`cycle>`, `zip` and `take>` do what `cycle`, `zip` and `take` do in mu.
`notes->mus` writes the result as musics text, which mu reads as it
is: paste it at the mu prompt and it's a line on the stack.

## 15. Algos as words

The algos of the trees are mu words too, named by what they deliver:
`duration/euclid` gives durations, `pitch/lorenz` pitches,
`volume/walk` volumes, `parallel/drums` a `{ }` of drum parts. `words`
lists them by namespace with their counts, and `"duration/euclid" see`
says what one does and which settings it reads:

```
"duration/euclid" see
duration/euclid ( [^{ @… }] -- durations )
  Distribute k beats evenly among n pulses (Bjorklund's algorithm). …  @k @n @rotation @duration-pulse
```

A source takes nothing from the stack. A context on top, `^{ … }`, is
its settings, each written `@name:value` (`!` sets the music, `@` an
algo):

```
duration/euclid                                        → '[3/16 3/16 1/8]
^{ @k:5 @n:8 @duration-pulse:1/8 } duration/euclid     → '[1/4 1/8 1/4 1/8 1/4]
^{ @k:3 @n:8 @duration-pulse:1/8 } duration/euclid [c e g] pitches zip
                                                       → [C4/4. E4 G4/4]
```

Three beats among eight eighths is the tresillo. A setting the context
doesn't give falls back to the music's own (so `!key:` reaches an algo
that reads a key), then to the algo's default.

An algo that varies something takes it from the stack, a line taken
apart into what it wants: durations, pitches or the notes themselves.
`duration/necklace` gives a rotation of a rhythm on its grid:

```
[c4. d8 e4] durations ^{ @index:1 @duration-pulse:1/8 } duration/necklace
                                                       → '[r4 1/8 1/4 1/8]
```

A loop around what it is given stays around what it gives -- the algo
works on what the loop repeats -- so a loop of a rhythm varied is a
loop of the varied rhythm, ready to zip with a loop of pitches:

```
[c4. d8 e4] [~] durations ^{ @index:1 @duration-pulse:1/8 } duration/necklace
    → '[r4 1/8 1/4 1/8] [~]
^{ @k:3 @n:8 @duration-pulse:1/8 } duration/euclid [~] [c e g a] [~] pitches zip
    → repeat ~ [C4/4. E4 G4/4 A4/4. C4 E4/4 G4/4. A4 C4/4 E4/4. G4 A4/4]
```

The tresillo's three notes against four pitches line up again after
twelve: the melody keeps landing on another beat, the color-talea of
section 14 written with an algo.

## 16. Rating what you hear

mu keeps your taste in sounds (`~/.mu-feedback.edn`):

| Word | Does |
|---|---|
| `like` `meh` `dislike` | rate what was just heard (Alt+l Alt+k Alt+j at the prompt) |
| `audition` | `( line -- )` play its sounds one by one, in the background, to rate each |
| `gap` | `( ms -- )` the silence between an audition's sounds, 150 by default |
| `next` `prev` `again` | move through an audition (Alt+L Alt+J Alt+K) |
| `taste` | your ratings, per sound and per instrument |
| `liked` / `disliked` | the sounds you rated, as a line |

```
c4 '[0 40 73] sounds audition
```

## 17. The same words in Clojure

Every word with a Clojure name is also a Clojure function, under `mu/`
in `lein repl`, taking its arguments in mu's order:

```clojure
(def x (mu/mu "[c d e g]"))     ; read a literal (or run a phrase)
(mu/retrograde x)               ; #mu "[G4/4 E4 D4 C4]"
(mu/R x)                        ; the same, short
(mu/= x (mu/mu "[C4 D4 E4 G4]")) ; true: = is eq, music by its notes
(mu/times x 3)                  ; #mu "repeat 3 [C4/4 D4 E4 G4]"
(mu/mute x #(> (mu/pitch %) 63))         ; a fn where mu takes '( … )
(apply mu/line (take 2 (mu/elements x))) ; Clojure's own take, filter, map …
```

What Clojure already has (`take`, `filter`, `first`, `map` …) is
Clojure's: `mu/elements` gives a value's notes as a Clojure seq, and
`mu/line` makes music of them again.

## All the music words

| Word | Stack |
|---|---|
| `<>` `chord` | `( notes -- chord )` |
| `[2]` `[3]` … `[n]`, `n line` | `( a … -- line )` |
| `{2}` `{3}` … `{n}`, `n together` | `( a … -- parallel )` |
| `{+}` `beside` | `( a b -- parallel )` |
| `[!]` `unfold` | `( x -- line )` |
| `[M]` `mirror`, `[R]` `retrograde`, `[I]` `invert`, `[RI]` `retrograde-inversion` | `( x -- x )` |
| `[:]` `repeats` | `( x -- [: x :] )` |
| `[\|\|]` `barred`, `[[]]` `measures` | `( x -- line )` |
| `[~]` `cycle` | `( x -- loop )` |
| `[*]` `times` | `( x n -- repeat )` |
| `+` `-` `*` `/`, `up` `down` `stretch` `shrink` | `( x n -- x )` |
| `cresc` `dim` | `( x volume -- x )` |
| `pitch` `duration` `volume` `articulation` `instrument` | `( note -- value )` |
| `pitches` `durations` `volumes` `articulations` `instruments` | `( line -- stream )` |
| `chord?` `drum?` `note?` `rest?` | `( note -- flag )` |
| `combos` | `( note numbers k -- line )` |
| `degrees` | `( numbers key -- line )` |
| `key` | `( note\|line -- key )` |
| `mute` | `( x q -- x )` |
| `par` `seq` | `( \| … -- parallel \| line )` |
| `repeat` | `( x n -- repeat )` (n plays, as `times`) |
| `sounds` | `( note numbers -- line )` |
| `stretch` | `( x f -- x )` |
| `tonic` | `( key -- note )` |
| `transpose` | `( x from to -- x )` |
| `vary` | `( repeat q … -- line )` |
| `volta` | `( repeat ending … -- repeat )` |
| `with` | `( x ^{ }\|key -- x )` |
| `zip` | `( durations pitches -- line )` |

`"word" see` shows any of them with what it does; `words` lists them
all.
