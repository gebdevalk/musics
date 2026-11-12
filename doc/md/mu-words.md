# mu: the words

The reference for mu's words, by group, with what each takes and gives. The design behind
them (what a seq contains, instructions and contexts, memory, the REPL itself) is in
[mu-repl.md](mu-repl.md); a guided tour of the words is [mu-tutorial.md](mu-tutorial.md);
in mu itself `words` lists them, `"dup" see` says what one takes and gives, `"chord" help`
lists the ones that mention chord, and Tab completes.

mu's own words are **tools** (algo space's kind without a manner) and
the REPL's **kernel**. The **algos** come in through their namespaces
as plain functions called by name (`pattern/euclid`): see
**Algos** below.

### Kernel

| Group | Words |
|---|---|
| **stack** | `dup` `drop` `swap` `over` `rot` `-rot` `nip` `tuck` `pick` `roll` `2dup` `2drop` `1+` `1-` `depth` `spread` (`**`) `clear` (the stack) `.s` `.` |
| **numbers** | `+` `-` `*` `/` (also on lists of numbers and on music, below) `mod` `negate` `abs` `min` `max` `round`; conversions `int` `float` `rational` |
| **comparison, flags** | `=` `<>` (any values; music by its notes) `<` `>` `<=` `>=` (numbers, or anything by a key); `true` `false` `and` `or` `xor` `not` |
| **quotations** | `loop` `+loop` (`i` `j` `k`) `if` `when` `each` `reduce`; `'dup` quotes a word, `execute` runs it (a quoted word, or a quotation or function) |
| **memory** | `'name` `:=` `\name` `R!` `R!!` `R@` (`store` `overwrite` `fetch`) |
| **defining, namespaces** | `:` `;` `use` `in` `require` |
| **playback** | `->` or `play` (stops what plays first), `->+` or `play+` (joins it now), `-\|>` or `play-next` (in its place, from the next boundary), `-\|>+` or `play-next+` (joins it, from the next boundary), `stop` (or `:bass stop`, one voice), `pause` (pause, or resume if paused), `resume`; voices: `voices` or `<<-`, `playing` or `<-`, `hush`, `solo` |
| **live** | a play word with a quotation `'( … )` under its voice and context plays live: `x '( 5 + ) :up ->`, `\| '( … ) :riff ->` from nothing (a mark, or nothing, under the quotation); `retree` (`'( 7 + ) :up retree`) gives the voice another quotation, which goes on at its position. A word written with `$` inputs plays live too, its inputs the voice's changeable values: `5 8 'riff :bass ->`, `:bass/onsets @`, `3 :bass/onsets !` (checked against the range of the algo parameter of the same name), `:bass settings`, `:bass gui` (a window of its inputs, a control each, under the algo it belongs to), `:bass ->` starts it again with the inputs as they are -- `musics.mu.live`, `musics.mu.voice`, `musics.mu.inputs` |
| **synthesizer** | `fluidsynth`, `gervill` (Java's own), `echo` (print each note as it sounds -- `x\38 Acoustic Snare`, `\i:40 violin  C4` -- or stop) |
| **soundfonts** | the `soundfont` namespace: `soundfonts` (the `.sf2` files, numbered), `list-soundfonts` (number and name, aligned), `soundfont` (a file in quotes, or a number from `soundfonts`), `list-groups` (General MIDI's groups of eight), `try-soundfonts` ( [x] [n] [pick] -- ): x, or a phrase of its own, through every instrument of every soundfont, as an audition -- n one soundfont, a pick `'[56 63]` programs, `"brass"` a group, `"horn"` the instruments named so |
| **tctx** | `T!` (`8 :steps T!`; a quotation, `'( :steps 2 * ) :twice T!`, is a computed key), `T@` (`:steps T@`; a computed key as its quotation), `T@@` (`:twice T@@`, run), `T_` (`:steps T_` takes a key out): the keyword-keyed settings a voice that is a function of the beat reads |
| **repo** | `ids` `usages` `ctx` `defaults`; `forget` `rename` |
| **help** | `about` (what a name is and where it can appear: `about tempo` says it is a musical parameter and can appear in `^{ }`, `{ }` and `[ ]`; also a mark, a word, an instrument, a drum, a scale, a grammar rule; `pitch`, `duration`, `articulation` are properties of a note, a duration also the length of a seq), `settings tempo` (a parameter's range, default and how it is written; `:riff settings` is still a live voice's), `marks tempo` (the marks that set it, by value), `help` (mu in a few seqs; `help dup` or `'dup help` is `see`; other text, `"chord" help`, lists the words whose name or description mention it), `words`, `see` (`"dup" see` or `'dup see`: the stack effect, then what the word does -- every built-in word has a description, and a word that gives its first argument back changed or rebuilt shows it primed: `( x n -- x' )` for `take`, `sort`, `times`, `+`; a defined word shows its source), `cls` (the screen; the stack stays); `use` and `in` take a name in quotes or quoted too (`'pitch use`) |

Stack effects:

| Word | Effect | Does |
|---|---|---|
| `dup` | `( a -- a a )` | copy the top |
| `drop` | `( a -- )` | discard the top |
| `swap` | `( a b -- b a )` | exchange the top two |
| `over` | `( a b -- a b a )` | copy the second over the top |
| `rot` | `( a b c -- b c a )` | rotate the top three |
| `nip` | `( a b -- b )` | discard the second |
| `tuck` | `( a b -- b a b )` | copy the top under the second |
| `pick` | `( xn … x0 n -- xn … x0 xn )` | copy the n-th item to the top |
| `roll` | `( xn xn-1 … x0 n -- xn-1 … x0 xn )` | move the n-th item to the top, the top being 0: `1 roll` is `swap`, `2 roll` is `rot` |
| `depth` | `( -- n )` | the number of items (what `mu<n>` shows) |
| `spread` or `**` | `( x -- a b … )` | x's elements on the stack: a seq's notes (no bar lines or jump marks), a parallel's parts, data's values (`[c d e] 2 [/] spread`) |
| `.s` | `( -- )` | show the stack, one item a line, top first, numbered as `pick` counts (`0> …`) |
| `.` | `( a -- )` | print the top and discard it |

**Numbers** are the grammar's three primitives: Int, Float and Ratio
(`7`, `0.5`, `1/4`), with Clojure's numeric tower: exact by default
(`1 4 /` gives `1/4`), ratios stay ratios, a float makes the result
float. `int` truncates toward zero (`7/2 int` → `3`), `round` rounds,
`float` converts to a float, `rational` to an exact ratio (Clojure's
`rationalize`: `0.1 rational` → `1/10`).

