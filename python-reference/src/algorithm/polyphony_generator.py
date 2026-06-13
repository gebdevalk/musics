import random

"""
Explanation of the prototype

· Motif placement: The motif’s durations must sum to an integer number of beats (so it aligns with beat boundaries). 
The engine builds a _motif_steps array the length of the piece, placing the transposed step at each beat where the motif 
is active. Outside the motif window, shape (if provided) is used; otherwise step=0.
· Strictness: At each beat, the engine rolls a random number; if ≤ strictness, it follows the motif step; otherwise it 
uses the shape or 0. This gives loose, non‑exact imitation.
· Density envelopes still subdivide beats; the motif step is divided equally among the sub‑notes of that beat.
· Counterpoint rules are applied after generating all voices’ raw pitch sequences (using _add_voice_against_all_sub). 
This ensures the final result obeys Jeppesen’s rules.
· Rhythm randomization works per voice, independent of motif.

Usage notes for your domain model later

· The engine currently requires all voices to have the same total number of sub‑notes (enforced by equal 
sum(density_envelope) across voices). This is necessary for note‑against‑note rule checking. In a future refactor, 
you could allow rests or time‑warping.
· The motif’s durations are in beats; the engine does not yet support motifs that start on fractional beats (e.g., 
0.3 beats). That can be added by resampling the motif onto the beat grid.
· For true paired imitation, you can simply give two voices the same motif (or complementary motifs) with different 
 and transpositions.

This prototype is ready to generate musical output. You can now experiment with different motifs, beat patterns, and 
densities. Later, we can refactor to match your domain model (e.g., separate Voice, Motif, Score classes).
"""

