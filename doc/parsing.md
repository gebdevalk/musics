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
not a text-level pre-processing step. The value is always a `Sequence`
(`{ }`, reused as-is — real LilyPond's own spelling, `myVar = { c4 d4
}`, since LilyPond has no separate scope/grouping delimiter distinct
from an ordinary music expression's own `{ }`; an earlier design gave
this its own dedicated `Scope`/`( )` rule specifically to keep a
variable's value from being registered as a real container the way a
plain `Sequence` is, but that's now purely a *walk-time* distinction —
`walk-var-def` sees a `Sequence` node sitting in `VarDef`'s own value
position and, on that basis alone, stashes its children rather than
registering them; the exact same `Sequence` node found elsewhere gets
registered as normal):

```
motif = { c4 d e f }
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
{piano: \motif g a}
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
four synonyms, all 17 ornament names, plus `time`/`tempo`/`key`) —
excluded so a bare `\trill` always means the ornament, never a same-named
variable (and `\key`/`\time`/`\tempo` always mean the free-standing
LilyPond-style commands below, never a variable reference); this
exclusion applies to `VarDef`'s own name too, so defining a variable
named `trill` is a parse error immediately rather than a silently
unreachable definition.

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
│   ├── Unit               '{ ... }       -- grouped, no context of its own
│   ├── Data               [ ... ]
│   ├── AtomicAlgo         @[ ... ]       -- wired to real execution
│   ├── ElementAlgo        @{ ... }       -- also wired to real execution
│   ├── Context            ^{ ... }       -- named context/envelope def
│   ├── Leaf
│   │   ├── Note           c4  d#'8.
│   │   ├── Chord          <c e g>4
│   │   ├── Rest           r4  r
│   │   └── Drum           x8  x\kick  x4\36
│   ├── Bar                |  ||  |||  ||||  (a RUN of these is legal
│   │                                        too, e.g. "c4 | | d4" --
│   │                                        see BarRun in section 4)
│   └── Reference          :name
├── Instruction
│   ├── BangConst          !mf  !ff  !swing
│   ├── KeyAssignment      !key:C.major
│   ├── Assignment         !vol:80  !art:staccato  !Meter:7/8
│   ├── Invalidate         !/mf              -- clears a context value
│   ├── Partial            \partial 8        -- pickup/upbeat
│   ├── Time               \time 7/8         -- alt. spelling of !Meter:
│   ├── Tempo              \tempo 4=120      -- alt. spelling of !tempo:
│   └── Key                \key d \major     -- alt. spelling of !key:
└── Command
    ├── transpose          \transpose c d { ... }   -- reuses Sequence's
    ├── times              \times 2/3 { ... }       -- own '{ }', never
    ├── tuplet             \tuplet 3/2 { ... }       -- registered as a
    ├── repeat             \repeat volta 2 { ... }   -- container of its
    ├── tremolo            c4:32  or  \repeat tremolo 4 { ... }  -- own
    └── grace              \grace  \acciaccatura  \appoggiatura  ...
```

(The old standalone `!(`/`!)` slur instructions have been removed
entirely — see "Slurs" under section 6 for the only spelling now.)

`transpose`/`times`/`tuplet`'s body reuses `Sequence`'s own `{ }` (real
LilyPond's own spelling, `\times 2/3 { c8 d8 e8 }`) but is never
registered as an addressable container regardless — `flat-core-builder/
pop-container` splices it straight into the parent instead, purely a
walk-time decision (the grammar itself makes no distinction). An earlier
design gave these their own dedicated `Scope`/`( )` bracket specifically
to signal that at the grammar level — removed since real LilyPond has no
such third delimiter at all, and `( )` in real LilyPond means only a
slur. `repeat`'s own body and `\alternative`/measured tremolo's body use
`{ }` for the same reason `VarDef`'s value does — they genuinely persist
as real, retained containers (an `Iterator`'s `:source`/`:alternative`,
replayed on each iteration), not a one-shot splice, so there was never
any ambiguity to resolve there in the first place.

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

`!language:english` switches accidental-suffix reading to English
(`s`/`ss`/`x`/`f`/`ff`) instead of the default Dutch/nederlands table —
symbolic accidentals (`#`/`b`/etc.) mean the same thing in every
language and need no switch. See CLAUDE.md's "Multi-measure rests,
pickups, and pitch languages" section.

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
c4~ c    tied notes
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
R1*4      multi-measure rest, explicit duration, LilyPond's own spelling
          -- *n multiplies the given note-value, same as a bare r's
          own duration
R         multi-measure rest with NO duration at all -- derives one
          bar's length from whatever Meter is currently active (a
          genuine extension beyond LilyPond, which has no equivalent)
x8        eighth drum hit
x\kick    drum with name modifier
x4\36     drum with MIDI number
```

---

## 4. Brackets

| Bracket   | Rule          | Contents           | Notes                                    |
|-----------|---------------|---------------------|-------------------------------------------|
| `{ }`     | `Sequence`    | Element             | musical sequence -- also reused as-is for `\times`/`\tuplet`/`\transpose`'s body and a `VarDef`'s value (the walker, not the grammar, decides whether a given `{ }` gets registered or spliced/stashed) |
| `<< >>`   | `Parallel`    | SequenceElement     | simultaneous parts, no bare notes (use chords for simultaneous pitches) |
| `'{ }`    | `Unit`        | Element             | grouped elements, no `:context` of its own -- a real, addressable container |
| `[ ]`     | `Data`        | DataItem            | data container                            |
| `@[ ]`    | `AtomicAlgo`  | —                   | algorithm over data, wired to real execution |
| `@{ }`    | `ElementAlgo` | —                   | algorithm over elements, also wired to real execution (`algo.common.split/split-leaf-voice` is the built-in example) |
| `^{ }`    | `Context`     | —                   | named context/envelope definition         |

