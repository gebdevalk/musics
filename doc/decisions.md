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
mid-performance via this project's own versioned-repo/`:tx`-redirect
mechanism (see "Session, the versioned repo, and playback" in
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
