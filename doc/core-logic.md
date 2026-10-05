# core.logic in musics

What `clojure.core.logic` could do for this project, where it pays, and
where it does not. It also covers spelled pitch values
(`{:n "c" :a 0 :o 4 :m 60}`), which core.logic relates to MIDI numbers.

Status: proposal. The code below is a sketch. It has not been run yet:
Maven was blocked from the sandbox this was written in, so try it in a
REPL before trusting it.

---

## 1. Summary

- **Pitches stay MIDI numbers.** Note names are a concern of parsing
  and writing notes only; the domain model and playback carry MIDI
  numbers plus a Key. A note following the key it is played in already
  works that way (`core.domain.resolve/rekey`, `el/rekey`), and so does
  `\transpose`'s spelling in the transposed key. Spelled values are
  computed when something writes notes (export, recorded MIDI) or needs
  an interval's quality (counterpoint), never stored on a Leaf.
- **core.logic's best fit is the relation between the two forms.** One
  MIDI number has several spellings (61 is `c#4`, `db4` or `b##3`).
  Choosing the right one depends on the key and the neighbouring notes.
  A relation that runs in both directions (spelling → MIDI, MIDI → every
  spelling) is exactly what core.logic is good at.
- **Its second fit is the three generators that already do constraint
  search by hand:** `algo.rhythmic.constraint/constraint-satisfaction-rhythm`,
  `algo.melodic.melody/constraint-melody` and `algo.melodic.counterpoint/generate`.
  All three can silently return results that break their own
  constraints, or search far more than they need to. core.logic gives
  backtracking and pruning, and returns either a correct answer or `nil`.
- **It is not a fit** for the engine, the conductor, envelopes, the
  parser, or anything that runs in real time. Search time is unbounded,
  so it belongs at generation time, outside the playback path.

---

## 2. Spelled pitches

### Representation

`{:n :a :o :m}`: note name (the letter), accidental, octave, MIDI number — the same order as the written name, `C#4` → `{:n "c" :a 1 :o 4 :m 61}`.

```clojure
{:n "c" :a 0  :o 4 :m 60}   ; C4
{:n "c" :a 1  :o 4 :m 61}   ; C#4
{:n "e" :a -1 :o 4 :m 63}   ; E&4
{:n "b" :a 2  :o 3 :m 61}   ; B##3
```

- **A map, not a keyword/value vector.** `[:oct 4 :p "c"]` is
  order-sensitive (`(= [:oct 4 :p "c"] [:p "c" :oct 4])` is false), and
  core.logic unifies vectors by position. A map compares and unifies by
  key, and core.logic's `featurec` can match part of one
  (`(featurec x {:m 61})`).
- **`:a` is an integer**, -2 to 2 (`&&`, `&`, natural, `#`, `##`), so
  intervals and transposition never parse strings.
- **`:m` is derived, stored for convenience.** It is always
  `12 * (o + 1) + pc(p) + a` (C4 = 60). It is what the engine,
  `:pitch-sum`, ranges and velocity code read, so a spelled result
  feeds them through `(map :m ...)`. Because it is a function of the other three keys, it
  doesn't change equality: two equal spellings always have the same
  `:m`, and enharmonics share `:m` but differ in `:n`/`:a`. The risk is
  an `(assoc x :o 5)` that leaves `:m` stale, so nothing should `assoc`
  into a pitch directly; everything goes through one constructor (and
  `+interval`):

  ```clojure
  (defn sp [nm a o] {:n nm :a a :o o :m (+ (* 12 (inc o)) (letter-pc nm) a)})
  ```

  A test that checks `(= x (sp (:n x) (:a x) (:o x)))` on everything
  the helpers and algos produce catches any stale `:m` early.
- **One canonical shape.** Spelled pitches end up as map keys (Markov
  transition tables, §4), so all four keys are always present (`:a 0`
  written out, not left off) and `:n` is always the same type (all
  strings or all keywords). A plain map, not a `defrecord`, because a
  record is never `=` to a map with the same entries.

### Where it is used

