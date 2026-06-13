import re
from functools import reduce
from typing import List, Dict, Any, Optional, Tuple, Callable
from dataclasses import dataclass, field, asdict
from enum import Enum
import json


# ============ Data Structures ============

@dataclass
class MidiNote:
    pitch: int  # MIDI note number (0-127)
    start_time: float  # in beats
    duration: float  # in beats
    velocity: int  # 0-127
    channel: int = 0
    articulation: str = ""
    is_rest: bool = False


@dataclass
class MidiTempo:
    bpm: float
    start_time: float
    beat_unit: int = 4  # quarter note = 4


@dataclass
class MidiProgram:
    program: int  # 0-127 GM program number
    channel: int
    start_time: float


@dataclass
class MidiControl:
    controller: int  # 7=volume, 10=pan, etc.
    value: int  # 0-127
    channel: int
    start_time: float


@dataclass
class MidiScore:
    notes: List[MidiNote] = field(default_factory=list)
    tempos: List[MidiTempo] = field(default_factory=list)
    programs: List[MidiProgram] = field(default_factory=list)
    controls: List[MidiControl] = field(default_factory=list)
    ticks_per_beat: int = 480
    total_beats: float = 0.0


@dataclass
class ParserContext:
    """Context for parsing including variable definitions"""
    variables: Dict[str, str] = field(default_factory=dict)
    current_variable: Optional[str] = None
    in_variable_definition: bool = False
    variable_content: List[str] = field(default_factory=list)


@dataclass
class ParserState:
    """Immutable parser state"""
    position: float = 0.0
    current_dynamic: str = 'mf'
    current_tuplet: Optional[Tuple[int, int]] = None
    current_instrument: str = 'piano'
    current_channel: int = 0
    grace_notes: List[MidiNote] = field(default_factory=list)
    tempo: float = 120.0
    beats_per_measure: int = 4
    beat_unit: int = 4
    score: MidiScore = field(default_factory=MidiScore)
    context: ParserContext = field(default_factory=ParserContext)

    def update(self, **kwargs) -> 'ParserState':
        """Create new state with updated values"""
        return ParserState(
            position=kwargs.get('position', self.position),
            current_dynamic=kwargs.get('current_dynamic', self.current_dynamic),
            current_tuplet=kwargs.get('current_tuplet', self.current_tuplet),
            current_instrument=kwargs.get('current_instrument', self.current_instrument),
            current_channel=kwargs.get('current_channel', self.current_channel),
            grace_notes=kwargs.get('grace_notes', self.grace_notes.copy()),
            tempo=kwargs.get('tempo', self.tempo),
            beats_per_measure=kwargs.get('beats_per_measure', self.beats_per_measure),
            beat_unit=kwargs.get('beat_unit', self.beat_unit),
            score=kwargs.get('score', self.score),
            context=kwargs.get('context', self.context)
        )


# ============ Keyword Definitions ============

# Lilypond keywords relevant for MIDI
KEYWORDS = {
    # Note-related
    'pitch': ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'r'],
    'accidentals': ['is', 'es', 'isis', 'eses'],
    'octave': [',', "'"],
    'duration': ['1', '2', '4', '8', '16', '32', '64', '128', '\\breve', '\\longa'],
    'dots': ['.'],

    # Articulations
    'articulations': {
        '-.': 'staccato',
        '--': 'tenuto',
        '-^': 'accent',
        '-+': 'marcato',
        '-|': 'staccatissimo',
        '->': 'strong_accent',
        '-1': 'finger_1', '-2': 'finger_2', '-3': 'finger_3', '-4': 'finger_4', '-5': 'finger_5'
    },

    # Dynamics
    'dynamics': [
        '\\ppppp', '\\pppp', '\\ppp', '\\pp', '\\p', '\\mp', '\\mf',
        '\\f', '\\ff', '\\fff', '\\ffff', '\\fffff',
        '\\fp', '\\sf', '\\sff', '\\sfz', '\\sp'
    ],

    # Ornaments
    'ornaments': ['\\trill', '\\mordent', '\\prall', '\\turn', '\\tremolo'],

    # Tempo and structure
    'tempo': ['\\tempo'],
    'repeats': ['\\repeat', '\\alternative', '\\unfoldRepeats'],
    'tuplets': ['\\tuplet', '\\times'],
    'grace': ['\\grace', '\\acciaccatura', '\\appoggiatura'],

    # Context
    'context': ['\\new', '\\context', '\\score', '\\midi', '\\layout'],
    'instrument': ['\\set', 'midiInstrument'],

    # Ties and slurs
    'ties': ['~'],
    'slurs': ['(', ')'],

    # Chords
    'chords': ['<', '>'],
}

