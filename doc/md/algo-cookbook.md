# Algo cookbook

The algorithms of `musics/algo/` as plain Clojure functions, called by name with their parameters on the stack: 58 recipes. Every recipe was run for real, the line typed at the `mu>` prompt and what came back. 

## The idea

An algorithm of `musics/algo/` is an ordinary function with clear argument names. `"musics.algo.rhythmic.pattern" require` makes its public functions words, `pattern/euclid`, and a word takes its arguments from the stack in the order `see pattern/euclid` shows them: `3 8 pattern/euclid`. There is nothing in between, no settings object and no defaults: what a function needs, you put on the stack. A function that makes *raw material* (pulses, numbers, weights, onsets, points) gives a plain list; a **bridge** from `musics.algo.bridge` (`pulses->durations`, `numbers->pitches` …) turns it into a duration, a pitch or a volume, with the range or the grid you give it; `zip` puts the parts together into notes. A function that makes one number per call is called n times with `pull`: `'( 0.0 1.0 random/normal ) 12 pull`; one that keeps its place (a walk, the logistic map) is `pull`ed as it is.

The recipes are written in two styles. The first ten put every number into the line, so that each word and its arguments can be seen at once. From recipe 11 the numbers are **settings** in the tctx, the session's table of named values (`3 :onsets T!`), and the line that makes the music is stored once as a computed key (`'( :onsets :pulses pattern/euclid … ) :name T!`) and run with `:name T@@`. That is the pattern to use for anything you will change, repeat or play live: a change is one `T!`, and a voice that reads the table hears it at its next note.

Four things to keep in mind:

- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right, and give a bridge the range its input really has.
- **`N rng/seed` repeats the random words that follow it**, so the recipes that draw numbers start with `7 rng/seed`.
- **A parameter that is a map** (Markov transitions, L-system rules) cannot be written in mu text yet; those recipes are shown with `see`.
- **Floating point values are shown with two decimals** (`0.5234` is shown `0.52`, `1.0` is `1.00`). The value keeps every digit; only what `.`, `.s` and the lines below print is rounded.

## Setup

Type these once. `reqalgo` loads a Clojure namespace of `musics.algo`, named without that start and quoted (`'rhythmic.pattern reqalgo` is `"musics.algo.rhythmic.pattern" require`; a second quoted name, `'rhythmic.pattern 'p reqalgo`, is its alias); `penta` is a word of our own, the scale the recipes play over.

```
mu> 'numeric.rng reqalgo
musics.algo.numeric.rng as rng/ (10 words)
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
musics.algo.numeric.walk as walk/ (9 words)
mu> 'numeric.markov reqalgo
musics.algo.numeric.markov as markov/ (2 words)
mu> 'numeric.chaos.logistic reqalgo
musics.algo.numeric.chaos.logistic as logistic/ (2 words)
mu> 'numeric.chaos.lorenz reqalgo
musics.algo.numeric.chaos.lorenz as lorenz/ (2 words)
mu> 'melodic.melody reqalgo
musics.algo.melodic.melody as melody/ (10 words)
mu> 'melodic.serial reqalgo
musics.algo.melodic.serial as serial/ (8 words)
mu> 'melodic.slonimsky reqalgo
musics.algo.melodic.slonimsky as slonimsky/ (9 words)
mu> 'counterpoint.jeppesen reqalgo
musics.algo.counterpoint.jeppesen as jeppesen/ (5 words)
mu> 'bridge reqalgo
musics.algo.bridge as bridge/ (50 words)
mu> : penta ( -- l ) '[60 62 64 67 69] ;

```

## Recipes (58)

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
  in pattern/euclid (input:1:6)
mu> 0 0 pattern/euclid
error: pattern/euclid: refused, it needs pos? pulses
  in pattern/euclid (input:1:5)
```

#### 3. Wrong shapes

A word takes what its stack effect says from the stack. Too few arguments are refused; arguments of the wrong kind are not always: a bridge on durations runs and gives nonsense.

```
mu> 5 pattern/euclid
error: pattern/euclid: needs 2 items on the stack, has 1
  in pattern/euclid (input:1:3)