Nowhere in the domain model: a Leaf keeps `:pitches` (MIDI numbers) and
the Key its letters were read against (`:key`), and playback moves a
note to another key from those two (see CLAUDE.md, "Grammar"). The map
above is the shape helpers return when a spelling is needed on the
way out:

| Need | How |
|---|---|
| Recorded MIDI, written as text | `midi->spelling` spells from the key, using the relation in §3, instead of always using sharps |
| Export (LilyPond / ABC / GUIDO) | Spell each pitch from its leaf's `:key` (`el/key-pitch-name`), with §3's ranked choice for chromatic pitches: in key first, then fewest accidentals, then the direction of the line |
| Intervals with their quality | `[steps semis]` from two spelled pitches, so `c`–`eb` (minor third) and `c`–`d#` (augmented second) stay distinct; counterpoint rules need that |
| Markov states and intervals | §4 |

---

## 3. The spelling relation

Make the relation a table. There are 35 letter/accidental pairs and 8
octaves, so 280 rows. A table relation is easy to check by hand, runs
in both directions, and avoids core.logic's finite-domain (`fd`)
constraints, which only work on non-negative integers and so don't
handle negative accidentals well.

```clojure
(ns algo.logic.pitch
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :refer :all]
            [clojure.core.logic.fd :as fd]))

(def letter-order ["c" "d" "e" "f" "g" "a" "b"])
(def letter-pc    {"c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11})

(defn sp [nm a o] {:n nm :a a :o o :m (+ (* 12 (inc o)) (letter-pc nm) a)})

(defn dpos-of
  "Diatonic position, 7 per octave: the letter-axis counterpart of :m."
  [{:keys [n o]}]
  (+ (* 7 o) (.indexOf letter-order n)))

(def spellings
  "[pitch dpos] for every spelling in octaves 1-8."
  (vec (for [o (range 1 9), p letter-order, a (range -2 3)
             :let [x (sp p a o)]]
         [x (dpos-of x)])))

(defn spelledo
  "x is a spelled pitch sounding MIDI m."
  [x m]
  (fresh [p a o d]
    (== x {:n p :a a :o o :m m})
    (membero [x d] spellings)))
```

Both directions from the same relation:

```clojure
(run* [m] (spelledo (sp "b" 2 3) m))   ;=> (61)
(run* [x] (spelledo x 61))
;=> ({:n "b" :a 2 :o 3 :m 61} {:n "c" :a 1 :o 4 :m 61} {:n "d" :a -1 :o 4 :m 61})
```

### Intervals

```clojure
(defn intervalo
  "hi is steps letters and semis semitones above lo
   (steps 0 = unison, 2 = third, 4 = fifth, 7 = octave)."
  [lo hi steps semis]
  (fresh [m1 m2 d1 d2]
    (membero [lo d1] spellings) (spelledo lo m1)
    (membero [hi d2] spellings) (spelledo hi m2)
    (fd/in steps semis (fd/interval 0 127))
    (fd/+ d1 steps d2)
    (fd/+ m1 semis m2)))

(def c4 (sp "c" 0 4))
(run* [q] (intervalo c4 q 2 3))   ;=> ({:n "e" :a -1 :o 4 :m 63})  minor third
(run* [q] (intervalo c4 q 1 3))   ;=> ({:n "d" :a 1 :o 4 :m 63})   augmented second
(run* [s] (fresh [q] (intervalo c4 q s 3)))   ;=> (1 2 3)  every way to spell 3 semitones
```

Diatonic transposition then needs no extra code:
`(run 1 [q] (intervalo note q 1 2))` is "up a major second", and the
spelling comes out right.

### Spelling in a key

The ordered rules (in key, then few accidentals, then anything) are a
`conda`. `conda` commits to the first clause whose first goal succeeds.

