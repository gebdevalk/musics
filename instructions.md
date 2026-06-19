# Musics Project

**Repo**: `~/Development/clojure/musics` — Clojure music DSL → MIDI.

## Architecture

- **Pipeline**: text → `vars/extract-vars` → `vars/expand-vars` → strip-comments → `instaparse` (EBNF grammar) → `tree-walker/walk` → domain objects → `engine/walk` → MIDI
- **Grammar**: `src/input/reader/musics.ebnf` — instaparse EBNF, explicit whitespace, no auto-ws
- **Tree-walker**: `src/input/reader/tree_walker.clj` — walks raw instaparse tree, builds domain objects (Leaf, Rest, Drum, Composite)
- **Entry points**: `grammar_parser.clj` provides `parse` (raw tree), `parse-domain` (domain objects), `try-parse` (formatted error on failure), `failure-info` (error location)
- **Data**: `music_data.clj` is central — `dynamics`, `tempo-markings`, `drum-name->midi`, `instruction-context`, `signatures`, `scales`
- **Context**: key-value envelopes with interpolation (`:lin-up`, `:lin-down`, `:smooth`, `:ease-in`, `:ease-out`). Hierarchical lookup (child → parent chain). Set via `ctx-append`, sampled via `ctx-value`.
- **Error reporting**: `format-parse-error` builds terminal-friendly output with source line, caret pointer, and humanized expected-token list. `try-parse` / `try-parse-string` wrap parsing and print errors.

## Two-tier element system

- **Element** — musical content only: `MusicElement | Command`. No bare data at the top level.
- **MusicElement** — `Part | Instruction`
- **Part** — `Composite | Chord | Note | Rest | Drum | Bar | Reference | FormSign | FormJump`
- **DataItem** — `DataElement | Keyword`. Used inside data containers only.
- **DataElement** — `Ratio | Float | Int | StringLit | Name`
- **SequenceElement** — `Composite | Reference | FormSign | FormJump | Instruction`. Used by Parallel (no bare leaves).

Bare numbers, strings, and keywords cannot appear in musical containers (`{...}`, `<<...>>`, top-level). They belong in data containers (`[...]`, `(...)`, `'(...)`).

## Id and Reference system

- **Id** — `name:` (trailing colon). Labels a composite: `{violin: c4 d4 e4}`
- **Reference** — `:name` (leading colon). Looks up a registered part: `{piano: c4 :violin d4}`
- Both are unambiguous against notes — `:` never appears in pitch/duration/articulation syntax
- Inside data containers, `:name` is a Keyword (data token); inside music contexts, it is a Reference

## Bracket scheme

- `{...}` — Musical sequence (Sequence). MusicElement. Optional `Id` label. Min 1 element.
- `<<...>>` — Parallel. SequenceElement only (no bare notes). Optional `Id` label. Min 2 elements.
- `[...]` — Data container (Data). DataItem. Min 0.
- `(...)` — List. DataItem. Min 0.
- `'(...)` — Quoted / deferred (Quoted). DataItem. Min 0.

## Commands (backslash-prefixed)

All command keywords use regex patterns (`#'\\command'`) to match a literal backslash. Variable-suffix rules use `<#'\\'>`.

- **relative**: `\relative [pitch] {sequence}`
- **transpose**: `\transpose pitch pitch {sequence}`
- **transposition**: `\transposition pitch`
- **times**: `\times ratio {sequence}`
- **tuplet**: `\tuplet ratio {sequence}`
- **tuplet-span**: `\tspan int`
- **repeat**: `\repeat type int element [volta]`
- **tremolo**: `note:int`, `chord:int`, or `\repeat tremolo int {sequence}`
- **grace**: `\grace`, `\acciaccatura`, `\appoggiatura`, `\slashedGrace`, `\afterGrace`
- **volta**: `\alternative {sequence}`

## Form navigation

- **FormSign**: `\segno`, `\coda` — position markers
- **FormJump**: `\fine`, `\dacapo`, `\dalsegno`, `\tocoda`, `\dcalfine`, `\dcalcoda`, `\dsalfine`, `\dsalcoda`
- Added to `<Part>`, appear inline with notes
- Implementation requires a two-phase approach: parse → flatten → unroll (state machine)

## Slurs

- `!(` — slur start
- `!)` — slur end
- Added to `<Instruction>`, e.g. `{violin: c4 !( d4 e4 f4 !) g4}`

## Comments