**`drop`** is the stack word that discards the top, as in Forth and Factor; the first
element of a form is `head`, all the rest `tail`, the first n `take` and the
last n `take-last` (`split-at` cuts at any n).
`pick` stays the stack word; the weighted draw is `select`.

### Tools

By algo space's tool actions (`doc/md/algo-space.md`, section 0):

| Action | Words |
|---|---|
| **make** | `range` (or `..`: `35 82 ..`, 35 up to 81) `iterate` `scale` |
| **form** | `cycle` `[*]` `[/]` `[+]` `[%]` `[?]` `[~]` `[n]` `{n}` `{+}` `[:]` `[!]` `[||]` `[|]` `[\]` `[H]` `[T]` `take` `tail` `take-last` `drop-last` `rotate` `shuffle` `concat` `flatten` `interleave` `partition` `split-at` `sputter`; `slice` `splice` `within`; `put` `insert` `delete` `cut` |
| **value** | `+` `-` `*` `/` `cresc` `dim` `map` `transpose` `inversion` |
| **selection** | `filter` `remove` `take-while` `drop-while` `distinct` `dedupe` `choose-n` `choose-from` `select`; `mute` |
| **assemble** | `par` `chord` `zip` `>ctx` `sounds` `with` `delay` `delays`; `times` `volta` `vary` |
| **access** | `head` `last` `nth` (`@`) `length`; by time: `duration` `at` `bar` `bars`; numbers of a stream: `sum` `mean` `max` `min` `sort` `intervals`; searching: `find` `index-of` `indexes`; pitch classes: `classes` `prime-form` `interval-vector` `profile` `key-of` `chord-name`; rhythm: `pulses`; one note: `pitch` `duration` `volume` `articulation` `instrument` `drum`; every note of a line: `pitches` `durations` (a rest's too) `volumes` `articulations` `instruments` `drums` (a rest's nil), back onto a seq with `zip`; `as-drums` (notes made drums); what a note plays with, `:volume setting` `:instrument setting` … (`setting`,); `with` writes one |

- **Form tools act on positions**, never values, so they work on any
  material; value tools read values (numbers, or what a pitch or
  duration means) and refuse the wrong material.
- **A tool keeps the form**: on a `:SEQ` it works on the children and
  gives a `:SEQ` back, keeping its context.
- **Endless input is checked**: an *everything* appetite after an
  *endless* yield (`cycle retrograde`) is refused before running.
- **Selection takes a quotation**, the test, run on each element:
  `[c e g c'] '( pitch 65 < ) filter` keeps the low notes, `remove`
  drops them (the later notes move earlier), `take-while`/`drop-while`
  stop at the first refused. `mute` silences the notes its test keeps -- each becomes a rest
  of its length, so the rhythm stays. `distinct` and `dedupe` compare
  elements as mu prints them; `choose-n`, `choose-from` and `select`
  (`[c d e] '[3 1 1] select`, one drawn by the weights) draw at random.
- **A chunk out and back**: `slice` takes the elements from a start up
  to (not including) an end, counted from 0 as `nth` counts; `splice` puts something in their place (start =
  end inserts); `within` does both around a quotation --
  `[c d e f g] 1 4 '( [R] ) within` gives `[c f e d g]`, the chunk
  changed where it came from, its length free to change.
- **One element at a time**: `put` (`!`) replaces the element at n,
  `insert` puts one before n (n = the count appends), `delete` (`_`)
  drops it, and `cut` (`[_]`) drops a range like `slice`'s:
  `[c d e] 1 g !` gives `[c g e]`, `[c d e] 1 g insert` `[c g d e]`,
  `[c d e] 1 _` `[c e]`, `[c d e f g] 1 3 [_]` `[c f g]`; `@` is `nth`.
  With a **reference** (`:bass/onsets`, a live voice's input) on top, `@`
  alone reads it and `!` stores the value under it: `7 :bass/onsets !`.
  `5 variable five` is Forth's variable, starting with a value (the name is
  read as `see` reads it): `five` pushes the place, `five @` reads it and
  `3 five !` stores. A second name for the place is a plain definition,
  `: six five ;`, and `: speed :bass/onsets ;` does the same for a voice's
  input: one place under two names, so a store through either is read by
  both, with no second value to keep equal. `5 value five` is Forth's value:
  `five` pushes 5, and `7 to five` changes it. `save-session` writes both
  as `7 value five` and `3 variable v`, with what they hold (one whose value
  can't be written as text is skipped and named).
  The value stays one element (`[c d] 1 [e f] !` is `[c [e f]]`);
  `splice` spreads. An index may be a path, a data list that goes
  down through nested seqs: `[[c d e] [f g a]] '[1 1] x !` replaces
  the second element of the second seq, and `'[0 2] @` reads the
  third of the first. An index past the end is an error.
- **Settings stay with their notes**: an instruction in the middle of
  a seq (`[c d !mf e f]`) stays in front of the element it was written
  before, whichever word moves, drops or replaces the elements around
  it. When that element is gone it waits for the next one that stays
  (none follows: dropped); when elements are reordered (`rotate`,
  `shuffle`) it travels with its note; a word that gives as many new
  elements as it was given (`map`, `mute`) gives each place its
  settings. Those before the first element are the seq's own and stay
  at its start. `[c d !mf e f] 1 3 slice` is `[D4/4 !mf E4]` and
  `[!mf c d e] tail` is `[!mf D4/4 E4]`. A seq put in by `splice` or `within` brings its own settings: `[c d e f] 1 3 [!pp A4 B4] splice` is `[C4/4 !pp A4 B4 F4]`, those before the first element replaced staying in front of it, those between the replaced ones waiting for the next element that stays.
- **Bar lines and jump marks are in a seq but are not its elements.** They
  are real and of no duration (the engine signals a `|` to the conductor),
  but a word counts and fetches notes, seqs and parallels only: `[c d | e f]
  length` is `4`, `2 @` is `E4`. A word that selects, inserts, splits or
  reorders (`take` `slice` `partition` `[/]` `insert` `filter` `rotate`
  `shuffle` `retrograde` …) gives its result without them, since they would
  sit where they mean nothing; a word that only changes each note (`+` `*`
  `with`) or repeats the whole seq (`times`) keeps the seq as written.
  `retrograde` refuses a seq with jumps rather than drop them.
- **`[]` is the empty seq**, mu's own (musics text needs an element
  in its `[ ]`, so `[c [] d]` is not read). It is where a seq is built
  up from (`[] x concat`), what `delete` leaves of a one-note seq, and
  `x 1 3 [] splice` is `x 1 3 [_]`. `[[]]` stays the measures word. An
  endless loop of nothing (`[] [~]`) is refused.
