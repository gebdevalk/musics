# Namespaces

Every namespace under `src/`, by directory, with its size in lines and its
role. The program is used through **mu** (`musics.mu`, `lein mu`) and the
**GUI** (`musics.gui.core`, mu's `gui`); *unreached* marks a namespace
neither of them loads (only tests do). about 109 namespaces, 32,000 lines.

## The tree

```
musics
├── algo
│   ├── bridge
│   ├── common
│   │   ├── gate
│   │   ├── isorhythm
│   │   ├── pulse
│   │   ├── reshape
│   │   ├── rotate
│   │   ├── split
│   │   ├── transient-ops
│   │   └── zfilter
│   ├── counterpoint
│   │   ├── imitation
│   │   └── jeppesen
│   │       ├── intervals
│   │       └── rules
│   ├── harmonic
│   │   ├── progression
│   │   └── walking-bass
│   ├── logic
│   │   └── tree
│   ├── melodic
│   │   ├── melody
│   │   ├── pitch
│   │   └── serial
│   ├── numeric
│   │   ├── chaos
│   │   │   ├── henon
│   │   │   ├── logistic
│   │   │   └── lorenz
│   │   ├── clam
│   │   ├── farey
│   │   ├── integer
│   │   ├── markov
│   │   ├── random
│   │   ├── rng
│   │   ├── scaling
│   │   ├── spec
│   │   ├── trig
│   │   └── walk
│   ├── rhythmic
│   │   ├── data
│   │   ├── decompose
│   │   ├── drums
│   │   ├── feel
│   │   ├── indispensability
│   │   ├── meter
│   │   ├── nature
│   │   ├── pattern
│   │   ├── process
│   │   ├── sieve
│   │   └── world
│   └── tree
│       ├── builder
│       ├── half-products
│       ├── lib
│       ├── live
│       └── registry
├── assist
├── common
│   ├── context-keys
│   ├── music-data
│   ├── music-elements
│   └── music-tools
├── compose
├── conductor
├── domain
│   ├── context
│   ├── ornaments
│   └── resolve
├── engine
├── events
├── feedback
├── gui
│   ├── components
│   ├── composer
│   ├── core
│   ├── data
│   ├── params
│   ├── state
│   └── theme
├── input
│   ├── abc-import
│   ├── guido-import
│   ├── lilypond-import
│   ├── midi
│   ├── midi-record
│   └── reader
│       ├── builder
│       ├── leaf-parser
│       └── walker
├── kernel
├── midi
│   ├── file
│   ├── gervill
│   └── live
├── mu
│   ├── algos
│   ├── complete
│   ├── eval
│   ├── feedback
│   ├── grammar
│   ├── kernel
│   ├── library
│   ├── live
│   ├── material
│   ├── print
│   ├── store
│   ├── tools
│   └── value
├── music
├── persist
├── play
├── rate
├── recipe
├── registries
├── repo
├── tool
├── version
└── wall
```

## The interfaces

```
musics.mu                    182   mu, the musical REPL: the prompt loop (JLine)
├── mu.grammar                31   musics.ebnf + mu.ebnf, start MuInput
├── mu.eval                  415   the evaluator: literals read up to each :=, items run on the stack
├── mu.value                 168   what a literal becomes: a leaf or a Material, not stored
├── mu.print                 153   printing, isomorphic to reading
├── mu.material             1098   what the words share: elements, lines, repeats, zip's leaves and bundles, storing, playing
├── mu.store                  56   text read into the repo: a file, the GUI editor (commits :ROOT)
├── mu.library                52   registers the word namespaces below as mu words
├── mu.kernel                222   stack words: dup swap … quotations, :=, the play words
├── mu.tools                 187   tool words that read the stack's shape: seq par zip {n} [n] volta …
├── mu.algos                 165   the algos as words by shelf: pitch/lorenz, duration/euclid …
├── mu.live                  157   a play word given a recipe plays live
├── mu.complete              163   Tab completion
└── mu.feedback              151   rating what is heard, audition
musics.kernel                257   kernel words: numbers, the repo, namespaces, require, help, files, load, render
musics.tool                  309   tool words: making, reading, reshaping
musics.music                 576   music words: transposing, row forms, the readers, as-drums …
musics.play                  165   play words: stop, voices, hush, set, settings, retree, compose, gui
musics.rate                   74   rate words: like, dislike, audition …
musics.soundfont             113   soundfont words: soundfonts, list-groups, try-soundfonts …
musics.feedback               92   the ratings file
musics.recipe                190   tree recipes @( … ): RPN words built into a tree and its tctx, run lazily; stored as their text
musics.version                18   mu's version, 0.3.<merges>.<commits>, counted by git

musics.gui.core             1237   cljfx entry: the windows
├── gui.state               1749   the model: the render-state atom and every action
├── gui.components           288   reusable controls: sliders, combos, param-control
├── gui.composer             311   the tree composer window (mu's compose)
├── gui.params               157   one tctx's settings window (mu's x gui)
├── gui.data                  74   read-only reference tables for the controls
└── gui.theme                 56   dark/light theming
musics.assist                263   what can be done now: action facts in core.logic (the GUI's buttons)
```

## Material: text into the store

```
musics.input.reader.walker   1836   the parse tree walked into domain nodes
├── reader.builder            407   the container stack, auto-ids, context chains
└── reader.leaf-parser        477   pitch, duration, articulation, dynamic of one leaf
musics.input.lilypond-import 2030   LilyPond → musics text (mu's load of .ly)
musics.input.abc-import       598   ABC → musics text (.abc)
musics.input.guido-import     562   GUIDO → musics text (.gmn)
musics.input.midi             128   MIDI input, midi-through (the GUI's MIDI Input)
musics.input.midi-record      326   a performance recorded as musics text (Record MIDI)

musics.domain                 910   the domain model: leaves, containers, iterators, duration
├── domain.context            552   contexts and envelopes: values in time
├── domain.resolve            624   a leaf's MIDI event from its context chain; ctx, ctx-value
└── domain.ornaments          280   ornaments, tremolo and grace as sub-leaves
musics.repo                   165   the flat id → node store
musics.registries             140   all mutable global state in one place
musics.persist                301   the store as EDN: write, load, sessions

musics.common.music-data      713   reference data: quantities, scales, instruments, drums …
musics.common.music-elements  694   keys, meters, indispensability wiring, transposition
musics.common.context-keys    155   the !key: registry: names, aliases, ranges
musics.common.music-tools      52   small music-theory helpers
```

## Sound: the store into sound

```
musics.events                 396   a Form's performance as a lazy seq of timed events
musics.compose                677   the play-arg Form grammar; display, display-timed; sq
musics.engine                 815   live playback: one sender thread, 100 ms ahead; the MIDI output
musics.conductor              209   boundaries → named, scheduled actions
musics.wall                    59   name → per-voice algo (live trees and recipes fill it)
musics.midi.live              225   MIDI output through a Receiver (Fluidsynth)
musics.midi.gervill           105   Gervill, the JDK's synthesizer
musics.midi.file              230   MIDI files (mu's render)
```

## Algorithms

```
musics.algo.tree                               0   trees of algos and their tctx: build, check, keys, run
├── tree.registry                              0   every algo, found by introspection; shelves
├── tree.lib                                   0   the tools (zip, cycle>, take> …) and every algo exposed
├── tree.half-products                         0   algos as the half products they deliver (duration/euclid …)
├── tree.live                                  0   a name bound to a tree or a recipe, followed live
└── tree.builder                               0   the composer's draft model
musics.algo.bridge                             0   raw material → half products, one way
musics.algo.logic.tree                         0   the registry as core.logic facts: how, why-not, fits

musics.algo.numeric.*                           numbers for any musical parameter
├── random                                     0   draws, distributions, picks (uniform, normal, beta, int-*, choose …)
├── spec                                       0   param specs shared by the generators' :algo metadata
├── walk                                       0   random walks, pink noise, glide, tendencies
├── markov                                     0   a Markov chain over a transition table
├── rng                                        0   the xorshift engine, seeding
├── chaos.logistic                             0   logistic map (Verhulst)
├── chaos.henon                                0   Hénon map
├── chaos.lorenz                               0   Lorenz attractor
├── integer                                    0   gcd, lcm
├── scaling                                    0   clamp, scale, bound
├── trig                                       0   waves
├── farey                                      0   rational approximation
└── clam                                       0   (nothing requires it)
musics.algo.rhythmic.*                          rhythm: pulses, onsets, layers
├── pattern                                    0   euclid, fibonacci, primes, L-system, markov; modular, bits, cfrac; cantor, dragon, golden, polygon, fifths
├── sieve                                      0   Xenakis sieves, Reich phases, cellular automata, necklaces, bracelets, tilings
├── meter                                      0   polyrhythm, polymeter, tuplets, metric modulation
├── indispensability                           0   Barlow's indispensability, tilt and power adherence
├── process                                    0   random events, clouds, genetic, RNN, constraint satisfaction
├── nature                                     0   pendulum, bounce, rain, heartbeat, birdsong
├── data                                       0   data, text, trends as rhythm
├── world                                      0   tala, theka, bell patterns, hemiola, djembe
├── feel                                       0   swing, pocket, EMI variation, oblique strategies
├── drums                                      0   drum grooves by style
└── decompose                                  0   bisect, split
musics.algo.melodic.*                           pitches
├── melody                                     0   markov, L-system, grammar, constraints, modulation
├── serial                                     0   rows, set classes, polations
└── pitch                                      0   scales from keys
musics.algo.harmonic.*                          chords
├── progression                                0   roman-numeral progressions
└── walking-bass                               0   a walking bass under chords
musics.algo.counterpoint.*                      counterpoint
├── jeppesen                                   0   species counterpoint after Jeppesen, in core.logic
├── jeppesen.intervals                         0   notes, intervals, modes
├── jeppesen.rules                             0   the rules as checks
└── imitation                                  0   motif imitation, best-effort species rules
musics.algo.common.*                            helpers shared by several algos
├── rotate                                     0   rotation
├── gate                                       0   gate criteria
├── isorhythm                                  0   color-talea
├── pulse                                      0   grid → pulses
├── reshape                                    0   reshaping recipes
├── split                                      0   voice splitting
├── transient-ops                              0   times, tuplet, transpose on material
└── zfilter                                    0   recurrence filters
```

## Unreached

Loaded only by tests: `musics.algo.numeric.{farey,trig,clam}`, `musics.algo.common.{split,gate,isorhythm,pulse,reshape,transient-ops,zfilter,rotate}`
and `musics.algo.harmonic.progression`; nothing requires `numeric.clam` at all.