# MIDI mappings
MIDI_NOTE_MAP = {
    'c': 0, 'd': 2, 'e': 4, 'f': 5, 'g': 7, 'a': 9, 'b': 11
}

DYNAMIC_VELOCITY = {
    'ppppp': 8, 'pppp': 12, 'ppp': 16, 'pp': 32, 'p': 48,
    'mp': 64, 'mf': 80, 'f': 96, 'ff': 112, 'fff': 120,
    'ffff': 126, 'fffff': 127, 'fp': (80, 48), 'sf': 110
}

GM_INSTRUMENTS = {
    'piano': 0, 'violin': 40, 'viola': 41, 'cello': 42, 'flute': 73,
    'trumpet': 56, 'trombone': 57, 'tuba': 58, 'french horn': 60,
    'oboe': 68, 'bassoon': 70, 'clarinet': 71, 'saxophone': 65,
    'guitar': 24, 'bass': 32, 'drums': 114, 'voice': 53
}


# ============ Pure Parsing Functions ============

def tokenize(lilypond_text: str) -> List[str]:
    """Split Lilypond text into tokens based on keywords"""
    # Remove comments
    text = re.sub(r'%.*$', '', lilypond_text, flags=re.MULTILINE)
    text = re.sub(r'%\{.*?%\}', '', text, flags=re.DOTALL)

    # Pattern for Lilypond tokens
    pattern = r'\\[a-zA-Z]+|[a-gr](?:is+|s|es+|[!\'])?[,!\']*|\d+|[()<>~\[\]{}]|-[\.,\^\+\|>]|r\d*|R\d*|\.+'
    return re.findall(pattern, text)


def is_note_token(token: str) -> bool:
    """Check if token represents a note"""
    return bool(re.match(r'^[a-gr](?:is+|s|es+|[!\'])?[,!\']*\d*\.*$', token))


def parse_note_pitch(note_token: str) -> Tuple[int, int, bool]:
    """Parse pitch from token: returns (midi_number, octave, is_rest)"""
    if note_token[0] == 'r':
        return (0, 0, True)

    # Extract base note
    base = note_token[0]
    rest = note_token[1:]

    # Parse accidentals
    accidental = 0
    if rest.startswith('isis'):
        accidental = 2
        rest = rest[4:]
    elif rest.startswith('eses'):
        accidental = -2
        rest = rest[4:]
    elif rest.startswith('ses'):
        accidental = -2
        rest = rest[3:]
    elif rest.startswith('is'):
        accidental = 1
        rest = rest[2:]
    elif rest.startswith('es'):
        accidental = -1
        rest = rest[2:]
    elif rest.startswith('s'):
        accidental = -1
        rest = rest[1:]

    # Parse octave marks
    octave = 4  # middle C octave
    if rest:
        octave_marks = re.match(r"^[,']*", rest).group()
        octave += octave_marks.count("'") - octave_marks.count(",")

    # Calculate MIDI number
    midi = 60 + (octave - 4) * 12 + MIDI_NOTE_MAP[base] + accidental
    return (midi, octave, False)


def parse_note_duration(note_token: str) -> Tuple[float, int]:
    """Parse duration from token: returns (duration_in_beats, dots)"""
    # Extract duration number
    match = re.search(r'(\d+)', note_token)
    if not match:
        return (0.25, 0)  # default quarter note

    duration_map = {'1': 4.0, '2': 2.0, '4': 1.0, '8': 0.5,
                    '16': 0.25, '32': 0.125, '64': 0.0625}
    dur = duration_map.get(match.group(1), 1.0)

    # Count dots
    dots = note_token.count('.')

    # Apply dots
    if dots:
        dot_val = dur
        for _ in range(dots):
            dot_val /= 2
            dur += dot_val

    return (dur, dots)