- **`concat`** (`[+]`) joins two seqs into one, as the text of both
  written together: the second seq's settings are written where it
  begins, so `[!mf c d] [!pp e f] concat` is `[!mf C4/4 D4 !pp E4 F4]`
  and nothing is lost. Settings carry on, as they do in text: a seq
  with none of its own takes on what came before it. `flatten` opens
  the seqs inside a seq, one level (data opens the data inside it,
  `'[1 '[2 3]] flatten` is `'[1 2 3]`): `a b [2] flatten` is `a b
  concat`. `[2]` nests instead, each seq keeping its own
  settings. Anything that is not two seqs (data, streams) joins by its
  elements.
- **`map`** runs a quotation on each element: notes give the form back
  (`[c e g] '( 12 + ) map`), anything else a stream (`[c e g] '( pitch
  ) map` is `'[60 64 67]`).
- **A seq taken apart**: each reader has a plural (`pitches`,
  `durations`, `volumes`, `articulations`, `instruments`) that gives
  every note's as a stream -- one half product of the seq; a chord's
  pitches stay a list, a rest's nil. A repeat or loop around the seq
  stays around the values (`[c d e] [~] pitches` is `'[60 62 64] [~]`,
  `[c d] 3 times pitches` `'[60 62] 3 times`); one inside it is written
  out, and a loop inside refused.
  `zip` puts durations and pitches back together, so `x durations x
  pitches zip` is `x` again, and changing one stream between the two
  changes only that half product of the seq.
- **Numbers of a stream**: `sum`, `mean` (exact: `'[60 62 65] mean` is
  `187/3`), `max`, `min`, `sort` (smallest first) and `intervals` (each
  number minus the one before) read a data list or a stream of numbers,
  such as a seq gives with `pitches`, `durations` or `volumes`:
  `[c e g] pitches intervals` is `'[4 3]`, `x durations sum` the length
  of a seq. `max` and `min` stay the two-number words when two numbers
  are on top (`3 5 max`); a stream on top is read as a stream
  (`'[3 5 1] max`). A seq itself is refused with a pointer to `pitches`
  and `durations`, an endless stream with `take`, an empty one by all but
  `sum` (0).
- **Pitch-class sets**: three kinds of value, each written with a `$`
  and its own bracket, the numbers glued together (`T` is 10, `E` 11).
  `$[047]` is a set of classes, the places of pitches above C in the
  octave (C 0 … B 11), each once and the smallest first; `$(037)` is a
  prime form; `$<001110>` is an interval vector, one count for each
  interval class 1-6 (a number above 11 goes apart, `$<12 12 12 12 12 6>`).
  They print as they are written, so a session keeps them, and they are
  mu's alone: a seq holds no set. Reading them is checked: classes are
  sorted, once each and taken mod 12 (`$[740]` is `$[047]`), a prime form
  must be one (`$(047)` is refused and the real `$(037)` shown), a vector
  has six counts.
  - `classes` makes the first from a seq, a chord, a data list of pitch
    numbers or a stream: `[c e g c'] classes` is `$[047]`, `[g c e d c']
    classes` `$[0247]`. A chord gives all its pitches, rests and drums
    have none, a loop is refused. It is absolute, not relative to a key:
    `degrees` is the key's own scale steps.
  - `prime-form` is the most packed of the classes' rotations and of
    their inversion's, from 0 (Forte): `[c e g] prime-form` is `$(037)`,
    and so is the minor triad's, `[c e& g]`; the diminished seventh is
    `$(0369)`. It tells whether two phrases are one set class: `x
    prime-form y prime-form =`.
  - `interval-vector` counts the interval classes between them:
    `[c e g] interval-vector` is `$<001110>`, one major third (ic 4), one
    fifth (ic 5), one minor third (ic 3).
  - What reads a list reads their numbers (`length`, `sum`, `max`, `@`,
    `find`, `=`, which ignores the kind: `$[037] '[0 3 7] =` is true).
    What changes the numbers (`sort`, `take`, `intervals`) gives a plain
    list `'[4 3]`; `>list` is that conversion by itself: `$[047] >list`.
- **A rhythm as pulses**: `pulses` writes a seq's rhythm as a grid, a 1
  where a note starts and a 0 while it holds or a rest is silent:
  `[c4 d8 e8 f2] pulses` is `'[1 0 1 1 1 0 0 0]`. The pulse is the largest
  note value every length is a whole number of (an eighth there, a twelfth
  for a triplet); a pulse on top is the grid instead, each length rounded
  to a whole number of them and at least one: `[c4 d8] 1/16 pulses` is
  `'[1 0 0 0 1 0]`. A chord is one onset, a tie's second note a hold, a
  drum a note. It is what the algos' `pulses->durations` reads back, as a
  list of numbers the number words read (`pulses sum` is how many notes,
  `pulses length` how many pulses). A parallel, data and a loop are refused.
- **Naming a chord**: `chord-name` gives the name of a chord as a chord
  symbol is written, as a string: the root, a colon and a quality word
  (`M` the major triad, `m`, `d`, `A`, `5`, `s2`, `s4`, `7`, `M7`, `m7`,
  `d7`, `m7.5-` the half-diminished, `6`, `m6`, the ninths, elevenths and
  thirteenths -- the words the reader's table has), and a slash and the
  lowest note when that is not the root. `<c e g>1 chord-name` is
  `"C:M"`, `<c e& g b&>1` `"C:m7"`, `<e g c'>1` `"C:M/E"`, the first
  inversion. It takes a chord, a seq (an arpeggio: the chord its notes
  make together), a set of classes or a list of pitch numbers. The root is
  the lowest note when a quality fits there (`<c e g a>` is `C:6`, not
  `A:m7/C`); with no lowest note (a set) the simplest quality wins, then
  the lowest root: `$[0479]` is `A:m7`. Roots are spelled with flats for a
  major or dominant chord (`E&:M`, `B&:7`) and sharps for a minor or
  diminished one (`F#:m`). What reads back is the name: `[C:M7]` is the
  chord. No fit (a cluster) is refused, and so is a single class.
- **Guessing the key**: `key-of` gives the major or minor key a seq, a
  chord, a set of classes or a list of pitch numbers most likely is in,
  Krumhansl and Schmuckler's way: the class weights (the time each sounds
  for music, once each for a set, how often for numbers) are correlated
  with Krumhansl and Kessler's major and minor profile from each of the
  twelve tonics, and the best of the 24 wins. `[c d e f g a b c'] key-of`
  is `C.major`, `[a4 c e a2 e4 c a] key-of` `A.minor`, `[f1 g4 a b& c']
  key-of` `F.major` (the long note counts). The result is a key, so the
  words of keys read it: `key-of tonic`. Flat keys are spelled with `&`
  (`E&.major`), sharp minor keys with `#`. It is a guess: a short phrase
  can fit two keys almost as well (a major key and its relative minor),
  and a seq whose classes are all alike has none, which it says. It is
  not `key`, the key a seq was written in.
