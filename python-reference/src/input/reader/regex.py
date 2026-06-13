# input/reader/parser/regex.py

import re

# ORNAMENT           = (
#     r"\\(prall|prallup|pralldown|upprall|downprall|"
#     r"prallprall|lineprall|prallmordent|mordent|"
#     r"upmordent|downmordent|trill|turn|reverseturn|"
#     r"shortfermata|fermata|longfermata|verylongfermata)"
# )

# --- Primitives ---
NAME          = r"[a-zA-Z][a-zA-Z0-9_]*"
EQUALS        = r"\s*=\s*"
INT           = r"[0-9]+"
FLOAT         = r"[0-9]+\.[0-9]+"
STRING        = r'"[^"]*"'
OPERATION     = r'(?:[+\-*/]\d+)+|[a-zA-Z][a-zA-Z0-9_]*(?:\(\d+(?:,\d+)*\))?'

# --- Pitch components (for post-processing) ---
PITCH_NAME    = "[A-G][1-8]|[a-g]|p"
ACCIDENTAL    = "[b#]{0,2}|n+"
OCTAVE        = "[',]*"

# --- Duration ---
DURATION      = r"longa|breve|\d{1,3}\.*"

# --- Post-note elements ---
ARTICULATION  = "-[.>^_!+]"
TIE           = "~"

# --- Assignments ---
BANG_CONST    = rf"!\s*({NAME})"
ASSIGN_INT    = rf"!\s*({NAME}){EQUALS}({INT})"
ASSIGN_FLOAT  = rf"!\s*({NAME}){EQUALS}({FLOAT})"
ASSIGN_CONST  = rf"!\s*({NAME}){EQUALS}({NAME})"
ASSIGN_STRING = rf"!\s*({NAME}){EQUALS}({STRING})"

# ==========================================
# WHOLE-UNIT CAPTURES (for first-pass matching)
# ==========================================

# Pitch as ONE whole unit
PITCH_UNIT    = rf"(?:{PITCH_NAME})(?:{ACCIDENTAL})(?:{OCTAVE})"

# --- Chord ---
# Chord core: captures entire content as ONE whole string
# Content must be space-separated PITCH_UNITs
CHORD_CORE    = rf"<(?!<)({PITCH_UNIT}(?:\s+{PITCH_UNIT})*?)>"

# Modifier as ONE whole unit (non-capturing for grouping)
MODIFIER      = rf"\\(?:{NAME})(?:{EQUALS}(?:{FLOAT}|{INT}|{NAME}|{STRING}))?"

# Multiple modifiers as ONE whole string (non-capturing)
MODIFIERS     = rf"(?:{MODIFIER})*"

# ==========================================
# MAIN PATTERNS (first pass)
# ==========================================

# NOTE: (PITCH) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
NOTE    = rf"({PITCH_UNIT})({DURATION})?({ARTICULATION})?({MODIFIERS})?({TIE})?"

# CHORD: (CHORD_CORE) (DURATION)? (ARTICULATION)? (MODIFIERS)? (TIE)?
CHORD   = rf"{CHORD_CORE}({DURATION})?({ARTICULATION})?({MODIFIERS})?({TIE})?"

# REST: r(DURATION)?
REST    = rf"r({DURATION})?"

# DRUM: x (DURATION)? (NAME|INT)
DRUM    = rf"x({DURATION})?({NAME}|{INT})"

# --- Composite ---
QUOTED_ID = STRING
# SEQUENCE = r"\[\s*.*\s*\]"
# PARALLEL = r"<<\s*.*\s*>>"
# COMPOSITE = f"{SEQUENCE}|{PARALLEL}"

# --- compiled patterns ---

def parse_chord(chord_content: str):
    """Drill deeper: split '<C E G>' content into individual pitches"""
    # Remove angle brackets if present
    content = chord_content.strip('<>')
    # Split by whitespace and parse each pitch
    pitches = content.split()
    return [parse_pitch(p) for p in pitches]

def parse_pitch(pitch_str: str):
    """Drill deeper: split 'C#4' into atoms"""
    pattern = re.compile(rf"({PITCH_NAME})({ACCIDENTAL})({OCTAVE})")
    match = pattern.fullmatch(pitch_str)
    if match:
        return (match.group(1), match.group(2), match.group(3))
    return None

def parse_modifiers(modifiers_str: str):
    """Split '\\vol=80\\tempo=120' into list of (name, value) tuples"""
    if not modifiers_str:
        return []
    modifier = rf"\\({NAME})(?:{EQUALS}({FLOAT}|{INT}|{NAME}|{STRING}))?"
    pattern = re.compile(modifier)
    return pattern.findall(modifiers_str)  # [('vol', '80'), ('tempo', '120')]


NOTE_RE          = re.compile(NOTE)
CHORD_RE         = re.compile(CHORD)
REST_RE          = re.compile(REST)
DRUM_RE          = re.compile(DRUM)
INT_RE           = re.compile(INT)
FLOAT_RE         = re.compile(FLOAT)
STRING_RE        = re.compile(STRING)
BANG_CONST_RE    = re.compile(BANG_CONST)
ASSIGN_INT_RE    = re.compile(ASSIGN_INT)
ASSIGN_FLOAT_RE  = re.compile(ASSIGN_FLOAT)
ASSIGN_CONST_RE  = re.compile(ASSIGN_CONST)
ASSIGN_STRING_RE = re.compile(ASSIGN_STRING)
OPERATION_RE     = re.compile(OPERATION)
MODIFIER_RE      = re.compile(MODIFIERS)

def show(name: str, pat ) -> None:
    if isinstance(pat, str):
        print(f"\n{name}:\n  {pat}")
    else:
        print(f"\n{name}:\n  {pat.pattern}")

if __name__ == "__main__":
    show("NOTE_RE", NOTE_RE)
    show("CHORD_RE", CHORD_RE)
    show("REST_RE", REST_RE)
    show("DRUM_RE", DRUM_RE)
    show("INT_RE", INT_RE)
    show("FLOAT_RE", FLOAT_RE)
    show("STRING_RE", STRING_RE)
    show("BANG_CONST_RE", BANG_CONST_RE)
    show("ASSIGN_INT_RE", ASSIGN_INT_RE)
    show("ASSIGN_FLOAT_RE", ASSIGN_FLOAT_RE)
    show("ASSIGN_CONST_RE", ASSIGN_CONST_RE)
    show("ASSIGN_STRING_RE", ASSIGN_STRING_RE)
    show("OPERATION_RE", OPERATION_RE)
    show("MODIFIER_RE", MODIFIER_RE)