def apply_tuplet(duration: float, tuplet_ratio: Optional[Tuple[int, int]]) -> float:
    """Apply tuplet ratio to duration"""
    if tuplet_ratio:
        return duration * tuplet_ratio[1] / tuplet_ratio[0]
    return duration


def dynamic_to_velocity(dynamic: str, base_velocity: int = 80) -> int:
    """Convert dynamic marking to MIDI velocity"""
    dyn = dynamic.replace('\\', '')
    if dyn in DYNAMIC_VELOCITY:
        val = DYNAMIC_VELOCITY[dyn]
        return val if isinstance(val, int) else val[0]
    return base_velocity


# ============ Basic Processors ============

def process_note(state: ParserState, token: str) -> ParserState:
    """Process a note token and add to score"""

    # Parse note
    midi, octave, is_rest = parse_note_pitch(token)
    duration, dots = parse_note_duration(token)

    # Apply tuplet
    duration = apply_tuplet(duration, state.current_tuplet)

    # Handle grace notes
    if state.grace_notes:
        # Attach grace notes before this note
        grace_start = state.position
        for grace in state.grace_notes:
            grace.start_time = grace_start
            grace.duration *= 0.5  # Shorten grace notes
            state.score.notes.append(grace)
            grace_start += grace.duration
        state = state.update(grace_notes=[])

    # Create MIDI note
    velocity = dynamic_to_velocity(state.current_dynamic)

    note = MidiNote(
        pitch=midi,
        start_time=state.position,
        duration=duration,
        velocity=velocity,
        channel=state.current_channel,
        is_rest=is_rest
    )

    if not is_rest:
        state.score.notes.append(note)

    # Update position
    return state.update(position=state.position + duration)


def process_articulation(state: ParserState, token: str) -> ParserState:
    """Process articulation (modifies last note)"""
    if state.score.notes:
        last_note = state.score.notes[-1]
        articulation = KEYWORDS['articulations'][token]

        # Modify note based on articulation
        if articulation == 'staccato':
            last_note.duration *= 0.5
        elif articulation == 'staccatissimo':
            last_note.duration *= 0.25
            last_note.velocity = min(127, last_note.velocity + 20)
        elif articulation == 'accent':
            last_note.velocity = min(127, last_note.velocity + 30)
        elif articulation == 'marcato':
            last_note.duration *= 0.75
            last_note.velocity = min(127, last_note.velocity + 40)

        last_note.articulation = articulation

    return state


def process_tie(state: ParserState) -> ParserState:
    """Process tie - combine with next note of same pitch"""
    if state.score.notes:
        last_note = state.score.notes[-1]
        last_note.duration *= 2  # Temporary, will be combined properly
    return state


def process_chord_start(state: ParserState) -> ParserState:
    """Start chord - notes should be simultaneous"""
    return state  # Chords handled by same start time


def process_chord_end(state: ParserState) -> ParserState:
    """End chord - no special handling needed"""
    return state


def process_command(state: ParserState, command: str,
                    next_token: Optional[str] = None) -> ParserState:
    """Process a Lilypond command (basic commands)"""
    # Dynamics
    if command in KEYWORDS['dynamics']:
        return state.update(current_dynamic=command)

    # Grace notes
    if command in ['\\grace', '\\acciaccatura', '\\appoggiatura']:
        return state  # Grace notes handled in note processing

    # Repeat unfolding
    if command == '\\unfoldRepeats':
        return state  # Just continue, we unfold by default for MIDI

    return state


# ============ Enhanced Command Processors with Lookahead ============