- **A pitch-class profile**: `profile` scores how much each of the twelve
  classes counts in a seq, from 0 to 1, C first and B last: the time
  each class sounds, a chord's time shared among its pitches, divided by
  the largest so the strongest scores 1. `[c4 e4 g4 c2] profile` is
  `'[1 0 0 0 1/3 0 0 1/3 0 0 0 0]` (C sounds 3/4, E and G 1/4);
  `[<c e g>2 <f a c'>2] profile` gives C 1 and the other four 1/2. Rests and drums add nothing, the octave does not
  count, nothing sounding is all 0, and a loop is refused. It scores,
  it has no durations: `duration` gives the time, `classes` which
  classes. The scores are exact ratios, which the number words read (`profile
  sum`), and the input of a key estimate.
- **Searching**: `find` is the first element a quotation accepts and
  `index-of` its place, counted from 0 as `nth` counts: `[c e g c'] '( pitch 65 > ) find` is
  `G4/4`, `index-of` `2`. `index-of` also takes a value in place of the
  quotation, the first element equal to it as mu prints it
  (`[C4 E4 G4] E4 index-of` is `1`; a bare `e` after other notes in the
  same input reads relative to them, as everywhere). Nothing found gives
  `nil`. An endless stream is refused, as for every word that reads it
  all: `take` first. `x dup '( … ) index-of nth` reads the element back.
  `indexes` is every place instead of the first, as a list, a quotation or
  a value again: `[C4 E4 C4 E4] C4 indexes` is `'[0 2]`, `'[3 5 1 5] 5
  indexes` `'[1 3]`, none an empty `'[]`; `indexes length` is how many match.
- **Delaying**: `delay` puts a rest of d whole notes in front of a seq,
  d any rational (`[c d] 1/4 delay` is `[r4 C4 D4]`, `1/3 delay` a rest
  as a tuplet), its own settings staying first; a note becomes a seq.
  `delays` is for the parts of a `{ }`: a number delays part k by k times
  it (`{[c d] [c d] [c d]} 1/4 delays`, a canon at the quarter), a data
  list one delay for each part (`'[0 1/3] delays`; a whole number among ratios is a ratio), 0 leaves a part alone,
  and the list must be as long as the parts are many. The delay is a rest
  in the music, so `duration`, `bar` and `at` see it, and the parallel ends
  with its longest part. `delay` refuses a parallel and `delays` a seq,
  each pointing at the other.
- **By time and by bar**: `duration` is a seq's length in whole notes as
  written (a note's is its own) (jumps not followed; `[!]` first writes them out; a `{ }` is
  its longest part): `[c4 d4 e2] duration` is `1`. `at` is the note, rest
  or drum sounding t whole notes in, a note sounding until the next
  starts: `[c4 d4 e2] 3/8 at` is `D4/4`, `1/2 at` or `0.5 at` `E4/2`; it
  reads through nested seqs, is refused past the end and for a `{ }`
  (take a part with `nth`). `bar` is the n-th measure by the seq's meter,
  counted from 0 as `nth` counts, a seq of its own (`measures` and `nth`
  in one): `[c2 d2 e2 f2] 1 bar` is `[E4/2 F4]`; `bars` the measures from
  a start up to an end as one seq. Every setting written before them
  comes first, as it would in the text: `[!mf c2 d2 !pp e2 f2] 1 bar` is
  `[!mf !pp E4/2 F4]`, `!Meter:3/4` travels with its bar.
