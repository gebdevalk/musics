import asyncio
from dataclasses import dataclass

import rtmidi
from fractions import Fraction
from typing import Union, List, Tuple, Dict, TypeAlias


# ----- Your existing composite structure (synchronous) -----
@dataclass
class Note:
    pitch: int
    dur: Fraction
    vel: int = 64


Part: TypeAlias = Union[Note, Tuple[str, Fraction], List[int], List['Part'], Dict[str, List['Part']]]


# ----- NEW: Async player that walks the structure -----
class MidiPlayer:
    def __init__(self, bpm: int = 120):
        self.bpm = bpm
        self.midiout = rtmidi.MidiOut()
        self.seconds_per_beat = 60.0 / bpm

        # Open first available port
        ports = self.midiout.get_ports()
        if ports:
            self.midiout.open_port(0)
            print(f"Opened MIDI port: {ports[0]}")
        else:
            print("No MIDI ports, using virtual port")
            self.midiout.open_virtual_port("Python Player")

    async def play_note(self, pitch: int, velocity: int, duration: Fraction, channel: int = 0):
        """Play a single note asynchronously"""
        duration_sec = float(duration) * self.seconds_per_beat

        # Note on
        self.midiout.send_message([0x90 | channel, pitch, velocity])

        # Wait for duration
        await asyncio.sleep(duration_sec)

        # Note off
        self.midiout.send_message([0x80 | channel, pitch, 0])

    async def play_part(self, part: Part, start_delay: float = 0):
        """Recursively play any musical part"""
        if start_delay > 0:
            await asyncio.sleep(start_delay)

        match part:
            case Note(pitch=p, dur=d, vel=v):
                await self.play_note(p, v, d)

            case ('rest', d):
                await asyncio.sleep(float(d) * self.seconds_per_beat)

            case list() if all(isinstance(x, int) for x in part):
                # Chord - play all notes simultaneously
                dur = Fraction(1, 4)  # Default duration
                tasks = [self.play_note(p, 64, dur) for p in part]
                await asyncio.gather(*tasks)

            case list():
                # Sequence - play one after another
                for element in part:
                    await self.play_part(element)

            case dict():
                # Polyphonic - play all parts simultaneously
                tasks = [self.play_part(subpart) for subpart in part.values()]
                await asyncio.gather(*tasks)

    async def play_with_countdown(self, part: Part, delay_beats: Fraction = Fraction(0)):
        """Play after a delay (for synchronization)"""
        delay_sec = float(delay_beats) * self.seconds_per_beat
        await asyncio.sleep(delay_sec)
        await self.play_part(part)

    def close(self):
        self.midiout.close_port()

# subclass with timing not tested!
class ScheduledMidiPlayer(MidiPlayer):
    async def play_score_scheduled(self, part: Part):
        """Convert notes to scheduled events for precise timing"""
        # First, flatten the structure to absolute times
        events = []  # (time_sec, type, pitch, vel)

        def collect_events(p: Part, current_time: Fraction = Fraction(0)):
            match p:
                case Note(pitch, dur, vel):
                    start_sec = float(current_time) * self.seconds_per_beat
                    end_sec = float(current_time + dur) * self.seconds_per_beat
                    events.append(('on', start_sec, pitch, vel))
                    events.append(('off', end_sec, pitch, 0))

                case ('rest', dur):
                    pass  # Just advances time

                case list() if all(isinstance(x, int) for x in p):
                    for pitch in p:
                        start_sec = float(current_time) * self.seconds_per_beat
                        end_sec = float(current_time + Fraction(1, 4)) * self.seconds_per_beat
                        events.append(('on', start_sec, pitch, 64))
                        events.append(('off', end_sec, pitch, 0))

                case list():
                    t = current_time
                    for element in p:
                        collect_events(element, t)
                        t += duration(element)  # Need duration function

                case dict():
                    for subpart in p.values():
                        collect_events(subpart, current_time)

        collect_events(part)
        events.sort(key=lambda e: e[1])  # Sort by time

        # Play scheduled events
        start_time = asyncio.get_event_loop().time()
        for event_type, event_time, pitch, vel in events:
            delay = event_time - (asyncio.get_event_loop().time() - start_time)
            if delay > 0:
                await asyncio.sleep(delay)

            if event_type == 'on':
                self.midiout.send_message([0x90, pitch, vel])
            else:
                self.midiout.send_message([0x80, pitch, 0])


# ----- Async context manager for clean setup/teardown -----
class AsyncMidiSession:
    def __init__(self, bpm: int = 120):
        self.bpm = bpm
        self.player = None

    async def __aenter__(self):
        print("Playing MidiPlayer...")
        self.player = MidiPlayer(self.bpm)
        return self.player

    # async def __aenter__(self):
    #     print("Playing ScheduledMidiPlayer...")
    #     self.player = ScheduledMidiPlayer(self.bpm)
    #     return self.player

    async def __aexit__(self, *args):
        if self.player:
            self.player.close()


# ----- Example usage with real async playback -----
async def main():
    # Define a score (synchronous data)
    melody = [
        Note(60, Fraction(1, 4)),  # C
        Note(62, Fraction(1, 4)),  # D
        Note(64, Fraction(1, 2)),  # E
        Note(65, Fraction(1, 4)),  # F
        Note(67, Fraction(1, 2)),  # G
    ]

    chords = {
        'right': [
            [60, 64, 67],  # C major
            [62, 65, 69],  # D minor
        ],
        'left': [
            Note(48, Fraction(1, 1)),  # Bass C
        ]
    }

    # Play using async session
    async with AsyncMidiSession(bpm=120) as player:
        print("Playing melody...")
        await player.play_part(melody)

        print("\nPlaying chords (polyphonic)...")
        await player.play_part(chords)

        # Play multiple things with offsets
        print("\nPlaying canon (delayed entry)...")
        await asyncio.gather(
            player.play_part(melody),  # Starts at 0
            player.play_with_countdown(melody, Fraction(1, 2))  # Starts 0.5 beats later
        )

    print("Done!")


# Run the async event loop
if __name__ == "__main__":
    asyncio.run(main())
