import re
from typing import List, Dict, Any, Optional, Tuple, Union
from dataclasses import dataclass, field, asdict
import json


# ============ Composite Pattern Data Structures ============

@dataclass
class MusicComponent:
    """Base component in the composite pattern"""
    type: str = "component"
    start_time: float = 0.0
    duration: float = 0.0
    voice_id: str = "default"
    staff_id: str = "default"


@dataclass
class Note(MusicComponent):
    """Leaf node representing a single note"""
    pitch: str = ""
    octave: int = 4
    midi_number: int = 60
    accidental: Optional[str] = None
    velocity: int = 80
    articulation: List[str] = field(default_factory=list)
    ornaments: List[str] = field(default_factory=list)
    is_rest: bool = False
    tie_start: bool = False
    tie_stop: bool = False
    tie_continue: bool = False
    grace_notes: List['Note'] = field(default_factory=list)
    dots: int = 0

    def __post_init__(self):
        self.type = "rest" if self.is_rest else "note"


@dataclass
class Chord(MusicComponent):
    """Composite node representing a chord"""
    notes: List[Note] = field(default_factory=list)

    def __post_init__(self):
        self.type = "chord"


@dataclass
class Tuplet(MusicComponent):
    """Composite node representing a tuplet group"""
    ratio: Tuple[int, int] = (3, 2)
    notes: List[Union[Note, Chord]] = field(default_factory=list)

    def __post_init__(self):
        self.type = "tuplet"


@dataclass
class GraceGroup(MusicComponent):
    """Composite node representing grace notes"""
    grace_type: str = "grace"
    notes: List[Note] = field(default_factory=list)

    def __post_init__(self):
        self.type = "grace_group"


@dataclass
class SimultaneousMusic(MusicComponent):
    """Node representing simultaneous voices (<< ... >>)"""
    voices: List['Voice'] = field(default_factory=list)

    def __post_init__(self):
        self.type = "simultaneous"


@dataclass
class Voice(MusicComponent):
    """Composite node representing a voice within a staff"""
    name: str = ""
    components: List[Union[Note, Chord, Tuplet, GraceGroup, 'Measure', 'SimultaneousMusic']] = field \
        (default_factory=list)

    def __post_init__(self):
        self.type = "voice"


@dataclass
class Measure(MusicComponent):
    """Composite node representing a measure"""
    number: int = 0
    time_signature: Tuple[int, int] = (4, 4)
    voices: Dict[str, List[MusicComponent]] = field(default_factory=dict)

    def __post_init__(self):
        self.type = "measure"


@dataclass
class Staff(MusicComponent):
    """Composite node representing a staff"""
    name: str = ""
    instrument: str = "piano"
    midi_program: int = 0
    voices: Dict[str, Voice] = field(default_factory=dict)

    def __post_init__(self):
        self.type = "staff"


@dataclass
class Score(MusicComponent):
    """Root composite node"""
    title: str = ""
    composer: str = ""
    opus: str = ""
    tempo: float = 120.0
    tempo_unit: int = 4
    tempo_text: str = ""
    key_signature: str = "c major"
    staves: Dict[str, Staff] = field(default_factory=dict)
    duration: float = 0.0
    version: str = ""

    def __post_init__(self):
        self.type = "score"


# ============ Keyword Definitions ============

KEYWORDS = {
    'pitch': ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'r'],
    'accidentals': ['is', 'es', 'isis', 'eses'],
    'octave': [',', "'"],
    'duration': ['1', '2', '4', '8', '16', '32', '64', '128', '\\breve', '\\longa'],
    'dots': ['.'],

    'articulations': {
        '-.': 'staccato',
        '--': 'tenuto',
        '-^': 'accent',
        '-+': 'marcato',
        '-|': 'staccatissimo',
        '->': 'strong_accent',
        '-1': 'finger_1', '-2': 'finger_2', '-3': 'finger_3',
        '-4': 'finger_4', '-5': 'finger_5'
    },

    'dynamics': [
        '\\ppppp', '\\pppp', '\\ppp', '\\pp', '\\p', '\\mp', '\\mf',
        '\\f', '\\ff', '\\fff', '\\ffff', '\\fffff',
        '\\fp', '\\sf', '\\sff', '\\sfz', '\\sp'
    ],

    'ornaments': ['\\trill', '\\mordent', '\\prall', '\\turn', '\\tremolo'],
    'tempo': ['\\tempo'],
    'repeats': ['\\repeat', '\\alternative', '\\unfoldRepeats'],
    'tuplets': ['\\tuplet', '\\times'],
    'grace': ['\\grace', '\\acciaccatura', '\\appoggiatura'],
    'context': ['\\new', '\\context', '\\score', '\\midi', '\\layout'],
    'instrument': ['\\set', 'midiInstrument'],
    'ties': ['~'],
    'slurs': ['(', ')'],
    'chords': ['<', '>'],
    'simultaneous': ['<<', '>>'],
    'voice_sep': ['\\\\'],
    'time': ['\\time'],
    'key': ['\\key'],
    'clef': ['\\clef'],
    'version': ['\\version'],
    'header': ['\\header'],
    'relative': ['\\relative'],
    'with': ['\\with'],
}

