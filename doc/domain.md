# Domain Model

The domain model lives in `core.domain.music-domain` and defines every
type the pipeline produces between parsing and MIDI output.

```clojure
(require '[core.domain.music-domain :as d])
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
(d/point 0.0 0.5)          ;; time 0, value 0.5, ip :fixed (default)
(d/point 2.0 1.0 :lin-up)  ;; time 2, value 1.0, ramps linearly forward
```

Fields: `time`, `value`, `ip`.

---

## Envelope

A mutable, ordered list of Points stored in an atom.  Thread-safe via
compare-and-swap — no locks needed.

### Construction

```clojure
(d/envelope)                ;; empty envelope
(d/envelope-from [{:time 0 :value 0.5 :ip :fixed}
                  {:time 2 :value 1.0 :ip :lin-up}])
```

### Mutation

```clojure
(d/env-append env 0.0 0.5 :fixed)   ;; append a point
(d/env-append env 2.0 1.0 :lin-up)  ;; another point
```

If the new point's time matches the last point's time, it **replaces**
the last point rather than appending a duplicate.

### Querying

```clojure
(d/env-duration env)   ;; time of the last point, or 0.0
(d/env-empty? env)     ;; true if no points
```

### Sampling (env-get)

`env-get` returns the interpolated value at a given time.

```clojure
;; Given points: {0.0 0.5 :fixed} {2.0 1.0 :lin-up} {4.0 2.0 :smooth}
(d/env-get env 1.0)   ;; => 0.5  — first point is :fixed, holds value
(d/env-get env 3.0)   ;; => 1.5  — second point is :lin-up, interpolates
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
(d/env-reverse env)   ;; new envelope, mirrored in time
```

Produces a new Envelope with points in reverse temporal order.
Directional IPs are swapped so the curve shape is preserved.

---

## Context

A hierarchical key → envelope store.  Each Context has an optional
`parent`; lookups walk the chain bottom-up.

### Construction

```clojure
(d/context)              ;; orphan context (no parent)
(d/context parent-ctx)   ;; child context

;; Root context from a map — each value becomes a :fixed point at t=0
(d/context-root {"tempo" 120 "volume" 0.8})
```

### Setting values

```clojure
(d/ctx-append ctx :tempo 2.0 80 :lin-up)
```

Adds a point to the local envelope for the given key.  If no local
envelope exists, one is created.

### Reading values — active-point validity

```clojure
(d/ctx-value ctx :tempo 0.0)
```

`ctx-value` searches bottom-up for the value of a key at a specific
time.  At each context level, it checks whether the local envelope
contains a **valid point** — one with `time ≤ query-time`.

If the local envelope exists but has no valid point at the query time,
it is skipped and the search continues to the parent.  This prevents
a future instruction from retroactively hiding a still-valid parent
value.

**Example:**

```clojure
(def root (d/context-root {"tempo" 120}))
(def child (d/context root))
(d/ctx-append child :tempo 2.0 80 :lin-up)

(d/ctx-value child :tempo 0.0)  ;; => 120
;; Child has a tempo envelope, but its only point is at t=2.
;; No point ≤ 0 exists → falls through to parent → 120.

(d/ctx-value child :tempo 3.0)  ;; => 80
;; Point at t=2 is ≤ 3 → valid → local value used.
```

---

## Leaf types

Immutable records representing individual musical events.  Clojure
records are immutable by default — no `frozen=True` needed.

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
(d/make-rest "r" ctx 1/4)
(d/rest* "r" ctx 1/4)          ;; alias
```

Fields: `id`, `context`, `duration`.

### Drum (unpitched percussion)

```clojure
(d/drum "kick" ctx 1/4 36)
```

Fields: `id`, `context`, `duration`, `program` (MIDI note number).

---

## Composite

A mutable container of child parts (Leaf, Rest, Drum, Composite,
Iterator).  Children are stored in an atom — thread-safe mutation
without locks.

### Construction

```clojure
(d/composite :SEQ "phrase" ctx)              ;; empty
(d/composite :SEQ "phrase" ctx [leaf1 leaf2]) ;; with initial children
```

Type keywords: `:SEQ` (sequence), `:PAR` (parallel), `:ALGO`
(algorithmic), `:SCORE`, `:QLIST` (quoted list), `:LIST`.

### Operations

```clojure
(d/composite-children c)         ;; snapshot as vector
(d/composite-duration c)         ;; sum of child durations
(d/composite-count c)            ;; number of children
(d/composite-seq c)              ;; lazy seq over snapshot
(d/composite-append c part)      ;; add at end
(d/composite-insert c idx part)  ;; insert at index
(d/composite-replace c idx part) ;; replace at index (returns old child)
(d/composite-remove c part)      ;; remove first occurrence
(d/composite-to-string c)        ;; pretty-print: "[ .. .. ]"
```

---

## Iterator

A deferred-expansion wrapper around a source Composite — used for
`\repeat`, `\tremolo`, and similar constructs that expand at a later
stage.

```clojure
(d/iterator :REPEAT "rep" ctx source-composite {:count 4 :repeat-type :volta})
```

Fields: `type` (`:REPEAT`, `:TREMOLO`, etc.), `id`, `context`,
`source` (the walked Composite), `params` (map of expansion hints).

---

## Transient

An operator list that exists during tree walking but is not part of
the final musical domain.  Collects items temporarily before they are
resolved into domain objects.

```clojure
(d/make-transient :OPERATORS "ops" ctx)
(d/transient-append t item)
(d/transient-children t)
```

---

## Score

A Score is a Composite with type `:SCORE`.  The root context provides
global defaults (tempo, volume, timbre, etc.).

```clojure
(d/make-score root-ctx)           ;; empty score
(d/make-score root-ctx part)      ;; score wrapping a part
```

When a part is added, its context parent is set to `root-ctx` so that
all global defaults are inherited.

---

## Transforms

Functions that return modified copies of leaf types.

### mutate

```clojure
(d/mutate leaf :dynamic :ff :tied true)
```

Returns a new record with the given fields replaced.

### transform

```clojure
(d/transform leaf (d/transpose 7))
```

Applies functions left-to-right, threading the part through each.

### transpose

```clojure
((d/transpose 7) leaf)   ;; pitches shifted up 7 semitones
```

Returns a function (for use with `transform`).

### times

```clojure
(d/times leaf 2)    ;; duration × 2
```

Multiplies the duration by a factor.

### to-tuplet

```clojure
(d/to-tuplet leaf 3/2)   ;; duration ÷ 3/2 = duration × 2/3  (triplet)
(d/to-tuplet leaf 5/4)   ;; quintuplet
```

LilyPond-style: the factor is **notes / replaced** (e.g. 3/2 means
"3 notes in the time of 2").  The duration is divided by the factor.

### to-triplet

```clojure
(d/to-triplet leaf)   ;; shorthand for (to-tuplet leaf 3/2)
```

### dotted

```clojure
(d/dotted leaf)   ;; duration × 3/2
```

---

## Predicates

```clojure
(d/leaf? x)       ;; true if Leaf
(d/rest? x)       ;; true if Rest
(d/drum? x)       ;; true if Drum
(d/composite? x)  ;; true if Composite
(d/transient? x)  ;; true if Transient
(d/iterator? x)   ;; true if Iterator
(d/part? x)       ;; true if Leaf, Rest, Drum, Composite, or Iterator
```
