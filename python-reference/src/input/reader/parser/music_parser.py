# src/core/xparser/music_parser.py

from __future__ import annotations

from dataclasses import dataclass, field
from fractions import Fraction
from re import Match
from typing import Any, Callable, Dict
from lark.lexer import Token
from common.data.context_keys import ROOT_DICT
from common.tools.fraction_helper import fraction_from_string
from common.tools.stack import Stack
from core.domain.context import Context
from core.domain.parts import Leaf, Rest, Composite, Transient, Drum
from common.data.articulation import ARTICULATION_MAP, ARTICULATION_DYNAMIC_MAP
from input.reader.parser.parser_utils import (
    NOTE_BASE, INTERVALS, NOTE_TO_INDEX, ACCIDENTAL_DELTA,
    INITIAL_PITCH, INITIAL_NAME, DEFAULT_DURATION, DEFAULT_DYNAMIC,
    TEMPO_MAP
)
from input.reader.regex import MODIFIER_RE, parse_pitch, parse_modifiers, NOTE_RE, CHORD_RE, REST_RE, DRUM_RE, INT_RE, \
    FLOAT_RE, STRING_RE, ASSIGN_STRING_RE, ASSIGN_FLOAT_RE, ASSIGN_INT_RE, BANG_CONST_RE, ASSIGN_CONST_RE


# ============================================================
# Parser state
# ============================================================

@dataclass(slots=True)
class LeafParserState:
    # stack = stack
    prev_tempo: int = None
    prev_pitch: int = INITIAL_PITCH
    prev_name: str = INITIAL_NAME
    prev_duration: Fraction = field(default_factory=lambda: DEFAULT_DURATION)
    prev_dynamic: float = DEFAULT_DYNAMIC
    scale: Any = None

def init() -> tuple[Stack, LeafParserState]:
    stack = Stack()
    stack.push(Composite("SCORE", "score", Context.root(ROOT_DICT), []))
    state = LeafParserState()
    state.scale = stack.peek().context.value("keyScale", 0.0).scale
    return stack, state

# ============================================================
# Parser stack
# ============================================================

stack, state =  init()

# ============================================================
# PURE FUNCTIONAL ID BUILDER
# ============================================================

def create_id_builder():
    list_types = { "SEQ", "PAR", "SECT", "FORM" , "SCORE", "ALG ", "OP", "DATA", "LIST" }
    counters = {t: 0 for t in list_types}

    def _create_id(type: str, id: str | None) -> str:
        if id: return id
        if type in list_types:
            counters[type] += 1
            return f"{type}.{counters[type]}"
        raise ValueError(f"Unknown type: {type}")
    return _create_id

create_id = create_id_builder()

# ============================================================
# Composite management
# ============================================================

def push_container(token: Token):
    """
    Create a new container inheriting the current context.
    Push it on the stack.
    """
    type = token.type
    id = token.value
    parent: Composite = stack.peek()
    ctx: Context = parent.context
    if type == "LIST":
        # Transient does not have a context of its own
        # so it takes the context of the parent, on pop
        # it appends all its children one by one to the parent composite
        item = Transient(type, id, ctx, [])
    else:
        item = Composite(type, create_id(type, id), Context(ctx), [])
    stack.push(item)

def pop_and_collect(token: Token):
    """
    Add the container or its children to the parent container
    """
    current: Composite | Transient = stack.pop()
    if current.type != token.type.split("_")[0]:
        raise TypeError(
            f"Closing bracket {token.value} "
            f"does not match opening bracket {current.type}"
        )
    peek: Composite = stack.peek()
    if isinstance(current, Transient):
        for e in current.children:
            peek.append(e)
    else:
        peek.append(current)

# ============================================================
# Regular expressionmatcher
# ============================================================

def match_token(regex, token: Token):
    match = regex.match(token.value)
    if not match:
        raise TypeError(
            f"{token.type!r} token did not match its regex: {token.value!r}"
        )
    return match

