# mu: a musical REPL

A stack-based REPL, Forth- and Factor-like, in which musical data is
first-class: `[ ]` is a sequence (`:SEQ`) and `{ }` a parallel (`:PAR`),
not Clojure data. Every input is parsed by instaparse, through a grammar
that includes `musics.ebnf`, so musics text means in the REPL what it
means in a `.mus` file.

Decided step by step (2026-10-08) and built on branch `mu`: `lein mu`
runs it. With the GUI (`gui`) it is the program's only interface: a
`.mus` file and the GUI editor's text are mu input too. Section 9 lists
what is still open, and [mu-words.md](mu-words.md) has every word.

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
- **One grammar for `.mus` and mu.** `musics.ebnf` defines the
  literals; `mu.ebnf`, appended to it, the top level (`MuInput`), the
  one start rule for a file and the prompt alike.

## 2. What a seq contains

| Written | Is | Pushed or run |
|---|---|---|
| `[c4 d e]` `{[c d] [e f]}` `'[ … ]` | a musical literal: sequence, parallel, data | pushed as a value |
| `'[c d e]` `'[C4 D4]` `'[C D E]` | notes in a data list: lowercase relative to the pitch before, a capital with an octave absolute, a capital alone a pitch class | pushed as the data lists `'[60 62 64]`, `'[60 62]`, `'[0 2 4]`; a list holds one kind, so classes and pitches cannot mix |
| `^{ !ff !tempo:120 }` | a context literal | pushed as a value |
| `c4` `C5/8` `<C4 E4 G4>4` `r4` `x8\36` | a loose leaf | pushed as one value |
| `7` `1/4` `0.5` `"text"` | a primitive | pushed |
| `/4` `/8.` `/4*2/3` `\breve` | a duration alone, as a note writes it (`/` marks it, as in a `'[ ]`) | pushed as its ratio of a whole note: `/4` is 1/4, `/8.` 3/16; `( /4 /8 )` is `'[1/4 1/8]`. Not inside a seq |
| `M:3/4` `T:8=200` `K:D.major` `vol:mf` | a setting alone: `name:value`, no space, as `!name:value` writes it in a seq | pushed as the context `^{ !M:3/4 }`: `x M:3/4 with` lays it under x. A leaf wins (`c:m7` is a chord) |
| `:motif` | a repo name (an id) | pushed as a name |
| `'( dup 12 transpose )` | a quotation: code held, not run | pushed |
| `( [c d] 2 times [e] [f] volta )` | a group: run now | what it leaves, gathered by its first value: music into one seq, numbers, keywords and data lists into a data list `'[ ]` (`( 60 12 + 62 )` is `'[72 62]`); an empty `()` gathers the stack the same way, what lies above the nearest `\|` else all of it |
| `'motif` | a name, held (for `:=`) | pushed |
| `\motif` | the value bound to that name | pushed |
| `dup` `play` `transpose` `s` | a word | run |
| `music/cycle` | a qualified word | run |
| `$a` | a local, inside a word's body | its value pushed |

**Leaves win.** A bare `a`, `c4` or `R` is always a note or rest. Any
other name is a word. A note can't be redefined: `: a … ;` is
refused, since bare `a` would always be the note.

