# Other libraries for musics

Clojure libraries and namespaces, besides core.logic (see
[core-logic.md](core-logic.md)), that musics could use, ranked by how
much they would help.

Status: proposal. Nothing has been added to `project.clj`.

---

## Worth adding

### 1. `org.clojure/test.check`: property-based tests

This is the biggest win. test.check generates random inputs and checks
that a stated property holds for every one of them, instead of a few
hand-picked cases. musics already has several properties that should
always hold:

- **Parser round trip.** Parse text, write it back with `part->mus`,
  parse it again, and you get the same domain.
- **Spelled pitches** (from core-logic.md):
  - `(sp (:n x) (:a x) (:o x))` always equals `x`, so `:m` is never stale.
  - Going up an interval and back down returns the same pitch:
    `(+interval (+interval x i) (mapv - i))` equals `x`.
  - Every spelling of a sound has the same `:m`.
- **The algo catalog.** Every algo gives its declared `:out` for *any*
  settings within its declared `:min`/`:max`/`:choices`, not only its
  defaults. `algo_catalog_test` checks defaults only. The ranges for
  the generators already exist in each param spec.

It is a test-only dependency (`:profiles {:test ...}`), so it costs
nothing at runtime.

### 2. `org.clojure/math.combinatorics`

This library produces lazy permutations, combinations, subsets,
partitions and selections. Several algorithms build these by hand:

- rhythm necklaces and Vuza canons (`algo.rhythmic.necklace`)
- Slonimsky permutations (`algo.melodic.slonimsky`)
- all-interval series (`algo.rhythmic.constraint`)
- "every way to fill a bar with these note values", which is integer
  partitions

It is a small official library, and it works well with core.logic:
combinatorics lists the candidates, core.logic filters them by
constraints.

### 3. `clojure.math`: built in, no new dependency

Part of Clojure since 1.11. It gives plain Clojure functions for the
many `Math/abs`, `Math/round` and `Math/floorDiv` calls across `algo/`
and avoids reflection warnings on them. This is a cleanup, not a new
feature.

---

## Maybe

### 4. fastmath (generateme)

fastmath has a large set of distributions, Perlin/simplex noise,
interpolation and easing curves, and signal helpers. The noise and
easing could feed the `noise` bridge and the envelope curves. Against
it:

- It is a heavy dependency, and version 3 has been in alpha for a long
  time.
- The seeded xorshift RNG (`algo.random.core`) must stay in charge of
  randomness for reproducibility (`seed_test`).

So either borrow the ideas, or wrap only its noise functions and drive
them from `algo.random`.

### 5. malli

The param specs (`{:type :min :max :default :choices}`) already work as
a schema language, and `algo.tree.registry` validates them. The main
thing malli would add is generating test data from specs for
test.check, which can also be written directly from the existing
specs. Not worth a migration.

### 6. criterium (dev only)

Benchmarking. Useful for measuring how long the sender thread or
`resolve-event` takes per note against the 100 ms lookahead.

---

## Not worth adding

### Leipzig / Overtone's pitch namespaces

Both are well-designed music libraries, but they are older and their
pitch, scale and chord models overlap with `common.music-elements`.
The spelled-pitch proposal in core-logic.md already goes further. They
are worth reading for ideas, not adding as dependencies. musics
already depends on `overtone/midi-clj` for MIDI I/O, which is
unaffected.

---

## Suggested order

1. Add test.check alongside the spelled-pitch work, since that work's
   invariants are exactly what property tests catch best.
2. Switch to `clojure.math` whenever a file in `algo/` is next touched.
3. Bring in math.combinatorics when porting the necklace or Slonimsky
   code, or with the core.logic rhythm work.

---

## Sources

- [ctford/leipzig](https://github.com/ctford/leipzig)
- [Overtone on GitHub](https://github.com/overtone)
- [fastmath readme (3.0.0-alpha)](https://cljdoc.org/d/generateme/fastmath/3.0.0-alpha7/doc/readme)
- [fastmath.random](https://generateme.github.io/fastmath/fastmath.random.html)
- [clojure/math.combinatorics](https://github.com/clojure/math.combinatorics)
