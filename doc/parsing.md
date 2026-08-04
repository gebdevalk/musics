# Parsing Pipeline & Syntax

The musics DSL is parsed in stages, each implemented in its own module.

```
text
  │
  ├─ instaparse           EBNF grammar → raw tree (nested vectors)
  │    (comments and variables are both real grammar rules -- Comment,
  │    VarDef, VarRef -- not a text pre-processing step; instaparse
  │    always parses exactly what was written, so a later parse error's
  │    line/column always matches the original text. See "Variables" and
  │    "Comments" below.)
  │
  ├─ flat-tree-walker/walk   raw tree → {:tree repo-map :auto-ids ... :var-map ...}
  │    (a flat {id -> container} map, not a tree of pointers -- see
  │    CLAUDE.md's "Domain model" section)
  │
  └─ core.repo/stage! + commit-staged!   new/changed ids land in the
       versioned store as one atomic tx (see CLAUDE.md's "Session, the
       versioned repo, and playback")
```

Entry points (all in `input.grammar-parser`):

```clojure
(parse-domain-string text)  ;; full pipeline → {:tree repo-map :auto-ids ...}
(try-parse text)            ;; parse only, formatted error on failure
```

In practice, use `musics.clj`'s `(parse text)` instead of calling this
namespace directly — it walks against the session's current committed
repo and *stages* the result (see CLAUDE.md), which these lower-level
entry points don't do on their own.

---

## 1. Variables

Real grammar constructs (`VarDef`/`VarRef` in `musics.ebnf`), resolved by
`flat-tree-walker` in the same top-to-bottom walk as everything else —
not a text-level pre-processing step. The value is always a `Scope`
(parenthesized, not braced -- a variable never becomes an addressable
container the way a real `Sequence` does, so it gets a different
bracket on purpose):

```
motif = (c4 d4 e4 f4)
```

A definition is only valid directly at the top level of the file --
`VarDef` is reachable through `Program`'s own element list only, never
through `Element`/`ParElement`, so it can't appear nested inside a
`{ }`/`<< >>`/`[ ]` body (same restriction LilyPond itself has). This
also keeps error messages sane: before this restriction, a plain typo
inside a Sequence (`{verse: cc4 d4}`) could send instaparse chasing a
dead-end "maybe this is a variable definition" interpretation past the
real mistake, reporting a useless "expected =" nowhere near the actual
problem -- confirmed directly, not assumed. Referencing one (`\name`)
has no such restriction and works anywhere a `Part` can.

Referenced with backslash:

```
{piano: \motif g4 a4}
```

`\motif`'s children splice in flat — direct siblings, not a nested
container — same shape a `\times`/`\tuplet` body already gets absorbed
into its parent. An instruction written inside the definition (`!f`, or
a note-glued `\f`) reaches the reference site and sticks forward past it,
for the same reason: `walk-var-def` stashes the value's built children
*and* context, and `walk-var-ref` replays that context onto the
reference site via `flat-core-builder/replay-context!` (the same
mechanism a `:CONTEXT` reference and a transient command's own context
already use).

A variable must be **defined before it's referenced** (same rule
LilyPond itself uses) — not a style convention, a structural consequence
of there being one sequential walk: nothing is stashed yet for anything
not yet walked. Referencing an undefined (or not-yet-defined) name is a
walk-time error, reported with the reference's own line/column (same as
a grammar-level parse failure would show). A later definition of the
same name overwrites the earlier one — since the walk is sequential,
this is naturally position-sensitive: a reference *between* two
definitions of the same name sees whichever was current at that point,
not always the last one.

Variable names allow letters, digits, and underscores (`[a-zA-Z][a-zA-Z0-9_]*`,
same as `Name` elsewhere), except the reserved command/ornament words
(`transpose`, `times`, `tuplet`, `repeat`, `alternative`, `grace` and its
four synonyms, all 17 ornament names) — excluded so a bare `\trill`
always means the ornament, never a same-named variable; this exclusion
applies to `VarDef`'s own name too, so defining a variable named `trill`
is a parse error immediately rather than a silently unreachable
definition.

---

## 2. Comments

Two forms, LilyPond-style only:

- `%` — line comment (to end of line)
- `%{ ... %}` — block comment (non-nested -- matches up to the first `%}`)

Both are a real, tagged `Comment` grammar rule, reachable everywhere `ws`
already is (via `ws`'s own definition, not by rewriting every place `ws`
is referenced) — nothing is stripped from the text before instaparse
parses it. `flat-tree-walker` discards `Comment` nodes outright, the same
way it already discards bare `ws`-artifact strings.

