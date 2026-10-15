# Audit and design notes

Working notes, not polished documentation.

| File | What it is |
|---|---|
| `review.txt` | Architecture review of `core.repo`, `core.async-engine`, `core.wall`, `core.conductor`, `context.clj`, `flat_domain.clj` and `resolve.clj`. Its numbered points are cited from `CLAUDE.md`, `musics.core` and the tests ("review.txt point 1/11"), so they must keep their numbers. Some points describe mechanisms since removed (staged commits, wall factories). |
| `indispensability-adherence.txt` | Six ways to reshape Barlow-indispensability weights by an adherence factor. Three shipped (`tilt-probabilities`, `power-law-probabilities`, `density-grid`); the other three were never built. |

Earlier audits and design explorations (the algoline/toolkit comparison,
the wall-factory pipeline trace, the `algo/` inventory, the staged-commit
command sheet, the 2026-09-10 and 2026-09-17 audits) described code that
no longer exists and were removed; they remain in git history.