# Mappings
NOTE_NAME_MAP = {'c': 0, 'd': 2, 'e': 4, 'f': 5, 'g': 7, 'a': 9, 'b': 11}
DURATION_MAP = {
    '1': 4.0, '2': 2.0, '4': 1.0, '8': 0.5,
    '16': 0.25, '32': 0.125, '64': 0.0625, '128': 0.03125,
    '\\breve': 8.0, '\\longa': 16.0
}
DYNAMIC_VELOCITY = {
    'ppppp': 8, 'pppp': 12, 'ppp': 16, 'pp': 32, 'p': 48,
    'mp': 64, 'mf': 80, 'f': 96, 'ff': 112, 'fff': 120,
    'ffff': 126, 'fffff': 127
}
GM_INSTRUMENTS = {
    'piano': 0, 'acoustic grand': 0, 'violin': 40, 'viola': 41, 'cello': 42, 'flute': 73,
    'trumpet': 56, 'trombone': 57, 'tuba': 58, 'french horn': 60,
    'oboe': 68, 'bassoon': 70, 'clarinet': 71, 'saxophone': 65,
    'guitar': 24, 'bass': 32, 'drums': 114, 'voice': 53
}


# ============ Tokenization ============

def tokenize(lilypond_text: str) -> List[str]:
    """Split Lilypond text into tokens based on keywords"""
    # Remove comments (but keep version command)
    text = re.sub(r'^[ \t]*%.*$', '', lilypond_text, flags=re.MULTILINE)
    text = re.sub(r'%\{.*?%\}', '', text, flags=re.DOTALL)

    # Remove curly brace content for tokenization (they'll be handled separately)
    # But first, we need to extract header content

    # Enhanced pattern for LilyPond tokens
    pattern = r'<<|>>|\\\\|\\[a-zA-Z]+|\"[^\"]*\"|[a-g][is]*[,!\']*\d*\.*|r\d*\.*|R\d*\.*|\d+|[()<>~\[\]{}]|-[\.,\^\+\|>]|\.+'
    return re.findall(pattern, text)


# ============ Helper Functions for Brace Content ============

def extract_braced_content(tokens: List[str], start_idx: int) -> str:
    """Extract content between curly braces starting at start_idx"""
    if start_idx >= len(tokens) or tokens[start_idx] != '{':
        return ""

    brace_count = 1
    content_tokens = []
    i = start_idx + 1

    while i < len(tokens) and brace_count > 0:
        token = tokens[i]
        if token == '{':
            brace_count += 1
        elif token == '}':
            brace_count -= 1
            if brace_count == 0:
                break

        if brace_count > 0:
            content_tokens.append(token)
        i += 1

    return ' '.join(content_tokens)


def extract_parenthesized_content(tokens: List[str], start_idx: int) -> str:
    """Extract content between parentheses starting at start_idx"""
    if start_idx >= len(tokens) or tokens[start_idx] != '(':
        return ""

    paren_count = 1
    content_tokens = []
    i = start_idx + 1

    while i < len(tokens) and paren_count > 0:
        token = tokens[i]
        if token == '(':
            paren_count += 1
        elif token == ')':
            paren_count -= 1
            if paren_count == 0:
                break

        if paren_count > 0:
            content_tokens.append(token)
        i += 1

    return ' '.join(content_tokens)


def extract_angled_content(tokens: List[str], start_idx: int) -> str:
    """Extract content between angle brackets starting at start_idx"""
    if start_idx >= len(tokens) or tokens[start_idx] != '<':
        return ""

    angle_count = 1
    content_tokens = []
    i = start_idx + 1

    while i < len(tokens) and angle_count > 0:
        token = tokens[i]
        if token == '<':
            angle_count += 1
        elif token == '>':
            angle_count -= 1
            if angle_count == 0:
                break

        if angle_count > 0:
            content_tokens.append(token)
        i += 1

    return ' '.join(content_tokens)


# ============ Parse Context ============

