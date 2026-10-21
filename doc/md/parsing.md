# Parsing: the text notation and its parser

The musics notation is parsed in stages, each in its own module. The
grammar, `resources/musics/input/musics.ebnf`, is always the source of truth; every
example in this document is checked against it.

```
text
  │
  ├─ instaparse (musics.ebnf)        raw parse tree (nested vectors)
  │    comments and variables are grammar rules, not a text
  │    pre-processing step: a parse error's line/column always
  │    matches the text as written
  │
  ├─ walker/walk            {:tree repo-map :auto-ids … :var-map …}
  │    a flat {id -> node} map, not a tree of pointers
  │
  └─ musics.repo/commit-many!           new and changed ids land in the
                                       store in one atomic swap!
```

Entry points, in `musics.input.grammar-parser`:

```clojure
(parse-domain-string text)  ;; parse + walk -> {:tree repo-map :auto-ids ...}; throws on error
(try-parse text)            ;; parse only; prints a formatted error and returns nil on failure
```

At the REPL use `musics.core/parse` instead: it walks against what's
already committed (so references to earlier parts resolve) and commits
the result immediately.

---

## 1. Brackets

| Bracket | Rule | Meaning |
|---|---|---|
| `[ ]` | `Sequence` | a musical sequence: its elements one after another |
| `{ }` | `Parallel` | simultaneous parts |
| `^{ }` | `Context` | a named set of instructions (a context/envelope definition), replayed where it's referenced |
| `'[ ]` | `Data` | a data container of plain values; `nil` is no value, a `'[ ]` inside is a list (`'[60 nil '[60 64]]`), and a `:keyword` may stand beside any value (`'[40 :Sea 73]`) |
| `( )` | `Scope` | the body of `transpose`, the row forms (`retrograde`/`R`, `inversion`/`I`, `retrograde-inversion`/`RI`, `mirror`/`M`), `shuffle ( )` and `rotate n ( )`: changed now, never a container of its own |

`( )` also closes and opens a slur when it's glued onto a note
(`c4( d e)`), which can never be confused with a `Scope`.

Sequences, parallels and contexts can carry an **Id**, which registers
them under that name; a **Reference** `:name` uses a registered part:

```mus
[verse: c4 d e f]
[song: :verse :verse g2]
```

A reference to a `^{ }` context replays its instructions onto the
current container at the current beat:

```mus
^{loud: !ff !tempo:132}
[chorus: :loud c4 e g c5]
```

A `Parallel` holds sequences, references, contexts, instructions and
commands — not bare notes (a chord is `<c e g>`):

```mus
{chorale: [sop: c'4 d e] [bass: c,2 g]}
```

## 2. Notes

### Pitch

A pitch is a letter, an optional accidental, an optional octave:

```
PitchLetter   a-g relative,  A-G absolute (then an octave digit 1-8)
Accidental    #  ##  &  &&  n    sharp, double sharp, flat, double flat, natural
Octave        4/ (absolute)   or   ' '' , ,, (relative ticks)
```

Relative vs. absolute is decided by the **case** of the letter:
uppercase is an absolute pitch (`C4`, `F#5`); lowercase is resolved
relative to the previous pitch — the nearest one, a fourth or fifth
away at most, LilyPond `\relative`-style — even for the first note of a
sequence (which is relative to C4). Ticks move a relative pitch an
octave up (`'`) or down (`,`). An uppercase letter without an octave
takes the octave of the pitch before it, as a note without a duration
takes its duration: `[C5/4 D E]` is C5 D5 E5, `<C5 E G>` C5 E5 G5, and
`C2/1:7 | F:7` F2 -- the octave number, not the nearest pitch (`C5 B` is
B5); octave 4 at the start.

The octave digit of an absolute pitch is followed by `/` when a
duration comes next (`C4/8`: C4, an eighth); without it the digits
would run together.

```mus
[c4 d e f]            % relative: C4 D4 E4 F4
[C4/4 G4/4 C5/2]      % absolute
[C5/4 D E <F A C>]    % absolute, the octave carried: C5 D5 E5 <F5 A5 C5>
[c4 f# b& cn']        % sharp, flat, natural (the accidental comes before the octave)
```

**Key-implied accidentals.** A bare letter takes the accidental its key
implies, as on a real staff: under `!key:D.major`, `f` and `c` sound as
F# and C#. An accidental written on the note always wins. `!acc:explicit`
makes every bare letter natural instead (the importers write this, since
LilyPond and ABC text is literal); `!acc:implied` is the default. C major
implies nothing, so a piece without `!key:` is unaffected.

