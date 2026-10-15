# mu tutorial: the music words

mu is musics' musical REPL: you type music, and it lands on a stack
where words change it, combine it and play it. This tutorial walks
through the words that work on music, from writing a note to building
a color-talea. Every example was run in mu; the line under `→` is what
mu printed.

Start mu with `lein mu` (or `(mu!)` from `lein repl`). For sound, type
`gervill` (Java's own synthesizer, nothing to set up) or `fluidsynth`
(see `doc/md/setup.md`). `bye` or Ctrl+D leaves; the stack stays for the
session.

The prompt `mu<0>` counts what is on the stack. A few words move things
around on it, and you'll need them throughout:

| Word | Does |
|---|---|
| `.s` | show the stack, top first |
| `dup` | copy the top |
| `swap` | exchange the top two |
| `pop` | drop the top |
| `cs` | clear the stack |
| `see` | what a word does: `"dup" see` |

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

| Word | Does |
|---|---|
| `>` | play now, stopping what plays (also `play`) |
| `>>>` | play now, joining what plays (also `play+`) |
| `>>` | play in place of what plays, at its next boundary: the end of it, or of its repeat's pass |
| `x \| >>` | the same, at the next bar line (`\|\|` … `\|\|\|\|` for stronger ones) |
| `>>+` | join at the next boundary |
| `[.]` | stop everything |
| `[,]` | pause, and again to go on |

```
[c d e f g] >
[c d e f g] [~] >        loop it until [.]
[e f g a b] >>           takes over at the end of the current pass
```

`echo` prints each note as it sounds; `echo` again stops it.

## 3. Putting music together

| Word | Stack | Does |
|---|---|---|
| `seq` | `( \| … -- line )` | what lies above a `\|` mark, one after another |
| `par` | `( \| … -- parallel )` | what lies above a `\|` mark, at once |
| `[&]` | `( a b -- parallel )` | the two at once |
| `<>` | `( notes -- chord )` | the notes on top of the stack as a chord |
| `[+]` | `( a b -- line )` | the two joined, one after the other |

```
| c d e seq             → [C4/4 D4 E4]
| [c d] [e f] par       → {[C4/4 D4] [E4 F4]}
[c d] [e f] [&]         → {[C4/4 D4] [E4 F4]}
c e g <>                → <C4 E4 G4>4
[c d e] [f g] [+]       → [C4/4 D4 E4 F4 G4]
```

## 4. Changing pitch

A bare symbol works on every note; the same symbol in brackets works
on the line as a whole.

| Word | Stack | Does |
|---|---|---|
| `+` / `-` | `( x n -- x )` | every pitch up / down n semitones |
| `transpose` | `( x from to -- x )` | moved by the interval between two notes |
| `invert` or `[-]` | `( x -- x )` | mirrored around the mean pitch |
| `[^]` | `( x -- x )` | mirrored around the first note, which stays |
| `[<]` | `( x -- x )` | the crab: backwards |
| `[<^]` / `[^<]` | `( x -- x )` | crab then inversion / inversion then crab |

```
[c e g] 7 +                → [G4/4 B4 D5]
[c e g] 12 -               → [C3/4 E3 G3]
[c e g] c d transpose      → [D4/4 F#4 A4]
[c e g] invert             → [G#4/4 E4 C#4]
[c e g] [^]                → [C4/4 G#3 F3]
[c e g b] [<]              → [B4/4 G4 E4 C4]
[c d e g] [<^]             → [G4/4 A#4 C5 D5]
```

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
[c d e f g] D.major with        → [!key:D.major [C#4/4 D4 E4 F#4 G4]]
```

The last one is worth a second look: notes written in C major (the
default) follow the key they're played in, as on a staff, so `c`
plays C♯ and `f` F♯ in D major.

## 6. Changing time

| Word | Stack | Does |
|---|---|---|
| `*` / `stretch` | `( x f -- x )` | every duration times f |
| `/` | `( x f -- x )` | every duration divided by f: `3 /` fits three notes in the time of one |
| `[\|]` | `( x -- line )` | barred by its meter: a bar line every bar, a note across one cut and tied |

```
[c d e] 2 *                       → [C4/2 D4 E4]
[c d e] 1/2 stretch               → [C4/8 D4 E4]
[c d e] 3 /                       → [3:1(C4/4 D4 E4)]
[c2 d2 e2] [|]                    → [C4/2 D4 | E4]
[!Meter:3/4 c2 d4 e2 f4] [|]      → [!Meter:3/4 C4/2 D4/4 | E4/2 F4/4]
```

## 7. Dynamics, tempo, instruments: contexts

A context `^{ … }` holds settings, written as in a `.mus` file
(`!tempo:60`, `!ff`, `!i:73`). `with` puts music under one.

| Word | Stack | Does |
|---|---|---|
| `with` | `( x ^{ } -- x )` | the music under that context |
| `cr` / `decr` | `( x volume -- x )` | a crescendo / decrescendo over it, to a volume 0-100 |
| `defaults` | `( -- ^{ } )` | the context everything starts from |

```
[c d e] ^{ !tempo:60 !ff } with   → [!tempo:60 !ff [C4/4 D4 E4]]
[c d e] ^{ !i:73 } with           → [!i:73 [C4/4 D4 E4]]
[c d e f g] 90 cr                 → a crescendo from where it is to 90
[c d e] 30 decr                   → a decrescendo down to 30
```

## 8. Repeats

| Word | Stack | Does |
|---|---|---|
| `repeat` | `( x n -- repeat )` | played n times, as `repeat n [ x ]` |
| `volta` | `( repeat ending … -- repeat )` | endings above a repeat, one after each pass |
| `vary` | `( repeat q … -- line )` | each pass changed by a quotation, one for all or one a pass |
| `[:]` | `( x -- [: x :] )` | in repeat signs: played twice |
| `[~]` | `( x -- loop )` | endlessly |
| `[*]` | `( x n -- line )` | n copies, written out |
| `[!]` | `( x -- line )` | written out as it plays |

```
[c d] 3 repeat                               → repeat 3 [C4/4 D4]
[a b c d] 2 repeat [c2 b a] [a4] volta       → repeat 2 [A3/4 B3 C4 D4] volta [[C4/2 B3 A3] [A3/4]]
[c d] 3 repeat '( ) '( 12 + ) '( [<] ) vary  → [[C4/4 D4] [C5 D5] [D4 C4]]
[c d] [:]                                    → [: C4/4 D4 :]
[c d] [:] [!]                                → [[C4/4 D4] [C4 D4]]
[c d] 3 [*]                                  → [[C4/4 D4] [C4 D4] [C4 D4]]
```

A quotation `'( … )` is a piece of mu held for later: `vary` runs the
first on the first pass, the second on the second, and so on.

## 9. Reading a note

A note is made of half products: its pitch, its duration, its volume,
its articulation, its instrument. These words read them.

| Word | Gives |
|---|---|
| `pitch` | the pitch number, a chord's lowest; nil for a rest |
| `pitches` | every pitch of a chord |
| `durations` | the length, in whole notes |
| `volume` | its own volume (`c4\f`, `c4\vol:90`), else nil |
| `articulation` | its articulation (`:accent`, `:staccato` …), else nil |
| `instrument` | its own instrument (`c4\i:40`), a drum's key |
| `note?` `chord?` `rest?` `drum?` | what it is |

```
c4 pitch              → 60
<c e g>4 pitches      → '[60 64 67]
c8. durations         → 3/16
c4\vol:90 volume      → 90
c4\f volume           → 70
c4-> articulation     → :accent
c4\i:40 instrument    → 40
```

## 10. Taking a line apart

On a whole line, the same words give every note's value as a
**stream** (a list), and `zip` puts durations and pitches back
together:

```
[c8 <e g>4 r8 d] durations            → '[1/8 1/4 1/8 1/8]
[c8 <e g>4 r8 d] pitches              → '['[60] '[64 67] nil '[62]]
'[/4 /8 /8] '[60 62 64] zip           → [C4/4 D4/8 E4]
```

So `x durations x pitches zip` gives `x` back, and anything you do in
between changes just that half of the line:

```
[c8 d4 e8 g4] dup durations swap pitches zip           → [C4/8 D4/4 E4/8 G4/4]
[c8 d4 e8 g4] dup durations reverse swap pitches zip   → [C4/4 D4/8 E4/4 G4/8]
```

The second keeps the melody and plays the rhythm backwards. (`dup`
copies the line; `swap` brings the original back on top.)

## 11. Choosing notes

These take a test, a quotation run on each note that leaves true or
false. `'( pitch 65 .< )` asks "is the pitch below 65?" (`.<` compares
numbers).

| Word | Does |
|---|---|
| `filter` | keeps the notes that pass |
| `remove` | drops them: the line gets shorter, later notes move earlier |
| `mute` | turns them into rests of the same length: the rhythm stays |

```
[c e g c'] '( pitch 65 .< ) filter       → [C4/4 E4]
[c e g c'] '( pitch 65 .< ) remove       → [G4/4 C6]
[c e g c'] '( pitch 65 .> ) mute         → [C4/4 E4 r r]
[c8 d4 e8 f2] '( durations 1/4 .< ) mute → [r8 D4/4 r8 F4/2]
```

To change just a part of a line, `within` takes the elements from a
start up to (not including) an end, counted from 0, runs a quotation
on them and puts the result back:

```
[c d e f g] 1 4 '( reverse ) within      → [C4/4 F4 E4 D4 G4]
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
and play it, or rate them (section 15).

## 13. Keeping things

| Word | Does |
|---|---|
| `'name value =` | bind a name for this session; `\name` is the value |
| `R!` | store in the repo, under an id: `[c d e] :mel R!` |
| `R@` | fetch from the repo: `:mel R@` |
| `: name ( a -- b ) … ;` | define a word of your own |

```
'motif [c e g] =
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
- `cycle` repeats it forever, `15 take` keeps the first 15.
- `'[60 62 64 65 67]` is the color; `[c d e f g] pitch` gives the same
  numbers from notes.

It's worth a word of its own. `$talea`, `$color` and `$n` are the
word's inputs, named in its stack effect:

```
: isorhythm ( talea color n -- line ) $talea cycle $n take $color cycle $n take zip ;

'[/4 /8 /8] [c d e f g] pitch 15 isorhythm [|]
→ [C4/4 D4/8 E4 F4/4 G4/8 C4 | D4/4 E4/8 F4 G4/4 C4/8 D4 | E4/4 F4/8 G4]

'[/4. /8 /4 /4] [c e g b d'] pitch 20 isorhythm >
```

Barring it with `[|]` shows the shift: each bar starts on another note
of the color. The result is an ordinary line, so everything above
applies: `5 +`, `[<]`, `'( … ) mute`, `1 4 '( [<] ) within`.

### The same as an algo tree

In Clojure, the same isorhythm is a tree of three tools, with its
settings kept apart from it (`lein repl`, where `t` and the tools are
already referred):

```clojure
(def isorhythm (take> (zip (cycle> [1/4 1/8 1/8]) (cycle> [60 62 64 65 67]))))
(def tctx (t/tctx isorhythm))      ; its settings, a context
(t/setp! tctx :len 15)             ; take> keeps 15
(notes->mus (t/run isorhythm tctx))
;; "[ !acc:explicit C4/4 D4/8 E4/8 F4/4 G4/8 C4/8 D4/4 E4/8 F4/8 G4/4 C4/8 D4/8 E4/4 F4/8 G4/8 ]"
```

`cycle>`, `zip` and `take>` do what `cycle`, `zip` and `take` do in mu.
`notes->mus` writes the result as musics text, which mu reads as it
is: paste it at the mu prompt and it's a line on the stack.

## 15. Rating what you hear

mu keeps your taste in sounds (`~/.mu-feedback.edn`):

| Word | Does |
|---|---|
| `like` `meh` `dislike` | rate what was just heard (Alt+. Alt+/ Alt+, at the prompt) |
| `audition` | `( line -- )` play its sounds one by one, in the background, to rate each |
| `next` `back` `again` | move through an audition (Alt+> Alt+< Alt+?) |
| `taste` | your ratings, per sound and per instrument |
| `liked` / `disliked` | the sounds you rated, as a line |

```
c4 '[0 40 73] sounds audition
```

## All the music words

| Word | Stack |
|---|---|
| `<>` | `( notes -- chord )` |
| `[&]` | `( a b -- parallel )` |
| `[!]` | `( x -- line )` |
| `[-]` `[^]` `[<]` `[<^]` `[^<]` | `( x -- x )` |
| `[:]` | `( x -- [: x :] )` |
| `[\|]` | `( x -- line )` |
| `[~]` | `( x -- loop )` |
| `+` `-` `*` `/` | `( x n -- x )` |
| `cr` `decr` | `( x volume -- x )` |
| `articulation` `durations` `instrument` `pitch` `pitches` `volume` | `( note -- value )`, or a stream on a line |
| `chord?` `drum?` `note?` `rest?` | `( note -- flag )` |
| `combos` | `( note numbers k -- line )` |
| `degrees` | `( numbers key -- line )` |
| `invert` | `( x -- x )` |
| `key` | `( note\|line -- key )` |
| `mute` | `( x q -- x )` |
| `par` `seq` | `( \| … -- parallel \| line )` |
| `repeat` | `( x n -- repeat )` |
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
