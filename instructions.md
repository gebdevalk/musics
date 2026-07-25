# instructions.md

**Superseded — see `CLAUDE.md` for the current architecture, grammar, and
state of this project.**

This file was a working snapshot from an early stage of the grammar's
development (planning notes for installing a second-draft grammar parser,
form-navigation types that have since been removed entirely, the pre-flat
domain model's `Composite`/`Chord`/`Note` types). Nearly everything it
described has since changed or been removed — the bracket scheme, the
tree-walker, the domain model, and the grammar's own command/element
vocabulary have all moved on multiple times since this was written.

Rather than duplicate `CLAUDE.md`'s content here (and then have two places
to keep in sync), this file is kept only as a pointer. If you're looking
for:

- **Architecture / pipeline** — `CLAUDE.md`'s "Architecture" section.
- **Grammar / bracket scheme** — `CLAUDE.md`'s "Grammar" section, or
  `src/input/reader/parser/musics.ebnf` directly (the actual source of
  truth — always check it when a doc and the grammar disagree).
- **Day-to-day syntax examples** (pitches, chords, dynamics, meter, etc.)
  — `doc/LilypondToMuCheatSheet.txt`.
- **Domain model details** (Point/Envelope/Context, container shapes,
  transforms) — `doc/domain.md`.
