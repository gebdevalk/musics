
import mido

# List available output ports
print(mido.get_output_names())

# Open the first available output port
output = mido.open_output()

# Create and send a note on message
msg = mido.Message('note_on', note=60, velocity=64, channel=0)
output.send(msg)

# Wait a bit
import time
time.sleep(0.5)

# Send note off
msg = mido.Message('note_off', note=60, velocity=64, channel=0)
output.send(msg)
