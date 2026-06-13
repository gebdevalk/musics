# ===================== DATA-DRIVEN ALGORITHMS =====================
from typing import List, Optional

from experimental.playback.polyphonic import VoiceAlgorithm, Note


class DataDrivenMelody(VoiceAlgorithm):
    """Melody that changes based on external data"""
    
    def __init__(self, base_scale: List[int], data_source=None):
        self.base_scale = base_scale
        self.data_source = data_source or (lambda: 0.5)  # Default to 0.5
        self.last_data = 0.5
        self.last_time = 0
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        # Get current data value
        current_data = self.data_source()
        
        # Smooth data changes
        self.last_data = self.last_data * 0.9 + current_data * 0.1
        
        # Play note based on data
        if time_since_start - self.last_time >= 0.2:  # 5 notes per second
            self.last_time = time_since_start
            
            # Map data to musical parameters
            scale_index = int(self.last_data * len(self.base_scale)) % len(self.base_scale)
            pitch = 60 + self.base_scale[scale_index]
            
            # Velocity based on data derivative
            velocity = int(40 + self.last_data * 60)
            
            # Duration based on data
            duration = 0.1 + self.last_data * 0.3
            
            return Note(pitch=pitch, velocity=velocity, duration=duration)
        return None

class HarmonicGenerator(VoiceAlgorithm):
    """Generates harmony notes based on a root note from another voice"""
    
    def __init__(self, harmony_rule, root_provider):
        """
        harmony_rule: function(root_note) -> List[notes]
        root_provider: function() -> current root note
        """
        self.harmony_rule = harmony_rule
        self.root_provider = root_provider
        self.last_time = 0
        
    def generate_next(self, time_since_start: float, voice_id: int) -> Optional[Note]:
        # Get current root from provider
        root = self.root_provider()
        
        if root and time_since_start - self.last_time >= 0.5:
            self.last_time = time_since_start
            
            # Generate harmony notes
            harmony_notes = self.harmony_rule(root)
            
            if harmony_notes:
                # Return one harmony note at a time
                note_idx = int(time_since_start * 2) % len(harmony_notes)
                return Note(pitch=harmony_notes[note_idx], 
                          velocity=60, 
                          duration=0.4)
        return None

# Add new algorithm by subclassing VoiceAlgorithm
class MyCoolAlgorithm(VoiceAlgorithm):
    def generate_next(self, time_since_start, voice_id):
        # Your logic here
        return Note(pitch=60, velocity=100, duration=0.5)

# Use it
# player.add_voice(MyCoolAlgorithm(), midi_channel=4)