**Top level against literals.** At the top level a bare name is a word
(`transpose`, postfix, from the stack). Inside `[ ]`, `{ }` and `^{ }`
the text is `.mus` text, unchanged: `transpose c d ( … )` is the text
command (at the top level it is refused, pointing at
`[transpose c d ( … )]` or the word, `x c d transpose`), `\name` a variable (the value bound to it, see Memory), `:name` a reference, `c4\f` a glued
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
- `|` at the top level is a **mark**, and the words that take as many
  values as you like gather what lies above the nearest one, **or the
  whole stack when there is none**: `zip` and `>ctx` their parts, `par` the
  parts of a parallel (`| [c d] [e f] par`), `chord` its notes, and the bare
  `()`; a play word reads its count as a bar line's strength. Inside brackets `|` is a bar line, as in the text. `( c4 d
  e )` is the same seq as `[c4 d e]`: a group runs what is inside it and
  gathers the result, a seq of music or a list of numbers.
- **Words don't run inside a literal**: a literal is musics text, read
  before anything runs. A known word inside one is an error that names
  it and shows the way: `( [a b] [?] [c d] [?] )`.

## 4. Instructions and contexts

- Inside literals, instructions are as in `.mus`: `[!mf c4 d e !vol< f]`.
- **An instruction at the top level is a context** holding it: `!tempo:89`
  is `^{ !tempo:89 }`, so `[c d] !tempo:89 with` writes it at the start of the
  seq, the same spelling as in a seq (`!tempo:4=120`, `!tempo<16:140`,
  `!allegro`, `!tempo:140@4`). Several: `^{ !mf !tempo:89 } with`. mu never
  writes `:ROOT`.
- **`with` on a voice** changes what plays: `:bass !tempo:140 with`,
  `:bass :Tempo 140 with` or `:bass ^{ !mf !tempo:90 } with` write the settings
  in front of the voice's own music and swap it in at the end of its pass, as
  `-|>` does; the id is taken and nothing is left on the stack. Do one change at a time: a
  second `with` before the pass ends starts again from the music still playing,
  so several settings go in one `^{ }`.
- `^{ … }` is a value like any literal: store it (`:loud R!`), use it
  inside a literal (`[:loud c4 d e]`), apply it with `with` (a new
  value, to keep or store: `[c4 d e] ^{ !pp } with` gives `[!pp C4/4 D4
  E4]`, the settings in front of the seq's own, so its own still win;
  a `{ }` or a repeat is wrapped instead), or give it to
  `play` for one performance: `[c4 d e g] ^{ !tempo:90 } play`.
- **A context given to play comes first** (`musics.compose/over?`): it
  is the first context in the chain, in front of the material's own,
  then the material's own contexts, then the defaults. So
  `[!tempo:120 c4 d e] ^{ !tempo:90 } play` plays at 90 -- the context
  just before the play word overrides what the music says -- and
  `[c4 d e] ^{ !tempo:90 } play` at 90 too. `with` is the other way
  round: it fills in what the seq leaves open (its settings go in front
  of the seq's own, which come after and win).
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
: double ( n -- n' )   dup + ;
: octaved ( $x -- p )  | $x $x 12 + par ;
: canon ( $a $s -- p ) | $a  $a $s +  par ;
: echo  ( $m -- l )    [$m $m r4 $m] ;
```

- `: name … ;` defines a word. The colon needs a space after it. The
  name can't be a leaf (`a`, `c4`, `x`, `R` …). It may start with `?`
  (`?empty`).
- **The stack effect** `( inputs -- results )` is read as in Forth. An
  input is taken from the stack and stays there, for the stack words
  (`( n -- n' ) dup +`), unless it is written with a **`$`**: `( $a $b -- c )`
  takes `a` and `b` off the stack and binds them as locals, read as `$a`
  and `$b` in the body and inside literals (`[$m $m r4 $m]` splices it).
  The two mix, `( $m n -- m' )` binds `m` and leaves `n` on the stack. Locals
  are **constants**, bound once per call; any name may be one, leaf letters
  included. A word without a stack effect is plain stack code.
- **The results are kept**: the word must leave as many values as the
  names after `--`, or its call is refused (`twin: leaves 2 values -- its
  stack effect says 1`), and a result named like an input, `x` for `x`, is
  that input as it came (`( x -- x )` on a word that changes `x` is refused:
  write `x'`). A name ending in `...` is any number and is not counted
  (`( x... -- l )`). The names are the same ones mu's own help shows:
  `x` the same, `x'` changed, any other name a new thing; by type, `n` a
  number, `x` anything, `m` music, `l` a list or stream, `q` a quotation,
  `t` a length in whole notes, `id` a stored name, `flag` true or false,
  numbered when two share one (`n1 n2 -- n3`).
- **Quotations** are `'( … )`, the quoted counterpart of `'[ … ]` (held
  data): held code, run by `call`, `if`, `each`, `map` …
- **`loop`** is Forth's `do … loop` with a quotation for its body:
  `limit start '( … ) loop` runs the body once for each whole number from
  `start` up to, not including, `limit`, with `i` the index, `j` the index of the
  loop around it and `k` the one around that. `3 0 '( i ) loop` leaves `0 1 2`, `0 5 0 '( i + ) loop`
  adds them (10), and `2 0 '( 3 0 '( i j 10 * + ) loop ) loop` leaves
  `0 1 2 10 11 12`. A start not below the limit runs it no times, and `i`, `j` or
  `k` outside enough loops is an error. `limit start step '( … ) +loop` steps by a whole number that is not 0, a
  negative one counting down: `0 5 -1 '( i ) +loop` leaves `5 4 3 2 1`, `10 0 3 '( i ) +loop`
  leaves `0 3 6 9`.
- **A live word**: a word whose inputs are all `$` plays as a voice, its
  inputs the voice's changeable values: `5 8 'riff :bass ->` (the quoted
  name keeps it from running now; the voice needs a name). `:bass/onsets`
  is a **reference** to one input (`@` reads it, `!` stores into it, heard
  from the voice's next note); `:bass settings` and `:bass gui` list them
  with the range of the algo parameter of the same name; `:bass ->` starts
  the voice again with the inputs as they are. See the tutorial, "A word as
  a live voice".
- **A voice as a function of the beat**: a live word with `$beat` among its inputs
  is asked for **one leaf at the beat the voice has reached** (`60 'tick :v ->`; the
  stack gives the other inputs, `$beat` is the voice's own), and every lookup for the
  leaf, producing it or resolving it, is made at that beat. Its settings are the
  **tctx**, a table of keyword keys (`T!`, `T@`, `T@@`, `T_`); a
  context's keys are strings, so `:density` and `"density"` are different keys. Inside
  such a voice a keyword argument of a word that the tctx holds is its value
  (`:onsets 8 pattern/euclid`). See the tutorial, "A voice as a function of the
  beat", and `doc/md/pull-voice.md`.

