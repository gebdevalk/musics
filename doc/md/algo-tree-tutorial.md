# Algo trees in mu

An algo tree is a recipe for music: algorithms whose results feed other
algorithms, ending in notes. In mu you don't draw the tree, you write it
as a sequence of words, postfix: each word takes what it needs from the
stack and leaves what it makes. The order of the words *is* the tree,
read from the leaves up. That makes trees concatenative: two pieces of
a recipe written one after the other are a bigger recipe, a piece can
be named and reused, kept as a value, swapped for another while it
plays.

This tutorial starts with one word and ends with a recipe playing live,
changed from the prompt and from the GUI's sliders. Start mu with
`lein mu`; `bye` leaves. Lines are typed at the `mu<n>` prompt (n is how
many values the stack holds); `→` shows what a line leaves on top, and
`clear` empties the stack between examples. The full word list is in
`doc/md/mu-tutorial.md`; this one is about putting algos together.

## 1. One word, one stream

An algo word is named by what it delivers: `duration/…` gives durations,
`pitch/…` pitches, `volume/…` volumes. On its own it runs with its
defaults:

```
duration/euclid      → '[3/16 3/16 1/8]
```

Euclid's rhythm: 3 beats spread over 8 pulses, a pulse a sixteenth.
`see` shows a word's stack effect and the settings it reads:

```
"duration/euclid" see
duration/euclid ( [@^{ … }] -- durations )
  Distribute k beats evenly among n pulses (Bjorklund's algorithm). …  @^{ k n rotation duration-pulse }
```

The `[@^{ … }]` is optional: settings on top of the stack, `@^{ name:value
… }`, are the word's own, read before the defaults. They are an algo's
settings, kept apart from the music's `^{ }`:

```
@^{ k:5 n:8 duration-pulse:1/8 } duration/euclid      → '[1/4 1/8 1/4 1/8 1/4]
@^{ seed:7 len:8 pitch-lo:60 pitch-hi:72 } pitch/walk → '[60 60 60 62 64 64 60 60]
```

`'[ … ]` is a stream: plain values, not yet music. A random algo runs
differently each time; `seed:7` makes it repeat.

## 2. Two streams make notes

`zip` takes durations and pitches and makes notes of them -- the first
tree with more than one branch:

```
@^{ k:3 n:8 duration-pulse:1/8 } duration/euclid [c e g] pitches zip
    → [C4/4. E4 G4/4]
```

Read it as the stack does: euclid leaves durations, `[c e g] pitches`
leaves the pitches of a line, and `zip` takes both. As a tree it is

```
zip
├── duration/euclid  (@^{ k:3 n:8 duration-pulse:1/8 })
└── pitches
    └── [c e g]
```

-- the same tree, written leaves first. Any word that gives pitches can
take the second branch's place:

```
@^{ k:5 n:8 duration-pulse:1/8 } duration/euclid @^{ seed:7 len:8 pitch-lo:60 pitch-hi:72 } pitch/walk zip
```

`zip` stops at the shorter stream: five durations, five notes. A loop
`[~]` around the pitches cycles them, so the rhythm decides the length:

```
@^{ k:5 n:8 duration-pulse:1/8 } duration/euclid [c e g a] pitches [~] zip
    → [C4/4 E4/8 G4/4 A4/8 C4/4]
```

and two loops zip into a loop as long as they take to line up:

```
@^{ k:3 n:8 duration-pulse:1/8 } duration/euclid [~] [c e g a] pitches [~] zip
    → repeat ~ [C4/4. E4 G4/4 A4/4. C4 E4/4 G4/4. A4 C4/4 E4/4. G4 A4/4]
```

Three beats against four pitches: the melody comes round on another
beat each time, until twelve notes later both start together again.

## 3. Arranging the stack

The branches of a tree don't have to be written in the order the word
takes them: the stack words move values around.

```
[c e g] pitches @^{ k:3 n:8 duration-pulse:1/8 } duration/euclid swap zip
    → [C4/4. E4 G4/4]
```

`swap` exchanges the top two, `dup` copies the top, `over` copies the
one under it, `rot` brings the third to the top. With `dup` one context
can set two words -- here one random walk heard as pitch and as volume,
the same seed giving both the same run:

