"""
🎛️ Main MIDI CC List

🎚️ Bank Select & Modulation
CC0 – Bank Select (MSB)
CC1 – Modulation Wheel (often vibrato)
CC2 – Breath Controller
CC3 – Undefined
CC4 – Foot Controller
CC5 – Portamento Time
CC6 – Data Entry (MSB)
CC7 – Channel Volume
CC8 – Balance
CC9 – Undefined

🎛️ More Controllers
CC10 – Pan
CC11 – Expression (fine volume/dynamics)
CC12 – Effect Control 1
CC13 – Effect Control 2
CC14–19 – Undefined

🎚️ General Purpose Controllers
CC16–19 – General Purpose Controllers 1–4

🔘 Switches (On/Off style)
CC64 – Sustain Pedal (Damper)
CC65 – Portamento On/Off
CC66 – Sostenuto
CC67 – Soft Pedal
CC68 – Legato Footswitch
CC69 – Hold 2

🎛️ Sound Controllers (synth shaping)
CC70 – Sound Variation
CC71 – Resonance (Filter Q)
CC72 – Release Time
CC73 – Attack Time
CC74 – Brightness (Filter cutoff)
CC75–79 – Sound Controllers 6–10

🎚️ Effects Sends
CC91 – Reverb Send Level
CC92 – Tremolo Depth
CC93 – Chorus Send Level
CC94 – Detune
CC95 – Phaser Depth

🎛️ Data & NRPN/RPN
CC96 – Data Increment
CC97 – Data Decrement
CC98 – NRPN (LSB)
CC99 – NRPN (MSB
CC100 – RPN (LSB)
CC101 – RPN (MSB)

🔧 Channel Mode Messages

CC120 – All Sound Off
CC121 – Reset All Controllers
CC122 – Local Control On/Off
CC123 – All Notes Off
CC124 – Omni Mode Off
CC125 – Omni Mode On
CC126 – Mono Mode
CC127 – Poly Mode

🧠 Important notes
Values go from 0–127
Some CCs come in pairs:
MSB (0–31) + LSB (32–63) for higher precision
Many CCs are “undefined” → plugins/devices can assign their own meaning

🎹 Most commonly used CCs
If you only remember a few, these matter most:
CC1 – Modulation (vibrato/dynamics)
CC7 – Volume
CC10 – Pan
CC11 – Expression
CC64 – Sustain pedal
CC74 – Filter cutoff / brightness

"""