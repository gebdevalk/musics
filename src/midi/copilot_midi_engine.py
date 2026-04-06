import asyncio
import time
from collections import defaultdict
from typing import List

import mido

from core.domain.composite import Composite, Concurrent
from core.domain.leafs import Leaf, LeafOn, LeafOff, Algorithm
from core.domain.meta import Part, Meta
from core.domain.score import SCORE
from midi.leaf_to_midi import (
    render_leaf, render_leaf_on, render_leaf_off,
    MidiNote, MidiNoteOn, MidiNoteOff,
)
from tools.ratio import Ratio


class MidiEngineAsync:
    """
    Fully non-blocking asyncio-based MIDI engine.

    - Leaf / LeafOn / LeafOff: scheduled immediately
    - Composite: sequential container, iterated with `for child in part`
    - Concurrent: parallel container, children started at same offset
    - Algorithm: expanded via _generate()
    - No global timeline; events are scheduled as they are rendered.
    """
    def __init__(self):
        """
        midi_out: synchronous function that sends a MIDI event
                  (e.g. to a MIDI port or file writer).
        """
        self.midi_out = None
        # self.tasks: List[asyncio.Task] = []
        self.offsets: dict[int, Ratio] = {}
        self.sounding_note_count: dict[int, dict[int, int]] = (
            defaultdict(lambda: defaultdict(int)))
        self.channel_pool: asyncio.Queue = asyncio.Queue()

    # --------------------------------------------------------
    # MIDI port management
    # --------------------------------------------------------

    def open_port(self):
        self.midi_out = mido.open_output()
        for ch in [i for i in range(15, -1, -1) if i != 9]:
            self.channel_pool.put_nowait(ch)
        for ch in [i for i in range(0, 16)]:
            self.offsets[ch]= Ratio(0)
        return None

    def close_port(self):
        if self.midi_out is not None:
            self.midi_out.close()

    # --------------------------------------------------------
    # Public API
    # --------------------------------------------------------

    async def play(self, part: Part):
        """
        Walks the Part tree and schedules events immediately.
        Returns when all scheduled events have been played.
        """
        start_time = time.time()
        await self.perform(0, part, SCORE)
        if self.tasks:
            await asyncio.gather(*self.tasks)

    # --------------------------------------------------------
    # Tree walk
    # --------------------------------------------------------

    async def perform(self, channel: int, part: Part, meta: Meta):
        """
        Dispatch based on Part type:
        - Leaf / LeafOn / LeafOff: render + schedule
        - Composite- > sequential (iteration)
        - Concurrent ->  parallel (iteration)
        - Algorithm -> expand via _generate()
        """

        # -----------------------------
        # Leaf
        # -----------------------------
        if isinstance(part, Leaf):
            note: MidiNote = render_leaf(part, meta, self.offsets[channel], channel)
            self.offsets[channel] += note.duration
            await self.play_note(channel, note)
            return

        # -----------------------------
        # Meta events
        # -----------------------------

        if isinstance(part, LeafOn):
            note_on: MidiNoteOn = render_leaf_on(part, meta, self.offsets[channel], channel)
            self.play_note_on(channel, note_on)
            return

        if isinstance(part, LeafOff):
            note_off: MidiNoteOff = render_leaf_off(part, meta, self.offsets[channel], channel)
            self.play_note_off(channel, note_off)
            return

        # -----------------------------
        # Composite (sequential)
        # -----------------------------
        if isinstance(part, Composite):
            channel = await self.channel_pool.get()
            try:
                for child in part:  # iteration over children
                    await self.perform(channel, child, part)
            finally:
                self.channel_pool.put_nowait(channel)  # always returned, even on error
            return

        # -----------------------------
        # Concurrent (parallel)
        # -----------------------------
        if isinstance(part, Concurrent):
            subtasks: List[asyncio.Task] = []
            for child in part:  # iteration over children
                # all children start at the same offset
                subtasks.append(
                    asyncio.create_task(self.perform(channel, child, SCORE))
                )
            if subtasks:
                await asyncio.gather(*subtasks)
            return

        # -----------------------------
        # Algorithm
        # -----------------------------
        if isinstance(part, Algorithm):
            generated = part.generate()
            for child in generated:
                await self.perform(channel, child, meta)
            return

        raise TypeError(f"Unknown Part type: {type(part)}")

    # --------------------------------------------------------
    # Sounding notes registration
    # --------------------------------------------------------

    def register(self, channel, pitch):

        if pitch > 0:
            self.sounding_note_count[channel][pitch] += 1

    def unregister(self, channel, pitch):
        count = self.sounding_note_count[channel].get(pitch, 0)
        if count > 1:
            self.sounding_note_count[channel][pitch] -= 1
        elif count == 1:
            del self.sounding_note_count[channel][pitch]
        # count == 0: already unregistered, do nothing (or raise)

    def sounding_count(self, channel, pitch):
        return self.sounding_note_count.get(channel, {}).get(pitch, 0)


    # --------------------------------------------------------
    # Async scheduling
    # --------------------------------------------------------

    async def play_note(self, channel: int, note: MidiNote):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)
        asyncio.create_task(self.note_off_after_delay(channel, note))
        await asyncio.sleep(max(note.duration, note.delay))
    #     FIXXXXX

    async def note_off_after_delay(self, channel: int, note: MidiNote):
        await asyncio.sleep(note.delay)
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def play_note_on(self, channel: int, note: MidiNoteOn):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)

    def play_note_off(self, channel: int, note: MidiNoteOff):
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def note_on(self, channel: int, pitch: int, velocity: int):
        self.register(channel, pitch)
        self.midi_out.send(mido.Message('note_on', note=pitch, velocity=velocity, channel=channel))

    def note_off(self, channel: int, pitch: int):
        if self.sounding_count(channel, pitch) == 1:
            self.midi_out.send(mido.Message('note_off', note=pitch, velocity=0, channel=channel))
        self.unregister(channel, pitch)
