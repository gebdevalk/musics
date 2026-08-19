# CLAUDE.md — src/input

Guidance specific to the grammar/parsing layer: `musics.ebnf`,
`grammar_parser.clj`, `reader/flat_tree_walker.clj`,
`reader/flat_core_builder.clj`. See the project root `CLAUDE.md` for
everything else (domain model, engine, conductor, etc.).

### Comments and variables — both grammar-native, not text pre-processing

Neither one is a separate step before instaparse runs anymore — both are
real grammar rules, resolved by `flat-tree-walker` as part of the same
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

- **Variables** (`name = ( ... )` / `\name`) are `VarDef`/`VarRef`
  grammar rules. `VarDef` is reachable only directly in `Program`'s own
  top-level element list (`TopElement`), never through `Element`/
  `ParElement` — so it can never appear nested inside a `Sequence`/
  `Parallel`/`Unit`/etc. body, same restriction LilyPond itself has (a
  variable is defined before the music, not inside it). `VarRef` is
  unrestricted — referencing one works everywhere a `Part` can, only
  *defining* one is restricted. This isn't just style: `VarDef` being
  reachable everywhere `Element` was meant a typo anywhere inside a
  `Sequence` (`{verse: cc4 d4}`) could make instaparse's furthest-failure
  tracking follow a dead-end "maybe this is a variable definition"
  attempt right past the real mistake, reporting a useless "expected =`"
  instead of pointing at the actual typo — confirmed directly (column 13
  instead of the real column 10, with `=` as the only reported
  expectation) before this restriction landed, not assumed.

  The value is always a `Sequence` (`{ }`) — parsed and grammar-checked
  at definition time regardless of whether it's ever referenced, not
  "whatever text is left on the line" the way the old pre-processor
  allowed, and not a dedicated `Scope`/`( )` rule either (an earlier
  design's choice, since reverted — see the project root CLAUDE.md's
  "Grammar" section for the full reasoning). `myVar = { c4 d4 }` is real
  LilyPond's own spelling for exactly this, not this DSL's invention —
  LilyPond has no separate scope/grouping delimiter distinct from an
  ordinary music expression's own `{ }`, so reusing `Sequence`'s rule
  as-is here (rather than a parallel rule on a different bracket) is
  what makes this grammar an actual superset of LilyPond's rather than
  a parallel dialect that merely looks similar. The registered-vs-
  spliced distinction a dedicated `Scope` rule used to signal at the
  grammar level is now purely a walk-time one: `walk-var-def` sees a
  `Sequence` node sitting in `VarDef`'s own value position and, on that
  basis alone, walks its children into a scratch container (for the
  same reason a transient command gets one — see below) and stashes
  `{:children :context}` under the name in the walk state's `:var-map`
  (threaded through `musics.clj`'s `session` the same way `:auto-ids`
  is, so a variable defined in one `(parse ...)` call is still usable in
  a later one) rather than registering it — the exact same `Sequence`
  node appearing as an ordinary `Element` elsewhere DOES get registered,
  by `walk-sequence`'s own default path; nothing about the node itself
  says which happens, only where it was found. `walk-var-ref` looks the
  name up and splices its children in flat — same shape a `\times`/
  `\tuplet` body already gets absorbed into its parent — and replays the
  stashed context onto the current container via `flat-core-builder/
  replay-context!` (the same mechanism `apply-context-ref` uses for a
  `:CONTEXT` reference, and the one a transient command's own context
  gets replayed with too — three callers of one function). A variable
  must be defined *before* it's referenced (same rule LilyPond itself
  uses) — not a style convention
  here, a structural consequence of there being one sequential walk and
  no separate first pass: nothing is in `:var-map` yet for anything not
  yet walked. `walk-var-ref` throws a clear `ex-info` if the name isn't
  there, including the reference's own line/column (`flat-tree-walker/
  node-position`, reusing the `:instaparse.gll/start-index` metadata
  `node-text` already relies on for a different purpose) — a walk-time
  error gets the same kind of position info a grammar-level parse
  failure already carries, not just a bare message. `musics.clj/parse`'s
  existing `catch` prints it and returns `nil`, same as any other
  walk/parse failure.

  `VarName` (the identifier rule for both the defining and the
  referencing position) excludes every reserved command/ornament word
  (`transpose`, `times`, `tuplet`, `repeat`, `alternative`, `grace` and
  its four synonyms, all 17 ornament names) via a regex negative
  lookahead. This is load-bearing, not defensive: instaparse's ambiguity
  resolution for a genuinely ambiguous grammar is **not** reliable
  declaration order — verified directly (a minimal grammar with a
  reserved-word rule listed before a generic fallback still resolved to
  the generic one, and vice versa when the order was flipped) — so a
  bare `\trill` has to be structurally incapable of also parsing as
  `VarRef`, not just conventionally discouraged from being used that
  way. Applied to `VarDef`'s own name too, not just `VarRef`'s, so
  defining a variable literally named e.g. `trill` is a parse error
  immediately, rather than a silently unreachable definition.

  Digits and underscores stay allowed in variable names (unlike
  LilyPond, which restricts identifiers to letters only) — deliberately:
  LilyPond's restriction exists because a bare identifier and a bare
  note token can occupy the same free-floating position in a music
  expression, and a trailing digit would otherwise misread as a
  duration. A variable name in this grammar never has that collision —
  it only ever appears right before `=` or right after `\`, neither of
  which a note could also occupy — so the character restriction wouldn't
  be buying anything here.
