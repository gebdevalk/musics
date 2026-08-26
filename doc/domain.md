# Domain Model

The domain model is split across three namespaces (there is no single
`core.domain.music-domain` anymore — that was the old, removed model):

```clojure
(require '[core.domain.context :as c])       ;; Point, Envelope, Context
(require '[core.domain.flat-domain :as d])    ;; Leaf, Rest, Drum, Iterator, Bar, containers
(require '[core.domain.resolve :as r])        ;; resolve-event, locate
```

---

## Interpolation (IP)

Every envelope point carries an **interpolation type** that describes
how its value transitions *forward* toward the next point.

| Keyword        | Curve                                         |
|----------------|-----------------------------------------------|
| `:fixed`       | Hold value — no interpolation                 |
| `:step`        | Hold value, then jump at the next point       |
| `:lin-up`      | Linear ramp (identity easing)                 |
| `:lin-down`    | Linear ramp (identity easing, reversed label) |
| `:smooth`      | S-curve: `t² (3 − 2t)`                       |
| `:ease-in`     | Slow start: `t²`                              |
| `:ease-out`    | Slow end: `1 − (1−t)²`                       |
| `:ease-in-out` | Slow start and end                            |

The IP on the **found point** (the one at-or-before the query time)
governs interpolation.  This is the opposite of many envelope
implementations that use the *next* point's IP.

When an envelope is time-reversed (`env-reverse`), directional IPs
are swapped: `:lin-up` ↔ `:lin-down`, `:ease-in` ↔ `:ease-out`.
Symmetric IPs (`:fixed`, `:step`, `:smooth`, `:ease-in-out`) are
unchanged.

---

## Point

A single value at a moment in time, with an interpolation type.

```clojure
(c/->Point 0.0 0.5 :fixed)     ;; time 0, value 0.5, ip :fixed
(c/->Point 2.0 1.0 :lin-up)    ;; time 2, value 1.0, ramps linearly forward
```

Fields: `time`, `value`, `ip`.

---

## Envelope

A mutable, ordered list of Points stored in an atom.  Thread-safe via
compare-and-swap — no locks needed.

### Construction

```clojure
(c/envelope)                ;; empty envelope
```

### Mutation

```clojure
(c/env-append env 0.0 0.5 :fixed)   ;; append a point
(c/env-append env 2.0 1.0 :lin-up)  ;; another point
```

If the new point's time matches the last point's time, it **replaces**
the last point rather than appending a duplicate.

### Sampling (env-get)

`env-get` returns the interpolated value at a given time.

```clojure
;; Given points: {0.0 0.5 :fixed} {2.0 1.0 :lin-up} {4.0 2.0 :smooth}
(c/env-get env 1.0)   ;; => 0.5  — first point is :fixed, holds value
(c/env-get env 3.0)   ;; => 1.5  — second point is :lin-up, interpolates
```

Rules:

- **Before first point**: returns the first point's value.
- **After last point**: returns the last point's value.
- **Between two points**: finds the left point via binary search.
  The **left point's IP** determines the interpolation curve.
  `:fixed` and `:step` hold the left value with no blending.
  All other IPs compute a weighted blend using the easing function.
- **Non-numeric values**: returned as-is from the left point (no blending).

### Reversal

```clojure
(c/env-reverse env)   ;; new envelope, mirrored in time
```

Produces a new Envelope with points in reverse temporal order.
Directional IPs are swapped so the curve shape is preserved.

---

## Context — no parent pointer, an explicit ctx-chain instead

**This is the one place the model changed most since an earlier draft of
this doc**: a `Context` used to hold a `:parent` pointer and `ctx-value`
walked bottom-up through it. It doesn't anymore. The reason: the same
container (and therefore the same `Context`) can be reached through
*different* enclosing containers if its id is referenced from more than
one place — "what's the enclosing scope" is a property of *how you got
here on this particular visit*, not something that can be baked into the
data itself.

So a `Context` only ever holds its own locally-authored envelope data, and
"enclosing scope" is threaded explicitly as a **ctx-chain** — a plain
vector of `Context`s, nearest-first — built by whatever traversal is doing
the walking (`core.async-engine`'s `build-chain`, or
`core.domain.resolve/locate`), not stored on the `Context` at all.