@dataclass
class ParseContext:
    """Immutable parsing context with polyphony support"""
    position: float = 0.0
    measure_start: float = 0.0
    measure_number: int = 1
    time_signature: Tuple[int, int] = (4, 4)
    current_dynamic: str = 'mf'
    current_tuplet: Optional[Tuple[int, int]] = None
    current_instrument: str = 'piano'
    current_staff: str = 'default'
    current_voice: str = 'default'
    voice_counter: int = 0
    current_clef: str = 'treble'
    relative_octave: int = 4
    relative_mode: bool = False
    last_note_pitch: Optional[str] = None
    last_note_octave: int = 4

    simultaneous_stack: List[Dict[str, List[MusicComponent]]] = field(default_factory=list)
    current_voice_components: Dict[str, List[MusicComponent]] = field(default_factory=dict)

    grace_group: Optional[GraceGroup] = None
    tie_start: Optional[Note] = None
    building_chord: List[Note] = field(default_factory=list)
    building_tuplet: List[Union[Note, Chord]] = field(default_factory=list)

    voice_components: Dict[str, List[MusicComponent]] = field(default_factory=dict)

    score: Score = field(default_factory=lambda: Score(type="score"))

    variable_defs: Dict[str, str] = field(default_factory=dict)
    current_variable: Optional[str] = None
    building_variable: List[str] = field(default_factory=list)

    staff_name: Optional[str] = None

    def update(self, **kwargs) -> 'ParseContext':
        """Create new context with updated values"""
        new_context = ParseContext(
            position=kwargs.get('position', self.position),
            measure_start=kwargs.get('measure_start', self.measure_start),
            measure_number=kwargs.get('measure_number', self.measure_number),
            time_signature=kwargs.get('time_signature', self.time_signature),
            current_dynamic=kwargs.get('current_dynamic', self.current_dynamic),
            current_tuplet=kwargs.get('current_tuplet', self.current_tuplet),
            current_instrument=kwargs.get('current_instrument', self.current_instrument),
            current_staff=kwargs.get('current_staff', self.current_staff),
            current_voice=kwargs.get('current_voice', self.current_voice),
            voice_counter=kwargs.get('voice_counter', self.voice_counter),
            current_clef=kwargs.get('current_clef', self.current_clef),
            relative_octave=kwargs.get('relative_octave', self.relative_octave),
            relative_mode=kwargs.get('relative_mode', self.relative_mode),
            last_note_pitch=kwargs.get('last_note_pitch', self.last_note_pitch),
            last_note_octave=kwargs.get('last_note_octave', self.last_note_octave),
            simultaneous_stack=kwargs.get('simultaneous_stack', self.simultaneous_stack.copy()),
            current_voice_components=kwargs.get('current_voice_components',
                                                self.current_voice_components.copy()),
            grace_group=kwargs.get('grace_group', self.grace_group),
            tie_start=kwargs.get('tie_start', self.tie_start),
            building_chord=kwargs.get('building_chord', self.building_chord.copy()),
            building_tuplet=kwargs.get('building_tuplet', self.building_tuplet.copy()),
            voice_components=kwargs.get('voice_components', self.voice_components.copy()),
            score=kwargs.get('score', self.score),
            variable_defs=kwargs.get('variable_defs', self.variable_defs.copy()),
            current_variable=kwargs.get('current_variable', self.current_variable),
            building_variable=kwargs.get('building_variable', self.building_variable.copy()),
            staff_name=kwargs.get('staff_name', self.staff_name)
        )

        if new_context.current_voice not in new_context.current_voice_components:
            new_context.current_voice_components[new_context.current_voice] = []

        return new_context


# ============ Parsing Helper Functions ============

def parse_pitch(note_token: str, context: ParseContext) -> Tuple[str, int, Optional[str], bool]:
    """Parse pitch from token with relative mode support"""
    if not note_token or note_token[0] == 'r':
        return ('r', 4, None, True)

    # Remove duration numbers and dots for pitch parsing
    base_token = re.sub(r'\d*\.*$', '', note_token)

    base = base_token[0]
    rest = base_token[1:]

    accidental = None
    if rest.startswith('isis'):
        accidental = 'doublesharp'
        rest = rest[4:]
    elif rest.startswith('eses'):
        accidental = 'doubleflat'
        rest = rest[4:]
    elif rest.startswith('is'):
        accidental = 'sharp'
        rest = rest[2:]
    elif rest.startswith('es'):
        accidental = 'flat'
        rest = rest[2:]

    # Calculate octave
    if context.relative_mode and context.last_note_pitch:
        # Relative mode octave calculation
        base_idx = NOTE_NAME_MAP.get(base, 0)
        last_idx = NOTE_NAME_MAP.get(context.last_note_pitch, 0)

        octave = context.last_note_octave

        # Check octave marks
        if rest:
            octave += rest.count("'") - rest.count(",")
        else:
            # In relative mode, try to stay close to last note
            if base_idx < last_idx and last_idx - base_idx > 4:
                octave += 1
            elif base_idx > last_idx and base_idx - last_idx > 4:
                octave -= 1
    else:
        # Absolute octave calculation
        octave = 4
        if rest:
            octave += rest.count("'") - rest.count(",")

    return (base, octave, accidental, False)


