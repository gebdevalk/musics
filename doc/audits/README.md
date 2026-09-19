# Audit and design-exploration files

Working notes and self-audits, moved here from the repo root on
2026-09-17 (part of acting on `audit-simplicity.txt`'s own Finding 2).
These are *not* polished documentation — several are explicitly
"working document, updated as the exploration continues, not a final
design doc." Several are also cited by filename from source-code
comments and `doc/decisions.md`, which is why they were moved rather
than deleted outright. One line per file, in rough chronological order:

| File | Status | Notes |
|---|---|---|
| `review.txt` | **SETTLED, actively cited** | Architecture review across `core.repo`/`core.async-engine`/`core.wall`/`core.conductor`/`context.clj`/`flat_domain.clj`/`resolve.clj`. Cited by point number from `CLAUDE.md` and several `src/` comments (e.g. "review.txt point 1/11/15") — treat those citations as load-bearing; don't delete this file without checking for dangling references first. |
| `assist.txt` | **SETTLED, actively cited, tracked in git** | REPL command cheatsheet by pipeline phase (parse/stage/commit/configure/conductor/play). Cited by `core.adviser`'s `intents` ordering and `musics.core/advise`'s docstring. The only file here under version control before this move. |
| `algo.txt` | SUPERSEDED, still cited historically | Early full inventory of `algo/` (25 files at the time), written to answer "what's under here and what should get wired." Superseded as a *current* inventory by `doc/algorithms.md`'s maintained table and by `audit-simplicity.txt`'s Finding 1 — but still cited by GAP number from `doc/decisions.md` and `algo/dimensions.clj`, so kept rather than deleted. |
| `indispensability-adherence.txt` | MOSTLY SETTLED (implemented) | Survey of six ways to reshape Barlow-indispensability weights by an adherence factor. Three of the six (`tilt-probabilities`, `power-law-probabilities`, `density-grid`) shipped; cited twice from `doc/decisions.md` for naming/design rationale. The other three surveyed approaches were never built — check this file before assuming they were. |
| `algo-stages.txt` | SUPERSEDED by CLAUDE.md | Traced the factory→build!→voice pipeline "as actually implemented" on 2026-09-10 (itself already a rewrite of two earlier versions). The same pipeline is now the canonical, kept-current description in `CLAUDE.md`'s "Wall" section and `doc/algorithms.md`. Cited from `doc/decisions.md` and `core.wall`'s own ns docstring — kept for the stage-by-stage trace detail the prose sections don't spell out as mechanically. |
| `audit-summary.txt` | **SETTLED — start here for the 2026-09-10 audit round** | Synthesis of `audit-wall-mechanism.txt`/`audit-algo-tree.txt`/`audit-gui-manipulability.txt` (2026-09-10), which have since been deleted as redundant with this synthesis — see "Removed" below. Read this one, not the three it used to stand alongside. |
| `algo-matrix.txt` | SUPERSEDED, still cited | `algo.toolkit`'s comp-rewrite notes + a function-combinability matrix (2026-09-13), working notes toward what became `algoline`. Cited by `algo/dimensions.clj` and `algo/toolkit.clj`'s own docstrings for the category work behind specific design choices. |
| `algo-composition.txt` | **SETTLED — the canonical history of the algoline/toolkit/dimensions exploration** | Tree vs. algoline vs. algoline2 design comparison (2026-09-13+), the file project memory itself points to as "full history" for that work. Cited from `algo/algoline.clj`, `algo/toolkit.clj`, `doc/algorithms.md`. |
| `audit-simplicity.txt` | **CURRENT — most recent audit** | 2026-09-17 simplicity audit (this file's own move is one of its action items). Supersedes the specific claims re-checked from the 2026-09-10 round; see its own "WHAT WASN'T RE-CHECKED" section for what it deliberately left standing from that round. |

## Removed (2026-09-17), not moved

Three files were deleted outright rather than archived, since their
content is fully absorbed by `audit-summary.txt` above and nothing
elsewhere in the codebase cites them by filename (grep-confirmed before
deletion):

- `audit-wall-mechanism.txt` (2026-09-10) — `core.wall`/play-arg
  mini-language simplicity audit. Its one concrete, still-actionable
  finding (the `bld!`/`reg-factory!`/`unreg-factory!`/etc. alias
  pairs) was acted on the same day as this move — see git log.
- `audit-algo-tree.txt` (2026-09-10) — `algo/` tree organization audit.
  Superseded by `doc/algorithms.md`'s maintained index and
  `audit-simplicity.txt`'s Finding 1.
- `audit-gui-manipulability.txt` (2026-09-10) — GUI-reach-into-algo/wall
  audit. Not re-verified since; its findings are only preserved in
  `audit-summary.txt`'s synthesis. If GUI/algo integration work resumes,
  treat its findings as unconfirmed for the current codebase rather than
  reconstructing this file from git history.
