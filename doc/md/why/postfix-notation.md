# Design note: one notation, postfix (option B)

Status: proposal, nothing built. Written 2026-11-25 on branch `postfix`, after the audit of ways to
do the same thing and the discussion of the prefix/postfix mix. Counts are from the source and the
test and doc text of that day, by grep: approximate.

## 1. The problem

mu's words are postfix: `[c d] 2 times`, `[c d e] [?]`, `[c e g] 5 transpose`. The notation inside
a literal has **prefix commands**: `repeat 2 [c d] volta [[e] [f]]`, `shuffle [c d e]`,
`transpose 5 ( c e g )`. One operation, two spellings, two orders of reading. A value even prints in
the prefix form: `[c d e] [?]` shows `shuffle [C4/4 D4 E4]`.

The aggregate literals themselves (`[ ]`, `{ }`, `( )`, `^{ }`, `'[ ]`) are not the problem: Factor,
Joy and PostScript all have them. The commands are.

This note covers **option B**: remove the prefix commands from the notation, keep the bracket
literals, ids, instructions and bar lines. Option C (only leaves are literals, brackets become words)
is not designed here; B is its first half and leaves it decidable (section 9).

## 2. The commands

One row for each command of `musics.ebnf` (`<Command>`), what it makes, what prints it, and what
mu has to say it postfix.

| Prefix form | Makes | Prints today as | Postfix word today | Verdict |
|---|---|---|---|---|
| `transpose 5 ( … )`, `transpose c d ( … )` | nothing: the notes are moved while reading and spliced in | the moved notes | `transpose`, `+` | **delete** |
| `retrograde ( … )`, `inversion ( … )`, `mirror ( … )`, `retrograde-inversion ( … )`, `R`/`I`/`M`/`RI` (+ semitones) | nothing, as `transpose` | the changed notes | `[R]` `retrograde`, `[I]`, `[M]`, `[RI]` | **delete** |
| `shuffle ( … )` | nothing: shuffled once, now | the shuffled notes | `shuffle` | **delete** |
| `rotate n ( … )` | nothing: rotated once, now | the rotated notes | `rotate` | **delete** |
| `repeat n [ … ]` | an Iterator, `:REPEAT` | `repeat 2 [C4/4 D4]` | `times` (`[*]`) | **convert** |
| `… volta [ … ]` | the Iterator's `:alternative` | `repeat 2 [ … ] volta [[E4] [F4]]` | `volta` | **convert** |
| `repeat ~ [ … ]` | an Iterator, count `:infinite` | `repeat ~ [ … ]` | `cycle` (`[~]`) | **convert** |
| `repeat tremolo n [ … ]` | an Iterator, `:TREMOLO` | `repeat tremolo 8 [ … ]` | none | **convert**, new word |
| `shuffle [ … ]` | a seq marked `:shuffle` | `shuffle [C4/4 D4 E4]` | `shuffling` (`[?]`) | **convert** |
| `rotate n [ … ]` | a seq marked `:rotate n` | `rotate 1 [ … ]` | `rotating` (`[%]`) | **convert** |
| `choose [ … ]`, `choose w,w [ … ]` | a seq marked `:choose`, with `:weights` | `choose 3,1 [ … ]` | `choosing` (`[\|]`), weights as data on top: `[[c d] [e f]] '[3 1] [\|]` | **convert** |
| `alternate [ … ]` | a seq marked `:alternate` | `alternate [ … ]` | `alternating` (`[\]`) | **convert** |
| `grace g m`, `acciaccatura`, `appoggiatura`, `slashedGrace` (grace first), `afterGrace m g` | a decorated container: the main note's length shortened, the grace note a tag | two plain notes, `[C4/16 D4/8.]` (the kind of grace is not written back) | none | **convert**, new words |
| `n:m( … )` | notes with a ratio factor (`c8*2/3`), spliced | `3:2(C4/8 D4 E4)` | `*` with a ratio factor, `2/3 times` | **keep, decide later** (section 5) |

Two groups. The first four rows are **transient**: they act while the text is read and leave nothing,
so they cannot be printed back; the postfix word already does the same job, and nothing stands in the
way of deleting them. The rest are **stored node types** that the printer must be able to write and
a reader read back.

## 3. What shrinks, what it costs

**Shrinks.** In `musics.ebnf`: the rules `transpose`, `row-form`, `shuffle`, `shuffle-now`, `rotate`,
`rotate-now`, `choose`, `alternate`, `repeat`, `alternative` and `grace`, and `<Command>` but for the
tuplet; `Scope` stays while the tuplet does (it is its body); in `mu.ebnf` `TopCommand` and its refusals; the reserved words in `VarName`. In
`walker.clj` the handlers `walk-transpose` (20 lines), `walk-row-form` (24), `walk-shuffle` (13),
`walk-shuffle-now` (8), `walk-rotate` (12), `walk-rotate-now` (8), `walk-choose` (17),
`walk-alternate` (5), `walk-repeat` (46), `walk-grace` (24) and their dispatch: about 180 lines of
a 1,860-line walker. Not much code; the gain is one spelling.

