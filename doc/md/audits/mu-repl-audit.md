# mu REPL audit: consistency, complexity, intuitiveness, expressiveness, doublures, compactness

2026-11-03, at commit `7973c96` (develop). Scope: interaction at the `mu<n>`
prompt: the grammar (`mu.ebnf`), the word registry as `musics.mu.library`
builds it, and how words behave on real input. No code was changed.

Method: the live registry was dumped (606 words) and about 110 inputs were run
through `musics.mu.eval/eval-input`, so every "evidence" line below is real
output, not reading. Not covered: Tab completion, key bindings (Alt+...),
history and the JLine layer (they need a real terminal), the GUI, and the 336
generated algo words beyond their naming scheme.

Each finding names the yardstick it serves:

- **A** = concatenative integrity (one meaning per spelling, words compose, no
  hidden modes).
- **B** = the person at the prompt (guessable, recoverable, short to type).

## Numbers

| | |
|---|---|
| words in the registry | 606 |
| core words (kernel, tool, music, play, rate, soundfont) | 270 |
| generated algo words (`pitch/lorenz`, `duration/euclid`, ...) | 336 |
| sign words among the core (`[R]`, `+`, `!`, `@`, `{n}`, ...) | 47 |
| alias groups (one function, several spellings) | 45, with 47 extra spellings |
| core words that have a second spelling | 86 of 270 (32%) |
| mean length of a core word's name | 5.9 characters |

## Ranked findings

### High

**H1. Any error empties the whole stack (A fine, B hurts).**
`[c d e] 7 +`, then `1 2`, then `foo`, then `depth` gives `0`. The music built
over several lines is gone because of one typo. `eval-input` documents this
("after which the stack is empty"), and it matches Forth's ABORT, but a mu
stack holds composed material, not scratch numbers.
Fix: restore the stack to what it was before the failing input.

**H2. The `drop` rename is silent for old scripts (B).**
`[c d e f] 2 drop` is now valid: it discards the `2` and leaves the line
untouched, with no error. Before the rename it cut two elements. The rename
itself is right (A); the failure mode is the problem.
Fix options: (a) `drop` refuses a number lying on music or data, saying "to
cut elements write tail"; (b) leave it and rely on the decisions log. (a)
costs one check and keeps the Forth meaning for everything else.
Related asymmetry the rename leaves behind: `take`/`tail`, `take-last`/
`drop-last`, `take-while`/`drop-while`. Three families, three vocabularies,
and no `tail-last`.

**H3. `+ - * /` on data succeed and do nothing (A and B).**
`'[60 62] 12 +` gives `'[60 62]`; `'[1 2 3] 2 *` gives `'[1 2 3]`. The same
signs on bare numbers are refused with a hint (`+: is musical; on numbers
write add`), and `'[60 62] 12 add` is refused too. A silent identity is the
worst of the three possible behaviours. It also shows an expressiveness gap:
there is no arithmetic over a stream except `'( 12 add ) map`, and streams are
what every algo delivers.
Fix: refuse on data, or (better) make `add sub mul div` (and then the signs)
broadcast over a stream.

**H4. The sign space is spent on music, so the first guess of anyone who has
used a programming language fails (B; A is neutral).**
`3 5 +`, `3 5 *`, `3 4 /`, `3 5 =`, `3 5 <=`, `3 5 >=` all fail with a hint, and
`3 5 <` fails with `playing: no voice 5`, because `<` and `>` are the play
words. mu already knows the answer in each case (the hints name it). A
type-dispatched `+` (numbers add, notes transpose, streams broadcast) is the
Factor-style fix and removes a family of hint errors. `<` and `>` cannot be
dispatched by type, because `3 5 >` could legitimately be play with a number
as material; that conflict stays unless play moves.

**H5. mu prints stack effects it cannot read (A).**
`see` and the help show `( x -- x' )`, but `: f ( x -- x' ) 12 + ;` is a parse
error ("Expected one of: )") because `EffectName` has no `'`. The error gives
no hint that the prime is the cause.
Fix: allow `'` in `EffectName` (one character in `mu.ebnf`).

### Medium

**M1. Case twins (A).** `ROT` (rotate a line) and `rot` (stack) differ only in
case and mean unrelated things. `SFL` and `TRP` are further upper-case
shorthands. A name pair that differs only in case is a typo trap and cannot be
spoken. Fix: drop `ROT`/`SFL`/`TRP` from mu (they belong to the text
grammar).

