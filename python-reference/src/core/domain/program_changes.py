"""
ProgramChanges.py

🎛️ What is Program Change (PC) in MIDI?
Program Change is a MIDI message used to switch sounds (presets/patches) on an instrument.
👉 Example: telling a synth to change from a piano to a string sound.

🎹 How it works
Each sound = a program number
Range: 0–127 (that’s 128 possible presets per bank)
Sent instantly—no continuous values like CC

🔢 Example
Program Change 0 → Acoustic Piano
Program Change 40 → Violin
Program Change 81 → Lead Synth

(These follow the General MIDI (GM) standard, but many plugins use their own mappings.)

🔁 Program Change vs Control Change
Feature	Program Change	Control Change (CC)
Purpose	Change preset/sound	Modify parameters
Type	Single message	Continuous controller
Value range	0–127	0–127
Example	Piano → Strings	Mod wheel, volume, filter

🧠 Important detail: Banks
Since 128 sounds isn’t enough for modern instruments:
Bank Select (CC0 + CC32) is used before Program Change
This lets you access thousands of sounds

👉 Flow:
Send CC0 / CC32 (choose bank)
Send Program Change (choose sound in that bank)

🎯 Real-world uses
Switching presets live on stage
Changing sounds mid-song automatically
Controlling external hardware synths

⚠️ Things to watch out for
Some DAWs (like Ableton Live) hide or simplify Program Change
Different instruments map program numbers differently
Off-by-one confusion:
Some systems count 0–127
Others show 1–128

🧩 Simple analogy
Program Change = selecting a new instrument
CC = adjusting knobs on that instrument
"""