# ============================================================
# Pitch conversion
# ============================================================

def _interval(note1: str, note2: str) -> int:
    return INTERVALS[NOTE_TO_INDEX[note1]][NOTE_TO_INDEX[note2]]

def _adjust_octave(octave: str | None) -> int:
    if not octave:
        return 0
    return len(octave) if octave[0] == "'" else -len(octave)


def _adjust_accidental(accidental: str | None) -> int:
    if not accidental:
        return 0
    return ACCIDENTAL_DELTA.get(accidental, 0)

def _to_relative_pitch(parsed_pitch: tuple) -> int:
    name, accidental, octave = parsed_pitch
    interval = _interval(state.prev_name, name.lower())
    pitch = state.prev_pitch + interval
    pitch += 12 * _adjust_octave(octave)
    state.prev_name = name.lower()
    state.prev_pitch = pitch
    return pitch + _adjust_accidental(accidental)

def _to_absolute_pitch(parsed_pitch: tuple) -> int:
    name, accidental, octave, microtone = parsed_pitch
    state.prev_name = name.lower()
    base = NOTE_BASE[state.prev_name] + (int(octave) + 1) * 12
    state.prev_pitch = base
    return base + _adjust_accidental(accidental)

def _resolve_pitch(parsed_pitch: tuple) -> int:
    name, accidental, octave = parsed_pitch
    if name.isupper():
        return _to_absolute_pitch(parsed_pitch)
    return _to_relative_pitch(parsed_pitch)


# ============================================================
# Duration
# ============================================================

def _parse_duration(duration: str | None) -> Fraction:
    if not duration:
        return state.prev_duration
    dur = fraction_from_string(duration)
    state.prev_duration = dur
    return dur

# ============================================================
# Leaf conversion
# ============================================================

def note_to_leaf(token: Token, ART_DYN_MAP=None):
    match: Match[str] = match_token(NOTE_RE, token)
    pitch, duration, articulation, modifiers, tie = match.groups()
    resolved_duration = _parse_duration(duration)
    resolved_pitch = _resolve_pitch(parse_pitch(pitch))
    resolved_articulation = ARTICULATION_MAP.get(articulation) if articulation else None
    resolved_dynamic = ARTICULATION_DYNAMIC_MAP.get(articulation) if articulation else None
    result = Leaf(token.value, stack.peek().context, resolved_duration,
                (resolved_pitch,), resolved_articulation, resolved_dynamic, parse_modifiers(modifiers), bool(tie))
    stack.peek().append(result)

def chord_to_leaf(token: Token):
    match: Match[str] = match_token(CHORD_RE, token)
    pitches, duration, articulation, modifiers, tie = match.groups()
    resolved_duration = _parse_duration(duration)
    resolved_pitches = tuple(_resolve_pitch(parse_pitch(p)) for p in pitches.split() if p.strip())
    resolved_articulation = ARTICULATION_MAP.get(articulation) if articulation else None
    resolved_dynamic =  ARTICULATION_DYNAMIC_MAP.get(articulation) if articulation else None
    result = Leaf(token.value, stack.peek().context, resolved_duration,
                resolved_pitches, resolved_articulation, resolved_dynamic, parse_modifiers(modifiers), bool(tie))
    stack.peek().append(result)

def rest_to_rest(token: Token):
    match: Match[str] = match_token(REST_RE, token)
    duration = match.group(1)
    resolved_duration = _parse_duration(duration)
    result = Rest(token.value, stack.peek().context, resolved_duration)
    stack.peek().append(result)

def drum_to_leaf(token: Token):
    match: Match[str] = match_token(DRUM_RE, token)
    duration = match.group(0)
    program: Any = match.group(1),
    if match_token(STRING_RE, token):
        program = DRUM_NAME_MAP[program]
    resolved_duration = _parse_duration(duration)
    result = Drum(token.value, stack.peek().context,
                resolved_duration, int(program))
    stack.peek().append(result)

