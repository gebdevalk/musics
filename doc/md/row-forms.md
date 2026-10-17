# Row forms in mu and the grammar (proposal, parked)

A proposal, not built. Discussed 2026-10-15; parked to return to mu
first. Branch: `grammar`.

## The idea

mu's vocabulary is succinct; some of it could be used in `.mus` text
too. The candidates are the transforms of fixed meaning, not the stack,
quotations, selection or session words (`dup`, `'( )`, `filter`, `R!`)
-- programming in text was removed on purpose (`@[ ]`, decisions.md).
The first to take over: the serial row forms.

## Row forms

| Letter | Name | Does |
|---|---|---|
| P | prime | the line as written |
| R | retrograde | backwards |
| I | inversion | every interval mirrored around the first note |
| RI | retrograde inversion | the inversion, backwards |

German: G, K (Krebs), U (Umkehrung), KU. Latin (canon): rectus,
inversus, cancrizans.

- With transposition, IR adds nothing: it is RI transposed by twice the
  first-to-last interval. Hence 48 forms (4 x 12), not 60.
- The inversion axis (first note or mean) adds nothing either: the two
  differ by a transposition. Around the mean R and I commute.
- Not modulo 12: with octaves, +12 is another line.

## Decided

- Words `[R]` `[I]` `[RI]`, standing apart (not glued to the bracket),
  in mu and in text alike, acting on what comes before them:
  `[c d e f] [RI]`, `:row [I]`.
- No number in the word. Transposition stays as it is: mu `x [I] 5 +`
  is I5; text `transpose`. `[P]` is not needed.
- `[R]` exactly is the word; `[ R ]` (spaces) is a line with a bar's
  rest, as are `[R1]`, `[R*2]`, `[r1]`. mu never prints `[R]`. `[C]` is
  a note, so not C for crab; `[K]`/`[U]` are free but were not chosen.
- mu's `[<]` `[^]` `[^<]` `[<^]` are dropped. `[-]` (inversion around
  the mean, keeping the register) stays.
- Kept as a node, not applied while parsing: something like
  `{:type :FORM :form :RI :source ...}` among the children, as `[: :]`
  is an Iterator; `musics.events`/`display-timed` realize it, its
  duration is the source's, the printers write `<source> [RI]` back. In
  mu `x [RI]` gives the node; `[!]` writes it out.

## Open before building

The four bugs found in mu's crab and inversion (2026-10-15) are fixed
(2026-10-17): a tie turns round with its notes; what a line's own
instructions change is written into the notes as overrides, so a
crescendo plays as a diminuendo; a natural the key would alter prints
with `n` (`Cn4` in D major), so an inversion keeps it; a line with jumps
is refused by `[R]`. `[R]` also reverses nested lines, parallels' parts
and repeats' bodies, as the text's `reverse` does.

Settled and built (2026-10-17, "mu and the text, one vocabulary" in
doc/md/decisions.md): the text writes the forms as commands with a body,
not as words after a part -- `retrograde`/`R`, `inversion`/`I`,
`retrograde-inversion`/`RI`, `mirror`/`M` ( … ), an optional number
transposing the result (`I 5 ( … )`); `reverse` became `retrograde`;
`R` alone stays a bar's rest. So the `{ [c] [R] }` trap and a word that
looks back never arise; dynamics and ties turn round with the notes;
the next relative note follows the last written one. Still open:
importing LilyPond's `\retrograde` and `\inversion`.

## Other transforms as text, later

Built (2026-10-17): `shuffle [ … ]` and `rotate n [ … ]`, made by mu's
`[?]`/`shuffling` and `rotating`, change each time they play;
`shuffle ( … )` and `rotate n ( … )` once, now. `[[]]`, `[/]`, `[|]` reshape a line into several and
stay mu words.
