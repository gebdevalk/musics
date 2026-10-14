# Feedback: rating what you hear

You rate what you hear (like, neutral, dislike) while you work in mu,
and the ratings are kept so they can be correlated with the musical
material. It starts with **sounds**: one instrument, a layer of
instruments, a drum or a layered drum. It extends to all of musics once
the material's dimensions are settled.

## Built: sounds

### A trial

A trial is one thing heard and rated. It is stored as:

| Field | Holds |
|---|---|
| `:what` | the sound as mu prints it -- `C4/4\instrument:40,73`, `x8\36,39` -- readable, and played again by reading it |
| `:synth` | `:gervill` or `:fluidsynth` |
| `:soundfont` | Gervill's soundbank name; nil through Fluidsynth (qsynth's is not known) |
| `:score` | 1 like, 0 neutral, -1 dislike |
| `:where` | `:audition` or `:repl` |
| `:at` | when |

The printed text is the shorthand: it reads back exactly, so nothing
is lost and a later analysis can derive anything from it.

### The ratings file

`~/.mu-feedback.edn`, one map per line, only ever appended. A second
rating of the same sound is another line, so a mean shows taste
changing over time. The path is an atom (`musics.feedback/file`); tests
point it at a scratch file.

### Rating

| Key | Word | Does |
|---|---|---|
| Alt+. | `like` | rate the current trial 1 |
| Alt+/ | `meh` | rate it 0 |
| Alt+, | `dislike` | rate it -1 |
| Alt+? | `again` | play it again |
| Alt+> | `next` | the next item of an audition |
| Alt+< | `back` | the previous item |

A key works mid-typing: it leaves the line alone and confirms above the
prompt, as `echo` prints. The keys are three neighbours, unshifted to rate
(`,` dislike, `/` meh, `.` like) and shifted to move (`<` back, `?`
again, `>` next). They need Alt because these characters are part of
what you type (`>` plays, `.s` prints, `[/]` parts).

The current trial is the audition's current item, else what was last
played with `>`/`play` when that is a single note, chord or drum.

### Auditions and combinations

- `audition` ( line -- ) plays the line's items one by one, in the
  background, so the prompt stays free. In auto mode each item plays,
  then a short gap, then the next. A rating moves on at once.
- `step` switches between auto and step mode. In step mode an item
  waits for a rating or `next`.
- `[]` stops an audition.
- `combos` ( leaf numbers k -- line ) gives every k-instrument layer of
  the numbers: `c4 '[40 41 71 73] 2 combos audition` plays every pair,
  and on a drum it layers keys.
- `ask` toggles a prompt to rate when what was played ends, as `echo`
  toggles printing.

### Reading your taste

- `taste` ( -- ) prints two tables, each with its mean and count:
  - per sound;
  - per instrument or drum key, where every layer it is part of counts
    for it.
- `liked` and `disliked` ( -- line ) give back the sounds whose mean is
  above or below zero, to play or audition again.

## Planned

- **Signatures.** A machine-readable bitmap per dimension of the
  material, for every trial, rather than the features read from the
  text. They give fast similarity (AND and a bit count), per-bit tallies
  of what you like, and mask searches. A record keeps a layout version,
  so signatures are recomputed from `:what` when the layout changes.
  This waits for the material's dimensions, worked out separately from
  `doc/algo-space.md`, whose D1-D8 describe algos rather than material.
- **All of musics.** Notes, lines and pieces rated as trials, with one
  signature per bar and one for the whole. How the material was made
  (which algos) could become a dimension too.
- **Rules (miniKanren).** The ratings feed a rule-based system in
  core.logic (`musics.feedback.logic`), as `musics.assist` uses it.
  - **Facts:** each time the ratings file changes, it is read into a
    `pldb` database:
    - `(sound s)` -- a rated sound;
    - `(part s p)` -- `p` an instrument `[:instrument 40]` or a drum
      `[:drum 36]`;
    - `(family p f)` -- the GM family (`program / 8`: piano, organ,
      strings, brass, reed, …) or a drum group (kick, snare, hat, tom,
      cymbal, hand percussion);
    - `(verdict s v)` and `(verdict p v)` -- `:likes` (mean > 0.3),
      `:dislikes` (< -0.3), else `:mixed`;
    - `(heard-in s synth soundfont)`.

    Means are worked out in Clojure before the facts are asserted;
    the logic sees only the verdicts.
  - **Rules:** named goals, each able to explain itself:

    | Rule | Says |
    |---|---|
    | `liked-layer` | an untried layer of parts that are each liked, none disliked |
    | `liked-family` | an untried instrument from a family with two or more liked members and none disliked |
    | `avoid` | a sound containing a disliked part |
    | `clash` | two parts liked alone whose layer was disliked: not together again |
    | `retry` | a mixed sound, worth hearing again |

    `suggesto` runs them and gives untried sounds, each with the rule
    that gave it; `whyo` gives the facts behind one.
  - **In mu:**
    - `suggest` ( leaf n -- line ) gives n untried sounds on the leaf,
      so `c4 8 suggest audition` closes the loop of hear, rate, suggest,
      hear;
    - `why` ( leaf -- ) gives the rule and facts behind a sound, or why
      it is avoided;
    - `rules` lists the rules with how many sounds each gives now.

    Your own rules are goals registered with `defrule` at the Clojure
    REPL.
  - **Later:** signature bits become facts too (register, interval, …),
    with rules over them.
- **Steering.** The ratings weight generation, so that `+instrument`
  picks liked programs and `surprise` favours trees whose output scores
  well, each taking only what `suggesto` or `avoid` allow.