# ============================================================
# Primitives
# ============================================================

def append_int(token: Token):
    match = match_token(INT_RE, token)
    peek = stack.peek()
    peek.context.append(int(match.group(0)))

def append_float(token: Token):
    match = match_token(FLOAT_RE, token)
    peek = stack.peek()
    peek.context.append(float(match.group(0)))

def append_str(token: Token):
    match = match_token(STRING_RE, token)
    peek = stack.peek()
    peek.context.append(match.group(0))
    
# ============================================================
# Instruction application
# ============================================================

def assign_bang_const(token: Token):
    match = match_token(BANG_CONST_RE, token)
    const = match.group(0)
    peek = stack.peek()
    if const in ARTICULATION_DYNAMIC_MAP:
        state.prev_dynamic = ARTICULATION_DYNAMIC_MAP[const]
        peek.context.append("v", peek.duration, state.prev_dynamic)
    elif const in TEMPO_MAP:
        tempo = TEMPO_MAP[const]
        peek.context.append("T", peek.duration, tempo)

def assign_const(token: Token):
    match = match_token(ASSIGN_CONST_RE, token)
    peek = stack.peek()
    keyword, const = match.groups()
    if const in ARTICULATION_DYNAMIC_MAP:
        state.prev_dynamic = ARTICULATION_DYNAMIC_MAP[const]
        peek.context.append(keyword, peek.duration, state.prev_dynamic)
    elif const in TEMPO_MAP:
        tempo = TEMPO_MAP[const][0]
        peek.context.append(keyword, peek.duration, tempo)

def assign_int(token: Token):
    match = match_token(ASSIGN_INT_RE, token)
    peek = stack.peek()
    peek.context.append(match.group(1), peek.duration, int(match.group(2)))

def assign_float(token: Token):
    match = match_token(ASSIGN_FLOAT_RE, token)
    peek = stack.peek()
    peek.context.append(match.group(1), peek.duration, float(match.group(2)))

def assign_str(token: Token):
    match = match_token(ASSIGN_STRING_RE, token)
    peek = stack.peek()
    peek.context.append(match.group(1), peek.duration, match.group(2))

def noop_operation(token: Token):
    """Operation tokens (e.g. @sum) are not yet implemented."""
    pass

# ============================================================
# Starting point
# ============================================================
#
# MatcherFn = Callable[[Token], None]
#
# _HANDLERS: Dict[str, MatcherFn] = {
#     # primitive appends
#     "INT":           append_int,
#     "FLOAT":         append_float,
#     # context key value assignments
#     "BANG_CONST":    assign_bang_const,
#     "ASSIGN_INT":    assign_int,
#     "ASSIGN_FLOAT":  assign_float,
#     "ASSIGN_CONST":  assign_const,
#     "ASSIGN_STRING": assign_str,
#     # operation
#     "OPERATION":     noop_operation,
#     # leafs
#     "NOTE":        note_to_leaf,
#     "CHORD":       chord_to_leaf,
#     "REST":        rest_to_rest,
#     "DRUM":        drum_to_leaf,
#     # composites
#     "SEQ":        push_container,
#     "PAR":        push_container,
#     "LIST":       push_container,
#     "DATA":       push_container,
#     "ALGO":       push_container,
#     "SEQ_CLOSE":  pop_and_collect,
#     "PAR_CLOSE":  pop_and_collect,
#     "LIST_CLOSE": pop_and_collect,
#     "DATA_CLOSE": pop_and_collect,
#     "ALGO_CLOSE": pop_and_collect,
# }
#
# def dispatch(lexer_tokens: list[Token]) -> None:
#     """Iterate through lexer tokens and dispatch each to its handler."""
#     for token in lexer_tokens:
#         handler = _HANDLERS.get(token.type)
#         if handler is None:
#             raise TypeError(
#                 f"Unknown token type: {token.type!r}  value={token.value!r}"
#             )
#         handler(token)

