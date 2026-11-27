# A guide into `musics`

`musics` is for writing music as text and hearing it played live. You
use it through **mu**, a musical REPL in the terminal, and a **GUI**
beside it. This guide goes from top to bottom through actually using
it: write a piece, play it, look at it, change it while it's still
sounding, and keep it.

- For every word that works on music, see `doc/md/mu-tutorial.md`.
- For algorithms, from single words to recipes playing live, see
  `doc/md/algo-cookbook.md`.
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
- `help` is mu in a few lines, and `"chord" help` lists the words that mention chord;
- `words` lists every word;
- `"dup" see` says what one word does (`dup ( a -- a a )  copy the top`);
- a word mu does not know says what it is now called or what is close to it.
- Tab completes words, instrument names, context keys and stored parts.

## Your first piece

```
[verse: !mf c4 d e f] ->
```

This is one seq with two parts. `[verse: !mf c4 d e f]` is music: a
seq named `verse`, at mezzo-forte, of four quarter notes. Typed at the
prompt, it becomes a value on the stack. `->` plays the top of the
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
  `R!` stores a value, `R!!` stores it over a name that other parts use
  (see "Storing over a name" below), `R@` fetches it again, and `:name`
  inside music refers to a stored part. Nothing enters the repo without
  `R!` or `R!!`.

  ```
  [verse: !mf c4 d e f] R!          → :verse
  [c d e] :mel R!                   → :mel
  :verse R@                         → [verse: !mf C4/4 D4 E4 F4]
  [song: :verse :verse g2] R!       → :song, a part that uses :verse twice
  ```

### What a part is stored under

A stored part has one name, its **id**, and `R!` takes it from the first
of these that applies:

| You write | Stored as |
|---|---|
| `[c d e] :mel R!` | `:mel`, the name you put on top, which wins |
| `[verse: c d e] R!` | `:verse`, the id written in the literal |
| `[c d e] R!` | `:s2`, an id mu makes up (`s` for a seq, then a number) |

`R!` prints nothing and leaves the id it stored under on the stack, so you
can print it (`.`), fetch it (`R@`), look at it (`usages`, `ctx`) or bind it
(`'m [c d] R! :=`). Parts written inside a stored one (`[song: [a: c d] e]`)
are stored with it, under their own ids.

### Storing over a name

Storing under an id that is already taken **replaces what was there**, with
no message. That is how you work at the prompt: write the part again and
`R!` it. The first `[mel: c d e] R!` and a later `[mel: e f] R!` leave
`:mel` holding `[mel: E4/4 F4]`.

Replacing a part that **another part uses** is different, because that
part would change with it. `R!` refuses, names the parts it would change,
and stores nothing:

```
[verse: c4 d] R!
error: R!: :verse is used by :song -- R!! stores it anyway, changing them too; or give it a new id
```

**`R!!`** stores anyway and says which parts change with it:

```
[verse: c4 d] R!!
R!!: :verse is used by :song, so storing it changes them too
```

A voice that is already playing keeps the music it started with, so a
replacement is heard the next time a voice starts, or at the boundary you
choose (`-|>`, see "Changing it while it plays" below). Reading a file (`load`) or
the editor's text behaves as `R!!`, so re-reading a file is how a piece is
edited.

### Looking, forgetting, renaming

| Word | Does |
|---|---|
| `ids` | every stored part: `(:mel :song :verse)` |
| `:verse usages` | the parts that hold `:verse` directly: `#{:ROOT :song}` (`:ROOT` holds every stored part) |
| `:verse ctx` | its contexts, nearest first: `:verse: !volume:60@0` |
| `:verse R@` | the part itself, onto the stack |
| `:mel R_` | forget it (refused while another part still uses it) |
| `:verse :v2 rename` | give it a new id, and every part that uses it follows |

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
- **In a data list** `'[ ]`, a capital letter **alone** is a pitch class:
  `'[C D E]` is `'[0 2 4]`, `'[c d e]` the relative pitches `'[60 62 64]` and
  `'[C4 D4]` the absolute `'[60 62]`; a list holds one kind.
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

### Seqs and parallel parts

```
[melody: c4 d e f]                          [ ] one note after another
{duet: [sop: C5/4 d e] [alto: E4/4 f g]}    { } parts at the same time
[c d] [e f] {2}                             → {[C4/4 D4] [E4 F4]}   the top two side by side
[c ( d e ) f]                               ( ) notes grouped as one element: a seq can be turned round, shuffled or rotated by groups
```

### Key, tempo, meter, and the rest

```
[!key:D.major f4 c]                 → [!key:D.major F#4/4 C#4]   a bare letter takes the key's accidental
[!tempo:120 !Meter:7/8 c8 d e f g a b]
[3:2[ c8 d e ] f4]                  a triplet: three eighths in the time of two
[c d] 2 times                       → repeat 2 [C4/4 D4]
[c4\trill d\mordent e( f g)]        ornaments, a slur
[C4/1:7 | F3/2:m7 B&3:7]            chord symbols: a root and a quality
```