```
@^{ seed:7 len:8 pitch-lo:60 pitch-hi:72 volume-lo:40 volume-hi:90 } dup pitch/walk swap volume/walk .s
0> '[40.0 40.0 40.0 47.35… 56.76… 56.10… 40.0 40.0]
1> '[60 60 60 62 64 64 60 60]
```

`zip` joins all three into notes: with a mark `|` under them it takes
everything above it, and each stream knows its part -- durations,
pitches, volumes:

```
| [c8 c c c c c c c] durations
@^{ seed:7 len:8 pitch-lo:60 pitch-hi:72 volume-lo:40 volume-hi:90 }
dup pitch/walk swap volume/walk zip
    → [C4/8\volume:40.0 C4\volume:40.0 C4\volume:40.0 D4\volume:47.35… E4\volume:56.76… …]
```

The pitch rises where the volume swells: one source, two half products
of it.

## 4. Algos on streams

Some algos take a stream and give a changed one. `durations` takes a
line apart into its durations; `duration/emi` varies them, as David
Cope's EMI varies a rhythm:

```
[c8 d e f g a b c] durations duration/emi
    → '[1/8 1/8 1/8 3/16 1/16 1/8 1/8 1/8]
```

and the variation goes back onto pitches with `zip`:

```
[c8 d e f g a b c] durations @^{ seed:2 } duration/emi [C4 D E F G A B C5] pitches zip
    → [C4/8 D4 E4/8. F4/8 G4/16 A4 B4 C5/8]
```

`volume/euclid` gives the meter's accents as volumes; `zip` writes them
onto a line, a value per note:

```
[c8 d e f g a b c] @^{ k:8 n:8 volume-pulse:1/8 volume-lo:50 volume-hi:80 } volume/euclid zip
```

Every algo is a word on one of these shelves; `words` lists them with
how many each holds (`pitch/` (107) `duration/` (104) …), `"pitch" use`
makes one shelf's words usable without the prefix.

## 5. Naming a recipe

A piece of a recipe can be named with a definition, `: name ( in -- out ) … ;`,
and used like any word:

```
: riff ( -- line ) @^{ k:3 n:8 duration-pulse:1/8 } duration/euclid [c e g] pitches zip ;
riff                  → [C4/4. E4 G4/4]
riff dup 7 + {2}      → {[C4/4. E4 G4/4] [!K:G.major G4/4. B4 D5/4]}
```

The stack effect names the inputs: inside the body `$name` is that
input, so a value can be used twice without `dup` and `swap`. The swell
of section 3, as a word:

```
: swell ( durs ctx -- line ) $durs $ctx pitch/walk zip $ctx volume/walk zip ;
[c8 c c c c c c c] durations @^{ seed:7 len:8 pitch-lo:60 pitch-hi:72 volume-lo:40 volume-hi:90 } swell
```

A definition without a stack effect works on the stack directly:
`: swell2 dup pitch/walk swap volume/walk rot rot zip swap zip ;`
gives the same. A word can leave its settings open, to be given each
time:

```
: line-of ( ctx -- line ) $ctx duration/euclid [c e g a] pitches [~] zip ;
@^{ k:5 n:8 duration-pulse:1/8 } line-of     → [C4/4 E4/8 G4/4 A4/8 C4/4]
```

A **quotation** `'( … )` is a recipe kept as a value, not run: pushed,
passed around, run with `call`:

```
'( 3 + ) '( 4 + ) [c e g] swap call swap call      → [!K:G.major G4/4 B4 D5]
```

Two pieces of recipe written one after the other are one recipe:
`'( 3 + )` then `'( 4 + )` is `'( 3 + 4 + )`, a transposition by 7.

## 6. Lines, loops and layers

What a recipe makes is music like any other, so mu's own words finish
it: `[~]` loops it, `n times` repeats it, `[n]` puts the top n one after
another, `{n}` side by side.

```
[c e g] dup 7 + dup 7 + [3]
    → [[C4/4 D4 E4] [!K:G.major G4 A4 B4] [!K:D.major D5 E5 F#5]]

@^{ k:3 n:8 duration-pulse:1/8 } duration/euclid [c e g] pitches [~] zip
@^{ k:5 n:8 duration-pulse:1/8 } duration/euclid [G4 b d] pitches [~] zip {2}
    → {[C4/4. E4 G4/4] [G4 B4/8 D5/4 G4/8 B4/4]}
```

