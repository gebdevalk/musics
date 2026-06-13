"""Token dispatcher: routes lexer tokens to parser handlers."""

from typing import Callable, Dict, Iterator

from lark.lexer import Token

from src.input.reader.parser.music_parser import (
    push_container, pop_and_collect,
    chord_to_leaf, note_to_leaf, rest_to_rest, drum_to_leaf,
    assign_bang_const, assign_int, assign_float, assign_const, assign_str,
    append_int, append_float, noop_operation
)

MatcherFn = Callable[[Token], None]

_HANDLERS: Dict[str, MatcherFn] = {
    # primitive appends
    "INT":           append_int,
    "FLOAT":         append_float,
    # context key value assignments
    "BANG_CONST":    assign_bang_const,
    "ASSIGN_INT":    assign_int,
    "ASSIGN_FLOAT":  assign_float,
    "ASSIGN_CONST":  assign_const,
    "ASSIGN_STRING": assign_str,
    # operation
    "OPERATION":     noop_operation,
    # leafs
    "NOTE":        note_to_leaf,
    "CHORD":       chord_to_leaf,
    "REST":        rest_to_rest,
    "DRUM":        drum_to_leaf,
    # composites
    "SEQ":        push_container,
    "PAR":        push_container,
    "LIST":       push_container,
    "DATA":       push_container,
    "ALGO":       push_container,
    "SEQ_CLOSE":  pop_and_collect,
    "PAR_CLOSE":  pop_and_collect,
    "LIST_CLOSE": pop_and_collect,
    "DATA_CLOSE": pop_and_collect,
    "ALGO_CLOSE": pop_and_collect,
}

def dispatch(lexer_tokens: Iterator[Token]) -> None:
    """Iterate through lexer tokens and dispatch each to its handler."""
    for token in lexer_tokens:
        handler = _HANDLERS.get(token.type)
        if handler is None:
            raise TypeError(
                f"Unknown token type: {token.type!r}  value={token.value!r}"
            )
        handler(token)


if __name__ == "__main__":
    from src.input.reader import lexer
    # "a#'4..-^\\prall~ !mf !art=80 !pan=0.0 !vol=mf "
    #     '!timbre="piano" !cresc c4 <c e g>2-! r4 !f '
    #     "<< f' g' >>"
    text = (
        "[ d' e' ]"
    )
    tokens = lexer.tokenize(text)
    dispatch(tokens)
