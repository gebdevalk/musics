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

New entries go at the top. `CLAUDE.md` itself is not being retroactively
rewritten to move its existing historical narration here — this file
starts fresh from here forward; existing "Wave N" write-ups in
`CLAUDE.md` stay where they are until whatever they describe is next
touched.

---

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
algorithm (`core.wall/register-wall!`) if per-voice playback reach is
wanted.

**2026-08-29 — Algorithm resolution (`resolve-algo`) stays in `core.async-engine`, not `core.domain.resolve`.**
Decided against: folding wall-algorithm application into
`resolve-event` itself, even though the two now read as obviously
symmetric "resolve" steps in `play-node`.
Why: `core.domain.resolve` deliberately has zero dependency on voices or
`core.wall` (tier 2, "Sound," staying ignorant of tier 3, "the
playground" — see `CLAUDE.md`'s "Shape of the system"). Moving wall
invocation into `resolve-event` would hand tier 2 a tier-3 concept it
has no business knowing about. Named `resolve-algo` instead, living
next to `voice-wall-slot-fn` in the engine, where voice-awareness
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
