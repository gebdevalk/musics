# Grammar notes

Guidance specific to the grammar/parsing layer:
`resources/musics/input/musics.ebnf` (the literals) and
`resources/musics/mu.ebnf` (the top level, `MuInput`, appended to it by
`musics.mu.grammar`), and under `src/musics/input/`, `reader/walker.clj`
and `reader/builder.clj`.
See the project root `CLAUDE.md` for
everything else (domain model, engine, conductor, etc.).

### Comments and variables — both grammar-native, not text pre-processing

Neither one is a separate step before instaparse runs anymore — both are
real grammar rules, resolved by `walker` as part of the same
walk as everything else. This replaced an earlier design (`vars.clj` +
`pre_parse.clj`, both gone from disk now) where comments were stripped and
variables extracted/expanded as text substitution *before* parsing. That
had a real, if narrow, cost: any transform that changes the text's shape
(a multi-line block comment collapsing lines, a variable's expansion
inserting or removing them) shifts everything after it, so a later parse
error's line/column stopped corresponding to anything in the file actually
written — confirmed concretely, not just suspected, before this was
replaced. Parsing the original text end to end removes the cause instead
of working around it: nothing is ever stripped or substituted first, so
positions can't drift.

- **Comments** (`%...`, `%{...%}`) are a real, tagged `Comment` rule
  (`musics.ebnf`), reachable everywhere `ws` is (via `ws`'s own
  definition, `(Blank | Comment)+` — not by rewriting every place `ws`
  is referenced). Hiding a *rule* only suppresses that rule's own tag,
  not a tagged sub-rule referenced inside it, so `Comment` still surfaces
  as `[:Comment "..."]` in the raw tree even though `ws` itself stays
  hidden — verified directly against instaparse, not assumed. The walker
  discards `Comment` nodes outright (`walk-element`'s `:Comment` case),
  same as it already discards bare `ws`-artifact strings. The old
  `;`/`(comment ...)` forms are gone entirely — nothing in this project's
  own docs or examples ever used them, and two unrelated comment syntaxes
  wasn't earning its keep. The line-comment alternative excludes a
  following `{` (`%(?!\{)[^\n]*`) so it can never compete with the block
  form for the same `%` — confirmed genuinely ambiguous without that
  exclusion (a block comment resolved to only its first line instead of
  being swallowed whole), not just theoretically risky.

- **Variables** are mu's: `'motif [c d e] :=` binds a value to a name,
  `\motif` (`VarRef`, inside a literal or bare) is that value. The text
  grammar has only the reference; binding is a word, so a file binds
  the same way the prompt does. `musics.mu.value/var-map` hands the
  bound music to the walker as `:var-map` (`{name {:children
  :context}}`, a line's children, any other value as itself), and
  `walk-var-ref` splices its children in flat -- the shape a tuplet's
  body gets absorbed into its parent -- and replays its context onto the
  current container via `builder/replay-context!` (the mechanism
  `apply-context-ref` uses for a `:CONTEXT` reference and a transient
  command's own context gets replayed with too). A name not bound when
  its literal is read is an error with the reference's line/column
  (`walker/node-position`). An input's literals are read up to each
  `:=`, just before that part of it runs (`musics.mu.eval/segments`),
  so a name can be bound and used in one input or file; the reading
  starts afresh after each `:=`.

  `VarName` (the identifier rule of `\name` and of `'name`, which tries
  it first) excludes every reserved command/ornament word
  (`transpose`, `reverse`, `repeat`, `volta`, `grace` and
  its four synonyms, all 17 ornament names) via a regex negative
  lookahead. This is load-bearing, not defensive: instaparse's ambiguity
  resolution for a genuinely ambiguous grammar is **not** reliable
  declaration order — verified directly (a minimal grammar with a
  reserved-word rule listed before a generic fallback still resolved to
  the generic one, and vice versa when the order was flipped) — so a
  bare `\trill` has to be structurally incapable of also parsing as
  `VarRef`, not just conventionally discouraged from being used that
  way.

  Digits and underscores stay allowed in variable names (unlike
  LilyPond, which restricts identifiers to letters only) — deliberately:
  LilyPond's restriction exists because a bare identifier and a bare
  note token can occupy the same free-floating position in a music
  expression, and a trailing digit would otherwise misread as a
  duration. A variable name in this grammar never has that collision —
  it only ever appears right after `\` or `'`, neither of which a note
  could also occupy — so the character restriction wouldn't be buying
  anything here.
