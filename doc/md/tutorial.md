# A guide into `musics`

`musics` is for writing music as text and hearing it played live. You
use it through **mu**, a musical REPL in the terminal, and a **GUI**
beside it. This guide goes from top to bottom through actually using
it: write a piece, play it, look at it, change it while it's still
sounding, and keep it.

- For every word that works on music, see `doc/md/mu-tutorial.md`.
- For algorithms, from single words to recipes playing live, see
  `doc/md/algo-tree-tutorial.md`.
- For the full notation, see `doc/md/parsing.md`; coming from
  LilyPond, see `doc/md/lilypond.md`.
- For the architecture underneath, see `CLAUDE.md`.

## Starting

```bash
lein mu
```

mu shows its version and the words that drive it, then a prompt:
`mu<0>`, where the number is how many values are on the stack. `bye` or
Ctrl+D leaves.

To hear anything, connect a synthesizer once:
- `gervill`: Java's own synthesizer, nothing to set up;
- `fluidsynth`: through qsynth and a virtual MIDI port (see `doc/md/setup.md`).

Without either, everything below still works; you just don't hear it.

A few words for finding your way:
- `words` lists every word;
- `"dup" see` says what one word does (`dup ( a -- a a )  copy the top`);
- Tab completes words, instrument names, context keys and stored parts.

## Your first piece

```
[verse: !mf c4 d e f] >
```

This is one line with two parts. `[verse: !mf c4 d e f]` is music: a
line named `verse`, at mezzo-forte, of four quarter notes. Typed at the
prompt, it becomes a value on the stack. `>` plays the top of the
stack, now.

That shape is the shape of everything else in this guide: music goes
on the stack, words after it change it, combine it and play it.

## Values, names and the store

A value on the stack is just a value: nothing is stored anywhere yet.
`.s` shows the stack, top first; `clear` empties it.

```
[c d e]            → [C4/4 D4 E4]
[c d e] .s         shows the stack
```

There are two ways to keep music:

- **For the session:** bind a name. `\name` is the value wherever you
  write it, in music too.

  ```
  'motif [c e g] :=
  \motif 5 +                → [!K:F.major F4/4 A4 C5]
  [melody: \motif f g \motif]
  ```

- **In the repo**, the store that the GUI, files and playback share:
  `R!` stores the value under its own name, or under an id you give.
  `R@` fetches it again, and `:name` inside music refers to it.

  ```
  [verse: !mf c4 d e f] R!          → :verse
  [c d e] :mel R!                   → :mel
  :verse R@                         → [verse: !mf C4/4 D4 E4 F4]
  [song: :verse :verse g2] R!       a part that uses :verse twice
  ```

Storing under a name that is already taken replaces what was there,
and mu warns you when another part uses it:
`Redefining :verse also affects [:song]`.

To look at what's stored:

| Word | Does |
|---|---|
| `ids` | every stored part: `(:mel :song :verse)` |
| `:verse usages` | who uses `:verse`: `#{:ROOT :song}` |
| `:verse ctx` | its contexts, nearest first: `:verse: volume=60@0` |
| `:verse R@` | the part itself, onto the stack |

## Writing music: a short tour

Anything you can write in a `.mus` file you can type at the prompt.
These are the essentials; `doc/md/parsing.md` has the full notation.
mu prints every value back in full, as shown after the `→`.

### Notes, octaves, durations

```
[c4 d8 e16 f]          → [C4/4 D4/8 E4/16 F4]          f keeps the sixteenth
[c4. d8 e2~ e8]        → [C4/4. D4/8 E4/2~ E4/8]       a dotted quarter, a tie
[C4/4 G4/4 C5/2]       → [C4/4 G4 C5/2]                absolute pitches
[c4 f# b& cn']         → [C4/4 F#4 B&4 C6]             accidentals # ## & && n; ' and , move an octave
```

- **Lowercase is relative:** a lowercase letter is the pitch nearest
  the one before it (a fourth or a fifth away at most), starting from
  C4.
- **Uppercase is absolute:** `C5/4`. Without an octave it keeps the one
  before it, as a duration carries over: `[C5/4 D E]` is C5 D5 E5.
- **A digit after a lowercase letter is always a duration**, never an
  octave. Move octaves with ticks (`c,` `c'`) or write an absolute
  pitch (`C3/4`).

### Dynamics

```
[!mf c4 d e f]              a dynamic instruction, from here on
[c4 d\f e f]                → [C4/4 D4\volume:70 E4 F4]   glued to one note
[!vol:p< c4 d e !vol:f g]   a crescendo is an instruction: p, rising to f
```

### Chords, rests, drums

```
[<c e g>4 r4 r <d f# a>2]                 a chord, two rests, a chord
[x8 x\kick x4\36]                         drums: plain, by name, by MIDI number
[x8\snare-> x8\snare-^ x8\snare\ghost]    accented, marcato, ghost
```

### Lines and parallel parts

```
[melody: c4 d e f]                          [ ] one note after another
{duet: [sop: C5/4 d e] [alto: E4/4 f g]}    { } parts at the same time
[c d] [e f] {2}                             → {[C4/4 D4] [E4 F4]}   the top two side by side
```

### Key, tempo, meter