Two Euclidean rhythms, three against five, as two parts. `parallel/drums`
makes a whole kit groove, a part per drum; `@^{ style:funk }` picks its
style (`rock pop funk hiphop trap jazz house techno metal`).

## 7. Playing it live

Every example so far makes music once: `>` plays the result, and to hear
another setting you change the line and play again. A play word given
the **recipe** instead of its result plays it live: each note the voice
reaches is the next note of what the recipe gives *now*, and a change of
its settings is heard on the next note, the voice going on where it is.

```
: riff ( -- line ) duration/euclid [c e g a] pitches [~] zip [~] ;
| '( riff ) :riff >
```

(`riff` again, now without settings of its own: they come from `set`.)

- `'( riff )` is the recipe; it ends in a loop, so the voice plays on.
- `|` under the recipe says it starts from nothing (below: from music).
- `:riff` names the voice, as for any play word, and its settings.

Its settings are every param the recipe's algos read, set with `set`:

```
:riff @^{ k:5 n:8 } set     five beats in eight, heard at once
:riff settings
:k             5            0..32            3          euclid       onsets
:n             8            1..32            8          euclid       pulses
:rotation      0            0..32            0          euclid       steps to rotate the onsets
:duration-pulse 1/16         1/64..4          1/16       euclid       note value of one pulse
:riff 'k unset               back to the recipe's head, or the default
```

`settings` lists each param, what `set` gave it (else the default), its
range, the default and the algo that reads it. A value out of range is
refused.

A recipe that opens with an `@^{ }` has it as its **head**: it reaches
every algo word in the recipe, `settings` shows it and `set` changes it,
and `unset` goes back to it. An `@^{ }` right before one word is that
word's alone, winning over the head:

```
| '( @^{ seed:7 len:16 } duration/euclid pitch/walk zip @^{ len:8 } volume/walk zip [~] ) :swell >
```

`seed` and `len` reach all three words; `volume/walk` alone reads `len:8`.

A long name gets a short one with `:=`, a name bound to a name:

```
'dp 'duration-pulse :=
:riff @^{ dp:1/8 } set
```

`retree` swaps the recipe and keeps the settings and the voice's place;
pieces concatenate here too:

```
'( riff 12 + ) :riff retree                       an octave up
'( duration/euclid pitch/lorenz zip [~] ) :riff retree   the attractor's pitches, same rhythm settings
```

With **music under the recipe**, the recipe runs on that music, and the
voice plays its notes as the recipe changes them:

```
[c d e g] [~] '( 5 + ) :up >                   the loop a fourth higher
'( [R] ) :up retree                            … now backwards
[c8 d e f g a b c] [~] '( durations duration/emi [c8 d e f g a b c] pitches zip ) :v >
:v @^{ similarity:0.3 } set                    vary it more
```

`voices` lists what plays, `:riff stop` stops one voice, `stop` all of
them; the settings belong to the name, so playing `:riff` again later
starts with them. Every play word takes a recipe: `>>` starts it at the next
boundary (`| '( riff ) || :riff >>`), `>+` and `>>+` join what plays.
A recipe that fails is refused when played, with the reason, and
nothing starts.

A random algo's `seed` is a setting like any other: in the head
(`'( @^{ seed:4 } pitch/walk … )`), before one word, or changed with
`set` (`:riff @^{ seed:5 } set`).

## 8. A recipe as a tree

A recipe can also be a tree, written in RPN between `@<<` and `>>`. Inside,
the words build the tree on a stack of their own: an algo takes as many
inputs as it has from the top and pushes itself, `zip` joins the top two
(`par` puts them side by side), and the params (`k:3`) and an alias
(`'bass`) go right *before* the algo they belong to. A `^{ }` first holds
the settings for all of it:

```
@<< ^{ seed:7 } k:3 n:8 duration/euclid len:8 pitch/walk zip >> run   → [C3/8. C3 C3/8]
@<< k:3 n:8 euclid '[60 64 67] [~] zip >> run                         → [C4/8. E4 G4/8]
@<< '[1/4] '[60] zip '[1/8] '[72 74] zip par >> run                   → {[C4/4] [C5/8]}
```

A bridge goes in where a type doesn't fit, as in a composer tree; `see`
shows the tree a recipe builds:

```
@<< euclid lorenz zip >> see
  builds (zip (pulses->durations (euclid)) (points->pitches (lorenz)))
```

