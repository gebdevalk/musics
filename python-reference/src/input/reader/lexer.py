# input/reader/lexer.py
from typing import Iterator

from lark import Lark
from lark.lexer import Token

from input.reader.regex import *

def _to_lark(pattern: str) -> str:
    result = re.sub(r'(?<!\\)\((?!\?)', '(?:', pattern)
    result = result.replace('/', '\\/')  # escape / so Lark doesn't close the regex early
    return "/" + result + "/"

# ====================== GRAMMAR ======================

LARK_GRAMMAR = f"""
    %import common.WS
    %ignore WS

    // Priority ladder (higher number wins on same-length match):
    //   ASSIGN_FLOAT.4 > ASSIGN_INT.3 > ASSIGN_CONST.2 = ASSIGN_STRING.2
    //   > BANG_CONST.1 and FLOAT.2 > INT.1

    INT.1:           {_to_lark(INT)}
    FLOAT.2:         {_to_lark(FLOAT)}
    QUOTED_ID:       {_to_lark(STRING)}
    TYPE:            {_to_lark(NAME)}
    STRING:          {_to_lark(STRING)}
    ASSIGN_FLOAT.4:  {_to_lark(ASSIGN_FLOAT)}
    ASSIGN_INT.3:    {_to_lark(ASSIGN_INT)}
    ASSIGN_CONST.2:  {_to_lark(ASSIGN_CONST)}
    ASSIGN_STRING.2: {_to_lark(ASSIGN_STRING)}
    BANG_CONST.1:    {_to_lark(BANG_CONST)}
    OPERATION:       {_to_lark(OPERATION)}
    NOTE:            {_to_lark(NOTE)}
    CHORD:           {_to_lark(CHORD)}
    REST:            {_to_lark(REST)}
    DRUM:            {_to_lark(DRUM)}

    // --------------------------------------------
    // High-level structure
    // --------------------------------------------

    start: expr*

    expr: instruction
        | sequence
        | parallel
        | operator_list
        | algorithm_def
        | algorithm_call
        | leaf
        | INT | FLOAT

    leaf: NOTE | CHORD | REST | DRUM

    arg: expr | TYPE

    // --------------------------------------------
    // Containers
    // --------------------------------------------

    SEQ:       "["
    SEQ_CLOSE: "]"

    PAR:       "<<"
    PAR_CLOSE: ">>"

    LIST:       "("
    LIST_CLOSE: ")"

    ALGO:       "@("
    ALGO_CLOSE: ")"

    DATA:       "'["
    DATA_CLOSE: "']"

    sequence:       SEQ QUOTED_ID? expr* SEQ_CLOSE
    parallel:       PAR QUOTED_ID? expr* PAR_CLOSE
    operator_list:  LIST OPERATION expr* LIST_CLOSE
    algorithm_call: ALGO TYPE arg* ALGO_CLOSE
    algorithm_def:  DATA TYPE expr* DATA_CLOSE

    // --------------------------------------------
    // Instructions
    // --------------------------------------------

    instruction: const_instr | assignment

    // --------------------------------------------
    // Constant instructions
    // --------------------------------------------

    const_instr: CONST_KEYWORD | BANG_CONST

    CONST_KEYWORD: "!silence"
                 | "!pppp" | "!ppp" | "!pp" | "!p" | "!mp"
                 | "!mf"   | "!f"   | "!ff" | "!fff" | "!ffff"
                 | "!cresc" | "!decresc" | "!dim"
                 | "!sfz"  | "!fp"
                 | "!left" | "!center" | "!right"
                 | "!near" | "!far"
                 | "!stageLeft" | "!stageCenter" | "!stageRight"
                 | "!largo" | "!lento" | "!adagio"
                 | "!andante" | "!moderato" | "!allegro"
                 | "!vivace" | "!presto" | "!prestissimo"
                 | "!rit" | "!acc" | "!rubato"
                 | "!straight" | "!swing" | "!shuffle"
                 | "!jazz" | "!latin" | "!rock" | "!classical" | "!swingFeel"
                 | "!DC" | "!DS" | "!Segno" | "!Coda" | "!ToCoda" | "!Fine"
                 | "!DC_al_Fine" | "!DS_al_Coda"
                 | "!repeatStart" | "!repeatEnd"
                 | "!(" | "!)"
                 | "!pedOn" | "!pedOff"
                 | "!unaCorda" | "!treCorde" | "!sostPed"
                 | "!commonTime" | "!cutTime"

    // --------------------------------------------
    // Parameter instructions
    // --------------------------------------------

    assignment: ASSIGN_INT | ASSIGN_FLOAT | ASSIGN_CONST | ASSIGN_STRING

    """

def tokenize(text: str) -> Iterator[Token]:
    lexer = Lark(LARK_GRAMMAR, parser="lalr")
    return lexer.lex(text)

# Test
if __name__ == "__main__":
    # text = """a"""
    # text = """a#'4..-^\\prall\\mf\\art=80\\pan=0.0\\vol=mf\\timbre="piano" !cresc c4 <c e g>2-! r4 !f [ d' e' ] << f' g' >>"""
    text = """a#'4..-^\\prall~ !mf !art=80 !pan=0.0 !vol=mf !timbre="piano" !cresc c4 <c e g>2-! r4 !f [ d' e' ] << f' g' >>
    << [ "1" a b c] [ "2" [ "3" d e f ] [g a' b]] [[c d e][ f g a][ b c d]] >>
    << "composite" [a b c] [e f g] >>
    '[ int 1 2 3 4'] 
    ( trans(9) A4 b c )      
    @( reverse [ a b c d e f g ])
    """
    # ( trans(9) A4 b c )
    #
    # text = """<< ["motif" a b c] [[ d e f ] [g a' b]] [[c d e][ f g a][ b c d]] >>"""

    tokens = tokenize(text)
    print("=== Token Sequence ===\n")
    for t in tokens:
        print(f"type={t.type}, value={t.value}")