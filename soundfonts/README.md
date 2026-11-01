# soundfonts

mu plays through Gervill, the JVM's own synthesizer, which needs a General
MIDI SoundFont (`.sf2`) to sound like instruments and not like a thin
emergency set. mu looks for one in this folder first, then in
`/usr/share/sounds/sf2` and `~/Music/sf`, and loads
`FluidR3_GM.sf2` when it finds it (`soundfont` and `soundfonts` in mu pick
and list others).

## What is here

**`FluidR3_GM.sf2`** (148 MB), the "Fluid (R3)" General MIDI bank by Frank
Wen, under the **MIT license**: free to use, copy, modify and redistribute,
commercially too, as long as its copyright and permission notice stay with
it, which `FluidR3_GM-LICENSE.txt` is. It is the bank Debian and Ubuntu ship
(`apt install fluid-soundfont-gm`, to `/usr/share/sounds/sf2`), so a machine
that has it needs nothing here.

The `.sf2` itself is not in git (it is git-ignored: 148 MB would weigh on
every clone). It belongs in a release download next to the jar, and in a
checkout you get it from the package above or copy it here. `SHA256SUMS`
checks it:

```bash
cd soundfonts && sha256sum -c SHA256SUMS
```

## Why this one

| SoundFont | License | Size | |
|---|---|---|---|
| **FluidR3_GM** | MIT, authorship documented | 148 MB | chosen: a standard license, a known origin, good all-round General MIDI, already what mu looks for |
| MuseScore_General | MIT, built on FluidR3 (its notices must be kept) | 208 MB (a 36 MB `.sf3` Gervill cannot load) | the same base, larger |
| GeneralUser GS | its own license v2.0: free to use and bundle, but the author writes he cannot be 100% sure where every sample came from | about 30 MB | the best small bank; its license is not a standard one and the provenance caveat is the author's own |
| TimGM6mb | GPL-2 | 5.7 MB | free and small, but GPL |

The MIT and GeneralUser GS texts were read from their sources
(Debian's copyright file for FluidR3_GM, MuseScore's license file, the
ScanCode license database for GeneralUser GS); a download of any of them
should be checked against its own license file.

## Your own

Put any other `.sf2` here (or in `~/Music/sf`): `soundfonts` lists them
and `soundfont` loads one by number or path.
