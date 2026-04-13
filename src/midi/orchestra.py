import asyncio
from collections import defaultdict

import mido

from rejected.composite import Concurrent, Container
from core.domain.leafs import Leaf


class MidiEngine:

    def __init__(self):
        self.port = None
        self.sounding_notes = defaultdict(lambda: defaultdict(int))
        self.lock = asyncio.Lock()

    def start(self):
        self.port = mido.open_output()

    def stop(self):
        if self.port:
            self.port.close()

    # -------------------------
    # ENTRY POINT
    # -------------------------

    async def play(self, node):
        await self._play_node(node)

    # -------------------------
    # TREE WALKER (REAL-TIME)
    # -------------------------

    async def _play_node(self, node):

        # ---- LEAF ----
        if isinstance(node, Leaf):
            # node = render_leaf(node, now)
            await self._play_event(node)

        # ---- SEQUENTIAL ----
        elif isinstance(node, Container):
            for child in node:
                await self._play_node(child)

        # ---- PARALLEL ----
        elif isinstance(node, Concurrent):
            await asyncio.gather(
                *(self._play_node(child) for child in node)
            )

        else:
            raise TypeError(f"Unknown node type: {type(node)}")

    # -------------------------
    # EVENT PLAYER (STREAMING)
    # -------------------------

    async def _play_event(self, event):

        # CONTROL
        if hasattr(event, "control"):
            self.port.send(mido.Message(
                "control_change",
                control=event.control,
                value=event.value,
                channel=event.channel
            ))

        # PROGRAM CHANGE
        if hasattr(event, "program"):
            self.port.send(mido.Message(
                "program_change",
                program=event.program,
                channel=event.channel
            ))

        # NOTE
        if hasattr(event, "pitches"):
            for pitch in event.pitches:
                await self._note_on(event.channel, pitch, event.velocity)

            await asyncio.sleep(event.duration)

            for pitch in event.pitches:
                await self._note_off(event.channel, pitch)

        # DRUM
        elif hasattr(event, "note"):
            self.port.send(mido.Message(
                "note_on",
                note=event.note,
                velocity=event.velocity,
                channel=event.channel
            ))

            await asyncio.sleep(event.duration)

            await self._note_off(event.channel, event.note)

        # CONTROL
        elif hasattr(event, "control"):
            self.port.send(mido.Message(
                "control_change",
                control=event.control,
                value=event.value,
                channel=event.channel
            ))

        # PROGRAM CHANGE
        elif hasattr(event, "program"):
            self.port.send(mido.Message(
                "program_change",
                program=event.program,
                channel=event.channel
            ))

    # -------------------------
    # SAFE NOTE HANDLING
    # -------------------------

    async def _note_on(self, channel, pitch, velocity):
        async with self.lock:
            self.sounding_notes[channel][pitch] += 1

        self.port.send(mido.Message(
            "note_on",
            note=pitch,
            velocity=velocity,
            channel=channel
        ))

    async def _note_off(self, channel, pitch):
        async with self.lock:
            count = self.sounding_notes[channel].get(pitch, 0)

            if count <= 1:
                self.port.send(mido.Message(
                    "note_off",
                    note=pitch,
                    velocity=0,
                    channel=channel
                ))
                self.sounding_notes[channel].pop(pitch, None)
            else:
                self.sounding_notes[channel][pitch] -= 1