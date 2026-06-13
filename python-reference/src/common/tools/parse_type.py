"""Convert a string value to a named type."""

import ast
from fractions import Fraction
from typing import Any

# Module-level type map — exact-key lookup, no prefix matching.
# bool is handled separately because bool("False") is True.
_TYPE_MAP: dict[str, type] = {
    'int': int,
    'float': float,
    'str': str,
    'Fraction': Fraction,
    'complex': complex,
    'list': list,
    'tuple': tuple,
    'dict': dict,
    'set': set,
}

# Types that need ast.literal_eval rather than direct constructor call.
_EVAL_TYPES = frozenset({'list', 'tuple', 'dict', 'set'})


def to_type(value_str: str, type_name: str) -> Any:
    """
    Convert a string to the given type.

    Args:
        value_str: String representation of the value.
        type_name: One of 'int', 'float', 'str', 'Fraction', 'bool',
                   'complex', 'list', 'tuple', 'dict', 'set'.

    Returns:
        Converted value of the requested type.

    Raises:
        KeyError: If *type_name* is not supported.
        ValueError: If the string cannot be parsed as the requested type.
    """
    if type_name not in _TYPE_MAP and type_name != 'bool':
        raise KeyError(
            f"Unknown type name: {type_name!r}. "
            f"Available: {sorted([*_TYPE_MAP, 'bool'])}"
        )

    if type_name == 'bool':
        lowered = value_str.strip().lower()
        if lowered in ('true', '1', 'yes', 'on'):
            return True
        if lowered in ('false', '0', 'no', 'off', ''):
            return False
        raise ValueError(f"Cannot parse {value_str!r} as bool")

    if type_name in _EVAL_TYPES:
        parsed = ast.literal_eval(value_str)
        expected = _TYPE_MAP[type_name]
        if not isinstance(parsed, expected):
            raise TypeError(
                f"Expected {type_name}, got {type(parsed).__name__}: {parsed!r}"
            )
        return parsed

    return _TYPE_MAP[type_name](value_str)