- **What a note plays with**: `volume`, `instrument` and
  `articulation` read only what the note itself says (`c4\vol:90`), nil
  otherwise. `setting` reads what it plays with, a note or a seq and a
  key on top (`:volume`, `:instrument`, `:articulation`, `:Key`,
  `:panning` … any context key, by its aliases as `!key:` writes it:
  `:vol`, `:i`): its own value, else the context's where it sits.
  `[!i:40 !mf c d\vol:90] 0 nth :volume setting` is `60` (mf), `1 nth
  :volume setting` `90`. A rest too; a drum's `:instrument` is its key of
  the kit (`x8\38` gives `38`); `:articulation` is the note's name, else
  the context's factor as the staccato or tenuto it equals, else the
  number (`0.9` is the default); a seq gives its context at its start
  (`:Key setting` of a seq is the key it starts in). A ramp is followed along the seq: in `[!vol:30< c4 d4 e4
  f4 !vol:90]` `2 @ :volume setting` is `60` and `3 @ :volume setting`
  `75`, and the volume at a time is `at` first: `1/2 at :volume setting`.
  `:Tempo` and `:Meter` read as written (`100`, `3/4`).
  `with` writes one: `[c d] !volume:70 with` is `[!volume:70 C4/4 D4]`,
  any instruction as the text writes it, the seq's own settings still after
  it so they win; a note becomes a seq. `with` also takes a context
  (`^{ !mf } with`) or a key (`D.major with`). A bare `!name` at the top
  level is a context holding it (see the instruction bullet above); to read a
  key use `:name setting`. Inside a seq a bare `!name` must be a mark: a typo is
  refused with what it is close to (`[!mff c]`: "!mff is no mark -- did you
  mean !ff or !mf?"), and a context key without its value says so
  (`[!volume c]`: write `!volume:value`); `!name:value` still takes any
  key, a custom one too.
- **`zip` builds leaves from their parts**: everything above a `|` mark,
  else the whole stack, each part a stream in the order a leaf holds them --
  durations, pitches (or drums), articulations, dynamics, ornaments,
  tremolos, ties -- then overrides by key (`:volume`, `:instrument`,
  `:pan` …). A stream a reader gave knows its part
  (`pitches`, `volumes`); a `:name` before a stream names it
  (`:volume '[90 60]`, plurals too); the others take the free parts in
  that order. There is one note per duration; the other parts cycle
  under the durations and step once per note, rests included: at a rest
  (an `r` among the durations, or a `nil` pitch) their values go
  unused, so every part's n-th value belongs to the n-th note. A `nil`
  writes nothing, the note keeping its context's value. Durations in a
  loop give a loop as long as all the parts take to line up again:
  three durations against four pitches give `repeat ~ [ … ]` of twelve
  notes.

  ```
  | [c8 d e] durations [c e g] pitches :volume '[90 nil 50] :articulation '[:accent] zip
  ```

  A dynamic is the weight an articulation gives, so only one the text
  can write is taken (`-20` alone, a ghost note).
- **Onto a seq**: an unnamed seq (or note) among zip's inputs is
  written on, keeping its form -- repeats, loops, nested seqs,
  instructions, bar lines. Its notes set the length; each part cycles
  and steps once per note, a rest's value unused, and a part given
  replaces the seq's own: `[c d e f] :volume '[90 60] zip` gives each note
  the next volume, `| [c d e] :duration '[/8] zip` makes them eighths.
  A seq under `:name` gives only that part (`| :duration [c4 r e g]
  '[60 62 64 65] zip`); onto a seq every stream needs its part known,
  and only one seq is written on.
- **A bundle**: without durations and pitches (or drums) `zip` gives
  the parts as columns, each as long as it is: they cycle on their own
  once durations join them, as loose parts do, so zipping a bundle later
  gives what zipping its parts now gives (a color against a talea). A
  loop stays a loop, written as a looped stream is: `'[:pitch '[60 62]
  [~] :volume '[90 80 70]]`. An endless stream that isn't a loop is
  taken to the longest finite part's length, and refused with nothing
  finite beside it. `| :pitch '[60 62 64] :volume '[90 70] zip` is `'[:pitch
  '[60 62 64] :volume '[90 70]]`. A bundle is data, so it reads back as it prints, and `zip` takes it
  again as its parts. It can't sound: the play words, `render`, `par`,
  `{n}` and `[n]` refuse it, saying what it lacks. There is no
  default duration.
- **A context from a control**: what changes over time -- volume, tempo,
  pan, the key -- is a context, not a part of each note. `>ctx` takes,
  above a `|` mark, `:duration` and one or more context keys, each a
  stream, and an optional `:curve` (`step`, the default, `lin` `smooth`
  `in` `out`), and gives a `^{ }` whose points stand at their times:

  ```
  | :duration '[1/1 ./. 2/1] :volume '[50 70 90] >ctx    → ^{ !volume:50 !volume:70@1 !volume:90@2 }
  | :duration '[1/4] [~] 50.0 4.0 walk/walk 8 pull :volume swap >ctx   a walk's volumes, one each quarter
  [c4 d e f] | :duration '[1/2] :volume '[30 90] :curve '[:lin] >ctx with
  ```

  The steps follow each other by the durations; each value holds until
  the next or, with a curve, glides to it. The longest finite column
  sets how many steps there are, the shorter ones cycling; a loop or an
  endless stream is taken to that length, and all of them looping is
  refused. `with` lays the result under a seq. `@t` after an
  instruction is where a point stands, in whole notes from the start
  (`!volume:70@1`, `@3/8`); `./.` in data is idem, the value before once
  more (`'[1/1 ./. 2/1]` is `'[1 1 2]`).
- Namespaces: the form, make, selection, map and access-by-position
  tools and the numeric value tools are in `tool`; the meaning value
  tools (`transpose`, `inversion`), `mute`, the leaf readers, assemble,
  and the symbols on material (`+` `-` `*` `/`, `cresc` `dim`) are in
  `music`; playing in `play`; rating in `rate`.
- **A text command and its word do the same thing**, prefix in the
  text and postfix on the stack: `transpose c d ( c e g )` is
  `[c e g] c d transpose`, `retrograde ( c d e )` is `[c d e] [R]`,
  and `repeat 3 [a b] volta [[c] [d]]` is
  `[a b] 3 times [c] [d] volta`. `times` (`[*]`) gives one
  repeat played n times, counting the plays as the text and LilyPond
  do (it prints, and reads back, as the text). `volta` takes the endings above
  the nearest repeat on the stack -- the repeat is the mark -- and gives
  the repeat with them. Each pass is followed by an ending, LilyPond's
  rule: `[a b c d] 2 times [c2 b a] [a4] volta` plays
  `a b c d c2 b a a b c d a4`. `+`, `-`, `*`, `/` reach into the body
  and the endings.
- **`vary` changes every pass**: the quotations above a repeat, one for
  every pass or one each, applied to the body as written, the first
  pass included -- `'( shuffle ) vary` a new shuffle each pass,
  `'( 1/3 * ) vary` each a third as long, `[c d] 3 times '( ) '( 12 + )
  '( shuffle ) vary` the first as written, the second an octave up,
  the third shuffled -- the endings following, unchanged. It is written
  out as a seq of the passes, not a `repeat volta`; for changes that
  add up pass by pass, use `iterate`.
- **Repeat signs and jumps are text**: `[: a b :]` is `repeat 2
  [a b]`, kept as written (`[: c d :] 2 +` gives `[: D4/4 E4 :]`), and
  `$`, `DS`, `DC`, `Fine`, `Coda` are marks inside a literal, `[c $ d
  DS e]` playing `c d d e`; `./.`, alone in its bar (a bar line before
  it, a bar line or the end after), plays the bar before it again
  (`[c d | ./. | ./. | e]`). A tool that reorders a seq can
  leave the marks unplayable (`[R]` puts DS before `$`), which is
  an error.
- **`-|>` waits for a boundary**, through the conductor: `x -|>` replaces
  what plays when it reaches its end, or the end of its repeat's
  current pass; `x | -|>` … `x |||| -|>` at the next bar line of that
  strength. The new material starts when the boundary is due, so it
  lands in time; with nothing playing, or only what mu didn't start (an audition, a voice
  from Clojure), whose passes it can't see, it plays at once. `-|>+` waits the
  same way but joins what plays, which goes on -- a second part entering
  at the next `||`. One staged play waits at a time for each voice it
  replaces: a later `-|>` or `-|>+` for it replaces it; `->`, `->+` and
  `stop` forget it.
- **Named music is a voice**: music with a name of its own plays as the
  voice of that name -- `[bass: c d e] ->` is the voice `:bass`, and so
  is `[bass: c d] [~] ->`, a loop of it. Playing named music replaces
  only its own voice (or adds it), the others going on: `[bass: g a] ->`
  now, `[bass: g a] -|>` at the end of `:bass`'s own pass (a bar line,
  `x | -|>`, is the next one of any voice). A name is one voice, so `->+`
  on it is `->`. Unnamed music stops everything (`->`) or joins under an
  id the engine gives (`->+`, `:TA` …). `voices` lists what plays, with
  its id (`<<-` for short); `:bass stop` stops one voice, `:bass playing`
  (`:bass <-`) puts what it plays on the stack (`:bass <- 5 + -|>`), `:bass hush` silences it
  and `:bass solo` lets only it sound -- both keeping its place, again
  to undo.