**M2. The `-ing` family and its signs disagree (A, B).** `rotating`,
`shuffling`, `choosing`, `alternating` mean "as it plays". `[?]` is `shuffling`
(as it plays), `[|]` is `choosing`, `[\]` is `alternating`, but `[%]` is
`rotate` (now), not `rotating`. The one sign that breaks the pattern is the
one people will guess wrong.

**M3. Spellings that are the same function listed as separate words (A).**
`times` (tool), `repeat` (music) and `[*]` all do one thing, yet `repeat`'s
help says "also times, [*]" while `times`'s says "also [*]". `context-of` and
`setting` have identical help and no cross-reference. `edit`/`,` and
`store`/`R!`, `fetch`/`R@` do cross-reference. Pick a rule: either every
twin lists the others, or only one is registered.

**M4. Four ways to build a line, four to build a parallel (A).**
`[n]`, `line`, `| ... seq`, `( ... )` for a line; `{n}`, `together`,
`| ... par` for a parallel, plus `{+}`/`beside` to merge. All work, all tested.
`( )` and `| seq` overlap fully for the common case. This is the largest
doublure by usage frequency.

**M5. `chord` takes all notes on top; `line` and `together` take a count
(A, B).** `c4 e g <>` works; `c e g 3 chord` fails with "needs notes on top
of the stack". Same job (gather the top), two conventions.

**M6. Names that clash with their usual meaning (B).**
`length` is duration (`[c d e] length` is `3/4`) while `count` is elements, the
reverse of most languages. `'[1 2 3] length` gives `0`, silently, because data
has no duration. `head x n` returns n elements, where `first` returns one
element and Haskell's `head` does too. `head`/`tail` with n default 1 are
`take` and the old `drop` with a default; two more names for the same two
ideas.

**M7. Discovery has a hole (B).** `help` is `unknown word`. A misspelt word
(`retrogrde`, `insrt`) is `unknown word: ...` with no suggestion, while a
misspelt `!name` does suggest ("within two letters"). The machinery exists
(`unknown-mark!`); words do not use it. `words` prints 270 names in a block;
`see` is good.

**M8. Printing is not isomorphic for some values (A).**
`[c d e] key` prints `#musics.common.music_elements.Key{:signature {...}}`, a
Java record; `:Key setting` the same. Every other value prints as something
mu reads back.

**M9. Error wording that misleads (B).**
- `[c d e] -1 take` says "needs a whole number on top, got -1" (-1 is one;
  the rule is non-negative).
- `1 2 3 4 4 [4]` says `can't build a value from "[2 3 4 4]"` (an internal
  string; the cause is that numbers are not elements).
- `[c d e] 70 :volume with` says "needs a context ^{ ... } on top", but
  `:volume 70 with` is accepted; the message should name the accepted forms.
- `3 5 <` reports a voice problem (see H4).

