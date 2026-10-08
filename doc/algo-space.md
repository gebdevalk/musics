# The dimensions of algo space

How to get a grip on algo space: its vocabulary, its rungs, its
independent dimensions, and how they map onto musical space. The
counts come from the registry as it stood on 2026-10-05: 149 algos.

Some of this is decided (sections 1–4) and some is still analysis
(sections 5–9). Section 10 lists what is still open.

## 0. Decided since (2026-10-07/08), to fold into the sections below

These override the sections below where they differ. "Operation"
replaces "arrow": every algo and tool is an operation, with a
**signature** (what it takes and gives) and an **arity** (nullary …
variadic).

### How an operation is described

Four things to **choose** by, and a set of **properties** that the
system derives or checks. They are not eight independent dimensions:
kind decides which actions exist, and most properties follow from the
signature and the action.

| Choose by | Asks | Values |
|---|---|---|
| **material** | what it takes and gives | the matrix below |
| **kind** | does it bring an idea? | **algo** (it has a manner) or **tool** (it has none) |
| **action** | what it does, within its kind | algos: generate, vary, elaborate, continue, combine, expand, learn; tools: make, form, value, selection, analyse, convert, assemble, access |
| **manner** | which kind of idea (algos only) | open: construction, chance, process, chaos, physical model, rewriting, learning, search, idiom, data |

| Property | Asks | Values | Use |
|---|---|---|---|
| **knowledge** | how much it reads of the values | none, numbers, meaning | the naturality law; which material a tool accepts |
| **appetite** | how much input one output needs | one value, a neighbourhood, everything (code: `:value :shape :whole`) | an *everything* appetite after an *endless* yield is refused |
| **yield** | how much it gives | one, finite, endless | the same check |
| **effect** | which part of a stream it changes | value, order, selection, length, arrangement | detail for the algos that vary, elaborate or continue; for tools the action says it |
| **variability** | per **param**: may it change along the music | fixed, stepwise, continuous | whether a stream may feed that param |

Material, kind, action and manner are what a person browses (the faces
of the cube). The properties are what the system relies on: they must
be true, so they are derived where possible (action and knowledge
largely from the signature) and tested where not (appetite, yield).
Manner and the vary/elaborate/continue split are judgements, assigned
by hand.

### Material, in the grammar's words

`musics.ebnf` has Primitive = Ratio | Float | Int and Atom = Pitch |
Duration | Articulation …; algo space uses the same words.

| Half | Rung | Kinds | In the domain | Arranged as |
|---|---|---|---|---|
| base | **primitive** | number, point | — | stream, grid |
| base | **primitive with a role** | pulse, onset, weight, stroke, index, model | — | stream, grid |
| domain | **atom** | duration, pitch, volume, articulation, instrument | a leaf's fields | stream |
| domain | **leaf** | note, chord, rest, drum | `:LEAF` `:REST` `:DRUM` | — |
| domain | **line** | leaves in succession | `:SEQ` | — |
| domain | **parallel** | lines together | `:PAR` | — |

Base material exists in algo space only; domain material is what musics
writes and plays. A bridge converts a primitive into an atom, `zip`
assembles atoms into a leaf, `parts` lines into a parallel. Raw
material is the atoms, end material the leaves. In the code `:part`
keeps its name. Still to place: nested material (a stream of streams,
from `partition` or `split-at`).

### Algos

An **algo** brings an idea, a manner: take it away and the music loses
that idea.