mu> 3 8 pattern/euclid zip
error: zip: needs 2 or more values to put together, has 1
  in zip (input:1:20)
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
'[58 58 54 52 52 50 48 48]  '[65.43 65.69 55.56 49.14 49.50 46.23 40.00 40.00]
```

### Rhythm × melody

#### 6. Euclidean rhythm, rotated, over a reshuffled pentatonic

`rotate` and `shuffle` are mu's own list words: they work on a row of pulses and on a list of pitches alike.

```
mu> 7 rng/seed

mu> 5 16 pattern/euclid 2 rotate 1/16 bridge/pulses->durations penta shuffle zip
[r16 G4/8. C4 D4 A4/4 E4/8]
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
[C4/16 D4 E4 F4/8 G4/8. 16:5[A4/1] B4/8.]
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

### Settings: the tctx

#### 11. Parameters live in the tctx

Until here every number was typed into the line. The session also keeps a table of named values, the **tctx**. Where a word that is a Clojure function needs a number, a keyword the table holds stands for its value, so the line that makes the music is written once, with names, and the numbers are kept apart. A key is named for the argument it feeds (`see pattern/euclid` says `onsets` and `pulses`), in letters, digits and underscores (`:step_bound`), and never for a part name `zip` knows (`:pitch`, `:duration`, `:volume`). A computed key is a quotation: the line itself, stored once, run by `T@@` with the settings as they are. The whole table can be written out as `@{ :key value … }` (a context, `^{ }`, has string keys and its own brackets) and made the session's with `:tctx R!`, which replaces what was there; `:tctx R@` writes the session's back in the same form. `T!` sets one key of it (`3 :onsets T!`), `T@` reads one. From here on the recipes are written this way.

```
mu> @{ :onsets 3 :pulses 8 :pulse 1/16 :euclid '( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta zip ) } :tctx R!
:tctx
mu> :tctx R@
@{ :euclid '( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta zip ) :onsets 3 :pulse 1/16 :pulses 8 }
mu> :onsets T@ :pulses T@
3  8
mu> :euclid T@
'( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta zip )
mu> :euclid T@@
[C4/8. D4 E4/8]
```

#### 12. Change a setting, run it again

A setting changes and the recipe does not. Nothing is retyped, and a live voice that reads the key hears it at its next note.

```
mu> 5 :onsets T!

mu> :euclid T@@
[C4/8 D4/16 E4/8 G4/16 A4/8]
mu> 7 :onsets T! 12 :pulses T!

mu> :euclid T@@
[C4/8 D4/16 E4/8 G4 A4/16 C4/8 D4]
```

#### 13. A setting computed from others

A computed key is asked for when it is read, so it follows what it is made of: `:onsets` here is half of `:pulses`, and `:euclid` goes on reading it as `:onsets`. A key that asks for itself, however far round, is refused.

```
mu> '( :pulses 2 / int ) :onsets T!

mu> :onsets T@@
6
mu> 16 :pulses T!

mu> :onsets T@@
8
mu> :euclid T@@
[C4/8 D4 E4 G4 A4 C4 D4 E4]
```

#### 14. Play what the settings give

`:euclid T@@` is a seq like any other, so a play word takes it. Shown, not run. A voice that reads the settings as it plays is recipe 55.

```
mu> :euclid T@@ [~] ->
mu> stop
```

### Isorhythm and durations

#### 15. Isorhythm: a 5-note color against a 3-value talea

Two loops of different lengths zipped line up after their least common multiple, here 15 notes. The color, the talea and the length are settings.

```
mu> '[1/4 1/8 1/8] :talea T! '[60 62 64 65 67] :color T! 15 :notes T!

mu> '( :talea [~] :color [~] zip :notes take ) :isorhythm T!

mu> :isorhythm T@@
[C4/4 D4/8 E4 F4/4 G4/8 C4 D4/4 E4/8 F4 G4/4 C4/8 D4 E4/4 F4/8 G4]
```

#### 16. Stretch the durations, transpose the pitches

A computed key can read another: `:isorhythm` inside the quotation is the seq it makes now. `*` stretches the durations of a line and `+` transposes it.

```
mu> 1/2 :stretch T! 7 :shift T!

mu> '( :isorhythm :stretch * :shift + ) :moved T!

mu> :moved T@@
[G4/8 A4/16 B4 C5/8 D5/16 G4 A4/8 B4/16 C5 D5/8 G4/16 A4 B4/8 C5/16 D5]
```

