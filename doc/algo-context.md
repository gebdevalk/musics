# Context in the algo space

How algo trees relate to what musics text can say, starting from the
three things that define it:
- the grammar (`resources/musics/input/musics.ebnf`): what can be written;
- the walker (`musics.input.reader.walker`): what it becomes;
- the domain (`musics.domain.*`, `musics.domain.resolve`): what playback
  reads.

The algo space should express the same things the same way, not
parallel versions of them. Written 2026-10-04, after building the bridges
and leaf assembly (`doc/algo-audit.md`). It is an analysis to decide on,
not a plan.

## 1. What text can say: three layers

The grammar already separates three layers. The algo space has
reinvented each of them.

### Context: settings over a stretch of music

- **Instructions** inside a container: `!vol:mf`, `!tempo:4=90`,
  `!key:D.major`, `!instrument:40`, `!pan:-0.5`.
- **Ramps:** `!vol<16:ff`, `!vol:mf<s`.
- **Named blocks** `^{ name: … }`, replayed by reference (`:name`).
- **Clearing:** `!/key` drops a setting, so the enclosing context
  answers again.

The walker writes these as envelope points (time → value, with an
interpolation) into the current container's Context. Contexts nest
along the container chain, and each note samples them at its time.

A dynamic glued onto a note (`c4\f`) is that note's own volume, a
per-note override (below). A crescendo spans time, so it is only ever an
instruction (`!vol<`, `!vol:mf<`), never glued onto a note.

### The note itself, with per-note overrides

A Note or Chord is a pitch and a duration plus suffixes
(`NoteSuffix*`, in any order, then `Tie?`):

| Suffix | Written | Walker puts it on the Leaf as | Played as |
|---|---|---|---|
| Articulation | `-> -. -^ \accent \ghost …` | `:articulation` (length ratio) and `:dynamic` (volume offset) | the leaf's own value **wins** over the context's articulation; `:dynamic` is added to the context's volume |
| Ornament | `\trill \mordent …` | `:modifiers` `["ornament" name]` | expanded into sub-notes (`musics.domain.ornaments`) |
| Tremolo | `:32` | `:modifiers` `["tremolo" 32]` | expanded into sub-notes |
| Slur | `( … )` | `:articulation` legato on the spanned notes | baked per note |
| Tie | `~` | `:tied` | no note-off |
| **Modifier** | **`\name:value`** | `:modifiers` `["mod_name" value]` | **nothing reads it** (see below) |
| Dynamic | `\f` | `:overrides` `{:volume 70}`, like `\vol:70` | that note's volume (no hairpin on a note: a crescendo is `!vol<`) |

**Per-note overrides exist, and the Modifier is their general form.**
`c4\vol:90`, `c4\i:40` or `c4\pan:-1.0` parses today; a note can
override any context key for itself. Articulation is the one override
the domain already applies: `resolve-common` takes the leaf's own
`:articulation` before the sampled one.

The generic Modifier is unfinished in two ways:
1. **Nothing applies it.** No code reads `mod_*` entries, so
   `c4\vol:90` plays at the context's volume.
2. **Its integer values are mangled.** The walker passes an Int through
   `parse-duration`, so `\vol:90` is stored as `1/90`.

### Data: material as streams

The Data container, `'[ ]`, is the text form of a stream of material,
typed by its first element or an explicit type:

| Written | Walks to | Data type |
|---|---|---|
| `'[c d e g]` | `[60 62 64 67]` | `:pitch` |
| `'[/4 /8 /8.]` | `[1/4 1/8 3/16]` | `:duration` |
| `'[-> -. -^]` | `[{:duration 0.9 :dynamic 5} …]` | `:articulation` |
| `'[1 2 3]`, `'[0.5 1.5]` | numbers | `:int`, `:float` |
| `'[!mf !p !ff]` | instruction maps | untyped |

The grammar's own comments call these a **color** (pitches) and a
**talea** (durations). `musics.core/sq` hands a Data container's
children to Clojure, so `(sq :talea)` can already feed a tree as a
literal.

### Ids name containers only

`Id` (`name:`) belongs to Sequence, Parallel, Context and Data. A note
never has one. The walker's leaf `:id` is just the token it was parsed
from (`"note-60"`).

## 2. What playback reads

Per note, `resolve` samples one batch of context keys:
- for every leaf, rest and drum: `:Tempo :volume :Meter :Partial
  :durScale :micro :humanization`;
- for a Leaf, also `:instrument :transposition :octave :panning`;
- `:articulation` unless the leaf carries its own;
- `:key :accidentals` (for respelling).

These are `musics.domain.resolve/played-keys`, the same set the GUI shows
sliders for. Everything a note can sound like is one of these keys, a
leaf field (duration, pitches, articulation, dynamic, tied), or an
ornament or tremolo expansion.

## 3. The algo space mapped onto the three layers

| Text layer | Algo space today | Duplication or gap |
|---|---|---|
| Context (settings over time) | tctx params: `:key`, `:pulse`, volume `:lo/:hi`; per-leaf fields from blend steps | the bridges' `:key` repeats `!key:`; trees ignore the context chain the live wall fn is handed |
| Per-note overrides | step 2a's `:volume`, `:program` fields, `+volume`, `+instrument` | **a second per-note mechanism next to the Modifier**, with its own field names; `part->mus` can't write them |
| Data streams | tree types `:pitch :duration :articulation :number ...` | `:duration` vs the domain's `:duration` (Leaf field and Data type); tree articulation is a name, Data's is a resolved map |
| Ids on containers | none; `zip` gives `:id nil` | consistent: leaves have no ids |

