
A complete, runnable example helps show how the whole model fits together: Components → coroutines → scheduler → real‑time MIDI output. The code below is a single, coherent listing that:

- defines all Component types (Atoms, StructuralIterable, GenerativeIterable, Concurrent, Chord, Data)
- defines coroutine runners for each Component
- defines a tempo‑aware scheduler
- includes a small musical structure using an Algorithm and a Chord
- runs it through a dummy MIDI output

This is the smallest fully working end‑to‑end example of the architecture you’ve been building.

---

Complete example: components, coroutines, scheduler, and playback

`python
import heapq
import time
from abc import ABC, abstractmethod


========= Core component model =========

class Component(ABC):
    pass


class IterableComponent(Component):
    """Capability: can produce Components when pulled."""
    def iter(self):
        return self

    @abstractmethod
    def next(self):
        raise NotImplementedError


========= Atoms (MIDI events, time-bearing) =========

class Atom(Component):
    """Leaf event with a duration in ticks."""
    def init(self, duration=0):
        self.duration = duration


class Note(Atom):
    def init(self, pitch, velocity, duration):
        super().init(duration)
        self.pitch = pitch
        self.velocity = velocity

    def repr(self):
        return f"Note({self.pitch}, {self.velocity}, {self.duration})"


class Rest(Atom):
    def repr(self):
        return f"Rest({self.duration})"


class ProgramChange(Atom):
    def init(self, program, duration=0):
        super().init(duration)
        self.program = program

    def repr(self):
        return f"ProgramChange({self.program})"


class ControlChange(Atom):
    def init(self, controller, value, duration=0):
        super().init(duration)
        self.controller = controller
        self.value = value

    def repr(self):
        return f"ControlChange({self.controller}, {self.value})"


class TempoChange(Atom):
    def init(self, bpm, duration=0):
        super().init(duration)
        self.bpm = bpm

    def repr(self):
        return f"TempoChange({self.bpm})"


========= StructuralIterable (containers) =========

class StructuralIterable(IterableComponent):
    def init(self, children):
        self.children = list(children)
        self.index = 0

    def next(self):
        if self.index >= len(self.children):
            raise StopIteration
        item = self.children[self.index]
        self.index += 1
        return item


class Chain(StructuralIterable):
    pass


class OrderedAggregate(StructuralIterable):
    pass


class SetComponent(StructuralIterable):
    pass


class Bag(StructuralIterable):
    pass


========= GenerativeIterable (generators) =========

class GenerativeIterable(IterableComponent):
    pass


class Sequence(GenerativeIterable):
    def init(self, generators):
        self.generators = list(generators)
        self.index = 0

    def next(self):
        while self.index < len(self.generators):
            gen = self.generators[self.index]
            try:
                return next(gen)
            except StopIteration:
                self.index += 1
        raise StopIteration


class Algorithm(GenerativeIterable):
    """Procedural generator yielding Atoms only."""
    def init(self, func, *params):
        self.func = func
        self.params = params
        self.state = self.func(*self.params)

    def next(self):
        atom = next(self.state)
        if not isinstance(atom, Atom):
            raise TypeError("Algorithm must yield Atoms only")
        return atom


========= Simultaneous components =========

class Concurrent(Component):
    def init(self, components):
        self.components = list(components)


class Chord(Component):
    def init(self, notes):
        self.notes = list(notes)


========= Literal data =========

class Data(Component):
    def init(self, value):
        self.value = value


========= Context (not a Component) =========

class Context:
    def init(self, metadata, component):
        self.metadata = metadata
        self.component = component


========= Component → coroutine runners =========

def run(component):
    if isinstance(component, Atom):
        return run_atom(component)
    if isinstance(component, Chain):
        return run_chain(component)
    if isinstance(component, OrderedAggregate):
        return run_chain(component)
    if isinstance(component, SetComponent):
        return run_chain(component)
    if isinstance(component, Bag):
        return run_chain(component)
    if isinstance(component, Sequence):
        return run_sequence(component)
    if isinstance(component, Algorithm):
        return run_algorithm(component)
    if isinstance(component, Concurrent):
        return run_concurrent(component)
    if isinstance(component, Chord):
        return run_chord(component)
    if isinstance(component, Data):
        return run_data(component)
    if isinstance(component, Context):
        return run(component.component)
    raise TypeError(f"Unknown component type: {component}")


def run_atom(atom):
    def _runner():
        yield [atom], atom.duration
    return _runner()


def run_chain(chain):
    def _runner():
        for child in chain.children:
            child_runner = run(child)
            for atoms, dt in child_runner:
                yield atoms, dt
    return _runner()


def run_sequence(seq):
    def _runner():
        while True:
            try:
                comp = next(seq)
            except StopIteration:
                break
            child_runner = run(comp)
            for atoms, dt in child_runner:
                yield atoms, dt
    return _runner()


def run_algorithm(algorithm):
    def _runner():
        while True:
            try:
                atom = next(algorithm)
            except StopIteration:
                break
            yield [atom], atom.duration
    return _runner()


def run_concurrent(conc):
    def _runner():
        runners = [run(c) for c in conc.components]
        active = {r: 0 for r in runners}

        while active:
            t_next = min(active.values())
            due = [r for r, t in active.items() if t == t_next]

            atoms = []
            max_dt = 0
            for r in due:
                try:
                    a, dt = next(r)
                    atoms.extend(a)
                    active[r] = t_next + dt
                    maxdt = max(maxdt, dt)
                except StopIteration:
                    del active[r]

            yield atoms, max_dt
    return _runner()


def run_chord(chord):
    def _runner():
        if not chord.notes:
            return
        max_dt = max(n.duration for n in chord.notes)
        yield list(chord.notes), max_dt
    return _runner()


def run_data(data):
    def _runner():
        if False:
            yield [], 0
    return _runner()


========= Scheduler with tempo & real-time =========

class Scheduler:
    def init(self, root, bpm=120, ppq=480):
        self.root = root
        self.bpm = bpm
        self.ppq = ppq
        self.secondspertick = (60.0 / bpm) / ppq
        self.queue = []
        heapq.heappush(self.queue, (0, run(root)))

    def update_tempo(self, bpm):
        self.bpm = bpm
        self.secondspertick = (60.0 / bpm) / self.ppq

    def run(self, midi_out):
        start_time = time.time()
        while self.queue:
            t_ticks, coro = heapq.heappop(self.queue)
            tseconds = tticks * self.secondspertick
            now = time.time() - start_time

            if now < t_seconds:
                time.sleep(t_seconds - now)

            try:
                atoms, dt = next(coro)
            except StopIteration:
                continue

            for atom in atoms:
                if isinstance(atom, TempoChange):
                    self.update_tempo(atom.bpm)
                else:
                    midi_out.send(atom)

            heapq.heappush(self.queue, (t_ticks + dt, coro))


========= Example usage =========

def scalealgorithm(rootpitch, steps, duration):
    def _gen():
        pitch = root_pitch
        for _ in range(steps):
            yield Note(pitch, 100, duration)
            pitch += 1
    return _gen()


class DummyMidiOut:
    def send(self, atom):
        print("MIDI:", atom)


if name == "main":
    algo = Algorithm(scale_algorithm, 60, 4, 120)
    chord = Chord([Note(60, 100, 240), Note(64, 100, 240), Note(67, 100, 240)])

    root = Chain([
        TempoChange(120),
        algo,
        Rest(240),
        chord,
    ])

    sched = Scheduler(root, bpm=120, ppq=480)
    midi_out = DummyMidiOut()
    sched.run(midi_out)
`

---

This example shows the full flow: Components → coroutines → scheduler → MIDI output, with tempo and tick‑based timing integrated.
