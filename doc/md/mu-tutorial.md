# mu tutorial: the music words

mu is musics' concatenative, real-time, interactive REPL: you type
music, and it lands on a stack where words change it, combine it and
play it, while it plays. This tutorial walks
through the words that work on music, from writing a note to building
a color-talea. Every example was run in mu; the line under `→` is what
mu printed.

Start mu with `lein mu`; its first line shows the version (`lein mu --version` prints only that). `bye` or Ctrl+D
leaves; the stack stays for the session.

## 0. The stack

The prompt `mu<0>` counts what is on the stack. These words move things
around on it, and you'll need them throughout:

| Word | Stack | Does |
|---|---|---|
| `.` | `( a -- )` | print the top and drop it |
| `.s` | `( -- )` | show the stack, top first |
| `,` | `( a -- )` | take the top back into the prompt as text, to change and run again |
| `dup` | `( a -- a a )` | copy the top |
| `swap` | `( a b -- b a )` | exchange the top two |
| `rot` | `( a b c -- b c a )` | the third comes on top |
| `drop` | `( a -- )` | discard the top |
| `over` | `( a b -- a b a )` | copy the second to the top |
| `nip` `tuck` | `( a b -- b )` `( a b -- b a b )` | drop the second; copy the top under the second |
| `2dup` `2drop` | `( a b -- a b a b )` `( a b -- )` | copy or drop the top two |
| `-rot` | `( a b c -- c a b )` | the top goes third: `rot` the other way |
| `pick` `roll` | `( … n -- … )` | copy or move the n-th item to the top, the top being 0 |
| `clear` | `( … -- )` | clear the stack (`cls` clears the screen) |
| `see` | `( "word" -- )` | what a word does: `see dup`, or `"dup" see` |
| `about` | `( ["name"] -- )` | what a name is and where it can appear, one sentence: `about tempo`, `about violin`, `about allegro` |
| `settings` | `( "name" -- )` | a parameter's range, default and how it is written: `settings tempo` (a voice on top: its live settings) |
| `marks` | `( "name" -- )` | the marks that set a parameter, by value: `marks tempo` |
| `help` | `( ["text"] -- )` | mu in a few lines; with a word, as `see`; with other text, the words that mention it: `"chord" help`; `help dup` looks `dup` up |

A **quotation** `'( … )` is a piece of mu held for later, run by the
words that take one (`vary`, `filter`, `within` …). The spaces inside
are optional: `'(12 +)` is `'( 12 + )`.

### Asking mu

After each input mu prints `ok`, or `error:` and the reason, and an error leaves the
stack as it was. The words that look things up read the word after them, as Forth's `see`
does, so `help dup` and `see dup` do not run `dup`:

```
help dup                 → dup ( x -- x x )
                           copy the top
about tempo              → tempo is a musical parameter (Tempo|T) and can appear in ^{ }, { } and [ ].
about pitch              → pitch is a property of a note or chord.
                           pitch is a word of mu ( note -- pitch ).
settings tempo           → 30..300, default 100, Beats per minute. Written !tempo:100, or on a line: x !tempo:100 with
marks tempo              → !adagissimo !larghissimo !grave !largo … !presto !prestissimo
```

`about` says in a sentence what a name is: a parameter, a mark, a word, an instrument, a drum, a
scale, a chord quality, an articulation or a rule of the grammar (written as the grammar writes it,
`about Leaf`), and where it can appear (notes only in `[ ]`). `settings` and `marks` say more about a
parameter: its range and default, and the named values that set it, slowest to fastest, loudest
last. A mistake explains itself when it can:

```
[1 2 3]                  → numbers in a list are data: '[1 2 3]
'n 5 :=
n                        → unknown word: n -- n is a value, not a word: \n puts it on the stack
\n                       → 5
```

### Numbers, comparison, conditions

Arithmetic is `+ - * /`, the same signs that move and stretch music
(section 4 and 6): on two numbers they add, subtract, multiply and divide
exactly (`7 2 /` is `7/2`); a number with a list works on each element, on
either side; two lists work in step, as long as the shorter.

```
3 4 +                  → 7
7 2 /                  → 7/2
'[60 62] 12 +          → '[72 74]
10 '[1 2 3] -          → '[9 8 7]
'[1 2 3] '[10 20] +    → '[11 22]
7 1+                   → 8                    1+ and 1- are one more, one less
9 7 mod                → 2                    mod negate abs min max round int float rational
```

Comparison is Forth's: `= <> < > <= >=`, giving `true` or `false`. The order
signs take numbers, ratios and durations (`/4 /8 >` is true), or anything by a
key, a quotation on top that reads a number from each (`c d '( pitch ) <`,
`[c d] [e f g] '( duration ) <`, and `[e c d] '( pitch ) sort` orders a line); `=` and `<>`
take any two values, two numbers by value (`1 1.0 =`), music by its notes
(`[c d] [c d] =`), data element by element.

```
1 2 <                  → true
/4 /8 >                → true
1 1.0 =                → true
[c d] [c d] =          → true
```

A **flag** is `true` or `false` and nothing else: `true false and or xor not`
join them, and `if` and `when` run a quotation by one. A number or a note in a
flag's place is refused, naming it.

```
1 2 < 3 4 < and                    → true
1 2 < '( :yes ) '( :no ) if        → :yes
5 1 2 > '( drop ) when             → 5          the quotation did not run
0 not                              → ERR not: needs a flag (true or false), got 0
```

An error leaves the stack as it was before the input, so a typo costs the line,
not what you built. A word mu does not know says what it is now called, or
what is close to it, and `help` shows the way in:

```
3 5 add                → ERR unknown word: add -- it is now written +
retrogrde              → ERR unknown word: retrogrde -- did you mean retrograde?
```

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
  note before. A capital letter with an octave is absolute: `C5`; a
  capital without one keeps the octave before it (`C5 D E`, `<C5 E G>`).
- mu prints back absolutely (`C4/4`), and what it prints reads back
  as the same thing.

## 2. Listening

