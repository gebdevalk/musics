# Algo cookbook

The algorithms of `musics/algo/` as plain Clojure functions, called by name with their parameters on the stack: 53 recipes. Every recipe was run for real, the seq typed at the `mu>` prompt and what came back. 

## The idea

An algorithm of `musics/algo/` is an ordinary function with clear argument names. `"musics.algo.rhythmic.pattern" require` makes its public functions words, `pattern/euclid`, and a word takes its arguments from the stack in the order `see pattern/euclid` shows them: `3 8 pattern/euclid`. There is nothing in between, no settings object and no defaults: what a function needs, you put on the stack. A function that makes *raw material* (pulses, numbers, weights, onsets, points) gives a plain list; a **bridge** from `musics.algo.bridge` (`pulses->durations`, `numbers->pitches` …) turns it into a duration, a pitch or a volume, with the range or the grid you give it; `zip` puts the parts together into notes. A function that makes one number per call is called n times with `pull`: `'( 0.0 1.0 random/normal ) 12 pull`; one that keeps its place (a walk, the logistic map) is `pull`ed as it is.

Three things to keep in mind:

- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right, and give a bridge the range its input really has.
- **`N rng/seed` repeats the random words that follow it**, so the recipes that draw numbers start with `7 rng/seed`.
- **A parameter that is a map** (Markov transitions, L-system rules) cannot be written in mu text yet; those recipes are shown with `see`.

## Setup

Type these once. `reqalgo` loads a Clojure namespace of `musics.algo`, named without that start and quoted (`'rhythmic.pattern reqalgo` is `"musics.algo.rhythmic.pattern" require`; a second quoted name, `'rhythmic.pattern 'p reqalgo`, is its alias); `penta` is a word of our own, the scale the recipes play over.

```
mu> 'numeric.rng reqalgo
musics.algo.numeric.rng as rng/ (9 words)
mu> 'rhythmic.pattern reqalgo
musics.algo.rhythmic.pattern as pattern/ (12 words)
mu> 'rhythmic.sieve reqalgo
musics.algo.rhythmic.sieve as sieve/ (13 words)
mu> 'rhythmic.meter reqalgo
musics.algo.rhythmic.meter as meter/ (5 words)
mu> 'rhythmic.indispensability reqalgo
musics.algo.rhythmic.indispensability as indispensability/ (6 words)
mu> 'rhythmic.process reqalgo
musics.algo.rhythmic.process as process/ (13 words)
mu> 'rhythmic.nature reqalgo
musics.algo.rhythmic.nature as nature/ (6 words)
mu> 'rhythmic.data reqalgo
musics.algo.rhythmic.data as data/ (3 words)
mu> 'rhythmic.world reqalgo
musics.algo.rhythmic.world as world/ (9 words)
mu> 'rhythmic.feel reqalgo
musics.algo.rhythmic.feel as feel/ (6 words)
mu> 'rhythmic.decompose reqalgo
musics.algo.rhythmic.decompose as decompose/ (2 words)
mu> 'numeric.random reqalgo
musics.algo.numeric.random as random/ (41 words)
mu> 'numeric.walk reqalgo
musics.algo.numeric.walk as walk/ (8 words)
mu> 'numeric.markov reqalgo
musics.algo.numeric.markov as markov/ (1 words)
mu> 'numeric.chaos.logistic reqalgo
musics.algo.numeric.chaos.logistic as logistic/ (1 words)
mu> 'numeric.chaos.lorenz reqalgo
musics.algo.numeric.chaos.lorenz as lorenz/ (1 words)
mu> 'melodic.melody reqalgo
musics.algo.melodic.melody as melody/ (10 words)
mu> 'melodic.serial reqalgo
musics.algo.melodic.serial as serial/ (8 words)
mu> 'melodic.slonimsky reqalgo
musics.algo.melodic.slonimsky as slonimsky/ (6 words)
mu> 'counterpoint.jeppesen reqalgo
musics.algo.counterpoint.jeppesen as jeppesen/ (5 words)
mu> 'bridge reqalgo
musics.algo.bridge as bridge/ (25 words)
mu> : penta ( -- l ) '[60 62 64 67 69] ;

```

