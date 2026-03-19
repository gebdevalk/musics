from __future__ import annotations
import random
from typing import TypeVar, Generic, Iterator, MutableSequence, overload

T = TypeVar("T")

INT_MAX = 2 ** 31 - 1


class CyclicList(MutableSequence[T], Generic[T]):
    def __init__(self, data: list[T] | None = None, repetitions: int = 0):
        self._data: list[T] = data if data is not None else []
        self.repetitions = repetitions
        self.iteration = -1

    # --- Construction helpers ---

    @classmethod
    def from_collection(cls, coll: list[T], repetitions: int = 0) -> CyclicList[T]:
        return cls(list(coll), repetitions)

    @property
    def length(self) -> int:
        return len(self._data) + len(self._data) * self.repetitions

    # --- Core MutableSequence interface ---

    def __len__(self) -> int:
        return len(self._data)

    def __getitem__(self, index: int) -> T:  # type: ignore[override]
        if len(self._data) == 0:
            raise IndexError("CyclicList is empty")
        if index == 0:
            return self._data[0]
        i = index % len(self._data)
        while i < 0:
            i += len(self._data)
        if i == 0:
            self.iteration += 1
        return self._data[i]

    def __setitem__(self, index: int, value: T) -> None:  # type: ignore[override]
        i = index % len(self._data)
        while i < 0:
            i += len(self._data)
        self._data[i] = value

    def __delitem__(self, index: int) -> None:
        i = index % len(self._data)
        while i < 0:
            i += len(self._data)
        del self._data[i]

    def insert(self, index: int, value: T) -> None:
        self._data.insert(index, value)

    # --- Cyclic iterator ---

    def __iter__(self) -> Iterator[T]:
        for i in range(self.length):
            yield self[i]

    # --- Mutation ---

    def reverse(self) -> None:
        self._data.reverse()

    def shuffle(self) -> None:
        random.shuffle(self._data)

    def shift_left(self, count: int) -> None:
        n = len(self._data)
        acc = [self._data[(i + count) % n] for i in range(n)]
        self._data[:] = acc

    def shift_right(self, count: int) -> None:
        n = len(self._data)
        acc = [self._data[(i - count) % n] for i in range(n)]
        self._data[:] = acc

    # --- Non-mutating variants ---

    def reversed(self) -> CyclicList[T]:
        return CyclicList(list(reversed(self._data)), self.repetitions)

    def shuffled(self) -> CyclicList[T]:
        shuffled = list(self._data)
        random.shuffle(shuffled)
        return CyclicList(shuffled, self.repetitions)

    def shifted_left(self, count: int) -> CyclicList[T]:
        n = len(self._data)
        acc = [self._data[(i + count) % n] for i in range(n)]
        return CyclicList(acc, self.repetitions)

    def shifted_right(self, count: int) -> CyclicList[T]:
        n = len(self._data)
        acc = [self._data[(i - count) % n] for i in range(n)]
        return CyclicList(acc, self.repetitions)

    def map(self, transform) -> CyclicList[T]:
        return CyclicList([transform(x) for x in self._data], self.repetitions)

    # --- Infinite helpers ---

    def infinite(self) -> None:
        self.repetitions = INT_MAX

    def infinited(self) -> CyclicList[T]:
        return CyclicList(list(self._data), INT_MAX)

    # --- Representation ---

    def __repr__(self) -> str:
        s = repr(self._data)
        if self.repetitions > 0:
            s += f"{self.repetitions}:"
        return s


class InfiniteList(CyclicList[T]):
    def __init__(self, data: list[T]):
        super().__init__(data, INT_MAX)


def cyclic_list_of(*elements: T) -> CyclicList[T]:
    return CyclicList(list(elements))
