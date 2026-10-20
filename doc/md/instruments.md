# Instruments and drums

Every General MIDI instrument by program, name and short name, and the drums and kits. An instrument is set for a line with `!i:` or for one note with `\i:`, by any of the three: `!i:32`, `!i:acousticBass` and `!i:acbs` are the same. A name reads in any case, and the start of one finds it when it can only mean one instrument (`!i:trump`, `!i:pizz`, `!i:glock`); one that could mean several is an error naming them (`viol`: viola or violin). In mu, `"viol" find-instrument`, `"cr" find-drum` and `"s" find-kit` list what a start can mean, one per line (`""` lists all); at the Clojure REPL `(mu/find-instrument "viol")`. At mu's prompt, Tab completes the name being written after `!i:`, `\i:`, `x8\` or `!kit:`. A list plays several at once (`!i:40,73`).

## Instruments

### Piano (0–7)

| # | name | short |
|---:|---|---|
| 0 | `acousticGrand` | `acgd` |
| 1 | `brightAcoustic` | `brac` |
| 2 | `electricGrand` | `elgr` |
| 3 | `honkyTonk` | `hnky` |
| 4 | `electricPiano1` | `ep1` |
| 5 | `electricPiano2` | `ep2` |
| 6 | `harpsichord` | `hpsd` |
| 7 | `clavinet` | `clav` |

### Chromatic percussion (8–15)

| # | name | short |
|---:|---|---|
| 8 | `celesta` | `cel` |
| 9 | `glockenspiel` | `glck` |
| 10 | `musicBox` | `mbox` |
| 11 | `vibraphone` | `vib` |
| 12 | `marimba` | `mar` |
| 13 | `xylophone` | `xyl` |
| 14 | `tubularBells` | `tbb` |
| 15 | `dulcimer` | `dul` |

### Organ (16–23)

| # | name | short |
|---:|---|---|
| 16 | `drawbarOrgan` | `dror` |
| 17 | `percussiveOrgan` | `pcor` |
| 18 | `rockOrgan` | `rkor` |
| 19 | `churchOrgan` | `chor` |
| 20 | `reedOrgan` | `rdor` |
| 21 | `accordion` | `acc` |
| 22 | `harmonica` | `harm` |
| 23 | `tangoAccordion` | `tngac` |

### Guitar (24–31)

| # | name | short |
|---:|---|---|
| 24 | `acousticGuitarNylon` | `acgtn` |
| 25 | `acousticGuitarSteel` | `acgts` |
| 26 | `electricGuitarJazz` | `elgtj` |
| 27 | `electricGuitarClean` | `elgtc` |
| 28 | `electricGuitarMuted` | `elgtm` |
| 29 | `overdrivenGuitar` | `ovgt` |
| 30 | `distortionGuitar` | `dsgt` |
| 31 | `guitarHarmonics` | `gthr` |

### Bass (32–39)

| # | name | short |
|---:|---|---|
| 32 | `acousticBass` | `acbs` |
| 33 | `electricBassFinger` | `elbsf` |
| 34 | `electricBassPick` | `elbsp` |
| 35 | `fretlessBass` | `frbs` |
| 36 | `slapBass1` | `slb1` |
| 37 | `slapBass2` | `slb2` |
| 38 | `synthBass1` | `syb1` |
| 39 | `synthBass2` | `syb2` |

### Strings (40–47)

| # | name | short |
|---:|---|---|
| 40 | `violin` | `vln` |
| 41 | `viola` | `vla` |
| 42 | `cello` | `clo` |
| 43 | `contrabass` | `cbs` |
| 44 | `tremoloStrings` | `trst` |
| 45 | `pizzicatoStrings` | `pzst` |
| 46 | `orchestralHarp` | `ohp` |
| 47 | `timpani` | `tmp` |

### Ensemble (48–55)

