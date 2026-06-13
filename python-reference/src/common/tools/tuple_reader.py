# tuple_reader.py

from operator import itemgetter as item
from types import SimpleNamespace
from collections import namedtuple

# Part = namedtuple('Part', ['id', 'type', 'context', 'data'])
#
# def part(id, type, context, data):
#     return Part(id, type, context, data)

def part(id, type, context, data):
    return id, type, context, data

def part_reader():
    ns = SimpleNamespace()
    ns.id = item(0)
    ns.type = item(1)
    ns.context = item(2)
    ns.data = item(3)
    return ns

def leaf_reader():
    lr = SimpleNamespace()
    lr.pitches=item(0)
    lr.duration=item(1)
    lr.articulation=item(2)
    lr.ornament=item(3)
    lr.tie=item(4)
    return lr

# token, letter, accidental, octave, microtone
def pitch_reader():
    pr = SimpleNamespace()
    pr.token=item(0)
    pr.letter=item(1)
    pr.accidental=item(2)
    pr.octave=item(3)
    pr.microtone=item(4)
    return pr

# duration_text, tie_text
def rest_reader():
    rr = SimpleNamespace()
    rr.duration=item(0)
    rr.tie=item(1)
    return rr

# Usage
p = part(42, 'note', 'C4', 0.5)
reader = part_reader()
# print(reader.id(p)) # 42 (using itemgetter on namedtuple works)
# print(p.id) # 42 (direct attribute access)
