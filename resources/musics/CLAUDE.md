# Grammar notes (resources/musics)

Loaded when working under `resources/musics/`: the grammar files `input/musics.ebnf` and `mu.ebnf`, and what the walker does with them. The project overview is in the root CLAUDE.md.

### Multi-measure rests, pickups, and LilyPond pitch languages

Three real LilyPond-superset gaps, closed together in one pass:

- **`R` (multi-measure rest)** — `MultiRest` in `musics.ebnf`, walked by
  `walker/walk-multi-rest`. With an explicit `Duration`
  (`R1*4`), this is exactly LilyPond's own spelling: the composer picks
  the note-value matching one bar in the current meter, `*n` multiplies
  it, same responsibility a bare `r`'s own duration already carries --
  LilyPond doesn't derive this from the meter automatically either,
  despite the name. With NO duration at all (`R`, or `R*4`), this DSL
  goes one step further and derives one bar's length from whatever
  `Meter` is actually active right there (`musics.domain.context/
  ambient-value` against the full chain, current context included) --
  a genuine extension with no LilyPond equivalent, motivated by a real,
  confirmed transcription bug: `r1` written to mean "rest one bar" in
  3/8 time is actually ~2.67 bars (a bare whole note), not 1.
- **`partial <duration>`** — a new `Instruction` alternative
  (`Partial` in `musics.ebnf`), LilyPond's own literal spelling, not a
  `!`-prefixed Assignment. Walked to a plain `:fixed` value under
  `:Partial`, sampled per leaf in the same batched `c/sample-many` pass
  `:Meter` already rides in (see `musics.domain.resolve/
  common-keys+defaults`). Applied lazily, not by pre-seeding anything
  at voice-creation time: `musics.events/advance-bar` consults a
  per-voice `:partial-pending?` flag (`true` in a top-level voice's
  starting state) and, the FIRST time only, adds `(bar-length - partial)` to that voice's own `:bar-pos`
  before its ordinary `+dur` -- so the first `:bar` crossing lands after
  just the pickup's own length, not a full bar. A forked voice inherits
  the flag from the voice it forks from, so a `{ }` after the pickup
  doesn't count it again; a `partial` that opens a `:PAR` branch before
  any note still applies there.
- **LilyPond pitch languages** — musics text has only GUIDO's
  accidental symbols (`#`/`##`/`&`/`&&`/`n`); there is no `!language:`
  key. `musics.common.music-data/accidental-tables` (`{language {suffix
  semitones}}`, `:nederlands` and `:english`) exists for reading real
  LilyPond source: `musics.input.lilypond-import` detects a file's `\language`
  and passes it to `leaf-parser/accidental-semitones`, which handles the
  symbols first and looks only a letter suffix (`is`/`es`, `s`/`f`) up
  in the table. Another letter-based language is one more table entry;
  solfège languages (do/re/mi) would need the importer's note-name
  parsing widened too.

Scheme (`#(...)`) stays unrecognized by the grammar entirely -- not a
new restriction, confirmed directly: no rule anywhere matches a leading
`#(`, so embedding one is a hard, clear parse failure, the same
behavior every other unsupported construct already gets, never a
silent misinterpretation.

### `\time`/`\tempo`/`\key`