```clojure
(defn spell-ino
  "sp spells m, preferring the key's own degrees (key-degrees is a
   seq of [letter accidental] pairs, e.g. from common.music-elements/key and
   key-letter-offset), then a single sharp, then a single flat, then
   anything."
  [key-degrees x m]
  (conda
    [(fresh [p a o] (== x {:n p :a a :o o :m m})
       (membero [p a] key-degrees) (spelledo x m))]
    [(fresh [p o] (== x {:n p :a 1 :o o :m m}) (spelledo x m))]
    [(fresh [p o] (== x {:n p :a -1 :o o :m m}) (spelledo x m))]
    [(spelledo x m)]))
```

The "sharp before flat" order should come from the key's signature sign
(or from whether the line is going up or down), not be fixed. That is a
detail of how the `conda` clauses are put together. `midi_record`'s
spelling and `respell-fn` could both be built on this.

---

## 4. Markov chains on spelled pitches

`markov-train` / `markov-generate` already work with any value that has
`=` and a hash, so canonical spelled maps (§2) work with them unchanged.
What needs deciding is **what the state is**. Each choice is a single
function applied before training, and each trades how faithful the
model is against how much data it needs:

| State | Function | Gains | Costs |
|---|---|---|---|
| Full spelled pitch | `identity` | Most faithful: `c#4` and `db4` are different states, which is correct, since they resolve differently (`c#` up to `d`, `db` down to `c`) | Sparsest: every octave and every spelling is its own state |
| Sound only | `:m` | What the current MIDI-number model does, as a fallback for tiny corpora | Loses spelling; the output has to be spelled again |
| Spelled pitch class | `#(select-keys % [:n :a])` | Octave-free, so it needs far less data | Octave chosen at output time (nearest to the previous note) |
| Interval | `->interval` on consecutive pairs | Independent of transposition: train in D, generate in F. Keeps diatonic meaning, so a minor third and an augmented second are different states | Needs a start pitch, and drift has to be checked (below) |

The interval view is where spelling pays off most. With MIDI, an
interval is only a semitone count, so the generated line has to be
spelled again afterwards (the guessing `respell-fn` does today). With
spelled pitches, the interval carries its letter distance and the
output is already correctly spelled:

```clojure
(defn ->interval [a b]
  [(- (dpos-of b) (dpos-of a)) (- (:m b) (:m a))])   ; signed [steps semis]

(defn +interval [x [steps semis]]
  (let [d (+ (dpos-of x) steps)
        m (+ (:m x) semis)
        o (Math/floorDiv d 7)
        p (letter-order (mod d 7))]
    (sp p (- m (* 12 (inc o)) (letter-pc p)) o)))

(+interval (sp "e" -1 4) [1 2])   ;=> {:n "f" :a 0 :o 4 :m 65}
(+interval (sp "c" 0 4) [-1 -1])  ;=> {:n "b" :a 0 :o 3 :m 59}

;; train on intervals, realize from any start pitch
(def model (markov-train (mapv ->interval notes (rest notes)) 2))
(reductions +interval start (markov-generate model 15))
```

Stacking intervals can drift into `:a` values outside -2..2 (a
triple sharp) or outside the pitch range. Both are rejections, and
rejecting a step means backtracking, which leads to the next point.

### Constrained Markov generation (core.logic)

`markov-generate` walks forward and takes whatever comes next. It cannot
also promise "end on the tonic", "stay between C3 and G5" or "no
triple sharps". Writing the transition table as a relation and searching
with backtracking gives exactly those guarantees. Every step is still a
transition the model actually saw; the search only rules out paths that
can't satisfy the constraints. This is the "Markov constraints" idea
(Pachet & Roy) at small scale.

```clojure
(defn constrained-markov
  "n states after start (order 1), each one a transition seen in
   training, with (ok? i state) true for every step and the last state
   in finals. nil when no such walk exists. Candidates are shuffled with
   algo.random (duplicates in a transition list act as weights), so the
   result is varied but reproducible from the seed."
  [transitions start n ok? finals]
  (letfn [(walko [state i out]
            (if (= i n)
              (== out ())
              (fresh [nxt more]
                (conso nxt more out)
                (membero nxt (rand/shuffle (get transitions [state] [])))
                (project [nxt]
                  (if (and (ok? i nxt) (or (< i (dec n)) (finals nxt)))
                    (walko nxt (inc i) more)
                    fail)))))]
    (first (run 1 [q] (walko start 0 q)))))
```