### Metre and indispensability

#### 17. Indispensability thins a 12/8 bar

`indisp` ranks the pulses of a meter given by its subdivisions, `tilt` turns ranks into probabilities (`adherence` how strongly rank decides), `weights->pulses` keeps the strongest `density` of them. `zip` reads keywords itself (`:articulation '[:staccato] zip` names a part), so a setting standing right before it is fetched with `T@`.

```
mu> '[2 2 3] :subdivisions T! 0.8 :adherence T! 0.5 :density T! '[67 64 60] :tones T!

mu> '( :subdivisions indispensability/indisp :adherence indispensability/tilt :density bridge/weights->pulses :pulse bridge/pulses->durations :tones T@ zip ) :thinned T!

mu> :thinned T@@
[G4/8. E4/8 C4/16 G4/8. E4/8 C4/16]
```

#### 18. Swap one stage: strong beats silent

`power` in place of `tilt`, with a negative adherence: the weak pulses win. The settings are the same ones; only one word of the line changes.

```
mu> -0.8 :adherence T!

mu> '( :subdivisions indispensability/indisp :adherence indispensability/power :density bridge/weights->pulses :pulse bridge/pulses->durations :tones T@ zip ) :weak_wins T!

mu> :weak_wins T@@
[r16 E4 C4/8 G4/8. E4/16 C4/8 G4]
```

#### 19. One weighted choice

`random/weighted-choose` draws one of the values (here the nine pulses `0 9 range`) with the weights it is given. The seed is a setting too: `:seed rng/seed` at the start of a computed key makes every run of it the same.

```
mu> 7 :seed T! 0 9 range :vals T! '[3 3] :subdivisions T! 1.0 :adherence T!

mu> '( :seed rng/seed :vals :subdivisions indispensability/indisp :adherence indispensability/tilt random/weighted-choose ) :choice T!

mu> :choice T@@
4
```

### Melody generators

#### 20. A Markov melody trained on a motif

`markov-train` learns the motif (its `order`), `markov-gen` walks the model for `length` notes.

```
mu> 7 :seed T! 11 :onsets T! 16 :pulses T! '[60 62 64 62 60 67 65 64] :melody T! 1 :order T! 11 :length T!

mu> '( :seed rng/seed :onsets :pulses pattern/euclid :pulse bridge/pulses->durations :melody :order melody/markov-train :length melody/markov-gen zip ) :markov T!

mu> :markov T@@
[C4/8 G4/16 F4/8 E4/16 D4/8 C4/16 D4/8 E4/16 D4/8 E4/16 D4]
```

#### 21. Slonimsky: insertions below, between, above

`slonimsky/infra` puts a pattern after each principal tone but the last (tones you chose to lie below them); `slonimsky/polation2` does infra, inter and ultra at once, in that order.

```
mu> '[60 64 67 72] :principal T! '[59 62] :infra T! '[65] :inter T! '[62] :ultra T!

mu> '( :principal :infra slonimsky/infra ) :below T!

mu> :below T@@
'[60 59 62 64 59 62 67 59 62 72]
mu> '( :principal '[] :inter :ultra slonimsky/polation2 ) :around T!

mu> :around T@@
'[60 65 62 64 65 62 67 65 62 72]
```

#### 22. An L-system melody

`melody/lsys-melody` expands an axiom with rules and reads each letter as a note. The rules and the letters' pitches are maps, which mu text cannot write yet, so this one is only shown: `see` gives its parameters.

```
mu> see melody/lsys-melody
melody/lsys-melody ( axiom rules note-map iterations max-notes -- lsys-melody )
  Expand axiom through rules `iterations` times; each character in note-map becomes that pitch, others are skipped.
```

#### 23. A constrained walk

`melody/constrained` walks a scale: each note is drawn from those that every constraint accepts. A constraint is a function of the melody so far and the candidate note, and a quotation is one: `( '( … ) '( … ) )` gathers quotations into the list, here inside the computed key. The first constraint refuses a repeated note (`swap last =` compares the candidate with the last note), the second keeps every step within a whole tone (`swap last - abs 3 <`).

