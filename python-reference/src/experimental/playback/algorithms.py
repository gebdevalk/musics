# ===================== SIMPLE ALGORITHM EXAMPLES =====================
from typing import List, Optional

from experimental.playback.polyphonic import VoiceAlgorithm, Note


class Arpeggiator(VoiceAlgorithm):
    """Simple arpeggiator - plays notes in sequence"""
    
    def __init__(self, notes: List[int], pattern: List[int], bpm: int = 120):
        self.notes = notes
        self.pattern = pattern  # indices into notes list
        self.bpm = bpm
        self.index = 0
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        # Calculate timing
        note_duration = 60 / self.bpm / 4  # 16th note duration
        
        # Get next note from pattern
        note_idx = self.pattern[self.index % len(self.pattern)]
        pitch = self.notes[note_idx % len(self.notes)]
        
        # Advance pattern
        self.index += 1
        
        return Note(pitch=pitch, velocity=80, duration=note_duration)

class Drone(VoiceAlgorithm):
    """Simple drone - holds a constant note"""
    
    def __init__(self, pitch: int, velocity: int = 50):
        self.pitch = pitch
        self.velocity = velocity
        self.played = False
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        if not self.played:
            self.played = True
            return Note(pitch=self.pitch, velocity=self.velocity, duration=999)  # Long note
        return None  # No more notes

class RandomMelody(VoiceAlgorithm):
    """Random melody generator"""
    
    def __init__(self, scale: List[int], root: int = 60, note_duration: float = 0.25):
        self.scale = scale
        self.root = root
        self.duration = note_duration
        self.last_time = 0
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        import random
        
        # Play note every note_duration seconds
        if time_since_start - self.last_time >= self.duration:
            self.last_time = time_since_start
            
            # Random note from scale
            scale_note = random.choice(self.scale)
            pitch = self.root + scale_note
            
            # Random velocity
            velocity = random.randint(40, 100)
            
            return Note(pitch=pitch, velocity=velocity, duration=self.duration * 0.9)
        return None

class BassLine(VoiceAlgorithm):
    """Simple bass line following chord progression"""
    
    def __init__(self, chord_roots: List[int], pattern: List[int]):
        self.chord_roots = chord_roots
        self.pattern = pattern  # octave offsets
        self.chord_index = 0
        self.pattern_index = 0
        self.last_time = 0
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        # Change chord every 4 seconds
        chord_duration = 4.0
        
        # Update chord index based on time
        self.chord_index = int(time_since_start / chord_duration) % len(self.chord_roots)
        
        # Play note every 0.5 seconds
        if time_since_start - self.last_time >= 0.5:
            self.last_time = time_since_start
            
            root = self.chord_roots[self.chord_index]
            offset = self.pattern[self.pattern_index % len(self.pattern)]
            
            pitch = root + offset
            self.pattern_index += 1
            
            return Note(pitch=pitch, velocity=90, duration=0.4)
        return None