| # | name | short |
|---:|---|---|
| 48 | `stringEnsemble1` | `ste1` |
| 49 | `stringEnsemble2` | `ste2` |
| 50 | `synthStrings1` | `sys1` |
| 51 | `synthStrings2` | `sys2` |
| 52 | `choirAahs` | `chah` |
| 53 | `voiceOohs` | `vooh` |
| 54 | `synthVoice` | `syvo` |
| 55 | `orchestraHit` | `orht` |

### Brass (56–63)

| # | name | short |
|---:|---|---|
| 56 | `trumpet` | `tpt` |
| 57 | `trombone` | `tbn` |
| 58 | `tuba` | `tba` |
| 59 | `mutedTrumpet` | `mtpt` |
| 60 | `frenchHorn` | `fhn` |
| 61 | `brassSection` | `brsc` |
| 62 | `synthBrass1` | `sybr1` |
| 63 | `synthBrass2` | `sybr2` |

### Reed (64–71)

| # | name | short |
|---:|---|---|
| 64 | `sopranoSax` | `spsx` |
| 65 | `altoSax` | `alsx` |
| 66 | `tenorSax` | `tnsx` |
| 67 | `baritoneSax` | `brsx` |
| 68 | `oboe` | `ob` |
| 69 | `englishHorn` | `enhn` |
| 70 | `bassoon` | `bsn` |
| 71 | `clarinet` | `cl` |

### Pipe (72–79)

| # | name | short |
|---:|---|---|
| 72 | `piccolo` | `pic` |
| 73 | `flute` | `fl` |
| 74 | `recorder` | `rec` |
| 75 | `panFlute` | `pnfl` |
| 76 | `blownBottle` | `bnbt` |
| 77 | `shakuhachi` | `skh` |
| 78 | `whistle` | `whs` |
| 79 | `ocarina` | `oca` |

### Synth lead (80–87)

| # | name | short |
|---:|---|---|
| 80 | `lead1` | `ld1` |
| 81 | `lead2` | `ld2` |
| 82 | `lead3` | `ld3` |
| 83 | `lead4` | `ld4` |
| 84 | `lead5` | `ld5` |
| 85 | `lead6` | `ld6` |
| 86 | `lead7` | `ld7` |
| 87 | `lead8` | `ld8` |

### Synth pad (88–95)

| # | name | short |
|---:|---|---|
| 88 | `pad1` | `pd1` |
| 89 | `pad2` | `pd2` |
| 90 | `pad3` | `pd3` |
| 91 | `pad4` | `pd4` |
| 92 | `pad5` | `pd5` |
| 93 | `pad6` | `pd6` |
| 94 | `pad7` | `pd7` |
| 95 | `pad8` | `pd8` |

### Synth effects (96–103)

| # | name | short |
|---:|---|---|
| 96 | `fx1` | `fx1` |
| 97 | `fx2` | `fx2` |
| 98 | `fx3` | `fx3` |
| 99 | `fx4` | `fx4` |
| 100 | `fx5` | `fx5` |
| 101 | `fx6` | `fx6` |
| 102 | `fx7` | `fx7` |
| 103 | `fx8` | `fx8` |

### Ethnic (104–111)

| # | name | short |
|---:|---|---|
| 104 | `sitar` | `sit` |
| 105 | `banjo` | `bnj` |
| 106 | `shamisen` | `smi` |
| 107 | `koto` | `kot` |
| 108 | `kalimba` | `kmb` |
| 109 | `bagpipe` | `bgp` |
| 110 | `fiddle` | `fdl` |
| 111 | `shanai` | `shn` |

### Percussive (112–119)

| # | name | short |
|---:|---|---|
| 112 | `tinkleBell` | `tnbl` |
| 113 | `agogo` | `ago` |
| 114 | `steelDrums` | `stdm` |
| 115 | `woodblock` | `wblk` |
| 116 | `taikoDrum` | `tkdm` |
| 117 | `melodicTom` | `mltm` |
| 118 | `synthDrum` | `sydm` |
| 119 | `reverseCymbal` | `rvcy` |

### Sound effects (120–127)

