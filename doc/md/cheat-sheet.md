# Cheat sheet: the signs of mu

`words` lists every word, `"x" see` says what one takes and gives, Tab completes.

## Writing

| Text | Is |
|---|---|
| `[a: c d e]` | a seq named `a`, notes one after another |
| `{a: [x: ..] [y: ..]}` | parts at the same time |
| `^{ !mf }` | a context: settings |
| `'[60 62 64]` | a data list (numbers); `'[C D E]` pitch classes |
| `!mf` `!tempo:120` `!key:D.major` | instructions, inside a seq |
| `c4 d8 e16.` | duration digit, dot; lowercase pitch is relative, `C5/4` absolute |
| `// …` `/* … */` | comments |
| `\name` `:name` | a bound value; a stored id |

## The stack

| Word | Does |
|---|---|
| `.s` `clear` `.` `drop` `dup` `swap` `over` `rot` | show, empty, drop top, copy, reorder |
| `'name value :=` | bind a name for the session |
| `: name ( $a -- b ) … ;` | define a word |
| `'( … )` | a quotation |

## Keeping and looking

| Word | Does |
|---|---|
| `R!` `R@` `R_` | store, fetch, forget (in the repo) |
| `ids` `usages` `ctx` | what is stored, who uses it, its contexts |
| `T!` `T@` `T_` | store, read, erase a setting in the tctx (key a keyword) |
| `tctx-derive` | a setting computed from others |
| `load` `render` `save-session` `restore-session` | files |

## Playing

| Word | Does |
|---|---|
| `->` `->+` | play now (replacing, or joining) |
| `-|>` `-|>+` | at the next boundary |
| `[~]` | loop |
| `stop` `pause` | Alt+. and Alt+, |
| `voices` `hush` `solo` `<-` | what plays, quiet one, only one, fetch what a voice plays |
| `gervill` `fluidsynth` `gui` | sound, window |

## Changing music (a sign acts on each note; in brackets on the whole seq)

| Sign | Does |
|---|---|
| `+` `-` | transpose |
| `[R]` `[I]` `[RI]` `[M]` | retrograde, inversion, both, mirror |
| `[*]` `times` | n times |
| `[+]` `concat` | join |
| `[2]` `[3]` / `{2}` `{3}` | top n one after another / side by side |
| `[?]` `[%]` | shuffle, rotate as it plays |
| `put` `!` `insert` `delete` `_` `cut` `[_]` `@` | edit and read by index |

## Asking

`duration` `at` `bar` `find` `length` `pitches` `durations` `pulses` `prime-form` `key-of` `chord-name` `settings`.

## Algorithms

```
'rhythmic.pattern 'p reqalgo       make its words available
3 8 p/euclid                        call one
'( … ) 12 pull                      call a sampler n times
x durations x pitches zip           put streams onto notes
```