## Recipes (53)

### First steps

#### 1. A source, a bridge, a scale

`pattern/euclid` is the Clojure function itself: its arguments, `onsets` and `pulses`, come from the stack and `see pattern/euclid` shows them. It gives a row of pulses, 1 for an onset and 0 for a rest. `bridge/pulses->durations` lets each onset last until the next one on a grid of the `pulse` you give it; `zip` puts durations and pitches together. `penta` is a word of our own, the scale the recipes play over.

```
mu> see pattern/euclid
pattern/euclid ( onsets pulses -- euclid )
  Spread onsets beats evenly over a grid of pulses (Bjorklund's algorithm, Toussaint's "The Euclidean Algorithm Generates Traditional Musical Rhythms"). Rotate the result with `rotate`. (euclid 3 8) ;=> [1 0 0 1 0 0 …
mu> 3 8 pattern/euclid
'[1 0 0 1 0 0 1 0]
mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip
[C4/8. D4 E4/8]
```

#### 2. Change a parameter; a precondition guards it

Write another number. A function that cannot work with its arguments says what it needs.

```
mu> 5 8 pattern/euclid 1/16 bridge/pulses->durations penta zip
[C4/8 D4/16 E4/8 G4/16 A4/8]
mu> 5 8 pattern/euclid 1/16 bridge/pulses->durations '[60 63 67 70] zip
[C4/8 D#4/16 G4/8 A#4/16 C4/8]
mu> 99 8 pattern/euclid
error: pattern/euclid: refused, it needs <= onsets pulses
mu> 0 0 pattern/euclid
error: pattern/euclid: refused, it needs pos? pulses
```

#### 3. Wrong shapes

A word takes what its stack effect says from the stack. Too few arguments are refused; arguments of the wrong kind are not always: a bridge on durations runs and gives nonsense.

```
mu> 5 pattern/euclid
error: pattern/euclid: needs 2 items on the stack, has 1
mu> 3 8 pattern/euclid zip
error: zip: needs 2 or more values to put together, has 1
mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations 1/16 bridge/pulses->durations
'[1/16 1/16 1/16]
```

#### 4. Raw material needs its bridges

Pulses become durations with one bridge. Weights (`indisp` ranks) need two: `weights->pulses` keeps the strongest `density` of them, then `pulses->durations`.

```
mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip
[C4/8. D4 E4/8]
mu> '[2 2 3] indispensability/indisp 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations penta zip
[C4/8. D4/8 E4/16 G4/8. A4/8 C4/16]
```

#### 5. One run, several parameters

`dup` copies what is on the stack, so one run of a walk can be the pitches and the velocities: the higher the note, the louder. `pull` calls a function n times; `walk/walk` gives a function that keeps its place.

```
mu> 7 rng/seed

mu> 60.0 3.0 walk/walk 8 pull dup 52 68 48 72 bridge/numbers->pitches swap 52 68 40 100 bridge/numbers->volumes
'[56 52 56 58 59 59 55 52]  '[58.75991465465863 49.974137897946456 59.06180705998835 63.89452847975646 67.60016116461487 67.34174451496028 57.44756325466967 50.35991624355313]
```

### Rhythm × melody

#### 6. Euclidean rhythm, rotated, over a reshuffled pentatonic

`rotate` and `shuffle` are mu's own list words: they work on a row of pulses and on a list of pitches alike.

```
mu> 7 rng/seed

mu> 5 16 pattern/euclid 2 rotate 1/16 bridge/pulses->durations penta shuffle zip
[r16 E4/8. D4 A4 G4/4 C4/8]
```

#### 7. Legato by default, detached by articulation

`zip` takes a named part: `:articulation` before a list gives every note it.

```
mu> 5 8 pattern/euclid 1/16 bridge/pulses->durations penta :articulation '[:staccato] zip
[C4/8-. D4/16-. E4/8-. G4/16-. A4/8-.]
```