def parse_duration(note_token: str) -> Tuple[float, int]:
    """Parse duration from token"""
    match = re.search(r'(\d+|\\\\breve|\\\\longa)', note_token)
    if not match:
        return (1.0, 0)

    dur_key = match.group(1)
    duration = DURATION_MAP.get(dur_key, 1.0)

    dots = note_token.count('.')

    if dots:
        dot_val = duration
        for _ in range(dots):
            dot_val /= 2
            duration += dot_val

    return (duration, dots)


def create_note_from_token(token: str, context: ParseContext,
                           duration_override: Optional[float] = None) -> Note:
    """Create a Note object from a token"""
    pitch_name, octave, accidental, is_rest = parse_pitch(token, context)
    duration, dots = parse_duration(token)

    if duration_override:
        duration = duration_override

    if context.current_tuplet and not context.grace_group:
        ratio = context.current_tuplet
        duration = duration * ratio[1] / ratio[0]

    midi_number = 0
    if not is_rest:
        base = NOTE_NAME_MAP.get(pitch_name, 0)
        accidental_offset = {'sharp': 1, 'flat': -1, 'doublesharp': 2,
                             'doubleflat': -2, None: 0}[accidental]
        midi_number = 60 + (octave - 4) * 12 + base + accidental_offset

    velocity = DYNAMIC_VELOCITY.get(context.current_dynamic.replace('\\', ''), 80)

    return Note(
        type="rest" if is_rest else "note",
        pitch=pitch_name,
        octave=octave,
        midi_number=midi_number,
        accidental=accidental,
        duration=duration,
        start_time=context.position,
        velocity=velocity,
        is_rest=is_rest,
        dots=dots,
        voice_id=context.current_voice,
        staff_id=context.current_staff
    )


def create_measure(context: ParseContext) -> Measure:
    """Create a measure from current voice components"""
    measure = Measure(
        type="measure",
        number=context.measure_number,
        time_signature=context.time_signature,
        start_time=context.measure_start,
        duration=context.position - context.measure_start
    )

    for voice_id, components in context.voice_components.items():
        measure.voices[voice_id] = components.copy()

    return measure


def expand_variable(variable_name: str, context: ParseContext) -> List[str]:
    """Expand a variable reference to its tokens"""
    if variable_name in context.variable_defs:
        # Re-tokenize the variable content
        return tokenize(context.variable_defs[variable_name])
    return []


# ============ Main Parsing Functions ============

def process_note(context: ParseContext, token: str) -> ParseContext:
    """Process a note token"""

    if context.grace_group:
        grace_note = create_note_from_token(token, context, duration_override=0.125)
        context.grace_group.notes.append(grace_note)
        context.grace_group.duration += grace_note.duration
        return context

    if context.building_chord is not None and len(context.building_chord) > 0:
        note = create_note_from_token(token, context)
        return context.update(building_chord=context.building_chord + [note])

    note = create_note_from_token(token, context)

    # Update last note for relative mode
    if not note.is_rest:
        context = context.update(
            last_note_pitch=note.pitch,
            last_note_octave=note.octave
        )

    if context.tie_start:
        if context.tie_start.pitch == note.pitch:
            context.tie_start.tie_stop = True
            note.tie_start = True
            note.duration += context.tie_start.duration
            note.start_time = context.tie_start.start_time
        context = context.update(tie_start=None)

    # Update both current_voice_components AND voice_components
    voice_comps = context.current_voice_components.copy()
    main_voice_comps = context.voice_components.copy()

    if context.current_voice not in voice_comps:
        voice_comps[context.current_voice] = []
    if context.current_voice not in main_voice_comps:
        main_voice_comps[context.current_voice] = []

    if context.current_tuplet:
        context.building_tuplet.append(note)
        target_count = context.current_tuplet[0]
        if len(context.building_tuplet) >= target_count:
            tuplet = Tuplet(
                type="tuplet",
                ratio=context.current_tuplet,
                notes=context.building_tuplet,
                start_time=context.building_tuplet[0].start_time,
                duration=sum(n.duration for n in context.building_tuplet),
                voice_id=context.current_voice,
                staff_id=context.current_staff
            )
            voice_comps[context.current_voice].append(tuplet)
            main_voice_comps[context.current_voice].append(tuplet)
            context = context.update(
                current_tuplet=None,
                building_tuplet=[],
                position=context.position + tuplet.duration
            )
            return context.update(
                current_voice_components=voice_comps,
                voice_components=main_voice_comps
            )
        else:
            return context.update(
                building_tuplet=context.building_tuplet,
                current_voice_components=voice_comps,
                voice_components=main_voice_comps
            )
    else:
        voice_comps[context.current_voice].append(note)
        main_voice_comps[context.current_voice].append(note)
        return context.update(
            position=context.position + note.duration,
            current_voice_components=voice_comps,
            voice_components=main_voice_comps
        )


