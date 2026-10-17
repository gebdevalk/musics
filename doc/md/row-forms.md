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

Grammar questions:

1. `{ [c d e f] [R] }` is today a part resting a bar -- common in
   scores. With the `[R]` exception it becomes the first part's
   retrograde. Accept, or another spelling for R in text?
2. `transpose` and `reverse` now reach into nested lines, references
   (as copies) and repeats (`builder/deep-children!`, 2026-10-17); a
   row-form node would be one more thing for that walk to reach.
3. `reverse ( … )` already exists in text: keep both, or drop it?
4. Dynamics and ramps: move with the notes (a crescendo becomes a
   decrescendo, `env-reverse`) or stay in time?
5. Relative pitch after `[c d e f] [RI]`: from the last written note
   (proposed) or the last sounding one?
6. A word that looks back is new in text (all else is prefix): `[I]` at
   a line's start, after a bar line or an instruction needs a rule.
7. LilyPond's `\retrograde`/`\inversion` are not imported; any grammar
   change needs a pass over `lilypond_import` and a corpus reparse.
8. The plain words (`reverse`, `invert`) should line up with the new
   names.

## Other transforms as text, later

Built (2026-10-17) as `shuffle [ … ]` in the text, made by mu's `[?]`:
`repeat ~ [ shuffle [c d e] ]` shuffles every pass. `[%]` rotate could
follow the same way. `[[]]`, `[/]`, `[|]` reshape a line into several and
stay mu words.