A param written before an algo is that node's own; the head's reaches
every node that reads that name. Which value a node gets is decided by
name, most specific first: its alias's (`bass.k`), its algo's
(`duration/euclid.k`), the plain one (`k`), then the default:

```
@<< ^{ k:5 } duration/euclid '[60] [~] zip >> run count                → 5
@<< ^{ k:5 } k:2 duration/euclid '[60] [~] zip >> run count            → 2
@<< ^{ k:5 bass.k:6 } 'bass duration/euclid '[60] [~] zip >> run count → 6
```

The settings can also come from outside, an `@^{ }` right under the
recipe, so one recipe runs with different ones:

```
@^{ seed:7 k:3 n:8 len:8 } @<< euclid pitch/walk zip >> run   → [C3/8. C3 C3/8]
```

No music is written inside: music comes from the stack under the recipe.
What the tree needs below its own stack is taken from there when it runs,
a line taken apart into what the slot wants:

```
[c d e] @<< '[0.1 0.9] [~] numbers->volumes zip >> run
    → [C4/4\volume:44.0 D4\volume:76.0 E4\volume:44.0]
[c8 d e f g a b c] @<< dup duration/emi zip >> run      the line, its rhythm varied
```

`dup` (and `swap` `over` `rot`) arrange the stack; a `dup`'d algo is one
node, run once, so `len:8 uniform dup numbers->pitches swap
numbers->volumes …` gives pitch and volume from the same draws.

`run` puts the result on the stack. A recipe is lazy, so an endless one
is refused there and played instead, live; its settings are the voice's.
A recipe that takes nothing plays from nothing, one that takes music
plays on the music under it:

```
| @<< '[1/8] [~] scale [~] zip >> :sc >
:sc @^{ root:62 } set
[c d e] @<< '[0.1 0.9] [~] numbers->volumes zip >> :vol >
```

## 9. The GUI

The GUI shows a live voice's settings as controls -- a slider for a
range, a dropdown for a choice -- and the controls and the prompt are
two views of the same settings: a slider moves what `set` changes, and
`set` moves the slider.

- `gui` opens the GUI beside mu. Its **Wall...** button opens the Wall
  window: every live voice with its recipe and a control per setting.
  Move a slider and the voice changes on its next note.
- `:riff gui` opens a window for just that voice's settings.
- `compose` opens the **composer**: a canvas for the tree and, on the
  right, the algos by shelf. Drop an algo on the canvas to start (the
  root: usually something that gives notes, `zip`), then on each
  numbered hole in turn: the pane dims what doesn't fit the active hole,
  so the tree grows from the root to its leaves. A literal or a `:param`
  can fill a hole too (the fields below the pane); Undo, Redo and Remove
  correct it. **Finalize** closes the window and leaves the tree on the
  stack -- a recipe like a quotation:

  ```
  compose :tree >          play the composed tree live, from nothing
  :tree @^{ k:7 } set      … and set it from the prompt
  compose gui              its settings with a preview of what it gives,
                           and Play once / Live as / Stop
  ```

  `compose` with a tree on the stack opens it again to edit.

So the same recipe can be typed (`'( … )`), composed (`compose`) and
tuned by hand (`set`, a slider), and one voice plays it all the while.

## Words used

| Word | Stack |
|---|---|
| `duration/…` `pitch/…` `volume/…` … | `( [inputs] [@^{ … }] -- stream )` an algo |
| `see` | `( "word" -- )` its effect and settings |
| `durations` `pitches` | `( line -- stream )` a line taken apart |
| `zip` | `( durations pitches -- line )` notes of them; `( line stream -- line )` the stream's part written on each note |
| `dup` `swap` `over` `rot` | arrange the stack |
| `: name ( a -- b ) … ;` `$a` | a definition, its inputs as locals |
| `'( … )` `call` | a recipe kept, run |
| `[~]` `times` `[n]` `{n}` | loop, repeat, one after another, side by side |
| `x recipe [voice] >` (`>>` `>+` `>>+`) | play x through the recipe, live; `\|` or nothing under it: from nothing |
| `set` `unset` `settings` | `( voice @^{ } -- )` `( voice 'k -- )` `( voice -- )` |
| `retree` | `( recipe voice -- )` a new recipe, same settings and place |
| `compose` `gui` | the composer window; the GUI, or a voice's or a tree's settings |