### Construction

```clojure
(c/context)                                    ;; empty context, no envelopes yet

;; Root context from a map — each value becomes a :fixed point at t=0
(c/context-root {"tempo" 120 "volume" 0.8})
```

### Setting values

```clojure
(c/ctx-append ctx :tempo 2.0 80 :lin-up)
```

Adds a point to the local envelope for the given key. If no local
envelope exists yet, one is created. Never touches any other `Context` —
a `Context` only ever mutates itself.

### Reading values — active-point validity, walked via an explicit chain

```clojure
(c/ctx-value-chain [ctx root-ctx] :tempo 0.0)
```

`ctx-value-chain` takes the chain to search (nearest-first) and looks for
the value of a key at a specific time. At each context in the chain, it
checks whether that context's local envelope contains a **valid point** —
one with `time ≤ query-time` *and* not `:invalid` (see `ctx-invalidate`,
used e.g. by a slur-end reverting a slur-start's forced legato).

If a context's envelope exists but has no valid point at the query time,
that context is skipped and the search continues to the next context in
the chain. This prevents a later instruction from retroactively hiding a
still-valid outer value.

**Example:**

```clojure
(def root (c/context-root {"tempo" 120}))
(def child (c/context))
(c/ctx-append child :tempo 2.0 80 :lin-up)

(c/ctx-value-chain [child root] :tempo 0.0)  ;; => 120
;; child has a tempo envelope, but its only point is at t=2.
;; No point ≤ 0 exists → falls through to root → 120.

(c/ctx-value-chain [child root] :tempo 3.0)  ;; => 80
;; Point at t=2 is ≤ 3 → valid → child's own value used.
```

`:duration` on a `Context` (a plain value, not an envelope) caches that
container's own total duration, stamped once at pop-container time (see
`flat-domain/set-container-duration`), so a traversal can read it in O(1)
without walking back into the repo (`core.domain.resolve/chain-offset`
sums it across a whole chain).

A container also carries `:pitch-sum`/`:pitch-n` (plain top-level keys on
the container itself, not on its `Context`), stamped the same way at the
same pop-container time (`flat-domain/set-container-pitch-stats`) — an
O(1) `mean-pitch` read (`(/ pitch-sum pitch-n)`) is what `core.async-
engine` ranks a `:PAR` fork's own children by (lowest pitch gets the
lowest short track id) — see `CLAUDE.md`'s "Wall: per-voice playback
algorithms" section.

---

## Leaf types

Immutable records representing individual musical events.

### Leaf (pitched note or chord)

```clojure
(d/leaf "c4" ctx 1/4 [60])
(d/leaf "c4" ctx 1/4 [60] :staccato :ff [:mod1] false)
```

Fields: `id`, `context`, `duration` (Ratio), `pitches` (vector of
MIDI ints), `articulation`, `dynamic`, `modifiers` (vector),
`tied` (boolean).

### Rest (silent duration)

```clojure
(d/rest* "r" ctx 1/4)
```

Fields: `id`, `context`, `duration`.

### Drum (unpitched percussion)

```clojure
(d/drum "kick" ctx 1/4 36)
```

Fields: `id`, `context`, `duration`, `program` (MIDI note number).

### Bar (structural marker, zero duration)

```clojure
(d/bar 2)   ;; a `||` -- count is the pipe-count, 1-4
```

Fields: `count`, `duration` (always `0`). Purely structural on disk, but
not inert at playback — `core.async-engine` fires a
`core.conductor` `:mark` signal for each one it hits. See CLAUDE.md's
"Conductor" section.

---

## Iterator

A deferred-expansion wrapper around a source part — used for `(repeat
...)` (`unfold`/`volta`/`tremolo` are all one grammar rule now, a
`repeat-type` value picking the Iterator's own `:type`: `:REPEAT` for
`unfold`/`volta`, `:TREMOLO` for the `tremolo` variant) and similar
constructs that expand at a later stage. Never registered under its own
id in the repo the way a regular container is.

```clojure
(d/iterator :REPEAT "rep" ctx source-part {:count 4 :repeat-type :volta})
```

Fields: `type` (`:REPEAT`, `:TREMOLO`, etc.), `id`, `context`,
`source` (the walked part), `params` (map of expansion hints).

