# Documentation audit: orthogonality, completeness, separation of subjects

Audited: 2026-11-23 08:14 CET, by Claude, on branch `develop` (working tree with the uncommitted `^{n}` change).
Scope: the 34 files of `doc/md/`, `README.md`, `CLAUDE.md`, `resources/musics/CLAUDE.md`, then the HTML in `doc/html/` and the PDFs in `doc/pdf/`.
Status: the corrections below were applied the same morning (the ten pages are rendered, the five stale places mended, the group and the missing words documented, the grammar docs say who they are for, the technical files moved to `doc/md/why/`, `docs.sh` rebuilds its output whole). This report records the state before. The user's pieces in `mus/` are private and were not covered.
Method: outlines of every file, 9-word shingle overlap between all pairs, a check of every relative link, every `scripts/` path, every mu word (`words`, 285 of them) against the docs, a scan for terms that no longer exist, and the links in the rendered HTML.

## Verdict

The set is well separated: the routing (`README.md` and `start-here.md`) is accurate, no link in the markdown is broken, and literal duplication is low (the most overlapping pair, `mu-tutorial.md` and `mu-words.md`, shares 4% of its text). The faults are: a few stale pages, one feature not in the notation reference, three dead links in the HTML, orphan PDFs, and a rendering list that has fallen behind the docs.

## 1. The markdown as a whole

### Subjects and their homes (one subject, one home)

| Subject | Home | Also treated in | Verdict |
|---|---|---|---|
| Getting started | README, `tutorial.md` | `start-here.md` | clear: README = first minute, tutorial = tour, start-here = routing only |
| Notation (what you write) | `parsing.md` | `resources/musics/CLAUDE.md`, `grammar-notes.md`, `lilypond.md`, `tutorial.md` | three files on the grammar, see A3 |
| mu, design | `mu-repl.md` | | clear |
| mu, every word | `mu-words.md` | `mu-tutorial.md` (by example), `cheat-sheet.md` (one page) | clear; reference, tutorial and summary are distinct roles |
| Algorithms | `algo-cookbook.md` | `algo-access`, `algo-context`, `algo-audit`, `algo-signal-chains` | clear, the four are analyses, not guides |
| Live voices, settings | `pull-voice.md`, `hoketus-dynamic.md` | `tutorial.md` (Settings), `domain.md` (keys) | clear |
| How it works | `walk-through.md`, `domain.md`, `namespace.md`, `CLAUDE.md` | | clear |
| Why | `decisions.md` | | clear, but 3789 lines with no index |

### Findings, most important first

**A1. Stale content that describes things that no longer exist** (fix or mark):
- `counterpoint.md:33` shows `parallel/species` with `@^{ voices:3 kind:2 }`. There is no `@^{ }` set in the grammar and no `parallel/species` call of that shape; the working call is in the cookbook (`'[55 57 …] 2 1 :auto :auto 1 jeppesen/species`). `mu-repl.md:244` also describes `@^{ dp:1/8 }`.
- `mu-repl.md:158` says quotations are run "by `call`"; the word is `execute`.
- `assist-with-logic.md` and `core-logic.md` describe the Clojure REPL API (`musics.core/help`, `(play :verse)`, `core.adviser`). Both say so in their status line, but they are reading material from before the mu-only design and neither is linked from `start-here.md`.
- `hoketus-dynamic.md:89` and `domain.md:186` show `tctx-derive` as a Clojure call; it exists in `musics.domain.context` but mu has no such word (a quotation under `T!` replaces it). Both pages should say that this is the code layer.
- `scripts/algo-cookbook.clj` is named in a doc and does not exist (the generator is `scripts/algo-cookbook.clj`).

