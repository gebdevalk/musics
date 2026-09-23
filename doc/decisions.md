# Architectural decisions

A log of settled design questions — what was decided, what was
considered and rejected, and why — kept separate from `CLAUDE.md` so
that file can stay a current-state reference instead of a running
history. Check here before re-proposing something that looks like an
obvious improvement; it may already have been tried, or deliberately
rejected, for a reason worth reading first.

Format per entry: what was decided, what it was decided against (if
anything), and the one or two sentences of *why* that actually matter
for judging future edge cases. Not a full narrative — see `git log`/
`CLAUDE.md`'s own section for a given mechanism if you need the full
story behind an entry here.

New entries go at the top, though in practice each editing session's
own entries get appended as their own block rather than merged into
strict global date order — check dates within a block, don't assume
the whole file is sorted. `CLAUDE.md`'s historical narration is not
rewritten proactively on its own — only when whatever it describes is
next touched anyway, or on request (its own "Wave 1" through "Wave 7"
section was extracted here on 2026-09-17, see the bottom of this file
— that was the first such pass; any *other* still-narrated aside
elsewhere in `CLAUDE.md`, e.g. inside "Session, the versioned repo, and
playback"/"Conductor"/"Wall", is still untouched and stays that way
until it's next touched for unrelated reasons).

---

**2026-09-23 — `musics.lang`'s `run-repl-loop` reads via a real JLine 3 `LineReader` (new `org.jline/jline` dependency) instead of a bare `read-line`, giving up/down-arrow history (persisted to `~/.musics-lang-history` across sessions) and ordinary left/right-arrow line editing.**
Decided against: hand-rolling history/line-editing over raw terminal input, or leaving `read-line` as-is and treating "no history" as an acceptable limitation of a REPL nested inside another REPL.
Why: a bare `read-line` has none of that -- no arrow-key recall, no in-line editing beyond whatever the raw terminal happens to do -- a real, felt gap against `lein repl`'s own prompt (backed by `reply`/JLine already) sitting one level up. JLine's `.system true` terminal attaches to whatever's actually connected, so the same code path serves both `-main`'s standalone process and `repl!`'s nested case -- confirmed safe for the nested case specifically because the OUTER reply/lein-repl loop is simply blocked, not itself reading stdin, for as long as the nested loop runs (the exact reasoning `(mu!)`'s own nested `clojure.main/repl` already relies on). Ctrl-C now clears the line and reprints a fresh prompt (an ordinary shell's own behavior) rather than either doing nothing (bare `read-line` has no Ctrl-C handling of its own to speak of) or exiting; only Ctrl-D/`bye` still exit.
**Root cause confirmed** (follow-up the same day, once the user reproduced the same dumb-terminal fallback in their own REAL terminal, not just this project's agentic sandbox — so it was never a sandbox artifact): `System.console()` returns `nil` under plain `lein run`, in both environments, and JLine's own `TerminalBuilder` treats that as "not a real terminal" regardless of which underlying provider (`ffm`/`jni`/`jna`/`jansi`/`exec`) is forced — every one of the five reports `type: dumb` with no exception at all when tested directly, ruling out any single provider being the culprit. `lein run` spawns the JVM as a genuine child process rather than exec'ing it in place, which is exactly the kind of process-launch wrapping known to break `System.console()`'s own detection even with a real terminal attached upstream. `lein trampoline run -m musics.lang` (execs java directly, replacing the `lein` launcher process rather than spawning a child of it) was confirmed live, in the user's own real terminal, to fix it — `System.console()` non-nil, arrow-key history genuinely working. `print-dumb-terminal-hint!` now tells the composer this directly the moment it's detected, rather than leaving them to wonder why arrow keys print raw escape codes.

**2026-09-23 — `musics.lang`'s prompt drops stack depth (`vocab<depth>` → `vocab>`); `run-repl-loop` moves it into a new `" ok<depth>"` trailer printed after a line actually runs, instead.**
Decided against: keeping depth in the prompt as before, or dropping it from the REPL's own output entirely.
Why: requested directly, splitting one piece of information (the vocabulary you're in, which only changes on `IN:`) from another (how many items are on the stack, which changes on every single line) that had been living in the same prompt string despite changing at completely different rates. `ok<depth>`, computed fresh right after `run-string` returns and printed alongside the plain `ok`/error trailer `run-repl-loop` already prints, keeps the same information available without it churning the prompt itself on every line. `doc/musics-course.txt` (72 occurrences) and `doc/parse.txt` (22) both used the old `vocab<depth>` shape throughout every transcript — mechanically corrected (`scratchpad<N>`/`my-shapes<N>` → `scratchpad>`/`my-shapes>`, a safe substitution since removing a depth number never changes what a transcript actually demonstrates) plus a hand rewrite of `musics-course.txt`'s own section 1 opening, which specifically taught stack-depth awareness by "watching the prompt's own `<depth>` change" — a mechanism this change removes. Neither doc's own transcript convention was changed to show the new `" ok<depth>"` trailer, since both have always used `run-string` directly (bypassing `run-repl-loop`'s own read/print loop entirely) rather than replaying a literal interactive session — that convention predates this change and stayed as it was, now noted explicitly in `musics-course.txt`'s own header so a reader isn't left wondering why the trailer never appears in transcripts.

---

**2026-09-23 — `project.clj` gains `:main musics.lang`, so bare `lein run` (no `-m musics.lang`) launches the musics.lang REPL directly.**
Decided against: also doing the fuller `lein uberjar`/AOT/`:gen-class` work needed for a genuinely standalone `java -jar ...` executable, in the same pass.
Why: bare `lein run` failing at all ("No :main namespace specified") was a real, felt rough edge, and fixing it needs nothing beyond this one key — `lein run`/`lein trampoline run`/`lein repl` all dynamically require and invoke `:main`'s namespace, no `:aot`/`:gen-class` needed for any of those three tasks (only `lein uberjar` would need that, and wasn't asked for in this pass — scoped down deliberately, on request, rather than assumed). Confirmed live that `:repl-options {:init-ns user}` still wins over `:main` for `lein repl` specifically (`lein repl` still starts in `user`, unaffected) — `:main` only changes what a bare `lein run` targets. Real interactive history still needs `lein trampoline run` specifically, same as before this change (see the entry just above) — `:main` fixes *which* task launches the REPL, not *how* that task's own subprocess gets its terminal.

---

**2026-09-19 — `musics.ebnf` moved to a GUIDO-flavored bracket/accidental scheme: Context `{ }` → `^{ }`; Parallel `(par ...)` → bare `{ }` (its own earlier spelling); accidentals GUIDO-only (`#`/`##`/`&`/`&&`/`n`); `times`/`tuplet` removed outright in favor of a note's own `*Ratio` duration suffix (`c4*1/3`); `transpose`/`repeat`/`alternative`/`grace`(+4 synonyms)/`reverse`/`chordmode` revert from Lisp prefix calls back to backslash commands (`\transpose c d ( ... )`, `\repeat volta 2 [ ... ]`).**
Decided against: keeping the Wave 6/7 Clojure-flavored scheme (`(par ...)` Parallel, bare-word Lisp-call commands, Dutch/English/LilyPond accidental letter suffixes) that CLAUDE.md's own "Grammar" section still narrates in detail as current.
Why: not otherwise recorded at the time of this entry (added retroactively, once the gap between this commit and CLAUDE.md's still-unrevised "Grammar"/"Shape of the system" prose was found) — see commit `33d4ec5`'s own message for the full worked rationale (GUIDO's own bracket/accidental conventions, and `*Ratio` closing the times/tuplet gap without a dedicated command). Flagging here mainly so this move has a decisions.md entry to point to at all: CLAUDE.md described the PRE-revert (Lisp-call, `(par ...)`, Dutch/English-accidental) state as current for three days after this commit landed, a real, confirmed staleness gap this entry and the accompanying CLAUDE.md pass (2026-09-22) closed. `!language:`/`accidental-tables` (Dutch/English/nederlands pitch-language switching) is a SEPARATE, still-open staleness gap this pass did not close — CLAUDE.md's "Multi-measure rests, pickups, and pitch languages" section's third bullet still describes a mechanism this same commit's own message says was removed (`Accidental` is GUIDO-only now, no letter-suffix regex survives) — needs its own pass.

**2026-09-22 — musics.ebnf's line comment reverts from `;` (real Clojure's own spelling, introduced by `d0d8b90`'s "radical/Clojure-flavored" migration) back to `%...` (`%(?!\{)[^\n]*`, its own pre-`d0d8b90` spelling) — every real `;`-comment usage under `mus/` migrated to `%` in the same pass; `musics.core/parse` gains a new `:all-ids` key, and musics.lang's own `parse`/`parse-notation`/`s!`/`parse-file` push every id a parse touched (top-level AND nested, structural order) as separate stack items instead of one `{:ids ids}` map — with an isolated bare top-level leaf's own auto-wrap id replaced by the leaf value itself, never surfaced as an id at all.**
Decided against: keeping `;` as line comment (with `%` only for line comments as an ADDITIONAL, second spelling) so nothing already using `;` would need migrating; keeping musics.lang's `parse` family returning one `{:ids ids}` map per call, with `>ids`/`first` as the unpacking idiom.
Why: `;` colliding with musics.lang's own `#: ... ;` span terminator was a real, confirmed, user-facing gotcha (see `doc/parse.txt`'s own section 3, written the same day) — a bare `;`-comment at depth zero inside a `#: ... ;` span silently truncates the span early, since the scanner can't tell "start a comment" from "end the span," both being the same character. Reverting to `%` removes the collision entirely rather than working around it with depth tricks. Keeping `;` as a second, redundant spelling was considered and rejected — this project's own stated position against back-compat shims, and the fact that `%(?!\{)`/`%{ ... %}` already fully cover both comment shapes on their own, meant a second spelling wasn't earning its keep once the collision was the actual reason to move. Migrating `mus/`'s own 13 example files (their only real `;`-comment usage anywhere in the repo -- `data/`/`test/resources/musics/` had none) was a mechanical, fully-reparsed-and-verified pass, not left half-done.

The `parse`/`:all-ids` stack change (musics.lang side) was requested directly, independent of the comment-character question, in the same session: bundling several touched ids into one `{:ids ids}` map made every id but the whole map itself inaccessible without an explicit `>ids`/`first` unpack step, and `:ids` (top-level-only, still `musics.core/parse`'s original key, kept unchanged for every existing caller e.g. the GUI) already hid a Parallel's own named branches from anything reading just `:ids`. `:all-ids` is `musics.core/parse`'s own new, additive key — a depth-first, written-order walk from `:ROOT` collecting every id `changed-ids` (the existing, unordered-set diff) actually touched, so a top-level container's id always precedes its own nested ids (`{ [a: c4] [b: d4] }` → `[:p1 :a :b]`, not just `[:p1]`). `>ids` itself was removed from musics.lang's `parse` vocabulary once it had no remaining producer to unpack. `try-parse`'s own one-line help text was corrected in the same pass (it never returned `{:ids ids}` at all, contrary to what it claimed — see the leaf-substitution note below for why this was caught only by testing live).

The further leaf-substitution refinement (an isolated top-level leaf pushes the LEAF, not its wrapper's id) was also requested directly: a bare `c4`'s auto-wrap (`flat-tree-walker/wrap-bare-leaf`, from the 2026-09-22 `TopElement`-Leaf entry above) is pure plumbing the composer never asked to name or address again -- unlike a real, composer-written `[ ]` Sequence or `{ }` Parallel, which the composer DID choose to make addressable. `wrap-bare-leaf` stamps its own throwaway container `:bare-leaf-wrapper? true` (never set on an ordinary explicit `[ ... ]`, even a one-child one, since that goes through `walk-element`'s own `:Sequence` case, never `wrap-bare-leaf`) — `:all-ids`'s own collection walk substitutes the wrapper's one leaf value in its id's place whenever it sees that flag, so `"c4 d4 e4" parse` leaves three raw leaf maps on the stack, while `"[c4]" parse` (the SAME single note, but deliberately wrapped by the composer) still leaves its own real id, `:s1`.

**2026-09-22 — `TopElement` now includes bare `Leaf` (Note/Chord/Rest/Drum/MultiRest); `flat-tree-walker/walk` auto-wraps a bare top-level Leaf in its own one-child `:SEQ` Sequence rather than walking it directly.**
Decided against: reverting the whole `TopElement` restriction (re-admitting `Instruction`/transient `Command`/`Reference`/`VarRef` too), or carving a dynamic-free-only exception directly out of `Leaf`'s own grammar rule.
Why: only a bare `Leaf` was actually needed — the other three still have their own confirmed-live write-path into `:ROOT` and none of that changed. A note-glued dynamic (`c4\f`) can't be split out of `Note`/`Chord`'s own grammar rule without much deeper surgery (`NoteSuffix*` is part of the rule itself), so the walker auto-wraps any bare top-level Leaf in an ordinary auto-id'd Sequence instead, reusing `:Sequence`'s own push/walk/pop idiom — the wrapper gets its own genuine `:context`, so `apply-note-dynamics!` mutates that, never `:ROOT`. Verified live, not just reasoned: parsed a bare `c4\f`, confirmed `:ROOT`'s own `:volume` stayed at its unmodified default both immediately after and after a second, unrelated bare note, while the returned wrapper id's own `:volume` correctly reflected forte. One real consequence: several bare leaves with no `[ ]` (`c4 d4`) become two separate one-note Sequences, not one — `[ ]` is still required to group leaves together.

**2026-09-17 — `core.repo`'s two-phase staging (`begin-staged-tx!`/`stage!`/`commit-staged!`/`abort-staged!`) was removed; `musics.core/parse` commits immediately, one atomic swap!, instead of staging under a `sid` for a separate `commit!`/`abort!` call to resolve later.**
Decided against: keeping stage/commit/abort as three genuinely separate steps.
Why: the two-phase dance's only real job — several ids from one `(parse ...)` call landing together, atomically — is already covered by a single immediate multi-id `swap!`/`merge` (`commit-many!`), with no separate open/hold/abort lifecycle needed to get it. A rollback was never actually the intended use in practice — a parse that comes out wrong is fixed by re-parsing under the same id, not by aborting a not-yet-visible staged value — so the extra state (`:sid`, `pending`, `abort!`) was pure ceremony around an atomicity guarantee `commit-many!` already provides directly. `musics.core/parse`'s own return shape dropped `:sid`/the staged-vs-committed distinction accordingly.

**2026-09-17 — Each voice carries its own `:view` (a frozen snapshot of the repo, captured once at birth) instead of a per-voice `:tx` number; committing auto-advances the one remaining `play-tx` pointer instead of leaving it wherever it last pointed.**
Decided against: keeping a per-voice `:tx` integer that a voice re-resolved against a versioned store, and requiring an explicit `(play-latest!)` call after every commit to keep new playback current.
Why: once every commit already needs to land immediately and visibly, a voice needs isolation from LATER edits, not a number to re-look-up — capturing the current value once, as a plain immutable map, gives that isolation for free via ordinary Clojure persistent-data-structure semantics, no tx-numbering machinery required. Auto-advancing `play-tx` on every commit closed a real, confirmed-live footgun: forgetting the explicit `(play-latest!)` step after a commit silently left the *next* `play` call performing stale content, with no error — the fix made "committing" and "a fresh voice sees it" the same moment, always, while a voice already mid-performance stays completely unaffected (its own `:view` never moves on its own).

**2026-09-17 — `core.repo` collapsed to a flat `{id -> node} `map with no history dimension at all — `as-of`/`history`/`latest-tx`/`view`/`RepoView`/the `play-tx` pointer itself are all removed, not just superseded.**
Decided against: keeping tx-numbered history reachable (an explicit `tx` argument on every inspection fn, `as-of`/`history` for time-travel) even after `play-tx` itself had become redundant with "whatever's currently committed."
Why: once every commit lands immediately and a voice's own `:view` (not a tx number) is what actually provides isolation from later edits, nothing left in the project ever read a PAST commit again — grepped and confirmed before removing anything, not assumed. Retaining history nothing could reach was pure cost: memory (every past value of every id, forever), and a whole extra optional-trailing-`tx`-argument dimension on roughly a dozen `musics.core`/`input.forth`/`core.wall` functions, all to serve zero real call sites. A caller who wants to keep an old value already can, the same way any Clojure value is ever kept (`(def v0 (sq :id))`) — that's not a capability this removal took away, since `sq`'s own result was already a frozen snapshot before and after. `core.repo/registry` (a thin accessor FUNCTION, not a bare `def` alias) replaced `play-tx` as the one thing a fresh voice's own `:view` snapshots from — a function specifically so it still re-resolves `core.registries/*repo-registry*`'s current dynamic binding at call time rather than freezing onto whatever the root binding was at namespace-load time; confirmed directly that a bare var alias would silently ignore a test's own `binding`, not just reasoned as a risk. One real bug surfaced mid-refactor and was caught before shipping: `core.repo/reset-all!` briefly delegated wholesale to `core.registries/reset-all!` (which also wipes `core.wall`/`core.conductor`/`core.adviser`'s own state), rather than resetting only the repo registry the way it always had — narrowed back once a test (`persist-session-round-trips-a-factory-built-algo-assignment`) failed specifically because a `(repo/reset-all!)` call was silently wiping more than it should have.

---

**2026-09-03 — Filters became one general engine (`gate`) + a registry of named criteria + `configure-preset!` presets; a whole-project survey found five other superficially similar cases and deliberately left all of them as plain functions.**
Decided against: applying the same general-engine-plus-registry refactor project-wide once the pattern was found once (`algo.random`'s own `lo-emph`/`mean-emph`/`hi-emph`, `algo.common.zfilter`'s own `smooth`/`momentum`/`memory`, `rising`/`falling`, `random-walk`/`biased-walk`, `algo.common.trig`'s own `cosr`/`sinr`/`tanr` were all found to have the identical shape — one general engine, several fixed-parameter variants).
Why: the filters are independently *played* algorithms (`:algo [:loFilter 67]`) — genuinely benefiting from being nameable/switchable via `configure-preset!`, the same real reason `weighted-shuffle-algo`'s own distribution argument already works that way. The other five cases are all plain building-block *values* — nothing anyone independently plays or configures via a preset, just occasionally handed to something else (`register-distribution!`, `smooth-pitch-algo`) when actually needed, which they already support fine as ordinary functions. Turning three one-line functions into a registry entry + factory + `configure-preset!` call trades trivial duplication for genuine indirection with no real payoff — directly against this file's own house rule (`CLAUDE.md`: "three similar lines is better than a premature abstraction").

**2026-09-03 — `gate` operates on a Leaf's own FIRST pitch only; per-chord-tone filtering (gating each pitch of a chord independently, the earlier `pitch-filter`'s own behavior) was dropped.**
Decided against: preserving the earlier version's own "a chord is filtered pitch by pitch" behavior in the new general engine.
Why: per-chord-tone splitting only ever applied to the pitch-range criteria (`:lo`/`:hi`/`:window`/`:pitch-class`) — `interval-filter`/`probability-filter` already operated on the whole Leaf. Unifying six criteria under one engine needs one consistent contract; whole-Leaf pass/fail is simpler and uniform across every criterion, matching the broader simplification this whole redesign was already committed to.

**2026-09-03 — `select-fn` receives `raw-prev` (the immediately preceding Leaf in the ORIGINAL sequence); `on-reject :hold` uses a separately-tracked `last-sounding` — two different notions of "previous," not one.**
Decided against: a single "previous" value serving both needs.
Why: `interval-filter`'s own criterion needs the RAW previous Leaf regardless of whether it was itself kept or rejected (an excluded note still counts as "the previous one" for the next note's own interval check — matches the original source email's own zip-over-raw-sequence semantics, confirmed before this redesign). `:hold` needs something different: the pitch of whatever ACTUALLY sounds immediately before this note in the OUTPUT, so a run of several consecutive rejections correctly chains back to the same real note rather than each hold re-anchoring to the previous hold's own (already-substituted) pitch. Verified live before writing real tests: `[60 67 72 50]` filtered at cutoff 65 with `:hold` produces `[60 60 60 50]` with `:tied` `[false true true false]` — both rejected notes correctly hold the ORIGINAL 60, not a chain of re-anchored holds.

---

**2026-09-02 — Micro-timing (`:micro`/`:humanization` context keys) can only ever DELAY a note's onset, never anticipate it.**
Decided against: porting the source design email's own `push`/`pull` symmetric-offset model, or trying to let `:micro` reach negative enough to fire a note early.
Why: `play-event!` has never had a wait of its own before sending note-on — it fires the instant its go-block runs, relying entirely on the previous note's own hold landing at the right wall-clock moment. There is no earlier instant left to reach back to once execution is already there, so a negative offset is structurally impossible without adding a genuine look-ahead/buffering scheme (a much bigger change, out of scope here) — clamped to 0 instead of silently ignored or, worse, becoming a negative timeout. `:humanization`'s own jitter is scaled the same way, onto a fixed `humanize-max-jitter-secs` (0.05s) — a deliberately chosen, not rigorously derived, "roughly the upper end of ordinary human timing variability" constant.

**2026-09-02 — A note's micro-timing offset never touches the voice's own running `:clock`/`:structural` atoms.**
Decided against: applying the offset directly to `@clock` before computing this note's own timing targets.
Why: `:clock` is what every SUBSEQUENT note's own nominal onset is computed from — perturbing it would compound one note's own local delay into permanent drift for the rest of the voice, and would make every note's own offset depend on whichever offsets came before it rather than staying independent. The offset is applied only as a local target for THIS note's own scheduling (`onset-target`/`played-target`/`full-target`), computed from `onset` (the clock's own value at entry) plus the offset, entirely separately from the clock's own advancement at the end of `play-event!`, which still adds `(:dur-secs midi)` to whatever `@clock` already held — the unperturbed value.

---

**2026-09-02 — `weighted-shuffle` picks its next output element from what's remaining, never sorts by an independent per-element key.**
Decided against: the more obvious-looking construction — draw one independent key from the distribution per element, sort by the keys. Tried first, in a throwaway probe, before writing any real code.
Why: ranks of i.i.d. continuous draws are uniform over permutations no matter the marginal distribution's own shape — a real, checkable probability fact, confirmed with a 20000-trial probe before deciding anything: `uniform` and `algo.random/lo-emph` came back statistically indistinguishable (1.9456 vs 1.9395 average displacement) under the sort-by-key construction, meaning the distribution's own shape would have been silently irrelevant to the result. The construction actually used — repeatedly draw the *next* output index from `(dist-fn 0 n)`, `n` the current remaining count — does respond to the distribution: with `lo-emph` (peaked low) it clearly preserves more of the original order than `uniform` does (1.26 vs 1.94 in the same probe), and with `uniform` it reduces to the same permutation distribution plain Fisher-Yates produces (1.9422 vs 1.9449), confirming it's a genuine generalization of shuffle, not an unrelated thing that happens to also permute. See `algo.common.reshape/weighted-shuffle`'s own docstring.

**2026-09-02 — A composite wall-fn factory resolves a named argument (e.g. a distribution) against its own registry, eagerly, inside the factory itself — not lazily inside the wall-fn it returns.**
Decided against: letting an unregistered name fail wherever the returned wall-fn first actually gets called.
Why: a wall-fn runs deep inside a live voice's own `go-block`; a thrown exception there is swallowed silently rather than surfaced (confirmed elsewhere in this project, not assumed here). Checking `core.wall/distribution-fn` inside the factory itself, before ever returning a closure, means an unregistered name degrades to identity with a console warning *at assignment time* — the same "degrade and warn, never throw from inside a live voice" policy `core.wall/apply-factory` already has for every other composite-resolution failure mode. See `algo.common.reshape/weighted-shuffle-algo`.

**2026-09-02 — Distributions get their own, third registry (`*distribution-registry*`), separate from `*algo-registry*`/`*preset-registry*`.**
Decided against: reusing the algo registry itself (registering a distribution as if it were a wall-fn).
Why: a distribution is a plain `(lo hi) -> value` sampler (e.g. `algo.random/lo-emph`) — it has no `(nodes ctx voice) -> nodes'` shape at all, so it isn't a wall-fn and calling it as one would be a straightforward category error. This is the concrete first case for "algorithm composition specified declaratively, not just parameters" (see the session that produced it): a composite factory (`weighted-shuffle-algo`) needs to resolve one of its own arguments *by name* against something that isn't repo material and isn't another wall-fn either — a third, independent axis of "reference something named," alongside `configure-preset!`'s existing repo-material resolution.

---

**2026-08-29 — Look-ahead: one-note-ahead single-slot design, not a whole-bar batch with a scanning coordinator.**
Decided against: the first working version — a shared coordinator scanning every voice on a tick, dispatching threads that computed roughly a whole bar ahead into a per-voice `{:cursor :pending :gen :inflight?}` map.
Why: that shape had a real, structural hazard — two different threads (the voice's own goroutine, and the coordinator's dispatched thread) mutating shared, multi-field per-voice state — and it produced a genuine bug on nearly every attempt to close it: a stale-snapshot lost-update in the consume path, an unconditional wipe of still-valid data on rearm, a replace-instead-of-append in the dispatch path, and a "first entry must match" check that (correctly, once fixed) had to become "search past stale leading entries." Each fix patched one symptom of the same root cause. The rebuilt version removes the hazard structurally instead: precompute exactly one leaf ahead (never a whole bar), hold it in a single slot (empty or one leaf's worth, nothing to split/append/search), and invalidate it eagerly via `add-watch` on the two things that can make it stale (`:tx` per voice, `:algo-assignments` engine-wide) rather than having every reader re-derive staleness from a snapshot. The real cost: less depth — one note's worth of hidden compute time, not a bar's — accepted deliberately in exchange for removing the bug class rather than continuing to patch instances of it. See `core.async-engine`'s own "Look-ahead" section header comment for the mechanism as built.

**2026-08-29 — Doc style: current-state prose in `CLAUDE.md`, reasoning here.**
Decided against: continuing to narrate history inline in `CLAUDE.md`
("Wave N", "this used to X, now it's Y, because Z") as the default style
for new or edited documentation.
Why: `CLAUDE.md` is read fresh every session and has grown large enough
that retrospective narration competes with just stating how the system
works now. The *why* is still valuable — for exactly the "don't
re-litigate a settled question" purpose this file serves — so it moves
here as a condensed entry instead of inline prose. Applies going
forward, when a doc is next touched; not a retroactive rewrite.

**2026-08-29 — Deleted the AtomicAlgo/ElementAlgo registry outright, not kept dormant.**
Decided against: leaving `input/algo_registry.clj` (and its
`musics.clj`/Forth wrappers) in place now that `@[ ]`/`@{ }` are gone
from the grammar, on the reasoning (previously the actual call, per an
earlier `CLAUDE.md` note) that "registering an algorithm still works
exactly as before."
Why: once its only two readers (`walk-atomic-algo`/`walk-element-algo`)
were already gone from the walker, the registry had no entry point left
to serve at all — keeping it wasn't "unreachable from text," it was
orphaned code with no caller anywhere. `color-talea`/`split-leaf-voice`
are unaffected as plain Clojure functions; register one as a *wall*
algorithm (`core.wall/register-algo!`) if per-voice playback reach is
wanted. The deleted registry's own surface, for the record:
`atomic-algo-registry`/`element-algo-registry` (plain `defonce` atoms,
`name -> {:fn f :doc doc}`), `register-algo!`/`unregister-algo!`/`algos`/
`register-element-algo!`/`unregister-element-algo!`/`element-algos`
(`musics.core` wrappers), plus the matching Forth words
(`ALGOS`/`ALGOS?`/`REGISTER-ALGO!`/`REGISTER-ALGO-DOC!`/
`UNREGISTER-ALGO!`) — the wall-side words
(`REGISTER-FACTORY!`/`BUILD!`/`ALGOS`/`ASSIGN-ALGO!` and the rest) are
a separate, unaffected vocabulary in `input/forth.clj`.

**2026-08-29 — Algorithm resolution (`resolve-algo`) stays in `core.async-engine`, not `core.domain.resolve`.**
Decided against: folding wall-algorithm application into
`resolve-event` itself, even though the two now read as obviously
symmetric "resolve" steps in `play-node`.
Why: `core.domain.resolve` deliberately has zero dependency on voices or
`core.wall` (tier 2, "Sound," staying ignorant of tier 3, "the
playground" — see `CLAUDE.md`'s "Shape of the system"). Moving wall
invocation into `resolve-event` would hand tier 2 a tier-3 concept it
has no business knowing about. Named `resolve-algo` instead, living
next to `voice-algo-slot-fn` in the engine, where voice-awareness
already legitimately belongs — same conceptual symmetry, no dependency
inversion.

**2026-08-29 — Algorithm assignment stays voice-path-keyed, never chain/structurally scoped like context values.**
Decided against: giving `:algo-assignments` real chain semantics (like
`ctx-value-chain`'s nearest-first search) so a nested `:algo` tag's
push/pop hack in `play-form-tagged` could be replaced with free nesting.
Why: an algorithm is a *performance* choice bound to a voice's own
identity — deliberately unreachable from `.mus` text, and surviving a
`:tx` redirect to entirely different material. Context values are a
*material* property, authored in text, scoped to structural position.
Giving algorithm assignment chain semantics would bind it to structural
position instead of voice identity, which is exactly the tier boundary
(`:algo` never reachable from text) this project has already committed
to elsewhere. The push/pop mechanism is the accepted cost of keeping
that boundary, not an oversight to fix.

**2026-09-05 — Generator parameters (r/a/b/sigma/rho/beta) go context-driven via a `pre-step-fn` hook, not a per-generator elapsed-duration accumulator or a pre-written cycling Data sequence.**
Decided against two earlier designs in the same discussion, both
concrete enough to be worth recording so they aren't re-proposed:
(1) giving each generator its own internal elapsed-duration accumulator
so `next-fn` could sample context "as if" it knew musical time. Wrong:
`voice`'s own `:structural` atom already tracks real elapsed time, for
free, the same coordinate every ordinary note's `:micro`/`:humanization`/
`:Tempo` sampling already uses (`core.domain.resolve`) — a generator
never needed its own copy of this, it just had no path to reach the one
that already exists, since `next-fn` in `core.wall/stateful-generator`
is a bare 0-arg closure with no access to `ctx-chain`/`voice` at all.
(2) feeding the algo a plain Data sequence to cycle through instead of a
context envelope — simple, and reuses `'[ ]` container resolution
`configure-preset!`'s own `resolve-config-form` already has. Rejected
directly by the user as "too static": a pre-written cycling sequence is
the same *kind* of thing as one fixed value, just plural, fully decided
before playback, unlike a context envelope, which can be changed
mid-performance via this project's own commit/`schedule-tx!`-redirect
mechanism (see "Session, the repo, and playback" in
`CLAUDE.md`) — genuinely live in a way a baked-in sequence literal
structurally cannot be.
Landed instead: `core.wall/stateful-generator` gained an optional 3rd
arg, `pre-step-fn`, called at most once per genuinely new placeholder
(same idempotency guard `next-fn` already has) as `(pre-step-fn
ctx-chain @(:structural voice))` right before `next-fn` — pure plumbing,
no new time-tracking. `core.wall/context-params-pre-step-fn` is the
shared helper built on top (`{param key} -> pre-step-fn`, sampling each
key via `ctx-value-chain` and handing the result to a setter), wired
into `logistic-algo`/`henon-algo`/`lorenz-algo` as an optional trailing
arg (`r-key`/`param-keys`) — omitted, every parameter stays exactly the
fixed literal it always was.

**2026-09-05 — `Pulse`'s `:value` is a fixed literal (always `1`) for now, not resolved from context or a function.**
Decided against: giving a text-authored Pulse (`p<Duration>`,
`PitchLetterRel`'s own long-reserved-but-unwired `p` slot in
`musics.ebnf` — confirmed live before this fix that resolving it as an
ordinary pitch threw a `NullPointerException`, `common.music-data/
diatonic-pcs`/`diatonic-degree` having no `p` entry) a way to set an
explicit value at authoring time, whether a plain literal suffix or a
context-key/function reference resolved later at play time.
Why: the user's own account of `p`'s original intent was "a pitch that
had to be looked up in the context (from a function or something),"
which is a fundamentally different mechanism (deferred, resolved at
play time) than a value baked in once at parse time — and it's still
genuinely unsettled which of the two `Pulse` is actually for: the
algorithmic side (`algo.common.pulse/grid->pulses`, an onset/pulse grid
already computed once, value known upfront) or something closer to the
old, deliberately-removed `AtomicAlgo`/`ElementAlgo` grammar mechanism
(a value computed fresh, per note, at resolve time). Committing to a
literal-value text syntax now would bias toward the former and make the
latter, if it turns out to be what's actually wanted, a breaking change
to undo. A fixed default (`p4` → `Pulse{duration 1/4 value 1}`, always)
keeps every existing capability that's actually needed today —
`algo/`'s generators build `Pulse` records with real values directly,
never through this text path at all — while leaving the resolution
question genuinely open rather than answered by accident.

**2026-09-05 — `p` excluded from `Chord` at the grammar level, not just the walker; the now-redundant walk-time guard removed rather than kept alongside it.**
Decided against: keeping `walk-chord`'s own `pulse-letter?` check (added
earlier the same session, catching `<c e p>4` with a clear `ex-info`)
once `musics.ebnf`'s `ChordPitch = !'p' Pitch` made the same case a
genuine parse-time failure instead — user's own explicit ask ("a p
should not occur in a chord" → "can you change the grammar? … yes,
exclude p from Chord").
Why: once the grammar itself makes `<c e p>4` unparseable, `walk-chord`
can never be reached with a `p` pitch in its own children through
either real caller (`musics.clj`/`grammar_parser.clj`, both instaparse-
first) — confirmed by checking every call site of the walker, not
assumed. Keeping the walk-time check anyway would be exactly the
"validation for a scenario that can't happen" `CLAUDE.md` already warns
against; removed instead of left as unreachable defense-in-depth.
`ChordPitch` stays a hidden (`<...>`) rule specifically so `Pitch`'s own
node still splices straight into `Chord`'s children — no wrapper level
added for the walker to see, so this needed zero walker changes beyond
deleting the now-dead guard.

**2026-09-05 — `algo.melodic.melody`'s scales converted to pitch-class integers, not absolute MIDI or `common.music-elements/key`'s own scale-degree representation (algo.txt's GAP 1).**
Decided against two alternatives, after surveying every pitch-touching
function across all 31 `algo/` files (not just `melody.clj`), per the
user's own ask to "look at all pitch generating algo's first to see how
we can unify output" before changing anything: (1) converting straight
to absolute MIDI — rejected, since a scale here never carried octave
information to begin with, and everything currently NEEDING absolute
values (`Leaf`'s own `:pitches`, `counterpoint.clj`) already gets there
via one uniform, always-the-same octave-offset step, regardless of
source; (2) reusing `common.music-elements/key`+`key-pitches` directly
(the project's own existing, central, well-tested scale-building
mechanism) — rejected once actually checked live: `key`'s own `:pitches`
are NOT wrapped into 0-11 (e.g. `(key-pitches (key :A :minor))` =>
`[9 11 12 14 16 17 19]`, deliberately unwrapped so an ascending scale
run from a non-C tonic stays ascending in absolute terms) — a genuinely
different, and for THIS purpose incompatible, convention from what
`algo.common.gate/pitch-class-criterion` and `algo.common.zfilter/
pc-smooth` actually need (a value already reduced mod 12, confirmed by
reading both bodies directly, not assumed).
Landed instead on a new, small, `algo/common/`-level helper (`algo.
common.pitch/build-scale`, per the user's own "centralize the
conversion in common") that wraps every interval into 0-11 via `mod` --
matching gate/zfilter's own convention exactly, letting `melody.clj`'s
output plug into either with zero further conversion, and available to
any other `algo/` generator wanting a root+interval-pattern scale
without duplicating this same wrap-around-12 logic locally. `melody.clj`
itself needed no changes beyond its own scale defs (`c-major`/`a-minor`/
`c-pentatonic`) and one stale usage example (`cadence-constraint`'s
`cadence-note` arg) -- every generator/constraint fn in the file
(`markov-generate`, `constraint-melody`, `max-leap-constraint`, etc.)
was already representation-agnostic, confirmed by reading each body,
not assumed.

**Same day, follow-up correction** — the user's own question ("we
already had keys, scales and signatures does melody reinvent the
wheel?") caught something the entry above missed: rejecting `key`'s own
`:pitches` as the *direct* source (still correct — see above) isn't the
same question as whether `melody.clj`'s own hand-typed interval
patterns (`[0 2 4 5 7 9 11]` etc.) duplicate `common.music-elements/
scale-steps`'s already-central formula table — they did, verbatim, just
re-encoded as cumulative offsets instead of consecutive steps.
`algo.common.pitch` gained a second fn, `from-key` (`key-kw scale-kw ->
mod-12-wrapped key-pitches`), a thin adapter over the SAME central
table rather than a parallel copy of the scale formulas themselves;
`build-scale` itself is unchanged and stays available for a genuinely
custom pattern with no entry in `scale-steps` at all. `melody.clj`'s
three scale defs now call `from-key` instead of typing intervals by
hand.

**2026-09-05 — `modulating-melody`'s pivot note is never de-duplicated at a segment seam; each segment always contributes exactly its own declared length.**
Decided against: dropping a shared pivot note from the second segment's
own output when a modulation pivots cleanly (i.e. treating the pivot as
one shared note belonging to both segments, so a segment's own
`length` would sometimes mean "one fewer new note" than declared).
Why: with de-duplication, `(count result)` would no longer always equal
the sum of every segment's own `length` — a fuzzy invariant depending
on whether THIS PARTICULAR run happened to pivot, which is genuinely
hard to predict from the arguments alone (a real, unrepeatable-without-
reading-the-scale side effect). Keeping every segment's own `length`
literal (a pivot just repeats that one note value once, consecutively,
at the boundary — a held tone, not an error) keeps the contract simple
and the total note count always directly computable from `segments`
alone, at the small cost of an occasional literal repeated value where
a real modulation's own pivot tone would usually just be written once.
`algo.common.pitch/resolve-scale` (accepting a plain scale vector, a
`[key-kw scale-kw]` pair, or a `"F#.major"`-style spec string, dispatch
purely by shape — a 2-element vector is only ever read as a keyword
pair when BOTH elements actually are keywords, since a real scale is
always plain integers) is what lets `modulating-melody`'s own segments
mix pre-built scales with spec shorthand freely, motivated directly by
the user's own question ("can melody become richer... with access to
new source material?") once surveying `common.music-elements` turned
up 24 named scales × 13 tonics (312 combinations) reachable via
`from-key`/`from-key-spec`, against `melody.clj`'s previous fixed 3.

**2026-09-05 — GAP 4 (algo.txt) resolved by unifying every remaining bare `clojure.core rand`/`rand-nth`/`shuffle` site onto `algo.random`.**
Decided against: leaving the split as-is on the reasoning that
reproducibility might not matter for every one-shot/static generator.
Why: a precise re-check (not the broad, alias-collision-confused grep
that first suggested this was widespread — `algo.random`'s own
conventional alias, `rand`, made namespaced calls like `rand/choose`
false-positive-match a naive "bare rand" search) found the real split
was narrow and clean: exactly 3 files, 8 call sites total
(`algo.rhythmic.rhythm/markov-rhythm`'s own `(rand)`;
`algo.melodic.melody`'s `markov-generate`/`grammar-generate`/
`constraint-melody`, 6 sites; `algo.common.gate/probability-criterion`'s
`(rand)`) — against every OTHER file in the same directories
(`counterpoint.clj`, and every `rhythmic/*` file except `rhythm.clj`
itself) already drawing consistently from `algo.random`. Every site had
a confirmed 1:1 replacement already in `algo.random`'s own public API
(`rand-double`/`choose`/`shuffle`), used identically by those neighbors
— genuinely no design tradeoff left to weigh once the scope was this
narrow and well-precedented, unlike, say, the `melody.clj`/
`common.music-elements/key` representation question earlier the same
day. `algo/`'s own generators can no longer silently mix a reproducible
stream with an unreproducible one when composed together.

**2026-09-06 — `tilt-probabilities` normalizes its own weights to
[0,1] by their max first; `indispensability` itself deliberately left
untouched.** Motivated by a real bug found while designing an
"adherence" factor on top of Barlow indispensability: `beat-
probabilities` multiplied raw rank values straight into its softmax
exponent, so the same `adherence` value tilted a 12-pulse meter's
ranks (`0..11`) much harder than a 4-pulse meter's (`0..3`) purely
because the numbers involved were bigger — `adherence` didn't mean the
same thing across different meters. Decided against normalizing inside
`indispensability` itself instead (the more literal reading of "change
the indisp function"): checked the actual call sites first
(`test/indispensability_test.clj`, `test/music_elements_test.clj`)
and found `indispensability`'s exact-integer output is load-bearing —
verified there against Barlow's own known-correct reference tables
(`[2 2 3]` -> `[11 0 4 8 2 6 10 1 5 9 3 7]`) and checked as a genuine
permutation of `0..N-1` (`permutation-of-0-to-n-1?`). Normalizing there
would have turned those clean, hand-checkable reference values into
elevenths and broken the permutation check outright, for no gain: the
only thing that actually needs meter-size independence is where
adherence gets applied, not the ranks themselves. `tilt-probabilities`
now divides its input by its own max before exponentiating (the
docstring already says "or any weights," so this doesn't narrow its
contract) -- confirmed scale-invariant with a new test comparing
`(tilt-probabilities [0 1 2 3] 2.0)` against `(tilt-probabilities
[0 10 20 30] 2.0)` for exact equality.

**2026-09-06 — `density-grid` (new, `algo.indisp.indispensability`)
selects the top-K most indispensable pulses deterministically, not via
a per-pulse weighted coin-flip.** Motivated by the user's own question
("indisp allows also to specify a density: how much of the pulses are
made into actual sound") -- checked first whether this already existed
(`algo.common.gate`'s criteria are pitch/interval/Bernoulli-based, no
metric-position awareness at all; `algo.rhythmic.stochastic/
stochastic-rhythm` builds a density-driven binary grid too, but from
statistical distributions over raw index, never from indispensability
weights -- same word, unrelated mechanism), then asked the user
directly which selection style they wanted, since the two give
genuinely different musical results: a probabilistic per-pulse
Bernoulli gate (like `stochastic-rhythm`'s own gaussian/exponential
branches, or `probability-criterion`) varies which pulses survive from
one pass to the next; a deterministic top-K by rank always keeps the
exact same subset for a given meter/density pair. User chose
deterministic top-K -- a fixed metric "skeleton" thinning. Sorts
`(map-indexed vector ranks)` by weight descending and keeps the first
`(Math/round (* n density))` positions; ties (only possible for
non-permutation "any weights" input, never for `indispensability`'s own
output) break by original position order via Clojure's stable sort.
Outputs the same 0/1 grid shape every other rhythm generator in
`algo/rhythmic/` already does, so it composes directly with
`algo.common.pulse/grid->pulses`.

**2026-09-06 — `tilt-probabilities` gained an irrational, position-
based tie-break; `power-law-probabilities` added as a second, distinct
adherence mechanism, per `indispensability-adherence.txt`'s own survey.**
`tilt-probabilities`'s softmax collapsed to an exact tie at
adherence=0 (every exponent reduces to `exp(0)=1`) -- the precise
"area of equal values" the whole adherence design was meant to avoid,
confirmed still present in the real code, not just discussed. Fixed by
adding `tie-break-phi * i` (i the pulse's own position, `tie-break-phi`
a fixed irrational constant scaled to `1e-6`, far below anything
musically audible) INSIDE the exponent but OUTSIDE adherence's own
multiplication, so it survives no matter what adherence is -- at
adherence=0 only this term is left, strictly increasing in position,
never flat. Being irrational, two pulses can only tie at an irrational
value of adherence -- unreachable by any real/floating-point input.

`power-law-probabilities` is a second, genuinely different mechanism
(same file, sharing the new private `normalize-weights` helper with
`tilt-probabilities`), not a replacement -- the user asked for both.
Raises normalized weights to an exponent driven by `adherence`; unlike
softmax, it's always order-preserving (or order-REVERSING), never
re-ranks by blending. Covering the full `-1.0..+1.0` range safely needs
a branch on adherence's sign, not one continuous exponent: exponentiate
the normalized weight directly for `adherence >= 0`, exponentiate its
COMPLEMENT `(1 - normalized weight)` instead for `adherence < 0`.
Worked out live why the naive alternative (map adherence linearly onto
a single continuous exponent, letting it go negative) fails two
different ways: `0^(negative)` is `+Infinity` in Java, poisoning the
whole normalization with a NaN; and by the intermediate value theorem,
any continuous exponent function equal to 1 at adherence=0 and very
negative near adherence=-1 MUST cross exactly 0 somewhere in between --
at that crossing, `rank^0 = 1` for every pulse, reproducing the exact
collapse bug this whole design started from, just relocated to an
interior point instead of the endpoint. The branch-on-sign design keeps
the exponent >= 1 always, on both branches, so it never has to cross 0
at all; both branches agree exactly at adherence=0 (exponent 1, raw
normalized ranks, no collapse, no tie-break hack needed here since an
exponent of exactly 0 -- the only value that could tie two distinct
positive bases -- never occurs). `power-law-max-exponent` (8.0, at
`\|adherence\|=1`) is a deliberately chosen, not rigorously derived,
constant, same spirit as async-engine's own `humanize-max-jitter-secs`
-- steep enough to make the strongest pulse dominate almost completely,
without the overflow risk an unbounded exponent mapping would carry
right at the `+-1` edge.

A genuine, confirmed behavioral difference between the two mechanisms,
not just a different curve shape: `exp(anything)` is always `> 0`, so
`tilt-probabilities` never assigns a pulse exactly zero probability, no
matter how extreme adherence gets. `power-law-probabilities` does,
whenever a position's own base is exactly `0` -- the least-indispensable
pulse for `adherence >= 0`, the downbeat itself for `adherence < 0` --
confirmed live and pinned down by test (`power-law-probabilities-zero-
rank-position-gets-exactly-zero`/`-downbeat-gets-exactly-zero-at-full-
negative-adherence`). Left as a real, documented tradeoff rather than
patched -- it directly follows from raising an actual `0` normalized
weight to any positive power, not an edge-case bug.

**2026-09-06 — `beat-probabilities` renamed to `tilt-probabilities`.**
The old name predated the "named adherence mechanisms" framing this
whole design session introduced (`indispensability-adherence.txt`'s own
survey calls this mechanism "Tilt / contrast") -- once
`power-law-probabilities` existed as its sibling, announcing its own
mechanism by name, `beat-probabilities` was the odd one out, not
naming what it actually does. Pure rename, no behavior change; every
call site (source, tests, both scratch `.txt` docs) updated together.

**2026-09-06 — `normalize-weights` made public; `normalized-
indispensability` added as the one-step subdivisions -> [0,1] floats
wrapper.** Motivated by the user's own request for a chainable starting
point: `indispensability` -> `normalize-weights` -> arbitrary ordinary
seq transforms (`reverse`, `algo.random/shuffle`, `algo.common.rotate/
rotate` for a "shift") -> `algo.common.pulse/grid->pulses`. Needed no
new combinator/pipeline mechanism of its own -- a normalized weight
vector is just a plain Clojure vector, so every one of those already
composes via ordinary threading; the only real gap was that
`normalize-weights` was private (added alongside `tilt-probabilities`'s
own scale-invariance fix, with no reason at the time to expose it more
widely) and there was no single call combining it with
`indispensability` for the common "start from subdivisions" case.
Also fixed in passing, confirmed by a failing test before the fix: raw
integer ranks divided by an integer max produced exact Clojure Ratios
(`1/4`, not `0.25`), not the floats the docstring already promised --
`normalize-weights` now coerces the max to `double` first. Purely a
type-consistency fix, not a behavior change for either
`tilt-probabilities`/`power-law-probabilities` (`Math/exp`/`Math/pow`
already coerced their input either way).

**2026-09-10 — a voice's own algorithm assignment became an immutable
field, not a live, externally-reassignable table.** Reconsidered
directly from the 2026-09-09 redesign (`core.wall`'s own ns docstring):
that design kept `:algo-assignments`, `path -> name`, as the ACTUAL
per-node source of truth — every voice re-read it fresh on every node,
and `assign-algo!` could repoint an already-playing voice to a
different name from outside, at any moment. Rejected in favor of: a
voice's own `:algo` baked in once, at mint/fork time, never reassigned
for that voice's life; the only way to change what an already-playing
voice sounds like is `build!` rebuilding what its (fixed) name resolves
to in `*algo-registry*`. Motivating argument: every genuine use case
already discussed turned out to be covered by that one remaining
mechanism plus a narrower, separate table (`:algo-prepared`) consulted
ONLY at mint time — hot-swap-by-rebuild was never in question, only
whether a voice's own *pointer* also needed to be externally mutable,
and nobody could name a capability that needed the pointer-mutable case
specifically, once "prepare a track before it starts" and "coordinate a
swap via the conductor" were each already reachable another way (the
former via `assign-algo!` on a not-yet-live path, or passing `:algo`
straight to `play-change`; the latter via `core.conductor/
register-action!` triggering an ordinary `play-change`/`assign-algo!`
call at a chosen boundary — conductor actions were always generic, so
nothing new was needed there either). The removed indirection was
specifically "a voice's behavior changing via a side table nobody
watching that voice's own call site would see" — judged not worth
keeping for a capability nothing in the project actually used.

A real, deliberate side effect: a `:PAR`/`#{}` fork's own children, when
untagged, now INHERIT the parent voice's `:algo` (since `fork-voice`
builds each child via `assoc` off the parent, carrying forward anything
not explicitly overridden) — previously an untagged fork always
resolved to identity, since a fresh path had no entry in the shared
table. Consistent with "one immutable key governs the track": an
internal fork boundary alone is not a reason to silently revert to
identity.

The one temporary-override case that genuinely needs a SPAN, not a
whole-voice assignment (`[Form :algo Name]` nested inside an ongoing
`[]` walk, reverting once that span ends) moved from
reassign-then-restore-via-the-shared-table to a LOCAL, immutable-update
shadow of the current voice (`(assoc voice :algo name)`, passed into
the recursive call covering just that span) — no shared state touched
at all, restoration is just returning from that stack frame. Considered
and rejected: a per-voice mutable atom field for this one case — would
have worked mechanically, but reintroduces mutability for a need that's
inherently lexically scoped (a span within one ongoing walk), which
plain immutable-value threading already expresses more directly, with
zero risk of forgetting to restore on an early return/exception.

See `doc/audits/algo-stages.txt` for the full current pipeline traced
stage by stage.

**2026-09-10 — `display`/the play-arg Form grammar moved out of
`core.async-engine` into a new `core.compose` namespace.** Motivated
by an audit finding: `core.async-engine`'s own file mixed real-time
execution (async, voices, MIDI dispatch) with a second, genuinely
engine-free responsibility — the Form-shape grammar (`tagged-form?`/
`split-tag`/`resolve-form-tag`/`par-form?`/`par`/`form-tag+items`/
`peel-group-contexts`) both `play` and `display` needed identically,
plus `display` itself (a fully synchronous preview that never touches
`*engine*`/a voice/`core.async` at all — its own docstring already
said so). Verified, not assumed, that every function in scope had zero
dependency on engine/voice/MIDI state before moving it — `live-repo`/
`build-chain`/`mean-pitch-rank`/`form-pitch-source` all turned out to
depend only on `core.repo`/`core.domain.*`, confirmed by reading each
one rather than inferring from name alone.

Rejected along the way: a genuine **pipeline** shape, where `compose`
would produce some intermediate result and hand it to `engine` to
execute. This was the user's own first mental model, worth naming
because the actual shape is different and the difference matters: two
walkers (`core.async-engine`'s `play-form*`, `core.compose`'s own
`realize-form*`) each recurse through a Form live, on their own,
calling INTO `core.compose`'s small functions at every node/group they
visit — neither one ever hands the other a materialized result. A real
pipeline would mean computing something ahead of time, which conflicts
directly with this project's own long-standing "nothing is
materialized between parse and play" principle (Wave 4-era reasoning,
restated in `core.async-engine`'s own ns docstring) — introducing one
here, for this specific split, would have been a real architectural
regression disguised as a refactor.

Also rejected: keeping `core.async-engine/display` as a thin re-export
(`(def display compose/display)`) to avoid updating every call site.
Would have defeated the point — the whole motivation was making the
namespace boundary honest from the OUTSIDE too, not just internally;
a passthrough would have hidden the real move from every future reader
of a `require` form. Every real call site (tests, `musics.clj`) was
updated to `compose/display`/`compose/par` directly instead.

Deliberately NOT framed as a fourth architectural tier alongside
Material/Sound/The playground (see "Shape of the system" in the main
body) — `core.compose` is a sub-piece of tier 3 (the play-arg
mini-language's own grammar), not a new layer with its own boundary;
`core.wall` and the rest of tier 3 are unaffected by this split.

---

The seven entries below were extracted from `CLAUDE.md`'s own "Repo
state" section on 2026-09-17 (condensed from its "Wave 1" through
"Wave 7" narration, per this file's own stated convention — see the
top of this file). They predate every entry above, so they're appended
here rather than sorted into chronological position; dates are
approximate, read off `git log` for the commits that did each wave's
work, not hand-recorded at the time.

**2026-06-26 — The domain model was rewritten from a mutable, atom-based tree (parent-linked contexts, `Composite` records holding `children-atom`) to a flat, immutable one: a single `repo` map of `id -> container`, contexts with no parent pointer (Wave 1).**
Decided against: keeping the tree-of-atoms model and patching it incrementally.
Why: a parent-pointer/atom-based tree conflates "where a container sits in the piece" with "what its own content is," which breaks the moment the same container id is reachable through more than one path (`\repeat`, a `Reference`) — the atom-in-place model has no clean way to represent that. The flat, id-addressed model makes every lookup explicit (an id plus a `ctx-chain` built by whoever's doing the walking) instead of implicit in a stored pointer. The old model, walker, and engine (`music_domain.clj`, `tree_walker.clj`, `engine/engine.clj`) are gone from disk entirely, not kept dormant.

**2026-07-22 to 2026-07-25 — The single `repo` map became `core.repo`, a versioned/staged store (`id -> tx -> node`, not one current value); `core.conductor` was added as a signal/schedule layer bridging structural boundaries to named actions; `Meter` became a real, computed record wired into context (Wave 2).**
Decided against: keeping a single mutable current-value map with no history, and leaving `Meter` an unwired bare string.
Why: a versioned store is what makes "prepare an edit without disturbing what's currently playing" possible at all — commit lands in history, nothing changes what a live voice reads until something explicitly repoints it. `core.conductor` exists as a generic dispatcher precisely so the engine doesn't need to know what a "scheduled action" is — `signal!`'s event is opaque to it. `Meter` needed to be real (not a string) before Barlow indispensability or per-voice bar tracking could compute anything from it.

**2026-07-28 — Comments and variables became real grammar rules (`Comment`/`VarDef`/`VarRef`), resolved by the walker in the same pass as everything else, replacing text-level pre-processing (`vars.clj`/`pre_parse.clj`, both deleted) that stripped/substituted them before instaparse ever ran (Wave 3).**
Decided against: keeping the pre-parse text-substitution approach.
Why: a confirmed, real bug — any text-shape-changing transform before parsing (a comment collapsing lines, a variable insertion) shifted everything after it, so a parse error's reported line/column stopped matching the file actually written. Parsing the original text end to end removes the cause rather than working around it.

**2026-08-09 — `core.repo/play-tx` stopped being a single pointer live playback continuously re-read; each voice now carries its own `:tx`, redirected one voice at a time via `schedule-tx!` (moved from `core.conductor` into `core.async-engine`) (Wave 4).**
Decided against: keeping one shared `play-tx` pointer for every voice.
Why: a confirmed limitation, not a hypothetical one — a single shared pointer conflated every voice's own cutover timing, so two independently-scheduled, uneven-length parts couldn't each redirect on their own boundary without one flipping the other's still-playing content early. `core.conductor` lost its only dependency on `core.repo` as a direct consequence (`schedule-tx!` needs to know what a voice is; conductor still doesn't).

**2026-08-18 to 2026-08-21 — The engine's fixed `:generation` counter and fixed-size wall-slot array were replaced by a single, unbounded `:voices` map keyed by each voice's own real path; `core.wall` (a per-voice playback-algorithm registry) was added on top of that; the project gained real-time MIDI input (`input.midi`/`input.midi-record`) (Wave 5).**
Decided against: keeping the fixed-size/fixed-slot voice bookkeeping, and staying output-only for MIDI.
Why: a path-keyed, unbounded voice registry is what makes `play`/`play-change`/`play-add` able to coexist and target specific voices by path at all, rather than by a fragile numeric slot. Per-voice playback algorithms needed real voices to hang off of before they could exist; MIDI input was a genuinely separate, additive capability that happened to land in the same wave.

**2026-08-25 to 2026-08-26 — The `play` mini-language and `musics.ebnf` converged on one vocabulary: `[]` always sequential, `#{}`/`(par ...)` always parallel, on both the Clojure-arg side and the text-grammar side — replacing `play`'s earlier `:par`/`:seq` leading-keyword tags and the grammar's earlier, unrelated bracket dialect (Wave 6).**
Decided against: keeping `play`'s own tag-guessing scheme (untagged vector defaulting to `:par` unless told otherwise), and keeping the text grammar as a close LilyPond superset (`\keyword` commands, `@[ ]`/`@{ }` algorithm invocation, `\time`/`\tempo`/`\key`).
Why: harmonizing both sides onto "the collection type alone is the tag" removed guessing entirely — vector is always `:seq`, set is always `:par`, no leading keyword to get wrong. Staying a LilyPond superset stopped being a goal once `input.lilypond-import` became a real, actively-maintained converter in its own right, so the grammar no longer needed to double as one.

**2026-08-28 — `Parallel`'s spelling moved from `#{ }` to `(par ...)`, on both the text grammar and the play-arg mini-language, closing the one gap Wave 6 left behind (Wave 7).**
Decided against: leaving `#{ }` as the only spelling for "these parts play together."
Why: a literal Clojure set can't hold the same value twice (`#{:s1 :s1}` is a reader error, not just discouraged) — `#{ }` inherited that as a pure surface-syntax accident, since nothing about a real `:PAR` container's own duplicate-tolerant vector `:children` ever required set semantics. This mattered concretely for phase-music-style writing (the same part against itself, offset) — `(par :s1 :s1)` is meaningful and was previously inexpressible. `#{ }` still works identically for the common case of naturally-distinct branches; `par` is additive, not a breaking change, and is the only member of the transient-Lisp-call family that's a registrable `Composite`.

---

**2026-09-09 — `configure-preset!`/`*preset-registry*` (a second, separate store so several independent, coexisting presets could be built off one factory) was merged away; `build!` targeting an explicit name covers the same case directly.**
Decided against: keeping a dedicated preset-registry alongside `*algo-registry*`, so "install a factory once, configure named instances of it later" stayed a two-step, two-store process.
Why: once every `build!` call already needs its own explicit target name (`(build! :bright :colorTalea {...})`, `(build! :dark :colorTalea {...})`), several independently-named, independently-hot-swappable results off one factory was already the ORDINARY case, not a special one needing its own second registry — `*algo-registry*` alone covers it. Same commit (`569c83a`) also collapsed the old single-registry, `:kind`-tagged design (plain fns and factories sharing one map) into today's two structurally separate stores (`*algo-factory-registry*`/`*algo-registry*`), and surfaced a real correctness bug along the way: the per-voice look-ahead cache's freshness check compared the captured assignment, which caught a path being reassigned to a different name but missed a hot-swap of the SAME name's own registry entry — fixed by comparing the resolved fn itself (reference equality), catching both cases uniformly.

**2026-09-11 — Every wall-algorithm factory takes exactly `(name params)`, `params` ALWAYS a plain map — not a positional arg list whose shape differs per factory.**
Decided against: leaving factories as `(fn [name & args] -> name)`, each free to define its own positional argument order (the shape `569c83a`, two days earlier, had just introduced).
Why: a uniform `{key value}` params map is what makes a built algo genuinely TOOLABLE — a GUI (or any other generic caller) can render/edit `{key value}` pairs without knowing anything about which factory produced them, which a positional arg list can't offer since the Nth argument's meaning differs per factory. All `algo/` factory files updated to the new shape in the same pass.

**2026-08-15 — A `Leaf`/`Rest`/`Drum` bakes its own `:ctx-chain` at walk time (nearest-first `[Context relative-offset]` pairs); `effective-chain` re-bases each ancestor by `(structural-time - relative-offset)` at resolve time, not by `structural-time` alone.**
Decided against: shifting every baked ancestor uniformly by
`structural-time` alone (no offset) — tried first, as the more obvious
construction.
Why: `sq` returns a container's bare `:children` with none of that
container's own `:context` (`!instrument:`/`!tempo:`/`!mf`/etc.)
attached — a leaf played standalone (`(play (times 12 (sq :verse)))`)
used to resolve against whatever minimal `ctx-chain` the *new*
top-level `play` call built (often just `[ROOT-ctx]`), silently losing
`:verse`'s own values — confirmed live with a mock MIDI receiver:
`(play :verse)` sent `[:program-change 0 32]` correctly,
`(play (times 12 (sq :verse)))` sent `[:program-change 0 0]` (piano)
and velocity 50 (ROOT's raw default, not `!mf`'s). The uniform-shift
alternative broke ramp interpolation for ORDINARY playback instead — a
ramp spanning several leaves collapsed to its start value on every one
of them, since shifting every ancestor to "right now" erases their
relative spacing. Verified live both ways: a `!vol:30<l ... !vol:80`
ramp across 4 notes plays `[30 43 55 68]` normally, and
`(times 2 (sq :verse))` on the same material plays
`[30 43 55 68 30 43 55 68]` — each repeat correctly re-interpolating
fresh from 30, not flattened, not carrying over where the previous
repeat left off. This is safe specifically because a leaf, unlike a
container, is never independently re-referenced by a different path.

**2026-08-27 — `java-reference/`/`julia-reference/`/`kotlin-reference/`/`python-reference/` (prior implementations of this same system in other languages, kept on disk gitignored for cross-checking behavior) were removed entirely, not kept around indefinitely.**
Decided against: leaving the four reference trees in place as permanent cross-check material.
Why: fully surveyed against the current `algo/` tree first, not assumed safe to delete — `algo.common.farey`/`trig`/`scaling`, most of `algo/rhythmic/` (`phase-sieve`/`poly`/`necklace`/`stochastic`/`physical`/`transform`/`sonification`/`constraint`/`fractal-geometric`/`world`), and `algo.melodic.counterpoint` all trace back to one of these four and were ported before removal. Everything else was either already ported (the reference data tables, `algo.indisp.indispensability`, `algo.melodic.melody`, `algo.common.isorhythm`) or superseded architecture with nothing left to port (the old domain model/parser/MIDI engine/GUI `python-reference` was itself ported from, and the old Context/Leaf/Source/Decorator class hierarchy `java-reference`/`kotlin-reference` carried).

**2026-09-22 — `input.forth`/`forth.clj` (the classic-Forth hosted REPL kernel) was removed entirely; `musics.lang` (the Factor-style kernel, see its own ns docstring) is now the sole hosted-DSL REPL language.**
Decided against: keeping both hosted kernels side by side indefinitely, `forth.clj` as a stable fallback alongside `musics.lang`'s continued development.
Why: `musics.lang`'s own header comment had always framed this as the plan — replace `input.forth`'s language core "once this one reaches parity and the user confirms the cutover" — and the user confirmed it directly once `musics.lang` had its own vocabulary system, quotations, `::`-locals, and the full `musics.core` word bridge (including, in the same pass, splitting `core.wall`'s own factory/algo/assignment words out of the shared `musics` vocabulary into their own sibling `algorithms` vocabulary, USE:'d by `scratchpad` by default same as `musics` — see `musics.lang`'s own header comment). `test/forth_test.clj` and `doc/forth-functions.txt` (a hand-maintained word table describing `forth.clj`'s own UPPERCASE/S"-string/bare-bracket-auto-detection conventions specifically) were removed alongside it, since both described code that no longer exists; `doc/musics-course.txt` already covers `musics.lang`'s own conventions as the current, maintained course. `dev/user.clj`'s `(forth/repl!)` nested-REPL alias became `(lang/repl!)`; the `:forth` `lein test` selector (project.clj/CLAUDE.md) was renamed `:lang`, since `musics_lang_test.clj` was the only namespace left carrying that tag once `forth_test.clj` was gone. A handful of comments elsewhere (`src/core/async_engine.clj`, `src/gui/lib/state.clj`) describing this project's own design lineage as inherited from "a Forth-hosted predecessor" were left as-is — genuine, still-true history, not a live code dependency on the removed file.
