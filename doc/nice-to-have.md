# Nice-to-have: native LilyPond reading

Analysis, not a plan — no code has been written for this. Written in
response to "what would it take to let the parser read LilyPond files
directly, entering a LilyPond mode when the file opens with the
obligatory `\version`, with LilyPond treated as a subset of our own
grammar?" Deliberately deferred to its own future branch, not scoped
for near-term work.

Trimmed to the durable strategic points — a fuller draft (specific
grammar-rule table, exact line-number references into `flat_tree_
walker.clj`/`lilypond_import.clj`) existed here before but had already
gone stale (e.g. it claimed `\times`/`\tuplet`/`\transpose` bracket
their body with `{ }` — they use `Scope`, `( )`, now) after the bracket
scheme changed underneath it. Re-derive those specifics fresh against
`musics.ebnf`/`flat_tree_walker.clj` when this is actually picked up,
rather than trusting anything left over here.

## Current state

`src/input/lilypond_import.clj` isn't part of the grammar pipeline at
all — a standalone text-to-text transpiler with its own hand-rolled
tokenizer that runs *before* `musics.ebnf` ever sees anything, converting
`.ly` text to `.mus` text first. That's the same "text-shape-changing
transform before parsing" pattern Wave 3 deliberately eliminated for
comments/variables (see the project `CLAUDE.md`'s "Comments and
variables" section) — same failure mode: a LilyPond parse error's line/
column means nothing relative to the real `.ly` file, and unrecognized
constructs can be silently dropped with no way to report a proper error.

A real "LilyPond mode" inside `musics.ebnf` itself, triggered
structurally by `\version`, is the same move Wave 3 made for comments
and variables, applied one level up.

## The key mechanism: mode-switching via start rule, not shared grammar

`insta/parser` compiles every rule once but lets you pick the **start
rule per call**: `(parser text :start :LilyProgram)`. The cheapest,
lowest-risk design: one `.ebnf` file, one compiled parser, a new start
rule (`LilyProgram`, gated behind a required `\version`) that
`grammar_parser.clj` switches to based on a cheap peek at the raw text.

This is much safer than adding LilyPond's top-level constructs as more
alternatives in the *shared* rule set (`TopElement`/`Element`). This
codebase has already been burned by exactly that shape of mistake once
— the `VarDef`-reachability lesson (`musics.ebnf`/`CLAUDE.md`): making
`VarDef` reachable everywhere `Element` was caused instaparse's
furthest-failure tracking to chase a dead-end "maybe this is a
variable" branch past real typos, producing useless errors — confirmed
directly, not assumed. Piling LilyPond-only constructs into the
*native* grammar's own reachable set risks the same regression for
every existing `.mus` file, for zero benefit to them. A separate start
rule keeps native parsing's error quality and performance untouched,
while still letting both dialects **share** every leaf-level rule
(pitch/duration/chord/articulation/ornament/dynamic/tremolo, and the
transient commands) — those were already designed to mirror LilyPond
one-for-one, which is the actual "subset" relationship. `doc/
LilypondToMuCheatSheet.txt` is the live source of truth for exactly
how much already overlaps — check that fresh rather than trusting a
specific list here.

## Hard/open problems worth flagging before scoping this

1. **Noise commands** (`\override`/`\set`/`\tweak`/etc.) as a *formal*
   grammar rule, not `lilypond_import.clj`'s current token-stream
   heuristic (drop until the next command/`{`/`=`/string) — needs each
   command's argument shape actually specified, or a generic
   balanced-`{}`-and-stop-at-backslash rule. Likely the part most in
   need of iteration against real files.
2. **Scheme is unbounded** (`lilypond_import.clj`'s own docstring
   already says so). A grammar rule can only balance parens and
   discard the content, never really parse it — same ceiling the
   transpiler already has, just enforced as a real rule instead of a
   scanner.
3. **`\\`-separated polyphony** (`<< a \\ b \\ c >>`) is a genuine
   *structural* mismatch, not just missing syntax: this grammar's own
   `Parallel` expects each child already in its own bracketed
   container, but LilyPond's flat `\\`-separated form has none — the
   walker would need to synthesize an implicit per-group container
   that doesn't correspond to any single grammar node, closer to the
   `VarDef` scratch-container trick than a plain container case.
4. **`\relative <pitch>` seeding** needs genuinely new walk-time state
   (a stashed anchor consumed by the next note), not reuse of
   `replay-context!`/the context-replay machinery everything else
   here can lean on. `\relative` with no start pitch, by contrast, is
   a no-op wrapper — this format's own default pitch resolution
   already *is* LilyPond's relative rule.
5. **Ambiguity risk stays real** even with a separate start rule,
   *within* `LilyProgram` itself (e.g. a bare `name = ...` vs.
   `\include`/`\version`/`\header` all being valid top-level elements)
   — needs the same "distinguishable by leading token" discipline
   already applied to `Reference`/`Instruction`/`Command`, verified
   directly against instaparse the way `VarName`'s reserved-word
   exclusion was, not assumed.

## Recommendation

Treat this as three separable stages, not one project:

1. **Mode detection + skeleton** (`\version` gate, `LilyProgram` start
   rule, header/score/layout/midi/paper drops, bare `{ }`/`<< >>`
   passthrough) — small, low-risk, gets real `.ly` files with no
   polyphony/noise-commands/relative-pitch working end to end via the
   grammar instead of the transpiler.
2. **The already-compatible commands** (`\tempo`/`\time`/`\key`/`\new`/
   `\relative` without a seed pitch/`\repeat`/`\transpose`/`\times`/
   `\tuplet`) — medium, mostly plumbing since the leaf grammar and most
   commands are untouched.
3. **The genuinely hard bits** (`\relative <pitch>` seeding, `\\`
   polyphony splitting, noise-command swallowing, Scheme balancing) —
   where the real design/iteration work happens.

`lilypond_import.clj` doesn't need to be deleted to start this — it can
stay as the fallback for whatever stage 3 doesn't cover yet, and get
retired once the grammar-native path's coverage genuinely supersedes it
(its own docstring's "known simplifications" list is a ready-made
checklist for what parity would mean).