For order > 1 the state is the last `order` items, carried through the
recursion the same way. It works on any of the three state views above.
For the interval view, `ok?` checks the realized pitch: run `+interval`
along the path and reject any `:a` outside -2..2 or any pitch out of
range. This also covers what `constraint-melody` + `cadence-constraint`
are trying to do (§5), but with learned transitions in place of a
scale.

### Fitting it into the tree registry

`markov-train` declares `:in [:pitch]` and `markov-gen` declares
`:out :pitch`, so the tree's type check would reject spelled input.
Either add port types `:spelled` and `:intervals` and let the `:model`
record which one it was trained on, or register spelled variants
(`markov-train-spelled`, ...) next to the existing ones. Either way, add
the adapter to `:pitch` (`:m`) so a spelled result can feed
every existing node.

Dead ends get more frequent with sparser spelled states. When
`markov-generate` reaches a state with no transitions, it jumps to a
random state seen in training and plays all of it, so the join
is a transition the training data never had. `constrained-markov`
backtracks out of dead ends instead of jumping.

---

## 5. The existing constraint generators

### `constraint-satisfaction-rhythm`: full enumeration, no pruning

It tries 0 and then 1 at every position and only checks the predicates
once a pattern is complete. That is 2^n patterns, which is why its
`:length` tops out at 16. With `fd`, constraints prune while the search
runs. Most rhythm constraints are naturally about gaps or onset
positions, not 0/1 cells:

```clojure
(defn fd-sumo [vars total]
  (if (empty? vars)
    (== total 0)
    (fresh [r]
      (fd/in r (fd/interval 0 total))
      (fd/+ (first vars) r total)
      (fd-sumo (rest vars) r))))

(defn distinct-gap-rhythms
  "Onset gaps for n-onsets onsets in n-pulses pulses: every gap
   different and at least min-gap pulses (an all-interval rhythm)."
  [n-onsets n-pulses min-gap]
  (let [gaps (repeatedly n-onsets lvar)]
    (run* [q]
      (== q (vec gaps))
      (everyg #(fd/in % (fd/interval min-gap n-pulses)) gaps)
      (fd/distinct gaps)
      (fd-sumo gaps n-pulses))))

(defn gaps->grid [n gaps]
  (let [ons (set (butlast (reductions + 0 gaps)))]
    (mapv #(if (ons %) 1 0) (range n))))

(map #(gaps->grid 16 %) (take 3 (distinct-gap-rhythms 4 16 2)))
```

The same shape handles "fill a bar of 16 sixteenths with five distinct
note values" (`fd/in` over `(fd/domain 1 2 3 4 6 8)`), a fixed number
of onsets, onsets required on given pulses, and similar constraints.
`all-interval-rhythm`, which its own docstring calls "not a true
all-interval series", becomes exact.

### `constraint-melody`: greedy, so it can break its own constraints

When no scale note satisfies every constraint, it picks any scale note.
With `(max-leap-constraint scale 2)` and `(cadence-constraint 16 0)`, if
the 15th note is more than two degrees from 0, the last note is random
and the cadence is lost without any warning. Greedy search can't look
ahead. A relational version backtracks, so the cadence is always
reached or the result is `nil`. The existing `fn [melody note] -> bool`
constraints can be reused through `project`, so the current constraint
library carries over as it is.

### `counterpoint/generate`: greedy

1. **Consonances.** `consonant-interval?` accepts `#{0 3 4 7 8 9}`
   (perfect and imperfect), so the parallel-fifth and parallel-octave
   checks do the policing of the perfect ones.
2. **Greedy fallback.** The ns comment already says this: when no
   candidate satisfies the rules, it takes the nearest pitch anyway. A
   backtracking search over the same candidates removes that.
3. **Semitone intervals.** It cannot tell a third from an augmented
   second, or a sixth from a diminished seventh. Spelled pitches fix
   that (§3).

