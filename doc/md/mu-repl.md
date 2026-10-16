# mu: a musical REPL

A stack-based REPL, Forth- and Factor-like, in which musical data is
first-class: `[ ]` is a sequence (`:SEQ`) and `{ }` a parallel (`:PAR`),
not Clojure data. Every input is parsed by instaparse, through a grammar
that includes `musics.ebnf`, so musics text means in the REPL what it
means in a `.mus` file.

Decided step by step (2026-10-08) and built on branch `mu`:
`(mu!)` runs it. Section 9 lists what is still open.

## 1. Approach

- **A new, small kernel** on branch `mu`, built around the grammar. Not
  a revival of musics.lang: that one had its own tokenizer and many
  features mu doesn't need (vocabulary closing, early binding, stack
  effects checked, parsing words).
- **musics.lang as a reference** to borrow from, at tag `frepl-standby`
  (`41e449b`), checked out beside the repo without merging:
  `git worktree add ../musics-frepl frepl-standby`. Worth borrowing:
  `musics/lang/runtime.clj` (stack, quotations, executing entries), the
  kernel words as a model, and the REPL loop (JLine, prompt, history).
- **One grammar for `.mus` and mu.** The REPL grammar includes
  `musics.ebnf`'s rules and adds only what a stack language needs.

## 2. What a line contains

| Written | Is | Pushed or run |
|---|---|---|
| `[c4 d e]` `{[c d] [e f]}` `'[ … ]` | a musical literal: sequence, parallel, data | pushed as a value |
| `^{ !ff !tempo:120 }` | a context literal | pushed as a value |
| `c4` `C5/8` `<C4 E4 G4>4` `r4` `x8\36` | a loose leaf | pushed as one value |
| `7` `1/4` `0.5` `"text"` | a primitive | pushed |
| `:motif` | a repo name (an id) | pushed as a name |
| `'( dup 12 transpose )` | a quotation: code held, not run | pushed |
| `'motif` | a name, held (for `:=`) | pushed |
| `\motif` | the value bound to that name | pushed |
| `dup` `play` `transpose` `s` | a word | run |
| `tool/cycle` | a qualified word | run |
| `$a` | a local, inside a word's body | its value pushed |

**Leaves win.** A bare `a`, `c4` or `R` is always a note or rest. Any
other name is a word. A note can't be redefined: `: a … ;` is
refused, since bare `a` would always be the note.

**Top level against literals.** At the top level a bare name is a word
(`transpose`, postfix, from the stack). Inside `[ ]`, `{ }` and `^{ }`
the text is `.mus` text, unchanged: `transpose c d ( … )` is the text
command, `\name` a variable (the value bound to it, see Memory), `:name` a reference, `c4\f` a glued
dynamic, `!mf` an instruction. Text pasted from a `.mus` file means
what it means in the file.

**`:` by position.** `: ` with a space starts a definition, `:name`
is a name, `name:` an id. `!` is only ever an instruction (`!mf`).

## 3. Loose leaves and inheritance

- A bare leaf is pushed as one value: `c4 d e` pushes three leaves.
- **Within one read**, duration carries over (`c4 d e`: three quarters),
  and pitch follows the grammar: a capital with an octave is absolute
  (`C5`), lowercase is relative to the previous note in the read. The
  octave is not inherited.
- **Each read starts fresh**: the default duration, and the default
  reference pitch for the first relative note.
- One read is the whole input until it is complete (section 7), so a
  melody across several lines inside one `[ … ]` carries over.
- `|` at the top level is a **mark**: `seq` gathers what lies above the
  nearest mark into a `:SEQ`, `par` into a `:PAR`, and drops the mark:
  `| c4 d e seq` is the same line as `[c4 d e]`. Inside brackets `|`
  is a bar line, as in the text.
- **Words don't run inside a literal**: a literal is musics text, read
  before anything runs. A known word inside one is an error that names
  it and shows the way: `| [a b] [?] [c d] [?] seq`.

## 4. Instructions and contexts

- Inside literals, instructions are as in `.mus`: `[!mf c4 d e !vol< f]`.
- **A loose instruction at the top level is an error**, with a hint:
  write `[!mf …]`, or give a context, `^{ !mf }`. mu never writes
  `:ROOT`.
