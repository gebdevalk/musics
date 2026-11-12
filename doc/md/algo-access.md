# How material is accessed: the survey

The rule (decided 2026-11-12, `decisions.md`): **every access is by time or by
next**. A producer of material -- an algorithm, a half product, a finished
seq -- is read one item after another, and a voice reads several in step with the
beat. This page says what the algorithms of `musics.algo` do now and what each
class would have to become. It was made by reading the code and the `:algo`
metadata of 146 functions, not by running each; the classes are a guide to the work.

## The vocabulary

| access | what it is | state |
|---|---|---|
| **by time** | a function of the beat: a context, a tctx value, an envelope | none |
| **by next** | the next item after this one; a lazy seq is the model | a cursor |
| **by index** | a pure function of the index: `sine-fn`, `polation-fn`, a pattern's `(mod i n)` | none |

By index is not a third kind of access: it is a capability some producers have,
and a voice only ever moves forward, so "next" is enough for a consumer. The
duration stream (skeletons) is the clock: its durations sum to the beat, and the
other streams are read one position per skeleton.

Each function declares what it needs of its input (`:works` in `bridge.clj`):
**value** (an item on its own, any stream), **shape** (a bounded look-ahead or
look-behind) or **whole** (all of it, so a finite input).

## What the algorithms are now

| class | where | access today | to be lazy |
|---|---|---|---|
| **Pure builders of a finite grid** (about 60) | `rhythmic.pattern`, `sieve`, `meter`, `world`, `decompose`, `indispensability`, `data`, parts of `nature` | an eager vector of 0/1, weights or onsets; the same for the same arguments | periodic: read by `(mod i n)`, nothing to do. Not periodic: read by next from the vector |
| **Random builders of a finite grid** | `rhythmic.process`, parts of `nature`, `feel`, `drums`, `melodic.melody` (`markov-gen`, `constrained`), `melodic.serial/row`, `harmonic.walking-bass` | eager, drawn from the shared rng atom | counter-based random (see below) |
| **Draws** (about 25) | `numeric.random` | one number per call from `default-rng`, an atom | `value(seed, i)`: pure and seekable |
| **Samplers that carry state** (9) | `numeric.walk` (5), `numeric.markov/chain`, `chaos.logistic`, `henon`, `lorenz` | a closure over atoms; `pull` calls it n times | a lazy seq from `iterate`: the cursor is the state, and a seed starts it. Not seekable |
| **Index functions** (12) | `numeric.trig` (`sine-fn` and the other `-fn`), `create-fn`, `polation-fn` | pure; `polation-fn` goes round at its period | already right |
| **Melodic builders** (about 10) | `melodic.slonimsky`, `pitch`, `serial` | eager vectors; `polation-at` and `-fn` are the index forms | as the grids |
| **Bridges** (22) | `bridge.clj` | `:value` (12), `:shape` (5, with `pul>tied`), `:whole` (5), as declared; lazy already (`map`) | `pul>tied` and `pulse->skeleton` are the lazy duration producers |
| **Leaf and seq transforms** | `common.gate`, `reshape`, `split`, `rotate`, `transient-ops`, `zfilter` | finite lists of leaves | `map` over a stream of leaves for per-leaf ones (`transpose`); a window for the order ones |
| **Search** | `counterpoint.jeppesen`, `imitation` | a bounded search, its own seeded rng atom and a step budget | `:whole`; made once, not per beat |

## Side effects, flagged

The rule is that a function used as a voice or an index function is pure. These are
not:

- **The shared rng atom** (`numeric.rng/default-rng`), drawn from by `numeric.random`
  and everything that calls it. What is heard depends on the order of draws, so two
  voices, a restart or a look-ahead that asks twice change the result. This is the
  one that matters.
- **Closures over atoms** by design: `walk`, `markov/chain`, `logistic`, `henon`,
  `lorenz` (`:pull`). Each call moves the state, so they must not be asked twice for
  the same item; `pull` asks each once, in order.
- **Local state that does not escape**: `pattern/cantor`'s atom, `progression` and
  `walking-bass` volatiles (text formatting), `clam`'s trace, `jeppesen`'s budget and
  own rng. Pure from outside; they can stay.

## What follows

1. **Counter-based random** (`numeric.counter`, built): a draw `i` of a seed is a hash of the
   two (SplitMix64), so a random stream is a pure function of its index. `numeric.random`
   keeps its atom for one-off use (`shuffle` now, `choose-n`, the distributions).
2. **A lazy shuffle** (built): `counter/shuffled` per pass of a finite pool, `shuffled-windows`
   per window of a stream. mu's `[?]` and `choose [ ]` as they play use it too
   (`compose/play-order`, seeded per voice by `compose/voice-seed`).
3. **Samplers as lazy seqs** (built): `logistic-seq`, `henon-seq`, `lorenz-seq`, `markov/chain-seq`,
   `walk/walk-seq`. The closures `walk`, `markov/chain` and the chaos maps stay for `pull`.
4. **Skeletons as the duration element** everywhere (a ratio is a skeleton with no tie): started
   (`pul>tied`, `durations` keep ties), not everywhere.

Still on the shared atom: the distributions and picks of `numeric.random`, and every algorithm built
on them (`rhythmic.process`, `nature`, `melody`, `walking-bass` …).