- **Voices by index**: wherever a voice's id goes, its index does too,
  by the mean pitch of what each plays: `1` the lowest voice with pitch,
  `n` the highest; the voices without pitch (drums) from `0` down, `0`
  the one with the lowest drum key, `-1` the next. `1 <-`, `2 hush`,
  `0 stop`, `x 1 -|>` (a voice's id or index on top of a play word is
  the voice to play as: `3 <- 12 + 3 -|>`; alone, with no music under it,
  the voice starts its own music again: `1 ->`, `:bass -|>`); `<<-` lists
  them by index, marking the drums, the lowest and the highest.

### Arithmetic on material, accessors

`+` `-` `*` `/` work on everything they can (`musics.arith`, one
multimethod over what the two values are):

| the two values | the result |
|---|---|
| two numbers | the sum, difference, product, exact quotient: `2 3 +` is `5`, `1 4 /` is `1/4` |
| a list or stream of numbers, and a number | each element, the number on either side in the order written: `'[60 62] 12 +` is `'[72 74]`, `10 '[1 2 3] -` is `'[9 8 7]`; an endless stream stays endless |
| two lists or streams | element by element, as long as the shorter: `'[1 2 3] '[10 20] +` is `'[11 22]` |
| music and a number | `+` `-` every pitch up, down by semitones; `*` `/` every duration times, divided (below) |

Anything else is refused, naming both values.

Comparison is Forth's: `= <> < > <= >=`. `<` `>` `<=` `>=` take numbers,
ratios and durations (`1 2 <`, `/4 /8 >`), or any two values by a **key**, a
quotation on top that reads a number from each: `c d '( pitch ) <`,
`[c d] [e f g] '( duration ) <` (how long), `[c d e] [e f] '( length ) >` (how
many notes). `=` and `<>` take a key too, then compare what it reads
(`c C5 '( pitch 12 mod ) =`, the same pitch class). `sort` takes the same key
and orders any elements by it, the first of equals first: `[e c d] '( pitch )
sort` is `[c d e]`, `[[c d] [e]] '( length ) sort` is `[[e] [c d]]`. `=` and `<>`
take any two values:
two numbers by value (`1 1.0 =`, `1/2 0.5 =` are true), music by its notes,
names left out (`[c d e] [C4 D4 E4] =` and `[a: c] [c] =` are true), two loops
by what they repeat, data element by element (another endless stream is
refused). A flag is `true` or `false` and nothing else: `and` `or` `xor` `not`
`if` and `when` refuse a number or a note in its place, naming it. `:=`
binds a name. Playing is `->` (a dot, as in Forth's output words: `.` prints,
`.s` shows the stack, `..` is a range), so `[c d] >` is refused with "to play,
write ->"; the old `>>` `>+` `>>+` `<<` say what replaced them. The stack words
are Forth's too: `dup drop swap over rot -rot nip tuck pick roll 2dup 2drop`,
and `1+` `1-` (a number, a list, or a semitone for music).

A bare symbol acts on each element (`[c d] 2 +` transposes every
note); the same symbol in brackets acts on the whole seq (`[a b] 3
[*]` plays the seq three times, `[c d e] [R]` reverses it). Every bracketed word
is a symbol, and has a named twin, a name Clojure could carry too:
`[R]` `retrograde`, `[I]` `inversion`, `[RI]` `retrograde-inversion`,
`[~]` `cycle`, `[:]` `repeats`, `[!]` `unfold`, `[||]` `barred`, `[|]` `choosing`, `[\]` `alternating`, `[[]]`
`measures`, `[*]` `times`, `[/]` `parts`, `[+]` `concat`, `!` `put`, `_` `delete`, `[_]` `cut`, `@` `nth`,
`[H]` `head`, `[T]` `tail`, `[%]` `rotating` (as it plays; `rotate` now), `[M]` `mirror`, `[?]` `shuffling` (as it plays; `shuffle` now), and on each element `+` `-` `*` `/`. Playback, which acts on
what plays rather than on a seq, is written with `->`: `->` now, `-|>` at
the next boundary, a `+` joining instead of replacing (`play`, `play+`,
`play-next`, `play-next+`); `stop` and `pause` stop and pause it.