```mus
[!key:D.major f4 c d fn]    % F# C# D F
[!acc:explicit f4 c]        % F C
```

### Duration

```
4       quarter note (1/4)
8.      dotted eighth (3/16)
16..    double-dotted sixteenth
\longa  4 whole notes
\breve  2 whole notes
```

A note without a duration takes the previous one. A note value is a
power of two (`1 2 4 8 16 32 64 128`), dotted or not, or `\breve`/
`\longa`. A tuplet is a count before a group: `3:2( c8 d e )` is
three eighths in the time of two, `5:4( c16 d e f g )` five
sixteenths in the time of four. A single note can carry a factor of its
own, `*Ratio`, inherited by the following notes until a new duration is
written: `c8*2/3` is a triplet eighth.

```mus
[c4 d e f]                 % all quarters
[3:2( c8 d e ) f4]        % an eighth-note triplet, then a quarter
[c8*2/3 d e f4]            % the same, as factors on the notes
```

### Articulation, ornaments, dynamics

```mus
[c4-. d-> e-_ f\staccato]         % shorthand -. -> -_ ..., or named
[c4\trill d\mordent e\fermata]     % ornaments
[c4 d\f e !vol:p< f g !vol:ff]    % a dynamic on one note; a crescendo
[c4\vol:80 d\pan:0.5]              % a modifier: any context key, on one note
```

- Shorthand articulations: `-.` `->` `-^` `-_` `-!` `-+` `--`. Named:
  `staccato`, `staccatissimo`, `tenuto`, `marcato`, `portato`, `accent`,
  `espressivo`.
- Ornaments (18): `prall`, `prallup`, `pralldown`, `upprall`,
  `downprall`, `prallprall`, `lineprall`, `prallmordent`, `mordent`,
  `upmordent`, `downmordent`, `trill`, `turn`, `reverseturn`,
  `shortfermata`, `fermata`, `longfermata`, `verylongfermata`. They're
  expanded into sub-notes at play time (`musics.domain.ornaments`), using
  the active key for scale-relative ones.
- A glued dynamic (`d\f`) is that note's own volume, like `d\vol:70`;
  the next note is back at the context's. A crescendo or decrescendo
  spans time, so it is an instruction: `!vol<` (toward whatever level
  comes next), `!vol:p<` (set p and start one), `!vol<2:ff` (timed).
- Tags after a note may repeat and come in any order; a tie `~` comes
  last.

### Chords, ties, slurs, tremolo

```mus
[<c e g>4 <d f# a>2.]      % chords: two or more pitches, one duration
[c4~ c8 d]                 % tie
[c4( d e f) g]             % slur
[c4:32 <c e>4:16]          % tremolo on a note or chord: 8, 16, 32, 64 or 128
[F3/1:7 | C4/2:m7 F3:7 | B&3/1:M7 | B3:d7 | G3:7.9-/B]   % chord symbols
```

- A note with a `:` quality is a chord: its root, then the quality.
  The qualities are LilyPond's chordmode words (`7`, `maj7`, `m7`,
  `dim7`, `aug`, `sus4`, `9`, `11`, `13`, ...) and the shorthand `m`
  minor, `M` major (`M` the triad, `M7`..`M13` the major-seventh
  chords), `d` diminished, `A` augmented, `s` suspended (`s` = `s4`).
  A bare number is dominant: `7` is C E G B♭.
- `.step` adds or alters a step, `^step` removes steps (`m7.5-`,
  `7.9+`, `9^3`); `/pitch` puts a chord tone in the bass, `/+pitch`
  adds one (`C4:7/E`).
- A chord symbol keeps the duration and the relative-pitch chain going,
  as a note does. A note without a quality stays a note; `:M` makes it
  a major triad.

### Rests and drums

```mus
[r4 r]                     % rests; a bare r keeps the previous duration
[R1*4]                     % multi-measure rest: 4 bars of a whole note
[x8\kick x x4\36 x]        % drum hits by name or MIDI number; a bare x is the previous drum
[x4\snare~ x8 x\kick]      % a tie: the snare is struck once, held a dotted quarter
[x8\kick,clap x x\42]      % layered: one stroke sounds every key listed
```