Sketch of first species against a fixed cantus firmus:

```clojure
(def perfect   #{[0 0] [4 7]})             ; [steps mod 7, semis mod 12]
(def imperfect #{[2 3] [2 4] [5 8] [5 9]})

(defn vertical [lo hi]
  (let [s (- (:m hi) (:m lo))]
    (when (pos? s)
      [(mod (- (dpos-of hi) (dpos-of lo)) 7) (mod s 12)])))

(defn ok? [i n lo hi prev]
  (let [v (vertical lo hi)]
    (and v
         (if (or (zero? i) (= i (dec n))) (perfect v) (or (perfect v) (imperfect v)))
         (not (and prev (perfect v) (= v (:v prev))))                   ; no parallel perfects
         (or (nil? prev) (<= (Math/abs (- (:m hi) (:m (:note prev)))) 7)))))

(defn first-species
  "Up to k counterpoints above cf (a vector of spelled pitches), drawn
   from candidates (spelled pitches in key and range)."
  [k cf candidates]
  (let [n (count cf)]
    (letfn [(cpo [i cp prev]
              (if (= i n)
                (== cp ())
                (fresh [p more]
                  (conso p more cp)
                  (membero p candidates)
                  (project [p]
                    (if (ok? i n (nth cf i) p prev)
                      (cpo (inc i) more {:note p :v (vertical (nth cf i) p)})
                      fail)))))]
      (run k [cp] (cpo 0 cp nil)))))
```

Here core.logic mostly supplies the search: the rules are plain Clojure
predicates inside `project`. That is fine. The gain is backtracking and
a guaranteed result, and new rules (contrary motion into a perfect
interval, leap recovery, approach to the cadence) are one more `and`
clause rather than changes to a hand-written search loop.

---

## 6. Choosing connectable algos

Done (2026-10-02): `algo.logic.tree`, with `find-algos`, `how`, `feeds`,
`why-not`, `examples` and `surprise`, and the builder typing its draft
through it. It differs from the sketch below where running it showed
the need: `:any` outputs are constants in searches, `how` checks type
reachability (`reacho`) before searching trees, and `fill` is the
builder's own `fits?`.

The tree builder already dims algos that don't fit the active slot
(`algo.tree.builder/fits?`). It checks a single connection: does this
algo's `:out` match what this slot wants? Treating the registry's
`:in`/`:out` declarations as facts and running core.logic over them
answers four questions the current check can't.

### What the current check misses

1. **`:same` is a type variable, but it's treated as a fixed type.**
   `builder/produces` reads `:same` as "my declared first `:in`". For an
   algo declared `:in [:any] :out :same`, that is `:any`, so it fits
   every slot. Its child hole is then typed from the declared `:in`,
   which is `:any` again (`slot-type`). So the builder accepts, say, a
   `:pulse` source under a `:same` node that sits in a `:pitch` slot.
   Only `algo.tree/check!` rejects the result, once the tree is built.
   With unification, `:same` is one logic variable shared by the node's
   output and its first child. Placing the node in a `:pitch` slot
   binds that variable to `:pitch`, so the hole below asks for
   `:pitch` too, however many `:same` nodes are stacked in between.
2. **Dead ends.** An algo can fit the slot while one of its own inputs
   is a type that no algo produces, or produces only within too many
   levels. A recursive search can say "this fits and can be completed
   within *k* levels", and the builder can dim the rest.
3. **Bridges.** "I have a `:pulse` and need `:leaf`, what connects
   them?" is a path search through the bridges (`pulses->durations`,
   `degrees->pitches`, `threshold`, ...). The builder could offer
   these as suggestions.
4. **Random valid trees.** `run k` over the same relation, with
   candidates shuffled by `algo.random`, gives "surprise me" trees that
   always type-check and are reproducible from the seed.

### The relation

