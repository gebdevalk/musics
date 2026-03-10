# parser.py
from core.struct.domain.domain import Pitch, Chain, Concurrent, Note, Chord, Primitives, ContextChange, \
    ProgramChange, ControlChange, Rest, Duration


class Parser:
    def __init__(self, tokens):
        self.tokens = tokens
        self.pos = 0
        self.stack = [] # stack of Composite objects

    # ---------------------------------------------------------
    # Token helpers
    # ---------------------------------------------------------

    def peek(self):
        return self.tokens[self.pos]

    def advance(self):
        tok = self.tokens[self.pos]
        self.pos += 1
        return tok

    def expect(self, kind):
        tok = self.advance()
        if tok.kind != kind:
            self.error(f"Expected {kind}", tok)
        return tok

    def error(self, msg, tok=None):
        if tok is None:
            tok = self.peek()
        raise SyntaxError(f"{msg} at line {tok.line}, col {tok.col}")

    # ---------------------------------------------------------
    # Entry point
    # ---------------------------------------------------------

    def parse(self):
        root = Chain("root", parent=None)
        self.stack.append(root)

        while self.peek().kind != "EOF":
            node = self.parse_expression()
            if node:
                # context blocks may return a list of ContextChange leaves
                if isinstance(node, list):
                    for n in node:
                        self.stack[-1].add_child(n)
                else:
                    self.stack[-1].add_child(node)

        self.stack.pop()
        return root

    # ---------------------------------------------------------
    # Expression dispatcher
    # ---------------------------------------------------------

    def parse_expression(self):
        tok = self.peek()

        if tok.kind == "LBRACK":
            return self.parse_chain()

        if tok.kind == "LDBL":
            return self.parse_concurrent()

        if tok.kind == "LANGLE":
            return self.parse_chord()

        if tok.kind == "QUOTE":
            return self.parse_literal()

        if tok.kind == "LPAREN":
            return self.parse_paren()

        if tok.kind == "LBRACE":
            return self.parse_context_block()

        if tok.kind == "ATOM":
            return self.parse_atom()

        return None

    # ---------------------------------------------------------
    # [ ... ] → Chain
    # ---------------------------------------------------------

    def parse_chain(self):
        self.expect("LBRACK")
        parent = self.stack[-1]
        chain = Chain("chain", parent=parent)
        self.stack.append(chain)

        while self.peek().kind != "RBRACK":
            if self.peek().kind == "EOF":
                self.error("Missing ']'")
            node = self.parse_expression()
            if node:
                if isinstance(node, list):
                    for n in node:
                        chain.add_child(n)
                else:
                    chain.add_child(node)

        self.expect("RBRACK")
        self.stack.pop()
        return chain

    # ---------------------------------------------------------
    # << ... >> → Concurrent
    # ---------------------------------------------------------

    def parse_concurrent(self):
        self.expect("LDBL")
        parent = self.stack[-1]
        conc = Concurrent("concurrent", parent=parent)
        self.stack.append(conc)

        while self.peek().kind != "RDBL":
            if self.peek().kind == "EOF":
                self.error("Missing '>>'")
            node = self.parse_expression()
            if isinstance(node, Composite):
                conc.add_composite(node)
            elif isinstance(node, list):
                for n in node:
                    conc.add_child(n)
            elif node:
                conc.add_child(node)

        self.expect("RDBL")
        self.stack.pop()
        return conc

    # ---------------------------------------------------------
    # < ... > → Chord
    # ---------------------------------------------------------

    def parse_chord(self):
        self.expect("LANGLE")
        notes = []
        ctx = self.stack[-1].context
        parent = self.stack[-1]

        while self.peek().kind != "RANGLE":
            if self.peek().kind == "EOF":
                self.error("Missing '>'")
            leaf = self.parse_atom()
            if isinstance(leaf, Note):
                notes.append(leaf)
            else:
                self.error("Chord can only contain notes")

        self.expect("RANGLE")
        return Chord(notes, context=ctx, parent=parent)

    # ---------------------------------------------------------
    # '[ ... ] → Primitives literal
    # ---------------------------------------------------------

    def parse_literal(self):
        self.expect("QUOTE")
        self.expect("LBRACK")

        items = []
        while self.peek().kind != "RBRACK":
            tok = self.advance()
            if tok.kind != "ATOM":
                self.error("Literal can only contain atoms", tok)
            items.append(tok.value)

        self.expect("RBRACK")
        ctx = self.stack[-1].context
        parent = self.stack[-1]
        return Primitives("literal", items, context=ctx, parent=parent)

    # ---------------------------------------------------------
    # ( ... ) → operations
    # ---------------------------------------------------------

    def parse_paren(self):
        lp = self.expect("LPAREN")

        if self.peek().kind != "ATOM":
            self.error("Expected operator", lp)

        op = self.advance().value
        ctx = self.stack[-1].context
        parent = self.stack[-1]

        # (= key value) → ContextChange
        if op == "=":
            key_tok = self.expect("ATOM")
            val = self.parse_value()
            ctx.set(key_tok.value, val)
            self.expect("RPAREN")
            return ContextChange(key_tok.value, val, context=ctx, parent=parent)

        # (program N)
        if op == "program":
            num_tok = self.expect("ATOM")
            num = int(num_tok.value)
            self.expect("RPAREN")
            return ProgramChange(num, context=ctx, parent=parent)

        # (cc X Y)
        if op == "cc":
            c_tok = self.expect("ATOM")
            v_tok = self.expect("ATOM")
            c = int(c_tok.value)
            v = int(v_tok.value)
            self.expect("RPAREN")
            return ControlChange(c, v, context=ctx, parent=parent)

        self.error(f"Unknown operator '{op}'", lp)

    # ---------------------------------------------------------
    # { key value ... } → multiple ContextChange
    # ---------------------------------------------------------

    def parse_context_block(self):
        self.expect("LBRACE")
        ctx = self.stack[-1].context
        parent = self.stack[-1]

        changes = []

        while self.peek().kind != "RBRACE":
            key_tok = self.expect("ATOM")
            val = self.parse_value()
            ctx.set(key_tok.value, val)
            changes.append(ContextChange(key_tok.value, val, context=ctx, parent=parent))

        self.expect("RBRACE")
        return changes

    # ---------------------------------------------------------
    # ATOM → Note | Rest | Primitive
    # ---------------------------------------------------------

    def parse_atom(self):
        tok = self.expect("ATOM")
        ctx = self.stack[-1].context
        parent = self.stack[-1]
        text = tok.value

        # rest: r4
        if text.startswith("r") and text[1:].isdigit():
            dur = self.parse_duration(text[1:])
            return Rest(dur, context=ctx, parent=parent)

        # note: c4, d#3, etc.
        if text[0].lower() in "abcdefg":
            return self.parse_note(text, ctx, parent)

        # fallback: primitive atom
        return Primitives("atom", text, context=ctx, parent=parent)

    # ---------------------------------------------------------
    # Note parsing (placeholder)
    # ---------------------------------------------------------

    def parse_note(self, text, ctx, parent):
        # simple placeholder: c4 → Pitch.C4, Duration.QUARTER
        pitch_name = text[0].upper() + "4"
        pitch = Pitch[pitch_name]
        dur = Duration.QUARTER
        return Note(pitch, dur, context=ctx, parent=parent)

    def parse_duration(self, num):
        return Duration.QUARTER # placeholder

    # ---------------------------------------------------------
    # Value parsing for context setters
    # ---------------------------------------------------------

    def parse_value(self):
        tok = self.peek()
        if tok.kind == "ATOM":
            return self.advance().value
        if tok.kind == "STRING":
            return self.advance().value
        self.error("Invalid value", tok)