- `^{ … }` is a value like any literal: store it (`:loud R!`), use it
  inside a literal (`[:loud c4 d e]`), apply it with `with` (a new
  value, to keep or store: `[c4 d e] ^{ !pp } with` gives `[!pp C4/4 D4
  E4]`, the settings in front of the line's own, so its own still win;
  a `{ }` or a repeat is wrapped instead), or give it to
  `play` for one performance: `[c4 d e g] ^{ !tempo:90 } play`.
- **A context given to play comes first** (`musics.compose/over?`): it
  is the first context in the chain, in front of the material's own,
  then the material's own contexts, then the defaults. So
  `[!tempo:120 c4 d e] ^{ !tempo:90 } play` plays at 90 -- the context
  just before the play word overrides what the music says -- and
  `[c4 d e] ^{ !tempo:90 } play` at 90 too. `with` is the other way
  round: it fills in what the line leaves open (its settings go in front
  of the line's own, which come after and win).
- **The defaults are `:ROOT`**, built at session start from the
  factory defaults (`musics.common.music-data/quantities` through the
  context-key registry) and adjustable during the session in the GUI's
  root window, which changes them in place (playing voices hear it).
  mu doesn't set them; `defaults` `( -- ctx )` pushes them, as they are
  now, as a `^{ }` value: `defaults .`, or
  `[c4 d e] defaults play`.

## 5. Words

**Defining**, Forth style:

```
: octaved ( x -- p )  | $x $x 12 + par ;
: canon ( a s -- p )  | $a  $a $s +  par ;
: echo  ( m -- l )    [$m $m r4 $m] ;
```

- `: name … ;` defines a word. The colon needs a space after it. The
  name can't be a leaf (`a`, `c4`, `x`, `R` …).
- **Locals are optional**: with a stack effect `( a b -- c )`, its
  input names are bound as locals; without one, the word is plain stack
  code. Any name may be a local, leaf letters included.
- A local is written **`$a`**, in the body and inside literals
  (`[$m $m r4 $m]` splices it). Locals are **constants**: bound once
  per call.
- **Quotations** are `'( … )`, the quoted counterpart of `'[ … ]` (held
  data): held code, run by `call`, `if`, `each`, `map` …

**Namespaces**:
- a word is qualified as `ns/word` (`tool/cycle`, `duration/euclid`);
- `"grid" use` makes a namespace's words usable unqualified, and `"song" in`
  sets where new definitions go;
- **an ambiguous name is an error** until qualified;
- visible by default: `kernel` (the stack, quotations, numbers,
  binding, the repo, help, files), `tool`, `music`, `play` (playback and
  the synthesizers), `rate` (ratings and auditions), `mine`; new words
  go into `mine`;
- the algos come in by what they deliver, the namespaces the trees use
  (`doc/md/algo-space.md`, "Algos deliver half products"): the half
  products `pitch/`, `duration/`, `volume/`, `articulation/` (and
  `instrument/`, once it has sources), and the products `leaf/` and
  `parallel/`. Raw material (numbers, pulses, weights) and the bridges
  stay in the trees' workshop, not in mu.

## 6. Memory

| Memory | Lives | Holds | Store | Fetch |
|---|---|---|---|---|
| local | one call of a word | what the stack effect bound | — (constant) | `$a` |
| binding | the session | any value | `'shift 7 :=` | `\shift` |
| repo | shared, saved, playable | material | `R!` | `:id R@` |

`R!` and `R@` are words, never a multi-measure rest: `R` as a rest is
followed by nothing, a duration or `*n`.

- **Bindings** are musics text's variables, in RPN: `'motif` is a
  name, held (as `'[ ]` holds data and `'( )` code), `:=` binds the value
  under it to it, and `\motif` is that value, as in a `.mus` file. Bare,
  `\motif` pushes it; inside a literal it is spliced like a text
  variable: `'m [!mf c d e] :=`, then `[\m f]` gives `[!mf C4/4 D4/4 E4/4
  F4/4]`. A bound sequence splices its notes and replays its context, a
  leaf is itself, a parallel goes in whole. Only music goes in a
  literal; a number bound to a name is pushed with a bare `\name`.
- `:=` again rebinds: what comes later sees the new value; nothing is
  changed in place. A literal is read before its input runs, so it sees
  bindings of earlier inputs: `'m [c d] := [\m e]` on one line is
  refused -- use `\m` in the next input.