A drum's key is inherited like a duration: `x` plays the drum before it
(the first `x` of a piece, with none before, plays key 35). A tied drum
(`~`, last, as on a note) is struck once: the next drum, if it has the
same key, isn't struck and only adds its length, across a bar line too.
A drum with several keys (`x8\36,39`) is one stroke sounding all of
them on the drum channel; a bare `x` after it repeats the whole layer.

`R` also works without a duration (`R`, `R*4`): it then takes one bar's
length from the active `Meter`.

### Bar lines

```mus
[c4 d e f | g a b c' || c1]
```

`|` to `||||` are zero-duration markers. At playback each one fires a
`musics.conductor` `:mark` signal (its pipe count 1–4), a cue you can
schedule actions on. A run of several (`c4 | | d4`) is legal.

## 3. Instructions

`!` followed by a name, with no spaces inside:

```mus
[!mf !f c4 d]                        % a dynamic (a named constant)
[!vol:80 !pan:-0.5 c4]               % key:value, any context key
[!i:40 c4]                           % an instrument (a MIDI program)
[!i:seashore c1 d\i:birdTweet]       % or its General MIDI name, camelCase
[!i:sea c1 d\i:vib]                  % or its short name (vib the vibraphone; a drum's vib is the vibraslap)
[!kit:brush x8\sna x\hhc]            % the drum kit: standard room power electronic tr808 jazz brush orchestra sfx, or a number
[!i:40,73,0 c4 d]                    % several: each note played by all three
[c4\i:40,73 d]                       % on one note: c by two instruments, d as before
[!K:C.major !K:F#.dorian c4]         % a key: a tonic, # or &, and a scale (!K:, also !Key: !key: !k:)
[!key:B&.minor !key:d.harmonic-minor c4]
[!tempo:120 c4]                      % tempo in BPM (a quarter note)
[!tempo:3/8=90 c4]                   % note value = BPM (a dotted quarter at 90)
[!tempo:4.=90 c4]                    % the same, the note value dotted
[!allegro !marciaModerato c4]        % named tempo markings
[!Meter:7/8 c4]                      % a meter
[!Meter:"7/8(2+2+3)" c4]             % a meter with explicit grouping
[!M:223 c8]                          % a meter as the factors of its pulses: 2x2x3 eighths (12/8)
[!M:232 c8]                          % the same length grouped otherwise; !M:2232/16 sixteenths
[!swing c8 d e f]                    % swung eighths, the off-beat late (0.6)
[!swing:0.7 !swingUnit:16 c16 d e f] % the long note's share of a pair; sixteenths
[!straight !shuffle c8 d]            % 0.5 straight; !shuffle 2/3, a triplet feel
[!laidBack c16 d e f]                % weak places a little late (!groove:0.5)
[!groove:-0.3 c16 d e f]             % pushed: a little early
[!accent:metric c8 d e f g a b c]    % each note weighed by its level in the bar (by the meter)
[!accent:barlow c8 d e f g a b c]    % by Barlow's finer ranking, every pulse its own weight
[!accent:agogic !accentDepth:1 c16 d8 e4]   % or by its length; how much, 0..1 (0.5)
[!left !center !right c4]            % panning
[!/mf c4]                            % invalidate: clear a value set here
[partial 8 c8 | c4 d e f]           % a pickup: the first bar is an eighth
```

`!tempo:`, `!Tempo:` and `!T:` are the same key; every context key has
short aliases (see `musics.common.context-keys`). A `TempoMark` (`3/8=90`, `4.=90`) is
converted to quarter-note BPM when parsed. Only an instrument takes a list
(`!i:40,73`, or `\i:40,73` on one note): the note sounds once per
instrument, each instrument on its own MIDI channel. The full list of named
constants is `musics.common.music-data/instruction-context`.

### Ramps

A ramp moves a value over time. The direction (`<` up, `>` down) follows
the key name directly; an optional curve letter follows the direction
(`l` linear, the default; `s` smooth; `i` ease-in; `o` ease-out):

```mus
[!vol< c4 d !vol:ff e f]           % open-ended: ramps to the next value set
[!vol<s c4 d !vol:p e]             % with a curve
[!vol<16:ff c4 d e f]              % timed: over 16 (whole notes) to ff
[!vol<s:16:ff c4 d e f]            % timed, with a curve
[!vol:mf< c4 d !vol:ff e]          % set a value and start a ramp from it
```

The timed form's duration is a whole-note count, a product (`16*4`) or
a ratio (`16/1`); its target a dynamic mark or a number.

## 4. Commands

Backslash-prefixed, LilyPond-style.

