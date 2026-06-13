from mido.backends import rtmidi
import rtmidi

# Create a MIDI output port
midiout = rtmidi.MidiOut()
available_ports = midiout.get_ports()

if available_ports:
    # Open the first available port
    midiout.open_port(0)

    # Send a MIDI note on (channel 1, note 60, velocity 100)
    note_on = [0x90, 60, 100]  # 0x90 = note on, channel 1
    midiout.send_message(note_on)

    # Wait a bit
    import time

    time.sleep(0.5)

    # Send a MIDI note off (note 60, velocity 0)
    note_off = [0x80, 60, 0]  # 0x80 = note off, channel 1
    midiout.send_message(note_off)

    # Close the port
    midiout.close_port()
else:
    print("No MIDI output ports available")