#### 8. A Fibonacci rhythm

Every generator of `musics/algo/` is a function you can call.

```
mu> 16 pattern/fibonacci 1/16 bridge/pulses->durations '[60 62 64 65 67 69 71] zip
[C4/16 D4 E4 F4/8 G4/8. 16:5(A4/1) B4/8.]
```

#### 9. A Xenakis sieve as the rhythm

The moduli and their residues are lists; a list inside a list is written `'[ '[0 1] '[2] ]`.

```
mu> '[3 4] '[ '[0 1] '[2] ] 12 sieve/sieve 1/16 bridge/pulses->durations penta zip
[C4/16 D4 E4 G4 A4/8 C4/16 D4/8 E4/16 G4/8]
```

#### 10. A clave timeline carrying an arpeggio

`world/bell` gives a named West African timeline for a meter, `[pulses subdivision]`.

```
mu> '[12 8] "clave" world/bell 1/16 bridge/pulses->durations '[60 64 67 72] zip
[C4/8. E4 G4/4 C5/8]
```

### Isorhythm and durations

#### 11. Isorhythm: a 5-note color against a 3-value talea

Two loops of different lengths zipped line up after their least common multiple, here 15 notes.

```
mu> '[1/4 1/8 1/8] [~] '[60 62 64 65 67] [~] zip 15 take
[C4/4 D4/8 E4 F4/4 G4/8 C4 D4/4 E4/8 F4 G4/4 C4/8 D4 E4/4 F4/8 G4]
```

#### 12. Stretch the durations, transpose the pitches

`*` stretches the durations of a seq and `+` transposes it.

```
mu> '[1/4 1/8 1/8] [~] '[60 62 64 65 67] [~] zip 15 take 1/2 * 7 +
[G4/8 A4/16 B4 C5/8 D5/16 G4 A4/8 B4/16 C5 D5/8 G4/16 A4 B4/8 C5/16 D5]
```

### Metre and indispensability

#### 13. Indispensability thins a 12/8 bar

`indisp` ranks the pulses of a meter given by its subdivisions, `tilt` turns ranks into probabilities (`adherence` how strongly rank decides), `weights->pulses` keeps the strongest `density` of them.

```
mu> '[2 2 3] indispensability/indisp 0.8 indispensability/tilt 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations '[67 64 60] zip
[G4/8. E4/8 C4/16 G4/8. E4/8 C4/16]
```

#### 14. Swap one stage: strong beats silent

`power` in place of `tilt`, with a negative adherence: the weak pulses win.

```
mu> '[2 2 3] indispensability/indisp -0.8 indispensability/power 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations '[67 64 60] zip
[r16 E4 C4/8 G4/8. E4/16 C4/8 G4]
```

#### 15. One weighted choice

`random/weighted-choose` draws one of the values (here the nine pulses `0 9 range`) with the weights it is given.

```
mu> 7 rng/seed

mu> 0 9 range '[3 3] indispensability/indisp 1.0 indispensability/tilt random/weighted-choose
0
```

### Melody generators

#### 16. A Markov melody trained on a motif

`markov-train` learns the motif (its `order`), `markov-gen` walks the model for `length` notes.

```
mu> 7 rng/seed

mu> 11 16 pattern/euclid 1/16 bridge/pulses->durations '[60 62 64 62 60 67 65 64] 1 melody/markov-train 11 melody/markov-gen zip
[C4/8 D4/16 E4/8 D4/16 C4/8 D4/16 C4/8 D4/16 E4/8 D4/16 C4]
```

#### 17. Slonimsky: insertions below, between, above

`slonimsky/infra` puts a pattern after each principal tone but the last (tones you chose to lie below them); `slonimsky/slonimsky2` does infra, inter and ultra at once, in that order.

```
mu> '[60 64 67 72] '[59 62] slonimsky/infra
'[60 59 62 64 59 62 67 59 62 72]
mu> '[60 64 67 72] '[] '[65] '[62] slonimsky/slonimsky2
'[60 65 62 64 65 62 67 65 62 72]
```