`( )` means only one thing anywhere in this grammar now: a slur mark
glued directly onto a note/chord (`c4( d4 e4)`) — never a grouping/scope
delimiter. An earlier design gave `\times`/`\tuplet`/`\transpose`'s body
and a `VarDef`'s value their own dedicated `Scope` rule on `( )`,
specifically to keep them visually distinct from a real, registered
`Sequence` — removed since real LilyPond has no such third delimiter at
all (`myVar = { c4 d4 }`, `\times 2/3 { c8 d8 e8 }` are its own actual
spellings), so `Scope` was a needless departure from this grammar being
a superset of LilyPond's own. `Unit` keeps its own bracket (`'{ }`)
rather than reusing plain `{ }` the way those four now do, because `Unit`
genuinely IS a registered, addressable container (keeps an id, appears
in `:children`, just with no `:context` of its own) — collapsing it onto
plain `{ }` would make it indistinguishable from an ordinary `Sequence`
at the point of use, unlike the other four (none of which was ever meant
to be addressable in the first place).

This differs from earlier drafts of this doc (`[ ]` was `Data`, `( )` was
a plain `List`, `'( )` was `Quoted`, `Unit` was `[ ]`, `Scope` was
`( )`) — the bracket scheme has changed repeatedly; always check
`musics.ebnf` when in doubt.

Sequences can carry an **Id** label:

```
{verse: c4 d e f}
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
\transpose c d { c4 d e }
```

Shifts pitches by the interval between `from-pitch` and `to-pitch`.

### times / tuplet

```
\times 2/3 { c4 d e }     multiply durations by 2/3 (triplet)
\tuplet 3/2 { c4 d e }    divide durations by 3/2 (same result)
```

Both accept any ratio, not just simple triplets — a genuine quintuplet
(`\tuplet 5/4 ( ... )`) or septuplet (`\tuplet 7/4 ( ... )`) works exactly
the same way, and is unconstrained by whatever the prevailing `Meter` is
(a tuplet is a local, temporary duration rescaling, independent of meter/
indispensability, same as in standard notation).

### repeat

```
\repeat volta 2 { c4 d }
\repeat unfold 4 { c4 d }
\repeat volta 2 { c4 d } \alternative { { e } { f } }
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
c4 | | d4        a RUN of consecutive bar lines is legal too, not just
                  one -- each still surfaces as its own separate marker
```

Purely a structural/print marker on disk (`Bar`, zero duration), but not
inert at playback: each one fires a `core.conductor` `:mark` signal
(`count` = the pipe-count 1-4) as an extra, author-placed cue layered on
top of the automatic section/bar signals the engine also fires — see
CLAUDE.md's "Conductor" section.

### \partial / \time / \key / \tempo -- LilyPond's own free-standing spelling

Alternative, literal-LilyPond surface spellings alongside `!Meter:`/
`!key:`/`!tempo:` above -- both forms land on exactly the same context
value, neither replaces the other:

```
\partial 8              pickup/upbeat -- pure structural declaration
                         (affects bar-boundary accounting only, no
                         !-prefixed equivalent at all)
\time 7/8                same as !Meter:7/8
\tempo 4=120              same as !tempo:4=120
\tempo 120                bare BPM, quarter note implied, same as !tempo:120
\key d \major             same as !key:D.major -- pitch written
                          LOWERCASE, language-aware (Dutch by default),
                          mode its own backslash-prefixed word --
                          structurally different from !key:'s own
                          uppercase, dotted-suffix spelling
```

`\clef` is deliberately NOT implemented — pure notation, nothing this
DSL's audio-only engine can act on. See CLAUDE.md's "`\time`/`\tempo`/
`\key`" section for the full design, including exactly which mode words
`\key` accepts.

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
{violin: c4( d e f) g}
```

Glued directly onto the start/end note, LilyPond-style — the only
spelling now. An earlier, standalone `!(`/`!)` Instruction form has been
removed entirely (it was a second, non-LilyPond spelling for exactly
what the note-glued form already does — both just set/clear the
walker's own `:in-slur?` flag).

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
- `MultiRest` → `walk-multi-rest` → `Rest`, duration derived from `Meter`
  when none was written explicitly (`R`/`R*4`)
- `Drum` → `walk-drum` → `Drum`
- `Chord` → `walk-chord` → `Leaf` (multiple pitches)
- `BarLine` → `(d/bar n)` inline in `:children` (a run of several in a
  row is legal, see `BarRun` in `musics.ebnf`)
- `Comment` → discarded
- `Sequence`/`Parallel`/`Unit`/etc. → push/pop a container of the
  matching `:type`, registered in the flat `repo` map by id on pop (see
  `flat-core-builder/pop-container`)
- `BangConst` / `Assignment` / `KeyAssignment` / `Invalidate` →
  `ctx-append`/`ctx-invalidate` on the current container's context
- `Partial` / `Time` / `Tempo` / `Key` → `walk-partial`/
  `walk-time-command`/`walk-tempo-command`/`walk-key-command`, each a
  thin conversion onto the same `ctx-append` path `Assignment`/
  `KeyAssignment` already use (`Time`/`Tempo`/`Key` are just LilyPond's
  own free-standing spelling of `!Meter:`/`!tempo:`/`!key:`)
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