```mus
[transpose c d ( c4 e g )]                   % shift by the interval c -> d
[retrograde ( c4 d e f )]                    % backwards (also R ( … ))
[TRP 5 ( c4 d e )]                           % transpose by 5 semitones (TRP = transpose, SFL = shuffle, ROT = rotate)
repeat 2 [c4 d e f]
repeat 4 [c8 d]
repeat 2 [c4 d] volta [e2]
repeat 3 [c4 d] volta [[e2] [f2]]  % several endings
repeat tremolo 4 [c16 d]                     % measured tremolo
repeat ~ [c4 d]                              % endlessly
[: c4 d :] volta [[e2] [f2]]                 % repeat signs: repeat 2 [c4 d]
[c4 $ d e DS f]                              % plays c d e d e f
[c4 d Fine e DC]                             % plays c d e c d
[c4 Coda d e DC Coda f]                      % plays c d e c f
[c4 d | ./. | e]                             % plays c d c d e
[grace c16 d4 acciaccatura e16 f4 afterGrace g4 a16]
```

- `transpose` and the row forms take a `Scope` `( )` body. It's spliced
  into the parent, never registered as a part of its own. Neither
  reaches into a part referenced inside that body.
- `repeat` (`repeat 2 [ ]`, or `repeat tremolo 8 [ ]`) and its endings, `volta`, take a
  `Sequence` body, kept as a real container that an `Iterator` replays
  on each pass.
- A volta's endings follow LilyPond: every pass is followed by an
  ending, the last pass by the last one, the passes before it by the
  ones before, the earliest passes sharing the first. One ending
  follows every pass; several are written one bracket each inside the
  volta's, `volta [[e2] [f2]]`.
- `repeat ~` repeats endlessly and takes no `volta`. `[: … :]` is
  `repeat 2 [ … ]` written in repeat signs, and prints back that way.
- Jump marks sit inside a sequence and take no time: `$` the segno,
  `DS` and `DC` the jump back to it or to the sequence's start, `Fine`
  where the return pass stops, `Coda` twice (the return pass leaves at
  the first for the second). `./.` plays the bar before it once more,
  and stands alone in its bar: a bar line before it, a bar line or the
  end after it. A return pass goes on past its jump without taking it
  again; marks that can't be played are an error.
- Grace notes take two elements: `grace`, `acciaccatura`,
  `appoggiatura` and `slashedGrace` put the grace note first,
  `afterGrace` the main note first.

## 5. Variables

A variable holds a sequence, defined at the top level and referenced
with a backslash:

```mus
motif = [c4 d e f]
[piano: \motif g a \motif]
```

- The reference splices the notes in flat, as direct siblings. An
  instruction inside the definition (`!f`, `c4\f`) takes effect at the
  reference and continues past it.
- A variable must be defined before it's referenced (one sequential
  walk); a later definition of the same name replaces it from then on.
  An undefined reference is an error at its own line and column.
- Definitions are only allowed at the top level, never inside a bracket.
- A name is letters, digits and underscores, but not an ornament name or
  a command word (`trill`, `transpose`, `repeat`, …), so `\trill` always
  means the ornament.
- Variables persist across `parse` calls in the same session.

## 6. Comments

```mus
[c4 d e f]      % a line comment, to the end of the line
%{ a block
   comment %}
```

Both are grammar rules (`Comment`), allowed wherever whitespace is; the
walker drops them.

## 7. What a program may contain at the top level

A program's top level holds containers, notes, `repeat` and variable
definitions. Instructions, references and `transpose`/the row forms/grace
commands are not allowed bare at the top level, because they'd write
into `:ROOT`, the read-only defaults. A bare note is wrapped in its own
one-note sequence, so `c4\f` at the top level is fine; a bare `repeat`
is wrapped the same way, so it gets an id of its own to play. Two bare
notes (`c4 d4`) are two separate sequences; group them with `[ ]`.

## 8. The walker

How the parse tree becomes the flat repo — the build state, the handler
per node type — is stage 2 of `doc/md/walk-through.md`.

## 9. Errors

A parse failure is reported with the source line, a caret under the
position, the tokens that were expected, and the line and column:

```
-- Parse error --- line 1, column 5 --
|
|  c4 d !
|      ^
|
|  Expected one of:
|    * pitch letter
|    * name
|    * duration (e.g. 4, 8., 16..)
------------------------------------------
```

`try-parse` prints this and returns `nil`; `parse-domain-string` throws
it; `musics.core/parse` prints it.