## 4. Conclusions

### 4.1 Per-note material is per-note overrides

The leaf principle's materials beyond duration and pitch (volume,
articulation, instrument, and also panning or transposition) are
**per-note overrides of played context keys**. The grammar already
writes them as Modifiers. So instead of a field per material:
- **The domain:** a Leaf carries one sparse map, `:overrides` (say
  `{:volume 90 :instrument 40}`), keyed by canonical context keys.
  `resolve-common` merges it over the sampled values: one line, and it
  covers every played key. The leaf's `:articulation` is the existing
  precedent.
- **The walker:**
  - stores `\name:value` there under the canonical key (`\vol` →
    `:volume`, `\i` → `:instrument`);
  - parses the value as a value, not a duration, fixing the `1/90` bug.
- **Text out:** `part->mus` writes overrides back as `\vol:90\i:40`, so
  generated material round-trips through text.
- **The algo space:** step 2a's `:volume` and `:program` fields and the
  three blend steps collapse into one, setting any key per note, for
  example `(override leaves :volume volumes)`. A drum stays a different
  leaf type (`x8\38`), not an override.

This replaces the earlier idea of moving volume and articulation into
envelopes. Envelopes are for change over time, overrides for the note
itself, and text has both.

### 4.2 Trees read the context they play in (option A, confirmed)

Bridges and algos take their defaults from the context where they're
played: the `!key:`, the `Meter`, the tempo. A param stays as an
explicit override.
- **For:** less duplication; generated material follows the piece. The
  live path already has the chain.
- **Against:**
  - `t/run` on its own needs a default context, which the root
    defaults provide;
  - a live tree needs a rule for when it re-reads: per note or per
    phrase.

### 4.3 Trees may also produce context (option B, narrowed)

Producing envelopes is still useful for what is genuinely about time:
- a crescendo over a phrase (`!vol<16:ff`);
- a tempo curve;
- a panning sweep.

It is no longer needed for per-note material (4.1). A tree that makes
envelopes produces a **Sequence with its own Context**, the text
equivalent of `[!vol:p< … !vol:ff]`. That makes containers values in
the tree and play path, the larger change, so it can wait until
something needs it.

### 4.4 Data containers are the text side of tree material

The tree's material types and the Data types should be the same:
- **`:duration` becomes `:duration`**, as the Leaf field and the Data type
  spell it (singular, as decision 5 asked).
- **`:articulation` values** should agree with Data's: either resolved
  `{:duration :dynamic}` maps, or names resolved at the leaf.

With one vocabulary:
- **Text into a tree:** a Data container feeds a tree, `'[c d e g]` as a
  color, `'[/4 /8 /8.]` as a talea, through `sq` today, or a `data`
  source algo reading a committed id.
- **A tree back into text:** a tree's raw material can be written out as
  a Data container, the way finished leaves are written out by
  `notes->mus`.

### 4.5 Id and context of generated leaves

`zip` gives no id (a note has none in text) and no context. A
generated leaf takes its settings from where it is played, and its own
per-note overrides (4.1). An id and a Context belong to the container
the material is committed into, as in text: `[name: !tempo:90 …]`.

### 4.6 Unchanged

- **C (the tctx as a context)** is still the largest change. Its time-
  varying params would cost a re-run per sample. It is best left until
  4.1–4.3 have settled.
- **D (choosing an algo from text)** stays out, by an earlier decision.

## 5. The picture after this

| Layer | Text | Domain | Algo space |
|---|---|---|---|
| Settings over time | `!key:value`, ramps, `^{ }` | Context envelopes | read by trees (A); made by trees when it's about time (B) |
| The note | pitch, duration, suffixes, **`\key:value`** | Leaf: `:duration :pitches :articulation :dynamic :tied :modifiers` + **`:overrides`** | `zip` (duration, pitch) + **override** (any played key) |
| Material streams | `'[ ]` typed `:pitch :duration :articulation …` | Data container | tree types with the **same names** |
| Names | `name:` on containers | container `:id` | ids only where material is committed |

## 6. What it changes in current work

1. **Fix the walker's Modifier** (value parsing, canonical keys) and
   **apply overrides in `resolve`**. A small, self-contained domain fix;
   text gains it whatever happens in the algo space. *Done
   2026-10-04:* a leaf's `:overrides`, merged by `resolve-common`;
   ornament sub-notes keep them; ModValue takes signed numbers and
   ratios.
2. **Replace step 2a's `:volume`/`:program` fields and the three blend
   steps** with `:overrides` and one blend algo. `part->mus` writes
   overrides as modifiers. *Done 2026-10-04:* `+volume`/`+instrument` and a generic
   `+override` write `:overrides`; `part->mus` writes them, and a note's
   articulation, back as text; drums take Modifiers.
3. **Rename the tree type `:duration` to `:duration`**, and align
   `:articulation` with Data.
4. **Bridges and algos read `!key:`/`Meter` from the context**, keeping
   params as overrides.
5. **Later, when needed:** trees producing context (containers as
   values). Option C after that.

## 7. Questions

1. Overrides: one generic blend algo (`override` with a key param), or
   one per common key (`+volume`, `+instrument`) built on it?
2. ~~Should `c4\f` stay a context write?~~ Decided 2026-10-05: it is
   the note's own volume; note hairpins are gone; the LilyPond importer
   writes `!f`/`!vol<` before the note.
3. Articulation in trees: names (`:accent`) or resolved maps, as Data
   holds them?
4. A live tree reading its context: re-read at every note, or at phrase
   boundaries?