| On material | Means | Example |
|---|---|---|
| `+`, `-` | transposition up, down, by whole semitones, the key with it (`!K:` at the start of the seq: its own moved, else the one it is in; not for whole octaves) | `c4 2 +` gives `D4/4`; `[c d] 2 +` `[!K:D.major D4/4 E4]`; `[c d e] 12 -` an octave down |
| `*`, `/` | duration times, divided by a positive factor, as in the grammar (`c4*1/3`, `R*4`) | `[c d e] 3 /` (triplets), `c4 2 *` |
| `cresc`, `dim` | crescendo, diminuendo across the material, to a volume 0-100 | `[c d e f] 90 cresc` gives `[!vol:50.0< C4/4 D4/4 E4/4 F4/4 !vol:90]` |
| `[/]` | the whole split into n parts, as equal as can be, the longer first | `[a b c d] 2 [/]` gives `[[A3/4 B3] [C4 D4]]` |
| `[+]` | two seqs joined into one (`concat`), each seq's settings written where it begins | `[a b] [c d] [+]` |
| `[_]` | a range of the elements cut out (`cut`): start and end, as `slice` takes them | `[c d e f g] 1 3 [_]` gives `[C4/4 F4 G4]` |
| `[H]`, `[T]` | the head, the first element, and the tail, all after it (`head`, `tail`); the first n is `take` | `[c d e f] [H]` gives `C4/4`, `[c d e f] [T]` `[D4/4 E4 F4]`, `[c d e f] 2 take` `[C4/4 D4]` |
| `[%]` | the text's `rotate n [ … ]`, one step further each time it plays (`rotating`); data and streams are rotated now. `rotate` rotates music now, wrapping around | `[a b c d] 1 rotate` gives `[B3/4 C4 D4 A3]`, `[c d e] 1 [%] [~]` a new rotation every pass |
| `[?]` | the whole in a new random order each time it plays, the text's `shuffle [ … ]` (data shuffled now, as `shuffle`) | `[a b c d] [?] [~]` gives `repeat ~ [shuffle [A3/4 B3 C4 D4]]`: every pass shuffled |
| `[~]` | the whole endlessly, `repeat ~ [ … ]`: a loop, played until `stop` | `[c d] [~] ->`, `[c d] [~] \|\| -\|>+` |
| `[:]` | the whole in repeat signs, played twice | `[c d] [:]` gives `[: C4/4 D4 :]` |
| `[!]` | the whole written out as it plays: repeats as their passes, jumps and `./.` followed | `[c4 $ d e DS f] [!]` gives `[C4/4 D4 E4 D4 E4 F4]` |
| `[[]]` | the whole split by its meter into its measures, each a seq of its own, cut and tied as `[\|\|]` does; a setting carries on as it did, a ramp ends at its measure | `[c2 d2 e2] [[]]` gives `[[C4/2 D4] [E4]]` |
| `[\|\|]` | the whole barred by its meter (its own `!Meter:`, else the defaults'): a `\|` every bar, a note or drum across one cut and tied | `[c2. d2] [\|\|]` gives `[C4/2. D4/4~ \| D4]` |
| `[\|]` | one of the alternatives each time it plays, the text's `choose [ … ]` (`choosing`): the quotations on top applied to the whole, or without them its own elements; data of weights on top, one each, weighs them (`choose 3,1 [ … ]`) | `[c d e g] '( [I] ) '( [R] ) [\|] [~]`: every pass the inversion or the retrograde |
| `[\]` | the alternatives in turn, one each time it plays, the text's `alternate [ … ]` (`alternating`), made as `[\|]` makes them | `[c d e g] '([I]) '([R]) [\] [~]`: I, R, I, R … |
| `[2]` `[3]` … | the top n one after another, `[ a b … ]` | `[c d] [e f] [2]` |
| `{2}` `{3}` … | the top n at once, `{ a b … }` | `[c d] [e f] {2}` |
| `{+}` | the parts of two parallels in one `{ }` (a seq is one part) | `{[c] [d]} {[e] [f]} {+}` |
| `[*]` | the whole played n times (`times`), the text's `repeat n [ ]`; data and streams written out n times, printed as their repeat | `[a b] 3 [*]` gives `repeat 3 [A3/4 B3]`, `'[1 2] 2 [*]` `'[1 2] 2 times` (its elements `1 2 1 2`) |
| `->`, `->+`, `-\|>`, `-\|>+` | play now, stopping what plays; joining it now; in its place from the next boundary; joining it from the next boundary | `[c d e] ->`, `[e f] ->+`, `[g a] \|\| -\|>`, `[e f] \| -\|>+` |
| `stop` | stop (and forget a waiting `-\|>`); Alt+. at the prompt, mid-typing | `stop` |
| `pause` | pause what plays; again, resume it; Alt+, at the prompt | `pause` |
| `sounds` | a copy of a note, chord or drum per number (0-127): a drum's key, a note's instrument | `x8 35 82 range sounds` (the drum set), `c4 0 128 range sounds` (every instrument), `x4 '[36 38 42 46] sounds [~]` |
| `D.major` | a key: a tonic (`#` sharp, `&` flat) and a scale, as `!key:` writes it; without the scale, `D` is the note | `B&.minor`, `f#.dorian` |
| `with` | a seq played in a key (as `^{ !key:… } with`) | `[c d e f g] G.major with` plays `f#` |
| `scale` | a key's scale, one octave up from its tonic in octave 4 | `D.major scale` gives `[D4/4 E4 F#4 G4 A4 B4 C#5]` |
| `degrees` | numbers as scale steps of a key: 0 the tonic, 7 an octave up, -1 the step below | `'[0 2 4 7] D.major degrees` gives `[D4/4 F#4 A4 D5]`, `'[0 -1 -3] D.major degrees` `[D4/4 C#4 A3]` |
| `tonic` | a key's tonic, a note in octave 4 | `D.major tonic` gives `D4/4` |
| `key` | the key a note was written in, or a seq's own (else the defaults') | `[!key:A.minor a b] key` gives `A.minor` |
| `+`, `-` on a key | the key moved by semitones, its scale kept | `D.major 2 +` gives `E.major` |
| `[R]` | the retrograde, the crab (reverse order) at any depth -- nested seqs, parallels' parts, repeats' bodies; ties turn round, what the seq's instructions change (`!f`, a ramp) goes into the notes, so it sounds backwards; jumps and repeats with endings are refused; exactly `[R]`, as `[ R ]` is a seq with a bar's rest | `[c4 d e g] [R]` gives `[G4/4 E4 D4 C4]` |
| `[M]` | the inversion around the mean pitch, keeping the register (`mirror`) | `[c4 d e g] [M]` gives `[F#4/4 E4 D4 B3]` |
| `[I]` | the inversion around the first note, which stays; I5 is `[I] 5 +` | `[c4 d e g] [I]` gives `[C4/4 A#3 G#3 F3]` |
| `[RI]` | the retrograde inversion: inverted around the first note, then backwards | `[c4 d e g] [RI]` gives `[F3/4 G#3 A#3 C4]` |

- `transpose` takes a musical interval: `[ … ] c d transpose`, by the
  interval from C to D, as the text command does.
