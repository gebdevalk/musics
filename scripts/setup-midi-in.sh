#!/bin/bash
# musics — MIDI INPUT check (midi-through / record-midi)
#
# Unlike scripts/setup.sh (MIDI OUTPUT), a real USB MIDI keyboard needs
# no kernel module here -- it's a standard USB MIDI class-compliant
# device, ALSA already sees it once it's plugged in. This script just
# lists what ALSA currently sees as a MIDI INPUT (source) port, so you
# can confirm your keyboard shows up and note its name for
# input.midi/open-midi's own name-substring argument.

echo "=== musics MIDI input check ==="
echo ""
echo "MIDI input (source) ports ALSA currently sees:"
echo ""
aconnect -i
echo ""
echo "If your keyboard isn't listed above, check it's plugged in and"
echo "powered on, then re-run this script -- USB MIDI keyboards need no"
echo "further setup on Linux (no kernel module, unlike scripts/setup.sh's"
echo "snd-virmidi for MIDI OUTPUT)."
echo ""
echo "Once it's listed, start a REPL and open it by a unique substring"
echo "of its name (the quoted name after each \"client N:\" above):"
echo ""
echo "  user=> (require '[input.midi :as midi])"
echo "  user=> (midi/open-midi \"your-keyboard-name\")"
echo ""
echo "This immediately starts midi-through (play the keyboard, hear it"
echo "through the same Fluidsynth setup scripts/setup.sh configured)."
echo "(midi/close-midi) stops it. See doc/setup.md's 'MIDI input' section"
echo "and input.midi's own ns docstring for record-midi on top of this."
