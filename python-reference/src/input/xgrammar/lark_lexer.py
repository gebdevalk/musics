# src/input/grammar/lark_lexer.py

from lark import Lark

# ============================================
# Regex building blocks (Python side)
# ============================================

ABS_PITCH_NAME = r"(?:[A-G][1-8])"  # Keep as single unit
REL_PITCH_NAME = r"[a-gx]"
PITCH_NAME   = rf"(?:{ABS_PITCH_NAME}|{REL_PITCH_NAME})"
ACCIDENTAL   = r"(?:##|bb|[#bn])"          # non-capturing group
OCTAVE       = r"[',]"
MICROTONE    = r"(?:[+-]\d+(?:\.\d+)?)"    # non-capturing groups
DURATION     = r"(?:longa|brevis|\d{1,3})\.*?)"  # dots must follow a number
ARTICULATION = r"(?:-[.>^_!+])"            # non-capturing group
TIE          = r"~"                         # tie symbol
ORNAMENT     = (
    r"(?:\\(?:prall|prallup|pralldown|upprall|downprall|"
    r"prallprall|lineprall|prallmordent|mordent|"
    r"upmordent|downmordent|trill|turn|reverseturn|"
    r"shortfermata|fermata|longfermata|verylongfermata))"
)

# pitch = pitch_letter accidental? octave* microtone?
ABS_PITCH = rf"{ABS_PITCH_NAME}{ACCIDENTAL}?{MICROTONE}?"
REL_PITCH = rf"{REL_PITCH_NAME}{ACCIDENTAL}?{OCTAVE}*{MICROTONE}?"
PITCH = rf"{ABS_PITCH}|{REL_PITCH}"

# Wrap PITCH for safe embedding in larger patterns
PITCH_GROUP = rf"(?:{ABS_PITCH}|{REL_PITCH})"

# NOTE = pitch duration? articulation? ornament? tie?
NOTE_REGEX = rf"(?:{PITCH_GROUP})(?:{DURATION})?(?:{ARTICULATION})?(?:{ORNAMENT})?(?:{TIE})?"

# CHORD = < pitch+ > duration? articulation? ornament? tie?
CHORD_REGEX = rf"<(?:{PITCH_GROUP}(?:\s+{PITCH_GROUP})+)\s*>(?:{DURATION})?(?:{ARTICULATION})?(?:{ORNAMENT})?(?:{TIE})?"

# REST = r duration? tie? — negative lookahead stops "role", "r=4" etc. matching as REST
REST_REGEX = rf"r(?![a-zA-Z_=])(?:{DURATION})?(?:{TIE})?"

# ============================================
# Grammar (Lark) as f-string
# ============================================

MUSICAL_GRAMMAR = f"""
// ============================================
// Top-level
// ============================================

start : expr*

expr  : leaf
      | sequence
      | concurrent
      | operator_list
      | data_list
      | algorithm_literal
      | instruction
      | symbol


// ============================================
// Musical events (atomic lexer tokens)
// ============================================

leaf : NOTE
     | CHORD
     | REST


// ============================================
// NOTE / CHORD / REST tokens
// ============================================

NOTE.3  : /{NOTE_REGEX}/
CHORD.3 : /{CHORD_REGEX}/
REST.2  : /{REST_REGEX}/


// ============================================
// Parser-level pitch structure
// (used by transformers/visitors, not the main parse path)
// ============================================

pitch : /{ABS_PITCH}|{REL_PITCH}/


// ============================================
// Containers
// ============================================

sequence   : "[" expr* "]"
concurrent : "<<" expr* ">>"


// ============================================
// Operator, data, algorithm forms
// ============================================

operator_list     : "(" IDENTIFIER expr* ")"
data_list         : "'" "(" expr* ")"
algorithm_literal : "'" "[" expr* "]"


// ============================================
// Instructions
// ============================================

instruction : const_instr
            | param_instr


// ============================================
// Constant instructions
// ============================================

const_instr : CONST_KEYWORD
            | "!" IDENTIFIER

CONST_KEYWORD.3 : "!silence"
                | "!pppp" | "!ppp" | "!pp" | "!p" | "!mp"
                | "!mf"   | "!f"   | "!ff" | "!fff" | "!ffff"
                | "!cresc" | "!decresc" | "!dim"
                | "!sfz"  | "!fp"
                | "!left" | "!center" | "!right"
                | "!near" | "!far"
                | "!stageLeft" | "!stageCenter" | "!stageRight"
                | "!largo"      | "!lento"    | "!adagio"
                | "!andante"    | "!moderato" | "!allegro"
                | "!vivace"     | "!presto"   | "!prestissimo"
                | "!rit" | "!acc" | "!rubato"
                | "!straight" | "!swing" | "!shuffle"
                | "!jazz" | "!latin" | "!rock" | "!classical" | "!swingFeel"
                | "!DC" | "!DS" | "!Segno" | "!Coda" | "!ToCoda" | "!Fine"
                | "!DC_al_Fine" | "!DS_al_Coda"
                | "!repeatStart" | "!repeatEnd"
                | "!("  | "!)"
                | "!pedOn" | "!pedOff"
                | "!unaCorda" | "!treCorde" | "!sostPed"
                | "!commonTime" | "!cutTime"


// ============================================
// Parameter instructions
// ============================================

param_instr : PARAM_NUM    -> param_assign_num
            | PARAM_IDENT  -> param_assign_ident
            | PARAM_STRING -> param_assign_string

// Priority 4 — must beat NOTE/CHORD/REST on tokens like "key=c" or "r=4"
PARAM_NUM.4    : /[a-zA-Z][a-zA-Z0-9_]*=[0-9]+(?:\\.[0-9]+)?/
PARAM_IDENT.4  : /[a-zA-Z][a-zA-Z0-9_]*=[a-zA-Z][a-zA-Z0-9_]*/
PARAM_STRING.4 : /[a-zA-Z][a-zA-Z0-9_]*="[^"]*"/


// ============================================
// Identifiers and symbols
// ============================================

symbol : IDENTIFIER

// Priority 0 — lowest, catches anything not claimed above
IDENTIFIER.0 : /[a-zA-Z][a-zA-Z0-9_-]*/


// ============================================
// Whitespace and comments
// ============================================

%ignore /\\s+/
%ignore /#[^\\n]*/
%import common.ESCAPED_STRING
"""