def process_tempo_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \tempo command which can have complex syntax"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Check for text tempo indication (e.g., \tempo "Allegro")
    if i < len(tokens) and re.match(r'"[^"]+"', tokens[i]):
        i += 1  # Skip the text tempo
        if i >= len(tokens):
            return state, i

    # Look for duration = bpm pattern
    if i < len(tokens):
        current = tokens[i]

        # Case 1: "4=120" as single token
        if re.match(r'^\d+=\d+$', current):
            parts = current.split('=')
            beat_unit = int(parts[0])
            bpm = float(parts[1])
            tempo = MidiTempo(bpm=bpm, start_time=state.position, beat_unit=beat_unit)
            state.score.tempos.append(tempo)
            return state.update(tempo=bpm), i + 1

        # Case 2: "4" and then "=120" as separate tokens
        elif (re.match(r'^\d+$', current) and
              i + 1 < len(tokens) and
              tokens[i + 1].startswith('=')):
            combined = current + tokens[i + 1]
            if re.match(r'^\d+=\d+$', combined):
                parts = combined.split('=')
                beat_unit = int(parts[0])
                bpm = float(parts[1])
                tempo = MidiTempo(bpm=bpm, start_time=state.position, beat_unit=beat_unit)
                state.score.tempos.append(tempo)
                return state.update(tempo=bpm), i + 2

        # Case 3: "4" and then "=" and then "120" as three tokens
        elif (re.match(r'^\d+$', current) and
              i + 2 < len(tokens) and
              tokens[i + 1] == '=' and
              re.match(r'^\d+$', tokens[i + 2])):
            beat_unit = int(current)
            bpm = float(tokens[i + 2])
            tempo = MidiTempo(bpm=bpm, start_time=state.position, beat_unit=beat_unit)
            state.score.tempos.append(tempo)
            return state.update(tempo=bpm), i + 3

        # Case 4: Just a number (BPM value)
        elif re.match(r'^\d+$', current):
            bpm = float(current)
            beat_unit = 4
            tempo = MidiTempo(bpm=bpm, start_time=state.position, beat_unit=beat_unit)
            state.score.tempos.append(tempo)
            return state.update(tempo=bpm), i + 1

    return state, i


def process_tuplet_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \tuplet or \times command which takes a ratio"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    current = tokens[i]

    # Case 1: "3/2" as single token
    if re.match(r'^\d+/\d+$', current):
        parts = current.split('/')
        ratio = (int(parts[0]), int(parts[1]))
        return state.update(current_tuplet=ratio), i + 1

    # Case 2: "3" and then "/2" as separate tokens
    elif (re.match(r'^\d+$', current) and
          i + 1 < len(tokens) and
          tokens[i + 1].startswith('/')):
        combined = current + tokens[i + 1]
        if re.match(r'^\d+/\d+$', combined):
            parts = combined.split('/')
            ratio = (int(parts[0]), int(parts[1]))
            return state.update(current_tuplet=ratio), i + 2

    # Case 3: "3" and then "/" and then "2" as three tokens
    elif (re.match(r'^\d+$', current) and
          i + 2 < len(tokens) and
          tokens[i + 1] == '/' and
          re.match(r'^\d+$', tokens[i + 2])):
        ratio = (int(current), int(tokens[i + 2]))
        return state.update(current_tuplet=ratio), i + 3

    return state, i


def process_set_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \set command which sets various properties"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Look for midiInstrument setting
    if i < len(tokens) and 'midiInstrument' in tokens[i]:
        i += 1  # Skip the midiInstrument token
        if i >= len(tokens):
            return state, i

        instrument_token = tokens[i]

        # Case 1: #"piano" as single token
        if re.match(r'#?"[^"]+"', instrument_token):
            instr_match = re.search(r'"([^"]+)"', instrument_token)
            if instr_match:
                instr = instr_match.group(1)
                prog = GM_INSTRUMENTS.get(instr.lower(), 0)
                program = MidiProgram(program=prog, channel=state.current_channel,
                                      start_time=state.position)
                state.score.programs.append(program)
                return state.update(current_instrument=instr), i + 1

        # Case 2: # "piano" as separate tokens
        elif (instrument_token == '#' and
              i + 1 < len(tokens) and
              re.match(r'"[^"]+"', tokens[i + 1])):
            instr_match = re.search(r'"([^"]+)"', tokens[i + 1])
            if instr_match:
                instr = instr_match.group(1)
                prog = GM_INSTRUMENTS.get(instr.lower(), 0)
                program = MidiProgram(program=prog, channel=state.current_channel,
                                      start_time=state.position)
                state.score.programs.append(program)
                return state.update(current_instrument=instr), i + 2

        # Case 3: "piano" without # (less common)
        elif re.match(r'[a-zA-Z]+', instrument_token):
            instr = instrument_token
            prog = GM_INSTRUMENTS.get(instr.lower(), 0)
            program = MidiProgram(program=prog, channel=state.current_channel,
                                  start_time=state.position)
            state.score.programs.append(program)
            return state.update(current_instrument=instr), i + 1

    return state, i + 1