def process_chord(context: ParseContext) -> ParseContext:
    """Create a chord from built notes"""
    if context.building_chord:
        duration = context.building_chord[0].duration
        chord = Chord(
            type="chord",
            notes=context.building_chord,
            start_time=context.position,
            duration=duration,
            voice_id=context.current_voice,
            staff_id=context.current_staff
        )

        voice_comps = context.current_voice_components.copy()
        main_voice_comps = context.voice_components.copy()

        if context.current_voice not in voice_comps:
            voice_comps[context.current_voice] = []
        if context.current_voice not in main_voice_comps:
            main_voice_comps[context.current_voice] = []

        voice_comps[context.current_voice].append(chord)
        main_voice_comps[context.current_voice].append(chord)

        # Update last note for relative mode (use first note of chord)
        if chord.notes and not chord.notes[0].is_rest:
            context = context.update(
                last_note_pitch=chord.notes[0].pitch,
                last_note_octave=chord.notes[0].octave
            )

        return context.update(
            building_chord=[],
            position=context.position + duration,
            current_voice_components=voice_comps,
            voice_components=main_voice_comps
        )
    return context


def process_articulation(context: ParseContext, token: str) -> ParseContext:
    """Add articulation to the last note"""
    voice_comps = context.current_voice_components.copy()
    main_voice_comps = context.voice_components.copy()

    if context.current_voice in voice_comps and voice_comps[context.current_voice]:
        last = voice_comps[context.current_voice][-1]
        if isinstance(last, Note):
            articulation = KEYWORDS['articulations'][token]
            last.articulation.append(articulation)

            # Also update in main voice components
            if context.current_voice in main_voice_comps and main_voice_comps[context.current_voice]:
                main_last = main_voice_comps[context.current_voice][-1]
                if isinstance(main_last, Note):
                    main_last.articulation.append(articulation)

    return context.update(
        current_voice_components=voice_comps,
        voice_components=main_voice_comps
    )


def process_tie(context: ParseContext) -> ParseContext:
    """Mark the last note as tied"""
    voice_comps = context.current_voice_components.copy()
    main_voice_comps = context.voice_components.copy()

    if context.current_voice in voice_comps and voice_comps[context.current_voice]:
        last = voice_comps[context.current_voice][-1]
        if isinstance(last, Note):
            last.tie_continue = True

            # Also update in main voice components
            if context.current_voice in main_voice_comps and main_voice_comps[context.current_voice]:
                main_last = main_voice_comps[context.current_voice][-1]
                if isinstance(main_last, Note):
                    main_last.tie_continue = True

            return context.update(
                tie_start=last,
                current_voice_components=voice_comps,
                voice_components=main_voice_comps
            )
    return context


def start_simultaneous(context: ParseContext) -> ParseContext:
    """Start a simultaneous music block"""
    simultaneous_voices = context.current_voice_components.copy()
    context.simultaneous_stack.append(simultaneous_voices)

    return context.update(
        current_voice_components={},
        current_voice='simul_0'
    )


def end_simultaneous(context: ParseContext) -> ParseContext:
    """End a simultaneous music block"""
    if not context.simultaneous_stack:
        return context

    simult_voices = context.current_voice_components

    # Calculate the actual duration for the simultaneous block
    max_duration = 0.0
    for components in simult_voices.values():
        for comp in components:
            comp_end = comp.start_time + comp.duration
            max_duration = max(max_duration, comp_end)

    simult = SimultaneousMusic(
        type="simultaneous",
        start_time=context.measure_start,
        duration=max_duration - context.measure_start,
        voice_id='simultaneous',
        staff_id=context.current_staff
    )

    # Create voice objects for each voice in the simultaneous block
    for voice_id, components in simult_voices.items():
        voice = Voice(
            type="voice",
            name=voice_id,
            components=components,
            start_time=context.measure_start,
            duration=max_duration - context.measure_start
        )
        simult.voices.append(voice)

    # Get the previous voices from stack
    prev_voices = context.simultaneous_stack.pop()

    # Add the simultaneous block to the appropriate voice in the previous context
    current_voice_in_prev = context.current_voice
    if current_voice_in_prev not in prev_voices:
        prev_voices[current_voice_in_prev] = []
    prev_voices[current_voice_in_prev].append(simult)

    # Also add to main voice_components for measure creation
    main_voice_comps = context.voice_components.copy()
    if current_voice_in_prev not in main_voice_comps:
        main_voice_comps[current_voice_in_prev] = []
    main_voice_comps[current_voice_in_prev].append(simult)

    new_current_voice = list(prev_voices.keys())[-1] if prev_voices else 'default'

    return context.update(
        current_voice_components=prev_voices,
        voice_components=main_voice_comps,
        current_voice=new_current_voice,
        position=context.measure_start + simult.duration
    )