- `%` — line comment (to end of line)
- `%{ ... %}` — block comment (non-nested)
- Handled in the `ws` rule: `#'(\s|%\{[\s\S]*?%\}|%[^\n]*)+'`
- Old comment systems (`;` line, `(comment ...)` block) still handled by `strip-comments` pre-processing

## Grammar design

- **Backslash handling**: fixed commands inline as `#'\\command'` regex. Variable-suffix rules (Modifier, Ornament, DrumMod, Articulation) use `<#'\\'>`.
- **Accidental**: `##|bb|[#bn]` (5 valid musical combos only)
- **OctaveTicks**: `'+|,+` (at least one, no mixing)
- **ChordPitches**: 2+ pitches required
- **DurationSpecial**: `\longa`, `\breve` (backslash prefix, regex)
- **Articulation**: `-` + shorthand OR `\` + enumerated ArticulationName (7 names)
- **Ornament**: `\` + enumerated OrnamentName (17 names)
- **Hidden rules**: `<Element>`, `<MusicElement>`, `<Part>`, `<DataElement>`, `<DataItem>`, `<Composite>`, `<SequenceElement>`, `<Command>`, `<Instruction>`, `<Value>`, `<Octave>`, `<Duration>`, `<NoteSuffix>`, `<ModValue>`, `<ChordPitches>`, `<repeat-type>`, `<bar-type>`, `<CurvePrefix>`, `<Direction>`, `<ws>`

## Pitch system

- **a-g / A-G**: standard diatonic pitch names. Uppercase = absolute, lowercase = relative.
- **p**: context-dependent pitch — resolved at runtime from the active pitch source.
- **Accidentals**: `#`, `b`, `n`, `##`, `bb` only.
- **Octave**: absolute `4/` or relative ticks `'` (up) / `,` (down). No mixing.

## Articulation and ornament names

**ArticulationName** (7): `staccato`, `staccatissimo`, `tenuto`, `marcato`, `portato`, `accent`, `espressivo`

**OrnamentName** (17): `prall`, `prallup`, `pralldown`, `upprall`, `downprall`, `prallprall`, `lineprall`, `prallmordent`, `mordent`, `upmordent`, `downmordent`, `trill`, `turn`, `reverseturn`, `shortfermata`, `fermata`, `longfermata`, `verylongfermata`

**ArticulationShorthand** (7 symbols): `. > ^ _ ! + -`

## Key decisions

1. Composite IDs use trailing colon: `{verse: c4 d4}`. References use leading colon: `:verse`.
2. `:` syntax for assignments and modifiers: `!art:80`, `!key:C.major`, `\vol:80`.
3. Instructions are compact (no internal whitespace): `!mf`, `!art:80`.
4. Drums use `\` modifier: `x\kick`, `x4\36`. No inline names.
5. Variables: `name = value` defs, `\name` references (text-level expansion via `vars.clj`, pre-parser).
6. Ramp syntax: `!vol:<`, `!vol:s>` — curve prefix (l/s/i/o) + direction (< >).
7. Elements must be whitespace-separated. Grammar uses explicit `ws` rules (no auto-whitespace).
8. Ratio type `3/4` available in DataElement and Value. Listed before Int in alternatives.
9. Parallel blocks require sequences/composites — no bare notes (use chords for simultaneous pitches).
10. Old parser (`parser/lexer.clj`, `parser/music_parser.clj`) coexists — all old tests still pass.

## Current state

- Grammar tests: 6 tests, 51 assertions, all passing
- Error formatting: `format-parse-error`, `try-parse`, `try-parse-string` written in `grammar_parser2.clj`
- `grammar_parser2.clj` at project root — ready to copy over `src/input/reader/grammar_parser.clj`

## Next phase

- Install `grammar_parser2.clj` as the grammar parser
- Tree walker updates: handle `Id`, `Reference`, `FormSign`, `FormJump`, `SlurStart`, `SlurEnd` node types
- Form mark unrolling: flatten → state machine for Da Capo / Dal Segno playback
- Reconcile comment systems (old `;` / `(comment ...)` vs grammar `%` / `%{ %}`)
- Port key tests from `music_parser_test.clj` to grammar parser
- Goal: both parsers produce identical domain objects → retire hand-rolled parser

## Open / deferred

- **Envelope instructions** (`!cresc`, `!dim`, `!rit`, `!acc`): expressible via ramp syntax; need engine beat tracking.
- **Pedal CCs** (`!pedOn` → CC 64): need engine CC message support.
- **Drum name expansion**: more aliases in `drum-name->midi`.

## Dependencies

- `[instaparse "1.4.12"]` — GLL parser
- `[org.clojure/clojure "1.12.0"]`