**Costs.**

| What | Uses of a prefix command |
|---|---|
| tests | repeat 133, volta 57, transpose 51, choose 24, shuffle 19, rotate 17, alternate 3, grace 36, row forms 21 |
| docs | repeat 50, volta 30, transpose 14, grace 10, row forms 7, shuffle/rotate/choose/alternate 14 |
| `mus/`, `corpus/` | about 30 lines (tuplets included) |
| the three importers | repeat 15, volta 6, grace 19, transpose 3 (and 40 for the `n:m(` tuplet) |

The importers write notation text; they would write mu code (postfix) instead, which is mu input
all the same.

**The one real obstacle.** Printing a value as postfix text is easy; **reading it back** needs the
evaluator, because `[C4/4 D4] 2 times` is a word acting on a seq. Today `read-text` is notation
only, and 44 places in `src` use `read-text`, `read-form`, `read-material` or `read-domain`
(`material.clj` 19, `music.clj` 7, `tool.clj` 4, `tools.clj` 2, `live.clj` 2, and one each in seven
other files). They build a value by writing text and reading it. With prefix commands gone from the
reader, those places can no longer build a repeat, a shuffle or a rotate that way.

## 4. The printed form after B

A stored value prints as the postfix text that makes it:

```
[C4/4 D4] 2 times                          was  repeat 2 [C4/4 D4]
[C4/4 D4] 2 times [E4] [F4] volta          was  repeat 2 [C4/4 D4] volta [[E4] [F4]]
[C4/4 D4 E4] [?]                           was  shuffle [C4/4 D4 E4]
[C4/4 D4 E4] 1 [%]                         was  rotate 1 [C4/4 D4 E4]
[C4/4 D4] 3 1 weights [|]  (to settle)     was  choose 3,1 [C4/4 D4]
```

Reading it back is a mu input: the evaluator runs the words and the stack holds the value. That is
how a `.mus` file already reads. The internal readers need a different route:

1. **Native constructors** for the node types (a seq, a parallel, a repeat, a shuffle, a rotate, a
   choose, an alternate): functions that build the value from its parts, with no text. This is what
   `mt/line`, `par-of`, `rebuild`, `read-repeat` do by printing and re-reading. It is the largest
   piece of work, and useful on its own (the walker's cost per leaf, which caps `perms` at 6, comes
   from this round trip; not measured).
2. **A closed evaluation for stored text** (a `.mus` file, `edit`, a session): run through the
   evaluator. Nothing new.

Rejected: a pure evaluator that only runs the form words, for internal reading. Native
constructors avoid text altogether.

## 5. Two commands that are not commands

- **Tuplet `n:m( … )`.** It is a **group with a ratio**, like `( … )` and `^{ }`, not a command
  word: a count and a colon directly before the bracket, and it prints compactly
  (`3:2(C4/8 D4 E4)`). The importers write it 40 times. Proposal: keep it as a literal for now.
  Postfix would be `( c8 d e ) 3 2 tuplet`, longer and no clearer. Revisit with option C.
- **`( … )` as a grouping** inside a seq is already a literal, not a command, and stays.

## 6. New words

| Word | Does | Example |
|---|---|---|
| `tremolo` | a measured tremolo (an Iterator `:TREMOLO`) | `[c16 d] 8 tremolo` |
| `grace` | the grace note on the left, the main note on the right | `c16 d4 grace` |
| `afterGrace` | the main note on the left, the grace note on the right | `d4 c16 afterGrace` |
| `acciaccatura`, `appoggiatura`, `slashedGrace` | the other kinds | `c16 d4 acciaccatura` |

The kind of a grace note is not written back today (`[grace c16 d4]` prints `[C4/16 D4/8.]`); a
postfix word could print `c16 d4 grace` if the kind is kept on the note. Settle with the leaf's tag.

## 7. Order of work

1. **Native constructors** (section 4, item 1). Behaviour unchanged. Each step ends with the test
   suite green; the printed forms do not change yet.
2. **The missing words** (section 6), with the prefix forms still accepted. Both ways work.
3. **Convert tests, docs, `mus/`, `corpus/`, the importers** to postfix. Both ways still work.
4. **Switch the printer** to postfix.
5. **Delete** the prefix rules, the walker handlers, `TopCommand` and the reserved
   words, with a hint for the old spelling (as for `M:3/4`: `repeat 2 [ … ] is now written
   [ … ] 2 times`).

Each step is a commit on this branch; none changes what plays.

## 7a. Progress (2026-11-25)

Step 1 is done on branch `postfix`, in seven commits, each with the suite green (`lein verify`), and
the text route kept beside each native one, `line-by-text`, `par-by-text`, `read-repeat-by-text`,
so that `test/musics/mu/native_test.clj` can compare them: the printed text, the duration, the
pitch statistics, each leaf's fields and its context chain.

