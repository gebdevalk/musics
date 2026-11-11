# Editor support for `.mus` and mu input

`mus/` is a TextMate grammar (`mus.tmLanguage.json`) with a VS Code language
configuration (`language-configuration.json`, `package.json`): comments `//` and
`/* */`, brackets, strings, notes with their durations, rests, drums, ids
(`verse:`, `:verse`, `:bass/onsets`), instructions (`!vol:50`), settings (`M:3/4`),
`\name` references, `$` locals, pitch-class sets, quotations and data lists
(`'( … )`, `'[ … ]`), the definition `: word ( stack effect ) … ;`, the play signs
(`->`, `-|>`), `T!` `T@` `T_` `R!` `R@` `R_`, and `ns/word`.

## IntelliJ (also Cursive)

Settings, Editor, TextMate Bundles, `+`, and choose the folder `editor/mus`. `.mus`
files are then highlighted; Code, Comment with Line Comment writes `//` once the
language configuration is read (IntelliJ 2023 and later).

## VS Code

Copy or link the folder `editor/mus` into `~/.vscode/extensions/` (the name is free,
for example `musics.mus`), then restart. Or run `code --extensionDevelopmentPath=editor/mus`.

## What it does not do

It colours by shape, not by meaning: a bare letter `a` .. `g` is a note, as in the
grammar (a leaf wins over a word), so a word of one such letter is coloured as one. The
patterns were checked on the repo's own `.mus` files with a small script that applies
them in order; they have not been loaded into an editor yet.
