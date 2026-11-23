# Cheat sheet: the signs of mu

`words` lists every word, `"x" see` says what one takes and gives, Tab completes.

## Writing

| Text | Is |
|---|---|
| `[a: c d e]` | a seq named `a`, notes one after another |
| `[a: c ( d e ) f]` | a group `( )` in a seq: one element, no id, its own instructions; order words move it whole, pitch words reach inside |
| `{a: [x: ..] [y: ..]}` | parts at the same time |
| `^{ !mf }` | a context: settings |
| `'[60 62 64]` | a data list (numbers); `'[C D E]` pitch classes |
| `!mf` `!tempo:120` `!key:D.major` | instructions: inside a seq or a context, or alone, where one is a context holding it (`!mf` is `^{ !mf }`) |
| `c4 d8 e16.` | duration digit, dot; lowercase pitch is relative, `C5/4` absolute |
| `// …` `/* … */` | comments |
| `\name` `:name` | a bound value; a stored id |

## The stack

| Word | Does |
|---|---|
| `.s` `.` `clear` `depth` | show the stack, print and drop the top, empty it, how deep it is |
| `dup` `drop` `swap` `over` `rot` `-rot` `nip` `tuck` | copy, discard, reorder the top ones |
| `2dup` `2drop` `pick` `roll` | two at once; reach n down: copy, move |
| `1+` `1-` `spread` | add or take one; `spread` (`**`) puts a seq's notes, a parallel's parts or a list's values on the stack |
| `if` `when` `each` `reduce` `loop` `+loop` `execute` | choose, repeat or run a quotation (`i` `j` `k` the loop counters) |
| `'name value :=` | bind a name for the session |
| `: name ( $a -- b ) … ;` | define a word |
| `'( … )` | a quotation |

## Keeping and looking

| Word | Does |
|---|---|
| `R!` `R!!` `R@` `R_` | store, store over a name (the name is dropped), fetch, forget (in the repo) |
| `rename` `forget` | rename an id wherever it is held; delete one nothing refers to |
| `ids` `usages` `ctx` | what is stored, who uses it, its contexts |
| `T!` `T@` `T@@` `T_` | store, fetch, fetch and run, erase a setting in the tctx (key a keyword); `T!` with a quotation `'( … )` stores a setting computed from others, `T@` gives the quotation, `T@@` its value; inside a voice a keyword argument of a word (`:steps 2 *`) is the setting's value |
| `@{ :onsets 3 :half '( :onsets 2 / ) }` `:tctx R!` `:tctx R@` | write a whole tctx (keyword keys; a quotation is a computed key), make it the session's, read it back |
| `load` `render` `save-session` `restore-session` | files |

## Playing

| Word | Does |
|---|---|
| `->` `->+` | play now (`play`: replacing what plays; `play+` `->+`: joining it) |
| `x -\|>` | replace what plays at its end (or its repeat's current pass) |
| `x \| -\|>` … `x \|\|\|\| -\|>` | at the next bar line of that strength: `\|` `\|\|` `\|\|\|` `\|\|\|\|` |
| `x -\|>+` | the same, but joining: what plays goes on, a second part enters |
| `[bass: g a] -\|>` | named music: replaces only voice `:bass`, at the end of its own pass |
| `x :bass -\|>` `x 1 -\|>` | as the voice with that id or index; alone, `:bass -\|>` restarts its own music |
| `:bass <- 5 + -\|>` | fetch what a voice plays, change it, put it back at the boundary |
| `->` `->+` `stop` | forget a staged `-\|>` (one waits at a time for each voice it replaces) |
| `x :bass ->` `x ^{ !mf } ->` | a voice's id on top: the voice to play as; a context on top: played under it |
| `[~]` | loop |
| `stop` `:bass stop` | stop all, or one voice |
| `pause` `resume` | pause (again: resume), Alt+. and Alt+, |
| `1/8 later` | before a play word: start delay of a voice that is a function of onset |
| `x '( 5 + ) :up ->` `'( … ) :up retree` | a live voice follows a quotation; `retree` gives it another |
| `5 8 'riff :bass ->` | a word with `$` inputs plays live; `:bass/onsets @`, `3 :bass/onsets !`, `:bass settings`, `:bass gui` |
| `voices` (`<<-`) `playing` (`<-`) `hush` `solo` | which voices play, fetch what a voice plays, quiet one, only one |
| `gervill` `fluidsynth` `gui` `echo` | sound, window, print each note as it sounds |
| `soundfonts` `soundfont` `try-soundfonts` | list, choose, audition `.sf2` instruments |

## Changing music (a sign acts on each note; in brackets on the whole seq)

| Sign | Does |
|---|---|
| `+` `-` | transpose |
| `[R]` `[I]` `[RI]` `[M]` | retrograde, inversion, both, mirror |
| `[*]` `times` | n times |
| `[+]` `concat` | join |
| `[2]` `[3]` / `{2}` `{3}` | top n one after another / side by side |
| `^{2}` `^{3}` | top n contexts in one: `^{ !mf } ^{ !tempo:90 } ^{2}` is `^{ !mf !tempo:90 }` |
| `[?]` `[%]` | shuffle, rotate as it plays |
| `shuffle` `rotate` `perms` | shuffle, rotate now; `perms` every order of 2 to 6 elements, music as a seq of groups, a list as a list of lists |
| `par` `chord` `zip` `>ctx` `with` `delay` `vary` | assemble: parts at once, notes from streams, a context from columns, one setting, a rest in front, a change each pass |
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