def next_voice(context: ParseContext) -> ParseContext:
    """Move to next voice within simultaneous block"""
    voice_num = len(context.current_voice_components)
    new_voice = f"simul_{voice_num}"

    return context.update(current_voice=new_voice)


def process_command(context: ParseContext, command: str,
                    next_token: Optional[str] = None,
                    all_tokens: Optional[List[str]] = None,
                    current_index: int = 0) -> Tuple[ParseContext, int]:
    """Process Lilypond commands"""
    new_context = context
    skip_count = 0

    if command == '\\version' and next_token:
        new_context.score.version = next_token.strip('"')
        skip_count = 1

    elif command == '\\header':
        # Extract header content
        header_content = extract_braced_content(all_tokens, current_index + 1)
        if header_content:
            # Parse header
            title_match = re.search(r'title\s*=\s*"([^"]+)"', header_content)
            if title_match:
                new_context.score.title = title_match.group(1)
            composer_match = re.search(r'composer\s*=\s*"([^"]+)"', header_content)
            if composer_match:
                new_context.score.composer = composer_match.group(1)
            opus_match = re.search(r'opus\s*=\s*"([^"]+)"', header_content)
            if opus_match:
                new_context.score.opus = opus_match.group(1)
            skip_count = len(tokenize(header_content)) + 1

    elif command == '\\tempo':
        if next_token:
            # Check for tempo with text: \tempo "Allegro" 4 = 120
            if next_token.startswith('"'):
                # Extract tempo text
                tempo_text = next_token.strip('"')
                new_context.score.tempo_text = tempo_text
                # Look ahead for the numeric tempo
                if all_tokens and len(all_tokens) > current_index + 2:
                    tempo_match = re.search(r'(\d+)=(\d+)', all_tokens[current_index + 2])
                    if tempo_match:
                        new_context.score.tempo_unit = int(tempo_match.group(1))
                        new_context.score.tempo = float(tempo_match.group(2))
                        skip_count = 3
            else:
                # Simple \tempo 4 = 120
                tempo_match = re.search(r'(\d+)=(\d+)', next_token)
                if tempo_match:
                    new_context.score.tempo_unit = int(tempo_match.group(1))
                    new_context.score.tempo = float(tempo_match.group(2))
                    skip_count = 1

    elif command in KEYWORDS['dynamics']:
        new_context = new_context.update(current_dynamic=command)

    elif command in ['\\tuplet', '\\times'] and next_token:
        match = re.search(r'(\d+)/(\d+)', next_token)
        if match:
            ratio = (int(match.group(1)), int(match.group(2)))
            new_context = new_context.update(current_tuplet=ratio, building_tuplet=[])
            skip_count = 1

    elif command in KEYWORDS['grace']:
        grace_type = command.replace('\\', '')
        new_context = new_context.update(
            grace_group=GraceGroup(
                type="grace_group",
                grace_type=grace_type,
                start_time=new_context.position,
                voice_id=new_context.current_voice,
                staff_id=new_context.current_staff
            )
        )

    elif command == '\\time' and next_token:
        match = re.search(r'(\d+)/(\d+)', next_token)
        if match:
            time_sig = (int(match.group(1)), int(match.group(2)))

            # Only create a measure if we have accumulated some music
            if new_context.position > new_context.measure_start and new_context.voice_components:
                measure = create_measure(new_context)

                # Ensure staff exists
                if new_context.current_staff not in new_context.score.staves:
                    new_context.score.staves[new_context.current_staff] = Staff(
                        type="staff",
                        name=new_context.staff_name or new_context.current_staff,
                        instrument=new_context.current_instrument
                    )

                # Add measure to appropriate voices
                for voice_id in new_context.voice_components:
                    if voice_id not in new_context.score.staves[new_context.current_staff].voices:
                        new_context.score.staves[new_context.current_staff].voices[voice_id] = Voice(
                            type="voice",
                            name=voice_id
                        )
                    new_context.score.staves[new_context.current_staff].voices[voice_id].components.append(measure)

            new_context = new_context.update(
                time_signature=time_sig,
                measure_start=new_context.position,
                measure_number=new_context.measure_number + 1,
                voice_components={}
            )
            skip_count = 1

    elif command == '\\key' and next_token:
        # Simple key signature handling
        key = next_token
        if key in ['c', 'd', 'e', 'f', 'g', 'a', 'b']:
            # Check for mode (major/minor)
            if all_tokens and len(all_tokens) > current_index + 2 and all_tokens[current_index + 2] == '\\minor':
                new_context.score.key_signature = f"{key} minor"
                skip_count = 2
            else:
                new_context.score.key_signature = f"{key} major"
                skip_count = 1

    elif command == '\\clef' and next_token:
        new_context = new_context.update(current_clef=next_token)
        skip_count = 1

    elif command == '\\relative' and next_token:
        # Handle \relative c'' or \relative c' { ... }
        new_context = new_context.update(relative_mode=True)
        if next_token and next_token[0] in 'abcdefgr':
            # Parse the reference pitch
            _, octave, _, _ = parse_pitch(next_token, new_context)
            new_context = new_context.update(
                relative_octave=octave,
                last_note_pitch=next_token[0],
                last_note_octave=octave
            )
            skip_count = 1

    elif command == '\\new':
        if next_token and next_token in ['Voice', 'Staff', 'PianoStaff']:
            if next_token == 'Voice':
                voice_id = f"voice_{new_context.voice_counter}"
                new_context = new_context.update(
                    current_voice=voice_id,
                    voice_counter=new_context.voice_counter + 1
                )
                skip_count = 1
            elif next_token in ['Staff', 'PianoStaff']:
                # Look for with block or name
                staff_id = f"staff_{len(new_context.score.staves)}"
                staff_name = None
                skip_count = 1

                # Check if there's a name or with block
                if all_tokens and len(all_tokens) > current_index + 2:
                    if all_tokens[current_index + 2] == '=':
                        # Named staff: \new Staff = "right" ...
                        if len(all_tokens) > current_index + 3:
                            staff_name = all_tokens[current_index + 3].strip('"')
                            staff_id = staff_name
                            skip_count = 3
                    elif all_tokens[current_index + 2] == '\\with':
                        # Handle \with block
                        with_content = extract_braced_content(all_tokens, current_index + 3)
                        if with_content:
                            # Parse instrument from with block
                            instr_match = re.search(r'midiInstrument\s*=\s*"([^"]+)"', with_content)
                            if instr_match:
                                new_context = new_context.update(
                                    current_instrument=instr_match.group(1)
                                )
                            skip_count = len(tokenize(with_content)) + 3

                new_context.score.staves[staff_id] = Staff(
                    type="staff",
                    name=staff_name or staff_id,
                    instrument=new_context.current_instrument
                )
                new_context = new_context.update(
                    current_staff=staff_id,
                    current_voice='default',
                    staff_name=staff_name or staff_id
                )

    elif command == '\\set' and next_token and 'midiInstrument' in next_token:
        instr_match = re.search(r'#?"([^"]+)"', next_token)
        if instr_match:
            instr = instr_match.group(1)
            if new_context.current_staff in new_context.score.staves:
                new_context.score.staves[new_context.current_staff].instrument = instr
                new_context.score.staves[new_context.current_staff].midi_program = GM_INSTRUMENTS.get(instr.lower(), 0)
            new_context = new_context.update(current_instrument=instr)
            skip_count = 1

    elif command == '\\score':
        # Score block is handled at the top level
        pass

    elif command.endswith('=') and next_token:
        # Variable definition: baseA = { ... }
        var_name = command[:-1]  # Remove the '='
        # Find the content in braces
        if next_token == '{':
            var_content = extract_braced_content(all_tokens, current_index + 1)
            if var_content:
                new_context.variable_defs[var_name] = var_content
                skip_count = len(tokenize(var_content)) + 1

    return new_context, skip_count