| Step | Built without text | Falls back to text for |
|---|---|---|
| 1 | `line` of leaves and marks | anything else |
| 2 | `par-of` of leaves and plain seqs and parallels | an instruction, a repeat, a group, a name, a stored part |
| 3 | `line` of plain seqs and parallels too | the same |
| 4 | a repeat (`times`, `volta`, `cycle`, tremolo) with a plain body and endings | the same |
| 5 | `shuffling`, `rotating`, `choosing` (with weights), `alternating` | the same |
| 6 | `rebuild`, `from-items` of a plain seq | instructions, a parallel, a group |
| 7 | a group `( … )` of leaves, inside `line`; `perms` | the same |

Two things the text route does that a native build has to copy: it **normalizes a leaf** (a length
that is no note value comes back with its factor as `:ratio`, which is what `n:m(` prints from), and
it gives each leaf the **chain of contexts** of the containers it was read inside. A part of a
parallel is read as a literal of its own, so the parallel's context is in none of its leaves'
chains; an element of a seq is read inside it, so the seq's is.

Measured: `line` of 256 leaves 216 ms -> 1.9 ms; `par-of` of two 8-leaf seqs 16 ms -> 0.4 ms;
`[c d e f g] perms` 0.55 s -> 0.05 s, six elements 15.7 s -> 0.11 s (the cap of 6 on `perms` was the
cost of the text route, not of the orders; not changed).

Not done, still through text: data lists `'[ ]` (`data`), `hairpin`, `with` and any value holding an
instruction, a stored part, a name, a repeat inside a seq, a parallel rebuilt from its parts.

## 7b. The words added (step 2)

`tremolo` (`[c16 d] 8 tremolo`, the text's `repeat tremolo 8 [c16 d]`) and the grace words, the
grace note on the left and the main note on the right: `c16 d4 grace`, `acciaccatura`,
`appoggiatura`, `slashedGrace`, and `d4 c16 afterGrace` (the main note first, as the text writes
it). Each gives what the text form gives. **Until the prefix rules are deleted** a `grace` word
followed by two notes (`c16 d4 grace e f`) is read as the text's command `grace e f`; written at
the end of an input, or followed by a word, it is the word. The same holds for `afterGrace`.

## 7c. A problem found while building: a repeat inside a seq

Section 4 says a stored value prints as the postfix text that makes it, `[C4/4 D4] 2 times`, and
reads back through the evaluator. That holds for a repeat **on its own**. A repeat that is **an
element of a seq** has no postfix spelling in the notation: `[c4 [d e] 2 times f]` is a word inside
brackets, which the notation does not read (option D's code brackets would, option C's `[` and `]`
as words would). The same goes for a shuffled, rotated, chosen or alternating seq inside another
seq, and for a repeat with endings. The printer has to write such a value somehow:

1. **As code, outside the notation:** `c4 [d e] 2 times f [3]`, a seq built by words. The printed form
   of any seq that holds one of these is then code, and reading it needs the evaluator, so every place
   that prints a value and reads it back (the text route, `edit`, a saved session) must evaluate: B
   becomes C for printing.
2. **Keep the prefix spelling as the printed form** of a repeat or a shuffled seq inside a seq, and
   remove only the authoring of it (the words are the way to make one). Reading the printed text still
   needs the prefix rules; the notation keeps them as a stored form.
3. **Code brackets** (option D): `[c4 [d e] 2 times f]` read as code.

Until this is settled the printer is **not switched** (step 4 of section 7) and the prefix rules are
**not deleted** (step 5). Steps 1 and 2 are done: they stand either way (a faster `perms` and the words),
and the native constructors are what any of the three needs.

## 8. Open questions

1. **Weights for `choosing`: nothing to add.** The word takes the weights as data on top
   (`'[3 1] [|]`); the first draft of this note missed it.
2. **Grace words.** Five words, or `grace` with a kind (`c16 d4 :slashed grace`)?
3. **Tremolo.** `tremolo` as a word, or a count on `times` (`[c16 d] 8 times` is a repeat;
   a tremolo is a repeat in a time)?
4. **Where a postfix word sits at the end of a line.** `[c d] 2 times [e] [f] volta` needs `volta` to
   find the repeat below its two endings; this is how it works today.
5. **`shuffle`, `rotate` the words, and `shuffling`, `rotating`.** The first act now, the second
   as it plays. The names are alike; the audit left the aliases for later.
6. **Tuplet** (section 5).

## 9. Option C afterwards

After B the notation holds only data: brackets, ids, instructions, bar lines, leaves. Option C
(`[` pushes a mark, `]` gathers) then has the native constructors it needs, the printed form of
every value is postfix code, and the decision can be taken on what the output reads like, not on
a guess. What C still needs settled: how an id is written (`verse:`), how an instruction becomes an
element, and how `|` is both a bar line and a stack mark.

## 10. Rejected

- **Code brackets** (`[ … ]` holding words, branch `code-brackets`): a fifth way to gather,
  and it gives up reading the text before anything runs.
- **A `${ … }` escape** inside a literal: a new sign, and every computed part wrapped.
- **Keeping the prefix forms as the printed form** (postfix only on input): the confusion stays in
  every printed value.
