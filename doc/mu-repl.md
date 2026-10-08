# mu: a musical REPL

A stack-based REPL, Forth- and Factor-like, in which musical data is
first-class: `[ ]` is a sequence (`:SEQ`) and `{ }` a parallel (`:PAR`),
not Clojure data. Every input is parsed by instaparse, through a grammar
that includes `musics.ebnf`, so musics text means in the REPL what it
means in a `.mus` file.

This is a design, decided step by step (2026-10-08). Nothing is built
yet. Section 9 lists what is still open.

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
| `dup` `play` `transpose` `s` | a word | run |
| `\a` | a word, forced (for a name that looks like a leaf) | run |
| `tool/cycle` | a qualified word | run |
| `$a` | a local, inside a word's body | its value pushed |

**Leaves win.** A bare `a`, `c4` or `R` is always a note or rest. Any
other name is a word, and `\name` forces a word.

**Top level against literals.** At the top level a bare name is a word
(`transpose`, postfix, from the stack). Inside `[ ]`, `{ }` and `^{ }`
the text is `.mus` text, unchanged: `\transpose c d ( … )` is the text
command, `\name` a VarRef splice, `:name` a reference, `c4\f` a glued
dynamic, `!mf` an instruction. Text pasted from a `.mus` file means
what it means in the file.

**`!` and `:` by position.** `!` with a space after it is the store
word, `!mf` glued an instruction; `: ` with a space starts a definition,
`:name` is a name, `name:` an id.

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
- `n seq` gathers the top n values into a `:SEQ`, `n par` into a
  `:PAR`: `c4 d e 3 seq` is the same line as `[c4 d e]`.

## 4. Instructions and contexts

- Inside literals, instructions are as in `.mus`: `[!mf c4 d e !vol< f]`.
- **A loose instruction at the top level is an error**, with a hint:
  write `[!mf …]`, or `^{ !mf }` for a context. `:ROOT` stays read-only.
  A deliberate `defaults` word for session-wide settings may come
  later.
- `^{ … }` is a value like any literal: store it (`:loud !!`), use it
  inside a literal (`[:loud c4 d e]`), or apply it with `with`:
  `[c4 d e] ^{ !pp } with`. Nearest wins, as in the ctx-chain.

## 5. Words

**Defining**, Forth style:

```
: octaved  dup 12 transpose 2 par ;
: canon ( a s -- p )  $a  $a $s transpose  2 par ;
: echo  ( m -- l )    [$m $m r4 $m] ;
```

- `: name … ;` defines a word. The colon needs a space after it.
- **Locals are optional**: with a stack effect `( a b -- c )`, its
  input names are bound as locals; without one, the word is plain stack
  code. Any name may be a local, leaf letters included.
- A local is written **`$a`**, in the body and inside literals
  (`[$m $m r4 $m]` splices it). Locals are **constants**: bound once
  per call.
- **Quotations** are `'( … )`, the quoted counterpart of `'[ … ]` (held
  data): held code, run by `call`, `if`, `each`, `map` …

**Namespaces**:
- a word is qualified as `ns/word` (`tool/cycle`, `grid/euclid`);
- `"tool" use` makes a namespace's words usable unqualified, and `"song" in`
  sets where new definitions go;
- **an ambiguous name is an error** until qualified;
- visible by default: `kernel`, `music`, `mine`; new words go into
  `mine`;
- the namespaces match `musics.trees`: `tool`, and the generator
  namespaces `grid/`, `stream/`, `line/` and `parallel/`.

## 6. Memory

| Memory | Lives | Holds | Store | Fetch |
|---|---|---|---|---|
| local | one call of a word | what the stack effect bound | — (constant) | `$a` |
| variable | the session | any value | `x !` | `x @` |
| repo | shared, saved, playable | material | `!!` | `:id @@` |

- `variable x` declares a word `x` that pushes the variable.
- **Storing in the repo**, by precedence:
  1. `[c4 d e] :motif !!`: an explicit name;
  2. `[motif: c4 d e] !!`: the literal's own id;
  3. `[c4 d e] !!`: an auto id.
- Nothing enters the repo without `!!`. Inner ids of a nested literal
  are stored with it.
