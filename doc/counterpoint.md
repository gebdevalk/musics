# Species counterpoint after Jeppesen, in core.logic

## Context

Knud Jeppesen's *Kontrapunkt* (1930; English *Counterpoint: The
Polyphonic Vocal Style of the Sixteenth Century*, 1939) teaches
Palestrina-style counterpoint through the five species (kinds), in two,
three and four voices, against a given *cantus prius factus* (a cantus
firmus, CF). musics has one counterpoint generator,
`algo.melodic.counterpoint` (motif imitation + a few greedy rules, which
falls back to rule-breaking pitches when stuck). It has no species,
can't take a given cantus to work against, and can't tell a third
from a diminished fourth.

`algo.logic.counterpoint` takes a cantus, a number of voices (2–4) and
a kind (1–5), and returns counterpoint that obeys Jeppesen's rules —
or `nil` when none is found — searched with core.logic. Every rule has
a name, and the same rules *check* music (a cantus, a hand-written
counterpoint) and say what breaks, where.

Decided with the user: with 3–4 voices, **one added voice moves in the
chosen kind, the others in 1st species** (Jeppesen's and Fux's exercise
convention), a `:kinds` option giving each voice its own; **church
modes plus major/minor** (major as ionian, minor as aeolian with a
raised leading tone at cadences).

## Interface

```clojure
(require '[algo.logic.counterpoint :as cp])

(def r (cp/counterpoint {:cantus [62 65 64 62 67 65 69 67 65 64 62] ; whole notes, MIDI
                         :voices 3          ; 2, 3 or 4, the cantus included
                         :kind   2          ; 1..5, for the florid voice
                         :cantus-in :tenor  ; :soprano :alto :tenor :bass
                         :mode   nil        ; :dorian ..., :major/:minor; nil = from the final
                         :kinds  nil        ; per added voice, top to bottom, e.g. [2 3]
                         :ranges nil        ; {part [lo hi]} replacing a default range
                         :seed   1          ; reproducible shuffles
                         :tries  10         ; searches; the best by the soft rules is kept
                         :budget 10000}))   ; notes tried per search
;; => {:voices [[[pitch dur] ...] ...]   top to bottom, durations as note values (1 = whole)
;;     :parts [[:soprano 2] [:tenor :cantus] [:bass 1]] :mode :dorian
;;     :penalty 16 :penalties {:leaps 5 :perfect-downbeats 3 ...}}   ; or nil

(cp/check r)                      ; => [] or [{:rule :parallel-perfects :bar 4 :part :soprano :with 2 ...}]
(cp/check-cantus [62 65 65 62])   ; => [{:rule :repetition}]
(cp/->mus r :cpt)                 ; "{cpt: [ !acc:explicit r2 A4/2 ... ] [ ... ] }", spelled in the mode
```