#### 18. An L-system melody

`melody/lsys-melody` expands an axiom with rules and reads each letter as a note. The rules and the letters' pitches are maps, which mu text cannot write yet, so this one is only shown: `see` gives its parameters.

```
mu> see melody/lsys-melody
melody/lsys-melody ( axiom rules note-map iterations max-notes -- lsys-melody )
  Expand axiom through rules `iterations` times; each character in note-map becomes that pitch, others are skipped.
```

#### 19. A constrained walk

`melody/constrained` walks a scale: each note is drawn from those that every constraint accepts. A constraint is a function of the melody so far and the candidate note, and a quotation is one: `( '( … ) '( … ) )` gathers quotations into the list. Here the first constraint refuses a repeated note (`swap last =` compares the candidate with the last note), the second keeps every step within a whole tone (`swap last - abs 3 <`).

```
mu> 7 rng/seed

mu> '[60 62 64 65 67 69 71 72] 12 '[] melody/constrained
'[72 72 65 69 72 67 64 72 72 64 69 67]
mu> '[60 62 64 65 67 69 71 72] 12 ( '( swap last = not ) '( swap last - abs 3 < ) ) melody/constrained
'[60 62 64 65 67 65 64 65 64 62 64 62]
```

#### 20. A melody that modulates

Segments of `[key length]` pairs, a key being `'[:C :major]`, and the constraints they share, as in recipe 19. The result is pitch classes of the keys; `60 +` lifts them to the middle of the keyboard.

```
mu> 7 rng/seed

mu> '[ '[ '[:C :major] 6] '[ '[:E :minor] 6] ] ( '( swap last = not ) ) melody/modulating 60 +
'[60 64 62 67 62 60 60 71 66 62 67 62]
```

#### 21. Two-voice counterpoint

`jeppesen/species` takes the cantus, the number of voices, the species of the added voice, which part holds the cantus, the mode (`:auto` finds both) and a seed for the search. It gives one seq per voice.

```
mu> '[55 57 59 60 62 64 65 67 69 71 72 74 76] 2 1 :auto :auto 1 jeppesen/species
'[[G4/1 F4 G4 G4 F4 G4 A4 B4 C5 D5 E5 F5 E5] [G3 A3 B3 C4 D4 E4 F4 G4 A4 B4 C5 D5 E5]]
```

### Metric generators

#### 22. A number's bits, a fraction's expansion

45 is 101101: onsets on bits 0, 2, 3 and 5. A function with a keyword tail takes `:key value` pairs written before it: `:length 8`. π is [3; 7, 15, 1, …].

```
mu> 45 :length 8 pattern/bits 1/16 bridge/pulses->durations penta zip
[C4/8 D4/16 E4/8 G4/8.]
mu> 3.141592653589793 16 pattern/cfrac 1/16 bridge/pulses->durations penta zip
[C4/16 D4 E4 G4 A4 C4 D4 E4 G4 A4/4 C4/16 D4 E4]
```

#### 23. Modular arithmetic as a rhythm

An onset wherever `multiplier · i + offset` is a multiple of `modulus`: `modulus multiplier length offset`.

```
mu> 5 2 15 0 pattern/modular 1/16 bridge/pulses->durations penta zip
[16:5(C4/1 D4 E4)]
```

### Random sources

#### 24. A sampler is a function: pull n draws

`random/triangular` gives one draw per call; `pull` calls a quotation n times and collects the results. The bridge puts the draws on pitches, the range they really have being its second and third arguments.

```
mu> 7 rng/seed

mu> '( 0.0 1.0 0.5 random/triangular ) 12 pull 0.0 1.0 60 73 bridge/numbers->pitches
'[60 63 70 68 68 66 62 64 66 63 70 64]
```

#### 25. Any numbers, rescaled onto a scale

Scale the draws to the scale steps you want and round them (`'( round ) map`); `degrees->pitches` spells whole steps in a key and octave. A key name with a hyphen goes in quotes.

```
mu> 7 rng/seed

mu> '( 0.0 1.0 random/normal ) 12 pull 3 * 4 + '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches
'[86 67 69 86 74 72 67 60 67 74 64 67]
```

