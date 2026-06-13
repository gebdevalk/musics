from typing import Generic, TypeVar, List, Iterator, MutableSequence, Protocol, Optional
import random
from collections.abc import MutableSequence as ABCMutableSequence
import sys

T = TypeVar('T')

class Cyclic(Protocol[T]):
    """Interface for cyclic collections"""

    @property
    def repetitions(self) -> int: ...

    @repetitions.setter
    def repetitions(self, value: int) -> None: ...

    @property
    def iteration(self) -> int: ...

    @iteration.setter
    def iteration(self, value: int) -> None: ...


class CyclicList(Generic[T], ABCMutableSequence[T]):
    def __init__(self, items=None, repetitions: int = 0):
        if items is None:
            self._delegate: List[T] = []
        elif isinstance(items, int):
            # capacity constructor
            self._delegate: List[T] = [None] * items  # type: ignore
        else:
            self._delegate: List[T] = list(items)

        self.repetitions = repetitions
        self.iteration = -1

    @property
    def length(self) -> int:
        """Get the cyclic length including repetitions"""
        return len(self._delegate) + len(self._delegate) * self.repetitions

    def update_observers(self) -> None:
        """Hook method for observers"""
        pass

    def __getitem__(self, index: int) -> T:
        """Get item with cyclic indexing"""
        if not self._delegate:
            raise IndexError("list is empty")

        if index == 0:
            return self._delegate[0]

        i = index % len(self._delegate)
        while i < 0:
            i += len(self._delegate)

        if i == 0:
            self.iteration += 1
            # self.update_observers()

        return self._delegate[i]

    def __setitem__(self, index: int, value: T) -> None:
        """Set item with cyclic indexing"""
        if not self._delegate:
            raise IndexError("list is empty")

        i = index % len(self._delegate)
        while i < 0:
            i += len(self._delegate)

        self._delegate[i] = value

    def __delitem__(self, index: int) -> None:
        """Delete item - not cyclically implemented"""
        del self._delegate[index]

    def __len__(self) -> int:
        return len(self._delegate)

    def insert(self, index: int, value: T) -> None:
        """Insert item at index"""
        self._delegate.insert(index, value)

    def __iter__(self) -> Iterator[T]:
        return self.Itr(self)

    def map(self, transform) -> 'CyclicList[T]':
        """Map function over list"""
        result = CyclicList[T](len(self._delegate), self.repetitions)
        return self.map_to(result, transform)

    def map_to(self, destination: 'CyclicList[T]', transform) -> 'CyclicList[T]':
        """Map to existing CyclicList"""
        for i in range(len(self._delegate)):
            destination.append(transform(self[i]))
        return destination

    def infinite(self) -> None:
        """Make list infinite"""
        self.repetitions = sys.maxsize

    def infinited(self) -> 'CyclicList[T]':
        """Return infinite copy"""
        return CyclicList(self._delegate, sys.maxsize)

    def repeated(self, count: int) -> 'CyclicList[T]':
        """Return repeated list"""
        acc = []
        for _ in range(1, count):
            acc.extend(self._delegate)
        return CyclicList(acc, count - 1)

    def reverse(self) -> None:
        """Reverse in place"""
        self._delegate.reverse()

    def shuffle(self) -> None:
        """Shuffle in place"""
        random.shuffle(self._delegate)

    def reversed(self) -> 'CyclicList[T]':
        """Return reversed copy"""
        return CyclicList(list(reversed(self._delegate)), self.repetitions)

    def shuffled(self) -> 'CyclicList[T]':
        """Return shuffled copy"""
        shuffled_list = self._delegate.copy()
        random.shuffle(shuffled_list)
        return CyclicList(shuffled_list, self.repetitions)

    def shift_left(self, count: int) -> None:
        """Shift elements left"""
        self._shift_left(count)

    def shift_right(self, count: int) -> None:
        """Shift elements right"""
        self._shift_right(count)

    def shifted_left(self, count: int) -> 'CyclicList[T]':
        """Return shifted left copy"""
        if not self._delegate:
            return CyclicList([], self.repetitions)

        acc = []
        size = len(self._delegate)
        for index in range(size):
            i = index + count
            while i < 0:
                i += size
            acc.append(self[i % size])

        return CyclicList(acc, self.repetitions)

    def shifted_right(self, count: int) -> 'CyclicList[T]':
        """Return shifted right copy"""
        if not self._delegate:
            return CyclicList([], self.repetitions)

        acc = []
        size = len(self._delegate)
        for index in range(size):
            i = index - count
            while i < 0:
                i += size
            acc.append(self[i % size])

        return CyclicList(acc, self.repetitions)

    def _shift_left(self, count: int) -> None:
        """Internal shift left implementation"""
        if not self._delegate:
            return

        acc = []
        size = len(self._delegate)
        for index in range(size):
            i = index + count
            while i < 0:
                i += size
            acc.append(self[i % size])

        self._delegate.clear()
        self._delegate.extend(acc)

    def _shift_right(self, count: int) -> None:
        """Internal shift right implementation"""
        if not self._delegate:
            return

        acc = []
        size = len(self._delegate)
        for index in range(size):
            i = index - count
            while i < 0:
                i += size
            acc.append(self[i % size])

        self._delegate.clear()
        self._delegate.extend(acc)

    def noop(self) -> None:
        """No operation"""
        pass

    def __str__(self) -> str:
        builder = str(self._delegate)
        if self.repetitions > 0:
            builder += f"{self.repetitions}:"
        return builder

    def __repr__(self) -> str:
        return f"CyclicList({self._delegate}, repetitions={self.repetitions})"

    class Itr(Iterator[T]):
        """Iterator implementation"""

        def __init__(self, outer):
            self._outer = outer
            self._cursor = 0

        def __next__(self) -> T:
            if abs(self._cursor) >= self._outer.length:
                raise StopIteration

            result = self._outer[self._cursor]
            self._cursor += 1
            return result

        def __iter__(self) -> Iterator[T]:
            return self

        def has_next(self) -> bool:
            return abs(self._cursor) < self._outer.length

        def next_index(self) -> int:
            return self._cursor + 1

        def has_previous(self) -> bool:
            return abs(self._cursor) < self._outer.length

        def previous_index(self) -> int:
            return self._cursor - 1

        def previous(self) -> T:
            self._cursor -= 1
            return self._outer[self._cursor]

        def add(self, element: T) -> None:
            self._outer._delegate.append(element)

        def remove(self) -> None:
            i = self._cursor % len(self._outer._delegate)
            while i < 0:
                i += len(self._outer._delegate)
            del self._outer._delegate[i]

        def set(self, element: T) -> None:
            self._outer._delegate[self._cursor] = element


class InfiniteList(CyclicList[T]):
    """Infinite cyclic list"""

    def __init__(self, items):
        super().__init__(items, sys.maxsize)


def cyclic_list_of(*elements) -> CyclicList:
    """Create a CyclicList from elements"""
    return CyclicList(list(elements))