```clojure
(ns algo.logic.tree
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :refer :all]
            [algo.tree.registry :as reg]
            [algo.random :as rand]))

(defn- type-term [t] (if (= t :any) (lvar) t))      ; :any = unconstrained

(defn algoo
  "short is a registered algo whose inputs have types ins (a list) and
   whose output has type out. Each call makes fresh type variables, so
   the same algo used twice in one tree is typed independently."
  [short ins out]
  (or* (for [[s {:keys [in] o :out}] (rand/shuffle (seq (reg/algos)))]
         (let [ins* (map type-term in)
               out* (if (= o :same) (or (first ins*) (lvar)) (type-term o))]
           (all (== short s) (== ins ins*) (== out out*))))))

(declare childreno)

(defn treeo
  "tree is a complete tree, at most depth levels deep, producing ty.
   A tree is (short child ...). When from is given, a leaf may also be
   the placeholder :input, standing for something of type from that
   you already have."
  ([depth tree ty] (treeo depth tree ty nil))
  ([depth tree ty from]
   (conde
     [(if from (all (== tree :input) (== ty from)) fail)]
     [(if (zero? depth)
        fail
        (fresh [short ins kids]
          (algoo short ins ty)
          (conso short kids tree)
          (childreno (dec depth) kids ins from)))])))

(defn childreno [depth kids ins from]
  (conde
    [(emptyo ins) (emptyo kids)]
    [(fresh [t ts k ks]
       (conso t ts ins) (conso k ks kids)
       (treeo depth k t from)
       (childreno depth ks ts from))]))
```

Literals and keyword params are left out on purpose. Both count as
`:any` in `algo.tree/out-type`, so with them every slot could always be
filled, and "completable" would mean nothing. The search only counts
completion by real algos.

### Queries the builder can ask

```clojure
;; algos for a :pitch slot that can be completed within 3 levels
(distinct (run* [s] (fresh [ins kids]
                      (algoo s ins :pitch)
                      (childreno 2 kids ins nil))))

;; type inference through :same: what the hole under a :same node must be
(run 1 [t] (fresh [ins] (algoo :cycle> ins :pitch) (firsto ins t)))

;; bridges: trees producing :leaf from a :pulse you already have
;; (keep only the ones that actually use :input)
(->> (run 50 [tr] (treeo 3 tr :leaf :pulse))
     (filter #(some #{:input} (flatten %)))
     (take 5))

;; a random tree producing :leaf, up to 4 levels deep
(first (run 1 [tr] (treeo 4 tr :leaf)))
```

### As a help and tutorial function

The same relation answers the questions a newcomer has while building a
tree. Each answer is a small, real tree that can be run right away, so
help and examples come from the registry itself and never go out of
date when an algo is added.

| Question | Function (sketch) | Answer |
|---|---|---|
| How do I get from X to Y? | `(t/how :pulse :leaf)` | The smallest trees from X to Y, with each algo's `:doc` |
| What can I do with this? | `(t/feeds riff)` | Algos whose input accepts this tree's output type |
| What can go here? | `(t/fill tree path)` | Algos for a hole, completable ones first |
| Why is this dimmed? | `(t/why-not d :euclid)` | "euclid gives :pulse, this slot wants :pitch", plus the shortest bridge between the two |
| Show me this algo in use | `(t/examples :euclid)` | The smallest complete trees containing it, run with defaults |

(`feeds`, not `next`: `algo_catalog_test` forbids names that shadow
`clojure.core`.)

**Smallest first.** core.logic searches depth-first, so a plain `run`
can return a deep tree before a shallow one. For help, search depth 1,
then 2, then 3 (iterative deepening), so the first answers are the
simplest:

```clojure
(defn how
  "Up to n of the smallest trees turning a `from` into a `to`."
  [from to n]
  (->> (range 1 5)
       (mapcat (fn [d] (run n [tr] (treeo d tr to from))))
       (filter #(some #{:input} (flatten %)))
       distinct
       (take n)))

(how :pulse :leaf 3)   ; trees shown as (algo child ...), :input = your grid
```

