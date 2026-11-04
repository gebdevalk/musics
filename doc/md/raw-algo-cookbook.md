# Raw algo cookbook

The `raw/` words of mu, composed with mu's own words: 115 algorithms from the source files, one word each, no half-product wrapping. Every recipe below was run for real, the line typed at the `mu>` prompt and what came back; each random word has its own `seed`, so the results repeat.

## The idea

An algorithm of `musics/algo/` that makes or changes *raw material* (pulses, numbers, weights, onsets, points, layers, strokes, models) is a word `raw/<name>`. It takes what its stack effect says from the stack, its settings from an `@^{ name:value … }` written just before it, and leaves one value. The value is a plain list that knows what it is (a `pulse` row, a `number` stream …), so mu's list words work on it and `zip` can tell a rhythm from a melody. A **bridge** (`pulses->durations`, `numbers->pitches` …) turns raw material into a half product, a duration, a pitch, a volume; the finished words `duration/euclid` and `pitch/lorenz` are a source and a bridge welded together, with the bridge's range read from the source. Here you do the welding yourself, one word at a time, and choose which bridge.

Three things to keep in mind:

- **Settings go before their word.** `@^{ onsets:5 pulses:8 } raw/euclid @^{ pulse:1/8 } raw/pulses->durations`: each word reads only its own settings.
- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right (the table at the end says what each takes and gives), and give a bridge the range its input really has.
- **`seed:N` repeats a word.** A word with no seed draws from the shared generator.

## Recipes (26)

### First steps

#### 1. A raw source

A raw word gives raw material, not music: `raw/euclid` is a row of pulses, 1 for an onset and 0 for a rest. Settings go in an `@^{ }` just before the word, `name:value` each; the defaults are `onsets:3 pulses:8`.

```
mu> raw/euclid
'[1 0 0 1 0 0 1 0]
mu> @^{ onsets:5 pulses:16 } raw/euclid
'[1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
```

#### 2. Pulses become durations

A bridge turns raw material into a half product. `raw/pulses->durations` lets each onset last until the next one, on a grid of `pulse` (1/16 of a whole note by default). It takes the pulses from the stack, so the source comes first, and its settings sit before it.

```
mu> raw/euclid raw/pulses->durations
'[3/16 3/16 1/8]
mu> @^{ onsets:5 pulses:8 } raw/euclid @^{ pulse:1/8 } raw/pulses->durations
'[1/4 1/8 1/4 1/8 1/4]
```

#### 3. Durations and pitches make notes

