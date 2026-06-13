import mido
import time
import threading
from abc import ABC, abstractmethod
from typing import List, Dict, Optional
from dataclasses import dataclass

# ===================== CORE COMPOSITE STRUCTURE =====================

@dataclass
class Note:
    """Simple note representation"""
    pitch: int
    velocity: int = 64
    duration: float = 0.5  # seconds

class VoiceAlgorithm(ABC):
    """Abstract base for voice algorithms"""
    
    @abstractmethod
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        """Generate next note or None for rest"""
        pass

class Voice:
    """Individual voice with its own algorithm"""
    
    def __init__(self, voice_id: int, algorithm: VoiceAlgorithm, midi_channel: int = 0):
        self.voice_id = voice_id
        self.algorithm = algorithm
        self.midi_channel = midi_channel
        self.is_playing = False
        self.thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        
    def start(self, midi_out):
        """Start the voice in its own thread"""
        self.is_playing = True
        self.stop_event.clear()
        self.thread = threading.Thread(target=self._run, args=(midi_out,))
        self.thread.start()
        
    def stop(self):
        """Stop the voice"""
        self.is_playing = False
        self.stop_event.set()
        if self.thread:
            self.thread.join()
            
    def _run(self, midi_out):
        """Main voice loop - runs in separate thread"""
        start_time = time.time()
        
        while not self.stop_event.is_set():
            # Ask algorithm for next note
            elapsed = time.time() - start_time
            note = self.algorithm.generate_next(elapsed, self.voice_id)
            
            if note:
                # Play the note
                midi_out.send(mido.Message('note_on', 
                                         note=note.pitch,
                                         velocity=note.velocity,
                                         channel=self.midi_channel))
                
                # Schedule note off
                threading.Timer(note.duration, 
                              lambda: midi_out.send(mido.Message('note_off',
                                                                note=note.pitch,
                                                                channel=self.midi_channel))).start()
                
                # Wait a bit before next note (prevent overwhelming)
                time.sleep(0.05)
            else:
                # Rest - wait a bit
                time.sleep(0.1)

class PolyphonicMIDIPlayer:
    """Main composite player - manages multiple voices"""
    
    def __init__(self):
        # Setup MIDI output
        self.midi_out = self._setup_midi()
        self.voices: List[Voice] = []
        
    def _setup_midi(self):
        """Simple MIDI setup"""
        try:
            if mido.get_output_names():
                return mido.open_output(mido.get_output_names()[0])
            else:
                return mido.open_output('Python MIDI Player', virtual=True)
        except:
            print("Warning: Using virtual MIDI port")
            return mido.open_output('Python MIDI Player', virtual=True)
    
    def add_voice(self, algorithm: VoiceAlgorithm, midi_channel: int = 0) -> int:
        """Add a voice with its algorithm"""
        voice_id = len(self.voices)
        self.voices.append(Voice(voice_id, algorithm, midi_channel))
        return voice_id
    
    def start(self):
        """Start all voices"""
        print(f"Starting {len(self.voices)} voices...")
        for voice in self.voices:
            voice.start(self.midi_out)
    
    def stop(self):
        """Stop all voices"""
        print("Stopping all voices...")
        for voice in self.voices:
            voice.stop()
        self._all_notes_off()
    
    def _all_notes_off(self):
        """MIDI panic"""
        for ch in range(16):
            self.midi_out.send(mido.Message('control_change', control=123, value=0, channel=ch))
    
    def __enter__(self):
        self.start()
        return self
    
    def __exit__(self, *args):
        self.stop()