def process_token(context: ParseContext, token: str,
                  next_token: Optional[str] = None,
                  all_tokens: Optional[List[str]] = None,
                  current_index: int = 0) -> ParseContext:
    """Process a single token"""

    if token.startswith('\\'):
        new_context, skip = process_command(context, token, next_token, all_tokens, current_index)
        return new_context

    if token == '<<':
        return start_simultaneous(context)
    if token == '>>':
        return end_simultaneous(context)

    if token == '\\\\':
        return next_voice(context)

    if token and token[0] in 'abcdefgr' and not token.startswith('\\'):
        return process_note(context, token)

    if token in KEYWORDS['articulations']:
        return process_articulation(context, token)

    if token == '~':
        return process_tie(context)

    if token == '<':
        return context.update(building_chord=[])
    if token == '>':
        return process_chord(context)

    # Check for variable references
    if token in context.variable_defs:
        # Expand variable
        var_tokens = expand_variable(token, context)
        temp_context = context
        for var_token in var_tokens:
            temp_context = process_token(temp_context, var_token, None, all_tokens, current_index)
        return temp_context

    return context


# ============ Main Parser ============

def parse_to_composite(lilypond_text: str) -> Score:
    """Parse Lilypond text to composite score structure"""

    tokens = tokenize(lilypond_text)

    # Create Score with explicit type
    score = Score(type="score")
    score.staves['default'] = Staff(type="staff", name='default')

    context = ParseContext(score=score)

    # Process all tokens
    i = 0
    while i < len(tokens):
        current_token = tokens[i]
        next_token = tokens[i + 1] if i + 1 < len(tokens) else None
        context = process_token(context, current_token, next_token, tokens, i)
        i += 1

    # Create final measure if there's remaining music
    if context.position > context.measure_start and context.voice_components:
        # Ensure staff exists
        if context.current_staff not in context.score.staves:
            context.score.staves[context.current_staff] = Staff(
                type="staff",
                name=context.staff_name or context.current_staff,
                instrument=context.current_instrument
            )

        # Add measure to appropriate voices
        staff = context.score.staves[context.current_staff]
        for voice_id, components in context.voice_components.items():
            if voice_id not in staff.voices:
                staff.voices[voice_id] = Voice(type="voice", name=voice_id)

            # Create a new measure with the components
            measure = Measure(
                type="measure",
                number=context.measure_number,
                time_signature=context.time_signature,
                start_time=context.measure_start,
                duration=context.position - context.measure_start
            )
            measure.voices[voice_id] = components
            staff.voices[voice_id].components.append(measure)

    context.score.duration = context.position

    return context.score


