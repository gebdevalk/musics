# musics — Setup Guide

## Automated setup (recommended)

```bash
./scripts/setup.sh
```

This single command handles everything below. After it completes, start a REPL:

```clojure
(require '[output.midi.midi-live :as live])
(def rcv (live/open-receiver))   ;; auto-connects VirMIDI -> Fluidsynth
(live/play-phrase rcv [[60 300 80] [64 300 80] [67 500 80]])
```

`open-receiver` automatically finds the VirMIDI port, finds the Fluidsynth
port, and runs `aconnect` if they're not already connected. No manual port
numbers needed.

After restarting qsynth, connections may need re-establishing:

```bash
./scripts/reconnect.sh
```

## Manual setup (what the scripts do)

The Clojure project is self-contained (only `org.clojure/clojure 1.12.0`).
Audio output requires these system components:

### 1. Fluidsynth (via qsynth)

```bash
sudo apt install qsynth fluid-soundfont-gm
```

**qsynth** is a GUI wrapper around Fluidsynth. The package
`fluid-soundfont-gm` provides the General MIDI SoundFont
(`FluidR3_GM.sf2`) used for instrument samples.

Key configuration (set in the qsynth GUI or `~/.config/rncbc.org/Qsynth.conf`):

| Setting | Value | Notes |
|---|---|---|
| MIDI Driver | `alsa_seq` | Exposes Fluidsynth as an ALSA sequencer client |
| Audio Driver | `alsa` | PCM output via ALSA (routed through PipeWire/PulseAudio) |
| MIDI Channels | 16 | Full General MIDI |
| SoundFont | `FluidR3_GM.sf2` | Instrument sample library |
| MIDI Auto-connect | true | Automatically connects to new MIDI ports |

### 2. Virtual MIDI kernel module

`snd-virmidi` creates virtual raw MIDI ports that Java's `javax.sound.midi`
can open directly. These are bridged to the ALSA sequencer so Fluidsynth
receives the MIDI data.

```bash
# Once: load now
sudo modprobe snd-virmidi

# Permanent: auto-load on boot
echo snd-virmidi | sudo tee /etc/modules-load.d/virmidi.conf
```

After loading, four ports appear in `aconnect -l`:

```
client 24: 'Virtual Raw MIDI 2-0' [type=kernel,card=2]
    0 'VirMIDI 2-0     '
client 25: 'Virtual Raw MIDI 2-1' [type=kernel,card=2]
    0 'VirMIDI 2-1     '
client 26: 'Virtual Raw MIDI 2-2' [type=kernel,card=2]
    0 'VirMIDI 2-2     '
client 27: 'Virtual Raw MIDI 2-3' [type=kernel,card=2]
    0 'VirMIDI 2-3     '
```

Java sees these as `VirMIDI [hw:2,0,0]` through `VirMIDI [hw:2,3,15]`
(16 channels × 4 ports = 64 total).

### 3. Connect VirMIDI to Fluidsynth

```bash
aconnect 24:0 128:0
```

If `MidiAutoConnect=true` is set in qsynth, this happens automatically.
Otherwise run after each boot, or add to a startup script.

## MIDI input (midi-through / record-midi)

```bash
./scripts/setup-midi-in.sh
```

Lists the MIDI input ports ALSA currently sees, so you can confirm your
keyboard shows up and note its name.

Unlike MIDI *output* above, a real USB MIDI keyboard needs **no kernel
module** — it's a standard USB MIDI class-compliant device, ALSA already
sees it once plugged in. No `snd-virmidi`, no `aconnect` wiring of your
own to do; `input.midi/open-midi` handles routing internally.

```clojure
(require '[input.midi :as midi])

;; Opens the device and immediately starts midi-through: play the
;; keyboard, hear it live through the same Fluidsynth setup above.
(midi/open-midi "your-keyboard-name")   ;; or (midi/open-midi) for a GUI picker

(midi/close-midi)   ;; stops midi-through and releases the device
```

`record-midi` (`input.midi-record`) builds on the same open input to
record a performance and quantize it into musics-DSL text — see
`input.midi-record`'s own ns docstring, or the "Record MIDI" panel in
`(musics/gui)`.

## MIDI output flow

```
Clojure (midi_live.clj)
  │  ShortMessage NOTE_ON / NOTE_OFF
  ▼
javax.sound.midi.Receiver
  │  raw MIDI bytes
  ▼
/dev/snd/midiC2D0          (raw MIDI device, kernel)
  │
  ▼
snd-virmidi                 (kernel module)
  │  ALSA sequencer events
  ▼
aconnect                    (sequencer routing)
  │
  ▼
FLUID Synth (qsynth)        (ALSA client 128:0)
  │  PCM audio
  ▼
PipeWire / ALSA             (audio server)
  │
  ▼
Hardware DAC → speakers
```

## Clojure namespaces

| Namespace | File | Purpose |
|---|---|---|
| `output.midi.midi-live` | `src/output/midi/midi_live.clj` | Real-time MIDI, auto-connects to Fluidsynth |
| `output.midi.midi-file` | `src/output/midi/midi_file.clj` | MIDI file generation + playback via `aplaymidi` |
| `input.midi` | `src/input/midi.clj` | Real-time MIDI input (overtone/midi-clj) + midi-through |
| `input.midi-record` | `src/input/midi_record.clj` | Records + quantizes a performance into musics text |

### Real-time usage (REPL)

```clojure
(require '[output.midi.midi-live :as live])

;; Auto-connects VirMIDI -> Fluidsynth
(def rcv (live/open-receiver))

;; Play a C major phrase
(live/play-phrase rcv
  [[60 300 80] [62 300 80] [64 300 80] [65 300 80]
   [67 300 80] [69 300 80] [71 300 80] [72 500 80]])

;; Play a chord
(live/play-chord rcv 0 [60 64 67] 80 2000)
```

### File-based usage (REPL)

```clojure
(require '[output.midi.midi-file :as mf])

(def trk (mf/pitches->track [[60 240] [64 240] [67 480]]))
(mf/play (mf/sequence [trk]))
```

## Scripts

| Script | Purpose |
|---|---|
| `scripts/setup.sh` | Full system setup for MIDI OUTPUT (packages, kernel module, port connection) |
| `scripts/reconnect.sh` | Reconnect VirMIDI → Fluidsynth after qsynth restart |
| `scripts/setup-midi-in.sh` | List MIDI INPUT ports (no kernel-level setup needed) |
