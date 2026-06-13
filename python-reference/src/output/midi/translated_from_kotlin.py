import asyncio
from abc import ABC
from collections import defaultdict
import mido

class Performer(ABC):
    pass

# --- Setup MIDI ---
class Orchestra:
    def __init__(self):
        self.port = mido.open_output()  # default MIDI output
        self.performers = [Performer(self, 0, ch) for ch in range(16)]
        self.DRUM_CHANNEL = 9
        self.sounding_note_count = defaultdict(lambda: defaultdict(int))

    def start(self):
        pass  # nothing required for mido

    def stop(self):
        self.port.close()

    # ---------------- Performer ----------------
    class Performer:
        def __init__(self, orchestra, instrument=0, channel_number=0):
            self.orchestra = orchestra
            self.channel_number = channel_number
            self.instrument = instrument
            self.set_instrument(instrument)

        def set_channel(self, value):
            self.channel_number = value
            self.set_instrument(self.instrument)

        def set_instrument(self, program):
            self.instrument = program
            msg = mido.Message(
                'program_change',
                program=program,
                channel=self.channel_number
            )
            self.orchestra.port.send(msg)

        async def play(self, part):
            if isinstance(part, list):  # sequence
                for p in part:
                    await self.play(p)

            elif isinstance(part, dict):
                if part["type"] == "leaf":
                    await self.play_leaf(part)
                elif part["type"] == "polyphonic":
                    await asyncio.gather(
                        *(self.play(p) for p in part["parts"])
                    )

        async def play_leaf(self, leaf):
            delay = leaf["duration"]

            # start note_off task
            asyncio.create_task(
                self.note_off_after_delay(leaf, delay)
            )

            for pitch in leaf["pitches"]:
                self.note_on(pitch, leaf.get("volume", 64))

            await asyncio.sleep(delay / 1000)

        async def note_off_after_delay(self, leaf, delay):
            await asyncio.sleep(delay / 1000)
            for pitch in leaf["pitches"]:
                self.note_off(pitch)

        def note_on(self, pitch, volume):
            self.register(pitch)
            msg = mido.Message(
                'note_on',
                note=pitch,
                velocity=volume,
                channel=self.channel_number
            )
            self.orchestra.port.send(msg)

        def note_off(self, pitch):
            if self.sounding_count(pitch) == 1:
                msg = mido.Message(
                    'note_off',
                    note=pitch,
                    velocity=0,
                    channel=self.channel_number
                )
                self.orchestra.port.send(msg)
            self.unregister(pitch)

        def register(self, pitch):
            if pitch > 0:
                self.orchestra.sounding_note_count[self.channel_number][pitch] += 1

        def unregister(self, pitch):
            count = self.orchestra.sounding_note_count[self.channel_number][pitch]
            if count > 1:
                self.orchestra.sounding_note_count[self.channel_number][pitch] -= 1
            else:
                del self.orchestra.sounding_note_count[self.channel_number][pitch]

        def sounding_count(self, pitch):
            return self.orchestra.sounding_note_count[self.channel_number].get(pitch, 0)

    # ---------------- Ensemble ----------------
    class Ensemble(Performer):
        def __init__(self, orchestra, performers):
            super().__init__(orchestra)
            self.performers = performers

            for i, performer in enumerate(self.performers):
                performer.set_channel(i)

        async def play(self, part):
            if part["type"] == "polyphonic":
                await asyncio.gather(*[
                    self.performers[i].play(p)
                    for i, p in enumerate(part["parts"])
                ])



# ---------------- Example Usage ----------------

async def main():
    orch = Orchestra()
    orch.start()

    performer = orch.Performer(orch, instrument=0, channel_number=0)

    # simple test note
    part = {
        "type": "leaf",
        "pitches": [60, 64, 67],  # C major chord
        "duration": 1000,
        "volume": 80
    }

    await performer.play(part)

    await asyncio.sleep(1)
    orch.stop()


if __name__ == "__main__":
    asyncio.run(main())