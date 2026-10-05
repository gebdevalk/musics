# Parsing: the text notation and its parser

The musics notation is parsed in stages, each in its own module. The
grammar, `src/input/musics.ebnf`, is always the source of truth; every
example in this document is checked against it.

```
text
  │
  ├─ instaparse (musics.ebnf)        raw parse tree (nested vectors)
  │    comments and variables are grammar rules, not a text
  │    pre-processing step: a parse error's line/column always
  │    matches the text as written
  │
  ├─ flat-tree-walker/walk            {:tree repo-map :auto-ids … :var-map …}
  │    a flat {id -> node} map, not a tree of pointers
  │
  └─ core.repo/commit-many!           new and changed ids land in the
                                       store in one atomic swap!
```

Entry points, in `input.grammar-parser`:

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
| `'[ ]` | `Data` | a data container of plain values |
| `( )` | `Scope` | the body of `\transpose` / `\reverse`; never a container of its own |

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
octave up (`'`) or down (`,`).

The octave digit of an absolute pitch is followed by `/` when a
duration comes next (`C4/8`: C4, an eighth); without it the digits
would run together.

```mus
[c4 d e f]            % relative: C4 D4 E4 F4
[C4/4 G4/4 C5/2]      % absolute
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

A note without a duration takes the previous one. A duration can carry a
`*Ratio` suffix, which scales it — this is how tuplets are written:
`c4*2/3` is a triplet quarter. The scaled duration is inherited by the
following notes until a new duration is written.

```mus
[c4 d e f]                 % all quarters
[c8*2/3 d e f4]            % an eighth-note triplet, then a quarter
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
  expanded into sub-notes at play time (`core.domain.ornaments`), using
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
[c4:32 <c e>4:16]          % tremolo on a note or chord
```

### Rests and drums

```mus
[r4 r]                     % rests; a bare r keeps the previous duration
[R1*4]                     % multi-measure rest: 4 bars of a whole note
[x8 x\kick x4\36]          % drum hits: plain, by name, by MIDI number
```

`R` also works without a duration (`R`, `R*4`): it then takes one bar's
length from the active `Meter`.

### Bar lines

```mus
[c4 d e f | g a b c' || c1]
```

`|` to `||||` are zero-duration markers. At playback each one fires a
`core.conductor` `:mark` signal (its pipe count 1–4), a cue you can
schedule actions on. A run of several (`c4 | | d4`) is legal.

## 3. Instructions

`!` followed by a name, with no spaces inside:

```mus
[!mf !f c4 d]                        % a dynamic (a named constant)
[!vol:80 !pan:-0.5 c4]               % key:value, any context key
[!key:C.major !key:F#.dorian c4]     % a key
[!tempo:120 c4]                      % tempo in BPM (a quarter note)
[!tempo:3/8=90 c4]                   % note value = BPM (a dotted quarter at 90)
[!allegro !marciaModerato c4]        % named tempo markings
[!Meter:7/8 c4]                      % a meter
[!Meter:"7/8(2+2+3)" c4]             % a meter with explicit grouping
[!straight !swing !shuffle c4]       % swing feel
[!left !center !right c4]            % panning
[!/mf c4]                            % invalidate: clear a value set here
[\partial 8 c8 | c4 d e f]           % a pickup: the first bar is an eighth
```

`!tempo:`, `!Tempo:` and `!T:` are the same key; every context key has
short aliases (see `common.context-keys`). A `TempoMark` (`3/8=90`) is
converted to quarter-note BPM when parsed. The full list of named
constants is `common.music-data/instruction-context`.

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
[\transpose c d ( c4 e g )]                   % shift by the interval c -> d
[\reverse ( c4 d e f )]                       % reverse the order
\repeat volta 2 [c4 d e f]
\repeat unfold 4 [c8 d]
\repeat volta 2 [c4 d] \alternative [e2]
\repeat tremolo 4 [c16 d]                     % measured tremolo
[\grace c16 d4 \acciaccatura e16 f4 \afterGrace g4 a16]
[\chordmode ( c4:maj7 f:m7 g:7/b )]
```

- `\transpose` and `\reverse` take a `Scope` `( )` body. It's spliced
  into the parent, never registered as a part of its own. Neither
  reaches into a part referenced inside that body.
- `\repeat` (`volta`, `unfold`, `tremolo`) and `\alternative` take a
  `Sequence` body, kept as a real container that an `Iterator` replays
  on each pass.
- Grace notes take two elements: `\grace`, `\acciaccatura`,
  `\appoggiatura` and `\slashedGrace` put the grace note first,
  `\afterGrace` the main note first.
- `\chordmode` writes chords by root and quality (`c:maj7`, `c:m7/g`,
  added/removed steps `c:7.9+^5`).

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

A program's top level holds containers, notes, `\repeat` and variable
definitions. Instructions, references and `\transpose`/`\reverse`/grace
commands are not allowed bare at the top level, because they'd write
into `:ROOT`, the read-only defaults. A bare note is wrapped in its own
one-note sequence, so `c4\f` at the top level is fine; a bare `\repeat`
is wrapped the same way, so it gets an id of its own to play. Two bare
notes (`c4 d4`) are two separate sequences; group them with `[ ]`.

## 8. The walker

How the parse tree becomes the flat repo — the build state, the handler
per node type — is stage 2 of `doc/walk-through.md`.

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
