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
  ├─ tree-walker/walk     raw tree → domain objects (Leaf, Rest, Composite …)
  │
  └─ Score                root Composite, ready for engine / MIDI
```

Entry points (all in `input.reader.grammar-parser`):

```clojure
(parse-domain text)         ;; full pipeline → {:score Score}
(parse-domain-string text)  ;; without variable pre-processing
(try-parse text)            ;; parse only, formatted error on failure
(parse text)                ;; raw instaparse tree
```

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
- `|` — barline (treated as whitespace)

---

## 3. Grammar

The grammar lives in `src/input/reader/musics.ebnf` (instaparse EBNF
format, explicit whitespace, no auto-ws).

### Element hierarchy

```
Element
├── Part
│   ├── Composite
│   │   ├── Sequence      { ... }
│   │   ├── Parallel      << ... >>
│   │   ├── Data          [ ... ]
│   │   ├── List          ( ... )
│   │   └── Quoted        '( ... )
│   ├── Leaf
│   │   ├── Note          c4  d#'8.  p,16
│   │   ├── Chord         <c e g>4
│   │   ├── Rest          r4  r
│   │   └── Drum          x8  x\kick  x4\36
│   ├── Reference         :name
│   ├── FormSign          \segno  \coda
│   └── FormJump          \fine  \dacapo  \dalsegno  ...
├── Instruction
│   ├── BangConst         !mf  !ff  !swing
│   ├── KeyAssignment     !key:C.major
│   ├── Assignment        !vol:80  !art:staccato
│   ├── SlurStart         !(
│   └── SlurEnd           !)
└── Command
    ├── transpose         \transpose c d { ... }
    ├── times             \times 2/3 { ... }
    ├── tuplet            \tuplet 3/2 { ... }
    ├── repeat            \repeat volta 2 { ... }
    ├── tremolo           c4:32  or  \repeat tremolo 4 { ... }
    └── grace             \grace  \acciaccatura  \appoggiatura  ...
```

### Pitch

```
PitchLetter  a-g (relative), A-G (absolute), p (context pitch)
Accidental   # b n ## bb
Octave       4/ (absolute)  or  ' '' , ,, (relative ticks)
```

Relative vs absolute: lowercase letters use relative octave
resolution (ticks shift from previous pitch); uppercase letters
use absolute pitch names.

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

## 4. Composites & brackets

| Bracket   | Type     | Contents             | Min elements |
|-----------|----------|----------------------|:------------:|
| `{ }`     | Sequence | Element              | 1            |
| `<< >>`   | Parallel | SequenceElement      | 2            |
| `[ ]`     | Data     | DataItem             | 0            |
| `( )`     | List     | DataItem             | 0            |
| `'( )`    | Quoted   | DataItem             | 0            |

Sequences can carry an **Id** label:

```
{verse: c4 d4 e4 f4}
```

References recall a labelled composite:

```
:verse
```

Parallel blocks require sequences or composites — no bare notes
(use chords for simultaneous pitches).

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

### repeat

```
\repeat volta 2 { c4 d4 }
\repeat unfold 4 { c4 d4 }
\repeat volta 2 { c4 d4 } \alternative { { e4 } { f4 } }
```

Creates an Iterator with type `:REPEAT`.

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

Grace notes are tagged with duration 0 during tree-walking; the
resolver expands them to short playable durations (1/32 or 1/16).

---

## 6. Instructions

Compact, no internal whitespace, prefixed with `!`:

```
!mf              dynamic (BangConst → context lookup)
!ff              dynamic
!vol:80          assignment (key:value)
!art:staccato    assignment
!key:C.major     key assignment
!tempo:120       tempo
!swing           swing on
!noswing         swing off
!left !center !right   panning
```

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

## 7. Form navigation

Position markers and jumps for repeat structures:

```
\segno   \coda          markers
\fine    \dacapo        jumps
\dalsegno  \tocoda      jumps
\dcalfine  \dcalcoda    compound jumps
\dsalfine  \dsalcoda    compound jumps
```

---

## 8. Tree walker

`input.reader.tree-walker/walk` converts the raw instaparse tree into
domain objects.  It maintains a mutable state with:

- **stack** — container stack (Score at bottom, nested Composites above)
- **last-pitch** — for relative pitch resolution
- **last-dur** — for duration inheritance
- **auto-ids** — counter per type for unique IDs

Each node tag dispatches to a handler:

- `Note` → `walk-note` → Leaf
- `Rest` → `walk-rest` → Rest
- `Drum` → `walk-drum` → Drum
- `Chord` → `walk-chord` → Leaf (multiple pitches)
- `Sequence` → push/pop Composite `:SEQ`
- `Parallel` → push/pop Composite `:PAR`
- `BangConst` / `Assignment` / `KeyAssignment` → `ctx-append` on current context
- `transpose` → walk children with pitch offset
- `times` / `tuplet` → walk children with duration scaling
- `repeat` → Iterator `:REPEAT`
- `tremolo` → modifier or Iterator `:TREMOLO`
- `grace` → modifier `["grace" type]`, duration set to 0

The result is a Score (Composite `:SCORE`) containing the full
part tree with contexts attached.

---

## 9. Error reporting

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
