#!/bin/bash
# Quick reconnect: run after restarting qsynth
set -e
VIRMIDI=$(aconnect -l | grep -oP 'client \d+(?=:.*VirMIDI)' | grep -oP '\d+' | head -1)
FLUID=$(aconnect -l | grep -oP 'client \d+(?=:.*FLUID Synth)' | grep -oP '\d+' | head -1)
if [ -n "$VIRMIDI" ] && [ -n "$FLUID" ]; then
    aconnect ${VIRMIDI}:0 ${FLUID}:0
    echo "Connected $VIRMIDI:0 -> $FLUID:0"
else
    echo "Could not find ports. Is qsynth running and snd-virmidi loaded?"
fi