- `!!` prints the id it stored under, as Clojure prints it (`:s12`),
  and leaves the stack empty. An existing id is replaced silently;
  playing voices keep their snapshot.
- A literal on the stack is a value, not yet in the repo: it can be
  `dup`ed, transformed and played without being stored.

## 7. The REPL

- **Prompt**: `mu<n>`, n the number of items on the stack. A
  continuation is `...`, right-aligned under it:

  ```
  mu<1> : canon ( a s -- p )
    ...   $a  $a $s transpose
    ...   2 par ;
  mu<1>
  ```

- **Multi-line input**: an input is read until the grammar says it is
  complete (an open bracket, `'(`, a definition without `;`, an open
  string or block comment continue it). A parse failure at the very end
  of the text means unfinished; a failure earlier is an error, reported
  with line and column. Ctrl+C abandons an unfinished input.
- **Printing is isomorphic to reading**: what is printed can be typed
  back and gives the same value.
  - musical values print as musics text, absolutely, with explicit
    durations: `C4/4`, `<C4 E4 G4>4` (no slash after the octave inside a
    chord), `r8`, `x4\36`; a line as `[…]`, with its own context and id;
  - everything else prints as Clojure prints it: `7`, `1/4`, `:s12`;
  - quotations print as their source.

## 8. Words

mu's own words are **tools** (algo space's kind without a manner) and
the REPL's **kernel**. The **algos** come in through their namespaces
(`grid/euclid`, `stream/lorenz` …), after the kernel.

### Kernel

| Group | Words |
|---|---|
| **stack** | `dup` `pop` `swap` `over` `rot` `nip` `tuck` `pick` `depth` `.s` `.` |
| **numbers** | `+` `-` `*` `/` `mod` `neg` `abs` `min` `max` `round`; conversions `int` `float` `rational` |
| **quotations** | `call` `if` `when` `each` `reduce` |
| **memory** | `variable` `!` `@` `!!` `@@` |
| **defining, namespaces** | `:` `;` `use` `in` |
| **playback** | `play` `stop` `pause` `resume` |
| **help** | `words` `see` |

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
| `.s` | `( -- )` | show the stack |
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

By algo space's tool actions (`doc/algo-space.md`, section 0):

| Action | Words |
|---|---|
| **make** | `range` `repeat` `iterate` `scale` |
| **form** | `cycle` `times` `take` `drop` `take-last` `drop-last` `reverse` `rotate` `shuffle` `then` `interleave` `partition` `split-at` `sputter` |
| **value** | `map` `rescale` `transpose` `invert` `stretch` `reductions` |
| **selection** | `filter` `remove` `take-while` `drop-while` `distinct` `dedupe` `choose-n` `choose-from` `select` |
| **convert** | the bridges (`pulses->durations` …) |
| **assemble** | `seq` `par` `zip` `with` |
| **access** | `first` `last` `nth` `count` |

- **Form tools act on positions**, never values, so they work on any
  material; value tools read values (numbers, or what a pitch or
  duration means) and refuse the wrong material.
- **A tool keeps the form**: on a `:SEQ` it works on the children and
  gives a `:SEQ` back, keeping its context.
- **Endless input is checked**: an *everything* appetite after an
  *endless* yield (`cycle reverse`) is refused before running.
- **One implementation**: mu's tools call `musics.algo.tool`, the same
  operations a tree uses.
- Namespaces: the form, make, selection and access tools and the
  numeric value tools are in `tool`; the meaning value tools
  (`transpose`, `invert`, `stretch`) and assemble are in `music`.

### First set

To write and play music by hand: the whole kernel; make, form and
assemble; and from the value tools `transpose` and `stretch`.
Selection, the other value tools, convert and the algo namespaces
follow.

## 9. Still open

1. **Accessor words** on a leaf for quotations (`pitch`, `duration`,
   `volume`).
2. **`times`** as one word for both repeating material and running a
   quotation n times.
3. **`defaults`**: session-wide settings in `:ROOT`, deliberately.
4. **Files**: `"song.mus" load`, keeping a file's behaviour (everything
   committed).
5. **Algo trees in mu**: through the generator namespaces, after the
   kernel.
