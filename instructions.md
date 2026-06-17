# Musics Project

**Repo**: `~/Development/clojure/musics` — Clojure music DSL → MIDI.

## Architecture

- **Pipeline**: text → `vars/extract-vars` → `vars/expand-vars` → `lex/tokenize` → `parse` → domain objects → `engine/walk` → MIDI
- **Data**: `music_data.clj` is central — `dynamics`, `tempo-markings`, `drum-name->midi`, `instruction-context`, `signatures`, `scales`
- **Context**: key-value envelopes with interpolation (`:lin-up`, `:lin-down`, `:smooth`, `:ease-in`, `:ease-out`). Set via `ctx-append`, sampled via `ctx-value`. Keys are strings internally.
- **Parser**: `music_parser.clj` — recursive descent with stack, `:BANG_CONST` calls `data/instruction-context`.

## Bracket scheme

| Bracket | Semantics | Token types |
|---------|-----------|-------------|
| `{...}` | Musical sequence | `:SEQ` / `:SEQ_CLOSE` |
| `<<...>>` | Parallel | `:PAR` / `:PAR_CLOSE` |
| `[...]` | Data container | `:DATA` / `:DATA_CLOSE` |
| `(...)` | Activity / operator | `:LIST` / `:LIST_CLOSE` |
| `'(...)` | Quoted / deferred | `:QUOTE` / `:QUOTE_CLOSE` |

## Key decisions

1. Composite IDs are strings. `"SEQ.1"`, `"verse"`. Bare-word naming.
2. `:` syntax for instructions: `!art:80`, `!key:C.major`. `=` replaced.
3. Drums use `\` modifier: `x\kick`, `x4\36`. No inline names.
4. `!` constants sorted longest-first in TOKEN_PATTERN.
5. PAR playback uses `future` for concurrency.
6. `instruction-context` in `music_data.clj` — parser data-free.

## Current state

- 125 tests, all passing
- `resources/input-text.txt` — comprehensive test coverage
- `!cresc`/`!dim` are temporary fixed-point mappings (TODO: envelope instructions)

## Deferred

| Item | Notes |
|------|-------|
| Envelope instructions | Need engine beat tracking. Envelope system works. |
| Pedal CCs | Need engine CC message support |
| Navigation/repeats | Need engine jump logic |
| $name → \name variables | `vars.clj` built and wired |
| Drum name expansion | More aliases in `drum-name->midi` |