**M10. One word, two stack effects, chosen by the type of the top item (A).**
`head`, `tail`, `max`, `min`, `stop`, `R!`, `pulses`, `walking-bass`, `with` all
have an optional argument decided by an `:optional` test. The cost: a number
left on top by an earlier step is silently taken as the optional argument. The
`( x [n] -- x' )` display tells the user, but the rule ("a number on top is
n") is not visible at the point of use.

**M11. Generic names in the default namespaces (B).** `rate` is in `:use` by
default and holds `next`, `prev`, `step`, `again`, `gap`, `ask`, `taste`.
Plain words like these are what a user wants for their own `: ... ;`
definitions; the leaf-name guard already refuses `f` and `c`, so a clash with
a rating word is an error later, not a design the user chose.

### Low

- Stack effects for the algo words are generated and uniform; no consistency
  findings from sampling (`pitch/lorenz`, `duration/euclid`, `volume/walk`).
- `each` (spreads results on the stack) and `map` (gathers a stream) differ
  by design but are named as near neighbours; `reduce` and `sum` overlap for
  numbers only.
- `<=`, `=`, `>=` are registered as "not a word" stubs; a fourth category of
  word (neither a word nor an error from the parser).

## Complexity

Concepts a user must hold at the prompt: the stack; quotations `'( )`;
groups `( )`; marks `|`; three bracket families (`[ ]` `{ }` `^{ }`) plus the
count forms `[n]` `{n}`; literals that become values (`/4`, `M:3/4`, `$[047]`,
`D.major`, `@( )`, `@^{ }`); the value-versus-literal split of notes (`c` is a
note, never a word); optional arguments by type; the singular/plural reader
pair (`pitch`/`pitches`); the `x'` convention; "above a `|`" rules for `zip`,
`seq`, `par`, `>ctx`. Each is individually sensible; together they are about
fifteen distinct rules. The mark-and-gather family (`|`, `()`, `[n]`, `{n}`) is
where the rules overlap most (M4).

The two newest additions, a duration alone (`/4`) and a setting alone
(`M:3/4`), each add a literal kind. Both are compact and worked in every probe
(`c M:3/4 with`, `[c d e] M:3/4 with`); their cost is the grammar's ordered
choice (`KeyLit` and `AssignLit` come after `Leaf`), which is why `c:m7` stays
a chord. Watch for the next name that collides.

## Expressiveness

Tasks tried at the REPL, all without a Clojure fallback:

| Task | Result |
|---|---|
| palindrome | `[c d e f] dup [R] [+]`: yes |
| canon at a half note | `[c d e f] dup {2} 1/2 delays`: yes |
| octave of the notes above d | `'( dup pitch 62 gt '( 12 + ) '( ) if ) map`: works, long |
| every second note | `2 partition` gives pairs; no direct "every n-th" or index-aware `map` |
| crescendo | `90 cresc`: yes |
| swing | `swing:0.6 with`: yes |
| shift a stream's pitches | `'[60 62] 12 add` refused (H3) |
| user word with a `x'` effect | refused (H5) |
| compare numbers | names only (H4) |

Gaps: index-aware iteration, stream arithmetic, `last`-side counterparts
(`init`, `tail-last`).

## Compactness

Keystrokes follow a clear pattern: music operations are very short
(`7 +`, `2 *`, `dup [R] [+]`, `1 g !`), numbers take three-letter names
(`add`, `lt`), and everything rare is long (`restore-session`,
`retrograde-inversion`). That split is deliberate and mostly good: the common
edit loop at the prompt is short.

Where compactness is bought with doublures: 45 alias groups. Where it is
missing: the broadcast over streams (`'( 12 add ) map` is 14 characters for
what `12 +` does on a line), the "make a line from n things" idiom (four
spellings, none shorter than `[n]`), and reading a setting (`:volume setting`
is 16 characters; `volume` works on a note but refuses a line).

Bracket signs (`[R]`, `[I]`, `[T]`, `[||]`) are short to read but cost three
keystrokes each on most layouts (two shifted brackets plus the letter), so
`[R]` is not much shorter to type than `retrograde` with Tab. Tab completion
(`musics.mu.complete`) was not measured here.

## Where A and B pull apart

| Question | A says | B says |
|---|---|---|
| `+` on numbers | one word, one meaning: `add` | the first guess must work |
| `<` `>` `=` | spend them where they are most used (play) | they mean comparison everywhere else |
| signs against names | one spelling per idea | `[R]` is quicker than `retrograde`, and `[R]` is musics text |
| errors empty the stack | Forth ABORT | a typo must not cost the piece |
| optional arguments by type | fewer words | the rule is invisible |
| `drop` rename | Forth name | silent on old input |

Recommended tie-breaker: use A to decide what a word means and B to decide how
it is spelled and how it fails.

## Suggested order of work

1. H1 (stack kept on error) and H5 (`'` in effects): small, no design
   decision.
2. H2 (`drop` guard) and H3 (data arithmetic): decide refuse versus broadcast.
3. M7 (`help`, did-you-mean for words): the machinery exists for marks.
4. M1, M2, M3: small renames or removals, but breaking, so batch them.
5. H4 (type-dispatched `+ - * /`) and M4 (collapsing line builders): design
   discussions, not edits.

## Open questions for the author

- Is a failing input meant to cost the stack (H1), or is that only the
  Forth heritage?
- Should the signs `+ - * /` dispatch on type, or stay musical-only (H4)?
- Is the third and fourth way to build a line (`( )` and `| seq`) kept for a
  reason, or did they arrive one at a time (M4)?
- Are `ROT`/`SFL`/`TRP` wanted in mu at all (M1)?
