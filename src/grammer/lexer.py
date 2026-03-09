# lexer.py
import re
from collections import namedtuple

# -----------------------------
# Token definition
# -----------------------------
Token = namedtuple("Token", ["type", "value"])

# -----------------------------
# Lexer regex rules
# -----------------------------
token_specs = [
    # --- Ignored separators ---
    ("WHITESPACE", r"[ \t\r\n|,]+"),

    # --- Multi-character punctuation (longest first) ---
    ("CONC_START", r"<<"),
    ("CONC_END", r">>"),

    # --- Single-character punctuation ---
    ("LCHORD", r"<"),
    ("RCHORD", r">"),
    ("LPAREN", r"\("),
    ("RPAREN", r"\)"),
    ("LBRACKET", r"\["),
    ("RBRACKET", r"\]"),
    ("QUOTE", r"'"),
    ("BACKSLASH", r"\\"),
    ("DASH", r"-"),

    # --- Musical tokens ---
    ("ACCIDENTAL", r"##|bb|#|b"),
    ("PITCHLETTER", r"[a-g]"),
    ("OCTAVEMARK", r"['\,]"),
    ("MICROTONE", r"(\+|\-)\d+(\.\d+)?"),
    ("DURATION", r"\d+(\.)*"),
    ("REST", r"r"),

    # --- Decorations ---
    ("ARTICULATION", r"-[.>^_!+]"),
    ("ORNAMENT", r"\\[a-zA-Z][a-zA-Z0-9_-]*"),

    # --- Symbols / Identifiers ---
    ("IDENTIFIER", r"[a-zA-Z][a-zA-Z0-9_-]*"),

]

# -----------------------------
# Combined regex
# -----------------------------
tok_regex = "|".join(f"(?P<{name}>{pattern})" for name, pattern in token_specs)
get_token = re.compile(tok_regex).match

# -----------------------------
# Lexer function
# -----------------------------
def lexer(code):
    pos = 0
    end = len(code)
    tokens = []

    while pos < end:
        m = get_token(code, pos)
        if not m:
            raise SyntaxError(f"Unexpected character: {code[pos]!r} at {pos}")
        typ = m.lastgroup
        val = m.group(typ)
        if typ != "WHITESPACE":  # skip separators
            tokens.append(Token(typ, val))
        pos = m.end()
    return tokens

# -----------------------------
# Example usage
# -----------------------------
if __name__ == "__main__":
    code = """
    <c e g>4
    r2
    '(a b c)
    <<c4 d4 e4>>
    """
    toks = lexer(code)
    for t in toks:
        print(t)