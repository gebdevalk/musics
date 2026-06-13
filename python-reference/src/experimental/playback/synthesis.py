from fractions import Fraction
from dataclasses import dataclass, field
from typing import List, Union, Dict, Optional, Tuple
import math
import time
import struct
import wave
import numpy as np
from pathlib import Path

from experimental.core.timbre import Timbre, SpectralNote, KlangNote


# ============================================================================
# SOUND GENERATION ENGINE
# ============================================================================

class SoundSynthesizer:
    """Advanced synthesizer that can generate audio from our sound objects."""

    def __init__(self, sample_rate: int = 44100):
        self.sample_rate = sample_rate
        self.master_volume = 0.5

    def generate_waveform(self, frequency: float, duration: float,
                          timbre: Timbre, amplitude: float = 0.5) -> np.ndarray:
        """Generate a waveform based on timbre parameters."""
        samples = int(duration * self.sample_rate)
        t = np.linspace(0, duration, samples, endpoint=False)

        # Generate base waveform
        if timbre.waveform == 'sine':
            wave = np.sin(2 * np.pi * frequency * t)
        elif timbre.waveform == 'square':
            wave = np.sign(np.sin(2 * np.pi * frequency * t))
        elif timbre.waveform == 'saw':
            wave = 2 * (t * frequency - np.floor(t * frequency + 0.5))
        elif timbre.waveform == 'triangle':
            wave = 2 * np.abs(2 * (t * frequency - np.floor(t * frequency + 0.5))) - 1
        elif timbre.waveform == 'noise':
            wave = np.random.uniform(-1, 1, samples)
        else:
            wave = np.sin(2 * np.pi * frequency * t)

        # Apply brightness (simple low-pass filter)
        if timbre.brightness < 1.0:
            # Very simple filter - mix with filtered version
            filtered = self._lowpass_filter(wave, timbre.brightness)
            wave = filtered

        # Apply ADSR envelope
        envelope = self._generate_adsr(duration, timbre, samples)
        wave = wave * envelope

        # Apply modulation effects
        wave = self._apply_modulation(wave, frequency, duration, timbre, t)

        return wave * amplitude * self.master_volume

    def _lowpass_filter(self, wave: np.ndarray, cutoff: float) -> np.ndarray:
        """Simple low-pass filter (cutoff 0-1, 1 = no filtering)."""
        if cutoff >= 1.0:
            return wave

        # Simple one-pole filter
        filtered = np.zeros_like(wave)
        filtered[0] = wave[0]
        alpha = min(0.5, cutoff * 0.5)  # Filter coefficient

        for i in range(1, len(wave)):
            filtered[i] = filtered[i - 1] + alpha * (wave[i] - filtered[i - 1])

        return filtered

    def _generate_adsr(self, duration: float, timbre: Timbre, samples: int) -> np.ndarray:
        """Generate ADSR envelope."""
        envelope = np.zeros(samples)

        # Convert times to samples
        attack_samples = int(timbre.attack * self.sample_rate)
        decay_samples = int(timbre.decay * self.sample_rate)
        release_samples = int(timbre.release * self.sample_rate)
        sustain_samples = samples - attack_samples - decay_samples - release_samples

        # Attack phase
        if attack_samples > 0:
            envelope[:attack_samples] = np.linspace(0, 1, attack_samples)

        # Decay phase
        if decay_samples > 0 and attack_samples + decay_samples <= samples:
            decay_start = attack_samples
            decay_end = attack_samples + decay_samples
            envelope[decay_start:decay_end] = np.linspace(1, timbre.sustain, decay_samples)

        # Sustain phase
        if sustain_samples > 0 and attack_samples + decay_samples + sustain_samples <= samples:
            sustain_start = attack_samples + decay_samples
            sustain_end = sustain_start + sustain_samples
            envelope[sustain_start:sustain_end] = timbre.sustain

        # Release phase
        if release_samples > 0:
            release_start = max(0, samples - release_samples)
            envelope[release_start:] = np.linspace(
                envelope[release_start - 1] if release_start > 0 else 0,
                0,
                release_samples
            )

        return envelope

    def _apply_modulation(self, wave: np.ndarray, frequency: float,
                          duration: float, timbre: Timbre, t: np.ndarray) -> np.ndarray:
        """Apply vibrato and tremolo effects."""
        if not timbre.modulation:
            return wave

        # Vibrato (frequency modulation)
        if timbre.modulation.get('vibrato_depth', 0) > 0:
            vibrato_rate = timbre.modulation.get('vibrato_rate', 5.0)
            vibrato_depth = timbre.modulation.get('vibrato_depth', 0.02)

            # Generate vibrato phase modulation
            vibrato = vibrato_depth * np.sin(2 * np.pi * vibrato_rate * t)

            # Re-generate wave with vibrato (simplified - phase modulation)
            # For complex waveforms, this is approximate
            if timbre.waveform == 'sine':
                wave = np.sin(2 * np.pi * frequency * t + vibrato * 2 * np.pi)

        # Tremolo (amplitude modulation)
        if timbre.modulation.get('tremolo_depth', 0) > 0:
            tremolo_rate = timbre.modulation.get('tremolo_rate', 4.0)
            tremolo_depth = timbre.modulation.get('tremolo_depth', 0.1)

            tremolo_env = 1 + tremolo_depth * np.sin(2 * np.pi * tremolo_rate * t)
            wave = wave * tremolo_env

        return wave

    def render_spectral_note(self, note: SpectralNote) -> np.ndarray:
        """Render a SpectralNote with multiple partials."""
        duration_sec = float(note.duration) * 2.0  # Assuming quarter note = 2 seconds
        samples = int(duration_sec * self.sample_rate)

        if samples <= 0:
            return np.array([])

        # Convert MIDI pitch to frequency
        fundamental_freq = 440.0 * (2 ** ((note.fundamental - 69) / 12.0))

        # Initialize output buffer
        output = np.zeros(samples)

        # Generate each partial
        for harmonic_ratio, amplitude in note.partials:
            freq = fundamental_freq * harmonic_ratio

            # Amplitude decreases with harmonic number (natural spectral rolloff)
            partial_amp = amplitude * 0.5

            # Generate waveform for this partial
            partial_wave = self.generate_waveform(
                freq,
                duration_sec,
                note.timbre,
                partial_amp
            )

            # Add to output
            min_len = min(len(output), len(partial_wave))
            output[:min_len] += partial_wave[:min_len]

        # Normalize
        max_val = np.max(np.abs(output))
        if max_val > 0:
            output = output / max_val * 0.8

        # Apply spatial positioning (simple panning based on x coordinate)
        if note.spatial[0] != 0:
            pan = max(-1, min(1, note.spatial[0]))
            left_gain = 0.5 * (1 - pan)
            right_gain = 0.5 * (1 + pan)
            stereo = np.array([output * left_gain, output * right_gain])
            return stereo

        return output

    def render_klang_note(self, note: KlangNote) -> np.ndarray:
        """Render a KlangNote with dynamics and pitch bend."""
        duration_sec = float(note.duration) * 2.0
        samples = int(duration_sec * self.sample_rate)

        if samples <= 0:
            return np.array([])

        t = np.linspace(0, duration_sec, samples, endpoint=False)

        # Handle pitch (single or detuned multiple)
        if isinstance(note.pitch, list):
            # Multiple detuned pitches (chorus effect)
            outputs = []
            for detune_pitch in note.pitch:
                freq = 440.0 * (2 ** ((detune_pitch - 69) / 12.0))
                wave = self.generate_waveform(freq, duration_sec, note.timbre, 0.5)
                outputs.append(wave)

            # Mix detuned voices
            output = np.zeros(samples)
            for wave in outputs:
                min_len = min(len(output), len(wave))
                output[:min_len] += wave[:min_len]

            # Normalize
            max_val = np.max(np.abs(output))
            if max_val > 0:
                output = output / max_val * 0.8
        else:
            # Single pitch
            freq = 440.0 * (2 ** ((note.pitch - 69) / 12.0))
            output = self.generate_waveform(freq, duration_sec, note.timbre)

        # Apply dynamics envelope
        if note.dynamics:
            amplitude_env = np.ones(samples)
            for time_point, amp in note.dynamics:
                sample_point = int(float(time_point) * self.sample_rate * duration_sec)
                if sample_point < samples:
                    # Simple interpolation at breakpoints
                    amplitude_env[sample_point:] = amp

            # Smooth the envelope
            amplitude_env = np.convolve(amplitude_env, np.ones(100) / 100, mode='same')
            output = output * amplitude_env

        # Apply pitch bend
        if note.pitch_bend:
            # This is complex to implement in time domain
            # For demo, we'll just note it
            pass

        return output

    def save_wav(self, audio: np.ndarray, filename: str):
        """Save audio to WAV file."""
        # Convert to 16-bit int
        if audio.ndim == 1:
            # Mono
            audio_int = np.int16(audio * 32767)
            with wave.open(filename, 'wb') as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(self.sample_rate)
                wav_file.writeframes(audio_int.tobytes())
        else:
            # Stereo
            audio_int = np.int16(audio.T * 32767)
            with wave.open(filename, 'wb') as wav_file:
                wav_file.setnchannels(2)
                wav_file.setsampwidth(2)
                wav_file.setframerate(self.sample_rate)
                wav_file.writeframes(audio_int.tobytes())

        print(f"Saved: {filename}")