An instruction belongs inside a seq or a `^{ }` context, or stands alone: at
the prompt `!mf` is the context `^{ !mf }`, which `with` writes onto a seq
(`[c d] !mf with`). Grace notes, tremolo, tempo names,
repeats and the rest are in `doc/md/parsing.md`.

### Bar lines and comments

```
[c4 d e f | g a b c' || c1]     bar lines: zero length, but cues while playing
// a line comment; /* a block comment */
```

## Playing it back

`->` plays now and stops what plays; `-|>` plays at the next boundary;
a `+` joins instead of replacing. `stop` (or Alt+.) stops everything,
`pause` (Alt+,) pauses and goes on.

```
[c d e f g] ->
[c d e f g] [~] ->          loop it until stop
[e f g a b] -|>             takes over at the end of the current pass
[c d] [e f] {2} ->          two parts at once
```

Music with a name plays as a **voice** of that name, and playing it
again replaces only that voice:

```
[bass: c2 g] [~] ->          the voice :bass
[mel: e8 f g a] [~] ->       a second voice; :bass goes on
voices                      what plays, the highest voice on top
```

`doc/md/mu-tutorial.md`, section 2, has every play word.

## Changing it while it plays

Nothing you do to the stack or the store disturbs what is sounding. A
voice plays the music it was given, so you can prepare a change at
leisure and let it take over at a moment you choose:

```
[bass: f2 c] [~] -|>         :bass changes at the end of its pass
[mel: g8 a b c] [~] | -|>    at the next bar line instead
:mel hush                   silence :mel, keeping its place; again to undo
:bass solo                  only :bass sounds; again to undo
3 <- 12 + 3 -|>               voice 3, an octave up, at its boundary
```

`3 <-` puts what voice 3 plays on the stack, `12 +` moves it up an
octave, and `3 -|>` plays it as voice 3. Voices go by index from low to
high (`1` the lowest), the drums from `0` down.

A voice can also play a **recipe**: a quotation that changes the music
under it, and a word you define can make a voice of its own (see the
settings section below and `doc/md/mu-tutorial.md`, section 16):

```
[c d e g] [~] '( 5 + ) :up ->   the loop, a fourth higher, as the voice :up
```

## Settings: values a voice looks up

A **tctx** is a table of named values that lives with the session. Its
keys are keywords. A voice that reads them as it plays hears a change at
its next note.

```
8 :steps T!                          store a value under a key
:steps T@                           fetch it            → 8
'( :steps 2 * ) :twice T!            a value computed from the others
:twice T@                            the quotation, as stored
:twice T@@                           → 16, run
6 :steps T!                          change :steps ...
:twice T@@                           → 12   ... and :twice follows it
:steps T_                            take a key out
```

A computed value is a quotation given to `T!`; it runs when it is asked, with `:onset`
and every key of the tctx as keywords. Inside a voice or a computed key, a keyword where
a word needs a number is replaced by its value, so `:steps 2 *` means "the steps, doubled"; at the
prompt that does not happen, so use `T@@` there (`:steps T@@ 2 *`).

A voice can be a **function of the onset**: a word with `$onset` among its
inputs, asked for one note at the onset the voice has reached. The
settings are looked up at that onset, so nothing is precomputed:

```
6 :steps T!                          heard at the next note of a running voice
```

The whole table can also be written out, `@{ :steps 8 :twice '( :steps 2 * ) }`, and made the session's with
`:tctx R!` (it replaces what was there); `:tctx R@` writes it back in that form.

### A voice's own settings

A key written with a voice's name before the slash is that voice's own, and the voice reads it
instead of the shared one:

```
8 :steps T!                          the shared :steps
5 :bass/steps T!                     the bass's own :steps
:bass/steps T@                       → 5
:lead/steps T@                       → 8, a voice with none of its own reads the shared one
:bass/twice T@@                      → 10, :twice is computed from the bass's :steps
:bass/steps T_                       the bass reads the shared :steps again
```

A voice that reads `:steps` finds its own first. A computed key such as `:twice`, written once for
everyone, is worked out from each voice's own values. The values of a voice are kept by its
name, so they can be written before the voice starts and are there when it is started again, and a
saved session keeps them.

A voice's keys can be written once, as a block, instead of one by one:

```
@{ :steps 5 :density 3 } :bass T!    writes :bass/steps 5 and :bass/density 3
:bass T@                             → @{ :density 3 :steps 5 }, all the tctx holds for the bass
:bass T_                             takes all of it out
```

The block adds or changes those keys and leaves the voice's others; if one entry is refused (an input
of a word voice outside its range) none is written. The whole table is written the same way, a voice's
keys nested under its name, and `:tctx R@` prints it back like that:

```
@{ :steps 8
   :bass @{ :steps 5 :density 3 }
   :lead @{ :density 2 } } :tctx R!
```

