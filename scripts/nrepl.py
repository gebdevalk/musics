#!/usr/bin/env python3
"""Evaluate Clojure in the project's running nREPL and print the result.

    scripts/nrepl.py '(+ 1 2)'          code as an argument
    scripts/nrepl.py < file.clj         or on stdin
    scripts/nrepl.py -n core.assist '(plan :live)'   in a namespace (default user)

The port is read from .nrepl-port (written by `lein repl` and Calva's
jack-in), else NREPL_PORT, else 7888. Output, values and errors are printed
as they arrive; exits 1 when the evaluation threw. Start the REPL with
scripts/repl-start.sh. Only the standard library: nREPL speaks bencode over
a plain socket.
"""
import os
import socket
import sys
import uuid

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def port():
    try:
        with open(os.path.join(HERE, ".nrepl-port")) as f:
            return int(f.read().strip())
    except (OSError, ValueError):
        return int(os.environ.get("NREPL_PORT", 7888))


def encode(x):
    if isinstance(x, int):
        return b"i%de" % x
    if isinstance(x, str):
        x = x.encode()
    if isinstance(x, bytes):
        return b"%d:%s" % (len(x), x)
    if isinstance(x, list):
        return b"l" + b"".join(map(encode, x)) + b"e"
    if isinstance(x, dict):
        return b"d" + b"".join(encode(k) + encode(v) for k, v in sorted(x.items())) + b"e"
    raise TypeError(x)


class Reader:
    def __init__(self, sock):
        self.sock, self.buf = sock, b""

    def need(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("nREPL closed the connection")
            self.buf += chunk

    def take(self, n):
        self.need(n)
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def value(self):
        c = self.take(1)
        if c == b"i":
            return int(self.until(b"e"))
        if c == b"l":
            out = []
            while self.peek() != b"e":
                out.append(self.value())
            self.take(1)
            return out
        if c == b"d":
            out = {}
            while self.peek() != b"e":
                k = self.value()
                out[k] = self.value()
            self.take(1)
            return out
        n = int(c + self.until(b":"))
        return self.take(n).decode(errors="replace")

    def peek(self):
        self.need(1)
        return self.buf[:1]

    def until(self, stop):
        out = b""
        while (c := self.take(1)) != stop:
            out += c
        return out


def main(argv):
    ns = "user"
    if len(argv) >= 2 and argv[0] == "-n":
        ns, argv = argv[1], argv[2:]
    code = " ".join(argv) if argv else sys.stdin.read()
    sock = socket.create_connection(("127.0.0.1", port()))
    r = Reader(sock)
    msg_id = str(uuid.uuid4())
    sock.sendall(encode({"op": "eval", "code": code, "ns": ns, "id": msg_id}))
    failed = False
    while True:
        m = r.value()
        if m.get("id") != msg_id:
            continue
        if "out" in m:
            sys.stdout.write(m["out"])
        if "err" in m:
            sys.stderr.write(m["err"])
        if "value" in m:
            print(m["value"])
        if "ex" in m:
            failed = True
        if "done" in m.get("status", []):
            break
    sys.stdout.flush()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