# ============================================================================
# COMPOSITION: "Spectral Dreams" - An Ambient Electronic Piece
# ============================================================================

def create_spectral_dreams() -> Dict[str, List]:
    """Create an ambient electronic piece demonstrating all sound capabilities."""

    # ========================================
    # PART 1: DEEP PAD (SpectralNote with complex partials)
    # ========================================

    # Timbre for the pad - warm and evolving
    pad_timbre = Timbre(
        waveform='saw',
        brightness=0.4,  # Dark, filtered
        attack=2.0,  # Slow attack
        decay=1.0,
        sustain=0.8,
        release=3.0,  # Long release
        modulation={
            'vibrato_rate': 3.0,
            'vibrato_depth': 0.01,
            'tremolo_rate': 2.0,
            'tremolo_depth': 0.15
        }
    )

    # C minor pad with rich harmonics (fundamental + harmonics)
    c_minor_pad = SpectralNote(
        fundamental=48,  # C2
        partials=[
            (1.0, 1.0),  # Fundamental
            (2.0, 0.6),  # Octave
            (3.0, 0.4),  # Fifth + octave
            (4.0, 0.3),  # Second octave
            (5.0, 0.2),  # Major third above second octave
            (6.0, 0.15),  # Perfect fifth above second octave
            (7.0, 0.1),  # Minor seventh
            (8.0, 0.08),  # Third octave
            (9.0, 0.05),  # Major second
        ],
        duration=Fraction(8, 1),  # 8 bars (long pad)
        timbre=pad_timbre,
        spatial=(0.0, 0.0, 0.5)  # Center, slightly forward
    )

    # ========================================
    # PART 2: GLASSY MELODY (SpectralNote with bell-like partials)
    # ========================================

    bell_timbre = Timbre(
        waveform='sine',
        brightness=0.9,  # Bright
        attack=0.01,  # Fast attack
        decay=0.5,
        sustain=0.3,
        release=1.0,
        modulation={
            'vibrato_rate': 6.0,
            'vibrato_depth': 0.005,
            'tremolo_rate': 5.0,
            'tremolo_depth': 0.05
        }
    )

    # Bell-like inharmonic partials
    bell_partials = [
        (1.0, 1.0),  # Fundamental
        (2.0, 0.4),  # Octave
        (2.76, 0.3),  # Inharmonic
        (3.0, 0.25),  # Twelfth
        (4.1, 0.2),  # Inharmonic
        (5.3, 0.15),  # Inharmonic
    ]

    # Melody notes
    bell_melody = [
        SpectralNote(60, bell_partials, Fraction(1, 1), bell_timbre, 80, (-0.5, 0, 0)),
        SpectralNote(64, bell_partials, Fraction(1, 1), bell_timbre, 75, (0.0, 0, 0)),
        SpectralNote(67, bell_partials, Fraction(1, 1), bell_timbre, 85, (0.5, 0, 0)),
        SpectralNote(72, bell_partials, Fraction(2, 1), bell_timbre, 90, (0.0, 0, 0.2)),
        SpectralNote(70, bell_partials, Fraction(1, 1), bell_timbre, 80, (-0.3, 0, 0.1)),
        SpectralNote(67, bell_partials, Fraction(1, 1), bell_timbre, 75, (0.0, 0, 0)),
        SpectralNote(64, bell_partials, Fraction(1, 1), bell_timbre, 70, (0.3, 0, 0.1)),
        SpectralNote(60, bell_partials, Fraction(3, 1), bell_timbre, 85, (0.0, 0, 0.3)),
    ]

    # ========================================
    # PART 3: EVOLVING TEXTURE (KlangNote with dynamics and expression)
    # ========================================

    texture_timbre = Timbre(
        waveform='triangle',
        brightness=0.6,
        attack=0.5,
        decay=0.3,
        sustain=0.6,
        release=1.5,
        modulation={
            'vibrato_rate': 4.5,
            'vibrato_depth': 0.03,
            'tremolo_rate': 3.2,
            'tremolo_depth': 0.2
        }
    )

    # Create dynamics envelope (crescendo and decrescendo)
    dynamics_shape = [
        (Fraction(0, 4), 0.1),  # Start soft
        (Fraction(1, 4), 0.3),  # Growing
        (Fraction(2, 4), 0.6),  # Louder
        (Fraction(3, 4), 0.9),  # Peak
        (Fraction(4, 4), 0.5),  # Fading
        (Fraction(5, 4), 0.2),  # Soft
        (Fraction(6, 4), 0.1),  # Very soft
    ]

    # Create pitch bend (microtonal glissando)
    pitch_bend_shape = [
        (Fraction(0, 4), 0.0),
        (Fraction(2, 4), 0.5),  # Bend up quarter tone
        (Fraction(4, 4), -0.25),  # Bend down
        (Fraction(6, 4), 0.0),
    ]

    # Evolving texture note with multiple detuned pitches
    texture_note = KlangNote(
        pitch=[45, 46, 45.5],  # Detuned A's (microtonal cluster)
        duration=Fraction(8, 1),
        timbre=texture_timbre,
        dynamics=dynamics_shape,
        pitch_bend=pitch_bend_shape,
        expression={
            'legato': 0.8,
            'accent': 0.3,
            'vibrato_amount': 0.5
        }
    )

    # ========================================
    # PART 4: PULSING RHYTHM (KlangNote with aggressive timbre)
    # ========================================

    pulse_timbre = Timbre(
        waveform='square',
        brightness=0.3,  # Dark pulse
        attack=0.001,  # Very fast
        decay=0.1,
        sustain=0.0,  # No sustain (percussive)
        release=0.05,
        modulation={
            'vibrato_rate': 0,
            'vibrato_depth': 0,
            'tremolo_rate': 8.0,
            'tremolo_depth': 0.5  # Heavy tremolo for pulsing effect
        }
    )

    # Create a sequence of pulsing notes
    pulse_sequence = []
    for i in range(16):
        # Alternate between two pitches
        pitch = 36 if i % 2 == 0 else 39  # C2 and Eb2

        # Dynamics vary
        if i % 4 == 0:
            accent = 1.0  # Accent every 4th note
        else:
            accent = 0.6

        pulse_sequence.append(
            KlangNote(
                pitch=pitch,
                duration=Fraction(1, 2),  # Eighth notes
                timbre=pulse_timbre,
                dynamics=[(Fraction(0, 4), accent)],
                expression={'accent': accent, 'staccato': 0.9}
            )
        )

    # ========================================
    # PART 5: ATMOSPHERIC NOISE (SpectralNote with noise waveform)
    # ========================================

    noise_timbre = Timbre(
        waveform='noise',
        brightness=0.7,
        attack=0.5,
        decay=0.3,
        sustain=0.4,
        release=2.0,
        modulation={
            'vibrato_rate': 0.1,
            'vibrato_depth': 0.5,  # Slow, deep modulation for sweeping
            'tremolo_rate': 0.2,
            'tremolo_depth': 0.3
        }
    )

    # Wind-like noise with multiple "partials" (actually just different frequency bands)
    noise_note = SpectralNote(
        fundamental=30,  # Low rumble
        partials=[
            (1.0, 1.0),  # Low rumble
            (0.5, 0.7),  # Sub-bass
            (2.0, 0.5),  # Mid
            (4.0, 0.3),  # High-mid
            (8.0, 0.1),  # High
        ],
        duration=Fraction(16, 1),  # Long atmospheric layer
        timbre=noise_timbre,
        spatial=(0.0, 0.0, -0.5)  # Slightly behind
    )

    return {
        'pad': [c_minor_pad],
        'bells': bell_melody,
        'texture': [texture_note],
        'pulse': pulse_sequence,
        'atmosphere': [noise_note]
    }