**Namespaces**:
- a word is qualified as `ns/word` (`music/cycle`, `pattern/euclid`);
- `"grid" use` makes a namespace's words usable unqualified, and `"song" in`
  sets where new definitions go;
- `"musics.algo.numeric.random" require` loads a Clojure namespace while mu runs,
  its public functions words under its last name (`random/uniform`), or
  under a short alias given after it (`"musics.algo.numeric.random" 'r require`,
  `r/uniform`); a `.clj` file too (`"my/words.clj" require`, named by its
  `ns` form). Requiring again reloads it, a file afresh, so an edit is
  heard at once; a mu namespace of its own (`kernel`, `pitch` …) can't be
  an alias. A function with a keyword tail (`& {:keys [length]}`) takes its
  `:key value` pairs written just before the word, or none: `13 r/bits`,
  `13 :length 8 r/bits`; `see` shows them as `[:length v]`. `reqalgo` is
  `require` for the namespaces of `musics.algo`, named without that start:
  `"numeric.rng" reqalgo` is `"musics.algo.numeric.rng" require`. A name
  may be quoted instead of written in a string, dots and all, and an alias
  follows it: `'bridge 'b reqalgo`, `'numeric.rng 'r reqalgo`
  (`require` takes a quoted name too). An algorithm
  of `musics/algo/` takes all its arguments from the stack, as `see` lists
  them (`3 8 pattern/euclid`; its defaults are the tree's). `pull` calls a
  function n times and gives the list: `'( 0.0 1.0 random/normal ) 12 pull`
  for a draw, `60.0 2.0 walk/walk 16 pull` for a function that keeps its
  place, a generator with a `:value` (lorenz) too. A quotation `'( … )` is
  also a function of what it is given, so it can be the fitness function of
  `process/genetic`, and `( '( … ) '( … ) )` gathers quotations into the list of functions a function wants (the constraints of `melody/constrained`). A list inside a list is written `'[ '[0 1] '[2] ]`;
- **an ambiguous name is an error** until qualified;
- visible by default: `kernel` (the stack, quotations, numbers,
  binding, the repo, help, files), `tool`, `music`, `play` (playback and
  the synthesizers), `rate` (ratings and auditions), `mine`; new words
  go into `mine`;
- the algorithms come in through `require` (below): `"rhythmic.pattern"
  reqalgo` makes `pattern/euclid` a word.

## 6. Memory

| Memory | Lives | Holds | Store | Fetch |
|---|---|---|---|---|
| local | one call of a word | what the stack effect bound | — (constant) | `$a` |
| binding | the session | any value | `'shift 7 :=` | `\shift` |
| repo | shared, saved, playable | material | `R!` | `:id R@` |

`R!` and `R@` (named `store` and `fetch`) are words, never a multi-measure rest: `R` as a rest is
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
  changed in place. An input's literals are read up to each `:=`, just
  before that part runs, so `'m [c d] := [\m e]` works on one line; a
  literal before the `:=` that binds its name is refused. After a `:=`
  the reading starts afresh (durations, relative pitch).
- A name bound to another (`'dp 'duration-pulse :=`) is a short
  name for an algo param: `@^{ dp:1/8 }` is read, kept and shown as
  `@^{ duration-pulse:1/8 }`.
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
  (`usages`, `ctx`) or bind (`'m [c d] R! :=`). An existing id is replaced silently
  when nothing else stored uses it; when other parts do, `R!` refuses and names
  them (`R!: :motif is used by :verse -- R!! stores it anyway …`), and **`R!!`**
  (`overwrite`) stores it and says which parts change with it. Playing voices keep
  their snapshot either way. Reading a file (`load`, the editor) behaves as `R!!`.
- A literal on the stack is a value, not yet in the repo: it can be
  `dup`ed, transformed and played without being stored.
- **Inspecting the repo** (printing a stored part is `:id R@`): `ids`,
  `:inner usages` and `:inner ctx` print exactly what `(ids)`,
  `(usages :inner)` and `(ctx :inner)` give in Clojure: every stored id,
  the ids whose content uses `:inner` directly (what a new `R!` of
  `:inner` changes too), and its context chain, nearest first. `usages`
  and `ctx` also take material fetched with `R@`, and `ctx` a seq that is
  not stored: `[!T:90 c d] ctx` prints its own settings, where they begin.

- **Tidying the repo**: `:motif forget` takes a stored name out of the
  repo and out of `:ROOT`; it is refused while another stored id uses it
  anywhere (a child, a repeat's body, an override marker -- the message
  names them: `:motif is still used by :b :r`), and `:ROOT` stays.
  `:motif :theme rename` gives a stored name another in one step, in the
  node, in `:ROOT`'s children, in every container that holds it, in the
  `:name` of an override marker and the `^{ name: … }` of an inline
  context, and in a stored text that names it; refused when
  the new name is stored already or is no name (a letter, then letters,
  digits and `_`), and `:ROOT` keeps its name. What plays keeps its
  snapshot and its voice name, as after any `R!`.

## 7. The REPL

- **`ok`**: an input that ran prints `ok` after whatever it printed, as in
  Forth; one that failed prints `error:` and the reason, and leaves the
  stack as it was. Nothing else is shown: a value stays on the stack until
  `.` prints it or `.s` shows the stack, and the prompt's number tells
  how deep it is.
- **Prompt**: `mu<n>`, n the number of items on the stack. A
  continuation is `...`, right-aligned under it:

  ```
  mu<1> : canon ( $a $s -- p )
    ...   | $a  $a $s +
    ...   par ;
  mu<1>
  ```

- **`,` (`edit`) takes the top of the stack back into the prompt** as
  its printed text, ready to change and run again: `[c d e] ,` leaves
  the stack one less and `mu0> [C4/4 D4 E4]` to edit. Printing is
  isomorphic to reading, so what comes back reads as the value it was.
  `.` prints and drops, `.s` prints the stack.

- **Multi-line input**: an input is read until the grammar says it is
  complete (an open bracket, `'(`, a definition without `;`, an open
  string or block comment continue it). A parse failure at the very end
  of the text means unfinished; a failure earlier is an error, reported
  with line and column. Ctrl+C abandons an unfinished input.
- **Only words print**, as in Forth: a literal or a number is pushed
  silently, a word's result goes on the stack silently, and the prompt
  shows the new depth (as gforth's ` ok 1`). Printing is what `.`,
  `.s`, `words`, `see`, `help`, `ids`, `usages` and `ctx` are for; errors are
  reported. An unknown word is met with what to write instead: the name it
  used to have (`add` is now `+`), else the words a typo away (`retrogrde`:
  did you mean `retrograde`?), a word of a namespace not in use written
  with it, else where to look (`words`, `help`).
- **Printing is isomorphic to reading**: what is printed can be typed
  back and gives the same value. Brackets are written tight, with no
  space inside them: `[a b c]`, `{[a b] [c d]}`, `repeat 2 [a b c] volta
  [[c] [b]]`; so are the texts that `prog/->mus`, `walking-bass`, a
  recording and `display` write.
  - musical values print as musics text, absolutely: `C4/4`,
    `<C4 E4 G4>4` (no slash after the octave inside a chord), `r8`,
    `x4\36`; a seq as `[…]`, with its own context and id. A duration
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
- **An error leaves the stack as it was before the input**, with a clear
  error message saying what failed and where; an input is all or
  nothing, so none of its words' results are kept. The input is in the
  REPL's history, so it can be recalled, corrected and run again.
- **The stack lasts the session**: leaving mu (`bye`, or Ctrl+D) and
  entering it again keeps the stack as it was.
- **`"song.mus" load`** runs a file as mu input on a stack of its own
  and stores the music it leaves, each part under its name or an auto
  id (`musics.mu.store`), unlike a literal at the prompt, which waits
  for `R!`; its bindings and definitions stay. `.ly`, `.abc` and `.gmn`
  are converted first. `x "x.mid" render` writes a MIDI file.
- **A session is saved whole**: `"piece.edn" save-session` writes what is
  stored, mu's words and its bindings, the latter two as mu text (the
  namespaces required and in use, each `: … ;` with its literals written
  out in full, each `'name value :=` whose value can be written; not the
  stack); `"piece.edn" restore-session` replaces the repo, the words and
  the bindings with the file's, the stack staying.
- **Entry point**: `lein mu` (`musics.mu/-main`).

## 8. Words

The words, by group and with what each takes and gives, are in [mu-words.md](mu-words.md). mu's
own words are **tools** (algo space's kind without a manner) and the REPL's **kernel**; the
**algos** come in through their namespaces as plain functions called by name
(`pattern/euclid`).

## 9. Still open

1. **Maps from mu text**: a parameter that is a map (Markov transitions, L-system
   rules) cannot be written yet, so `markov/chain` and `melody/lsys-melody` are
   only shown.
2. **A word's several inputs**: `|` marks also separate them, `| c d e | 1/4 1/8
   color-talea`, and the deepest mark a word reaches is where its inputs end.