| Action | Does | Algos |
|---|---|---|
| **generate** | from nothing, or from a set outside time | `euclid`, `lorenz`, `normal` and the other samplers (their distribution is the idea), `drums`, `constrained` (a melody from a scale) |
| **vary** | the same thing, changed | `emi` (Cope's EMI: random flips, swaps, inserts, deletes, by similarity), `mutate` (each bit flipped with a probability), `oblique` (an Oblique Strategies transform), `tilt` and `power` (a meter's weights reshaped by adherence: obedient, indifferent, syncopated), `pocket` (notes laid back by their place in the beat) |
| **elaborate** | the same thing, filled in | `polations` and `infra`/`inter`/`ultra` (Slonimsky: tones inserted before, between, after the principal tones), `tuplets` (onsets subdivided recursively) |
| **continue** | the same thing, extended | `rnn` (a small recurrent network extends a seed rhythm) |
| **combine** | two of the same kind into one, by an idea | `crossover` (two parent rhythms recombined), `tiling` (two patterns laid over a span) |
| **expand** | one into several voices | `counterpoint`, `species`, `duet`, `phases` |
| **learn** | a habit from material | `markov-train` |

### Tools

A **tool** brings no idea: it makes, shapes, changes, selects or
converts what it is given.

| Action | Acts on | Tools | Musically |
|---|---|---|---|
| **make** | nothing: a sequence without an idea | `scale` (root plus offsets), `range`, `repeat`, `iterate` | — |
| **form** | positions, never values | `cycle`, `times`, `take`, `drop`, `take-last`, `reverse`, `rotate`, `shuffle`, `then`, `interleave`, `partition`, `split-at`, `sputter` | repetition, ostinato, fragmentation, retrograde, rotation, succession, phrasing |
| **value** | each value | `map`, `rescale`, `transpose`, `invert`, `stretch`, `reductions` | transposition, inversion, augmentation, register |
| **selection** | values, as a test | `filter`, `remove`, `take-while`, `distinct`, `choose-n`, `choose-from`, `pick` | elimination, choosing |
| **analyse** | material, into the structure behind it, without an idea | `iso-strength` (a rhythm's strength per pulse) | — |
| **convert** | the material's kind | the bridges | interpretation |
| **assemble** | several into one | `zip`, the blend steps, `seq`, `par`, `parts` | the note, the line, the texture |
| **access** | one element or part | `first`, `last`, `nth`, `row`, `part` | — |

- **Form** acts on positions (`take`: the first n), **selection** on
  values (`filter`: those that pass a test).
- **Form tools are the class the naturality law holds for**: they never
  read values, so they commute with value-by-value bridges.
- **Chance is a property of a tool, not a manner**: `shuffle` is a form
  tool, `choose-n` and `choose-from` selection tools (they draw from a
  given collection). A sampler (`normal`, `cauchy`) is an algo: its
  distribution is the idea. The line is thin, and worth showing a user
  (samplers and `choose-from` near each other when browsing chance).
- **Knowing what a pitch is isn't an idea**: `transpose`, `invert` and
  `stretch` are value tools. `tilt` and `power` are algos: adherence is
  a model of how strongly a rhythm obeys its meter, where `transpose` is
  a standard operation with one meaning.
- **Learning and analysing** both go back down from material to the
  structure behind it; `markov-train` learns a habit (an idea),
  `iso-strength` computes strengths (none).

### Doubts kept in view

- The four "choose by" aspects are a hierarchy (kind, then action) plus
  facets, not a grid in which every combination exists.
- Manner and action are judgements on about 150 operations; tests can
  check material, appetite and yield, not these, so they need curating.
- Material typing already narrows choice sharply (the builder offers
  only what fits); the other aspects mainly help browsing.

### Also explored

- The **cube** as the interface to the catalogue: one face per aspect
  to choose by, turning keeps the selection, an edge between two faces
  is a cross-table whose empty cells are gaps.
- Grids and step sequencers: `doc/grid.md` (parked).

## 1. What an algo is

Strip away names and categories, and every algo is the same kind of
thing: a typed **operation**. It takes zero or more streams (its
children) and some settings (its params, held in the tctx), and gives
one stream:

```
algo : stream τ₁ × … × stream τₙ × params  →  stream τ
```

What it takes and what it gives is its **signature**; how many streams
it takes is its **arity**:

| Arity | Takes | Examples |
|---|---|---|
| **nullary** | nothing | the sources: `euclid`, `normal`, `scale` |
| **unary** | one stream | tools and bridges: `cycle`, `pulses->durations` |
| **binary** | two streams | `zip`, `parts`, `crossover` |
| **ternary**, **quaternary**, … **variadic** | three, four, any number | none yet (section 8) |

A tree is operations composed. Seen this way, algo space is a small
**category**:
- the objects are the types (`:pulse`, `:pitch`, `:leaf` …);
- the operations connect them;
- composition is nesting;
- the tools (`cycle`, `take` …) are the operations that exist for every
  object at once.

## 2. Vocabulary

Algo space uses the parts of speech, and Clojure's own naming habits:

| Part of speech | Is | Examples |
|---|---|---|
| **noun** | a type: what flows | pulse, pitch, leaf, line |
| **adjective** | context: a quality of what flows | dorian, in 7/8, at 90 bpm |
| **verb** | an algo: what is done | see below |
| **adverb** | a param, in the tctx: how it is done | `:k 3`, `:density 0.5`, `:semitones 7` |

Each kind of algo is named the way Clojure names that kind of
function:

| Kind | Clojure's habit | Named | Examples |
|---|---|---|---|
| **generator** (makes) | after what it makes or how, like `range`, `iterate` | its method, a noun | `euclid`, `lorenz`, `counterpoint` |
| **tool** (transforms) | a verb, like `map`, `filter` | a plain verb | `cycle`, `take`, `shuffle`, `rescale` |
| **bridge** (converts) | an arrow, like `str->int` | `from->to`; singular for one value, plural for a stream | `degree->pitch`, `pulses->durations` |
| **blend step** (adds) | — | `+` a material | `+volume`, `+articulation` |
| **combination** | a noun | what it builds | `zip`, `parts` |

The arrow is the bridge's verb: `pulses->durations` reads "turn pulses
into durations", and it names both ends, which a single verb couldn't.

## 3. The rungs

What flows is one of four rungs, and the top rung comes arranged:

| Rung | Is | Types |
|---|---|---|
| **primitive** | a value, as in Java | int, double, ratio, string, keyword; `:number`, `:point` (a vector of them) |
| **atom** | a primitive with a role | `:pulse`, `:onset`, `:weight`, `:stroke`, `:model`, `:index`, a degree (a `:number` read against a key) |
| **parameter** | an atom with a musical meaning: something a note has | `:duration`, `:pitch`, `:volume`, `:articulation`, `:instrument` |
| **leaf** | parameters bound together: a note, chord, rest or drum | `:leaf` (the domain's `:LEAF`, `:REST`, `:DRUM`) |

Leaves are arranged:
- in **lines**, in succession: what a `:SEQ` holds;
- in a **parallel**, lines sounding together: what a `:PAR` holds.

In the code, a tree of type `:leaf` carries one line, and a tree of
type `:part` carries a parallel (each element one line).

Atoms and parameters are arranged too, with their own words:
- a **stream** is atoms or parameters in succession: a pitch stream, a
  number stream;
- a **grid** is atoms aligned position by position on a common pulse
  frame, in one or more **rows**, like a step sequencer. Row *i*,
  position *n* sounds with every other row's position *n*.

A rhythm pattern is a grid, even with a single row (euclid's pattern is
a one-row pulse grid). A polyrhythm is a pulse grid of several rows. A
row can hold any atom, so weights per pulse are a weight grid. Each
grid content has its own type (`:pulse-grid`, `:weight-grid`), and a
grid of one row has the type of its row (`:pulse`, `:weight`).

Each step up the rungs has its own kind of operation:

| Step | What happens | Operation |
|---|---|---|
| primitive → atom | a value gets a role | generators |
| atom → parameter | a role gets a musical meaning | **bridges** |
| parameters → leaf | they are bound into a note | `zip`, the blend steps |
| leaves → line | they follow one another | succession (missing: `then`) |
| lines → parallel | they sound together | `parts`, `+part` |

**Musical analogue.** Xenakis split musical structure into *outside
time* (scales, sieves, sets: relations with no before and after),
*temporal* (time structures on their own) and *in time* (realised
music). The rungs make the same journey:
- a scale or a sieve is outside time;
- a pulse grid is a temporal structure;
- a line of leaves is music in time.

A **bridge is an act of interpretation**: it gives an atom a meaning it
didn't have. That is why bridges are one-way. Interpretation decides
something (this degree is a pitch, in D dorian) that can't be undone
without knowing the decision.

## 4. Where things live, and how a tree reads

### Namespaces

| Namespace | Holds |
|---|---|
| `musics.trees` | the namespace you **compose** in: every algo referred by its plain name, with `clojure.core`'s clashing versions (`map`, `take`, `cycle` …) excluded there. `user` stays ordinary Clojure. |
| `musics.algo.tool` | the tools, by plain verb: `cycle`, `take`, `shuffle`, `map`, `filter`, `rescale`, `stretch`, `choose-n`, `sputter`, … A tool works within one type for any type: `:in [:any] :out :same`. |
| `musics.algo.bridge` | the bridges |
| `musics.algo.tree.*` | the machinery: registry, builder, live |
| generator namespaces | stay organised by origin and method (`rhythmic.world`, `random`, `melodic.slonimsky` …), where code that belongs together lives together |

`rescale` is the tool that maps one number range onto another; `scale`
stays the source of a key's pitches.

### Grouping by what is made

Grouping by output belongs to namespaces in Clojure, not to a keyword
argument: `(grid :euclid)` would rebuild a multimethod on the surface.
So `musics.trees` offers **namespaces named after what comes out**,
generated from the registry at load time (it knows every algo's
`:out`), with nothing maintained by hand:

| Alias | Holds generators that give |
|---|---|
| `grid/` | atoms aligned on a pulse frame, one row or more: `grid/euclid`, `grid/polyrhythm`, `grid/indisp` |
| `stream/` | the other atoms and parameters: `stream/lorenz`, `stream/normal`, `stream/markov` |
| `line/` | a line of leaves: `line/djembe` |
| `parallel/` | a parallel: `parallel/drums`, `parallel/counterpoint` |

The noun says what you get and the name says how. The params, the
further adverbs, live in the tctx as always, not in the call:

```clojure
(grid/euclid)        ; a pulse grid, euclid's way   -- {:k 3 :n 8} in the tctx
(stream/lorenz)      ; a number stream, the lorenz way
```

### Reading order

Trees read inside out. Clojure's threading macro turns them into a
sentence, and it works with tree constructors as they are, because a
node's first argument is its child:

```clojure
(zip (pulses->durations (euclid)) (cycle scale))   ; inside out

(-> (grid/euclid)                                   ; as a sentence
    pulses->durations
    (zip (cycle scale)))
```

## 5. The dimensions

The vocabulary and the rungs name things. The dimensions below
describe how algos differ. They're the axes a builder, `show-algos` or
a core.logic query can sort and filter by.

### D1. Rung: what flows

Section 3. A type's rung is its position from primitive to leaf, and its
arrangement (stream, grid, line, parallel).

### D2. Shape: what the operation does to the rungs

From the signature alone, every algo falls into one of these shapes:

| Shape | Signature | Count | Examples |
|---|---|---|---|
| **Source** | nothing → τ | 84 | euclid, normal, lorenz, scale, drums, tala |
| **Endo** | τ → τ | 26 | cycle, shuffle, transpose, emi, tilt, pocket |
| **Interpretation** (up a rung) | atom → parameter, or primitive → atom | 19 | the bridges, threshold, axis, konnakol, swing, metric-mod, data-rhythm, trend-rhythm, markov-gen |
| **Analysis** (back down) | τ → the structure behind τ | 2 | markov-train (pitch → model), iso-strength (pulse → weight) |
| **Expansion** (one → several) | τ → grid or parallel | 4 | counterpoint, species (cantus → parallel), duet, phases (pattern → grid) |
| **Combination** | τ × σ → ρ | 12 | zip, the blend steps, parts, +part, infra/inter/ultra, crossover, tiling |
| **Projection** (several → one) | grid or parallel → τ, or τ → one value | 3 | row, part, pick |

Only interpretation deserves the name *bridge*.
- **Analysis** goes back down, from material to the structure behind
  it. `markov-train` learns a habit from a melody, and `markov-gen`
  reinterprets it.
- **Expansion** turns one into several.

The leaf principle forbids crossing between parameters (pitch to
duration). It doesn't forbid analysis.

**Combination splits in two:**
- **Product**: `zip` and the blend steps bind *different* parameters
  into one leaf. A leaf *is* a product of parameters.
- **Algebra within a type**: `infra`/`inter`/`ultra` (pitch × pitch),
  `crossover` and `tiling` (pulse × pulse) combine two values of the
  *same* type. Each type has its own algebra:
  - pulse grids form a Boolean algebra (union, intersection,
    complement);
  - weights add and multiply;
  - pitches add intervals;
  - durations concatenate.

  Only scraps of these exist.

### D3. Aspect: which part of a stream an endo touches

| Aspect | Changes | Keeps | Tools now | Musical operation |
|---|---|---|---|---|
| **Value** | each element's value | order, count | `map`, `rescale`, `transpose`, `stretch`, `tilt`, `power` | transposition, inversion, augmentation/diminution, register |
| **Order** | the sequence | the elements (a multiset) | `shuffle`, `deep-shuffle`, `necklace`, `bracelet` | permutation, retrograde, rotation |
| **Membership** | which elements remain | order | `filter`, `only`, `choose-n` | elimination, fragmentation, selection |
| **Extent** | length; finite or endless | the elements, cyclically | `cycle`, `take`, `sputter` | repetition, ostinato, truncation |
| **Arrangement** | rows, lines | the elements | `parts`, `+part`, `part`, `row` | texture |

The other endos (`emi`, `mutate`, `oblique`, `tuplets`, `rnn`,
`polations`, `constrained`, `pocket`) touch several aspects at once,
the way a musical variation does.

### D4. Genericity: how much the operation needs to know

| Level | Reads | Works on | Examples |
|---|---|---|---|
| **Structural** | nothing of the values | any type | `cycle`, `take`, `shuffle`, `choose-n`, `sputter`, `only` |
| **Numeric** | values as numbers | any numeric type | `rescale`, `map`, `filter` (via a fn) |
| **Semantic** | what the type means | one type | `emi` (onsets in a grid), `transpose` (pitches, leaves), `tilt` (a weight distribution) |

The structural and numeric levels are the tools.

It comes with a **law**. A structural operation commutes with every bridge
that works value by value (`:works :value`):

```
(cycle (degrees->pitches xs))  =  (degrees->pitches (cycle xs))
```

That is naturality, in category-theory terms: two trees that differ
only in where a structural tool sits are the same tree. A builder can
offer one of them, a test can check the law, and you can put the tool
where it reads best. It holds only for value-by-value bridges:
`pulses->durations` reads its neighbours, so shuffling before it isn't
shuffling after it.

### D5. Locality: how much of the stream one output needs

| Locality | One output needs | Endless input? | Marked now |
|---|---|---|---|
| **Value** | one input element | yes | `:works :value` (5) |
| **Shape** | its neighbours, or a running state | yes | `:works :shape` (2) |
| **Whole** | the whole (finite) stream | no | `:works :whole` (4) |

Sources have a matching scale:
- **a draw per value** (the samplers, `:repeat`);
- **a process with state** (walks and chaos, `:pull`);
- **a whole pattern at once** (euclid, sieve, counterpoint).

Locality decides what can play **live and endless**: a whole-stream
operation after an endless source never returns.

### D6. Extent: how much an algo gives

One value (`pick`), a finite pattern (a cycle of n pulses), or an
endless stream (samplers, walks, `cycle`). `cycle` and `take` move along
this dimension, just as bridges move up the rungs. A finite pattern is
usually meant as a period: an ostinato, a talea.

### D7. Principle: where the novelty comes from

The one dimension the signature can't tell you:

| Principle | Algos (examples) | Musical technique |
|---|---|---|
| **Construction** | euclid, sieve, fibonacci, primes, cantor, dragon, golden, necklaces, all-interval, cfrac, bits, modular, indisp, scale | constructive and serial composition |
| **Chance** | normal, uniform, the `*-emph` and `int-*` samplers, poisson, shuffle, choose-n | aleatoric music |
| **Process** | walk, glide, biased-walk, chain, markov-rhythm | process music |
| **Chaos** | logistic, logistic-grid, henon, lorenz, noise | dynamical systems |
| **Physical model** | bounce, pendulum, rain, heartbeat, birdsong, cloud | nature simulation |
| **Rewriting** | lsys-rhythm, lsys-melody, grammar, tuplets | generative grammars |
| **Learning** | markov-train, markov-gen, rnn | style imitation |
| **Search** | csp, genetic, constrained, counterpoint, species | rule-based composition (Fux, Jeppesen) |
| **Idiom** | tala, theka, bell, african, djembe, drums, hemiola, konnakol | stylistic convention |
| **Data** | text-rhythm, trend-rhythm, data-rhythm | sonification |

Today's categories (`rhythmic`, `random`, `melodic`) mix D1 and D7:
`random` is a principle, and `rhythmic` and `melodic` describe what is
made.

### D8. Rate: setting or stream

A param is a value held for a run; a child is a stream with a value per
element. A param is a stream frozen to one value, so letting a number
param take a stream (euclid's `:k` changing bar by bar) is
**modulation**: a fixed setting versus an envelope, as in musics text's
context (`doc/algo-context.md`).

## 6. Which dimensions are independent

| Dimension | Independent? | Recorded now |
|---|---|---|
| D1 rung | yes | `:in`/`:out` |
| D2 shape | derived from the signature and the rungs | — (computable) |
| D3 aspect | yes, for endos | — |
| D4 genericity | yes | partly: `:in [:any]` marks a tool |
| D5 locality | yes | `:works`, on 11 algos |
| D6 extent | mostly | — |
| D7 principle | yes | — |
| D8 rate | per param | — |

An algo's coordinates are its signature (which gives rung and shape),
aspect, genericity, locality, extent and principle:

| Algo | Signature | Shape | Aspect | Genericity | Locality | Extent | Principle |
|---|---|---|---|---|---|---|---|
| euclid | → pulse grid | source | — | semantic | whole | finite | construction |
| normal | → number | source | — | — | value | endless | chance |
| lorenz | → point | source | — | — | shape | endless | chaos |
| degrees->pitches | number → pitch | interpretation | — | semantic | value | as input | — |
| markov-train | pitch → model | analysis | — | semantic | whole | one | learning |
| cycle | τ → τ | endo | extent | structural | value | endless | — |
| shuffle | τ → τ | endo | order | structural | whole | as input | chance |
| transpose | pitch → pitch | endo | value | semantic | value | as input | — |
| zip | duration × pitch → leaf | combination (product) | — | semantic | value | shorter input | — |
| counterpoint | pitch → parallel | expansion | — | semantic | whole | finite | search |
| row | grid → row | projection | arrangement | structural | value | as input | — |

## 7. Algo space against musical space

| Musical space | Algo space |
|---|---|
| parameters: pitch, duration, dynamics, articulation, timbre | the parameter rung |
| a note: one value of each parameter | a leaf: the product (`zip`, blend steps) |
| a melodic line, a voice | a line |
| texture: monophony, polyphony | lines and parallels; expansion and projection |
| a step sequencer's pattern | a grid with rows |
| outside time → in time (Xenakis) | the rungs; bridges realise |
| transposition, inversion, augmentation | D3 value |
| retrograde, permutation, rotation | D3 order |
| repetition, ostinato, fragmentation | D3 extent and membership |
| note, phrase, form | D5 locality, D6 extent |
| compositional technique and style | D7 principle |
| key, meter, tempo | adjectives: context |
| fixed settings versus envelopes | D8 rate |
| analysis and listening | operations back down the rungs |

## 8. What the dimensions show is missing

1. **Succession.** Parallels have `parts`, but lines have no `then`
   that plays one after another, and no `interleave` or `alternate`.
   The play args have both (`[]`, `#{}`); the tree only the parallel
   one.
2. **Order tools.** No `reverse` (retrograde), no general `rotate`.
   Rotation exists only for pulses, inside `necklace`.
3. **Value tools.** No inversion (reflection about a pitch or a value)
   beside `transpose` (translation) and `stretch` (multiplication).
4. **Each type's algebra.** Pulse grids have no union, intersection or
   complement; weights no sum or product; durations no concatenation.
5. **Taking a leaf apart.** No `leaf → duration` or `leaf → pitch`,
   the inverse of `zip`. Transform trees need it. It doesn't break the
   leaf principle: it takes a note apart, it doesn't turn pitch into
   duration.
6. **Instrument.** No source and no bridge.
7. **Locality.** Recorded on 11 algos, so the builder can't warn about
   a whole-stream operation after an endless source.
8. **Over rows and lines.** Nothing applies one subtree to *each* row
   of a grid; named picks work, but don't scale to n rows.
9. **Variadic `zip` and `parts`.** No operation takes more than two
   streams. A leaf binds up to five parameters, but `zip` takes two and
   the rest come one blend step at a time (`+volume`, `+articulation`,
   `+instrument`); a parallel holds any number of lines, but `parts`
   takes two and `+part` adds one more. A variadic `zip` (durations,
   pitches, volumes, …) and a variadic `parts` would say each in one
   operation, and could make `+part`, and perhaps the blend steps,
   unnecessary. Tree constructors have a fixed `:in` count, so this
   needs variadic support in the registry and the builder.

## 9. How to get a grip on it

1. **Record the independent coordinates as metadata:** `:works` on
   every algo, `:principle` from D7, `:aspect` for endos. Shape
   and genericity are computed from the signature.
2. **Group by what is made, filter by the rest.** The generated output
   namespaces (`grid/`, `stream/`, `line/`, `parallel/`) are the
   grouping; principle, shape and locality are filters, and in
   core.logic they're queries ("a chaotic number stream", "a structural
   tool that changes order").
3. **Laws as tests:** naturality; `(take (cycle xs))` is periodic;
   `take` and `filter` are idempotent; no path between two parameters
   except through a leaf.
4. **Fill the holes in section 8 one at a time**, each a cell with a
   known address.

## 10. Still open

1. **Picking a row.** One `row` picker, with a checker rule like
   `:same` that maps a grid type to its row type (`:pulse-grid` →
   `:pulse`), or a picker per grid type.
2. **Grid rows of parameters** (a grid of pitch rows), or atoms only.
3. **Skipping rungs.** Some generators give parameters straight away
   (`scale` gives pitches, `split` durations). Allowed, or must
   everything pass through an atom?
4. **Analysis in trees.** Are operations back down the rungs (taking a
   leaf apart, learning) first-class?
5. **Grouping in the builder.** The output namespaces as the pane's
   groups, with the other dimensions as filters, or something simpler.
6. **The principle vocabulary.** The ten in D7, or fewer.
7. **Which holes first** (section 8). Succession and taking leaves
   apart look like the two that unlock the most music.
8. **Modulation (D8).** Should params take streams? The largest change
   here, and it overlaps with context.
