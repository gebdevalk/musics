# Design note: a scoped tctx, for polyphony

Status: proposal, nothing built. Written 2026-11-25 after the audit of ways to do the same thing
(`decisions.md`, 2026-11-24/25) and a question the tctx had not met: how it holds up with more
than one voice. 2026-11-26: the two risks of section 8 checked, and a way to seed random keys
chosen (section 10).

## 1. The problem

The tctx (`T!`, `T@`, `T@@`, `@{ }`) was designed and tried with one voice. With several voices:

1. **One table for all.** `:steps` is the same for the bass and the lead. A voice that needs its
   own value has no place to keep it. The only per-voice values today are the `$` inputs of a
   word voice (`:bass/onsets`), which are a second mechanism with its own words (`@`, `!`), its own
   registry (`musics.mu.inputs`) and its own literal (`:voice/name`).
2. **A change is not synchronized.** `T!` is read by each voice at its own next note, and the
   engine reads ahead of the clock, so two interlocking voices (the hoketus) can hear one change at
   different places in the music.
3. **Shared state.** A computed key that draws random numbers or keeps a walk is one state for all
   the voices that read it. Not checked: see section 8.

The audit also found that `:bass/steps` is a reference literal in `mu.ebnf` (`IdName` with a
`/name`) and that `@` and `!` mean two things (`nth` and `put`, and read and store a reference). A
scoped tctx can take both over.

## 2. What exists, and what was checked

Read in the source and tried on the context code itself (not through the engine):

- A tctx is "a Context like any other whose keys are Clojure keywords"
  (`musics.domain.context/tctx!`). A string key never finds a keyword one, nor the reverse.
- A lookup walks a **chain** of contexts, nearest first (`lookup*`), and `getter` memoizes one
  answer per key per leaf.
- A voice that is a function of the onset reads through `[tctx voice-ctx music-contexts… :ROOT]`
  (`events/walk-source`, `compose/source`). `Source` already has a slot for "a voice's own
  Context"; today `play-onset-word!` passes only `(tctx-node!)`.
- A computed key (`tctx-derive`) asks other keys through **the chain of the asker**: `(get :other)`.
- Tried: two tctx contexts in front of a shared one. With `:steps` 8 at the root, 5 in the bass
  scope and `:twice` computed at the root as `:steps 2 *`: the lead (`[lead root]`) got 8 and 16,
  the bass (`[bass root]`) 5 and 10. The order `[root bass]` gives the root the win.
- `:bass !steps:8 with` is **not** a way: it stores the string key `"steps"` in a music context,
  and a keyword `:steps` does not find it.
- The sender thread reads every voice, so reads are in sequence; `@{ } :tctx R!` is one `reset!`.

Checked 2026-11-26, at the level of `musics.events` with two `compose/source` voices read in step
(the scope in the source's `tctx` slot, the shared table in its `ctx` slot, which gives the order
`[scope shared …]`): the scopes do not leak, a change of the shared table reaches the voice that
has no value of its own and not the one that has, a computed key defined at the shared level is
computed from each asker's own chain (`:twice` 10 for the bass with `:steps 5`, 16 for the lead
with 8), and `getter` is made per leaf and per voice, so there is no shared cache. The random words
under a shared key do share state: see section 8 and section 10.

Not checked: the sender thread and its lookahead in a playing engine, `T!` landing at a musical
boundary (section 4, way 2).

## 3. The proposal

A voice's lookup chain gets a **scope**: a tctx of its own in front of the shared one.

```
[ voice-scope   shared tctx   voice's context   music's contexts …   :ROOT ]
```

Nearest wins. A voice with no value of its own reads the shared one, so a piece that never writes
a scope behaves exactly as today. The shared table stays in front of the music's contexts, as
now, so the live override keeps working.

Computed keys need no change: a key defined at the root and asked by a voice resolves what it
reads through that voice's chain.

### Words

