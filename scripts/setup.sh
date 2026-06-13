#!/bin/bash
# musics — one-time system setup
# Run once after cloning:  ./scripts/setup.sh

set -e

echo "=== musics setup ==="

# 1. Install packages (skip if already installed)
echo ""
echo "[1/3] Checking packages..."
MISSING=""
dpkg -l qsynth 2>/dev/null | grep -q '^ii' || MISSING="$MISSING qsynth"
dpkg -l fluid-soundfont-gm 2>/dev/null | grep -q '^ii' || MISSING="$MISSING fluid-soundfont-gm"

if [ -n "$MISSING" ]; then
    echo "  Installing:$MISSING"
    sudo apt install -y $MISSING
else
    echo "  qsynth + fluid-soundfont-gm already installed."
fi

# 2. Load snd-virmidi kernel module (permanent)
echo ""
echo "[2/3] Setting up snd-virmidi..."
if lsmod | grep -q virmidi; then
    echo "  Already loaded."
else
    echo "  Loading now..."
    sudo modprobe snd-virmidi
    echo "  Loaded."
fi

if [ ! -f /etc/modules-load.d/virmidi.conf ]; then
    echo "  Adding to /etc/modules-load.d/ for auto-load on boot..."
    echo snd-virmidi | sudo tee /etc/modules-load.d/virmidi.conf > /dev/null
    echo "  Done."
else
    echo "  Already in /etc/modules-load.d/."
fi

# 3. Connect VirMIDI to Fluidsynth
echo ""
echo "[3/3] Connecting MIDI ports..."
# Find the first VirMIDI sequencer port
VIRMIDI=$(aconnect -l | grep -oP 'client \d+(?=:.*VirMIDI)' | grep -oP '\d+' | head -1)
FLUID=$(aconnect -l | grep -oP 'client \d+(?=:.*FLUID Synth)' | grep -oP '\d+' | head -1)

if [ -z "$VIRMIDI" ]; then
    echo "  WARNING: No VirMIDI port found. Is snd-virmidi loaded?"
elif [ -z "$FLUID" ]; then
    echo "  WARNING: No FLUID Synth port found. Is qsynth running?"
else
    if aconnect -l | grep -q "client $VIRMIDI:.*Connecting To: $FLUID"; then
        echo "  Already connected: $VIRMIDI:0 -> $FLUID:0"
    else
        aconnect ${VIRMIDI}:0 ${FLUID}:0
        echo "  Connected: $VIRMIDI:0 -> $FLUID:0"
    fi
fi

echo ""
echo "=== Setup complete ==="
echo ""
echo "Ensure qsynth is running. Then start a REPL:"
echo "  lein repl"
echo ""
echo "  user=> (require '[output.midi.midi-live :as live])"
echo "  user=> (def rcv (live/open-receiver))"
echo "  user=> (live/play-phrase rcv [[60 300 80] [64 300 80] [67 500 80]])"