- A literal stored with `R!` takes what its bound names bring with it.
- **Storing in the repo**, by precedence:
  1. `[c4 d e] :motif R!`: an explicit name;
  2. `[motif: c4 d e] R!`: the literal's own id;
  3. `[c4 d e] R!`: an auto id.
- Nothing enters the repo without `R!`. Inner ids of a nested literal
  are stored with it.
- `R!` stores silently, as Forth's `!` does, and leaves the top-level
  id it stored under on the stack (inner ids are stored with it): to
  print (`[c d] R! .` prints `:s12`), fetch (`[c d] R! R@`), inspect
  (`usages`, `ctx`) or bind (`'m [c d] R! :=`). An existing id is replaced silently;
  playing voices keep their snapshot.
- A literal on the stack is a value, not yet in the repo: it can be
  `dup`ed, transformed and played without being stored.
- **Inspecting the repo** (printing a stored part is `:id R@`): `ids`,
  `:inner usages` and `:inner ctx` print exactly what `(ids)`,
  `(usages :inner)` and `(ctx :inner)` give in Clojure: every stored id,
  the ids whose content uses `:inner` directly (what a new `R!` of
  `:inner` changes too), and its context chain, nearest first. `usages`
  and `ctx` also take material fetched with `R@`.

## 7. The REPL

- **Prompt**: `mu<n>`, n the number of items on the stack. A
  continuation is `...`, right-aligned under it:

  ```
  mu<1> : canon ( a s -- p )
    ...   | $a  $a $s +
    ...   par ;
  mu<1>
  ```

- **Multi-line input**: an input is read until the grammar says it is
  complete (an open bracket, `'(`, a definition without `;`, an open
  string or block comment continue it). A parse failure at the very end
  of the text means unfinished; a failure earlier is an error, reported
  with line and column. Ctrl+C abandons an unfinished input.
- **Only words print**, as in Forth: a literal or a number is pushed
  silently, a word's result goes on the stack silently, and the prompt
  shows the new depth (as gforth's ` ok 1`). Printing is what `.`,
  `.s`, `words`, `see`, `ids`, `usages` and `ctx` are for; errors are
  reported.
- **Printing is isomorphic to reading**: what is printed can be typed
  back and gives the same value.
  - musical values print as musics text, absolutely: `C4/4`,
    `<C4 E4 G4>4` (no slash after the octave inside a chord), `r8`,
    `x4\36`; a line as `[…]`, with its own context and id. A duration
    the reader would inherit from the note before it is left out, the
    first always written: `[C4/4 D4/8 E4 F4/4]`; a drum key likewise;
  - a pitch is spelled as it was written (`b&` prints `B&3`), else as
    its key implies (`[!key:F.major b]` prints `B&3`, `B&.minor scale`
    in flats), else with a flat if the note was written with flats,
    else a sharp; the octave is the letter's (`C&4` is 59);
  - everything else prints as Clojure prints it: `7`, `1/4`, `:s12`;
  - quotations print as their source.

## 7a. Behaviour

- **Late binding**: redefining a word changes every word that uses it,
  from then on.
- **Errors clear the stack**, with a clear error message saying what
  failed and where. The input is in the REPL's history, so it can be
  recalled, corrected and run again.
- **The stack lasts the session**: leaving mu (`bye`, or Ctrl+D) and
  entering it again keeps the stack as it was.
- **`"song.mus" load`** reads a file and commits it as the file says
  (every named part stored), as `parse` does, unlike a literal at the
  prompt, which waits for `R!`.
- **Entry point**: `(mu!)` in `musics.core`, replacing today's nested
  musics-text REPL of that name. The implementation lives in its own
  namespace, `musics.mu`.

## 8. Words

mu's own words are **tools** (algo space's kind without a manner) and
the REPL's **kernel**. The **algos** come in through their namespaces
by what they deliver (`duration/euclid`, `pitch/lorenz` …): see
**Algos** below.

### Kernel