**A2. Completeness**
- `( )` as a group inside a seq (`[c ( d e ) f]`) is in `mu-repl.md`, `resources/musics/CLAUDE.md` and the cheat sheet, but **not in `parsing.md`**, the notation reference, whose "Brackets" section only knows the tuplet and slur uses of `( )`. `tutorial.md` and `mu-tutorial.md` do not show it either.
- Words that exist and are in no word reference: `edit`, `find-kit`, `find-instrument`, `find-drum`, `defaults-text`, `walking-bass` (in the live word list, one with a description in mu's own `help`). `durs` `vols` `pits` `arts` are short names of listed words. `R_` is in the cheat sheet but not `mu-words.md` (which lists its alias `forget`).
- `perms` and `^{n}` appear only as table entries in `mu-words.md`; the prose sections that describe `shuffle`, `[n]` and `{n}` do not mention them.
- `mu-repl.md` section 9 "Still open" has two items I did not verify against the code.

**A3. Separation of the notation docs.** The grammar is described in three places with three roles that are not stated at the top of each: `parsing.md` (the user's reference), `resources/musics/CLAUDE.md` (the same grammar for a coding agent, loaded on demand) and `grammar-notes.md` (design notes of the comment/variable rules). Their text overlaps little, which is good, but a reader of one cannot tell the others exist. `grammar-notes.md` and `resources/musics/CLAUDE.md` are both titled "Grammar notes".

**A4. Ten pages are not rendered** (`docs.sh` lists 25 of 35): `algo-audit`, `algo-signal-chains`, `assist-with-logic`, `core-logic`, `feedback`, `hoketus-dynamic`, `ideas`, `other-libs`, `pull-voice`, `row-forms`. Three of them are routed to by `start-here.md` and the README (A5).

**A5. Smaller points**
- `decisions.md` has no table of contents; it is the one file a reader searches, at 3789 lines.
- `mu-tutorial.md` (1368 lines) and `mu-words.md` share 316 nine-word runs; the examples are the same ones. Acceptable (tutorial versus reference) but a word's behaviour changed in one place has to be changed in both.

## 2. HTML

Rendered by `scripts/docs.sh` (pandoc, `docs.css`, a navigation line on top).

- **Three dead links**: `index.html` and `start-here.html` link to `pull-voice.html`, `hoketus-dynamic.html` and `row-forms.html`, which are never rendered (A4). This is the one real defect of the HTML.
- The navigation line lists 12 pages; the other 13 rendered pages are reachable only through `index.html` and `start-here.html`, and `cheat-sheet`, `mu-words` and `mu-repl` are not in it, though a reader of the tutorial needs them most.
- The page title is suppressed (`--variable title=`), the page's own `# ` heading serves. There was no date or time on any page. **Fixed in this change**: each page now carries its title and the date and time it was rendered.
- `doc/html/algo-cookbook.html` is described as "generated and kept"; it is the one hand-kept page and is not in the markdown set.

## 3. PDF

One PDF per HTML page (headless Chrome, A4).

- **Orphans**: `mu-repl-audit.pdf`, `raw-algos-before-after.pdf`, `raw-bare-params-call-order.pdf`, `raw-euclid-call-order.pdf` (titles `RAW.html`, `CO.html`, `CO2.html`) and the old `hoketus-dynamic.pdf` and `pull-voice.pdf` have no source in `doc/html/` now; they are leftovers of earlier runs and can be deleted. `scripts/docs.sh` never removes a PDF.
- PDFs carry the page's title in their metadata, and now also the date and time in the page itself.
- No PDF has a table of contents, page numbers or a running header. For the A5 booklet that was discussed, the pages would need an A5 `@page` rule.
- The 70-page `decisions.pdf` and the 25-page `mu-tutorial.pdf` are the two long ones; the rest are 1 to 13 pages.

## 4. Recommended order of work

1. Render the ten missing pages, or take them out of `start-here.md` and the README (kills the dead links).
2. Mend A1 (five stale places).
3. Add the group `( )` to `parsing.md` and the missing words to `mu-words.md` (A2).
4. Say, at the top of `parsing.md`, `grammar-notes.md` and `resources/musics/CLAUDE.md`, which one is for whom (A3).
5. Make `docs.sh` remove PDFs and HTML whose source is gone.