Not part of the grammar. `!Meter:`/`!tempo:`/`!key:` (`Assignment`/
`KeyAssignment`) are the only spelling for any of these (the three
significant keys are capitals: `Tempo` `T`, `Key` `K` -- also `key`,
`k` -- and `Meter` `M`; the key's context name is `:Key`) — see
`doc/md/decisions.md`'s Wave 6 entry for why LilyPond's own free-standing
`\time`/`\tempo`/`\key` spelling was dropped rather than kept as an
alternative. `VarName`'s own reserved-word exclusion list is just the
17 ornament names — `time`/`tempo`/`key` don't collide with anything a
`\name` VarRef could be mistaken for.

### Grammar (`resources/musics/input/musics.ebnf`, instaparse, explicit `ws`, no auto-whitespace)

The parsing layer's own detail -- comments, variables and how the
walker handles them -- is in `doc/md/grammar-notes.md`; read it before
changing the grammar or the walker.

Comments are `//` to the end of the line and `/* … */` for a block, wherever
whitespace is allowed (a `Comment` rule; the walker drops them).

Current bracket scheme — GUIDO-inspired brackets/accidentals,
LilyPond-inspired leaf/command spelling otherwise (checked directly
against `resources/musics/input/musics.ebnf`'s own header comment, always the
source of truth when this section and that comment could drift apart
again):

| Bracket   | Rule          | Meaning                          |
|-----------|---------------|-----------------------------------|
| `[ ]`     | `Sequence`    | musical sequence — also reused as-is for `repeat`/`volta`'s own body; the walker, not the grammar, decides whether a given `[ ]` becomes a real, addressable container or is spliced/stashed, never how it's spelled |
| `{ }`     | `Parallel`    | simultaneous parts (GUIDO's own bracket for this) — can carry an `Id` exactly like `Sequence` can, so a parallel group is individually addressable the same way |
| `^{ }`    | `Context`     | named context/envelope definition — a genuine Clojure map-literal echo, a Context being a bag of key/value settings; the leading `^` sets it apart from Parallel's plain `{ }` |
| `'[ ]`    | `Data`        | data container (`nil` no value, a `'[ ]` inside a list, `'[ ] [~]` a list that loops, `./.` idem -- the value before once more, expanded when read -- a `:keyword` beside any value) |
| `[: :]`   | `RepeatBars`  | a sequence played twice, `repeat 2 [ ]` |

**Notes in a data list** are of three spellings: lowercase is relative (`'[c d e]`
is 60 62 64, the pitch before it deciding the octave), a capital with an octave is
absolute (`'[C4 D4]` is 60 62), and a capital alone is a **pitch class**, 0 to 11
(`'[C D E]` is 0 2 4, `'[C# D&]` is 1 1), an `:int` list that cannot mix with
pitches. A bare letter takes the key's implied accidental, a written one wins.

**A data list is a vector in mu.** The walker builds a `:DATA` node for `'[ ]`
(`:data-type` `:int` `:pitch` `:duration` …), but what mu holds and every word
reads is a plain vector of its values with that type on its metadata
(`musics.mu.material/as-value`, applied where text is read: `read-text` and
the input's literals), a list inside it a vector too. A word that rebuilds
data keeps the vector and its metadata; a stream (`pitches`, `map`, `cycle`)
stays lazy and prints the same. The `:DATA` node exists in the repo only when
stored (`R!`: `->material` builds it, `R@` reads it back as a vector), so
`:ROOT`'s children, `write` and `load` are unchanged. `:SEQ`, `:PAR` and the
rest are not vectors: they carry ids, contexts and addresses the engine reads.

`( )` means four things, never ambiguous with each other since they're
reachable from totally different positions: a slur mark glued directly
onto a Note/Chord (`c4( d4 e4)`), LilyPond-style, at a note's own
trailing suffix position (always glued with no separator onto an existing
note/chord, never a fresh element of its own); `Grouping`, notes grouped
as an element of a seq (`[a: c4 ( d e ) f]`): an **inline `:GROUP`
container with no id** (`builder/pop-container`'s inline branch appends the
finished container map to its parent's `:children`; nothing lands in the
repo), no `Id` allowed, at least one element, and its own instructions
apply to the group alone (it has its own `:context`; whatever it does not
set comes from the seq around it, and a `!pp` inside does not reach the
note after it). It plays exactly as its notes spliced and signals nothing
(`musics.events` fires no section/`boundary` for a container without an id).
The rule is named `Grouping`, not `Group`: `mu.ebnf` has its own `Group` (run
now, at mu's top level), and a second rule of that name would replace the
first when the two files are joined. Order words
(`retrograde`, `[R]`, `rotate`, `shuffle`, `take`, `length`) see a group as
ONE element: it moves, its notes keep their order. Pitch words (`transpose`,
`inversion`/`mirror` and `[I]`/`[M]`, `+`) reach into it, giving the pitches a flat
seq gives (`builder/deep`: leaf-f reaches in, order-f does not). `print/show` writes it back as
`( … )` and `read-text` reads such text as the one element of a seq; and
`Scope`,
the transient body of the bare-word structural commands below
(`transpose c g ( c4 d4 e4 )`) — never a container of its own, always
spliced/stashed at pop time, same as `[ ]` is for `repeat`/
`volta`'s own body. (`StructValue`'s own unrelated `( )`, a
parenthesized `Value` after `!name:`, is a third, textually-distinct
use reached only from that one position — see `musics.ebnf` directly
if you need it, not covered further here.)

No algorithm is invoked inside music: an algorithm is a function called by name
in mu (see "The algorithms"). `(par ...)` is likewise not this grammar's own spelling for
anything — that's tier 3's play-arg mini-language (see "The play-arg
mini-language" below for `musics.compose/par`, a genuinely different,
Clojure-data-side mechanism with its own reasons for existing,
unrelated to this text grammar's own `{ }` Parallel).

**The top level is mu's.** `musics.ebnf` defines the literals; `mu.ebnf`,
appended to it (`musics.mu.grammar`), defines `MuInput`, the one start
rule -- a `.mus` file, the GUI editor's text and the mu prompt are read
alike. At the top level a literal (`Composite`, `Leaf`, `repeat`, ...)
is a value, a name a word, `:name` an id, `\name` the bound value;
nothing written there reaches `:ROOT`'s context, which stays a read-only
endpoint with a guaranteed value for every key
(`musics.common.context-keys/root-defaults`,
`musics.domain.context/context-root`): a bare `Instruction` is parsed
only to be refused (`LooseInstruction`), and so is a transient command
(`TopCommand`: `transpose`, the row forms, `grace`, `shuffle ( )`,
`rotate n ( )`), which belongs inside a seq. A bare leaf is a value of
its own: `walker/walk-each` walks it inside an ordinary auto-id'd
one-child `:SEQ` (`wrap-bare-leaf`), and stored it is a one-note seq --
`c4 d4` stores two, `[ ]` groups. See `doc/md/decisions.md`, 2026-10-23.

`:ROOT` being grammar-guaranteed write-once is also what lets its own
context values skip the general `Envelope`/`Point`/atom machinery
entirely: `musics.domain.context/ValueSource` (`sample-at`/`shift`,
`extend-protocol`'d over `Envelope` and a bare-value fallback; `Generator` and
`Derived` are two more sources) lets
`context-root` store each default as a plain value directly — no
allocation for something that, by construction, can never receive a
second point. Any other context still builds a real `Envelope` as
before (a `!tempo:90` inside `[verse: ...]` could still legitimately
grow into a ramp later); the protocol dispatch is what lets
`ctx-value-chain`/`ctx-shift` treat both shapes uniformly without
needing to know in advance which one a given key holds.

`repeat`'s own body, its `volta` endings and measured tremolo's body all
persist as a real, retained container (an `Iterator`'s own `:source`/
`:alternative` to replay on each iteration), so `[ ]` (`Sequence`) has
always been the correct, unambiguous bracket for them — unlike
`transpose`'s and the row forms' own (transient, spliced-not-registered) `( )`
`Scope` body, which is never registered at all.

`Id` is `name:` (registers in the repo); `Reference` is `:name` (looks it up —
either a container/iterator to splice in, or a `:CONTEXT`). A context
written in a sequence -- a `:loud` reference or an inline `^{ !mf }` --
is the first context of everything after it there, nested seqs
included: `[c d ^{ !mf } e [!ff f g]]` plays e, f and g at mf. The
walker leaves an override marker among the children (`{:type
:assignment :key :override :ref id :raw ...}`, `override-mark`), which
`musics.events`' `walk-children` and `display-timed` put in front of
the chain (`musics.compose/override-of`); it also replays the context's
points into the current container (`apply-context-ref`), so what is
read while parsing (a key, a meter) sees them. Printers write the
marker's `:raw` back; `write`/`load` keep it (plain data, the id). A
variable is mu's: `'name value :=` binds, `\name` (`VarRef`) is the
value -- see "Comments and variables" in `doc/md/grammar-notes.md`.

`repeat ~ [ … ]` repeats endlessly (`:count :infinite`, no `volta`).
`rotate n [ … ]` (`:SEQ` marked `:rotate n`) moves them n places further
each time it is entered (the walk counts entries per voice). `choose [ … ]`
(`:choose`) plays one of its elements, picked at random, each
time it is entered (`choose 3,1 [ … ]` weighs them, one weight per
element, kept as `:weights`); its length, where one is needed, is its longest
element's (`d/duration`); `alternate [ … ]` plays its elements
in turn, one each time it is entered, counted per voice like `rotate`.
`shuffle [ … ]` is a seq (`:SEQ` marked `:shuffle`) whose notes, seqs
and repeats play in a new random order each time it is entered, so
`repeat 4 [ shuffle [c d e] ]` shuffles every pass; instructions, bar
seqs and jumps keep their places (`musics.compose/play-order`, used by
`musics.events` and `display-timed`). The order of a pass is counter-based
(`musics.algo.numeric.counter`): pass n of the part, under the voice's seed (`compose/voice-seed`: the
shared rng's seed, which `N rng/seed` sets, mixed with the voice's path), is the same every time it
is asked, so a seed repeats a piece, a restart plays it again, and one voice's draws do not move
another's. `choose [ ]` picks the same way. mu's `[?]` makes one.
`[: … :]` (`RepeatBars`) is `repeat 2 [ … ]` in repeat signs,
an Iterator marked `:bars` so the printers write it back that way;
a `volta [ … ]` (its endings) follows it as it follows `repeat`. `Jump` marks,
written inside a sequence, are zero-duration nodes (`d/jump`): `$` the
segno, `DS`/`DC` the jump back to it or to that sequence's start,
`Fine` where the return pass stops, `Coda` twice (the return pass
leaves at the first for the second), `./.` (alone in its bar: a bar line before
it, a bar line or the end after) the bar before it once more; a return pass goes on past the
jump without taking it again. `musics.domain/play-segments` gives a
sequence's children in the order they play (index ranges), and
throws, at read time, on marks that can't be played; `d/duration`,
`musics.events` and `display-timed` follow it, a return pass
re-entering the sequence's context where it is written. A `[: :]`
inside the part jumped back to repeats again. While reading, positions
are measured as written (`d/written-duration`).

`BarLine` (`|`, `||`, `|||`, `||||`) walks to a `Bar` record (`d/bar`,
zero duration) inline in `:children` — purely a structural marker on disk,
but no longer inert at playback: `musics.events` emits a `:mark` event
for each one a voice reaches, which the engine signals to
`musics.conductor` (see "Conductor"
above), so `|`/`||`/etc. are exactly how a composer places an extra,
author-controlled cue on top of the automatic `:section`/`:bar` signals.
Reachable only through `Sep`/`EdgeBar` (`Sequence`/`Parallel`'s own
element-list separator and edge-of-body marker), both built on a shared
`BarRun` (`BarLine (ws? BarLine)*`) so a *run* of consecutive bar lines —
not just one — is legal wherever a single one is, e.g. `c4 | | d4` (two
adjacent single-pipe checks with nothing between them). This was a real,
confirmed gap, not a hypothetical one: `musics.input.lilypond-import`'s own
`repeat`/`\relative` body-flattening can legitimately produce exactly
this shape — a nested `\relative` block contributes no wrapping container
of its own (`relative-block-text`'s own text has no surrounding
brackets), so its own leading edge bar ends up sitting directly next to
whatever bar line the enclosing stream already had, with no `Element`
between them for the old `Element (Sep Element)*` structure to accept.
Each `BarLine` in a run still surfaces as its own separate sibling node
(`BarLine` itself isn't hidden) — `walker`'s own `:BarLine`
case needed no change at all, it already appends one zero-duration `Bar`
record per occurrence regardless of how many arrive in a row.
`lilypond-import`'s own `append-relative-block` (a sibling of
`push-barline`) additionally dedupes the specific case it CAN see across
(a `\relative` block's own leading bar against the immediately preceding
one in the same accumulator) so the common case doesn't emit visibly
redundant text at all — a `VarRef`'s own stored body is opaque to that
check, so that case relies on the grammar's own tolerance instead.

Tempo takes either a bare BPM (`!tempo:120`, quarter note implied) or a
LilyPond-style `TempoMark` — note-value `=` BPM (`!tempo:4=120`, a
dotted one, `!tempo:4.=120`, or a ratio, `!tempo:3/8=120`). The
walker (`walker.clj`'s `walk-assignment` `:TempoMark` case)
converts a `TempoMark` to the quarter-note-equivalent BPM `resolve.clj`'s
tempo sampling expects (`el/tempo->quarter-bpm`, e.g. `8=120` → `60`,
since an eighth note is half a quarter, so eighth=120 is the same speed as
quarter=60) before storing it — `resolve-event` never sees the note-value
side at all, only the normalized BPM. `!tempo:`/`!Tempo:`/`!T:` all
canonicalize to the same `:Tempo` context key (`musics/common/context_keys.clj`)
and all work identically, for either form.

Named tempo markings (`common/music-data.clj`'s `tempo-markings` —
`:largo`/`:andante`/`:allegro`/`:presto`/... at their standard BPMs) are
usable directly as `BangConst`s (`!allegro`, `!presto`, ...), same as a
dynamic mark (`!mf`/`!ff`) — `instruction-context` merges both tables into
one `keyword -> [context-key value]` map that `walk-bang-const` looks up
generically, so no separate wiring was needed for the single-word ones.
A bare `!name` that is none of these is refused (`walker/walk-bang-const`, `unknown-mark!`): it names the marks it is within two letters of, or the context key that needs a value (`!volume:50`); a name with a value (`!name:value`) is any key, a custom one too. The handful of compound names are camelCase keys of `tempo-markings` itself (`:marciaModerato`, `:andanteModerato`, `:allegroModerato`, `:allegroVivace`), since `BangConst`'s `Name` token can't contain a hyphen, as `:commonTime`/`:stageLeft` are.

Accidentals are GUIDO's symbols only — `#`/`##`/`&`/`&&`/`n`, a key's
tonic too (`!key:B&.minor`, any case, any of the 23 scales); LilyPond's
Dutch suffixes have to be rewritten (see `doc/md/lilypond.md`). A dynamic mark
glued onto a note/chord (`c4\f`, `<c e g>4\mf`) is that note's own
volume, a per-note override exactly like `c4\vol:70`; the next note is
back at the context's. A note can't carry a crescendo or decrescendo:
that spans time, so it is an instruction (`!vol<`, `!vol:mf<`,
`!vol<16:ff`). The LilyPond importer, where a dynamic holds until the
next one, writes `!f` before the note and turns `\<`/`\>` into
`!vol<`/`!vol>` (joined to the note's dynamic as `!vol:mf<`). Absolute octaves need a **capital**
pitch letter (`C5`); lowercase is always relative pitch resolution (nearest
fourth/fifth, LilyPond `\relative`-style) even as a sequence's first note —
there's no position-based exception. A capital letter without an octave
takes the octave number of the pitch before it (`leaf-parser/resolve-
pitch`, its `last-ref`), as a duration carries over -- notes, chord tones
(a chord's next starting from its first tone, as relative chords do),
chord-symbol roots and bass notes alike; octave 4 at the start.

A chord symbol is a note with a `:` quality (`ChordSymbol`: `F3/1:7`,
`c:m7`, `G3:7.9-/B`), LilyPond's chordmode spelling as a leaf of its
own, walked (`walker/walk-chord-symbol`) to an ordinary multi-pitch Leaf. The
qualities are `musics.common.music-data/chord-qualities`: LilyPond's
words, `.step[+/-]`/`^step` alterations, `/pitch`/`/+pitch` bass notes,
and the shorthand `m` `M` (`M` the triad, `M7`.. the major sevenths)
`d` `A` `s`; a bare `7` is dominant. The quality is required (a note
without one is a note; `:M` is the major triad). A tremolo is `:8` `:16` `:32` `:64` or `:128` (`Tremolo`'s
lookahead), which keeps every `:` quality from reading as one.

A note or chord can override any context key for itself with a
Modifier, `\name:value` (`c4\vol:90\i:40`, `e4\pan:-1.0`): the value
reads as `!name:value` reads it (signed numbers, ratios, a dynamic mark
for volume, strings), the name is canonicalized the same way, and the
walker stores the lot as the leaf's `:overrides` map, which
`musics.domain.resolve/resolve-common` merges over the sampled context for
that note only (ornament and tremolo sub-notes keep it). A glued
dynamic (`c4\f`) is the same override, for volume.

A `Ramp` (`!key<...`, any context key) has four shapes: bare open-ended
(`!vol<`, `!vol>` — marks a ramp-start with no target, interpolating
toward whatever value comes next), bare with a curve (`!vol<s` —
`l`/`s`/`i`/`o` = linear/smooth/ease-in/ease-out), timed (`!vol<16:ff` —
duration, a raw whole-note count/product/ratio, not a note-value
reciprocal the way a note's own Duration digit is, then a target), and
timed with a curve (`!vol<s:16:ff`). `!key:value<` (`!vol:mf<`,
optionally `!vol:mf<s`) sets the value *and* marks a ramp-start in one
instruction, for any key. Deliberately *not* mirrored onto `BangConst` (`!mf<` was considered and
rejected) — `Name` has no exclusion list, so a trailing direction there
would collide with `Assignment`'s own bare-Ramp alternative: `!p<`
already parses today as `Assignment`(`AssignName` "p") + `Ramp`(bare
"<"), since `p` is a registered `:panning` alias, and `p` is also a real
`DynamicMark` word (pianissimo) — a directly demonstrable ambiguity, not
a hypothetical one. A direction is always bare (`<`/`>`, no `\`), since
`!` already marks "this is an instruction".

A point can carry its time, `@t` (`At`), after a value form --
`!vol:mf@3/8`, `!vol:50<@1`, `!mf@1`, `!K:D.major@2` -- in whole notes
from the start of the container it is written in, as a timed ramp's
duration counts them (`@4` four whole notes, `@3/8` three eighths): so a
`^{ }`, which holds no notes and had every point at its start, can hold
values that change later, `^{ !vol:50< !vol:70@1 !vol:90@2 }`, a ramp
mark gliding to the next point wherever its `@` puts it. The walker's
`point-time` reads it, and `with-at` keeps it on the instruction's
`:raw`, so the context prints back as written. In a seq it counts from
the seq's start too -- where `with` puts a context's instructions.

A bare (unmarked) pitch letter resolves against the active `Key`'s own
implied accidental by default — real staff-notation behavior: under
`!key:D.major`, a bare `f`/`c` sounds sharped without writing so, same as
a key signature implies on a real staff, and an explicit accidental
(`fn`/`f#`/`f&`/...) always overrides that outright. This is a
deliberate departure from LilyPond itself, whose input is always
literal (key affects printing only) — gated by a context key,
`:accidentals` (`!acc:`), `:implied` by default or `:explicit`
for literal/LilyPond-style resolution, rather than tying the behavior to
`!key:` itself (which already has an unrelated existing use — ornaments'
scale-relative resolution — that shouldn't gain a silent side effect). C
major (the context default when no `!key:` is ever set) implies nothing
either way, so a piece that never sets a key is completely unaffected.
`lilypond_import.clj` emits `!acc:explicit` once, ahead of
everything else, on every converted piece — imported content's meaning
should never depend on this format's own default, since real LilyPond
source is always already literal.

The key also applies where a note is played, not only where it was
written: a note/chord leaf keeps the Key its letters were resolved
against (`:key`, `walker/written-key`; none under
`:explicit`), and `musics.domain.resolve/rekey` — run per leaf before the
wall and ornaments, by `musics.events` and `display-timed` — moves each
pitch that is a degree of that key to the playing key's accidental for
that diatonic step (`musics.common.music-elements/rekey`, `key-step`):
`[mot: c d e f g]` plays `c d e f# g` inside `[!key:G.major :mot]`. A pitch outside the leaf's
key (written with its own accidental) stays; so does a container's own
`!key:` (nearest wins) and anything under `!acc:explicit` where it's
played. A written accidental that the key already implies (`fn` in C)
can't be told from a bare letter and follows too. `transpose` adds
its interval to the leaf's `:key-shift`, and `rekey` transposes both
keys by it before comparing, so transposed notes follow the playing key
transposed the same way; their printed names are spelled in the
transposed key (`musics.common.music-elements/transpose-key`). A chord
symbol moves by its root (`:key-root`), keeping its written quality
(`F4` in C plays F# major under G). Generated notes carry no `:key` and
never move.