```
[!key:D.major f4 c]                 → [!key:D.major F#4/4 C#4]   a bare letter takes the key's accidental
[!acc:explicit !key:D.major f4]     → F4: every letter literal
[!tempo:120 !Meter:7/8 c8 d e f g a b]
[!tempo:3/8=90 !allegro c4]         a note value = BPM; a named tempo
```

An instruction always belongs inside a line or a `^{ }` context; at the
prompt on its own, `!mf` is refused.

### Tuplets, repeats, grace notes, ornaments, chord symbols

```
[3:2( c8 d e ) f4]          a triplet: three eighths in the time of two
repeat 2 [c4 d e f]
[c d] 2 times               → repeat 2 [C4/4 D4]
[acciaccatura c16 d4 e]     → [C4/16 D4/8. E4/4]   the grace note takes its time from the next
[c4\trill d\mordent e( f g)]   ornaments, a slur
[c4:32]                     a tremolo: 8, 16, 32, 64 or 128
[C4/1:7 | F3/2:m7 B&3:7]    chord symbols: a root and a quality
```

### Bar lines and comments

```
[c4 d e f | g a b c' || c1]     bar lines: zero length, but cues while playing
% a line comment; %{ a block comment %}
```

## Playing it back

`>` plays now and stops what plays; `>>` plays at the next boundary;
a `+` joins instead of replacing. `stop` (or Alt+.) stops everything,
`pause` (Alt+,) pauses and goes on.

```
[c d e f g] >
[c d e f g] [~] >          loop it until stop
[e f g a b] >>             takes over at the end of the current pass
[c d] [e f] {2} >          two parts at once
```

Music with a name plays as a **voice** of that name, and playing it
again replaces only that voice:

```
[bass: c2 g] [~] >          the voice :bass
[mel: e8 f g a] [~] >       a second voice; :bass goes on
voices                      what plays, the highest voice on top
```

`doc/md/mu-tutorial.md`, section 2, has every play word.

## Changing it while it plays

Nothing you do to the stack or the store disturbs what is sounding. A
voice plays the music it was given, so you can prepare a change at
leisure and let it take over at a moment you choose:

```
[bass: f2 c] [~] >>         :bass changes at the end of its pass
[mel: g8 a b c] [~] | >>    at the next bar line instead
:mel hush                   silence :mel, keeping its place; again to undo
:bass solo                  only :bass sounds; again to undo
3 < 12 + 3 >>               voice 3, an octave up, at its boundary
```

`3 <` puts what voice 3 plays on the stack, `12 +` moves it up an
octave, and `3 >>` plays it as voice 3. Voices go by index from low to
high (`1` the lowest), the drums from `0` down.

A voice can also play a **recipe**: a quotation that makes the music,
or changes the music under it. Each note it reaches is the next one the
recipe gives *now*, so a change to its settings is heard on the next
note:

```
| '( duration/euclid [c e g] pitches zip [~] ) :riff >
:riff @^{ k:5 } set         five beats instead of three, heard at once
:riff settings              every setting and its range
[c d e g] [~] '( 5 + ) :up >   the loop, a fourth higher
```

`doc/md/mu-tutorial.md` (section 16) and `doc/md/algo-tree-tutorial.md`
go on from here.

## Files

| Word | Does |
|---|---|
| `"piece.mus" load` | read a file and store the parts it names: `stored :dyn_constant_soft …` |
| `"piece.ly" load` | the same for LilyPond, ABC (`.abc`) and GUIDO (`.gmn`), converted first |
| `x "x.mid" render` | write a MIDI file |
| `x "x.mid" 60 render` | the same for endless music: the first 60 seconds |

A `.mus` file is mu input, read just as the prompt reads it, so
anything you type can go in one.

## The GUI

`gui` opens the GUI beside mu, on what mu plays, and closing it leaves
mu running. Its main window holds the transport, a field to watch a
stored part and the buttons that open the other windows:
- **A watched part** gets a window of its own, its contexts as sliders
  that change what plays (`Root panel...` does the same for the
  defaults).
- **Editor...**: text read as mu reads a file.
- **Browser...**: the stored parts and their structure.
- **Play Builder...**: what to play, and how, built by hand.
- **Conductor...**: actions on the cues a voice passes, at a section, a
  bar or a bar line.
- **Wall...**: every live voice's settings as controls.
- **Record MIDI:** play a phrase on a MIDI keyboard and get it back as
  musics text, to edit, name and write as a `.mus` file. A keyboard
  needs no setup beyond `doc/md/setup.md`'s "MIDI input".
- **Persistence...**: write the repo to a file and load it back.
- **Reset:** a fresh, empty repo.

`:riff gui` opens just one voice's settings, and the controls and the
prompt stay two views of the same settings.

## Gotchas

- **Storing replaces.** `R!` under a name that's taken replaces the
  part for everything that uses it; mu names those parts. Give the new
  one its own name if that's not what you want.
- **Endless music has no end to write.** `render` and `run` refuse it;
  give `render` the seconds to write, or play it.

## Where to go next

- `doc/md/mu-tutorial.md`: every music word, section by section.
- `doc/md/algo-tree-tutorial.md`: algorithms as words, trees and recipes.
- `doc/md/parsing.md`: the full notation.
- `doc/md/lilypond.md`: LilyPond to musics, construct by construct.
- `doc/md/setup.md`: sound output, and MIDI input from a keyboard.
- `doc/md/walk-through.md`: one piece followed from text to sound.
- `CLAUDE.md`: the architecture underneath all of it.
