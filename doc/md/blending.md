# Instruments that blend

Which instruments fuse into one colour, which work with care, and which clash. It is orchestration practice, a rule of thumb taken from two summaries with no source cited, kept as data in `musics.common.music-data` (`orchestral-families`, `blend-pairs`, `blend-factors`, `blends-not-in-general-midi`) and read with `blend-partners` and `blend-grade`.

A blend is graded, and the grade belongs to how, where and how loudly a pair is played, not to the pair for ever. A combination muddy in one register is ethereal in another, and a harsh clash is sometimes the colour a composer wants.

| Grade | Meaning |
|---|---|
| `:harmonious` | the sounds fuse: spectra overlap, attacks are alike, or the families behave alike |
| `:possible` | it works with care: register, balance, articulation, or a role as contrast rather than fusion |
| `:harsh` | prone to clash: spectral opposition, tuning, envelope, masking, a mismatch of dynamics or articulation |

## What makes sounds blend

- **Dark spectra with clean attacks** fuse best; as instruments get brighter or their attacks noisier, the blend falls sharply.
- **Similar timbre** and overlapping spectra. Within a family it is the most natural; bowed strings with woodwinds rate highest across families.
- **Parallel motion, synchronous onsets and consonant intervals** (unison, octave, fifth).
- **Matching attack, decay and dynamics.**
- **Register**: each instrument in the part of its range where the two overlap well.
- **Doubling** (one line in unison or at the octave) is the core technique. A high-timbre instrument with a medium or low one at the octave adds volume and projection without a spectral fight.
- Percussion and plucked strings blend least with sustained tones.

## What makes them clash

Spectral opposition (a bright with a dark instrument in unison), two tuning systems, an envelope conflict (plucked against sustained), two instruments crowding one frequency band, pairs that are both bright or noisy in attack, a soft soloist against a loud mass, a legato line against a percussive one, and the close upper partials of trumpet and trombone, which beat when massed and loud.

## Families

| Family | Instruments (General MIDI) |
|---|---|
| strings | `violin` `viola` `cello` `contrabass` |
| woodwinds | `piccolo` `flute` `oboe` `clarinet` `bassoon` |
| brass | `frenchHorn` `trumpet` `mutedTrumpet` `trombone` `tuba` |

Two of one family lose nothing of their quality and gain power; the blend is stable, the variety a little less. The horn is a bridge: it blends best with other brass when softer than the trumpet and trombone, and joins brass to strings and woodwinds.

## The pairings

### Harmonious

| Pair | Why |
|---|---|
| strings + woodwinds | the most forgiving; the woodwinds soften the strings' bite (heard best placed higher or louder) |
| flute + oboe, unison | the flute smooths the oboe's nasal quality |
| oboe + clarinet, unison | a mysterious colour neither makes alone |
| violin + trumpet, viola + horn, cello + horn, unison | the string and brass unisons Rimsky-Korsakov recommended; cello + horn is soft and creamy |
| flute + violin, unison | sheen, a floating quality |
| flute + harp | airy, delicate, dreamlike |
| oboe + muted brass | a softened metallic edge with reedy colour (the muted trumpet stands for the muted trombone) |
| guitar + piano, violin or cello | harmonic and melodic interplay |
| clarinet + brass | the clarinet's dark quality softens a brass section's edge (the bass clarinet in practice) |

### Possible

| Pair | Care needed |
|---|---|
| strings + brass, unison | never as perfect as strings and woodwinds, except a horn on a violin line in the right register |
| flute + clarinet, unison | removes the oboe's bite, but the clarinet dominates; the flute in a non-brilliant register |
| oboe + bassoon, octave | the bassoon cannot play the oboe's range, so the octave is full and sonorous |
| piano + strings or woodwinds | the piano refuses to blend; contrast in chamber music, not fusion |
| piano + clarinet | a dialogue of melody and harmony, as contrast |
| harpsichord + strings | sharp attack against sustained strings: give it a rhythmic role |
| clarinet + viola | overlapping tessituras; close parallel motion in the shared middle register is muddy |
| woodwinds + horn, unison | can harm the melodic clarity |

### Harsh

| Pair | Why |
|---|---|
| piccolo + clarinet (bass clarinet), unison | bright against dark: a hybrid with two spectral peaks |
| flute + loud brass | a soft soloist is masked, not blended |

## Not in General MIDI

Two clashes of the practice have no sound in General MIDI and nothing stands for them, so they are kept apart in `blends-not-in-general-midi` and `blend-grade` does not report them:

- **Bagpipe + piano or organ**: just intonation against equal temperament. Every General MIDI sound is in equal temperament.
- **Oboe over col legno strings**: General MIDI has tremolo and pizzicato strings, no col legno.

Two instruments of the practice have no General MIDI sound and are replaced: the bass clarinet by the clarinet and the muted trombone by the muted trumpet (`:substitute` and `:note` in the entry). Whether brightness and attack show depends on the soundfont.

## In Clojure

```clojure
(require '[musics.common.music-data :as data])

(data/blend-grade :piccolo :clarinet)   ; => :harsh
(data/blend-grade "flute" "oboe")       ; => :harmonious; nil where the practice says nothing
(data/blend-partners :flute)            ; the family, the family's others, each pairing with its partners
```

An instrument is any name `program-of` reads (`:frenchHorn`, `"flute"`, `"fl"`). A pair that names the instruments wins over one that names their family; two of one family are harmonious.