Nesting is the path: `:bass @{ :steps 5 }` is `:bass/steps 5`, and `@{ :steps 8 :bass/steps 5 }`, written
flat, reads as the same thing. A shared key that is called like a voice is the key: `:bass T@` gives it.

### Groups of voices

A voice's name may have parts joined by dots, `:drums.kick`, and then it is in the group `:drums`. A
voice reads its own keys first, then its group's, then the shared ones:

```
8 :steps T!                          shared
4 :drums/steps T!                    every voice of the group :drums
5 :drums.kick/steps T!               the kick's own
:drums.kick/steps T@                 → 5
:drums.snare/steps T@                → 4, the group's
:bass/steps T@                       → 8, a voice outside it
5 8 'riff :drums.kick ->             a voice with a dotted name, its inputs :drums.kick/onsets and so on
```

Written nested, a group nests: `@{ :steps 8 :drums @{ :steps 4 :kick @{ :steps 5 } } }` is the same table,
printed back that way by `:tctx R@`. `:drums T@` is everything the tctx holds for the group and its voices,
`:drums T_` takes it all out. A group may be deeper, `:orchestra.strings.violin1`: each level is looked at
on the way from the voice to the shared keys.

`doc/md/pull-voice.md` and `doc/md/hoketus-dynamic.md` give the design.
Stored settings are saved with `save-session`.

## Files

| Word | Does |
|---|---|
| `"piece.mus" load` | read a file and store the parts it names: `stored :dyn_constant_soft …` |
| `"piece.ly" load` | the same for LilyPond, ABC (`.abc`) and GUIDO (`.gmn`), converted first |
| `x "x.mid" render` | write a MIDI file |
| `x "x.mid" 60 render` | the same for endless music: the first 60 seconds |
| `"piece.edn" save-session` | save the session: what is stored, your words (`: … ;`) and bindings (`:=`), not the stack |
| `"piece.edn" restore-session` | put it back, replacing what is stored and your words and bindings |

A `.mus` file is mu input, read just as the prompt reads it, so
anything you type can go in one.

## The GUI

`gui` opens the GUI beside mu, on what mu plays, and closing it leaves
mu running. Its main window holds the transport, a field to watch a
stored part and the buttons that open the other windows:
- **A watched part** gets a window of its own, its contexts as sliders
  that change what plays (`Root panel...` does the same for the
  defaults).
  Beside a slider, `z` narrows its range around the value (again and again, and
  it wraps back to the whole range) and `Z` widens it: symmetrical around the
  value, so a slider at 8 goes to 0..16, then -8..24, never past a range the
  parameter has.
- **Editor...**: text read as mu reads a file.
- **Browser...**: the stored parts and their structure.
- **Play Builder...**: what to play, and how, built by hand.
- **Settings...**: the session's tctx (`8 :steps T!`) as controls: a slider for each number, with `z`
  and `Z` beside it (a setting has no range, so the first is its own, symmetrical around the value and
  including zero, and `Z` widens it), a text field for a list, and a computed setting shown, not edited.
  A change is heard on the next note, and `T!` moves the control. `:tctx gui` opens it from mu. It has a section for each voice that has keys of its
  own (`5 :bass/steps T!`, and the inputs of a word voice): the voice's keys without its name, an input in
  its range, and a `reset` button to take a key out of the scope, so the voice reads the shared one
  again. `:bass gui` opens the same window at the bass's section.
- **Conductor...**: actions on the cues a voice passes, at a section, a
  bar or a bar line.
- **Wall...**: every live voice's settings as controls.
- **Record MIDI:** play a phrase on a MIDI keyboard and get it back as
  musics text, to edit, name and write as a `.mus` file. A keyboard
  needs no setup beyond `doc/md/setup.md`'s "MIDI input".
- **Persistence...**: write the repo to a file and load it back; Persist
  and Restore Session do what `save-session` and `restore-session` do.
- **Reset:** a fresh, empty repo.

`:riff gui` opens just one voice's settings, and the controls and the
prompt stay two views of the same settings.

## Gotchas

- **Storing replaces.** `R!` under a name that's taken replaces the
  part, with no message, unless another part uses it: then `R!` refuses
  and names those parts, and `R!!` stores anyway. Give the new one its
  own name if you don't want the old one gone.
- **Endless music has no end to write.** `render` and `run` refuse it;
  give `render` the seconds to write, or play it.

## Where to go next

- `doc/md/start-here.md`: which document fits where you are.
- `doc/md/mu-tutorial.md`: every music word, section by section.
- `doc/md/algo-cookbook.md`: algorithms as words, trees and recipes.
- `doc/md/parsing.md`: the full notation.
- `doc/md/lilypond.md`: LilyPond to musics, construct by construct.
- `doc/md/setup.md`: sound output, and MIDI input from a keyboard.
- `doc/md/walk-through.md`: one piece followed from text to sound.
- `CLAUDE.md`: the architecture underneath all of it.