#### 26. A random walk, and a biased one

`walk/walk` moves by at most `step-bound`; `biased-walk` also leans one way (above 0.5 up). A function that keeps its place is `pull`ed.

```
mu> 7 rng/seed

mu> 4.0 1.0 walk/walk 16 pull '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches
'[67 64 67 67 69 69 67 64 64 62 64 62 62 62 62 64]
mu> 60.0 2.0 0.7 walk/biased-walk 16 pull
'[58.2102509909799 58.99992112093604 60.08224234033429 58.52111338463639 59.540963313435434 60.74851840984741 61.73472477815457 62.970332711229645 61.304719841411504 63.21749456394871 61.96628913049733 61.09753432290106 62.461310400734966 63.20030089216314 6 …
```

#### 27. Chaos: the logistic map, the Lorenz attractor

`logistic` gives numbers in (0, 1) from a growth rate and a start; `lorenz` a point per step, `points->pitches` reads one coordinate onto the steps of a key. `pull` also takes a generator that holds a `:value` function, as lorenz does.

```
mu> 3.9 0.5 logistic/logistic 12 pull 9 * '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches
'[81 62 67 79 69 81 62 69 81 62 67 79]
mu> 10.0 28.0 2.667 1.0 1.0 1.0 0.03 lorenz/lorenz 24 pull 0 -20.0 20.0 "C.pentatonic-major" 4 2 bridge/points->pitches
'[72 72 72 72 72 74 74 76 76 79 81 81 79 76 74 72 …]
```

#### 28. A Markov chain over scale degrees

`markov/chain` walks a table of transitions between states. The table is a map, which mu text cannot write yet, so this one is only shown.

```
mu> see markov/chain
markov/chain ( transitions start-state -- chain )
  Returns a function that walks through states using transition weights. transitions: {state {next-state weight, ...}, ...} Example: (chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)
```

#### 29. Poisson onsets become durations

`poisson` gives event times from a rate and a duration; `onsets->durations` reads the gaps between them as note values, `unit` being the note value of one time unit and `quantum` the grid they are rounded to.

```
mu> 7 rng/seed

mu> 3.0 4.0 process/poisson 1/4 1/32 bridge/onsets->durations penta zip
[C4/8. D4/32 E4 G4/16]
```

#### 30. Smooth noise as a rhythm

`walk/smooth-noise` makes a curve, a function of t, from `n` random points between `lo` and `hi`; `map` samples it at sixteen places, and `data-rhythm` puts an onset wherever a value is above the median.

```
mu> 7 rng/seed

mu> 0 16 range 3 / 6 0.0 1.0 walk/smooth-noise map "median" 1 data/data-rhythm 1/16 bridge/pulses->durations penta zip
[16:5(r1) D4/16 E4 G4 A4 C4 D4 E4 G4/4]
```

### Rhythm generators

#### 31. A tala's theka, a named bell pattern



```
mu> "jhaptal" world/theka 1/16 bridge/pulses->durations penta zip
[C4/8 D4/16 E4/8 G4 A4/16 C4/8]
mu> '[16 8] "bossanova" world/bell 1/16 bridge/pulses->durations '[60 64 67] zip
[C4/8. E4 G4/4 C4/8 E4/4]
```

#### 32. Fractal rhythms: Cantor set, dragon curve



```
mu> 2 9 pattern/cantor 1/16 bridge/pulses->durations penta zip
[C4/8 D4/4 E4/8 G4/16]
mu> 3 pattern/dragon 1/16 bridge/pulses->durations penta zip
[C4/16 D4/8 E4/16 G4/8. A4/16 C4 D4/8. E4]
```

#### 33. A polyrhythm, one layer per voice

`polyrhythm` takes the layers, each `[onsets grid]`, and a length, and gives a list of layers; `0 @` picks one: three against two.

```
mu> '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 0 @ 1/16 bridge/pulses->durations '[48 55] zip
[C3/2 G3 C3]
mu> '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 1 @ 1/16 bridge/pulses->durations '[48 55] zip
[C3/2. G3]
```