# ============================================================================
# MAIN PROGRAM
# ============================================================================

def main():
    """Render the composition to audio files."""

    print("=" * 70)
    print("SPECTRAL DREAMS - Advanced Sound Synthesis Demo")
    print("=" * 70)

    # Initialize synthesizer
    synth = SoundSynthesizer(sample_rate=44100)

    print("\nCreating composition...")
    composition = create_spectral_dreams()

    print("\nRendering individual tracks...")

    # Create output directory
    output_dir = Path("../spectral_dreams_output")
    output_dir.mkdir(exist_ok=True)

    # Render each track separately
    all_audio = []

    # 1. Render pad track
    print("\nRendering ambient pad (SpectralNote with harmonics)...")
    pad_audio = []
    for note in composition['pad']:
        audio = synth.render_spectral_note(note)
        pad_audio.append(audio)

    if pad_audio:
        pad_mix = np.sum(pad_audio, axis=0)
        synth.save_wav(pad_mix, output_dir / "01_ambient_pad.wav")
        all_audio.append(pad_mix)

    # 2. Render bell melody
    print("Rendering glassy bells (SpectralNote with inharmonics)...")
    bell_audio = []
    for note in composition['bells']:
        audio = synth.render_spectral_note(note)
        bell_audio.append(audio)

    if bell_audio:
        bell_mix = np.sum(bell_audio, axis=0)
        synth.save_wav(bell_mix, output_dir / "02_glassy_bells.wav")
        all_audio.append(bell_mix)

    # 3. Render evolving texture
    print("Rendering evolving texture (KlangNote with dynamics)...")
    texture_audio = []
    for note in composition['texture']:
        audio = synth.render_klang_note(note)
        texture_audio.append(audio)

    if texture_audio:
        texture_mix = np.sum(texture_audio, axis=0)
        synth.save_wav(texture_mix, output_dir / "03_evolving_texture.wav")
        all_audio.append(texture_mix)

    # 4. Render pulse rhythm
    print("Rendering pulsing rhythm (KlangNote with tremolo)...")
    pulse_audio = []
    for note in composition['pulse']:
        audio = synth.render_klang_note(note)
        pulse_audio.append(audio)

    if pulse_audio:
        # For pulse, we need to concatenate rather than sum
        pulse_mix = np.concatenate(pulse_audio) if pulse_audio else np.array([])
        synth.save_wav(pulse_mix, output_dir / "04_pulsing_rhythm.wav")
        all_audio.append(pulse_mix)

    # 5. Render atmosphere
    print("Rendering atmospheric noise (noise waveform)...")
    atmos_audio = []
    for note in composition['atmosphere']:
        audio = synth.render_spectral_note(note)
        atmos_audio.append(audio)

    if atmos_audio:
        atmos_mix = np.sum(atmos_audio, axis=0)
        synth.save_wav(atmos_mix, output_dir / "05_atmosphere.wav")
        all_audio.append(atmos_mix)

    # Create final mix
    print("\nCreating final mix...")

    # Find the longest track
    max_length = max(len(audio) for audio in all_audio if len(audio) > 0)

    # Mix all tracks with different levels
    final_mix = np.zeros(max_length)
    mix_levels = [0.5, 0.6, 0.4, 0.3, 0.4]  # Levels for each track

    for audio, level in zip(all_audio, mix_levels):
        if len(audio) > 0:
            final_mix[:len(audio)] += audio[:len(audio)] * level

    # Normalize final mix
    max_val = np.max(np.abs(final_mix))
    if max_val > 0:
        final_mix = final_mix / max_val * 0.95

    synth.save_wav(final_mix, output_dir / "00_spectral_dreams_complete.wav")

    print("\n" + "=" * 70)
    print("RENDERING COMPLETE!")
    print("=" * 70)
    print(f"\nOutput saved to: {output_dir.absolute()}")
    print("\nFiles created:")
    for wav_file in sorted(output_dir.glob("*.wav")):
        print(f"  - {wav_file.name}")

    print("\n" + "=" * 70)
    print("SOUND DESIGN HIGHLIGHTS")
    print("=" * 70)
    print("""
    Ambient Pad:
      - SpectralNote with 9 harmonic partials
      - Saw waveform with low-pass filter (brightness=0.4)
      - Long ADSR envelope (slow attack, long release)
      - Subtle vibrato and tremolo modulation

    Glassy Bells:
      - SpectralNote with inharmonic partials (bell-like spectrum)
      - Sine waveform with fast attack
      - Spatial positioning varies per note (stereo field)

    Evolving Texture:
      - KlangNote with microtonal pitch detuning (pitch list)
      - Complex dynamics envelope (crescendo/decrescendo)
      - Pitch bend for glissando effects
      - Triangle waveform with heavy tremolo

    Pulsing Rhythm:
      - KlangNote with square waveform
      - Percussive ADSR (no sustain)
      - Heavy tremolo modulation for pulsing effect
      - Accented notes via dynamics

    Atmospheric Noise:
      - SpectralNote with noise waveform
      - Multiple "partials" creating frequency bands
      - Very slow, deep modulation for sweeping effect
    """)


if __name__ == "__main__":
    # Check for required libraries
    try:
        import numpy as np
    except ImportError:
        print("Please install numpy: pip install numpy")
        exit(1)

    main()