A scope is named in the key: a **qualified keyword**, `:bass/steps`, the voice's id before the slash,
which is a keyword like any other (Clojure's own `(keyword "bass" "steps")`), so no new kind of
value is needed. The words keep their shapes:

| Word | Does |
|---|---|
| `:steps T@` | the shared value (as now) |
| `:bass/steps T@` | the bass's value, else the shared one |
| `3 :steps T!` | set the shared value (as now) |
| `3 :bass/steps T!` | set it in the bass scope |
| `:bass/steps T_` | take it out of the scope; the bass reads the shared one again |
| `'( … ) :bass/density T!` | a computed key in a scope |
| `@{ :steps 8 :bass/steps 5 :lead/steps 3 } :tctx R!` | the shared table and the scopes written out in one literal |
| `:bass settings` | the scope's values, and the shared ones it inherits, marked |

`T@@` runs a computed key under the named voice's chain (`:bass/density T@@`).

A key inside a voice's scope is still written `:steps` by the words that run in that voice:
the chain finds the voice's value first.

### The `$` inputs become scope entries

`5 8 'riff :bass ->` writes `:onsets` 5 and `:pulses` 8 into the scope of `:bass`. The word reads
`$onsets` and `$pulses` as before; a write to the scope re-runs it, as an input change does. The
ranges still come from the algo parameter of the same name (`live/param-spec`) and are kept beside
the scope entry. `:bass settings` and `:bass gui` read the scope.

Then these go: `Ref` and the `@`/`!` on references, and the registry in `musics.mu.inputs`
(147 lines), reduced to watching a scope. `@` and `!` are left to `nth` and `put`. The `/name` of
`IdName` stays, now reading as a qualified keyword, not a reference.

### Keys

A key is a keyword (letters, digits, underscore), as now. The voice's own name can be a key,
`:voice`, set by the engine in the scope, so a word shared by several voices can tell which it is
in. To settle (section 7).

## 4. Time and synchronization

A scope, like the shared tctx, holds **bare values** or functions of time. Three ways to change
what every voice hears at one musical moment, all independent of the scope:

1. **A function of the onset.** `'( :onset 4 / int 2 mod 3 5 if ) :steps T!` answers the same for
   any voice asked at the same onset. This works today; the voices' clocks differ only by `later`.
2. **A change at a boundary.** `T!` waiting for the next bar or mark of a voice, as `-|>` does
   for music, through the conductor (`schedule!`, `:bar` and `:mark`). Does not exist. A word
   such as `bar T!`, or a quotation run at the boundary, to settle.
3. **A table written in one step.** `@{ … } :tctx R!` is one `reset!`; no voice sees half of it.

The note proposes 2 as the new piece, since it is what makes a change musical rather than a wall
clock event. It is separate from scopes and can be built after them.

## 5. Lifetime

A scope is kept **by name, in the session**, like a tctx entry: `3 :bass :steps T!` before the
voice starts is allowed, and a stopped voice keeps its values, so `:bass ->` starts it as it was
tuned. Today `$` inputs seem to live only while the voice plays (the tutorial shows `:bass/onsets @` refused
after `stop`); this is a change, in the direction of
what one does when tuning a voice. A scope is dropped with its last entry (`T_`), or all at once
(a word to name, `:bass clear`). The session file keeps scopes the way it keeps the tctx.

## 6. What changes in the code

| Where | Change |
|---|---|
| `compose/source`, `events/walk-source` | `tctx` becomes a vector of scopes, nearest first |
| `live/play-onset-word!`, `play-plain-word!` | start the voice with its scope in the chain |
| `kernel` `T!` `T@` `T@@` `T_` | an optional voice id before the key |
| `mu.ebnf`, `musics.mu.eval` | `:voice/name` reads as a qualified keyword, not a `Ref` |
| `musics.mu.inputs`, `voice`, `live` | inputs become scope entries; watch the scope to re-run a word voice |
| `musics.mu.material/stage!` or the conductor | a change at a boundary (section 4, way 2), later |
| `musics.gui.params`, `musics.gui.settings` | one window reads a scope and the shared table |
| docs, cookbook recipes 55 and 56 | rewritten; `mu-words`, `mu-repl`, `mu-tutorial` |

Tests to write: the chain order (the table in section 2 as assertions); a computed key under two
voices; a scope written before its voice starts; the word voice re-run on a scope write; `T_` back
to the shared value; two voices started at once with different scopes.

## 7. Open questions

1. **Syntax: settled.** `:bass/steps T@`, a qualified keyword (the user's choice). It is the form
   `:bass/onsets` already has, it needs no new word, and it is one item. A voice id and a key may
   use the characters `IdName` allows after the slash (letters, digits, `_`, `-`, `?`); the
   unqualified shared keys keep letters, digits and `_`.
2. **A scope written out: settled.** The one `@{ }` literal holds the shared keys and the scoped
   ones: `@{ :steps 8 :bass/steps 5 }`. `:tctx R!` replaces all of them in one step and `:tctx R@`
   prints them back, shared keys first, then the scopes by voice.
3. **Does a scope override the shared table?** Proposed yes, a scope is nearest. The shared table
   stays in front of the music's contexts, so the live override is kept. A voice that must not be
   overridden by a later shared write needs a scope entry of its own.
4. **Declared inputs and free keys.** A declared `$` input has a range; a free scope key has none,
   a text field in the GUI. Writing an undeclared key: create it, or refuse unless asked.
5. **The voice's name as a key** (`:voice`). Section 10 can do without it: the voice is mixed
   into a draw by the engine, not read by a word.
6. **Where a change at a boundary is written** (section 4, way 2).
7. **What a scope holds after the voice is replaced** (`:bass retree`).

## 8. Risks and what to check first

- **Several sources in the engine.** Checked (section 2): no leak, and the memoized `getter` is
  per leaf and per voice. Not checked: a playing engine with its sender thread.
- **Random state.** Checked, and a real problem. The words of `musics.algo.numeric.random`
  (`rand-int`, `choose`, `shuffle` …) draw from one global stream, `default-rng`. With seed 42 a
  voice reading a random key alone gets 17 71 45 46; read in step with a second voice that reads
  the same key, the two take alternate draws from the one stream (17 71 45 46 71 95 77 2). A voice's
  values then depend on how many draws the other made and on the order the sender thread reads them,
  so a seed does not repeat a piece with two voices. The notation's shuffle, choose and alternate do
  not have this: they are counter-based and per voice (`compose/voice-seed`, the pass number).
  Section 10 is the way out.
- **Re-running a word voice.** Today an input watch re-runs it; with a scope the watch is on a
  context's atom, which changes for any key. Re-run only when a key the word declares changed.
- **Compatibility.** `:bass/onsets @` and `!` stop working. They are in the docs and recipe 56;
  a pass over `mus/` and the tests finds the rest.

## 10. Seeding random keys

A computed key that draws (`'( 0 100 rand-int ) :density T!`) must be able to repeat, and the user
chooses how. The choice is written with ordinary scoped keys, not a mode.

| I want | I write |
|---|---|
| Different every time | nothing; `:seed T_` takes a seed away |
| The same every time | `7 :seed T!` |
| Re-roll just the bass | `8 :bass/seed T!` |
| Re-roll one key of the bass | `9 :bass/density/seed T!` |
| The bass unseeded, the rest seeded | `:none :bass/seed T!` |
| Voices that agree on a random choice | write the random key at the shared level |

**The seed is a scoped key.** The part before the last slash says who it is for, voice first, as in
`:bass/steps`. For a key written in the bass scope the lookup is `:bass/density/seed`, then
`:bass/seed`, then `:seed`, then nothing (the clock): nearest and most specific wins. The value is a
number, or `:none` to say "unseeded here" over a seed that is further out.

**A draw is a function of the seed, the key, the voice and the beat**, so it does not depend on
which voice is read first (the counter of `musics.algo.numeric.counter`, as `compose/play-order`
does for the notation's shuffle): the key is always mixed in, so pitch and density never draw
alike; the voice is mixed in only for a key written in a voice's scope; the beat is the onset. A
key written at the shared level is therefore the same draw for every voice at a beat, which is how
two interlocking voices follow one random decision. With no seed anywhere in the chain, or at the
prompt, the random words draw from the global stream, as today.

**Where a key is written decides who shares it**: that is the scope's own meaning (the shared
table is what all voices see, a scope what one voice sees), so no separate sharing setting is
needed. A key written at the shared level takes the shared seed; a key written in a scope takes the
scope's seed, else the shared one.

**Why not modes** (`:piece`, `:voice`, `:key`, `:voice-key`, considered first): once a draw is a
function of the beat there is no stream left to share, a piece-wide mode makes pitch and density
draw the same number at a beat, and what remains of the choice is whether a key is shared by the
voices, which the place it is written already says.

**Words stay as they are.** The random words run inside a key are already bound to a lookup
(`eval/with-lookup`); they read the seed from it and take the key and voice from the engine.

**First slice**: `:seed`, `:bass/seed`, `:bass/density/seed`, `:none`. Left out: the key-only seed
`:density/seed` (its first word reads like a voice; it needs a form that does not clash with voice
names), pass-number indexing for loops.

Open: `N rng/seed` at the prompt, does it become `:seed` at the shared level or stay the global
stream's seed (two ways to do one thing)? `:bass/density/seed` has two slashes, which `IdName` does
not take yet. The draw must know whether its key was written at the shared level or in a scope; the
lookup should know which context answered, not checked.

## 9. Rejected

- **A voice's context for the values** (`:bass !steps:8 with`): string keys, which a keyword does
  not find; and `with` on a voice restages its material from the end of the pass.
- **A prefixed key per voice** (`:bass_steps`) written by hand in each word: every shared word
  would have to know the voice's name.
- **Keeping both mechanisms** (the `$` inputs and the tctx): two tables, two sets of words, two
  windows, for one job. The `$` input is the per-voice layer the tctx lacked, and a scope is that
  layer inside the tctx.
- **Putting the shared table behind the music's contexts**: the live override is what the table is
  for.