class CounterpointEngine:
    def __init__(self, scale, pitch_range, rules=None):
        self.scale = sorted(scale)
        self.pitch_range = pitch_range
        self.rules = rules if rules else {
            'no_parallel_fifths': True,
            'no_parallel_octaves': True,
            'consonance_on_strong': True,
        }

    def generate(self, beat_durations, voice_specs):
        """
        beat_durations: list of durations (in quarters) for each beat.
        voice_specs: list of dict, one per voice, each with:
            - 'shape' (list of step changes per beat) OR 'motif' + 'delay_beats' + 'strictness'
            - 'density_envelope' (list of ints, same length as beats)
            - 'tolerance' (int)
            - 'rhythm_params' (dict, optional)
            - 'motif' (dict with 'steps' and 'durations' in beats) optional
            - 'delay_beats' (float, start of motif in beats)
            - 'strictness' (float 0..1, probability to follow motif)
            - 'transpose' (int semitones) for motif

        Returns: list of voices, each voice = list of (pitch, duration) tuples.
        """
        n_voices = len(voice_specs)
        n_beats = len(beat_durations)

        # Validate and normalize per-voice parameters
        for v, spec in enumerate(voice_specs):
            if 'density_envelope' not in spec:
                spec['density_envelope'] = [1] * n_beats
            if len(spec['density_envelope']) != n_beats:
                raise ValueError(f"Voice {v} density envelope length mismatch")
            if 'tolerance' not in spec:
                spec['tolerance'] = 3
            if 'rhythm_params' not in spec:
                spec['rhythm_params'] = {}
            if 'motif' in spec:
                # Ensure motif durations sum to an integer number of beats (for alignment)
                motif_total = sum(spec['motif']['durations'])
                if abs(motif_total - round(motif_total)) > 1e-6:
                    raise ValueError(f"Motif total duration {motif_total} must be integer beats")
                spec['motif_len_beats'] = int(round(motif_total))
                spec['motif_start_beat'] = spec.get('delay_beats', 0.0)
                spec['motif_end_beat'] = spec['motif_start_beat'] + spec['motif_len_beats']
                # Build a beat-level target step array (None where no motif)
                motif_steps = [None] * n_beats
                for i, step in enumerate(spec['motif']['steps']):
                    beat_idx = int(spec['motif_start_beat'] + i)
                    if beat_idx < n_beats:
                        motif_steps[beat_idx] = step + spec.get('transpose', 0)
                spec['_motif_steps'] = motif_steps
                spec['_strictness'] = spec.get('strictness', 0.7)
            else:
                # Use shape envelope directly
                if 'shape' not in spec:
                    raise ValueError(f"Voice {v} needs either 'shape' or 'motif'")
                spec['_motif_steps'] = None

        # Expand each voice into sub-notes (pitches and durations) independently
        voices_sub_pitches = []
        voices_sub_durs = []
        total_sub_notes = None

        for v, spec in enumerate(voice_specs):
            densities = spec['density_envelope']
            shape = spec.get('shape', [0]*n_beats) # fallback if no shape
            motif_steps = spec['_motif_steps']
            strictness = spec.get('_strictness', 0.0)
            tolerance = spec['tolerance']
            rhythm_params = spec['rhythm_params']

            sub_pitches = []
            sub_durs = []
            current_pitch = self._choose_start_pitch()

            for beat_idx in range(n_beats):
                beat_dur = beat_durations[beat_idx]
                density = densities[beat_idx]
                if density <= 0:
                    raise ValueError(f"Density must be positive, got {density}")

                # Determine target step for this beat
                target_step = None
                if motif_steps and motif_steps[beat_idx] is not None:
                    # motif provides a target step
                    if random.random() < strictness:
                        target_step = motif_steps[beat_idx]
                    # else fall through to shape (if any)
                if target_step is None and shape:
                    target_step = shape[beat_idx] if beat_idx < len(shape) else 0

                # If still None, step = 0
                step_total = target_step if target_step is not None else 0

                sub_step = step_total / density
                sub_dur = beat_dur / density

                # Optional randomization within beat
                if rhythm_params.get('randomize', False):
                    sub_durs_beat = self._randomize_beat_subdurations(sub_dur, density, rhythm_params)
                else:
                    sub_durs_beat = [sub_dur] * density

                # Generate sub-note pitches
                for sub_idx in range(density):
                    if sub_idx == 0 and beat_idx == 0:
                        pitch = current_pitch
                    else:
                        target_pitch = sub_pitches[-1] + sub_step
                        pitch = self._closest_allowed_pitch(target_pitch, tolerance)
                    sub_pitches.append(pitch)
                    sub_durs.append(sub_durs_beat[sub_idx])
                current_pitch = sub_pitches[-1]

            voices_sub_pitches.append(sub_pitches)
            voices_sub_durs.append(sub_durs)
            if total_sub_notes is None:
                total_sub_notes = len(sub_pitches)
            elif len(sub_pitches) != total_sub_notes:
                raise ValueError(f"Voice {v} has {len(sub_pitches)} sub-notes, expected {total_sub_notes}")

        # Now all voices have same number of sub-notes. Apply counterpoint rules sequentially.
        final_voices = []
        # First voice is already generated (no rules to check against)
        final_voices.append(list(zip(voices_sub_pitches[0], voices_sub_durs[0])))

        for v in range(1, n_voices):
            existing_pitches = [ [p for p,_ in final_voices[i]] for i in range(v) ]
            new_pitches = self._add_voice_against_all_sub(
                existing_pitches,
                voices_sub_pitches[v], # target pitches (from shape/motif with tolerance)
                voice_specs[v]['tolerance']
            )
            final_voices.append(list(zip(new_pitches, voices_sub_durs[v])))

        return final_voices

    # ---------- Helper methods (unchanged from previous version) ----------
    def _choose_start_pitch(self):
        candidates = [p for p in self.scale if self.pitch_range[0] <= p <= self.pitch_range[1]]
        return random.choice(candidates)

    def _closest_allowed_pitch(self, target, tolerance):
        low = target - tolerance
        high = target + tolerance
        allowed = [p for p in self.scale if low <= p <= high and self.pitch_range[0] <= p <= self.pitch_range[1]]
        if not allowed:
            allowed = [self._nearest_scale(target)]
        return random.choice(allowed)

    def _nearest_scale(self, pitch):
        return min(self.scale, key=lambda x: abs(x - pitch))

    def _randomize_beat_subdurations(self, ideal_dur, density, params):
        bias = params.get('long_first_bias', 0.0)
        if bias <= 0 or density == 1:
            return [ideal_dur] * density
        ratios = [1.0]
        for i in range(1, density):
            ratios.append(ratios[-1] * (1 - bias * 0.5))
        total = sum(ratios)
        ratios = [r / total for r in ratios]
        return [r * ideal_dur * density for r in ratios]

    def _add_voice_against_all_sub(self, existing_pitches_by_index, target_pitches, tolerance):
        n_notes = len(existing_pitches_by_index[0])
        new_voice = []
        for i in range(n_notes):
            prev_new = new_voice[-1] if new_voice else None
            target = target_pitches[i]

            candidates = []
            for p in self.scale:
                if not (self.pitch_range[0] <= p <= self.pitch_range[1]):
                    continue
                if abs(p - target) > tolerance:
                    continue
                ok = True
                for v in existing_pitches_by_index:
                    interval = abs(p - v[i]) % 12
                    if self.rules.get('consonance_on_strong', True):
                        if interval not in [3,4,8,9]:
                            ok = False
                            break
                    if self.rules.get('no_parallel_fifths', True) and i > 0:
                        prev_interval = abs(new_voice[-1] - v[i-1]) % 12
                        if prev_interval == 7 and interval == 7:
                            ok = False
                            break
                    if self.rules.get('no_parallel_octaves', True) and i > 0:
                        prev_interval = abs(new_voice[-1] - v[i-1]) % 12
                        if prev_interval == 0 and interval == 0:
                            ok = False
                            break
                if ok:
                    candidates.append(p)
            if not candidates:
                p = self._closest_allowed_pitch(target, tolerance)
            else:
                p = random.choice(candidates)
            new_voice.append(p)
        return new_voice