# ============================================
# Parser instance
# ============================================

parser = Lark(MUSICAL_GRAMMAR, parser="lalr")

# ============================================
# Self-test
# ============================================

if __name__ == "__main__":

    tests = [
        # single notes
        ("c4",                       True),
        ("c#'8.",                    True),
        ("ebb,,2..",                 True),
        (r"c'4\trill",               True),
        ("c'4-.",                    True),
        (r"c'4->\mordent",           True),
        ("c4~",                      True),  # note with tie
        ("c'4~",                     True),  # note with duration and tie
        (r"c'4\trill~",              True),  # note with ornament and tie

        # absolute single notes
        ("C44", True),
        ("C3#8.", True),
        ("E5bb2..", True),
        (r"C44\trill", True),

        # chords
        ("<c' e' g'>4",              True),
        ("<c# eb'>8",                True),
        (r"<c' e' g'>4\trill",       True),
        ("<c' e' g'>4-.",            True),
        ("<c' e' g'>4~",             True),  # chord with tie

        # rests
        ("r",                        True),
        ("r4",                       True),
        ("r8.",                      True),
        ("r4~",                      True),  # rest with tie

        # param assignments
        ("tempo=120",                True),
        ("swing=0.6",                True),
        ("instrument=piano",         True),
        ('role="chorus"',            True),

        # sequences and concurrents
        ("[c4 d4 e4 f4]",            True),
        ("<< [c4 e4 g4] [e4 g4 c4] >>", True),

        # instructions
        ("!allegro",                 True),
        ("!jazz",                    True),
        ("!stageLeft",               True),
        ("!repeatStart [c4 d4 e4] !repeatEnd", True),

        # mixed
        ("!allegro tempo=120 [c4 d4 e4 r4]", True),

        # edge cases
        ("c",                        True),   # bare pitch
        ("g##,,4.->\\prallmordent",  True),   # maximal note
        ("key=c",                    True),   # PARAM_IDENT where RHS is [a-g]
        ("r=4",                      True),   # PARAM_NUM, not REST
        ("!unknown",                 True),   # "!" IDENTIFIER branch
        ("<c>4",                     False),  # single-note chord — invalid per CHORD_REGEX (needs 2+ pitches)
    ]

    passed = failed = 0
    print("=" * 50)
    for src, should_pass in tests:
        try:
            tree = parser.parse(src)
            if should_pass:
                print(f"OK  {src!r}")
                passed += 1
            else:
                print(f"UNEXPECTED OK  {src!r}")
                failed += 1
        except Exception as e:
            if not should_pass:
                print(f"OK (expected fail)  {src!r}")
                passed += 1
            else:
                print(f"ERR {src!r}")
                print(f"    {e}")
                failed += 1

    print("=" * 50)
    print(f"{passed} passed, {failed} failed")