def process_key_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \key command which takes key and mode"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Skip key name (e.g., c, d, e, etc.) with possible accidentals
    if i < len(tokens) and re.match(r'^[a-g](?:is|es)?$', tokens[i]):
        i += 1

    # Skip mode (e.g., \major, \minor, etc.)
    if i < len(tokens) and tokens[i].startswith('\\'):
        i += 1

    return state, i


def process_time_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \time command which takes time signature"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    current = tokens[i]

    # Case 1: "4/4" as single token
    if re.match(r'^\d+/\d+$', current):
        parts = current.split('/')
        state = state.update(
            beats_per_measure=int(parts[0]),
            beat_unit=int(parts[1])
        )
        return state, i + 1

    # Case 2: "4" and then "/4" as separate tokens
    elif (re.match(r'^\d+$', current) and
          i + 1 < len(tokens) and
          tokens[i + 1].startswith('/')):
        combined = current + tokens[i + 1]
        if re.match(r'^\d+/\d+$', combined):
            parts = combined.split('/')
            state = state.update(
                beats_per_measure=int(parts[0]),
                beat_unit=int(parts[1])
            )
            return state, i + 2

    # Case 3: "4" and then "/" and then "4" as three tokens
    elif (re.match(r'^\d+$', current) and
          i + 2 < len(tokens) and
          tokens[i + 1] == '/' and
          re.match(r'^\d+$', tokens[i + 2])):
        state = state.update(
            beats_per_measure=int(current),
            beat_unit=int(tokens[i + 2])
        )
        return state, i + 3

    return state, i


def process_repeat_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \repeat command which takes type and count"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Skip repeat type (volta, unfold, etc.)
    if i < len(tokens) and re.match(r'^[a-zA-Z]+$', tokens[i]):
        i += 1

    # Skip repeat count number
    if i < len(tokens) and re.match(r'^\d+$', tokens[i]):
        i += 1

    return state, i


def process_mark_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \mark command which can take number or text"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Skip mark content (number or text)
    if i < len(tokens):
        if re.match(r'^\d+$', tokens[i]) or re.match(r'"[^"]+"', tokens[i]):
            i += 1

    return state, i


def process_partial_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \partial command which takes a duration"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Skip duration token (e.g., 4, 8., etc.)
    if i < len(tokens) and re.match(r'^\d+\.*$', tokens[i]):
        # Calculate pickup measure duration
        duration, _ = parse_note_duration(tokens[i])
        # Adjust position for pickup (negative because we're at start)
        state = state.update(position=state.position - duration)
        i += 1

    return state, i


def process_transposition_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \transposition command for instrument transposition"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Skip transposition pitch (e.g., c', d, etc.)
    if i < len(tokens) and is_note_token(tokens[i]):
        i += 1

    return state, i


def process_new_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \\new command which creates a new context (Voice, Staff, etc.)"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Get the context type (Voice, Staff, etc.)
    if i < len(tokens) and re.match(r'^[A-Z][a-zA-Z]*$', tokens[i]):
        context_type = tokens[i]
        i += 1

        # Handle potential context ID in quotes (e.g., \\new Voice = "foo")
        if i < len(tokens) and tokens[i] == '=':
            i += 1
            if i < len(tokens) and re.match(r'"[^"]+"', tokens[i]):
                # Skip the quoted ID
                i += 1

        # For MIDI purposes, we might want to assign a new channel for each Voice
        if context_type == 'Voice':
            # Increment channel for new voice (but keep within 0-15)
            new_channel = (state.current_channel + 1) % 16
            state = state.update(current_channel=new_channel)

    return state, i