`check` takes a result, or the same shape written by hand (`:voices`,
`:parts` with the cantus' kind `:cantus`, optional `:mode`), and reports
every hard rule broken, with its bar. `->mus` gives musics text ready
for `parse` and `play`, each note spelled from its letter in the mode
(the B♭ of D dorian is `B&4`, its leading tone `C#5`).

Defaults: `:cantus-in` — with two voices the cantus is the lower part
and the counterpoint goes above (`:soprano`/`:alto` put the cantus on
top); with three or four, the tenor, or else the first part whose range
holds the cantus. Parts: three voices soprano, tenor, bass; four voices
soprano, alto, tenor, bass. Ranges (Jeppesen's clefs, as MIDI): soprano
60–79, alto 53–74, tenor 48–69, bass 40–62, moved by octaves so the
cantus' part holds it; the added voice of two spans the cantus' range
plus an octave above (or below). With 3–4 voices the top added part
moves in `:kind`, the others in 1st species, unless `:kinds` says
otherwise.

Also the tree algo `:species` (`:in [:pitches] :out :layers`; params
`:voices :kind :cantus-in :mode :seed`): `(pair-notes (layer
(species cantus)))` plays a voice; no solution gives `[]`.

## Design

**Notes carry their diatonic step.** Every candidate is `{:m midi :d
dpos}` — `dpos` = 7·octave + step, from `el/key-step` on the mode's key;
a ficta note keeps its letter (C# is a C) and says where it may stand
(`:ficta :cadence`, `:free`, `:final`). An interval knows steps and
semitones, so P5, d5 and A4 stay apart (`intervals.clj`).

**The score** is a map of events per part, time in eighths (8 to a
bar): `{:n note :on :len :bar :tied?}`, a rest with `:n nil`. Hard rules
(`rules.clj`, `violations`) run as each event is placed, against what is
already decided, and return violations named by rule; the search prunes
on them, `check` collects them all. A dissonance is judged as a figure
of the voice that makes it: its approach when it is placed (marked on
the event as the figures it could still be — passing tone, lower
neighbour, nota cambiata, suspension), its departure when the next note
of that voice comes. Soft rules (`penalties`) score a finished score.

**Search** (`counterpoint.clj`). Bars left to right; in each bar the
1st-species parts bottom up, then the florid ones, so a florid voice's
dissonances are judged against everything else in its bar. Each choice
— a bar's rhythm pattern (4th and 5th kinds), each note — is a
core.logic goal, tried depth first (`firsto`: core.logic's own
interleaving keeps a stream per alternative and runs out of stack on a
long search). Candidates come steps first, then thirds, then larger
leaps, shuffled within each by `algo.random`; the last bar offers only
the notes a part may end on, the penultimate's last note only those a
step (or, for a bass of 3–4 voices, a fourth or fifth) from them — so
the cadence can't become a dead end discovered at the end. `:tries`
short searches (each `:budget` notes) with different shuffles beat a
few long ones, which thrash; they run in parallel, at most one per core
(each on its own thread with a 64 MB stack, since core.logic nests a
few frames per note), and of the results, taken in seed order, the one
with the lowest penalty is kept — the same one a run in turn would
keep. A search hands its result out boxed (core.logic can't walk the
Clojure sets in it).

**Where it lives.** `src/algo/logic/counterpoint.clj` (layout, rhythm,
search, `check`, `->mus`, `:species`), `counterpoint/rules.clj`,
`counterpoint/intervals.clj`. `algo.melodic.counterpoint` (motif
imitation) is a different algo and stays.

## The rules

Hard = must hold (pruned); soft = preference (scored). Intervals are
counted from the lowest sounding voice unless noted.

### Melody (every added voice, every kind)

| id | rule | |
|---|---|---|
| `:melodic-intervals` | Steps (m2, M2); leaps m3, M3, P4, P5, P8; the minor sixth ascending only. No augmented or diminished intervals, no sevenths, no major sixth, nothing over an octave. | hard |
| `:leap-recovery` | After a leap of a fourth or more, move by step in the opposite direction. | hard (≥ 5th), soft (4th) |
| `:consecutive-leaps` | Two leaps in one direction only if they outline a triad and the larger is below (ascending: larger first); never three. | hard |
| `:outlined-dissonance` | No tritone or seventh outlined between the turning points of a melodic span. | hard |
| `:range` / `:span` | Within the voice's range; a line spans at most a tenth. | hard |
| `:climax` | One highest note, not repeated. | soft |
| `:repetition` | A note repeated only whole against whole (1st kind), never three times. | hard |
| `:steps` | Mostly stepwise; leaps balanced by steps. | soft |

### Cantus (checked, as warnings, on the given cantus)

Begins and ends on the final; ends re–do (step down to the final);
mostly steps, no repeated notes, range ≤ a tenth, one climax; the
melodic rules above. A cantus that breaks them is still used.

### Two voices

**All kinds.** Consonances: P1, P5, P8 (perfect), m3, M3, m6, M6
(imperfect) and compounds; the fourth is a dissonance in two parts.
Begin on a perfect consonance (counterpoint below the cantus: unison or
octave only, never the fifth); end on unison or octave. No parallel
fifths or octaves, nor by contrary motion; no hidden (direct) fifths or
octaves (similar motion into a perfect consonance); no more than three
parallel thirds or sixths in a row (soft); voices at most a twelfth
apart, never crossing; the unison on a downbeat only in the first and
last bar.

| kind | rhythm | rules beyond the above |
|---|---|---|
| **1st** | whole against whole | every note consonant; cadence: sixth → octave (counterpoint above) or third → unison (below) with the leading tone |
| **2nd** | two halves against one | downbeat consonant; upbeat dissonant only as a passing tone (approached and left by step, same direction); no parallel perfects on successive downbeats; starts with a half rest; unison allowed on the upbeat; last bar a whole note |
| **3rd** | four quarters against one | first quarter consonant; dissonance on 2nd, 3rd (descending, Jeppesen's accented passing tone) or 4th quarter as a passing tone, or as a lower neighbour; the nota cambiata (dissonance on the 2nd quarter, down a step, leap down a third, step up); no leap from a dissonance otherwise; no parallel perfects between successive downbeats, nor from a fourth quarter to the next downbeat |
| **4th** | halves tied over the bar (syncopation) | the upbeat consonant (preparation); the tied downbeat consonant, or a suspension resolving down by step to a consonance: above the other voice 7–6, 4–3, 9–8; below it 2–3 (9–10); the tie may be broken where no suspension works (penalised); no parallel perfects between successive weak-beat notes |
| **5th** | florid: half, dotted half, quarter, eighth, ties | each note obeys the rule of its value and position; a bar's rhythm is one of Jeppesen's patterns: eighths only in pairs on a weak quarter, approached and left by step; a tie only from a half on the weak half into the next bar's first half (no quarter tied); a suspension may resolve on a quarter; starts with a half rest |

### Three voices (Jeppesen's three-part section)

The two-part rules hold between every pair, with these changes:

- Consonance is judged against the **lowest voice**: a fourth between
  two upper voices is fine; a fourth (or six-four chord) above the bass
  is a dissonance.
- The leading tone never doubled; the diminished triad never in root
  position (its fifth above the bass is a dissonance). Final chord: the
  bass on the final, the others on the final, fifth or major third
  (ficta in a mode whose third is minor), the third not doubled.
- Hidden fifths and octaves are allowed between inner voices, and in
  the outer voices when the upper one moves by step.
- Parallel fifths and octaves stay forbidden between every pair.
- Unisons allowed on any beat.
- Spacing: adjacent upper voices within an octave; bass to tenor up to
  a twelfth.
- Kinds: the florid voice obeys its kind's dissonance rules against the
  lowest voice *and* against each other voice; the 1st-species voices
  are consonant with the bass and with each other.

### Four voices

As three voices: any chord member may be doubled but the leading tone;
hidden perfects are checked between the outer voices only; incomplete
chords are allowed.

### Modes and cadences

Mode from the cantus' final (D dorian, E phrygian, F lydian, G
mixolydian, A aeolian, C ionian), or given; major = ionian, minor =
aeolian with the raised seventh at cadences. Musica ficta: the raised
leading tone in the clausula (dorian C#, mixolydian F#, aeolian G#,
lydian the B♭ to avoid the tritone); phrygian has no raised leading
tone — its cadence is F → E against D → E. The cadence follows from
the rules: the bass ends on the final, every voice approaches its last
note by step (a bass of 3–4 voices may leap a fourth or fifth), a voice
rising a step to the final rises a semitone, and in two voices the
counterpoint moves against the cantus' last step.

### Soft rules (the penalty)

Leaps (larger ones twice), a climax that is not single, a fourth not
followed by a step, broken ties in the 4th kind, perfect consonances on
downbeats, more than three parallel thirds or sixths in a row. The
penalty only chooses among solutions; it never rejects one.

## Limits

- Voices never cross (Jeppesen allows it briefly); this also keeps "the
  lowest voice" well defined.
- With several florid voices (`:kinds`), a dissonance between two
  florid voices is only allowed as a figure already placed; there is no
  separate treatment of florid against florid.
- Not scored yet: complete triads, contrary motion as such, the
  upward leap from an accented quarter, the 9–8 suspension's rarity,
  slower motion at the start and end of the 5th kind.
- 3rd-species voices start on the downbeat (no quarter rest).
- A cantus is used as given; `check-cantus` only warns.

## Tests

`test/algo_logic_counterpoint_test.clj`: intervals and ficta; each kind
of rule both ways on hand-written examples (a passing tone and a
cambiata accepted, a leap from a dissonance and a suspension resolving
up reported); every voice count × kind solved for a D-dorian cantus and
passed by `check`; seeds reproducing; per-voice kinds; `->mus` parsing
to the right number of notes; the `:species` tree algo. Across the six
modes (D dorian to C ionian), all 90 combinations of 2–4 voices × kinds
1–5 solve, in a few milliseconds (two voices) to about half a second
(four voices, 3rd–5th kind), with the searches in parallel on 16 cores
and JDK 25 (about four times slower on JDK 21).
