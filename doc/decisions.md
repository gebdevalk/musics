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

See `algo-stages.txt` (repo root, untracked) for the full current
pipeline traced stage by stage, if it's still around.

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
