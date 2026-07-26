# Parsing Pipeline & Syntax

The musics DSL is parsed in stages, each implemented in its own module.

```
text
  │
  ├─ vars/extract-vars    strip "name = ..." definitions
  ├─ vars/expand-vars     replace \name references with stored text
  ├─ strip-comments       remove ; line comments and (comment ...) blocks
  │
  ├─ instaparse           EBNF grammar → raw tree (nested vectors)
  │
  ├─ flat-tree-walker/walk   raw tree → {:tree repo-map :auto-ids ...}
  │    (a flat {id -> container} map, not a tree of pointers -- see
  │    CLAUDE.md's "Domain model" section)
  │
  └─ core.repo/stage! + commit-staged!   new/changed ids land in the
       versioned store as one atomic tx (see CLAUDE.md's "Session, the
       versioned repo, and playback")
```

Entry points (all in `input.reader.parser.grammar-parser`):

```clojure
(parse-domain text)         ;; full pipeline → {:tree repo-map :auto-ids ...}
(parse-domain-string text)  ;; without variable pre-processing
(try-parse text)            ;; parse only, formatted error on failure
(parse text)                ;; raw instaparse tree
```

In practice, use `musics.clj`'s `(parse text)` instead of calling this
namespace directly — it walks against the session's current committed
repo and *stages* the result (see CLAUDE.md), which these lower-level
entry points don't do on their own.

---

## 1. Variables

Defined before parsing as text-level macros.

```
verse = c4 d4 e4 f4
```

Referenced with backslash:

```
{piano: \verse g4 a4}
```

`vars/extract-vars` strips definitions from the input and registers
them.  `vars/expand-vars` replaces `\name` with the stored text.
Expansion is recursive (a variable can reference another).

---

## 2. Comments

Two old-style comment systems are stripped before parsing:

- `;` — line comment (to end of line)
- `(comment ...)` — block comment (nested parens tracked)

The grammar itself also handles comments in the `ws` rule:

- `%` — line comment (to end of line)
- `%{ ... %}` — block comment (non-nested)