**"Why not" is a bridge search.** core.logic only reports *that* a
query failed, not why. But the reason a dimmed algo doesn't fit is
always the same pair: the type it gives and the type the slot wants.
So `why-not` states that pair and then runs `how` from one to the
other. The answer is useful even when no bridge exists ("nothing turns
a :pulse into :pitch within 3 levels").

**Self-writing examples.** `t/examples` runs each found tree with
default settings (`t/run`) and shows the first few values, like a
cookbook recipe. `scripts/algo-cookbook.clj` could use it to propose
recipes for algos the 47 recipes don't cover yet. A person still picks
and annotates the good ones.

**A guided exercise mode.** Take a random valid tree, remove one node,
and ask for the missing piece. `fill` checks the answer and gives the
`why-not` hint when it's wrong. Seeded through `algo.random`, so a
lesson is the same every time.

**Where it surfaces.** At the REPL as `t/how` and friends; in the
builder window as a "?" on a dimmed algo (`why-not`) and a
"suggest" for the active hole (`fill`); and in `build-tree :repl` as
a `?` command. `(assist :pulse :pitch)` (`musics.core`) hands a pair
of types to `how`; the rest of `assist` asks `core.assist`, the same
kind of relation over REPL actions instead of algos.

### Integration

- `builder/fits?` and `builder/categories` call the first query instead
  of `t/fits?`, which also fixes the `:same` gap. The REPL builder
  (`build-tree :repl`) gets the same result, since both run on the
  builder model.
- The `:same` inference also fixes `slot-type`: a hole's type comes
  from unification over the partial tree, not from the parent's
  declared `:in` alone.
- Cost: about 120 algos, and depth 2–3 is plenty for dimming. Cache the
  "completable within k" set per type at registry load time, since it
  only changes when an algo is registered.
- `t/check!` stays as it is: the final check when a tree is built,
  whatever built it.

---

## 7. Things to settle before adopting it

- **Randomness.** core.logic searches in a fixed order, so `run 1` always
  returns the same answer. To get variety while keeping seeds
  reproducible, shuffle candidate lists (the `membero` arguments) with
  `algo.random`'s seeded generator before the search. That way
  `seed_test` still holds.
- **Cost.** Search time is unbounded. Use `run k`, never `run*`, on
  anything open-ended. Generate before playback, never inside
  `core.engine`. For `t/live!`, a tree node that re-runs on every
  setting change is fine as long as the search space is small (one
  phrase, one bar). If not, cache the result.
- **Fit with the algo registry.** New generators register like the others
  (`{:algo {:short :species1 :in [:pitch] :out :spelled ...}}`). `:out
  :spelled` would be a new port type, with an adapter to `:pitch` (map
  `:m`) so existing nodes keep working.
- **Dependency.** `[org.clojure/core.logic "1.1.0"]` (pure Clojure, small).
  It adds no runtime cost unless it is called.
- **When not to use it.** If a rule needs scoring ("prefer the smoothest
  line") rather than accept/reject, core.logic has no built-in
  optimisation. Use `run k` and rank the results in Clojure, or keep the
  weighted-random generators already in `algo/`.

---

## 8. Suggested order

1. Add `common.spelled` with plain-Clojure `sp`, `dpos-of`,
   `->interval` / `+interval` (§2, §4): helpers that spell from MIDI
   numbers and a Key when something needs it, never a field on a Leaf.
   This step needs no core.logic. Let `markov-train`/`markov-gen`
   accept spelled states and intervals.
2. Add core.logic and `algo.logic.pitch` (§3). Rebuild `midi->spelling`
   and `respell-fn` on `spell-ino`, and test them against
   `key-pitch-name`'s existing cases.
3. Port `constraint-satisfaction-rhythm` to `fd` (§5). It is
   self-contained and easy to compare against the current version.
4. Write `first-species` on spelled pitches. Then decide whether
   `counterpoint/generate` gets a backtracking mode or is replaced.
5. Add `constrained-markov` (§4) as a tree algo next to `markov-gen`.
6. ~~Add `algo.logic.tree` (§6)~~ — done; still open: a "?" on a
   dimmed algo (`why-not`) and "suggest" for the active hole in the
   builder window, and a guided exercise mode.