```
mu> 7 :seed T! '[60 62 64 65 67 69 71 72] :scale T! 12 :length T!

mu> '( :seed rng/seed :scale :length '[] melody/constrained ) :free_walk T!

mu> :free_walk T@@
'[64 60 71 60 62 62 60 62 72 64 67 64]
mu> '( :seed rng/seed :scale :length ( '( swap last = not ) '( swap last - abs 3 < ) ) melody/constrained ) :constrained_walk T!

mu> :constrained_walk T@@
'[60 62 60 62 64 62 60 62 60 62 64 65]
```

#### 24. A melody that modulates

Segments of `[key length]` pairs, a key being `'[:C :major]`, and the constraints they share, as in the walk above. The result is pitch classes of the keys; `60 +` lifts them to the middle of the keyboard.

```
mu> 7 :seed T! '[ '[ '[:C :major] 6] '[ '[:E :minor] 6] ] :segments T! 60 :lift T!

mu> '( :seed rng/seed :segments ( '( swap last = not ) ) melody/modulating :lift + ) :modulating T!

mu> :modulating T@@
'[62 69 67 64 65 69 69 62 69 71 66 71]
```

#### 25. Two-voice counterpoint

`jeppesen/species` takes the cantus, the number of voices, the species of the added voice, which part holds the cantus, the mode (`:auto` finds both) and a seed for the search. It gives one line per voice. `:auto` is no setting, so it stays itself.

```
mu> '[55 57 59 60 62 64 65 67 69 71 72 74 76] :cantus T! 2 :voices T! 1 :kind T! 1 :search_seed T!

mu> '( :cantus :voices :kind :auto :auto :search_seed jeppesen/species ) :counterpoint T!

mu> :counterpoint T@@
'[[G4/1 F4 G4 G4 F4 G4 A4 B4 C5 D5 E5 F5 E5] [G3 A3 B3 C4 D4 E4 F4 G4 A4 B4 C5 D5 E5]]
```

### Metric generators

#### 26. A number's bits, a fraction's expansion

45 is 101101: onsets on bits 0, 2, 3 and 5. A function with a keyword tail takes `:key value` pairs written before it: `:length 8`, the key `:length` staying itself because the table has no such setting. π is [3; 7, 15, 1, …].

```
mu> 45 :number T! 8 :width T! 3.141592653589793 :fraction T! 16 :terms T!

mu> '( :number :length :width pattern/bits :pulse bridge/pulses->durations penta zip ) :bits T!

mu> :bits T@@
[C4/8 D4/16 E4/8 G4/8.]
mu> '( :fraction :terms pattern/cfrac :pulse bridge/pulses->durations penta zip ) :expansion T!

mu> :expansion T@@
[C4/16 D4 E4 G4 A4 C4 D4 E4 G4 A4/4 C4/16 D4 E4]
```

#### 27. Modular arithmetic as a rhythm

An onset wherever `multiplier · i + offset` is a multiple of `modulus`: `modulus multiplier length offset`. Name a setting for the argument, and none for a keyword another word reads: a table with a `:length` in it would make the `:length 8` of the recipe above a number.

```
mu> 5 :modulus T! 2 :multiplier T! 15 :span T! 0 :offset T!

mu> '( :modulus :multiplier :span :offset pattern/modular :pulse bridge/pulses->durations penta zip ) :modular T!

mu> :modular T@@
[16:5[C4/1 D4 E4]]
```

### Random sources

#### 28. A sampler is a function: pull n draws

`random/triangular` gives one draw per call; `pull` calls a quotation n times and collects the results. The quotation inside the computed key reads the settings as well. The bridge puts the draws on pitches, the range they really have being its second and third arguments.

```
mu> 7 :seed T! 0.0 :lo T! 1.0 :hi T! 0.5 :mode T! 12 :draws T! 60 :low T! 73 :high T!

mu> '( :seed rng/seed '( :lo :hi :mode random/triangular ) :draws pull :lo :hi :low :high bridge/numbers->pitches ) :triangular T!

mu> :triangular T@@
'[66 67 69 73 68 71 66 63 66 67 67 67]
```

#### 29. Any numbers, rescaled onto a scale

Scale the draws to the scale steps you want and round them (`'( round ) map`); `degrees->pitches` spells whole steps in a key and octave. A key name with a hyphen goes in quotes.