`|`/`||`/`|||`/`||||` (`BarLine`) are **not** treated as whitespace —
they're a real grammar rule now, walked into a `Bar` record and, at
playback, firing a `core.conductor` `:mark` signal (see section 6 and
CLAUDE.md's "Conductor" section).

---

## 3. Grammar

The grammar lives in `src/input/musics.ebnf` (instaparse
EBNF format, explicit whitespace, no auto-ws) — always the source of
truth over this doc when they disagree.

### Element hierarchy

```
Element
├── Part
│   ├── Sequence           { ... }
│   ├── Parallel           << ... >>
│   ├── Unit               [ ... ]        -- grouped, no context of its own
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
    ├── transpose          \transpose c d ( ... )   -- Scope, not Sequence
    ├── times              \times 2/3 ( ... )       -- (never a container
    ├── tuplet             \tuplet 3/2 ( ... )       -- of its own -- see
    ├── repeat             \repeat volta 2 { ... }   -- below)
    ├── tremolo            c4:32  or  \repeat tremolo 4 { ... }
    └── grace              \grace  \acciaccatura  \appoggiatura  ...
```

`transpose`/`times`/`tuplet`'s body is `Scope` (`( )`), not `Sequence`
(`{ }`) — a deliberately different bracket, since none of the three ever
register their body as an addressable container; `flat-core-builder/
pop-container` splices it straight into the parent instead. `repeat`'s
own body and `\alternative`/measured tremolo's body stay `{ }` on
purpose — those genuinely persist as real, retained containers (an
`Iterator`'s `:source`/`:alternative`, replayed on each iteration), not a
one-shot splice.

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

A bare pitch letter with no accidental symbol resolves against the
active key's own implied accidental by default (`!key:D.major c f` →
C#, F#) — an explicit accidental always overrides it. Set
`!accidentals:explicit` to switch to literal, LilyPond-style resolution
(bare letter always natural, key ignored); C major implies nothing
either way, so a piece with no `!key:` is unaffected. See CLAUDE.md's
"Grammar" pitch paragraph for the full design.

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
| `[ ]`     | `Unit`        | Element             | grouped elements, no `:context` of its own -- a real, addressable container |
| `( )`     | `Scope`       | Element             | `\times`/`\tuplet`/`\transpose`'s body, a `VarDef`'s value -- never a container of its own, always spliced/stashed |
| `'[ ]`    | `Data`        | DataItem            | data container                            |
| `@'[ ]`   | `AtomicAlgo`  | —                   | algorithm over data                       |
| `@[ ]`    | `ElementAlgo` | —                   | algorithm over elements                   |
| `^{ }`    | `Context`     | —                   | named context/envelope definition         |

`Unit` and `Scope` used to share one bracket (`( )`) — split apart since
they mean genuinely different things: `Unit` registers as a real
container (an id, a place in `:children`), `Scope` never does (its
content is always consumed into something else). `\repeat`'s own body
and `\alternative`/measured tremolo's body stay `{ }` (`Sequence`), not
`Scope` — those really do persist as retained containers (an `Iterator`'s
`:source`/`:alternative`).

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
\times 2/3 ( c4 d4 e4 )     multiply durations by 2/3 (triplet)
\tuplet 3/2 ( c4 d4 e4 )    divide durations by 3/2 (same result)
```

Both accept any ratio, not just simple triplets — a genuine quintuplet
(`\tuplet 5/4 ( ... )`) or septuplet (`\tuplet 7/4 ( ... )`) works exactly
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
  (`:s1`/`:p1`/`:u1`/`:c1`/`:d1`/`:a1`/`:e1`). Assignment is lazy
  (`flat-core-builder/ensure-id`, called at pop time): a container only
  spends a counter slot if it reaches the end of the walk still unnamed
  -- an explicitly-named `{verse: ...}` never consumes one, and a
  transient container (`\times`/`\tuplet`/...), spliced away and never
  registered under any id, never consumes one either.
- **var-map** — `{name -> {:children :context}}`, populated by `VarDef`
  and read by `VarRef` (see "Variables" above); threaded through
  `musics.clj`'s `session` the same way `:auto-ids` is, so a variable
  defined in one `(parse ...)` call is still usable in a later one.

Each node tag dispatches to a handler:

- `Note` → `walk-note` → `Leaf`
- `Rest` → `walk-rest` → `Rest`
- `Drum` → `walk-drum` → `Drum`
- `Chord` → `walk-chord` → `Leaf` (multiple pitches)
- `BarLine` → `(d/bar n)` inline in `:children`
- `Comment` → discarded
- `Sequence`/`Parallel`/`Unit`/etc. → push/pop a container of the
  matching `:type`, registered in the flat `repo` map by id on pop (see
  `flat-core-builder/pop-container`)
- `BangConst` / `Assignment` / `KeyAssignment` → `ctx-append` on the
  current container's context
- `VarDef` → walk the value into a scratch container, stash its
  children + context in `:var-map`, register nothing
- `VarRef` → splice the stashed children in flat, replay the stashed
  context onto the reference site
- `transpose` → walk children with pitch offset
- `times` / `tuplet` → walk children with duration scaling
- `repeat` → `Iterator` `:REPEAT`
- `tremolo` → modifier or `Iterator` `:TREMOLO`
- `grace` → modifier `["grace" type]`, duration set to 0

The result is `{:tree repo-map :auto-ids ... :var-map ...}` — a flat
`{id -> container}` map reachable from `:ROOT`, not a nested tree of
pointers.

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
