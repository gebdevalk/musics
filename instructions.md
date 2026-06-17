# Musics Project

**Repo**: `~/Development/clojure/musics` — Clojure music DSL → MIDI.

## Architecture

- **Pipeline**: text → `vars/extract-vars` → `vars/expand-vars` → `lex/tokenize` → `parse` → domain objects → `engine/walk` → MIDI
- **Data**: `music_data.clj` is central — `dynamics`, `tempo-markings`, `drum-name->midi`, `instruction-context`, `signatures`, `scales`
- **Context**: key-value envelopes with interpolation (`:lin-up`, `:lin-down`, `:smooth`, `:ease-in`, `:ease-out`). Set via `ctx-append`, sampled via `ctx-value`.
- **Parser**: `music_parser.clj` — recursive descent with stack. `:BANG_CONST` calls `data/instruction-context`. 
- **Lexer**: `lexer.clj` — hand-rolled `TOKEN_PATTERN` regex + `classify-token` map. **Plan: migrate to instaparse** (next session, fresh branch).

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
2. `:` syntax for instructions: `!art:80`, `!key:C.major`. `=` replaced.
3. Drums use `\` modifier: `x\kick`, `x4\36`. No inline names.
4. Variables: `name = value` defs, `\name` references (text-level expansion via `vars.clj`).
5. `!` constants sorted longest-first in TOKEN_PATTERN to prevent prefix matching.
6. PAR playback uses `future` for concurrency.
7. `instruction-context` in `music_data.clj` — parser data-free, built from `dynamics` + `tempo-markings`.

## Current state

- 125 tests, all passing
- `resources/input-text.txt` — comprehensive test coverage
- `!cresc`/`!dim` are temporary fixed-point mappings

## Deferred

| Item | Notes |
|------|-------|
| **Parser migration to instaparse** | Next session, fresh branch |
| Envelope instructions (`!cresc`, `!dim`, `!rit`, `!acc`) | Need engine beat tracking |
| Pedal CCs (`!pedOn` → CC 64) | Need engine CC message support |
| Navigation/repeats | Need engine jump logic |
| Drum name expansion | More aliases in `drum-name->midi` |