```
mu> 0.0 :mean T! 1.0 :stdev T! 3 :spread T! 4 :centre T!

mu> '( :seed rng/seed '( :mean :stdev random/normal ) :draws pull :spread * :centre + '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches ) :normal T!

mu> :normal T@@
'[69 76 57 84 60 69 76 62 62 69 76 74]
```

#### 30. A random walk, and a biased one

`walk/walk` moves by at most `step-bound`; `biased-walk` also leans one way (above 0.5 up). A function that keeps its place is `pull`ed.

```
mu> 16 :draws T! 4.0 :start T! 1.0 :step_bound T! 0.7 :bias T!

mu> '( :seed rng/seed :start :step_bound walk/walk :draws pull '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches ) :walking T!

mu> :walking T@@
'[72 72 74 74 72 72 72 72 72 69 69 69 69 69 72 72]
mu> '( :seed rng/seed 60.0 :step_bound :bias walk/biased-walk :draws pull ) :leaning T!

mu> :leaning T@@
'[59.20 59.12 58.39 59.29 59.08 59.86 59.27 59.40 59.93 60.01 60.64 60.90 61.57 60.82 61.44 62.08]
```

#### 31. Chaos: the logistic map, the Lorenz attractor

`logistic` gives numbers in (0, 1) from a growth rate and a start; `lorenz` a point per step, `points->pitches` reads one coordinate onto the steps of a key. `pull` also takes a generator that holds a `:value` function, as lorenz does.

```
mu> 3.9 :r T! 0.5 :x T! 12 :draws T!

mu> '( :r :x logistic/logistic :draws pull 9 * '( round ) map "C.pentatonic-major" 4 bridge/degrees->pitches ) :logistic T!

mu> :logistic T@@
'[81 62 67 79 69 81 62 69 81 62 67 79]
mu> 24 :points T!

mu> '( 10.0 28.0 2.667 1.0 1.0 1.0 0.03 lorenz/lorenz :points pull 0 -20.0 20.0 "C.pentatonic-major" 4 2 bridge/points->pitches ) :lorenz T!

mu> :lorenz T@@
'[72 72 72 72 72 74 74 76 76 79 81 81 79 76 74 72 …]
```

#### 32. A Markov chain over scale degrees

`markov/chain` walks a table of transitions between states. The table is a map, which mu text cannot write yet, so this one is only shown.

```
mu> see markov/chain
markov/chain ( transitions start-state -- chain )
  Returns a function that walks through states using transition weights. transitions: {state {next-state weight, ...}, ...} Example: (chain {:C {:G 2 :F 1} :G {:C 1 :A 1}} :C)
```

#### 33. Poisson onsets become durations

`poisson` gives event times from a rate and a duration; `onsets->durations` reads the gaps between them as note values, `unit` being the note value of one time unit and `quantum` the grid they are rounded to.

```
mu> 3.0 :rate T! 4.0 :duration T! 1/4 :unit T! 1/32 :quantum T!

mu> '( :seed rng/seed :rate :duration process/poisson :unit :quantum bridge/onsets->durations penta zip ) :poisson T!

mu> :poisson T@@
[C4/16. D4/8.. E4/32 G4/16. A4/32 C4/16. D4/32 E4 G4/16 A4 C4 D4/8 E4/32]
```

#### 34. Smooth noise as a rhythm

`walk/smooth-noise` makes a curve, a function of t, from `n` random points between `lo` and `hi`; `map` samples it at sixteen places, and `data-rhythm` puts an onset wherever a value is above the median.

```
mu> 6 :n T! 16 :places T!

mu> '( :seed rng/seed 0 :places range 3 / :n :lo :hi walk/smooth-noise map "median" 1 data/data-rhythm :pulse bridge/pulses->durations penta zip ) :noise T!

mu> :noise T@@
[r8 D4/16 E4 G4 A4 C4 16:5[D4/1] E4/16 G4/8.]
```

### Rhythm generators

#### 35. A tala's theka, a named bell pattern

Two lines of one kind, so two computed keys.

