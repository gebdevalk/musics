import mido
import threading
import time as time_module
from fractions import Fraction

from core.domain.composite import Container
from tools.ratio import Ratio


# ── Helpers ───────────────────────────────────────────────────────────────────

def seconds_to_ratio(seconds: float, bpm: float = 120.0, resolution: int = 16) -> Ratio:
    """Convert a duration in seconds to a Ratio (beats as a fraction)."""
    beats = seconds * (bpm / 60.0)
    # Quantize to nearest 1/resolution
    quantized: float = round(beats * resolution) / resolution
    return Ratio(quantized, Fraction.limit_denominator(resolution))


# ── Recorder ──────────────────────────────────────────────────────────────────

class MidiRecorder:
    """
    Listens to a MIDI input port and records notes into a Monophonic or
    Polyphonic Composite.

    Usage:
        recorder = MidiRecorder(bpm=120)
        recorder.start()          # begin listening
        # ... play ...
        score = recorder.stop()   # returns a Composite with recorded Notes
    """

    def __init__(
        self,
        port_name: str = None,      # None = first available port
        bpm: float = 120.0,
        polyphonic: bool = False,   # True = Polyphonic, False = Monophonic
        velocity_as_accent: bool = True,
    ):
        self.bpm = bpm
        self.polyphonic = polyphonic
        self.velocity_as_accent = velocity_as_accent

        # Resolve port
        available = mido.get_input_names()
        if not available:
            raise RuntimeError("No MIDI input ports found.")
        self.port_name = port_name or available[0]

        # Internal state
        self._active: dict[int, float] = {}   # pitch -> note_on timestamp
        self._recorded: list[Note] = []
        self._running = False
        self._thread: threading.Thread = None
        self._lock = threading.Lock()
        self._start_time: float = None

    # ── Public API ────────────────────────────────────────────────────────────

    def start(self):
        """Open the port and begin recording in a background thread."""
        if self._running:
            return
        self._running = True
        self._start_time = time_module.time()
        self._active.clear()
        self._recorded.clear()
        self._thread = threading.Thread(target=self._listen, daemon=True)
        self._thread.start()
        print(f"Recording on '{self.port_name}' at {self.bpm} BPM ...")

    def stop(self) -> Container:
        """Stop recording and return the populated Composite."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=2.0)

        # Close any still-held notes using current time
        now = time_module.time()
        with self._lock:
            for pitch, on_time in list(self._active.items()):
                self._finalise_note(pitch, on_time, now, velocity=64)
            self._active.clear()

        return self._build_composite()

    def list_ports(self):
        print("Available MIDI input ports:")
        for name in mido.get_input_names():
            print(f"  {name}")

    # ── Internal ──────────────────────────────────────────────────────────────

    def _listen(self):
        with mido.open_input(self.port_name) as port:
            while self._running:
                for msg in port.iter_pending():
                    self._handle(msg)
                time_module.sleep(0.001)   # 1 ms polling interval

    def _handle(self, msg: mido.Message):
        now = time_module.time()

        # note_on with velocity 0 is treated as note_off by the MIDI spec
        if msg.type == "note_on" and msg.velocity > 0:
            with self._lock:
                self._active[msg.note] = (now, msg.velocity)

        elif msg.type == "note_off" or (msg.type == "note_on" and msg.velocity == 0):
            with self._lock:
                if msg.note in self._active:
                    on_time, velocity = self._active.pop(msg.note)
                    self._finalise_note(msg.note, on_time, now, velocity)

    def _finalise_note(self, pitch: int, on_time: float, off_time: float, velocity: int):
        duration_secs = off_time - on_time
        duration = seconds_to_ratio(duration_secs, self.bpm)
        accent = round(velocity / 127.0, 3) if self.velocity_as_accent else None

        note = Note(
            pitch=pitch,
            duration=duration,
            accent=accent,
        )
        self._recorded.append(note)

    def _build_composite(self) -> Container:
        container = Polyphonic() if self.polyphonic else Monophonic()
        for note in self._recorded:
            container.append(note)
        return container


"""
recorder = MidiRecorder(bpm=120, polyphonic=False)
recorder.start()
input("Press Enter to stop...")
score = recorder.stop()
print(score)
"""