| Group | Words |
|---|---|
| **stack** | `dup` `pop` `swap` `over` `rot` `nip` `tuck` `pick` `depth` `clear` (the stack) `.s` `.` |
| **numbers** | `add` `sub` `mul` `div` `mod` `neg` `abs` `min` `max` `round`; conversions `int` `float` `rational` |
| **comparison** | `eq` (any values) `lt` `gt` `le` `ge` `not` |
| **quotations** | `call` `if` `when` `each` `reduce`; `'dup` quotes a word, `execute` runs it |
| **memory** | `'name` `:=` `\name` `R!` `R@` |
| **defining, namespaces** | `:` `;` `use` `in` |
| **playback** | `>` or `play` (stops what plays first), `>+` or `play+` (joins it now), `>>` or `play-next` (in its place, from the next boundary), `>>+` or `play-next+` (joins it, from the next boundary), `stop`, `pause` (pause, or resume if paused), `resume` |
| **synthesizer** | `fluidsynth`, `gervill` (Java's own), `soundfonts` (the `.sf2` files, numbered), `soundfont` (a file in quotes, or a number from `soundfonts`), `echo` (print each note as it sounds -- `x\38 Acoustic Snare`, `\i:40 violin  C4` -- or stop) |
| **repo** | `ids` `usages` `ctx` `defaults` |
| **help** | `words`, `see` (`"dup" see` or `'dup see`: the stack effect, then what the word does -- every built-in word has a description; a defined word shows its source), `cls` (the screen; the stack stays); `use` and `in` take a name in quotes or quoted too (`'pitch use`) |

Stack effects:

| Word | Effect | Does |
|---|---|---|
| `dup` | `( a -- a a )` | copy the top |
| `pop` | `( a -- )` | discard the top |
| `swap` | `( a b -- b a )` | exchange the top two |
| `over` | `( a b -- a b a )` | copy the second over the top |
| `rot` | `( a b c -- b c a )` | rotate the top three |
| `nip` | `( a b -- b )` | discard the second |
| `tuck` | `( a b -- b a b )` | copy the top under the second |
| `pick` | `( xn … x0 n -- xn … x0 xn )` | copy the n-th item to the top |
| `depth` | `( -- n )` | the number of items (what `mu<n>` shows) |
| `.s` | `( -- )` | show the stack, one item a line, top first, numbered as `pick` counts (`0> …`) |
| `.` | `( a -- )` | print the top and discard it |

**Numbers** are the grammar's three primitives: Int, Float and Ratio
(`7`, `0.5`, `1/4`), with Clojure's numeric tower: exact by default
(`1 4 /` gives `1/4`), ratios stay ratios, a float makes the result
float. `int` truncates toward zero (`7/2 int` → `3`), `round` rounds,
`float` converts to a float, `rational` to an exact ratio (Clojure's
`rationalize`: `0.1 rational` → `1/10`).

**`pop` against `drop`.** The stack word that discards the top is
`pop`; `drop` is the form tool, Clojure's own (all but the first n).
`pick` stays the stack word; the weighted draw is `select`.

### Tools

By algo space's tool actions (`doc/md/algo-space.md`, section 0):

| Action | Words |
|---|---|
| **make** | `range` (or `..`: `35 82 ..`, 35 up to 81) `iterate` `scale` |
| **form** | `cycle` `[*]` `[/]` `[+]` `[%]` `[?]` `[~]` `[n]` `{n}` `{+}` `[:]` `[!]` `[|]` `[H]` `[T]` `take` `drop` `take-last` `drop-last` `reverse` `rotate` `shuffle` `then` `interleave` `partition` `split-at` `sputter`; `slice` `splice` `within` |
| **value** | `+` `-` `*` `/` `cresc` `dim` `map` `rescale` `transpose` `invert` `reductions` |
| **selection** | `filter` `remove` `take-while` `drop-while` `distinct` `dedupe` `choose-n` `choose-from` `select`; `mute` |
| **assemble** | `seq` `par` `<>` `zip` `sounds` `with`; `times` `repeat` `volta` `vary` |
| **access** | `first` `last` `nth` `count`; one note: `pitch` `duration` `volume` `articulation` `instrument`; every note of a line: `pitches` `durations` `volumes` `articulations` `instruments` |

- **Form tools act on positions**, never values, so they work on any
  material; value tools read values (numbers, or what a pitch or
  duration means) and refuse the wrong material.
- **A tool keeps the form**: on a `:SEQ` it works on the children and
  gives a `:SEQ` back, keeping its context.
- **Endless input is checked**: an *everything* appetite after an
  *endless* yield (`cycle reverse`) is refused before running.
- **Convert is not a mu action**: the bridges turn raw material into
  half products inside the algos (`pitch/lorenz` is lorenz and its
  bridge), so mu never meets a pulse or a weight.
- **Selection takes a quotation**, the test, run on each element:
  `[c e g c'] '( pitch 65 lt ) filter` keeps the low notes, `remove`
  drops them (the later notes move earlier), `take-while`/`drop-while`
  stop at the first refused. A bar line or a jump mark passes every
  test. `mute` silences the notes its test keeps -- each becomes a rest
  of its length, so the rhythm stays. `distinct` and `dedupe` compare
  elements as mu prints them; `choose-n`, `choose-from` and `select`
  (`[c d e] '[3 1 1] select`, one drawn by the weights) draw at random.
- **A chunk out and back**: `slice` takes the elements from a start up
  to (not including) an end, counted from 0 as `nth` counts (a bar line
  is an element too); `splice` puts something in their place (start =
  end inserts); `within` does both around a quotation --
  `[c d e f g] 1 4 '( reverse ) within` gives `[c f e d g]`, the chunk
  changed where it came from, its length free to change.
- **`map`** runs a quotation on each element: notes give the form back
  (`[c e g] '( 12 + ) map`), anything else a stream (`[c e g] '( pitch
  ) map` is `'[60 64 67]`).
- **A line taken apart**: each reader has a plural (`pitches`,
  `durations`, `volumes`, `articulations`, `instruments`) that gives
  every note's as a stream -- one half product of the line; a chord's
  pitches stay a list, a rest's nil.
  `zip` puts durations and pitches back together, so `x durations x
  pitches zip` is `x` again, and changing one stream between the two
  changes only that half product of the line.
- **Shared with the trees, not yet one implementation**: mu's tools and
  the trees' tools (`musics.algo.tree.lib`, `cycle>` …) are written
  separately; `musics.algo.tool`, one set of plain-named tools for both
  (`doc/md/algo-space.md`, section 0), is still to be built.
- Namespaces: the form, make, selection, map and access-by-position
  tools and the numeric value tools are in `tool`; the meaning value
  tools (`transpose`, `invert`), `mute`, the leaf readers, assemble,
  and the symbols on material (`+` `-` `*` `/`, `cresc` `dim`) are in
  `music`; playing in `play`; rating in `rate`.
- **A text command and its word do the same thing**, prefix in the
  text and postfix on the stack: `transpose c d ( c e g )` is
  `[c e g] c d transpose`, `reverse ( c d e )` is `[c d e] reverse`,
  and `repeat 3 [a b] volta [[c] [d]]` is
  `[a b] 3 times [c] [d] volta`. `times` (`[*]`) gives one repeat
  played n times (it prints, and reads back, as the text, which counts
  the plays); `repeat` counts the repeats, as a musician does: `1
  repeat` plays twice, the text's `repeat 2`. `volta` takes the endings above
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
  out as a line of the passes, not a `repeat volta`; for changes that
  add up pass by pass, use `iterate`.
- **Repeat signs and jumps are text**: `[: a b :]` is `repeat 2
  [a b]`, kept as written (`[: c d :] 2 +` gives `[: D4/4 E4 :]`), and
  `$`, `DS`, `DC`, `Fine`, `Coda` are marks inside a literal, `[c $ d
  DS e]` playing `c d d e`; `./.`, alone in its bar (a bar line before
  it, a bar line or the end after), plays the bar before it again
  (`[c d | ./. | ./. | e]`). A tool that reorders a line can
  leave the marks unplayable (`reverse` puts DS before `$`), which is
  an error.
- **`>>` waits for a boundary**, through the conductor: `x >>` replaces
  what plays when it reaches its end, or the end of its repeat's
  current pass; `x | >>` … `x |||| >>` at the next bar line of that
  strength. The new material starts when the boundary is due, so it
  lands in time; with nothing playing it plays at once. `>>+` waits the
  same way but joins what plays, which goes on -- a second part entering
  at the next `||`. One staged play waits at a time: a later `>>` or
  `>>+` replaces it; `>`, `>+` and `stop` forget it.

### Arithmetic on material, accessors

mu is a musical space: the plain symbols act on material, and the
same operations on numbers are words (`2 3 add`, `1 2 lt`). A plain
symbol on two numbers is refused with that hint (`2 3 +`: "+: is
musical; on numbers write add"); `<`, `<=` and `>=` aren't words but
point to `lt`, `le`, `ge`; `eq` and `ne` compare any two values, and
`:=` binds a name (`=` is no word, and says so). The dot is Forth's: `.` prints, `.s` shows the
stack, `..` is a range.

A bare symbol acts on each element (`[c d] 2 +` transposes every
note); the same symbol in brackets acts on the whole line (`[a b] 3
[*]` plays the line three times, `[c d e] [R]` reverses it). Every bracketed word
is a symbol, and has a named twin, a name Clojure could carry too:
`[R]` `retrograde`, `[I]` `invert`, `[RI]` `retrograde-inversion`,
`[~]` `cycle`, `[:]` `repeats`, `[!]` `unfold`, `[|]` `barred`, `[[]]`
`measures`, `[*]` `times`, `[/]` `parts`, `[n]` `n line`, `{n}` `n
together`, `{+}` `beside`, `<>` `chord`, and the older `[+]` `then`,
`[H]` `head`, `[T]` `tail`, `[%]` `rotate`, `[?]` `shuffle`, `[M]` `mirror`. Playback, which acts on
what plays rather than on a line, is written with `>`: `>` now, `>>` at
the next boundary, a `+` joining instead of replacing (`play`, `play+`,
`play-next`, `play-next+`); `stop` and `pause` stop and pause it.

| On material | Means | Example |
|---|---|---|
| `+`, `-` | transposition up, down, by whole semitones | `c4 2 +` gives `D4/4`; `[c d e] 12 -` an octave down |
| `*`, `/` | duration times, divided by a positive factor, as in the grammar (`c4*1/3`, `R*4`) | `[c d e] 3 /` (triplets), `c4 2 *` |
| `cresc`, `dim` | crescendo, diminuendo across the material, to a volume 0-100 | `[c d e f] 90 cresc` gives `[!vol:50.0< C4/4 D4/4 E4/4 F4/4 !vol:90]` |
| `[/]` | the whole split into n parts, as equal as can be, the longer first | `[a b c d] 2 [/]` gives `[[A3/4 B3] [C4 D4]]` |
| `[+]` | two lines joined into one (`then`) | `[a b] [c d] [+]` |
| `[H]`, `[T]` | the head, the first n elements (1 without a count), and the tail, all after them (`head`, `tail`); the same n splits a line in two | `[c d e f] [H]` gives `[C4/4]`, `[c d e f] 2 [T]` `[E4/4 F4]` |
| `[%]` | the whole rotated by n, wrapping around (`rotate`) | `[a b c d] 1 [%]` gives `[B3/4 C4 D4 A3]` |
| `[?]` | the whole in a random order (`shuffle`) | `[a b c d] [?]` |
| `[~]` | the whole endlessly, `repeat ~ [ … ]`: a loop, played until `stop` | `[c d] [~] >`, `[c d] [~] \|\| >>+` |
| `[:]` | the whole in repeat signs, played twice | `[c d] [:]` gives `[: C4/4 D4 :]` |
| `[!]` | the whole written out as it plays: repeats as their passes, jumps and `./.` followed | `[c4 $ d e DS f] [!]` gives `[C4/4 D4 E4 D4 E4 F4]` |
| `[[]]` | the whole split by its meter into its measures, each a line of its own, cut and tied as `[\|]` does; a setting carries on as it did, a ramp ends at its measure | `[c2 d2 e2] [[]]` gives `[[C4/2 D4] [E4]]` |
| `[\|]` | the whole barred by its meter (its own `!Meter:`, else the defaults'): a `\|` every bar, a note or drum across one cut and tied | `[c2. d2] [\|]` gives `[C4/2. D4/4~ \| D4]` |
| `[2]` `[3]` … | the top n one after another, `[ a b … ]` | `[c d] [e f] [2]` |
| `{2}` `{3}` … | the top n at once, `{ a b … }` | `[c d] [e f] {2}` |
| `{+}` | the parts of two parallels in one `{ }` (a line is one part) | `{[c] [d]} {[e] [f]} {+}` |
| `[*]` | the whole played n times (`times`), the text's `repeat n [ ]`; data and streams written out n times | `[a b] 3 [*]` gives `repeat 3 [A3/4 B3]`, `'[1 2] 2 [*]` `'[1 2 1 2]` |
| `>`, `>+`, `>>`, `>>+` | play now, stopping what plays; joining it now; in its place from the next boundary; joining it from the next boundary | `[c d e] >`, `[e f] >+`, `[g a] \|\| >>`, `[e f] \| >>+` |
| `stop` | stop (and forget a waiting `>>`); Alt+. at the prompt, mid-typing | `stop` |
| `pause` | pause what plays; again, resume it; Alt+, at the prompt | `pause` |
| `sounds` | a copy of a note, chord or drum per number (0-127): a drum's key, a note's instrument | `x8 35 82 range sounds` (the drum set), `c4 0 128 range sounds` (every instrument), `x4 '[36 38 42 46] sounds [~]` |
| `D.major` | a key: a tonic (`#` sharp, `&` flat) and a scale, as `!key:` writes it; without the scale, `D` is the note | `B&.minor`, `f#.dorian` |
| `with` | a line played in a key (as `^{ !key:… } with`) | `[c d e f g] G.major with` plays `f#` |
| `scale` | a key's scale, one octave up from its tonic in octave 4 | `D.major scale` gives `[D4/4 E4 F#4 G4 A4 B4 C#5]` |
| `degrees` | numbers as scale steps of a key: 0 the tonic, 7 an octave up, -1 the step below | `'[0 2 4 7] D.major degrees` gives `[D4/4 F#4 A4 D5]`, `'[0 -1 -3] D.major degrees` `[D4/4 C#4 A3]` |
| `tonic` | a key's tonic, a note in octave 4 | `D.major tonic` gives `D4/4` |
| `key` | the key a note was written in, or a line's own (else the defaults') | `[!key:A.minor a b] key` gives `A.minor` |
| `+`, `-` on a key | the key moved by semitones, its scale kept | `D.major 2 +` gives `E.major` |
| `[R]` | the retrograde, the crab (reverse order); exactly `[R]`, as `[ R ]` is a line with a bar's rest | `[c4 d e g] [R]` gives `[G4/4 E4 D4 C4]` |
| `[M]` | the inversion around the mean pitch, keeping the register (`mirror`) | `[c4 d e g] [M]` gives `[F#4/4 E4 D4 B3]` |
| `[I]` | the inversion around the first note, which stays; I5 is `[I] 5 +` | `[c4 d e g] [I]` gives `[C4/4 A#3 G#3 F3]` |
| `[RI]` | the retrograde inversion: inverted around the first note, then backwards | `[c4 d e g] [RI]` gives `[F3/4 G#3 A#3 C4]` |

- `transpose` takes a musical interval: `[ … ] c d transpose`, by the
  interval from C to D, as the text command does.
- A hairpin starts from the volume the material has (its own
  `!mf` …, else the defaults') and ends on the target: `cresc` and `dim`
  write the grammar's `!vol:60<` … `!vol:90` into the line; the
  direction is the word, its length the material's.
- A length prints as a note value where it is one, dotted if need be
  (`c4 3/2 *` gives `C4/4.`). Otherwise it is a tuplet: in a line,
  consecutive notes sharing a factor print as one group, written values
  with the count before them, `[c8 d e] 2/3 *` as
  `[3:2(C4/8 D4 E4)]`, as the text writes a tuplet; a lone note
  as its value with the factor, `C4/8*2/3`. The factor is the one the
  note carries (`3:2( )`, `c8*2/3`, `*`, `/`), else the standard one
  for its length (2/3, 4/5, 4/7, 8/9), so it prints back as written.
  A note value is a power of two: `c3` is refused, with a hint.
- A rest moves in time but not in pitch: `r4 2 +` is `r4`, `r4 2 *` is
  `r2`.
- `<>` gathers the leaves on top of the stack into a chord, stopping at
  the first non-leaf: `[c d] a c e <>` leaves `[c d]` and `<A3 C4 E4>`.
  The literal `<a c e>` is the grammar's chord.
- `sounds` builds new leaves from one, as `zip` builds them from
  durations and pitches; it is for hearing what a soundfont holds
  (`doc/md/setup.md`, Gervill).
- **Readers only**, no writers: `pitch` (a note's, a chord's as a
  list, nil for a rest), `duration`, `volume`, `articulation`,
  `instrument`, and `note?` `chord?` `rest?` `drum?`, each on one note
  (on a line, an error naming the plural). A reader is a step out of music, back down
  from atom to primitive: its result is a number or a keyword, not a
  pitch. `volume`, `articulation` and `instrument` read the leaf's own
  written value (or `nil`), not the sounding one; a drum's `instrument`
  is its key. `key` reads the key a note was written in (see the keys
  above).
- **Access**: `first`, `last`, `nth` (from 0) and `count` take a line,
  data or a stream (`first` and `nth` an endless one too).
- **Repeating**: `times` (`[*]`) plays n times and `repeat` adds n
  repeats, both one repeat (`vary` varying its passes); `[!]` writes the
  passes out as lines, each of which a quotation can change on its own
  (`[a c d e] 3 [*] [!] '( shuffle ) map`: three different shuffles);
  `[~]`/`cycle` loops, and a loop is taken from like a stream (`[c d]
  cycle 5 take`); `iterate` applies a quotation
  again and again (`c4 '( 2 + ) iterate 4 take`, or on numbers `1 '( 2 add ) iterate 4 take`), `each` and `map` work
  per element.

### Feedback

Rating what you hear (`doc/md/feedback.md`, `musics.mu.feedback`):

| Word | Key | Does |
|---|---|---|
| `like` `meh` `dislike` | Alt+l Alt+k Alt+j | rate the audition's item, else the sound last played (a note, chord or drum), 1 / 0 / -1, into `~/.mu-feedback.edn` |
| `again` | Alt+K | play it again |
| `next` `back` | Alt+L Alt+J | the audition's next or previous item |
| `audition` | | ( line -- ) play its sounds one by one in the background; `stop` stops |
| `gap` | | ( ms -- ) the silence between an audition's sounds, 150 by default |
| `step` | | auditions wait for a rating or `next`, or move on by themselves (the default) |
| `combos` | | ( leaf numbers k -- line ) every k of the numbers layered on the leaf: `c4 '[40 41 71 73] 2 combos` |
| `ask` | | ask for a rating when a played sound ends, or stop asking |
| `taste` | | the ratings per sound and per instrument or drum, best first |
| `liked` `disliked` | | ( -- line ) the sounds rated above or below zero |

A key works while you type: it leaves the line alone and answers
above the prompt. The keys are three neighbours, unshifted to rate
(`,` `/` `.`) and shifted to move (`<` `?` `>`). A rating in an audition moves on to the next item.

### Algos

Every algo on a half-product or product shelf is a word in that shelf's
namespace (`musics.mu.algos`, generated from the registry):
`pitch/lorenz`, `pitch/walk-degrees`, `duration/euclid`,
`duration/emi`, `volume/walk`, `articulation/tala`, `leaf/pocket`,
`parallel/drums`, … — the same names as in trees. The namespaces aren't
in use, so a word is written qualified, or after `"pitch" use`; `words`
lists them with their counts, `"duration/emi" see` gives the effect,
the doc and the `@` params.

```
duration/euclid                          → '[3/16 3/16 1/8]
^{ @k:5 @n:16 } duration/euclid          the context on top is its settings
[c8 d4 e8] dup durations ^{ @index:1 } duration/necklace swap pitches zip
                                         the rhythm rotated, the pitches kept
^{ !key:D.major @len:4 } pitch/walk-degrees   a key param follows the music's !key:
```

- **Inputs**: as many as the algo has (a source none). A line is taken
  apart into what the algo wants -- durations (a rest as itself),
  pitches (a chord's lowest, nil for a rest) or leaves; a stream is
  taken as it is.
- **Settings**: a `^{ … }` on top is the algo's settings, `@name:value`
  (`doc/md/algo-space.md`, the tctx as a context): read first, then the
  music's own (`:ROOT`'s, so `!key:` reaches a key param), then the
  defaults. A setting the algo doesn't read is ignored; a value out of
  range is an error naming the word.
- **Results**: streams come back as streams, leaves as a line, parts as
  `{ }`. `zip` keeps a rest among an algo's durations a rest.

### First set

To write and play music by hand: the whole kernel; make, form,
selection and assemble; from the value tools `map` and `transpose`; the leaf readers, on a leaf or a whole line; and the algos.
The other value tools (`rescale`, `reductions`) follow.

## 9. Still open

1. **Algos in mu, what's left**: two algos sharing one run (`:as`,
   pitch and volume of one attractor) -- a context naming an instance
   is the likely spelling; a tree of several algos as one word
   (`: riff … ;` composes words already, but runs each on its own).
2. **A word's several inputs** (with the algo trees): `|` marks also
   separate them, `| c d e | 1/4 1/8 color-talea`, and the deepest mark
   a word reaches is where its inputs end.