```
mu> "jhaptal" :tala_name T! '[16 8] :meter T! "bossanova" :pattern_name T!

mu> '( :tala_name world/theka :pulse bridge/pulses->durations penta zip ) :theka T!

mu> :theka T@@
[C4/8 D4/16 E4/8 G4 A4/16 C4/8]
mu> '( :meter :pattern_name world/bell :pulse bridge/pulses->durations '[60 64 67] zip ) :bell T!

mu> :bell T@@
[C4/8. E4 G4/4 C4/8 E4/4]
```

#### 36. Fractal rhythms: Cantor set, dragon curve

`cantor` takes `iterations` and `length`; `dragon` takes `iterations`.

```
mu> 2 :iterations T! 9 :length T!

mu> '( :iterations :length pattern/cantor :pulse bridge/pulses->durations penta zip ) :cantor T!

mu> :cantor T@@
[C4/8 D4/4 E4/8 G4/16]
mu> 3 :dragon_iterations T!

mu> '( :dragon_iterations pattern/dragon :pulse bridge/pulses->durations penta zip ) :dragon T!

mu> :dragon T@@
[C4/16 D4/8 E4/16 G4/8. A4/16 C4 D4/8. E4]
```

#### 37. A polyrhythm, one layer per voice

`polyrhythm` takes the layers, each `[onsets grid]`, and a length, and gives a list of layers; `0 @` picks one: three against two. One setting, the layer, picks which.

```
mu> '[ '[3 8] '[2 8] ] :layers T! 24 :length T! 0 :layer T!

mu> '( :layers :length meter/polyrhythm :layer @ :pulse bridge/pulses->durations '[48 55] zip ) :layer_line T!

mu> :layer_line T@@
[C3/2 G3 C3]
mu> 1 :layer T!

mu> :layer_line T@@
[C3/2. G3]
```

#### 38. A polyrhythm as two parts

Each layer its own line, each started by its own `|` mark so that `zip` joins only that part; `{2}` puts the two side by side.

```
mu> '( | :layers :length meter/polyrhythm 0 @ :pulse bridge/pulses->durations '[48] zip | :layers :length meter/polyrhythm 1 @ :pulse bridge/pulses->durations '[55] zip {2} ) :two_parts T!

mu> :two_parts T@@
{[C3/2 C3 C3] [G3/2. G3]}
```

#### 39. Clapping Music: the pattern against itself, shifted

`duet` gives the pattern and the same pattern `phase` pulses on; `1 @` picks the shifted one.

```
mu> '[1 1 1 0 1 1 0 1 0 1 1 0] :pattern T! 3 :phase T!

mu> '( :pattern :phase sieve/duet 1 @ :pulse bridge/pulses->durations '[72] zip ) :clapping T!

mu> :clapping T@@
[r16 C5 C5/8 C5 C5/16 C5/8 C5/16 C5 C5]
mu> 5 :phase T!

mu> :clapping T@@
[C5/8 C5 C5/16 C5/8 C5/16 C5 C5/8 C5/16]
```

#### 40. A genetic rhythm: the fitness function is a quotation

A quotation `'( … )` is a function of what it is given, so it can be the fitness function: this one scores a pattern by how close its onset count is to five. It is written inside the computed key, because a quotation given to `T!` would be taken for a computed setting.

```
mu> 16 :population_size T! 16 :pattern_length T! 20 :generations T!

mu> '( :seed rng/seed :population_size :pattern_length :generations '( sum 5 - abs negate ) process/genetic :pulse bridge/pulses->durations penta zip ) :genetic T!

mu> :genetic T@@
[C4/8. D4/16 E4/8 16:9[G4/1] A4/16]
```

#### 41. Swing: a grid becomes onset times, then durations

`swing` turns pulses into swung onset times (a ratio and the steps per beat); `onsets->durations` reads them back, a beat being `unit`.

```
mu> 8 :onsets T! 8 :pulses T! 0.67 :swing_ratio T! 2 :subdivision T! 1/4 :unit T! 1/32 :quantum T!

mu> '( :onsets :pulses pattern/euclid :swing_ratio :subdivision feel/swing :unit :quantum bridge/onsets->durations penta zip ) :swing T!

mu> :swing T@@
[32:5[C4/1] D4/16. 32:5[E4/1] G4/16. 32:5[A4/1] C4/16. 32:5[D4/1]]
```

#### 42. A bouncing ball, in seconds

