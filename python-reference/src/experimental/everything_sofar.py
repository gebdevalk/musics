
import time
import heapq
from threading import Lock
from abc import ABC, abstractmethod


# ============================================================
# MIDI EVENT (minimal runtime object)
# ============================================================

class MidiEvent:
    def __init__(self, kind, channel=0, data=None):
        self.kind = kind
        self.channel = channel
        self.data = data or {}

    def __repr__(self):
        return f"MidiEvent({self.kind}, ch={self.channel}, data={self.data})"


# ============================================================
# COMPOSITE PATTERN: COMPONENTS
# ============================================================

class Component(ABC):
    @abstractmethod
    def to_stream(self):
        """Compile this component into a runtime stream (generator)."""
        pass


# ------------------ LEAF COMPONENTS -------------------------

class Note(Component):
    def __init__(self, pitch, velocity, duration, channel=0):
        self.pitch = pitch
        self.velocity = velocity
        self.duration = duration
        self.channel = channel

    def to_stream(self):
        def _s():
            yield MidiEvent("note_on", self.channel,
                            {"pitch": self.pitch, "velocity": self.velocity}), 0
            yield MidiEvent("note_off", self.channel,
                            {"pitch": self.pitch}), self.duration
        return _s()


class Rest(Component):
    def __init__(self, duration):
        self.duration = duration

    def to_stream(self):
        def _s():
            yield None, self.duration
        return _s()


class TempoChange(Component):
    def __init__(self, bpm):
        self.bpm = bpm

    def to_stream(self):
        def _s():
            yield MidiEvent("tempo", data={"bpm": self.bpm}), 0
        return _s()


# ------------------ COMPOSITE COMPONENTS --------------------

class Chain(Component):
    """Sequential composition."""
    def __init__(self, children):
        self.children = list(children)

    def to_stream(self):
        def _s():
            for child in self.children:
                stream = child.to_stream()
                for event, dt in stream:
                    yield event, dt
        return _s()


class Concurrent(Component):
    """Parallel composition."""
    def __init__(self, children):
        self.children = list(children)

    def to_stream(self):
        streams = [child.to_stream() for child in self.children]

        def _s():
            active = {s: 0 for s in streams}
            while active:
                t_next = min(active.values())
                due = [s for s, t in active.items() if t == t_next]

                for s in due:
                    try:
                        event, dt = next(s)
                        yield event, 0
                        active[s] = t_next + dt
                    except StopIteration:
                        del active[s]
        return _s()


class Algorithm(Component):
    """Procedural generator producing (MidiEvent, dt)."""
    def __init__(self, func, *params):
        self.func = func
        self.params = params

    def to_stream(self):
        gen = self.func(*self.params)
        def _s():
            for event, dt in gen:
                yield event, dt
        return _s()


# ============================================================
# SCORE (Composite root with named parts)
# ============================================================

class Score(Component):
    def __init__(self):
        self.parts = {} # name -> Component
        self.lock = Lock()

    def add(self, name, component):
        with self.lock:
            self.parts[name] = component

    def replace(self, name, component):
        with self.lock:
            self.parts[name] = component

    def remove(self, name):
        with self.lock:
            del self.parts[name]

    def to_stream(self):
        with self.lock:
            return Concurrent(list(self.parts.values())).to_stream()


# ============================================================
# SCHEDULER (consumes streams only)
# ============================================================

class Scheduler:
    def __init__(self, score, bpm=120, ppq=480):
        self.score = score
        self.bpm = bpm
        self.ppq = ppq
        self.seconds_per_tick = (60 / bpm) / ppq
        self.queue = []

    def update_tempo(self, bpm):
        self.bpm = bpm
        self.seconds_per_tick = (60 / bpm) / self.ppq

    def start(self, midi_out):
        root_stream = self.score.to_stream()
        heapq.heappush(self.queue, (0, root_stream))
        start_time = time.time()

        while True:
            t_ticks, stream = heapq.heappop(self.queue)
            t_seconds = t_ticks * self.seconds_per_tick
            now = time.time() - start_time

            if now < t_seconds:
                time.sleep(t_seconds - now)

            try:
                event, dt = next(stream)
            except StopIteration:
                continue

            if event:
                if event.kind == "tempo":
                    self.update_tempo(event.data["bpm"])
                else:
                    midi_out.send(event)

            heapq.heappush(self.queue, (t_ticks + dt, stream))


# ============================================================
# EXAMPLE ALGORITHM
# ============================================================

def scale_algorithm(root_pitch, steps, duration):
    def _gen():
        pitch = root_pitch
        for _ in range(steps):
            yield MidiEvent("note_on", data={"pitch": pitch, "velocity": 100}), 0
            yield MidiEvent("note_off", data={"pitch": pitch}), duration
            pitch += 1
    return _gen()


# ============================================================
# DUMMY MIDI OUTPUT
# ============================================================

class DummyMidiOut:
    def send(self, event):
        print("MIDI:", event)


# ============================================================
# DEMO
# ============================================================

if __name__ == "__main__":
    score = Score()

    score.add("tempo", TempoChange(120))
    score.add("melody", Algorithm(scale_algorithm, 60, 4, 120))
    score.add("pad", Chain([
        Rest(240),
        Note(60, 80, 240),
        Note(64, 80, 240),
        Note(67, 80, 240)
    ]))

    scheduler = Scheduler(score, bpm=120, ppq=480)
    midi_out = DummyMidiOut()
    scheduler.start(midi_out)