#### 34. A polyrhythm as two parts

Each layer its own seq, each started by its own `|` mark so that `zip` joins only that part; `{2}` puts the two side by side.

```
mu> | '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 0 @ 1/16 bridge/pulses->durations '[48] zip | '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 1 @ 1/16 bridge/pulses->durations '[55] zip {2}
{[C3/2 C3 C3] [G3/2. G3]}
```

#### 35. Clapping Music: the pattern against itself, shifted

`duet` gives the pattern and the same pattern `phase` pulses on; `1 @` picks the shifted one.

```
mu> '[1 1 1 0 1 1 0 1 0 1 1 0] 3 sieve/duet 1 @ 1/16 bridge/pulses->durations '[72] zip
[r16 C5 C5/8 C5 C5/16 C5/8 C5/16 C5 C5]
```

#### 36. A genetic rhythm: the fitness function is a quotation

A quotation `'( … )` is a function of what it is given, so it can be the fitness function: this one scores a pattern by how close its onset count is to five.

```
mu> 7 rng/seed

mu> 16 16 20 '( sum 5 - abs negate ) process/genetic 1/16 bridge/pulses->durations penta zip
[r8. D4 16:5(E4/1) G4/16 A4/8. C4/16]
```

#### 37. Swing: a grid becomes onset times, then durations

`swing` turns pulses into swung onset times (a ratio and the steps per beat); `onsets->durations` reads them back, a beat being `unit`.

```
mu> 8 8 pattern/euclid 0.67 2 feel/swing 1/4 1/32 bridge/onsets->durations penta zip
[32:5(C4/1) D4/16. 32:5(E4/1) G4/16. 32:5(A4/1) C4/16. 32:5(D4/1)]
```

#### 38. A bouncing ball, in seconds

Times in seconds: height, restitution, gravity and duration; `unit` says how long one second is as a note value.

```
mu> 1.0 0.8 9.8 5.0 nature/bounce 1/4 1/64 bridge/onsets->durations '[72 67] zip
[C5/16.. G4/16. 64:5(C5/1) G4/16 C5/32. G4/32 C5 G4 C5/64 G4 C5 G4]
```

#### 39. Tuplet-like durations by repeated splitting

`decompose/split` takes a `ratio`-sized bite of what remains, `depth` times.

```
mu> 1 5 2/3 decompose/split penta zip
[3:2(C4/1) 9:2(D4) 27:2(E4) 81:2(G4) 81:1(A4)]
```

#### 40. Text as rhythm

A beat on each word's first syllable; density 1.0 lets every accent sound.

```
mu> "Composing trees of algorithms is plain Clojure." data/text-rhythm 1.0 bridge/weights->pulses 1/16 bridge/pulses->durations penta zip
[C4/8. D4/16 E4 G4/8. A4/16 C4 D4/4]
```

#### 41. Vary a rhythm: EMI style, Oblique Strategies

`emi` takes a pattern and a similarity (1 leaves it as it is); `oblique` a pattern and the name of a strategy.

```
mu> 7 rng/seed

mu> 5 16 pattern/euclid 0.6 feel/emi 1/16 bridge/pulses->durations penta zip
[C4/8 D4/16 E4/8. G4/8 A4/16 C4/4..]
mu> 3 8 pattern/euclid "mirror" feel/oblique 1/16 bridge/pulses->durations penta zip
[C4/8. D4 E4 G4 A4 C4/16]
```

#### 42. Cross two rhythms

`process/crossover` takes the first part of one pattern and the rest of the other, at a point; `child` 0 or 1 says which way round.

```
mu> '[1 0 1 1 0 1 0 0] '[1 0 0 1 0 0 1 0] 4 0 process/crossover 1/16 bridge/pulses->durations penta zip
[C4/8 D4/16 E4/8. G4/8]
mu> '[1 0 1 1 0 1 0 0] '[1 0 0 1 0 0 1 0] 4 1 process/crossover 1/16 bridge/pulses->durations penta zip
[C4/8. D4/8 E4/8.]
```