Times in seconds: height, restitution, gravity and duration; `unit` says how long one second is as a note value.

```
mu> 1.0 :initial_height T! 0.8 :restitution T! 9.8 :gravity T! 5.0 :duration T! 1/4 :unit T! 1/64 :quantum T!

mu> '( :initial_height :restitution :gravity :duration nature/bounce :unit :quantum bridge/onsets->durations '[72 67] zip ) :bounce T!

mu> :bounce T@@
[C5/16.. G4/16. 64:5[C5/1] G4/16 C5/32. G4/32 C5 G4 C5/64 G4 C5 G4]
```

#### 43. Tuplet-like durations by repeated splitting

`decompose/split` takes a `ratio`-sized bite of what remains, `depth` times.

```
mu> 1 :duration T! 5 :depth T! 2/3 :ratio T!

mu> '( :duration :depth :ratio decompose/split penta zip ) :split T!

mu> :split T@@
[3:2[C4/1] 9:2[D4] 27:2[E4] 81:2[G4] 81:1[A4]]
```

#### 44. Text as rhythm

A beat on each word's first syllable; density 1.0 lets every accent sound.

```
mu> "Composing trees of algorithms is plain Clojure." :text T! 1.0 :density T!

mu> '( :text data/text-rhythm :density bridge/weights->pulses :pulse bridge/pulses->durations penta zip ) :text_rhythm T!

mu> :text_rhythm T@@
[C4/8. D4/16 E4 G4/8. A4/16 C4 D4/4]
```

#### 45. Vary a rhythm: EMI style, Oblique Strategies

`emi` takes a pattern and a similarity (1 leaves it as it is); `oblique` a pattern and the name of a strategy.

```
mu> 5 :onsets T! 16 :pulses T! 0.6 :similarity T! "mirror" :strategy T!

mu> '( :seed rng/seed :onsets :pulses pattern/euclid :similarity feel/emi :pulse bridge/pulses->durations penta zip ) :emi T!

mu> :emi T@@
[C4/8. D4/8 E4 G4 A4/16 C4 D4/4]
mu> '( 3 8 pattern/euclid :strategy feel/oblique :pulse bridge/pulses->durations penta zip ) :oblique T!

mu> :oblique T@@
[C4/8. D4 E4 G4 A4 C4/16]
```

#### 46. Cross two rhythms

`process/crossover` takes the first part of one pattern and the rest of the other, at a point; `child` 0 or 1 says which way round.

```
mu> '[1 0 1 1 0 1 0 0] :a T! '[1 0 0 1 0 0 1 0] :b T! 4 :crossover_point T! 0 :child T!

mu> '( :a :b :crossover_point :child process/crossover :pulse bridge/pulses->durations penta zip ) :crossover T!

mu> :crossover T@@
[C4/8 D4/16 E4/8. G4/8]
mu> 1 :child T!

mu> :crossover T@@
[C4/8. D4/8 E4/8.]
```

### Algorithms of your own

#### 47. A pitch transform

A word of your own is an algorithm: `: name ( inputs -- results ) … ;`. This one moves every pitch down an octave, and works on pitches before `zip`. It goes into a computed key like any word.

```
mu> : down ( l -- l' ) 12 - ;

mu> 3 :onsets T! 8 :pulses T!

mu> '( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta down zip ) :lower T!

mu> :lower T@@
[C3/8. D3 E3/8]
```

#### 48. Required arguments and their limits

Every argument is required: there are no defaults, and a function says how many it needs and what it cannot take.

```
mu> 16 16 20 process/genetic
error: process/genetic: needs 4 items on the stack, has 3
  in process/genetic (input:1:10)
mu> 99 8 pattern/euclid
error: pattern/euclid: refused, it needs <= onsets pulses
  in pattern/euclid (input:1:6)
```

### Two of a kind

#### 49. Two instances of one algo

A key has one value, so two instances of one algorithm need two sets of keys: name them for the part they play. A computed key gives one value, so `( … )` gathers the two into a list.

```
mu> 2 :bass_onsets T! 5 :lead_onsets T! 16 :pulses T!

mu> '( ( :bass_onsets :pulses pattern/euclid :lead_onsets :pulses pattern/euclid ) ) :two_euclids T!

mu> :two_euclids T@@
'['[1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0] '[1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]]
```