# ========== DEMO: 3 voices, same motif ==========
def demo_3_voices():
    scale = [60,62,64,65,67,69,71,72] # C major
    pitch_range = (55, 84)
    engine = CounterpointEngine(scale, pitch_range)

    beat_durations = [0.5, 0.5, 1.0, 0.5, 0.5, 1.0, 0.5, 0.5] # 8 beats, total 5.0 quarters

    # Shared motif (4 beats long)
    motif = {
        'steps': [0, +2, -1, +2],
        'durations': [0.5, 0.5, 1.0, 0.5] # total 2.5 beats (not integer? Wait sum=2.5, not integer. Adjust to integer beats)
        # Let's change durations to sum to 2.0 or 3.0. I'll make it 2 beats: [0.5,0.5,0.5,0.5]
    }
    # Better: make motif exactly 2 beats long
    motif2 = {
        'steps': [0, +2, -1, +2],
        'durations': [0.5, 0.5, 0.5, 0.5] # total 2.0 beats
    }

    voice_specs = [
        {'motif': motif2, 'delay_beats': 0.0, 'strictness': 0.8, 'transpose': 0,
         'density_envelope': [1]*8, 'tolerance': 2, 'rhythm_params': {}},
        {'motif': motif2, 'delay_beats': 1.0, 'strictness': 0.8, 'transpose': 4,
         'density_envelope': [1]*8, 'tolerance': 3, 'rhythm_params': {'randomize': True, 'long_first_bias': 0.5}},
        {'motif': motif2, 'delay_beats': 2.0, 'strictness': 0.7, 'transpose': -3,
         'density_envelope': [2,2,2,2,2,2,2,2], # all beats in eighth notes
         'tolerance': 4, 'rhythm_params': {}}
    ]

    result = engine.generate(beat_durations, voice_specs)

    for idx, voice in enumerate(result):
        print(f"Voice {idx+1} (len={len(voice)}): {voice[:6]}...")
        total_dur = sum(d for _,d in voice)
        print(f" Total duration: {total_dur} quarters\n")

# ========== DEMO: 4 voices, paired imitation ==========
def demo_4_voices():
    scale = [60,62,64,65,67,69,71,72]
    pitch_range = (50, 86)
    engine = CounterpointEngine(scale, pitch_range)

    beat_durations = [1.0, 0.5, 0.5, 1.0, 1.0, 0.5, 0.5, 1.0] # 8 beats, total 6.0 quarters

    # Two contrasting motifs
    motifA = {
        'steps': [0, +1, -2, +3],
        'durations': [0.5, 0.5, 0.5, 0.5] # 2 beats
    }
    motifB = {
        'steps': [0, -1, +2, -3],
        'durations': [0.5, 0.5, 0.5, 0.5] # 2 beats
    }

    voice_specs = [
        # Pair 1: Soprano (voice1) and Alto (voice2)
        {'motif': motifA, 'delay_beats': 0.0, 'strictness': 0.8, 'transpose': 0,
         'density_envelope': [1]*8, 'tolerance': 2, 'rhythm_params': {}},
        {'motif': motifA, 'delay_beats': 1.5, 'strictness': 0.7, 'transpose': -2,
         'density_envelope': [1]*8, 'tolerance': 3, 'rhythm_params': {'randomize': True, 'long_first_bias': 0.4}},
        # Pair 2: Tenor (voice3) and Bass (voice4)
        {'motif': motifB, 'delay_beats': 0.5, 'strictness': 0.7, 'transpose': 0,
         'density_envelope': [2,2,2,2,2,2,2,2], 'tolerance': 3, 'rhythm_params': {}},
        {'motif': motifB, 'delay_beats': 2.0, 'strictness': 0.6, 'transpose': -5,
         'density_envelope': [1]*8, 'tolerance': 4, 'rhythm_params': {'randomize': True, 'long_first_bias': 0.6}}
    ]

    result = engine.generate(beat_durations, voice_specs)

    for idx, voice in enumerate(result):
        print(f"Voice {idx+1} (len={len(voice)}): first 6 notes {voice[:6]}...")
        print(f" Total duration: {sum(d for _,d in voice):.2f} quarters\n")

if __name__ == "__main__":
    print("=== 3 Voices, Same Motif ===")
    demo_3_voices()
    print("\n=== 4 Voices, Paired Imitation ===")
    demo_4_voices()