- A hairpin starts from the volume the material has (its own
  `!mf` …, else the defaults') and ends on the target: `cresc` and `dim`
  write the grammar's `!vol:60<` … `!vol:90` into the seq; the
  direction is the word, its length the material's.
- A length prints as a note value where it is one, dotted if need be
  (`c4 3/2 *` gives `C4/4.`). Otherwise it is a tuplet: in a seq,
  consecutive notes sharing a factor print as one group, written values
  with the count before them, `[c8 d e] 2/3 *` as
  `[3:2(C4/8 D4 E4)]`, as the text writes a tuplet; a lone note
  as its value with the factor, `C4/8*2/3`. The factor is the one the
  note carries (`3:2( )`, `c8*2/3`, `*`, `/`), else the standard one
  for its length (2/3, 4/5, 4/7, 8/9), so it prints back as written.
  A note value is a power of two: `c3` is refused, with a hint.
- A rest moves in time but not in pitch: `r4 2 +` is `r4`, `r4 2 *` is
  `r2`.
- `chord` puts the notes above the nearest `|` mark into one chord, else
  the whole stack: `a c e chord` is `<A3 C4 E4>4`, `[c d] | a c e chord`
  leaves `[c d]` and the chord. Anything but notes is refused. The literal
  `<a c e>` is the grammar's chord.
- `sounds` builds new leaves from one, as `zip` builds them from
  durations and pitches; it is for hearing what a soundfont holds
  (`doc/md/setup.md`, Gervill).
- **Readers only**, no writers: `pitch` (a note's, a chord's as a
  list, nil for a rest), `duration`, `volume`, `articulation`,
  `instrument`, and `note?` `chord?` `rest?` `drum?`, each on one note
  (on a seq, an error naming the plural). A reader is a step out of music, back down
  from atom to primitive: its result is a number or a keyword, not a
  pitch. `volume`, `articulation` and `instrument` read the leaf's own
  written value (or `nil`), not the sounding one; a drum's `instrument`
  is its key. `key` reads the key a note was written in (see the keys
  above).
- **Access**: `head`, `last`, `nth` (from 0) and `length` (how many elements; `duration` is how long) take a seq,
  data or a stream (`head` and `nth` an endless one too).
- **Repeating**: `times` (`[*]`) plays n times, one repeat
  (`vary` varying its passes); `[!]` writes the
  passes out as seqs, each of which a quotation can change on its own
  (`[a c d e] 3 [*] [!] '( shuffle ) map`: three different shuffles);
  `[~]`/`cycle` loops, and a loop is taken from like a stream (`[c d]
  cycle 5 take`); `iterate` applies a quotation
  again and again (`c4 '( 2 + ) iterate 4 take`, or on numbers `1 '( 2 + ) iterate 4 take`), `each` and `map` work
  per element.

### Feedback

Rating what you hear (`doc/md/feedback.md`, `musics.mu.feedback`):

| Word | Key | Does |
|---|---|---|
| `like` `meh` `dislike` | Alt+l Alt+k Alt+j | rate the audition's item, else the sound last played (a note, chord or drum), 1 / 0 / -1, into `~/.mu-feedback.edn` |
| `again` | Alt+K | play it again |
| `next` `prev` | Alt+L Alt+J | the audition's next or previous item |
| `audition` | | ( seq -- ) play its sounds one by one in the background; `stop` stops |
| `gap` | | ( ms -- ) the silence between an audition's sounds, 150 by default |
| `step` | | auditions wait for a rating or `next`, or move on by themselves (the default) |
| `combos` | | ( leaf numbers k -- seq ) every k of the numbers layered on the leaf: `c4 '[40 41 71 73] 2 combos` |
| `ask` | | ask for a rating when a played sound ends, or stop asking |
| `taste` | | the ratings per sound and per instrument or drum, best first |
| `liked` `disliked` | | ( -- seq ) the sounds rated above or below zero |

A key works while you type: it leaves the seq alone and answers
above the prompt. The keys are three neighbours, unshifted to rate
(`,` `/` `.`) and shifted to move (`<` `?` `>`). A rating in an audition moves on to the next item.

### Algos

The algorithms of `musics/algo/` are plain Clojure functions, called by name with
their arguments on the stack (the tutorial and `doc/md/raw-algo-cookbook.md` work
through them). `"rhythmic.pattern" reqalgo` makes the public functions of
`musics.algo.rhythmic.pattern` words, `pattern/euclid`; a quoted name works too,
dots and all, and an alias follows it (`'rhythmic.pattern 'p reqalgo`).
`see pattern/euclid` shows the stack effect, read from the function's own
argument names: `( onsets pulses -- euclid )`.

```
3 8 pattern/euclid                                    → '[1 0 0 1 0 0 1 0]
3 8 pattern/euclid 1/16 bridge/pulses->durations      → '[3/16 3/16 1/8]
3 8 pattern/euclid 1/16 bridge/pulses->durations '[60 64 67] zip
                                                      → [C4/8. E4 G4/8]
'( 0.0 1.0 random/normal ) 12 pull                    twelve draws
60.0 3.0 walk/walk 8 pull                             a walk of eight steps
```

- **Arguments, all of them**: an algorithm takes every argument it has, in the
  order `see` shows (`:algo :arity` says how many when a function has several
  arities); there are no defaults. A keyword tail is `:key value` pairs written
  before the word. A function that cannot work with its arguments says what it
  needs (`99 8 pattern/euclid`: `refused, it needs <= onsets pulses`).
- **Raw material and bridges**: a source gives a plain list (pulses, numbers,
  weights, onsets, points); the bridges of `musics.algo.bridge`
  (`pulses->durations`, `numbers->pitches`, `degrees->pitches`, `weights->pulses`,
  `points->pitches` …) turn it into durations, pitches, volumes, each with the
  grid or range it is given, and `zip` puts the parts into notes. Nothing is
  checked between words: a bridge takes what is on the stack.
- **Short names**: the leaf readers and the bridges have three-letter names, a
  kind of material each: `pul` pulses, `pit` pitches, `pnt` points, `dur`
  durations, `deg` degrees, `vol` volumes, `art` articulations, `wei` weights,
  `num` numbers, `ons` onsets, `str` steps, `cnt` counts, `lea` leaves, `ins`
  instruments. A bridge is `from>to`, the readers' plurals add an `s`:
  `3 8 pattern/euclid 1/16 bridge/pul>dur`, `x pits`, `x durs`. `pul>evnt` is a
  bridge too: one duration per pulse, a note for every onset and a rest for every
  0, all of the pulse you give it (`pul>dur` lets a note last until the next
  onset).
- **`pull`** calls a function n times and gives the list: a quotation that leaves
  one value (a sampler), a function that keeps its place (`walk/walk`,
  `logistic/logistic`), or a generator with a `:value` (`lorenz/lorenz`). A
  quotation is also a function of what it is given, so it can be a fitness
  function (`'( sum 5 - abs negate ) process/genetic`); `( '( … ) '( … ) )`
  gathers quotations into a list (the constraints of `melody/constrained`).
- **A shared seed**: `7 rng/seed` makes the random words after it repeat
  (`"numeric.rng" reqalgo` first).
- **Live**: a quotation under a play word, or a word written with `$` inputs,
  plays live (see Live in the table above); an input is read and stored as
  `:bass/onsets @`, `7 :bass/onsets !`, checked against the range of the algo
  parameter of the same name, and `:bass settings` and `:bass gui` show them.
- **Instruments and drums** are two worlds: an instrument is a program,
  its notes pitches; a drum is a key of the kit (`!kit:brush`). Zipped
  onto a seq, `:instrument` (programs and names, `'[40 :sea 73]`)
  refuses a drum and `:drum` (`'[:kick :hhc 38]`) a note; `as-drums`
  turns notes into drums, keeping their length, volume and accent.
  `:articulation` takes drums too: an accent is a drum's weight.

### First set

To write and play music by hand: the whole kernel; make, form,
selection and assemble; from the value tools `map` and `transpose`; the leaf readers, on a leaf or a whole seq; and the algos.