`zip` joins a note's parts. A stream a raw word gave knows what it is, so durations and pitches meet in the right place. The pitch list is data (`'[ ]`); it cycles over the notes.

```
mu> raw/euclid raw/pulses->durations '[60 62 64 67] zip
[C4/8. D4 E4/8]
mu> @^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] zip
[C4/8 D4/16 E4/8 G4/16 C4/8]
```

#### 4. mu's own words work on raw material

Raw material is a plain list, so the words that act on lists apply: `rotate` moves the front round to the back, `retrograde` reverses, `take` keeps the first n.

```
mu> raw/euclid 2 rotate
'[0 1 0 0 1 0 1 0]
mu> raw/euclid retrograde
'[0 1 0 0 1 0 0 1]
mu> raw/fibonacci 8 take
'[1 1 1 1 0 1 0 0]
```

### Rhythm material

#### 5. Grids from number theory

Each of these is a row of pulses from a formula: Fibonacci numbers, primes, the Cantor set, the golden ratio, the binary digits of a number, a modular formula, an L-system. Rows longer than 16 are shown cut.

```
mu> raw/fibonacci
'[1 1 1 1 0 1 0 0 1 0 0 0 0 1 0 0]
mu> raw/primes
'[0 1 1 1 0 1 0 1 0 0 0 1 0 1 0 0]
mu> raw/cantor
'[1 0 1 0 0 0 1 0 1 0 0 0 0 0 0 0 …]
mu> raw/golden
'[1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 …]
mu> @^{ number:13 length:8 } raw/bits
'[1 0 1 1 0 0 0 0]
mu> raw/modular
'[1 0 0 0 0 0 0 1 0 0 0 0 0 0 1 0 …]
mu> @^{ length:8 iterations:3 } raw/lsys-rhythm
'[1 0 1 1 0 0 0 0]
```

#### 6. A cellular automaton and a sieve

Two more ways to a row of pulses: an elementary cellular automaton, and a Xenakis sieve (an onset wherever a position is in a residue class of a modulus).

```
mu> raw/ca
'[0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 …]
mu> raw/sieve
'[1 1 1 1 1 0 1 1 0 1 1 0]
```

#### 7. Weights: what to keep

`raw/indisp` ranks the pulses of a meter by Barlow's indispensability, the downbeat highest. Ranks are weights, and weights go several ways: `weights->pulses` keeps the strongest fraction, `weights->volumes` turns them into velocities.

```
mu> raw/indisp
'[11 0 4 8 2 6 10 1 5 9 3 7]
mu> raw/indisp @^{ density:0.5 } raw/weights->pulses
'[1 0 0 1 0 1 1 0 0 1 0 1]
mu> raw/indisp @^{ lo:50 hi:100 } raw/weights->volumes
'[100.0 50.0 68.18181818181819 86.36363636363637 59.09090909090909 77.27272727272727 95.45454545454545 54.54545454545455 72.72727272727272 90.9090909090909 63.63636363636363 81.81818181818181]
```

#### 8. Rotations and reflections

`bracelet` picks the index-th pattern among a pattern's rotations and reversals; `phases` gives every rotation as a layer; `necklaces` picks a necklace class of n pulses and k onsets.

```
mu> @^{ onsets:3 pulses:8 } raw/euclid @^{ index:3 } raw/bracelet
'[1 0 1 0 0 1 0 0]
mu> raw/euclid raw/phases
'['[1 0 0 1 0 0 1 0] '[0 0 1 0 0 1 0 1] '[0 1 0 0 1 0 1 0] '[1 0 0 1 0 1 0 0] '[0 0 1 0 1 0 0 1] '[0 1 0 1 0 0 1 0] '[1 0 1 0 0 1 0 0] '[0 1 0 0 1 0 0 1] '[1 0 0 1 0 0 1 0] '[0 0 1 0 0 1 0 1] '[0 1  …
mu> @^{ pulses:8 onsets:3 index:1 } raw/necklaces
'[1 1 0 1 0 0 0 0]
```

#### 9. Layers

A layer is several rows at once. `polyrhythm` and `hemiola` give them; `layer` picks one by `index`; `duet` gives a pattern and the same pattern shifted.

```
mu> raw/hemiola
'['[1 0 0 0 1 0 0 0 1 0 0 0] '[1 0 0 0 0 0 1 0 0 0 0 0]]
mu> raw/polyrhythm @^{ index:1 } raw/layer
'[1 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 …]
mu> raw/euclid raw/duet
'['[1 0 0 1 0 0 1 0] '[0 0 1 0 0 1 0 1]]
```

#### 10. Onsets in time

Some sources give times, not a grid: a Poisson process, a Xenakis cloud. `onsets->durations` reads the gaps between them as note values (`unit` is the note value of one time unit).

```
mu> @^{ seed:7 rate:6 duration:4 } raw/poisson
'[1.287876109503635 1.656482553780222 1.6733226402734855 1.7292842764829126 1.7973554951687536 1.9167530750449615 2.38493804194627 2.6661771706985133 2.8329387597660687 3.2532510733924545 3.27151681 …
mu> @^{ seed:7 rate:6 duration:4 } raw/poisson @^{ unit:1/8 } raw/onsets->durations
'[1/32 1/16 1/32 1/32 1/16 1/32 1/32 1/32]
mu> @^{ seed:7 num-events:6 duration:2 } raw/cloud
'[0.08322428903061885 0.3187141081140645 0.6688388274604167 1.07250984336165 1.3561300618271024 1.6790617795617302]
```

#### 11. Varying a rhythm

Algos that take a pattern and give one back: `emi` mutates it in Cope's style (`similarity` 1 leaves it as it is), `oblique` applies one of Eno's strategies. Each word has its own `seed`.

```
mu> @^{ seed:7 } raw/euclid @^{ seed:9 similarity:0.5 } raw/emi
'[1 0 0 0 1 1 0 1]
mu> @^{ seed:7 } raw/euclid @^{ seed:9 } raw/oblique
'[0 1 1 0 1 1 0 1]
```

#### 12. Syllables and strokes

`konnakol` gives each onset of a rhythm a syllable and each rest a dash; `strokes->durations` reads that as note values.

```
mu> raw/euclid raw/konnakol
'["Ta" "-" "-" "Ka" "-" "-" "Di" "-"]
mu> raw/euclid raw/konnakol raw/strokes->durations
'[3/16 3/16 1/8]
```

### Number material

#### 13. Random draws

Samplers draw `len` numbers from a distribution. Give each a `seed` to repeat it. The ranged ones take `lo` and `hi`; `triangular` peaks at `mode`.

```
mu> @^{ seed:7 len:6 } raw/normal
'[2.4967286709185657 -0.438576756578065 0.06516482381250195 2.175295300849498 0.6839018548130706 0.3718533868519077]
mu> @^{ seed:7 len:6 lo:48 hi:72 mode:60 } raw/triangular
'[48.35624090111939 53.61623161150642 66.73902720726825 62.9368236347863 62.17310315339516 59.86137710741974]
mu> @^{ seed:7 len:6 } raw/uniform
'[4.406513181609687E-4 0.10952103303501406 0.903896407201862 0.7147876186563604 0.664694785993708 0.4885148155709065]
```

#### 14. Walks and noise

A random walk moves by at most `step-bound` each step from `start`; pink noise wanders between white noise and a walk, inside [0, 1) by default.

```
mu> @^{ seed:7 len:8 start:60 step-bound:3 } raw/walk
'[57.00264390790897 54.659770106119055 57.083148549330225 58.37187426126839 59.360042977230634 59.29113187065607 56.65268353457858 54.76264433161417]
mu> @^{ seed:7 len:8 } raw/pink
'[0.5762829320915703 0.5057261777356561 0.5672084517700619 0.5004118704005173 0.5699704505433261 0.4890508941116396 0.5446608381682683 0.6269966863158617]
```

#### 15. Chaos

The logistic map gives numbers in (0, 1); at `r` between 3.57 and 4 it is chaotic. The Lorenz system gives a point per step, [x y z]; `axis` picks one coordinate. (The first steps barely move: `dt` is the step.)

```
mu> @^{ r:3.9 len:8 } raw/logistic
'[0.975 0.09506250000000008 0.33549992226562525 0.8694649252590003 0.44263310911310905 0.962165255336889 0.1419727793616139 0.4750843861996143]
mu> @^{ len:4 } raw/lorenz
'['[1.0125671910736112 1.2599177989452743 0.9848909717916053] '[1.0488237097089568 1.5239971313226008 0.973114219876485] '[1.1072088542956613 1.7983098897421352 0.9651589513000616] '[1.1868680166603 …
mu> @^{ len:6 dt:0.1 } raw/lorenz @^{ axis:0 } raw/axis
'[2.2369069444444447 6.542057283818907 16.679643817758805 12.723181521974489 0.9517378983696343 -4.029229604297741]
```

#### 16. Numbers become music: a bridge needs its range

`numbers->pitches` maps a number's place in `from-lo..from-hi` onto `lo..hi`. Its input range defaults to 0..1, which a walk around 60 is far outside: every number lands on the top pitch. Give the range the numbers really have.

```
mu> @^{ seed:7 len:8 start:60 step-bound:3 } raw/walk raw/numbers->pitches
'[84 84 84 84 84 84 84 84]
mu> @^{ seed:7 len:8 start:60 step-bound:3 } raw/walk @^{ from-lo:52 from-hi:68 lo:48 hi:72 } raw/numbers->pitches
'[56 52 56 58 59 59 55 52]
```

#### 17. One source, several parameters

`dup` copies what is on the stack, so one run of a walk can be the pitches and the velocities: the higher the note, the louder.

```
mu> @^{ seed:7 len:8 start:60 step-bound:3 } raw/walk dup @^{ from-lo:52 from-hi:68 lo:48 hi:72 } raw/numbers->pitches swap @^{ from-lo:52 from-hi:68 lo:40 hi:100 } raw/numbers->volumes
'[56 52 56 58 59 59 55 52]  '[58.75991465465863 49.974137897946456 59.06180705998835 63.89452847975646 67.60016116461487 67.34174451496028 57.44756325466967 50.35991624355313]
```

#### 18. Scale steps

`degrees->pitches` reads whole numbers as steps of a key from its tonic. `points->pitches` maps a point's coordinate onto the steps of a key over `octaves`.

```
mu> '[0 1 2 3 4 5 6 7] @^{ key:D.minor } raw/degrees->pitches
'[62 64 65 67 69 70 72 74]
mu> @^{ seed:7 len:12 dt:0.1 } raw/lorenz @^{ axis:0 from-lo:-20 from-hi:20 key:A.minor octaves:2 } raw/points->pitches
'[81 84 89 88 81 77 77 76 74 74 76 76]
```

#### 19. Instruments

`numbers->instruments` picks from a `palette` of MIDI programs or General MIDI names; an empty palette is all 128.

```
mu> @^{ seed:5 len:8 } raw/uniform @^{ from-lo:0 from-hi:1 palette:(violin flute cello) } raw/numbers->instruments
'[40 40 42 42 40 40 42 73]
```

#### 20. A twelve-tone row

`row` is a random permutation of the twelve pitch classes; `row-form` gives a form of it (P, I, R, RI); `classes->pitches` puts classes on pitches.

```
mu> @^{ seed:3 } raw/row
'[0 4 2 9 5 6 1 8 11 7 10 3]
mu> @^{ seed:3 } raw/row @^{ form:R } raw/row-form
'[3 10 7 11 8 1 6 5 9 2 4 0]
mu> @^{ seed:3 } raw/row raw/classes->pitches
'[60 64 62 69 65 66 61 68 71 67 70 63]
```

#### 21. A Markov model

`markov-train` learns what follows each run of `order` notes in a melody; `markov-gen` walks that model for `length` notes. `chain` is a Markov chain over numeric states, with its own transition table.

```
mu> '[60 62 64 62 60 64] raw/markov-train @^{ seed:3 } raw/markov-gen
'[64 62 60 64 62 64 62 60 64 62 60 62 60 64 62 60]
mu> @^{ seed:3 } raw/chain
'[4 0 3 0 4 0 3 0 4 5 4 5 4 5 4 0]
```

### Putting it together

#### 22. A rhythm, pitches and accents

Above a `|` mark `zip` takes every stream with its part: durations, pitches and volumes (written on each note as `\volume:`). `[~]` loops a stream, so the shorter ones keep cycling.

```
mu> | @^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] [~] raw/indisp @^{ lo:50 hi:100 } raw/weights->volumes [~] zip
[C4/8\volume:100 D4/16\volume:50 E4/8\volume:68 G4/16\volume:86 C4/8\volume:59]
```

#### 23. A walk plays the pitches

The rhythm is a looped Euclid, the pitches a random walk laid on the notes.

```
mu> @^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations [~] @^{ seed:7 len:16 start:60 step-bound:2 } raw/walk @^{ from-lo:54 from-hi:66 lo:55 hi:79 } raw/numbers->pitches zip 8 take
[D#4/8 C4/16 D#4/8 F4/16 F#4/8 F#4 D#4/16 C4/8]
```

#### 24. Playing

A recipe that ends in music plays with `->`, a loop of it with `[~]` before. These are shown, not run here: nothing sounds when this page is made.

```
mu> @^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] [~] zip [~] ->
mu> stop
```

### The functions themselves

#### 25. Calling the Clojure function

`require` loads a Clojure namespace and makes its public functions words. This is the function itself: no settings, no defaults, its arguments from the stack. A keyword tail (`& {:keys [length]}`) is `:key value` pairs written before the word, or nothing.

```
mu> "musics.algo.rhythmic.pattern" 'r require
musics.algo.rhythmic.pattern as r/ (12 words)
mu> 3 8 r/euclid
'[1 0 0 1 0 0 1 0]
mu> 13 :length 8 r/bits
'[1 0 1 1 0 0 0 0]
```

### When it goes wrong

#### 26. A bridge is not checked

A stream carries what it is, but a word does not check it. A wrong pairing fails inside the algo, or gives a bundle that cannot sound. `lorenz` gives points, so `numbers->pitches` is the wrong bridge (`points->pitches` is right):

```
mu> @^{ len:4 } raw/lorenz raw/numbers->pitches
error: ClassCastException:
mu> raw/euclid raw/pulses->durations @^{ len:3 } raw/lorenz zip
'[:duration '[3/16 3/16 1/8] :point '['[1.0125671910736112 1.2599177989452743 0.9848909717916053] '[1.0488237097089568 1.5239971313226008 0.973114219876485] '[1.1072088542956613 1.7983098897421352 0 …
```

## Every raw word

Generated from the registry, so it matches the code. *Takes* are the stack inputs, *Settings* the `@^{ }` names with their defaults.

### Gives pulse (37)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/all-interval` | — | pulse | `length` 12 | A binary pattern of the given length where the intervals between … |
| `raw/bell` | — | pulse | `meter` [12 8], `pattern-name` "standard" | West African bell (timeline) pattern for meter [pulses subdivision], … |
| `raw/bits` | — | pulse | `number` 13, `length` 8 | A number's binary digits as a 0/1 onset grid, least significant bit first: 13 (1101) is [1 0 1 1]. |
| `raw/bracelet` | pulse | pulse | `index` 1 | The index-th of pattern's unique rotations and reversals … |
| `raw/ca` | — | pulse | `rule` 90, `width` 16, `steps` 8, `start` :single | An elementary cellular automaton's generations as pulses: :width cells … |
| `raw/cantor` | — | pulse | `iterations` 3, `length` 27 | The Cantor set as a rhythm: start with every position on (1), then … |
| `raw/cfrac` | — | pulse | `fraction` 3.141592653589793, `length` 16 | The continued-fraction expansion of fraction: each term contributes (min term 3) pulses of value ( … |
| `raw/chain-rhythm` | — | pulse | `transitions` {[1 0] {[0 1] 0.7, [1  …, `initial-state` [1 0], `length` 16 | Higher-order Markov chain rhythm generation: transitions maps a … |
| `raw/counts->pulses` | weight number | pulse |  | Thin each bar's weights to a count of notes: a number n in the stream keeps the n strongest pulses … |
| `raw/crossover` | pulse pulse | pulse | `crossover-point` 4, `child` 0 | One child of crossover-genomes: 0 starts as a and ends as b, 1 the … |
| `raw/csp` | — | pulse | `constraints` [#object[musics.algo.r …, `length` 8 | A binary pattern of the given length that satisfies every predicate in constraints (each a fn from … |
| `raw/data-rhythm` | number | pulse | `threshold-type` "median", `smoothing` 1 | Binary pattern from any numerical data sequence: 1 where a value … |
| `raw/dragon` | — | pulse | `iterations` 4 | The dragon-curve folding sequence as a binary rhythm: start with a … |
| `raw/emi` | pulse | pulse | `similarity` 0.7 | Vary pattern in the style of David Cope's EMI: at each position, with probability (1 - similarity) … |
| `raw/euclid` | — | pulse | `onsets` 3, `pulses` 8 | Spread onsets beats evenly over a grid of pulses (Bjorklund's algorithm, Toussaint's "The Euclidea … |
| `raw/fibonacci` | — | pulse | `length` 16 | An onset at every Fibonacci number below length. |
| `raw/fifths-rhythm` | — | pulse | `notes` 12, `pattern-length` 24 | A beat every (pattern-length / notes) steps, at a position stepping … |
| `raw/genetic` | — | pulse | `population-size` 20, `pattern-length` 16, `generations` 20, `fitness-fn` required, `mutation-rate` 0.1, `elitism` 0.1 | Evolve a rhythmic pattern of pattern-length over generations, scored … |
| `raw/golden` | — | pulse | `length` 34, `phi` 1.61803398875 | A beat at each position reached by repeatedly stepping forward by … |
| `raw/interval-sieve` | — | pulse | `intervals` [2 3], `length` 16 | A pattern with beats at the cumulative sums of intervals, cycling … |
| `raw/layer` | layer | pulse | `index` 0 | One of several pulse layers meant to sound together (wrapping) -- name each pick, (layer polyrhyth … |
| `raw/logistic-grid` | — | pulse | `r` 3.9, `x0` 0.5, `length` 16, `threshold` 0.5 | A binary pattern of the given length from the logistic map (x_{n+1} = r * x_n * (1 - x_n)), thresh … |
| `raw/lsys-rhythm` | — | pulse | `axiom` "A", `rules` {"A" "AB", "B" "A"}, `iterations` 5, `length` 16 | Expand axiom through rules for iterations generations, each character a pulse (A is 1; B or anythi … |
| `raw/modular` | — | pulse | `modulus` 7, `multiplier` 3, `length` 21, `offset` 0 | An onset wherever (i*multiplier + offset) is a multiple of modulus. |
| `raw/mutate` | pulse | pulse | `mutation-rate` 0.1 | Flip each bit of pattern independently with probability mutation-rate. |
| `raw/necklaces` | — | pulse | `pulses` 8, `onsets` 3, `index` 0 | The index-th rotation class of pulses pulses with onsets onsets … |
| `raw/oblique` | pulse | pulse | `strategy` "random" | Apply one of the named oblique-strategies transforms to pattern. |
| `raw/polygon` | — | pulse | `sides` 5, `rotations` 8, `offset` 0.0 | A beat wherever a rotating point comes close (within 0.1 of a full … |
| `raw/primes` | — | pulse | `length` 16, `include-one?` true | An onset at every prime below length (and at 1, unless include-one? is false). |
| `raw/rnn` | pulse | pulse | `weights` [[0.1 0.8 -0.3] [-0.2  …, `iterations` 8 | A deterministic toy-RNN continuation: seed-pattern is extended for iterations steps by running a s … |
| `raw/sieve` | — | pulse | `moduli` [3 4], `residues` [[0 1] [2]], `length` 12 | Xenakis sieve: a binary pattern of the given length where position i … |
| `raw/stochastic` | — | pulse | `distribution` "gaussian", `length` 16, `density` 0.5, `mean` nil, `std` nil, `decay` 2.0 | A binary pattern of the given length, its onsets placed by distribution … |
| `raw/theka` | — | pulse | `tala-name` "teental", `instrument` "tabla" | Simplified binary stroke pattern (theka) for tala-name. |
| `raw/threshold` | number | pulse | `level` 0.5 | An onset where a number is above :level of its range. |
| `raw/tiling` | pulse pulse | pulse | `length` 12 | The grid rhythmic-tiling lays from pattern-a and pattern-b over … |
| `raw/tuplets` | pulse | pulse | `tuplet-ratio` 3/2, `depth` 1 | Recursively expand base-pattern's own beats into tuplet groups: each … |
| `raw/weights->pulses` | weight | pulse | `density` 0.5 | The strongest :density fraction of the pulses on, the rest off -- a … |

### Gives weight (7)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/indisp` | — | weight | `subdivisions` [2 2 3] | Barlow indispensability for a meter whose beats decompose into … |
| `raw/iso-strength` | pulse | weight | `color` [0 1], `repetitions` 3 | Isorhythmic beat-strength pattern: talea (a binary rhythm pattern) … |
| `raw/power` | weight | weight | `adherence` 0.5 | Power-law reshaping of indispensability ranks (or any weights): the normalised weight raised to an … |
| `raw/tala` | — | weight | `tala-name` "teental" | tala-name's cycle as an accent per matra: 3 on sam, 2 on a tali … |
| `raw/text-rhythm` | — | weight | `text` "Hello world this is rhythm.", `mode` "syllables" | A rhythm from the structure of text. |
| `raw/tilt` | weight | weight | `adherence` 0.5 | Softmax over indispensability ranks (or any weights), temperature-scaled by adherence: higher adhe … |
| `raw/trend-rhythm` | number | weight | `lookback` 5 | Rhythm from price trends: each price at or past lookback is compared with the simple moving averag … |

### Gives number (31)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/arcsine` | — | number | `lo` 0.0, `hi` 1.0 | Arcsine distribution over [lo, hi]: density is HIGHEST at the two … |
| `raw/axis` | point | number | `axis` 0 | One coordinate of each point. |
| `raw/beta` | — | number | `a` 2.0, `b` 2.0 | Beta distribution on (0, 1), shaped by a and b: a = b = 1 is uniform, larger equal values peak at  … |
| `raw/biased-walk` | — | number | `start` 60.0, `step-bound` 2.0, `bias` 0.5, `clip-lo` -∞, `clip-hi` ∞, `boundary` :clamp | Like walk but with directional bias. |
| `raw/cauchy` | — | number | `median` 0.0, `scale` 1.0 | Cauchy distribution centred at median, spread by scale: values cluster near median like a normal's … |
| `raw/chain` | — | number | `transitions` {0 {4 2, 3 1}, 4 {0 1, …, `start-state` 0 | Returns a function that walks through states using transition weights. |
| `raw/chi-square` | — | number | `dof` 3 | Chi-square distribution with dof degrees of freedom, a gamma with shape dof/2 and scale 2: positiv … |
| `raw/exponential` | — | number | `mean` 1.0 | Random sample from an exponential distribution with the given mean … |
| `raw/falling` | — | number | `lo` 0.0, `hi` 1.0, `bias` 0.5 | Returns random float between lo and hi with downward bias. |
| `raw/gamma` | — | number | `shape` 2.0, `scale` 1.0 | Marsaglia & Tsang, 'A Simple Method for Generating Gamma Variables', … |
| `raw/glide` | — | number | `initial` 60.0, `inertia` 0.8, `step` 0.5 | A function that moves towards a target on each call, with inertia: inertia 0 snaps to the target,  … |
| `raw/hi-emph` | — | number | `lo` 0.0, `hi` 1.0 | Triangular distribution peaked at high end of [lo,hi] -- shorthand for … |
| `raw/inverse-gamma` | — | number | `shape` 3.0, `scale` 1.0 | If X is gamma(shape, scale) then 1/X is inverse-gamma(shape, 1/scale). |
| `raw/laplace` | — | number | `mean` 0.0, `scale` 1.0 | The Laplace distribution, also known as the double exponential … |
| `raw/linear` | — | number | `lo` 0.0, `hi` 1.0, `rising?` true | Linear-density distribution over [lo, hi] (Xenakis): the density ramps from one bound to the other … |
| `raw/lo-emph` | — | number | `lo` 0.0, `hi` 1.0 | Triangular distribution peaked at low end of [lo,hi] -- shorthand for … |
| `raw/log-normal` | — | number | `mu` 0.0, `sigma` 0.5 | Log-normal distribution: exp of a normal(mu, sigma) draw, so always positive and skewed right. |
| `raw/logistic` | — | number | `r` 3.8, `x` 0.5 | The logistic map (Verhulst) x = r*x*(1-x), one step per call. |
| `raw/mean-emph` | — | number | `lo` 0.0, `hi` 1.0 | Symmetric triangular distribution peaked at midpoint -- shorthand for … |
| `raw/noise` | — | number | `points` 8, `lo` 0.0, `hi` 1.0, `len` 16 | Smooth value noise: random points, blended, sampled :len times. |
| `raw/normal` | — | number | `mean` 0.0, `stdev` 1.0 | Random sample from a normal (Gaussian) distribution, via Box-Muller. |
| `raw/pink` | — | number | `dice` 5, `lo` 0.0, `hi` 1.0 | Pink (1/f) noise between lo and hi: returns a function (Voss's dice, Dodge & Jerse 8.1) that rolls … |
| `raw/rising` | — | number | `lo` 0.0, `hi` 1.0, `bias` 0.5 | Returns random float between lo and hi with upward bias. |
| `raw/row` | — | number | `kind` :random | A twelve-tone row as pitch classes 0-11: :random any order of the … |
| `raw/row-form` | number | number | `form` :P, `transposition` 0 | One of a row's 48 forms: :P as written, :I inverted around its first … |
| `raw/student-t` | — | number | `dof` 5 | Knuth, Seminumerical Algorithms. |
| `raw/tendency` | — | number | `len` 16, `lo-start` 48.0, `lo-end` 66.0, `hi-start` 84.0, `hi-end` 66.0 | Koenig's tendency mask: :len values, each drawn uniformly between a … |
| `raw/triangular` | — | number | `lo` 0.0, `hi` 1.0, `mode` 0.5 | Triangular distribution with peak at `mode`. |
| `raw/uniform` | — | number | `a` 0.0, `b` 1.0 | Uniform random sample from the interval (a, b). |
| `raw/walk` | — | number | `start` 60.0, `step-bound` 2.0, `clip-lo` -∞, `clip-hi` ∞, `boundary` :clamp | Returns a function that moves randomly by at most `step-bound` each call. |
| `raw/weibull` | — | number | `shape` 1.5, `scale` 1.0 | Weibull distribution: a generalised exponential whose shape sets the skew. |

### Gives point (2)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/henon` | — | point | `a` 1.4, `b` 0.3, `x0` 0.1, `y0` 0.1 | The Hénon map: x' = 1 - a*x^2 + y, y' = b*x, one algebraic step per call. |
| `raw/lorenz` | — | point | `sigma` 10.0, `rho` 28.0, `beta` 2.6666666666666665, `x0` 1.0, `y0` 1.0, `z0` 1.0, `dt` 0.01 | The Lorenz system, dx/dt = sigma*(y - x), dy/dt = x*(rho - z) - y, dz/dt = x*y - beta*z, integrate … |

### Gives onset (10)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/birdsong` | — | onset | `species` "sparrow", `duration` 5.0 | Onset timings (seconds) of a stylized birdsong's syllables, species- … |
| `raw/bounce` | — | onset | `initial-height` 2.0, `restitution` 0.7, `gravity` 9.8, `duration` 5.0 | Onset timings (seconds) of a bouncing ball's impacts: dropped from … |
| `raw/cloud` | — | onset | `num-events` 16, `duration` 4.0, `time-std` 0.1 | A Xenakis-style granular "cloud" of num-events timings spread evenly … |
| `raw/heartbeat` | — | onset | `bpm` 72.0, `variability` 0.1, `duration` 10.0 | Onset timings (seconds) of a heartbeat: a base inter-beat interval … |
| `raw/metric-mod` | pulse | onset | `base-tempo` 100, `ratio` 3/2, `subdivisions` 4 | Onset timings (in seconds) of pattern's own beats after converting to … |
| `raw/pendulum` | — | onset | `initial-angle` 0.3, `length` 1.0, `gravity` 9.8, `duration` 5.0, `sample-rate` 100.0 | Onset timings (seconds) of a simplified pendulum's downward … |
| `raw/poisson` | — | onset | `rate` 2.0, `duration` 4.0 | Event onset times in [0, duration) of a Poisson process: inter-arrival gaps are drawn from an expo … |
| `raw/rain` | — | onset | `intensity` 0.5, `duration` 10.0 | Onset timings (seconds) of raindrop impacts: a Poisson process at a … |
| `raw/random-onsets` | — | onset | `beat-duration` 0.25, `num-beats` 16, `density` 0.5 | Generates a sequence of event times within num-beats. |
| `raw/swing` | pulse | onset | `swing-ratio` 0.6, `subdivision` 2 | Swung onset timings (in beat-duration units, not seconds) of … |

### Gives layer (6)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/african` | — | layer | `layers` 3, `base-length` 12 | layers interlocking patterns of base-length pulses each: the first four layers use the classic rat … |
| `raw/duet` | pulse | layer | `phase` 1 | The two parts of Clapping Music: pattern unchanged, and pattern phase … |
| `raw/hemiola` | — | layer | `length` 12 | Classic 3:2 cross-rhythm (hemiola): two layers over length pulses, … |
| `raw/phases` | pulse | layer | `total-phases` 12 | Phase-shifting patterns in the style of Reich's Clapping Music: total … |
| `raw/polymeter` | — | layer | `meters` [[3 4] [4 4]], `length` 12 | polymeter's layers as pulses: an onset wherever it marks a measure … |
| `raw/polyrhythm` | — | layer | `layers` [[3 8] [2 8]], `length` 24 | Multiple simultaneous rhythmic layers. |

### Gives stroke (1)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/konnakol` | pulse | stroke | `syllables` ["Ta" "Ka" "Di" "Mi" " … | South Indian vocal-percussion syllables for pattern (a binary rhythm; default a fixed 8-beat examp … |

### Gives model (1)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/markov-train` | pitch | model | `order` 1 | A Markov model of melody: each run of `order` notes -> what followed it. |

### Gives index (1)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/pick` | weight | index |  | One index, drawn with the child's weights. |

### Gives pitch (5)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/classes->pitches` | number | pitch | `root` 60 | Each pitch class (a number, rounded, mod 12) as a pitch in the octave … |
| `raw/degrees->pitches` | number | pitch | `key` "C.major", `octave` 4 | Whole numbers as scale steps of :key from the tonic in :octave: 0 the … |
| `raw/markov-gen` | model | pitch | `length` 16, `opening` nil | A melody of `length` notes walked through a markov-train model. |
| `raw/numbers->pitches` | number | pitch | `from-lo` 0.0, `from-hi` 1.0, `lo` 48, `hi` 84 | Each number's place in :from-lo..:from-hi as a pitch in :lo..:hi. |
| `raw/points->pitches` | point | pitch | `axis` 0, `from-lo` 0.0, `from-hi` 1.0, `key` "C.major", `octave` 4, `octaves` 2 | One coordinate of each point, its place in :from-lo..:from-hi, onto … |

### Gives duration (6)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/numbers->durations` | number | duration | `from-lo` 0.0, `from-hi` 1.0, `lo` 1/16, `hi` 1/2, `quantum` 1/32 | Each number's place in :from-lo..:from-hi as a note value in … |
| `raw/onsets->durations` | onset | duration | `unit` 1/4, `quantum` 1/32 | The time between successive onsets, as note values: :unit is the note … |
| `raw/points->durations` | point | duration | `axis` 0, `from-lo` 0.0, `from-hi` 1.0, `lo` 1/16, `hi` 1/2, `quantum` 1/32 | One coordinate of each point as a note value, like numbers->durations. |
| `raw/pulses->durations` | pulse | duration | `pulse` 1/16 | Each onset lasts until the next one; a 0 lengthens the note before it, … |
| `raw/strokes->durations` | stroke | duration | `pulse` 1/16 | A syllable starts a note, "-" lengthens it; "-" before the first … |
| `raw/time-points->durations` | number | duration | `unit` 1/16 | Babbitt's time points: each pitch class is where its note starts in a … |

### Gives volume (4)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/numbers->volumes` | number | volume | `from-lo` 0.0, `from-hi` 1.0, `lo` 40.0, `hi` 80.0 | Each number's place in :from-lo..:from-hi as a volume in :lo..:hi … |
| `raw/points->volumes` | point | volume | `axis` 0, `from-lo` 0.0, `from-hi` 1.0, `lo` 40.0, `hi` 80.0 | One coordinate of each point as a volume, like numbers->volumes. |
| `raw/pulses->volumes` | pulse | volume | `accent` :metric, `pulse` 1/16, `meter` "4/4", `lo` 40.0, `hi` 80.0 | Each onset's accent as a volume in :lo..:hi (the !vol: 0-100 scale), … |
| `raw/weights->volumes` | weight | volume | `lo` 40.0, `hi` 80.0 | The weights' own range onto volumes :lo..:hi (the !vol: 0-100 … |

### Gives articulation (2)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/pulses->articulations` | pulse | articulation | `accent` :metric, `pulse` 1/16, `meter` "4/4", `levels` [:ghost nil :accent :m … | Each onset's accent as an articulation, as weights->articulations … |
| `raw/weights->articulations` | weight | articulation | `levels` [:ghost nil :accent :m … | The weights' own range split into as many bands as :levels, weakest … |

### Gives instrument (2)

| Word | Takes | Gives | Settings (default) | Does |
|---|---|---|---|---|
| `raw/numbers->instruments` | number | instrument | `from-lo` 0.0, `from-hi` 1.0, `palette` [] | Each number's place in :from-lo..:from-hi as an instrument of … |
| `raw/points->instruments` | point | instrument | `axis` 0, `from-lo` 0.0, `from-hi` 1.0, `palette` [] | One coordinate of each point as an instrument, like numbers->instruments. |

## Limits worth knowing

- **Bridges default to a 0..1 input range** (`from-lo`, `from-hi`). The finished words (`pitch/lorenz`) read the range from their source; here you set it.
- **Types are not checked** between words (see the last recipe); a wrong pairing fails or gives a bundle.
- **Some algos need a value mu text cannot write**: `genetic` needs a `fitness-fn`, `constrained` and `filter` need functions. Use them from Clojure.
- **Long rows are cut** with `…` when printed; the value on the stack is whole.