---

## Containers — a flat repo, not a tree of pointers

There is no `Composite` type anymore. A container is a **plain map**:
`{:type :SEQ :id :s1 :context ctx :children [...]}` — no atoms inside it,
and no parent pointer. `:children` holds a mix of inline leaf values
(Leaf/Rest/Drum/Bar/Iterator) and keyword ids that must be resolved
against a `repo` map (`id -> container`); a container never holds another
container inline, only by id (see CLAUDE.md's "Domain model" section for
why this is a hard invariant, not just a common case).

Type keywords: `:SEQ` (sequence), `:PAR` (parallel), `:DATA`, `:ROOT`,
plus `:CONTEXT` for a named context/envelope definition (registered in
`repo` so it can be referenced, but never appended to any container's own
`:children` — it's a definition, not musical content). `:UNIT`,
`:ATOMIC_ALGO`, and `:ELEMENT_ALGO` still exist as type keywords in the
domain-model builder (`flat_core_builder.clj` still carries their auto-id
prefixes and, for `:UNIT`, its own context-less-container handling), but
nothing in the current grammar/walker can produce one anymore — `Unit`,
`AtomicAlgo`, and `ElementAlgo` were removed as grammar constructs
entirely (see `musics.ebnf`'s own header comment), so these three are
unreachable dead paths now, not normal container types you'd encounter —
`flat_domain.clj`'s own `print-structure` bracket table already notes
this directly.

### Operations

```clojure
(d/container? x)             ;; true if x is a container map
(d/children repo container)  ;; children, keyword ids resolved via repo
(d/duration repo container)  ;; total duration -- sum for :SEQ, max for :PAR
(d/part-duration part)       ;; O(1) -- reads a pre-stamped :duration,
                              ;; no repo traversal (see below)
(d/describe repo id)         ;; abbreviated structural report
(d/print-structure repo id)  ;; pretty-print using the surface grammar's brackets
```

`duration` recurses through `repo` and is the source of truth, computed
once at container-build time (`set-container-duration`, called at
pop-container) and cached directly on the container's own `Context`
(`:duration`) — or as a bare top-level `:duration` key for a `:UNIT`,
which has no `Context` of its own to cache it on. `part-duration` reads
that cached value in O(1) instead of re-walking `repo`; it's what the
live engine calls on every leaf/bar/iterator it plays.

---

## Transforms

Functions that return **a function** (for use with `transform`), not
something you call directly on a leaf — the one thing worth double-
checking if you're following an older example:

### mutate

```clojure
(d/mutate leaf :dynamic :ff :tied true)
```

Returns a new record with the given fields replaced. Called directly
(no wrapper function).

### transform

```clojure
(d/transform leaf (d/transpose 7) (d/times 2))
```

Applies functions left-to-right, threading the part through each. Called
directly, but its arguments are the function-returning helpers below.

### transpose

```clojure
((d/transpose 7) leaf)   ;; pitches shifted up 7 semitones
```

`(d/transpose 7)` returns a function; apply it to a leaf (directly, or
via `transform`) to get the shifted copy.

### times

```clojure
((d/times 2) leaf)    ;; duration × 2
```

### to-tuplet

```clojure
((d/to-tuplet 3/2) leaf)   ;; duration ÷ 3/2 = duration × 2/3  (triplet)
((d/to-tuplet 5/4) leaf)   ;; quintuplet
```

LilyPond-style: the factor is **notes / replaced** (e.g. 3/2 means
"3 notes in the time of 2"). The duration is divided by the factor.

### to-triplet

```clojure
((d/to-triplet) leaf)   ;; shorthand for ((d/to-tuplet 3/2) leaf)
```

### dotted

```clojure
((d/dotted) leaf)   ;; duration × 3/2
```

---

## Predicates

```clojure
(d/leaf? x)        ;; true if Leaf
(d/rest? x)        ;; true if Rest
(d/drum? x)        ;; true if Drum
(d/bar? x)         ;; true if Bar
(d/container? x)   ;; true if a container map (see above)
(d/iterator? x)    ;; true if Iterator
(d/part? x)        ;; true if Leaf, Rest, Drum, container, or Iterator
```