#### 50. Different algos, one setting

A setting is read wherever a word names it, so one key can feed two different algorithms, in whatever position each has its argument.

```
mu> 5 :onsets T! 8 :pulses T!

mu> '( :onsets :pulses pattern/euclid ) :euclid_row T!

mu> :euclid_row T@@
'[1 0 1 1 0 1 1 0]
mu> '( :pulses :onsets 1 sieve/necklaces ) :necklace T!

mu> :necklace T@@
'[1 1 1 1 0 1 0 0]
```

### Parameters on the stack: a word with inputs

#### 51. One line, several parameters: a word with inputs

The tctx is one way to name the parameters. The other is a word of your own with `$` inputs, which are local to it: the numbers go on the stack before the word, in the order of its stack effect, and the word needs no table.

```
mu> : riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;

mu> 2 8 riff
[C4/4 D4]
mu> 7 8 riff
[C4/16 D4 E4 G4 A4 C4 D4/8]
```

#### 52. Another word over the same inputs

Words that share inputs just share names. `see` shows a word's stack effect with its inputs.

```
mu> see riff
: riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;
mu> : lift ( $notes $by -- notes' ) $notes $by + ;

mu> 2 8 riff 7 lift
[!K:G.major G4/4 A4]
```

#### 53. Defaults are a word

A default is a definition that fills in what you leave out; the arguments you give win.

```
mu> : groove ( $onsets -- pulses ) $onsets 16 pattern/euclid ;

mu> 5 groove
'[1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
mu> 7 groove
'[1 0 0 1 0 1 0 1 0 0 1 0 1 0 1 0]
```

#### 54. Look inside: see and the stack

`see` shows a word's stack effect and what it does; `.s` shows the stack.

```
mu> see pattern/euclid
pattern/euclid ( onsets pulses -- euclid )
  Spread onsets beats evenly over a grid of pulses (Bjorklund's algorithm, Toussaint's "The Euclidean Algorithm Generates Traditional Musical Rhythms"). Rotate the result with `rotate`. (euclid 3 8) ;=> [1 0 0 1 0 0 …
mu> see bridge/pulses->durations
bridge/pulses->durations ( pulses pulse -- pulses->durations )
  Each onset lasts until the next one; a 0 lengthens the note before it, and 0s before the first onset are one Rest. (also pul>dur)
mu> 3 8 pattern/euclid .s
0> '[1 0 0 1 0 0 1 0]
----
'[1 0 0 1 0 0 1 0]
```

### Live

#### 55. A voice that reads the settings as it plays

A word with `$onset` among its inputs is played as a voice that is a function of the onset: the engine asks it for one note at the onset the voice has reached, and while it runs a keyword the tctx holds stands for its value, `:onset` being the onset itself. The settings are looked up note by note, so `3 :steps T!` is heard at the next note, with no input to hand over. This one climbs `:steps` semitones from middle C and begins again. Shown, not run: nothing sounds when this page is made.

```
mu> 8 :steps T!

mu> : tick ( $onset -- note ) | '[1/16] '[1] 60 :onset 16 * int :steps mod + * zip ;

mu> 'tick :v ->
mu> 3 :steps T!
mu> stop
```

#### 56. A live word, changed while it plays

A word with `$` inputs plays as a voice: the quoted name keeps it from running now, the voice runs it again whenever an input changes. `:bass/onsets` is an input, an entry of the voice's scope in the tctx, read with `T@` and stored with `T!`. Shown, not run: nothing sounds when this page is made.

```
mu> 5 8 'riff :bass ->
mu> :bass settings
mu> 3 :bass/onsets T!
mu> :bass/onsets T@
mu> stop
```

#### 57. A transform: reshape any voice's own notes

A quotation under a play word runs on the music under it, note by note. Shown, not run.

```
mu> [c d e g] [~] '( 5 + ) :up ->
mu> '( 7 + ) :up retree
```

### Material

#### 58. Generated notes become committed musics text

`R!` stores what is on the stack under a name; `R@` reads it back, and `write` or `save-session` keeps it.

```
mu> 3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip :demo R!
:demo
mu> :demo R@
[demo: C4/8. D4 E4/8]
```