| # | name | short |
|---:|---|---|
| 120 | `guitarFretNoise` | `gtfr` |
| 121 | `breathNoise` | `brns` |
| 122 | `seashore` | `sea` |
| 123 | `birdTweet` | `brd` |
| 124 | `telephoneRing` | `tel` |
| 125 | `helicopter` | `hel` |
| 126 | `applause` | `apl` |
| 127 | `gunshot` | `gun` |

## Drums

A drum is `x` with its key of the kit or its name: `x8\36`, `x8\kick`, `x8\bd1`; a bare `x` repeats the last one, and a list sounds several at once (`x8\kick,clap`). Drum names, kit names too, are found from their start the same way (`x8\cow`, `x8\closed`, `!kit:elec`). Drum short names are their own: `vib` is the vibraslap here, the vibraphone as an instrument.

| key | drum | short | also |
|---:|---|---|---|
| 35 | Acoustic Bass Drum | `bda` |  |
| 36 | Bass Drum 1 | `bd1` | `bd` `kick` |
| 37 | Side Stick | `ss` |  |
| 38 | Acoustic Snare | `sna` | `sd` `snare` |
| 39 | Hand Clap | `hcp` | `clap` |
| 40 | Electric Snare | `sne` |  |
| 41 | Low Floor Tom | `ttfl` |  |
| 42 | Closed Hi-Hat | `hhc` | `hat` `hh` `hihat` |
| 43 | High Floor Tom | `ttfh` |  |
| 44 | Pedal Hi-Hat | `hhp` |  |
| 45 | Low Tom | `ttl` | `tom` `tom-lo` |
| 46 | Open Hi-Hat | `hho` |  |
| 47 | Low-Mid Tom | `ttml` | `tom-mid` |
| 48 | Hi-Mid Tom | `ttmh` |  |
| 49 | Crash Cymbal 1 | `cr1` | `crash` |
| 50 | High Tom | `tth` | `tom-hi` |
| 51 | Ride Cymbal 1 | `rd1` | `ride` |
| 52 | Chinese Cymbal | `chn` |  |
| 53 | Ride Bell | `rdb` |  |
| 54 | Tambourine | `tam` | `tamb` `tambourine` |
| 55 | Splash Cymbal | `spl` |  |
| 56 | Cowbell | `cow` | `cowbell` |
| 57 | Crash Cymbal 2 | `cr2` |  |
| 58 | Vibraslap | `vib` |  |
| 59 | Ride Cymbal 2 | `rd2` |  |
| 60 | Hi Bongo | `bgh` | `bongo` `bongo-hi` |
| 61 | Low Bongo | `bgl` | `bongo-lo` |
| 62 | Mute Hi Conga | `cghm` |  |
| 63 | Open Hi Conga | `cgho` | `conga` `conga-hi` |
| 64 | Low Conga | `cgl` | `conga-lo` |
| 65 | High Timbale | `tbh` |  |
| 66 | Low Timbale | `tbl` |  |
| 67 | High Agogo | `agh` |  |
| 68 | Low Agogo | `agl` |  |
| 69 | Cabasa | `cab` |  |
| 70 | Maracas | `mar` | `maracas` |
| 71 | Short Whistle | `whs` | `whistle` |
| 72 | Long Whistle | `whl` |  |
| 73 | Short Guiro | `grs` |  |
| 74 | Long Guiro | `grl` |  |
| 75 | Claves | `clv` | `claves` |
| 76 | Hi Wood Block | `wbh` |  |
| 77 | Low Wood Block | `wbl` |  |
| 78 | Mute Cuica | `cum` |  |
| 79 | Open Cuica | `cuo` |  |
| 80 | Mute Triangle | `trim` |  |
| 81 | Open Triangle | `trio` |  |

## Drum kits

`!kit:` picks the kit, the program of the drum channel; what each sounds like depends on the soundfont (General MIDI 1 has the standard kit only).

| kit | program |
|---|---:|
| `standard` | 0 |
| `room` | 8 |
| `power` | 16 |
| `electronic` | 24 |
| `tr808` | 25 |
| `jazz` | 32 |
| `brush` | 40 |
| `orchestra` | 48 |
| `sfx` | 56 |
