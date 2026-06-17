# Musics Project

**Repo**: `~/Development/clojure/musics` — Clojure music DSL → MIDI.

## Architecture

- **Pipeline**: text → `vars/extract-vars` → `vars/expand-vars` → strip-comments → `instaparse` (EBNF grammar) → tree-walker → domain objects → `engine/walk` → MIDI
- **Grammar**: `src/input/reader/musics.ebnf` — 155-line instaparse EBNF, replaces hand-rolled lexer + parser
- **Data**: `music_data.clj` is central — `dynamics`, `tempo-markings`, `drum-name->midi`, `instruction-context`, `signatures`, `scales`
- **Context**: key-value envelopes with interpolation (`:lin-up`, `:lin-down`, `:smooth`, `:ease-in`, `:ease-out`). Set via `ctx-append`, sampled via `ctx-value`.
- **Deferred validation**: tree-walker validates `BangConst`, `Ornament`, `Articulation` word, and drum names against `music_data.clj` / `ornaments.clj`. Grammar uses open `Name` rules — no duplicated keyword lists. Position reporting via instaparse node metadata.

## Bracket scheme

| Bracket | Semantics | Token types |
|---------|-----------|-------------|
| `{...}` | Musical sequence | `:SEQ` / `:SEQ_CLOSE` |
| `<<...>>` | Parallel | `:PAR` / `:PAR_CLOSE` |
| `[...]` | Data container | `:DATA` / `:DATA_CLOSE` |
| `(...)` | Activity / operator call | `:LIST` / `:LIST_CLOSE` |
| `'(...)` | Quoted / deferred | `:QUOTE` / `:QUOTE_CLOSE` |

## Key decisions

1. Composite IDs are strings. `"SEQ.1"`, `"verse"`. Bare-word naming inside composites.
2. `:` syntax for assignments: `!art:80`, `!key:C.major`, `\vol:80`.
3. Drums use `\` modifier: `x\kick`, `x4\36`. No inline names.
4. Variables: `name = value` defs, `\name` references (text-level expansion via `vars.clj`).
5. Ramp syntax: `!key:[lsio]?[<>]` — curve prefix (l=linear, s=smooth, i=ease-in, o=ease-out) + direction. E.g. `!vol:s<`, `!tempo:>`. Validated by tree-walker.
6. Ornaments: `\prall`, `\trill`, etc. — bare name (no `:`). Validated by tree-walker against `ornament-map`.
7. Articulations: `-.` (shorthand) or `-staccato` (word). Shorthand parsed in grammar, word validated by tree-walker.
8. PAR playback uses `future` for concurrency.
9. `instruction-context` in `music_data.clj` — grammar data-free, built from `dynamics` + `tempo-markings`.
10. Old parser (`parser/lexer.clj`, `parser/music_parser.clj`) kept until instaparse tree-walker passes all tests.

## Dependencies

- `[instaparse "1.4.12"]` — PEG parser (new)
- `[org.clojure/clojure "1.12.0"]`

## Current state

- 125 tests, all passing (old parser)
- `resources/input-text.txt` — comprehensive test coverage
- `src/input/reader/musics.ebnf` — instaparse grammar, ready for tree-walker

## Deferred

| Item | Notes |
|------|-------|
| **Tree-walker** | Port parser dispatch to walk instaparse tree → domain objects |
| Envelope instructions (`!cresc`, `!dim`, `!rit`, `!acc`) | Now expressible via ramp syntax; need engine beat tracking |
| Pedal CCs (`!pedOn` → CC 64) | Need engine CC message support |
| Navigation/repeats | Need engine jump logic |
| Drum name expansion | More aliases in `drum-name->midi` |