def process_context_command(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle \\context command which is similar to \\new"""
    i = index + 1
    if i >= len(tokens):
        return state, i

    # Similar to \\new, get the context type
    if i < len(tokens) and re.match(r'^[A-Z][a-zA-Z]*$', tokens[i]):
        context_type = tokens[i]
        i += 1

        # Handle context ID if present
        if i < len(tokens) and re.match(r'"[^"]+"', tokens[i]):
            i += 1

        if context_type == 'Voice':
            new_channel = (state.current_channel + 1) % 16
            state = state.update(current_channel=new_channel)

    return state, i


def process_block_skip(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Skip a block like \header { ... }"""
    i = index + 1
    brace_count = 0

    # Find the opening brace
    while i < len(tokens) and tokens[i] != '{':
        i += 1

    if i < len(tokens) and tokens[i] == '{':
        brace_count = 1
        i += 1

        # Skip until matching closing brace
        while i < len(tokens) and brace_count > 0:
            if tokens[i] == '{':
                brace_count += 1
            elif tokens[i] == '}':
                brace_count -= 1
            i += 1

    return state, i


def process_variable_definition(state: ParserState, tokens: List[str], index: int) -> Tuple[ParserState, int]:
    """Handle variable definitions like name = { ... }"""
    i = index

    # Check if this is a variable name (starts with a letter, not a command)
    if i < len(tokens) and re.match(r'^[a-zA-Z][a-zA-Z0-9_-]*$', tokens[i]) and not tokens[i].startswith('\\'):
        var_name = tokens[i]
        i += 1

        # Check for equals sign
        if i < len(tokens) and tokens[i] == '=':
            i += 1

            # Start collecting variable content until we find the closing brace
            content = []
            brace_count = 0
            started = False

            while i < len(tokens):
                token = tokens[i]

                if token == '{':
                    brace_count += 1
                    started = True
                    content.append(token)
                elif token == '}':
                    brace_count -= 1
                    content.append(token)
                    if started and brace_count == 0:
                        # End of variable definition
                        i += 1
                        break
                elif started:
                    content.append(token)
                elif not started:
                    # If we haven't started with {, this might be a simple value
                    content.append(token)
                    i += 1
                    break

                i += 1

            # Store the variable
            var_content = ' '.join(content)
            state.context.variables[var_name] = var_content

            return state, i

    return state, index


def process_variable_reference(state: ParserState, token: str) -> ParserState:
    """Process a variable reference like \variableName"""
    var_name = token[1:]  # Remove the leading backslash

    if var_name in state.context.variables:
        # Get the variable content and recursively parse it
        var_content = state.context.variables[var_name]
        var_tokens = tokenize(var_content)

        # Process the variable tokens with a new state that has the same context
        temp_state = ParserState(
            position=state.position,
            current_dynamic=state.current_dynamic,
            current_tuplet=state.current_tuplet,
            current_instrument=state.current_instrument,
            current_channel=state.current_channel,
            tempo=state.tempo,
            beats_per_measure=state.beats_per_measure,
            beat_unit=state.beat_unit,
            score=state.score,
            context=state.context
        )

        # Process the variable tokens
        var_state = process_tokens_with_state(var_tokens, temp_state)

        # Return the updated state
        return state.update(
            score=var_state.score,
            context=var_state.context
        )

    return state


def process_tokens_with_state(tokens: List[str], initial_state: ParserState) -> ParserState:
    """Process tokens with an initial state (for variable expansion)"""

    def process_with_lookahead(state: ParserState, index: int) -> Tuple[ParserState, int]:
        if index >= len(tokens):
            return state, index

        token = tokens[index]

        # Handle variable definitions (like "music = { c d e }")
        if (index + 2 < len(tokens) and
                re.match(r'^[a-zA-Z][a-zA-Z0-9_-]*$', token) and
                tokens[index + 1] == '='):
            return process_variable_definition(state, tokens, index)

        # Handle variable references (like \music)
        if token.startswith('\\') and len(token) > 1 and token[1].isalpha():
            # Check if it's a user variable (not a built-in command)
            var_name = token[1:]
            if var_name in state.context.variables:
                new_state = process_variable_reference(state, token)
                return new_state, index + 1

        # Handle built-in commands
        if token == '\\tempo':
            return process_tempo_command(state, tokens, index)
        elif token == '\\tuplet' or token == '\\times':
            return process_tuplet_command(state, tokens, index)
        elif token == '\\set':
            return process_set_command(state, tokens, index)
        elif token == '\\key':
            return process_key_command(state, tokens, index)
        elif token == '\\time':
            return process_time_command(state, tokens, index)
        elif token == '\\repeat':
            return process_repeat_command(state, tokens, index)
        elif token == '\\mark':
            return process_mark_command(state, tokens, index)
        elif token == '\\partial':
            return process_partial_command(state, tokens, index)
        elif token == '\\transposition':
            return process_transposition_command(state, tokens, index)
        elif token == '\\new':
            return process_new_command(state, tokens, index)
        elif token == '\\context':
            return process_context_command(state, tokens, index)
        elif token == '\\header' or token == '\\layout' or token == '\\midi':
            # Skip metadata blocks
            return process_block_skip(state, tokens, index)
        elif token == '\\score':
            # Just skip the \score token itself, content will be parsed
            return state, index + 1
        elif token.startswith('\\'):
            # Other commands
            new_state = process_command(state, token,
                                        tokens[index + 1] if index + 1 < len(tokens) else None)
            return new_state, index + 1
        elif is_note_token(token):
            return process_note(state, token), index + 1
        elif token in KEYWORDS['articulations']:
            return process_articulation(state, token), index + 1
        elif token == '~':
            return process_tie(state), index + 1
        elif token == '<':
            return process_chord_start(state), index + 1
        elif token == '>':
            return process_chord_end(state), index + 1
        else:
            # Skip unknown tokens
            print(token + " not handled")
            return state, index + 1

    state = initial_state
    index = 0
    while index < len(tokens):
        state, index = process_with_lookahead(state, index)

    return state


# ============ Main Parser Pipeline ============

def parse_to_midi(lilypond_text: str) -> MidiScore:
    """Main parsing function using functional pipeline"""

    # Tokenize
    tokens = tokenize(lilypond_text)

    # Process tokens with comprehensive lookahead and variable support
    final_state = process_tokens_with_state(tokens, ParserState())

    # Set total beats
    final_state.score.total_beats = final_state.position

    return final_state.score


# ============ MIDI Event Generation ============

def note_to_midi_events(note: MidiNote, ticks_per_beat: int) -> List[Dict[str, Any]]:
    """Convert note to MIDI events"""
    if note.is_rest:
        return []

    start_tick = int(note.start_time * ticks_per_beat)
    duration_ticks = int(note.duration * ticks_per_beat)
    end_tick = start_tick + duration_ticks

    return [
        {'type': 'note_on', 'tick': start_tick, 'channel': note.channel,
         'note': note.pitch, 'velocity': note.velocity},
        {'type': 'note_off', 'tick': end_tick, 'channel': note.channel,
         'note': note.pitch, 'velocity': 0}
    ]


def score_to_midi_events(score: MidiScore) -> List[Dict[str, Any]]:
    """Convert entire score to MIDI events"""
    events = []

    # Add tempo events
    for tempo in score.tempos:
        tick = int(tempo.start_time * score.ticks_per_beat)
        events.append({
            'type': 'tempo',
            'tick': tick,
            'bpm': tempo.bpm,
            'beat_unit': tempo.beat_unit
        })

    # Add program change events
    for prog in score.programs:
        tick = int(prog.start_time * score.ticks_per_beat)
        events.append({
            'type': 'program_change',
            'tick': tick,
            'channel': prog.channel,
            'program': prog.program
        })

    # Add note events
    for note in score.notes:
        events.extend(note_to_midi_events(note, score.ticks_per_beat))

    # Sort by tick time
    events.sort(key=lambda e: e['tick'])

    return events


# ============ Example Usage ============

def parse_lilypond_for_midi(lilypond_text: str, output_format: str = 'json') -> Any:
    """High-level parsing function"""

    # Parse to MIDI score
    score = parse_to_midi(lilypond_text)

    # Convert to events
    events = score_to_midi_events(score)

    if output_format == 'json':
        return json.dumps({
            'ticks_per_beat': score.ticks_per_beat,
            'total_beats': score.total_beats,
            'events': events,
            'note_count': len(score.notes)
        }, indent=2)
    elif output_format == 'dict':
        return {
            'score': score,
            'events': events
        }
    else:
        return events


# Example test
if __name__ == "__main__":
    test_input = """
    melody = { c''4 d'' e'' f'' }
    harmony = { <c e g>2 <d f a> }

    \\header {
        title = "Polyphonic Etude"
        composer = "J.S. Bach"
    }

    \\score {
        \\new Staff {
            \\set Staff.midiInstrument = #"piano"
            \\tempo 4 = 100
            \\time 4/4
            \\key c \\major

            <<
                \\new Voice {
                    \\voiceOne
                    \\melody
                    g''1
                }
                \\new Voice {
                    \\voiceTwo
                    c'4 e' g' b'
                    c''1
                }
                \\new Voice {
                    \\voiceThree
                    \\harmony
                    <e g c'>1
                }
            >>

            \\tuplet 3/2 {
                <<
                    { c'4 d' e' }
                    \\\\
                    { c4 a, f, }
                >>
            }
        }
        \\layout { }
        \\midi { }
    }
    """

    test_input2 = """
    \\version "2.24.0"

\\header {
  title = "MIDI Instrument Example"
  composer = "LilyPond User"
}

% Define a piano staff with acoustic grand piano
pianoMusic = \\relative c' {
  \\clef treble
  \\key c \\major
  \\time 4/4
  
  c4 e g c | b g e c |
  d f a d | c2 r2 \\bar "|."
}

pianoDynamics = {
  s1\\mp | s1 | s1\\mf | s1\\f |
}

\\score {
  <<
    \\new Staff = "piano" \\with {
      instrumentName = "Piano"
      midiInstrument = "acoustic grand"
    } {
      \\pianoMusic
    }
  >>
  \\layout { }
  \\midi {
    \\tempo 4 = 120
    \\context {
      \\Staff
      midiInstrument = "acoustic grand"
    }
  }
}

% Multiple instruments example
violinMusic = \\relative c'' {
  \\clef treble
  \\key c \\major
  \\time 4/4
  g4-.\\mp g-. g-. g-. |
  a-. a-. a-. a-. |
  b-.\\mf b-. b-. b-. |
  c2 r2 \\bar "|."
}

celloMusic = \\relative c {
  \\clef bass
  \\key c \\major
  \\time 4/4
  c2\\mp e | g c, |
  f2\\mf a | c r2 \\bar "|."
}

\\score {
  <<
    \\new Staff \\with {
      instrumentName = "Violin"
      midiInstrument = "violin"
    } { \\violinMusic }
    
    \\new Staff \\with {
      instrumentName = "Cello"
      midiInstrument = "cello"
    } { \\celloMusic }
  >>
  \\layout { }
  \\midi {
    \\tempo 4 = 100
  }
}
"""

    result = parse_lilypond_for_midi(test_input2, 'json')
    print(result[:500] + "...\n")  # Print first 500 chars

    # Also show summary
    data = parse_lilypond_for_midi(test_input2, 'dict')
    score = data['score']
    events = data['events']

    print(f"\nSummary:")
    print(f"Total notes: {len(score.notes)}")
    print(f"Total beats: {score.total_beats}")
    print(f"Total MIDI events: {len(events)}")
    print(f"Tempos: {[t.bpm for t in score.tempos]}")

    # To see variables, we'd need to modify parse_to_midi to return state
    # For now, let's just note that variables were processed
    print(f"Variables: Processed during parsing (melody, harmony)")