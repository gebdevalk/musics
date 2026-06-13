from typing import List, Callable, TypeVar, overload, Literal
import re

T = TypeVar('T', float, list)

# ---------- helpers ----------

def _parse_scaler(op_str: str) -> Callable[[float], float]:
    """Return a function that applies the scalar operation (e.g. '*2/3')."""
    tokens = re.findall(r'([*/+\-])(\d*\.?\d+)', op_str)
    if not tokens:
        raise ValueError(f"Not a valid scalar operation: {op_str}")

    ops = []
    for sign, val in tokens:
        num = float(val)
        if sign == '*':
            ops.append(lambda x, n=num: x * n)
        elif sign == '/':
            ops.append(lambda x, n=num: x / n)
        elif sign == '+':
            ops.append(lambda x, n=num: x + n)
        elif sign == '-':
            ops.append(lambda x, n=num: x - n)

    def composed(x: float) -> float:
        for op in ops:
            x = op(x)
        return x

    return composed


def _parse_aggregator(op_str: str) -> Callable[[list[float]], float]:
    """Return an aggregator function (sum, mean, max, min)."""
    op_str = op_str.strip().lower()
    if op_str == "sum":
        return sum
    if op_str == "mean":
        return lambda lst: sum(lst) / len(lst)
    if op_str == "max":
        return max
    if op_str == "min":
        return min
    raise ValueError(f"Unknown aggregator: {op_str}")


# ---------- public API ----------

def aggregate(op: str, data: list[float]) -> float:
    """Apply an aggregator (sum, mean, max, min) to a list of numbers."""
    if not isinstance(data, list):
        raise TypeError(f"Aggregator '{op}' requires a list, got {type(data)}")
    func = _parse_aggregator(op)
    return func(data)


def scale(op: str, data: T, mode: str = "map") -> T:
    """
    Apply a scalar operation to a number or list of numbers.

    op   : expression like '*2', '/3', '*2/3', '+1.5'
    data : a float, int, or list thereof
    mode : 'map' (default) — apply to each element; 'fold' — reduce left-to-right
    """
    scaler = _parse_scaler(op)

    if isinstance(data, (int, float)):
        return scaler(float(data))

    if isinstance(data, list):
        if mode == "map":
            return [scaler(x) for x in data]
        elif mode == "fold":
            if not data:
                raise ValueError("Cannot fold an empty list")
            result = float(data[0])
            for x in data[1:]:
                result = scaler(result)
            return result
        else:
            raise ValueError("mode must be 'map' or 'fold'")

    raise TypeError(f"Unsupported data type: {type(data)}")


# ---------- demo ----------

if __name__ == "__main__":
    # Aggregate
    res = aggregate("sum", [1, 2, 3, 4])   # 10.0
    print(res)
    res = aggregate("mean", [1, 2, 3])      # 2.0
    print(res)

    # Scale on single number
    res = scale("*2/3", 15)                 # 10.0
    print(res)

    # Scale on list – map (default)
    res = scale("*2", [1, 2, 3])            # [2.0, 4.0, 6.0]
    print(res)

    # Scale on list – fold
    res = scale("*2", [1, 2, 3], mode="fold")  # 8.0
    print(res)