First connect a synthesizer: `gervill` (Java's own, nothing to set up)
or `fluidsynth` (through qsynth, see `doc/md/setup.md`). If the sound
output doesn't open within 5 s (a Bluetooth speaker asleep), mu says so
instead of waiting; `doc/md/setup.md` has what to do.

| Word | Does |
|---|---|
| `->` or `play` | play now, stopping what plays |
| `->+` or `play+` | play now, joining what plays |
| `->>` or `play-next` | play in place of what plays, at its next boundary: the end of it, or of its repeat's pass |
| `x \| ->>` | the same, at the next bar line (`\|\|` … `\|\|\|\|` for stronger ones) |
| `->>+` or `play-next+` | join at the next boundary |
| `stop` | stop everything (or Alt+., even mid-typing); `:bass stop` stops one voice |
| `pause` | pause; `pause` again goes on (or Alt+,) |
| `voices` or `<<-` | what plays, the highest voice on top: each voice's index (`highest` … `1 lowest`, `0 drums` …), id and music |
| `:bass playing` or `:bass <-` | the music the voice `:bass` plays, onto the stack |
| `:bass hush` / `:bass solo` | silence a voice / let only it sound, keeping its place; again to undo |

`->` plays now, `->>` at the next boundary; a `+` joins what plays
instead of replacing it.

```
[c d e f g] ->
[c d e f g] [~] ->        loop it until stop
[e f g a b] ->>           takes over at the end of the current pass
```

Music with a name plays as a voice of that name, and playing it again
replaces only that voice, the others going on:

```
[bass: c2 g] [~] ->           the voice :bass
[mel: e8 f g a] [~] ->        a second voice, :mel; :bass goes on
[bass: f2 c] [~] ->>          :bass changes at the end of its pass
:mel hush                    silence :mel, keeping its place
:mel hush                    and back
```

Music without a name is no voice of its own: `->` stops everything,
`->+` adds it under an id the engine gives (`:TA` …), shown by
`voices`.

Voices also go by index, low to high: `1` the lowest voice, `2` the
next …, and the drum voices from `0` down (`0`, `-1` …, lowest drum key
first). An index or id on top of a play word plays as that voice; on
its own, with no music under it, the voice starts its own music again:

```
1 <-                  what the lowest voice plays
3 <- 12 + 3 ->>        voice 3 an octave up, at its boundary
0 hush               silence the drums
1 ->                  voice 1 from its start, now
:bass ->>             :bass from its start, at the end of its pass
```

`echo` prints each note as it sounds; `echo` again stops it.

`gui` opens musics' GUI beside mu, on what mu plays: the transport, the
parts and their contexts as sliders, the Wall and Record MIDI. Closing
it leaves mu running, and `gui` opens it again.

## 3. Putting music together

| Word | Stack | Does |
|---|---|---|
| `[2]` `[3]` … | `( a … -- line )` | the top n one after another |
| `( … )` | `( -- line )` | what runs inside, gathered into one line (or a data list, for numbers) |
| `{2}` `{3}` … | `( a … -- parallel )` | the top n at once |
| `par` | `( \| … -- parallel )` | everything above a `\|` mark side by side: `\| [c d] [e f] par` |
| `[+]` or `concat` | `( a b -- line )` | two lines joined into one |
| `{+}` | `( a b -- parallel )` | the parts of two parallels in one |
| `[H]` or `head`, `[T]` or `tail` | `( x -- elem )`, `( x -- x' )` | the first element (`head`) and all after it (`tail`); `take` is the first n |
| `chord` | `( \| notes -- chord )` | the notes above the nearest `\|` mark, else the whole stack, as a chord |
| `spread` or `**` | `( x -- a b … )` | the other way round: x's elements on the stack, a line's notes or a parallel's parts |

The pairs differ in what they take and what they make of it:

- `( … )` gathers **whatever runs inside it**, as many as you like, each
  staying an element of its own: music into a line, numbers into a data
  list. `par` (and `zip`, `>ctx`, `chord`) gather **everything above a
  `|` mark**, or the whole stack when there is none, instead: `| [c d] [e f] par` is the
  parallel of the two lines.
- `[n]` and `{n}` take **the top n**, `[2]`, `[3]`, … and `{2}`, `{3}`,
  …, each staying an element (a part) of its own.
- `[+]` and `{+}` **join** the top two: `[+]` two lines into one flat
  line, `{+}` the parts of two parallels into one `{ }` (a line counts
  as one part).

`[ ]` makes or acts on a line, `{ }` makes or acts on a parallel.

```
( c d e )               → [C4/4 D4 E4]
( [c d] [e f] )         → [[C4/4 D4] [E4 F4]]       two lines, one after the other
[c d] [e f] [2]         → [[C4/4 D4] [E4 F4]]       the same, counted
[c d] [e f] [+]         → [C4/4 D4 E4 F4]           one line of four notes
| [c d] [e f] [g a] par → {[C4/4 D4] [E4 F4] [G4 A4]}
[c d] [e f] {2}         → {[C4/4 D4] [E4 F4]}
[c] [e] [g] {3}         → {[C4/4] [E4] [G4]}
{[c] [d]} {[e] [f]} {+} → {[C4/4] [D4] [E4] [F4]}
[c d e f] [H]           → C4/4                      the head, one element
[c d e f] [T]           → [D4/4 E4 F4]              the tail
[c d e f] 2 take        → [C4/4 D4]
c e g chord             → <C4 E4 G4>4
[c d | e] spread        → C4/4 D4/4 E4/4            three notes, the bar line left out
{[c d] [e f]} **        → [C4/4 D4] [E4/4 F4]       two lines
[c d e] spread swap [3] → [C4/4 E4 D4]              the last two exchanged
```

`spread` undoes `[n]` and `{n}`: the elements come onto the stack one
by one, where the stack words reach each of them, and `[n]` or `{n}`
puts them back together. Bar lines and jump marks are left out, since
they mean nothing outside their line.

A group makes one line of what would be several values, so the next word
reaches all of it: `( [c d] 2 times [e] [f] volta ) 1 +` moves the repeat
and its endings together, under one key.

Both lines play the same four notes; the first keeps the two
lines apart, so a word can still treat them as two (`[R]` swaps the
lines, where on the flat line it reverses the notes).

### Parts that enter late

`delay` puts a rest in front of a line, a length in whole notes, any
rational. `delays` does it for the parts of a `{ }`: a number delays part
k by k times it, a canon at that distance, and a data list gives each
part its own delay, one for each part:

```
[c d] 1/4 delay                         → [r4 C4 D4]
[c d] 3/8 delay                         → [r4. C4/4 D4]
[c d] 1/3 delay                         → [3:1(r1) C4/4 D4]
{[c d] [c d] [c d]} 1/4 delays          → {[C4/4 D4] [r C4 D4] [r2 C4/4 D4]}
{[c d] [c d]} '[0 1/3] delays           → {[C4/4 D4] [3:1(r1) C4/4 D4]}
```

The delay is a rest in the music, so `duration`, `bar` and `at` see it, and
a `{ }` still ends with its longest part. A delay of 0 changes nothing. (A
whole number among ratios is a ratio, so `'[0 1/3]` is a list of ratios.)

## 4. Changing pitch

A bare symbol works on every note; the same symbol in brackets works
on the line as a whole.

| Word | Stack | Does |
|---|---|---|
| `+` / `-` | `( x n -- x )` | every pitch up / down n semitones; `+` and `-` also add and subtract numbers, and work through a list of numbers (`'[60 62] 12 +` is `'[72 74]`) |
| `transpose` | `( x from to -- x )` | moved by the interval between two notes |
| `[R]` or `retrograde` | `( x -- x )` | backwards, as it sounds: ties and dynamics turn round with it |
| `[I]` or `inversion` | `( x -- x )` | mirrored around the first note, which stays |
| `[RI]` or `retrograde-inversion` | `( x -- x )` | inverted, then backwards |
| `[M]` or `mirror` | `( x -- x )` | mirrored around the mean pitch, keeping the register |

```
[c e g] 7 +                → [!K:G.major G4/4 B4 D5]
[c e g] 12 -               → [C3/4 E3 G3]
[c e g] c d transpose      → [!K:D.major D4/4 F#4 A4]
[c e g] [M]                → [G#4/4 E4 C#4]
[c e g b] [R]              → [B4/4 G4 E4 C4]
[c e g] [I]                → [C4/4 G#3 F3]
[c e g] [I] 5 +            → [!K:F.major F4/4 D&4 B&3]       I5
[c d e g] [RI]             → [F3/4 G#3 A#3 C4]
```

Moving music moves its key with it: `+`, `-` and `transpose` write the
moved key at the start of the line (its own `!K:` moved, else the one
it is in), so the notes stay what they were in their key and play so
under any other; whole octaves leave the key alone.

These are the serial row forms: R, I and RI, transposed with `+` (I5 is
`[I] 5 +`). Exactly `[R]` is the word; `[ R ]`, with spaces, is a line
holding one bar's rest.

## 5. Keys

A key is a value too: write it as `!K:` does, a tonic and a scale
(`!K:` is the key's name, like `!T:` the tempo's and `!M:` the meter's;
`!Key:`, `!key:` and `!k:` mean the same).

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
[!K:D.major c d e] key          → D.major
[c d e f g] D.major with        → [!K:D.major C#4/4 D4 E4 F#4 G4]
```

The last one is worth a second look: notes written in C major (the
default) follow the key they're played in, as on a staff, so `c`
plays C♯ and `f` F♯ in D major.

## 6. Changing time

| Word | Stack | Does |
|---|---|---|
| `*` | `( x f -- x )` | every duration times f; `*` also multiplies numbers and the numbers of a list |
| `/` | `( x f -- x )` | every duration divided by f: `3 /` fits three notes in the time of one; `/` also divides numbers, exactly (`1 4 /` is `1/4`) |
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

A meter is `!M:` (also `!Meter:`, `!meter:`): a fraction (`!M:6/8`), additive
groups in quotes (`!M:"7/8(2+2+3)"`), or the factors of its pulses,
each an eighth: `!M:223` is 2 x 2 x 3 eighths, a 12/8 heard as four
dotted quarters, `!M:232` the same bar as six quarters, `!M:2232/16`
in sixteenths. The grouping is what the accents and the groove hear
(section 7).

```
[!M:6/8 c4. d8 e f g4.] [||]             → [!M:6/8 C4/4. D4/8 E4 F4 | G4/4.]
[!M:223 c4. d e f g a b c] [||]          → [!M:223 C4/4. D4 E4 F4 | G4 A4 B4 C5]
[!M:"7/8(2+2+3)" c4 d e8 f g a b] [||]   → [!M:"7/8(2+2+3)" C4/4 D4 E4/8 F4 G4 | A4 B4]
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
(`!tempo:60`, `!ff`, `!i:73`). `with` puts music under one. A tempo
names its beat as a note value: `!T:8=120` (eighths), `!T:4.=80` and
`!T:2.=60` (dotted quarters and halves), `!T:3/8=80` as a fraction.

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

`!swing` and the other settings of feel are below, in "Feel".

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
[!tempo:90 c d e] ^{ !tempo:60 } ->        plays at 60: the context comes first
[c d !ff e f] ^{ !mf } ->                  every note mf, the !ff overridden
[c d !ff e f] ^{ !pp } with ^{ !mf } ->    every note mf: with filled in pp, -> puts mf first
```

Inside a line, from where it is written, nested lines included:

```
[c d ^{ !mf } e [!ff f g]]                c d at the default, e f g mf
```

The difference in one sentence: `with` changes the music (its result
is a new line, stored or played later), a context before `->` or in
front of notes is how that music is played here, and so it wins.

### Feel: swing, groove, accents

These settings change only what you hear: the line prints, and counts
its bars, as written, so a swung part stays in step with a straight one.

| Setting | Does |
|---|---|
| `!swing` | swings the eighths as jazz does: of each pair the first lasts 0.6, the second starts late |
| `!shuffle` | the strict triplet feel of blues and rock, 2/3 and 1/3 |
| `!swing:0.7`, `!straight` | any ratio from 0.5 (straight) to 0.75 (dotted) |
| `!swingUnit:16` | swings sixteenths instead of eighths |
| `!laidBack`, `!pushed`, `!groove:-1..1` | the weaker a note's place in the bar, the later (or earlier) it comes, up to a 32nd, the downbeat staying; live, a note comes at most 100 ms early (at `!groove:-1` below about `!T:75`) |
| `!accent:metric` | the strong places of the bar louder, the weak ones softer, by the meter's levels |
| `!accent:barlow` | the same by Barlow's finer ranking, every place its own weight |
| `!accent:agogic` | the longer notes louder |
| `!accentDepth:0..1` | how much the accent weighs (0.5) |

```
[!shuffle c8 d e f g a b c] [~] ->
[!laidBack c16 d e f g a b c d e f g a b c d] [~] ->
[!M:223 !accent:metric c8 c c c c c c c c c c c] [~] ->
[!M:232 !accent:metric c8 c c c c c c c c c c c] [~] ->>
```

The last two are the same twelve eighths: `223` is heard as four
groups of three, `232` as six groups of two.

## 8. Repeats

| Word | Stack | Does |
|---|---|---|
| `times` or `[*]` | `( x n -- repeat )` | played n times, as the text's `repeat n [ x ]` and LilyPond's |
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

| `volta` | `( repeat ending … -- repeat )` | endings above a repeat, one after each pass |
| `vary` | `( repeat q … -- line )` | each pass changed by a quotation, one for all or one a pass |
| `[!]` or `unfold` | `( x -- line )` | written out as it plays |

The number is how often it plays: `3 times` plays three times, as
`repeat 3 [ ]` does in the text and in LilyPond.

```
[c d] 3 times                                → repeat 3 [C4/4 D4]
[c d] 3 times                                → repeat 3 [C4/4 D4]
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
| `duration` | `durations` | the length, in whole notes (a line's too: `[c4 d4 e2] duration` is 1) |
| `volume` | `volumes` | its own volume (`c4\f`, `c4\vol:90`), else nil |
| `articulation` | `articulations` | its articulation (`:accent`, `:staccato` …), else nil |
| `instrument` | `instruments` | its own instrument (`c4\i:40`), else nil (a drum has none) |
| `drum` | `drums` | a drum's key (`x8\38`: 38), else nil |

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
[c d] pitch              → ERR pitch: reads one note; for every note of a line, use pitches
```

### What a note plays with

`volume`, `instrument` and `articulation` read only what the note says
itself, so most notes give nil. `setting` reads what it plays with: put a
key after the note or the line, and it leaves the note's own value if it
has one, else the context's where it sits. Any context key works, by its
aliases (`:vol`, `:i`, `:K`, `:pan`):

```
[!i:40 !mf c d\vol:90] 0 @ :volume setting      → 60
[!i:40 !mf c d\vol:90] 1 @ :volume setting      → 90
[!i:40 !mf c d\vol:90] 0 @ :instrument setting  → 40
[!i:40 !K:D.major c d] :K setting               → D.major
```

A line gives its context at its start, and a note the value where it
sits, a ramp followed along the line:

```
[!T:90 !M:3/4 c d e] :Tempo setting             → 90
[!T:90 !M:3/4 c d e] :Meter setting             → 3/4
[!vol:30< c4 d4 e4 f4 !vol:90] 2 @ :volume setting    → 60
[!vol:30< c4 d4 e4 f4 !vol:90] 1/2 at :volume setting → 60
[!T:90 c d] ctx                         → (not stored under :ROOT -- its own context only)
                                          Tempo=90@0
```

(`ctx` prints a line's own settings; a stored name's chain too.) A drum's
`:instrument` is its key of the kit (`x8\38` gives 38), and `:articulation`
is the note's name, or the context's factor under the name it equals, else
the number (`0.9` by default). `@` is `nth`, the element counted from 0.

`with` writes one the same way, the instruction as the line would hold it, before the word:

```
[c d] !volume:70 with           → [!volume:70 C4/4 D4]
[c d] !Tempo:3/2 with           → [!Tempo:3/2 C4/4 D4]
[c d] !Key:D.major with         → [!Key:D.major C#4/4 D4]
[c d] !volume:70 with :volume setting   → 70
```

A note becomes a line, the line's own settings still come after so they
win, and a number key refuses a name (only `:instrument` and `:kit` take
one). `with` still takes a whole context, `^{ !mf } with`, or a key,
`D.major with`. Inside a line a bare `!name` must be a mark: `[!mff c]` is
refused ("did you mean !ff or !mf?"), and so is `[!volume c]`, which needs
its value, `!volume:70`.

### By time and by bar

`duration` is a line's length in whole notes, as written. `at` is the note
sounding that long into it, a note sounding until the next starts, and
`bar` the n-th measure by the line's meter, counted from 0 as `nth`
counts:

```
[c4 d4 e2] duration                     → 1
[c8 d e] duration                       → 3/8
{[c4 d4] [e2]} duration                 → 1/2
[c4 d4 e2] 3/8 at                       → D4/4
[c4 r4 e2] 1/4 at                       → r4
[c2 d2 e2 f2] 1 bar                     → [E4/2 F4]
[c2 d2 e2 f2 c2 d2] 1 3 bars            → [E4/2 F4 C4 D4]
```

`bars` gives several measures as one line. A bar carries the settings in
effect where it begins, so it plays as it did in its line, with its
meter too:

```
[!mf c2 d2 !pp e2 f2] 1 bar             → [!mf !pp E4/2 F4]
[!Meter:3/4 c2 d2 e2 f2] 1 bar          → [!Meter:3/4 D4/4 E4/2]
```

### Numbers of a stream

What a line gives with `pitches`, `durations` or `volumes` is a list of
numbers, and these read it: `sum`, `mean`, `max`, `min`, `sort` and
`intervals` (each number minus the one before):

```
[c e g] pitches sum                     → 191
[c e g] pitches mean                    → 191/3
[c e g] pitches mean float              → 63.66666666666667
[g c e] pitches sort                    → '[55 60 64]
[c e g] pitches max                     → 67
[c e g] pitches intervals               → '[4 3]
[c8 d e] durations sum                  → 3/8
```

`max` and `min` are still the two-number words when two numbers are on
top (`3 5 max` is `5`). A line itself is refused with a pointer to
`pitches`, and `mean` is exact, so `float` converts it.

`classes` turns a line into the set of its pitch classes: each pitch's
place above C in the octave, every one once, the smallest first. It is a
value of its own, written `$[047]` (the numbers glued together, `T` for 10
and `E` for 11), and the words above read it as the list it holds:

```
[c e g c'] classes                      → $[047]
[g c e d c'] classes                    → $[0247]
[c e g c'] classes length               → 3
$[047] 1 @                              → 4
$[047] >list                            → '[0 4 7]
```

It does not depend on a key (`degrees` counts scale steps in one), and a
rest or a drum has no class. Two more sets have a bracket of their own.
`prime-form` is the set class: the most packed form of the classes,
from 0, the same for a chord and its mirror image, and `interval-vector`
counts the six interval classes between them:

```
[c e g] prime-form           → $(037)
[c e& g] prime-form          → $(037)      the minor triad is the same set class
[c e& g& a] prime-form       → $(0369)     the diminished seventh
[c e g] interval-vector      → $<001110>   one third, one minor third, one fifth
```

`key-of` guesses the key a line is in, from how long each class sounds,
by correlating them with the usual shape of a major and a minor key from
every tonic. It gives a key, so the key words read it:

```
[c d e f g a b c'] key-of               → C.major
[a4 c e a2 e4 c a] key-of               → A.minor
[g a b c' d' e' f#' g'] key-of          → G.major
[d e f# g a b c#' d'] key-of tonic pitch → 62
```

It is a guess: a short phrase can fit a major key and its relative minor
nearly alike. `key` still reads the key a line was written in.

`pulses` writes a line's rhythm as a grid of pulses, a 1 where a note
starts and a 0 while it holds or a rest is silent. The pulse is the largest
note value every length is a whole number of, or the one you give:

```
[c4 d4 e4 f4] pulses           → '[1 1 1 1]
[c4 d8 e8 f2] pulses           → '[1 0 1 1 1 0 0 0]
[c4 r4 d2] pulses              → '[1 0 1 0]
[c4~ c4 d4] pulses             → '[1 0 1]       a tie's second note only holds
[c4 d8] 1/16 pulses            → '[1 0 0 0 1 0]
```

`pulses sum` is the number of notes and `pulses length` the number of
pulses. The algos read the same grid back (`pulses->durations`).

`chord-name` names a chord the way chord symbols are written, as a string:
the root, a colon, a quality word and, when the lowest note is not the root,
a slash and that note:

```
<c e g>1 chord-name            → "C:M"
<c e& g b&>1 chord-name        → "C:m7"
<e g c'>1 chord-name           → "C:M/E"      the first inversion
<c e g a>1 chord-name          → "C:6"        the lowest note is the root
$[0479] chord-name             → "A:m7"       a set has no lowest note
[c e g c'] chord-name          → "C:M"        a line is read as an arpeggio
```

The name reads back: `[C:M7]` is the chord it names.

Writing one yourself is checked: `$[740]` is `$[047]`, `$(047)` is refused
because it is no prime form (it shows `$(037)`), and a vector needs six
counts. What changes the numbers (`sort`, `intervals`) gives a plain list.

`profile` says how much each class counts, as twelve scores from 0 to 1,
C first: the time each sounds, a chord's time shared among its notes,
scaled so the strongest is 1, as exact ratios:

```
[c4 e4 g4 c2] profile        → '[1 0 0 0 1/3 0 0 1/3 0 0 0 0]
[<c e g>2 <f a c'>2] profile → '[1 0 0 0 1/2 1/2 0 1/2 0 1/2 0 0]
```

The biggest score is the note the line leans on. Rests count for nothing,
and the octave does not matter.

## 10. Taking a line apart

The plurals take a line apart into its half products, and `zip` puts
durations and pitches back together:

```
[c8 <e g>4 r8 d] durations       → '[1/8 1/4 1/8 1/8]
[c8 <e g>4 r8 d] pitches         → '[60 '[64 67] nil 62]
[c d\vol:90 e] volumes           → '[nil 90 nil]
[c\vol:30 r d\vol:90] volumes    → '[30 nil 90]
'[/4 /8 /8] '[60 62 64] zip      → [C4/4 D4/8 E4]
```

Every reader gives a rest's value too -- its length, and nil for the
rest -- so each stream keeps one value per element, as `zip` takes
them (section 15): at a rest the value goes unused.

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
false. `'( pitch 65 < )` asks "is the pitch below 65?" (`<` compares
numbers; `=` compares anything, music by its notes: `[c e g] [C4 E4
G4] =` is true, however each was written).

| Word | Does |
|---|---|
| `filter` | keeps the notes that pass |
| `remove` | drops them: the line gets shorter, later notes move earlier |
| `mute` | turns them into rests of the same length: the rhythm stays |

```
[c e g c'] '( pitch 65 < ) filter       → [C4/4 E4]
[c e g c'] '( pitch 65 < ) remove       → [G4/4 C6]
[c e g c'] '( pitch 65 > ) mute         → [C4/4 E4 r r]
[c8 d4 e8 f2] '( duration 1/4 < ) mute  → [r8 D4/4 r8 F4/2]
```

### Searching

`find` is the first note a test accepts and `index-of` its place,
counted from 0 as `@` counts. `index-of`
takes a value in place of the test. Nothing found is `nil`:

```
[c e g c'] '( pitch 65 > ) find                → G4/4
[c e g c'] '( pitch 65 > ) index-of            → 2
[C4 E4 G4 C5] E4 index-of                       → 1
[C4 E4 G4] D4 index-of                          → nil
[C4 E4 G4 C5] dup '( pitch 65 > ) index-of @   → G4/4
[C4 E4 C4 E4] C4 indexes                        → '[0 2]
[C4 E4 G4 C5] '( pitch 65 > ) indexes          → '[2 3]
```

`indexes` gives every place instead of the first, as a list; `indexes
length` is how many notes match.

(A bare `e` or `c` as the value reads relative to the notes before it in
the same input, as everywhere; capital letters say which.)

### Changing part of a line

`within` takes the elements from a start up to (not including) an end,
counted from 0, runs a quotation on them and puts the result back:

```
[c d e f g] 1 4 '( [R] ) within          → [C4/4 F4 E4 D4 G4]
[c d e f g] 1 3 slice                    → [D4/4 E4]
[c d e f g] 1 3 [a b c] splice           → [C4/4 A4 B4 C5 F4 G4]
```

`slice` and `splice` are its two halves, for when the change takes
several steps.

### One element at a time

`!` puts a value at an index, `insert` puts it before one, `_` drops one,
`[_]` drops a range, and `@` reads one:

```
[c d e] 1 g !                           → [C4/4 G4 E4]
[c d e] 1 g insert                      → [C4/4 G4 D4 E4]
[c d e] 1 _                             → [C4/4 E4]
[c d e f g] 1 3 [_]                     → [C4/4 F4 G4]
[c d e] 1 @                             → D4/4
```

The value stays one element: `[c d] 1 [e f] !` is a line of a note and a
line. An index can be a path, a list that goes down through nested lines:

```
[[c d e] [f g a]] '[1 1] @              → G4/4
[[c d e] [f g a]] '[1 1] x !            → [[C4/4 D4 E4] [F4 x A4]]
```

To change a value the way you typed it, `,` takes it back into the prompt
as text: `[c d e] ,` leaves `mu0> [C4/4 D4 E4]` to edit and run again.

### Joining lines

`concat` joins two lines into one, each keeping its own settings where it
begins. `flatten` opens the lines inside a line, one level, and `[2]`
(`[2]`) is the other way, a line of two lines. `[]` is the empty line,
the place to start building from:

```
[!mf c d] [!pp e f] concat              → [!mf C4/4 D4 !pp E4 F4]
[!mf c d] [!pp e f] [2]               → [[!mf C4/4 D4] [!pp E4 F4]]
[c d] [e f] [2] flatten               → [C4/4 D4 E4 F4]
[] [c d] concat                         → [C4/4 D4]
[c d e] 1 _ 1 _ 0 _                     → []
```

`concat` is the text of both written together, so a line with no settings
of its own takes on what came before it; `[2]` keeps each line's own
apart. (`[]` is mu's alone: inside a line, `[c [] d]` is not read.)

### Settings stay with their notes

An instruction in the middle of a line stays in front of the note it was
written before, whatever these words do to the notes around it. When its
note is gone it waits for the next one that stays, and when notes are
reordered it travels with its note. Settings before the first note are
the line's own and stay at its start:

```
[c d !mf e f] 1 3 slice                 → [D4/4 !mf E4]
[!mf c d e] tail                        → [!mf D4/4 E4]
[c !mf d e !pp f] 2 rotate              → [E4/4 !pp F4 C4 !mf D4]
```

## 12. Instruments and drums

An instrument is a General MIDI program, by number (`\i:40` is a
violin), by name (`!i:seashore`, `\i:birdTweet`) or by its short name
(`!i:sea`, `\i:vib`), or the start of a name that can only mean one
(`!i:trump`, `!i:pizz`), set on a note or for a whole line; `"viol"
find-instrument`, `"cr" find-drum` and `"s" find-kit` list what a
start of a name can mean (`""` all of them), and Tab completes a name
as you write it -- after `!i:` or `\i:`, a drum's `x8\` or `!kit:` --
filling in the one it can be, or listing them with their program or key
(a second Tab moves through the list). Tab completes keys too: a context
key or an instruction after `!` (`!swingU` to `!swingUnit:`, `!mo` to
`!moderato`), what a note takes after `\` (`c4\tri` to `c4\trill`,
`c4\vo` to `c4\vol:`), a dynamic after `!vol:`, and a scale after a
key's tonic (`!K:F.ma` to `!K:F.major`), a stored part after `:` (`:blu`
to `:blues`) and a word (`walk` to `walking-bass`, `pattern/eu` to
`pattern/euclid`, once required), each listed with its stack effect; a list plays
several at once. A drum is `x` with its key or name; a bare `x`
repeats the last one. `!kit:` picks the drum kit: `standard`, `room`,
`power`, `electronic`, `tr808`, `jazz`, `brush`, `orchestra`, `sfx`
(what you get depends on the soundfont).

```
[!i:40,73 c d e]                    violin and flute in unison
[x8\36 x x\38 x]                    kick, kick, snare, snare
x8\kick,clap                        → x8\36,39   one stroke, two sounds
[!kit:brush x8\sna x x x]           snares with brushes
```

Instruments and drums are two worlds: an instrument is a program and
its notes are pitches; a drum is a key of the kit, the key choosing the
sound. So the words keep them apart:

| Word | Stack | Does |
|---|---|---|
| `instruments` | `( line -- stream )` | each note's own program; nil for a drum |
| `drums` | `( line -- stream )` | each drum's key; nil for a note |
| `zip` with `:instrument` | `( \| line :instrument programs -- line )` | each note's instrument from the stream in turn; a drum is refused |
| `zip` with `:drum` | `( \| line :drum keys -- line )` | each drum's sound in turn; a note is refused |
| `as-drums` | `( line keys -- line )` | each note made a drum, keeping its length, volume and accent |

```
| [c d e] :instrument '[40 :sea 73] zip  → [C4/4\instrument:40 D4\instrument:122 E4\instrument:73]
| [x8\bd1 x8\sna] :drum '[:hhc :cr1] zip → [x8\42 x\49]
[c8 d e f] '[:kick :hhc :sna :hhc] as-drums → [x8\36 x\42 x\38 x\42]
```

Names in a stream are keywords (`:sea`, `:kick`); numbers and names
can stand side by side.

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
and play it, or rate them (section 17).

## 13. Keeping things

| Word | Does |
|---|---|
| `'name value :=` | bind a name for this session; `\name` is the value |
| `R!` or `store` | store in the repo, under an id: `[c d e] :mel R!`; refused when other stored parts use that id |
| `R!!` or `overwrite` | the same, redefining an id other parts use, and saying which change with it |
| `R@` or `fetch` | fetch from the repo: `:mel R@` |
| `ids`, `:mel usages` | every stored id; the ids that hold `:mel` |
| `forget` | take a name out of the repo: `:mel forget` (refused while something uses it) |
| `rename` | give a name another everywhere it is used: `:mel :tune rename` |
| `: name ( $a -- b ) … ;` | define a word of your own, `$a` an input bound as a local |
| `"x.edn" save-session` | save what is stored, your words and your bindings to a file (not the stack) |
| `"x.edn" restore-session` | put them back, in place of what is stored and of your words and bindings |

```
'motif [c e g] :=
\motif 5 +              → [!K:F.major F4/4 A4 C5]
[c d e] :mel R!         → :mel
:mel R@ 7 +             → [!K:G.major G4/4 A4 B4]
```

`rename` follows the name wherever it is written, so a part that used
`:mel` now uses `:tune`; `forget` refuses a name that is still used, and
says by whom (`:mel is still used by :song`), so nothing is left pointing
at nothing.

## 14. The color-talea

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
word's inputs, taken off the stack by the `$` in its stack effect:

```
: isorhythm ( $talea $color $n -- line ) $talea cycle $n take $color cycle $n take zip ;

'[/4 /8 /8] [c d e f g] pitches 15 isorhythm [||]
→ [C4/4 D4/8 E4 F4/4 G4/8 C4 | D4/4 E4/8 F4 G4/4 C4/8 D4 | E4/4 F4/8 G4]

'[/4. /8 /4 /4] [c e g b d'] pitches 20 isorhythm ->
```

Barring it with `[||]` shows the shift: each bar starts on another note
of the color. The result is an ordinary line, so everything above
applies: `5 +`, `[R]`, `'( … ) mute`, `1 4 '( [R] ) within`.

## 15. Algorithms as functions

The algorithms are plain Clojure functions, and mu calls them by name with their
parameters on the stack. `'rhythmic.pattern algorequire` (that is
`"musics.algo.rhythmic.pattern" require`, short for the namespaces of `musics.algo`)
makes the public functions of that namespace words, `pattern/euclid`, and `see` shows
what one takes, read from its own argument names. There is no settings object and no
default: you say every parameter.

```
'rhythmic.pattern algorequire
'bridge algorequire
see pattern/euclid                                    → pattern/euclid ( onsets pulses -- euclid )
3 8 pattern/euclid                                    → '[1 0 0 1 0 0 1 0]
3 8 pattern/euclid 1/16 bridge/pulses->durations      → '[3/16 3/16 1/8]
3 8 pattern/euclid 1/16 bridge/pulses->durations '[60 64 67] zip
                                                      → [C4/8. E4 G4/8]
```

Three beats among eight sixteenths is the tresillo. What a function makes is *raw
material*, a plain list (pulses here); a **bridge** from `musics.algo.bridge` turns it
into durations (`pulses->durations`, with the pulse you give it), pitches
(`numbers->pitches`, with the range the numbers really have: `from-lo from-hi lo hi`),
volumes and so on, and `zip` makes notes of them. A list inside a list is written
`'[ '[0 1] '[2] ]`. Nothing is checked between words, so pair the kinds right; `see`
says what each word takes. A function that cannot work with what it is given says so:

```
99 8 pattern/euclid           → pattern/euclid: refused, it needs <= onsets pulses
```

A function that gives one number per call is called n times with `pull`, which takes a
quotation, or a function that keeps its place:

```
'numeric.rng algorequire
'numeric.random algorequire
'numeric.walk algorequire
7 rng/seed                                            the random words after it repeat
'( 0.0 1.0 random/normal ) 12 pull                    twelve draws
60.0 3.0 walk/walk 8 pull                             a walk of eight steps, from 60
```

A quotation is also a function of what it is given, so it can be the fitness function a
Clojure function wants; and a group of quotations, `( '( … ) '( … ) )`, is a list of them:

```
'melodic.melody algorequire
'[60 62 64 65 67 69 71 72] 12 ( '( swap last = not ) '( swap last - abs 3 < ) ) melody/constrained
```

draws twelve notes of the scale, none repeated and none more than a whole tone from the
one before. `doc/md/raw-algo-cookbook.md` has 53 recipes written this way, every one run
for real: rhythms from number theory, sieves and polyrhythms, melodies from Markov chains,
walks and chaos, counterpoint, swing and variation, and a word of your own.

### Chords and a walking bass

A chord symbol is a root and a quality, absolute or relative, its
duration carrying on as a note's does (`F3/1:7`, `b&:7`, `c2.:m7`).
`walking-bass` takes a line of chords and makes a bass under it as you
run it, a new one each time: a quarter a beat, each chord's root on its
first beat, the beat before the next chord a step into it. Repeats are
walked pass by pass, each with its own line.

A number says how many times: `x 4 walking-bass` is as long as `x 4
times`, each time walked anew, the last chord held. Without a number it
is an ostinato: one line whose last beat leads back into its first, to
loop with the chords.

```
[blues: !M:4/4 repeat 2 [ F3/1:7 | b&:7 | f:7 | c2:m7 f:7 | b&1:7 | b:d7 | f:7 | d:7 | g:m7 | c:7 ] volta [ [ f,2:7 d:7 | g:m7 c:7 ] [ f,1:7 | f:7 ] ] ]
dup 1 walking-bass {2} ->          once through, the end held
[F3/1:7 | b&:7 | c:7 | f:7] dup walking-bass {2} [~] ->    an ostinato, looped
```

`dup` keeps the chords, `walking-bass` makes the bass from the copy,
`{2}` sets the two side by side.

## 16. Live

Two things play live. A **quotation** under a play word makes the music, each note the
voice reaches the next of what it gives now:

```
| '( [c d e] ) :riff ->           from nothing: the quotation's own line, again and again
[c d e g] [~] '( 5 + ) :up ->     the music under it, changed: the loop a fourth higher
'( 7 + ) :up retree               … now a fifth, from the next note
```

`|` under the quotation says it starts from nothing; without it, the music under the
quotation is what it runs on. `:riff` is the voice, as for any play word, and `retree` gives
it another quotation while it goes on from its place. All play words take one: `->>` from
the next boundary, `->+` joining what plays.

### A word as a live voice

A **word of your own** can be played with changeable numbers: write them as `$` inputs of
the definition, and play the word as a voice.

```
'rhythmic.pattern algorequire
'bridge algorequire
: riff ( $onsets $pulses -- notes )
    $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations '[60 62 64 67] zip ;
5 8 riff                           the line it gives: [C4/8 D4/16 E4/8 G4/16 C4/8]
```

`5 8 riff` runs the word once. To play it live, put the numbers under its
*quoted* name and give the voice a name:

```
5 8 'riff :bass ->
```

- The quote (`'riff`) keeps the word from running now: the voice stores the
  name and runs it again with the inputs it holds, each time they change.
- `5` and `8` are the voice's **inputs**, `onsets` and `pulses`, in the order
  of the stack effect. Each note the voice reaches is the next of the line
  the word gives; a short line starts again.
- The voice needs a name (`:bass`), and the word only `$` inputs: a plain
  input would need music passed in, which a quotation on music under it does
  (`x '( … ) :up ->`). `->>` waits for a boundary as it does for music.

An input is addressed by its voice and its name, `:bass/onsets`. It is a
**reference**: `@` reads it and `!` stores into it, as in Forth.

```
:bass/onsets @            5
3 :bass/onsets !          three beats, heard from the voice's next note
:bass/onsets @ :bass/pulses @       3 8
:bass settings            each input: value, range, default, what it is
:bass gui                 a window with a slider per input
:bass ->                  start it again with the inputs as they are now
stop
:bass/onsets @            the voice :bass is not playing
```

`settings` and the sliders take an input's range from the algo parameter of
the same name: `onsets` is `0..32`, `pulses` `1..32`, once the namespace that
reads them is loaded. A value outside it is refused (`99 :bass/onsets !`:
`onsets: 99 should be at most 32`). Where several algos read a name with different
ranges the widest is used. An input no algo reads (`$label`) has no range, so it is
a text field. The window files each control under the algo that owns the
parameter. Two voices may have inputs of the same name: `:bass/onsets` and
`:lead/onsets` are different places.

A local is written with its `$` in the body, `$onsets`, and a word that
leaves nothing, or fails when it runs, is refused when the voice starts.

## 17. Rating what you hear

mu keeps your taste in sounds (`~/.mu-feedback.edn`). The rating words are in the
`rate` namespace, which is not in use at the start: `"rate" use` makes them
usable as written below (or write `rate/like`); Alt+j, k and l, Alt+J, K and L
work either way.

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

## All the music words

| Word | Stack |
|---|---|
| `chord` | `( \| notes -- chord )` |
| `[2]` `[3]` … `[n]`, `n line` | `( a … -- line )` |
| `{2}` `{3}` … `{n}` | `( a … -- parallel )` |
| `**` `spread` | `( x -- a b … )` |
| `{+}` | `( a b -- parallel )` |
| `[!]` `unfold` | `( x -- line )` |
| `[M]` `mirror`, `[R]` `retrograde`, `[I]` `inversion`, `[RI]` `retrograde-inversion` | `( x -- x )` |
| `[:]` `repeats` | `( x -- [: x :] )` |
| `[\|\|]` `barred`, `[[]]` `measures` | `( x -- line )` |
| `[~]` `cycle` | `( x -- loop )` |
| `[*]` `times` | `( x n -- repeat )` |
| `+` `-` `*` `/` | `( x n -- x )` |
| `cresc` `dim` | `( x volume -- x )` |
| `pitch` `duration` `volume` `articulation` `instrument` `drum` | `( note -- value )` |
| `pitches` `durations` `volumes` `articulations` `instruments` `drums` | `( line -- stream )` |
| `as-drums` | `( line stream -- line )` |
| `chord?` `drum?` `note?` `rest?` | `( note -- flag )` |
| `combos` | `( note numbers k -- line )` |
| `pull` | `( f n -- list )` |
| `@` `!` | `( ref -- value )` `( value ref -- )`; `( x n -- elem )` `( x n y -- x' )` |
| `delay` | `( x d -- line )` |
| `delays` | `( parallel d\|list -- parallel )` |
| `setting` | `( note\|line :key -- value )` |
| `degrees` | `( numbers key -- line )` |
| `key` | `( note\|line -- key )` |
| `duration` | `( x -- t )` |
| `length` | `( x -- n )` |
| `classes`, `prime-form`, `interval-vector` | `( x -- set )` |
| `>list` | `( set -- list )` |
| `key-of` | `( x -- key )` |
| `chord-name` | `( x -- string )` |
| `pulses` | `( x [pulse] -- list )` |
| `profile` | `( x -- list )` |
| `at` | `( line t -- note )` |
| `bar` `bars` | `( line n -- line )`, `( line start end -- line )` |
| `mute` | `( x q -- x )` |
| `par` | `( \| … -- parallel )` |
| `sounds` | `( note numbers -- line )` |
| `tonic` | `( key -- note )` |
| `transpose` | `( x from to -- x )` |
| `vary` | `( repeat q … -- line )` |
| `volta` | `( repeat ending … -- repeat )` |
| `with` | `( x ^{ }\|key -- x )` or `( x :key value -- x )` |
| `zip` | `( durations pitches -- line )`, `( line stream -- line )`, `( \| parts … -- line\|bundle )` |

`"word" see` shows any of them with what it does; `words` lists them
all.