### Algorithms of your own

#### 43. A pitch transform

A word of your own is an algorithm: `: name ( inputs -- results ) … ;`. This one moves every pitch down an octave, and works on pitches before `zip`.

```
mu> : down ( l -- l' ) 12 - ;

mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations penta down zip
[C3/8. D3 E3/8]
```

#### 44. Required arguments and their limits

Every argument is required: there are no defaults, and a function says how many it needs and what it cannot take.

```
mu> 16 16 20 process/genetic
error: process/genetic: needs 4 items on the stack, has 3
mu> 99 8 pattern/euclid
error: pattern/euclid: refused, it needs <= onsets pulses
```

### Two of a kind

#### 45. Two instances of one algo

No naming is needed: each call takes its own arguments, so two euclids are two calls.

```
mu> 2 16 pattern/euclid 5 16 pattern/euclid
'[1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0]  '[1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
```

#### 46. Different algos, same parameter name

Arguments are by position, so `onsets` in `euclid` and in `necklaces` cannot meet.

```
mu> 5 8 pattern/euclid
'[1 0 1 1 0 1 1 0]
mu> 8 5 1 sieve/necklaces
'[1 1 1 1 0 1 0 0]
```

### Parameters as data

#### 47. One seq, several parameters: a word with inputs

The parameters of a word of your own are its `$` inputs.

```
mu> : riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;

mu> 2 8 riff
[C4/4 D4]
mu> 7 8 riff
[C4/16 D4 E4 G4 A4 C4 D4/8]
```

#### 48. Another word over the same inputs

Words that share inputs just share names. `see` shows a word's stack effect with its inputs.

```
mu> see riff
: riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;
mu> : lift ( $notes $by -- notes' ) $notes $by + ;

mu> 2 8 riff 7 lift
[!K:G.major G4/4 A4]
```

#### 49. Defaults are a word

A default is a definition that fills in what you leave out; the arguments you give win.

```
mu> : groove ( $onsets -- pulses ) $onsets 16 pattern/euclid ;

mu> 5 groove
'[1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
mu> 7 groove
'[1 0 0 1 0 1 0 1 0 0 1 0 1 0 1 0]
```

#### 50. Look inside: see and the stack

`see` shows a word's stack effect and what it does; `.s` shows the stack.

```
mu> see pattern/euclid
pattern/euclid ( onsets pulses -- euclid )
  Spread onsets beats evenly over a grid of pulses (Bjorklund's algorithm, Toussaint's "The Euclidean Algorithm Generates Traditional Musical Rhythms"). Rotate the result with `rotate`. (euclid 3 8) ;=> [1 0 0 1 0 0 …
mu> see bridge/pulses->durations
bridge/pulses->durations ( pulses pulse -- pulses->durations )
  Each onset lasts until the next one; a 0 lengthens the note before it, and 0s before the first onset are one Rest.
mu> 3 8 pattern/euclid .s
0> '[1 0 0 1 0 0 1 0]
----
'[1 0 0 1 0 0 1 0]
```

### Live

#### 51. A live word, changed while it plays

A word with `$` inputs plays as a voice: the quoted name keeps it from running now, the voice runs it again whenever an input changes. `:bass/onsets` is an input, read with `@` and stored with `!`. Shown, not run: nothing sounds when this page is made.

```
mu> 5 8 'riff :bass ->
mu> :bass settings
mu> 3 :bass/onsets !
mu> :bass/onsets @
mu> stop
```

#### 52. A transform: reshape any voice's own notes

A quotation under a play word runs on the music under it, note by note. Shown, not run.

```
mu> [c d e g] [~] '( 5 + ) :up ->
mu> '( 7 + ) :up retree
```

### Material

#### 53. Generated notes become committed musics text

`R!` stores what is on the stack under a name; `R@` reads it back, and `write` or `save-session` keeps it.

```
mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip :demo R!
:demo
mu> :demo R@
[demo: C4/8. D4 E4/8]
```