def score_to_json(score: Score, indent: int = 2) -> str:
    """Convert score to JSON"""

    def default_serializer(obj):
        if hasattr(obj, '__dataclass_fields__'):
            result = {}
            for field_name in obj.__dataclass_fields__:
                value = getattr(obj, field_name)
                if value is not None and value != [] and value != {} and value != "" and value != 0:
                    result[field_name] = value
            return result
        raise TypeError(f"Object of type {type(obj)} is not JSON serializable")

    return json.dumps(asdict(score), default=default_serializer, indent=indent)


# ============ Example Usage ============

if __name__ == "__main__":
    test_input2 = """
    \\version "2.24.1"

    \\header {
      title = "Mikrokosmos 48"
      composer = "Bela Bartok"
      opus = "Sz 107"
    }

    global = {
      \\key c \\major
      \\time 5/4
      \\tempo 4 = 184
    }

    baseA =  { g4 b d c a }

    rightHand = \\relative c'' {
      \\global
      \\clef treble

      r1 r4 | r1 r4 | r2 d2.\\f~ | d4 b a d2~ | d4 g, a2 c4 | b g a2.  |
      r4 d1~ | d4 g f e c | r4 d g, a d | b g a2. |
      r4 b\\mf d c a | g b d c a |
      r4 g d' c g| b1~ b4~ | b4 c b a g | b d2 b4 a| r4 g d' c d | b1~ b4~ |
      b g c d b | a g r r2 | r4 d'1\\f~ | d4 e f e c | e d1 | g4 f g e c | f e d2 c4 |
      f2 e d4~ | d e c f2~ | f4 e f d c | e d1~ | d1~ d4~ | d1~ d4 | r4 r1 |
    }

    leftHand = \\relative c' {
      \\global
      \\clef bass

      g4\\mf b d c a |  \\baseA \\baseA \\baseA \\baseA \\baseA
      \\baseA \\baseA \\baseA \\baseA
      g2 r4 r2 | f1\\f~ f4 |
      e b'2 a4 b | g e a b g | a e2 a4 b | g2 e4 g a | e b'2 a4 b | g e a b g |
      e2. f4 r | r g c d a | g b d a f | g1~ g4~ | g b d c a | g1~ g4~ | g c d b f |
      r d'2 g, | c a4 b c | d1~ d4 | r2 g,4\\mf a c | d g, a2 c4 | d2 a c4~ | c d1\\p |
    }

    pianoMusic = {
      \\new PianoStaff <<
        \\new Staff = "right" \\with {
          midiInstrument = "acoustic grand"
        } \\rightHand
        \\new Staff = "left" \\with {
          midiInstrument = "acoustic grand"
        } { \\clef bass \\leftHand }
      >>
    }

    \\score {
      \\pianoMusic
      \\layout { }
      \\midi {
        \\tempo 4 = 184
      }
    }
    """

    score = parse_to_composite(test_input2)

    # json_output = score_to_json(score)
    print(score)

    print(f"\n=== Polyphonic Score Summary ===")
    print(f"Title: {score.title}")
    print(f"Composer: {score.composer}")
    print(f"Opus: {score.opus}")
    print(f"Version: {score.version}")
    print(f"Tempo: {score.tempo} BPM (Unit: {score.tempo_unit})")
    print(f"Tempo Text: {score.tempo_text}")
    print(f"Key Signature: {score.key_signature}")
    print(f"Duration: {score.duration} beats")

    for staff_id, staff in score.staves.items():
        print(f"\nStaff: {staff_id} ({staff.name}) - {staff.instrument}")
        print(f"  Voices: {len(staff.voices)}")

        for voice_id, voice in staff.voices.items():
            print(f"    Voice: {voice_id}")
            measure_count = sum(1 for comp in voice.components if comp.type == "measure")
            print(f"      Measures: {measure_count}")