`|`/`||`/`|||`/`||||` (`BarLine`) are **not** treated as whitespace —
they're a real grammar rule now, walked into a `Bar` record and, at
playback, firing a `core.conductor` `:mark` signal (see section 6 and
CLAUDE.md's "Conductor" section).

---

## 3. Grammar

The grammar lives in `src/input/reader/parser/musics.ebnf` (instaparse
EBNF format, explicit whitespace, no auto-ws) — always the source of
truth over this doc when they disagree.

### Element hierarchy

```
Element
├── Part
│   ├── Sequence           { ... }
│   ├── Parallel           << ... >>
│   ├── Unit               ( ... )        -- grouped, no context of its own
│   ├── Data               '[ ... ]
│   ├── AtomicAlgo         @'[ ... ]
│   ├── ElementAlgo        @[ ... ]
│   ├── Context            ^{ ... }       -- named context/envelope def
│   ├── Leaf
│   │   ├── Note           c4  d#'8.
│   │   ├── Chord          <c e g>4
│   │   ├── Rest           r4  r
│   │   └── Drum           x8  x\kick  x4\36
│   ├── Bar                |  ||  |||  ||||
│   └── Reference          :name
├── Instruction
│   ├── BangConst          !mf  !ff  !swing
│   ├── KeyAssignment      !key:C.major
│   ├── Assignment         !vol:80  !art:staccato  !Meter:7/8
│   ├── SlurStart          !(
│   └── SlurEnd            !)
└── Command
    ├── transpose          \transpose c d { ... }
    ├── times              \times 2/3 { ... }
    ├── tuplet             \tuplet 3/2 { ... }
    ├── repeat             \repeat volta 2 { ... }
    ├── tremolo            c4:32  or  \repeat tremolo 4 { ... }
    └── grace              \grace  \acciaccatura  \appoggiatura  ...
```

`FormSign`/`FormJump` (`\segno`/`\coda`/`\fine`/`\dacapo`/etc.) described
in older drafts of this doc have been **removed from the grammar
entirely** — there is no form-navigation support currently.

### Pitch

```
PitchLetter  a-g (relative), A-G (absolute)
Accidental   # b n ## bb  --  or the Dutch (nederlands) suffixes
             is/isis/es/eses (+ elided a/e forms s/ses), same semitone
             offsets -- see doc/LilypondToMuCheatSheet.txt
Octave       4/ (absolute)  or  ' '' , ,, (relative ticks)
```

Relative vs. absolute is decided purely by the **case of the first
letter** (`Character/isUpperCase`) — uppercase is always an absolute
pitch name; lowercase is always relative pitch resolution (nearest
fourth/fifth from whatever the previous pitch was, LilyPond
`\relative`-style), even for the very first note of a sequence with no
preceding pitch to chain from (it resolves against an implicit default
of `C4`). There's no position-based exception — a lone lowercase letter
never means "absolute."

### Duration

```
4       quarter note (1/4)
8.      dotted eighth (3/16)
16..    double-dotted sixteenth
\longa  4 whole notes
\breve  2 whole notes
```

If omitted, the previous duration is reused.

### Articulation

Two forms — shorthand or named:

```
c4-.     staccato (shorthand)
c4->     accent (shorthand)
c4\staccato   (named)
```

Shorthand symbols: `. > ^ _ ! + -`

Named: `staccato`, `staccatissimo`, `tenuto`, `marcato`, `portato`,
`accent`, `espressivo`

### Ornaments

Backslash-prefixed, attached to a note:

```
c4\trill   c4\mordent   c4\turn   c4\fermata
```

17 ornaments: `prall`, `prallup`, `pralldown`, `upprall`,
`downprall`, `prallprall`, `lineprall`, `prallmordent`, `mordent`,
`upmordent`, `downmordent`, `trill`, `turn`, `reverseturn`,
`shortfermata`, `fermata`, `longfermata`, `verylongfermata`

Expanded into replacement sub-leaves by `core.domain.ornaments` (moved
there from `output/`, since it's a domain-model transform, not MIDI
output) at resolve time — needs the active `Key` from context for
scale-relative ornaments.

### Dynamics and hairpins glued to a note

A dynamic mark or hairpin glued directly onto a note/chord takes effect
from that note's own onset, same as writing the equivalent standalone
`!f`/`!vol<` instruction just before it — chainable:

```
c4\f          dynamic glued to the note, same table as !f
c4\<          crescendo hairpin start glued to the note
c4\mf\<       sets volume to mf right at this note, then starts a real
              crescendo from that value (not just an unresolved ramp)
```

### Modifiers

Key-value pairs on a note:

```
c4\vol:80   c4\pan:left
```

### Tie

```
c4~ c4    tied notes
```

### Chord

Two or more pitches in angle brackets, shared duration:

```
<c e g>4     C major triad, quarter note
<d f# a>2.   D major triad, dotted half
```

### Rest & Drum

```
r4        quarter rest
r         rest (previous duration)
x8        eighth drum hit
x\kick    drum with name modifier
x4\36     drum with MIDI number
```

---

## 4. Brackets

| Bracket   | Rule          | Contents           | Notes                                    |
|-----------|---------------|---------------------|-------------------------------------------|
| `{ }`     | `Sequence`    | Element             | musical sequence                          |
| `<< >>`   | `Parallel`    | SequenceElement     | simultaneous parts, no bare notes (use chords for simultaneous pitches) |
| `( )`     | `Unit`        | Element             | grouped elements, no `:context` of its own |
| `'[ ]`    | `Data`        | DataItem            | data container                            |
| `@'[ ]`   | `AtomicAlgo`  | —                   | algorithm over data                       |
| `@[ ]`    | `ElementAlgo` | —                   | algorithm over elements                   |
| `^{ }`    | `Context`     | —                   | named context/envelope definition         |

This differs from earlier drafts of this doc (`[ ]` was `Data`, `( )` was
a plain `List`, `'( )` was `Quoted`) — the bracket scheme has changed more
than once; always check `musics.ebnf` when in doubt.

Sequences can carry an **Id** label:

```
{verse: c4 d4 e4 f4}
```

References recall a labelled part:

```
:verse
```

— either splicing in a container/iterator, or (if the id names a
`Context`) replaying its envelope points onto the current container's
context at the current beat offset.

---

## 5. Commands

### transpose

```
\transpose c d { c4 d4 e4 }
```

Shifts pitches by the interval between `from-pitch` and `to-pitch`.

### times / tuplet

```
\times 2/3 { c4 d4 e4 }     multiply durations by 2/3 (triplet)
\tuplet 3/2 { c4 d4 e4 }    divide durations by 3/2 (same result)
```

Both accept any ratio, not just simple triplets — a genuine quintuplet
(`\tuplet 5/4 { ... }`) or septuplet (`\tuplet 7/4 { ... }`) works exactly
the same way, and is unconstrained by whatever the prevailing `Meter` is
(a tuplet is a local, temporary duration rescaling, independent of meter/
indispensability, same as in standard notation).

### repeat

```
\repeat volta 2 { c4 d4 }
\repeat unfold 4 { c4 d4 }
\repeat volta 2 { c4 d4 } \alternative { { e4 } { f4 } }
```

Creates an `Iterator` with type `:REPEAT`.

### tremolo

Note-level (shorthand):

```
c4:32     32nd-note tremolo on a quarter note → 8 repetitions
<c e>4:16 16th-note tremolo on a chord
```

Measured (sequence):

```
\repeat tremolo 4 { c8 d8 }
```

### grace notes

```
\grace c8           plain grace
\acciaccatura c16   short, slashed grace
\appoggiatura c8    long grace (half main note)
\slashedGrace c16   synonym for acciaccatura
\afterGrace c4 d16  grace after the main note
```

Grace notes are tagged with duration 0 during tree-walking; expansion
(`core.domain.ornaments`) assigns short playable durations (1/32 or 1/16).

---

## 6. Instructions

Compact, no internal whitespace, prefixed with `!`:

```
!mf              dynamic (BangConst → context lookup)
!ff              dynamic
!vol:80          assignment (key:value)
!art:staccato    assignment
!key:C.major     key assignment
!Tempo:120       tempo, bare BPM, quarter note implied (aliases !tempo:/!T:)
!Tempo:4=120     tempo, LilyPond-style note-value=BPM (quarter=120, same
                 as the bare form above); !tempo:3/8=120 for a ratio
                 note-value (dotted-quarter=120)
!Meter:7/8       divisible meter (bare ratio)
!Meter:"7/8(2+2+3)"   additive meter (quoted, explicit grouping;
                      groups must sum to the numerator) -- alias !M:
!allegro !andante !largo !presto ...   named tempo marking (BangConst,
                      resolves to a standard BPM under :Tempo -- see
                      music-data.clj's tempo-markings for the full list)
!marciaModerato !andanteModerato !allegroModerato !allegroVivace
                      compound tempo markings, camelCase (their
                      tempo-markings keys are kebab-case, which BangConst's
                      Name token can't spell)
!swing           swing on
!noswing         swing off
!left !center !right   panning
```

See CLAUDE.md's "Meter and indispensability" section for how `Meter`'s
grouping (explicit or defaulted) feeds Barlow indispensability, and its
Grammar section for how a `TempoMark` (`N=BPM`/`N/D=BPM`) is normalized to
quarter-note-equivalent BPM before it ever reaches playback, plus how named
tempo markings resolve as BangConsts.

### Bar lines

```
|  ||  |||  ||||
```

Purely a structural/print marker on disk (`Bar`, zero duration), but not
inert at playback: each one fires a `core.conductor` `:mark` signal
(`count` = the pipe-count 1-4) as an extra, author-placed cue layered on
top of the automatic section/bar signals the engine also fires — see
CLAUDE.md's "Conductor" section.

### Ramp syntax

A ramp attaches directly to the assignment name with no colon -- the
leading `<`/`>` is the separator, not `:`. The curve prefix (if any) comes
right *after* the direction, not before it:

```
!vol<          ramp up (linear, default), open-ended
!vol>          ramp down, open-ended
!vol<s         smooth ramp up, open-ended
!vol>i         ease-in ramp down, open-ended
!vol<o         ease-out ramp up, open-ended

!vol<16:ff     timed ramp up: 16 ticks to dynamic ff (linear, default)
!vol<16*4:ff   duration as a product (16*4)
!vol<16/1:ff   duration as a ratio (16/1)
!pan<4:1.0     timed ramp with a float target
!tempo>8:60    timed ramp down with an int target

!vol<s:16:ff   timed ramp with an explicit curve -- a ramp point is
               really a (curve, duration, target) triple; the plain
               timed form above just defaults curve to linear
```

Curve prefixes: `l` (linear), `s` (smooth), `i` (ease-in),
`o` (ease-out).  Direction: `<` (up), `>` (down).

### Slurs

```
{violin: c4 !( d4 e4 f4 !) g4}
```

---

## 7. Flat tree walker

`input.reader.flat-tree-walker/walk` converts the raw instaparse tree
into the flat repo -- **not** a tree of domain objects (that model, and
`input.reader.tree-walker`, are both gone; see CLAUDE.md). It threads a
build state (via `input.reader.flat-core-builder`) with:

- **stack** — container stack (`:ROOT` at bottom, nested containers above,
  pushed/popped as the walk descends/ascends)
- **last-pitch** — for relative pitch resolution
- **last-dur** — for duration inheritance
- **auto-ids** — counter per type for unique, type-prefixed ids
  (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`)

Each node tag dispatches to a handler:

- `Note` → `walk-note` → `Leaf`
- `Rest` → `walk-rest` → `Rest`
- `Drum` → `walk-drum` → `Drum`
- `Chord` → `walk-chord` → `Leaf` (multiple pitches)
- `BarLine` → `(d/bar n)` inline in `:children`
- `Sequence`/`Parallel`/`Unit`/etc. → push/pop a container of the
  matching `:type`, registered in the flat `repo` map by id on pop (see
  `flat-core-builder/pop-container`)
- `BangConst` / `Assignment` / `KeyAssignment` → `ctx-append` on the
  current container's context
- `transpose` → walk children with pitch offset
- `times` / `tuplet` → walk children with duration scaling
- `repeat` → `Iterator` `:REPEAT`
- `tremolo` → modifier or `Iterator` `:TREMOLO`
- `grace` → modifier `["grace" type]`, duration set to 0

The result is `{:tree repo-map :auto-ids ...}` — a flat `{id -> container}`
map reachable from `:ROOT`, not a nested tree of pointers.

---

## 8. Error reporting

On parse failure, `format-parse-error` produces terminal-friendly
output with:

- Source line and caret pointer
- Humanized list of expected tokens
- Line and column numbers

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

`try-parse` and `try-parse-string` wrap parsing and print errors
automatically, returning nil on failure.
