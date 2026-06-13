from collections.abc import MutableSequence
from typing import Any, Iterable, Optional, Union

class FiniteCyclicList(MutableSequence):
    """
    A list that cycles through its elements a finite number of times.
    After max_repetitions, indexing beyond the list length raises IndexError.
    Implements MutableSequence protocol.
    """

    def __init__(self, iterable: Optional[Iterable] = None, max_repetitions: int = 1):
        """
        Initialize the finite cyclical list.

        Args:
            iterable: Initial elements for the list
            max_repetitions: Maximum number of times to cycle through the list
                           (default: 1, meaning no cycling)

        Raises:
            TypeError: If max_repetitions is not an integer
            ValueError: If max_repetitions is negative
        """
        # Validate max_repetitions
        if not isinstance(max_repetitions, int):
            raise TypeError(f"max_repetitions must be an integer, got {type(max_repetitions)}")
        if max_repetitions < 0:
            raise ValueError(f"max_repetitions must be non-negative, got {max_repetitions}")

        # Store the base list internally
        self._base_list: list = []
        self.max_repetitions: int = max_repetitions
        self._total_length: int = 0

        # Initialize with iterable if provided
        if iterable is not None:
            self._base_list = list(iterable)
            self._update_total_length()

    def _update_total_length(self) -> None:
        """Update the total length after modifications."""
        self._total_length = len(self._base_list) * self.max_repetitions

    def _validate_index(self, index: int) -> int:
        """
        Validate and normalize an index.

        Args:
            index: The index to validate

        Returns:
            Normalized index within [0, _total_length)

        Raises:
            IndexError: If index is out of range
        """
        if not self._base_list:
            raise IndexError("Cannot index empty list")

        # Handle negative indices
        if index < 0:
            index = self._total_length + index

        if index < 0 or index >= self._total_length:
            raise IndexError(f"Index {index} out of range for {self._total_length} total elements")

        return index

    def _get_base_index(self, index: int) -> int:
        """Convert global index to base list index."""
        validated_index = self._validate_index(index)
        return validated_index % len(self._base_list)

    # Required MutableSequence methods

    def __getitem__(self, index: Union[int, slice]) -> Any:
        """Get item(s) with finite cyclical indexing."""
        if isinstance(index, slice):
            # Handle slice
            start, stop, step = index.indices(self._total_length)
            result = []
            for i in range(start, stop, step):
                if self._base_list:  # Only if not empty
                    result.append(self._base_list[i % len(self._base_list)])
            return result
        else:
            # Handle single index
            base_index = self._get_base_index(index)
            return self._base_list[base_index]

    def __setitem__(self, index: Union[int, slice], value: Any) -> None:
        """Set item(s) with finite cyclical indexing."""
        if isinstance(index, slice):
            start, stop, step = index.indices(self._total_length)
            indices = list(range(start, stop, step))

            # Handle single value for slice assignment
            if not isinstance(value, (list, tuple)):
                value = [value] * len(indices)

            if len(indices) != len(value):
                raise ValueError("Slice and value must have same length")

            for i, val in zip(indices, value):
                if self._base_list:
                    base_index = i % len(self._base_list)
                    self._base_list[base_index] = val
        else:
            base_index = self._get_base_index(index)
            self._base_list[base_index] = value

    def __delitem__(self, index: Union[int, slice]) -> None:
        """Delete item(s) - not allowed as it would change cycle pattern."""
        raise TypeError("Cannot delete items from FiniteCyclicList - would change cycle pattern")

    def __len__(self) -> int:
        """Return total length including repetitions."""
        return self._total_length

    def insert(self, index: int, value: Any) -> None:
        """
        Insert an item before index.

        Args:
            index: Position to insert at (in global indexing)
            value: Value to insert
        """
        if not self._base_list:
            self._base_list.insert(0, value)
        else:
            # Normalize index for insertion
            if index < 0:
                index = self._total_length + index

            # Clamp to valid range for base list insertion
            if index < 0:
                base_index = 0
            elif index >= self._total_length:
                base_index = len(self._base_list)
            else:
                base_index = index % len(self._base_list)

            self._base_list.insert(base_index, value)

        self._update_total_length()

    # Additional list-like methods

    def append(self, value: Any) -> None:
        """Append an item to the end (affects all cycles)."""
        self._base_list.append(value)
        self._update_total_length()

    def extend(self, iterable: Iterable) -> None:
        """Extend the list (affects all cycles)."""
        self._base_list.extend(iterable)
        self._update_total_length()

    def pop(self, index: int = -1) -> Any:
        """
        Remove and return item at index.

        Args:
            index: Index of item to pop (in global indexing)

        Returns:
            The popped item

        Raises:
            IndexError: If list is empty or index out of range
        """
        if not self._base_list:
            raise IndexError("pop from empty list")

        # Normalize index
        if index < 0:
            index = self._total_length + index

        if index < 0 or index >= self._total_length:
            raise IndexError(f"pop index {index} out of range")

        base_index = index % len(self._base_list)
        result = self._base_list.pop(base_index)
        self._update_total_length()
        return result

    def remove(self, value: Any) -> None:
        """Remove first occurrence of value (affects all cycles)."""
        self._base_list.remove(value)
        self._update_total_length()

    def clear(self) -> None:
        """Clear the list."""
        self._base_list.clear()
        self._total_length = 0

    # Cycle-specific methods

    def get_cycle(self, cycle_number: int) -> list:
        """
        Get a specific cycle as a list.

        Args:
            cycle_number: Which cycle to retrieve (0 to max_repetitions-1)

        Returns:
            List containing the elements for that cycle

        Raises:
            IndexError: If cycle_number is out of range
        """
        if cycle_number < 0 or cycle_number >= self.max_repetitions:
            raise IndexError(f"Cycle {cycle_number} out of range (0-{self.max_repetitions - 1})")

        return self._base_list.copy()

    def get_cycle_range(self, start_cycle: int, end_cycle: int) -> list:
        """
        Get a range of cycles.

        Args:
            start_cycle: First cycle to include
            end_cycle: Last cycle to include (exclusive)

        Returns:
            List containing all elements in the cycle range
        """
        if not self._base_list:
            return []

        result = []
        end = min(end_cycle, self.max_repetitions)
        for _ in range(start_cycle, end):
            result.extend(self._base_list)
        return result

    # Sequence protocol methods

    def __iter__(self):
        """Iterate through all repetitions."""
        for _ in range(self.max_repetitions):
            yield from self._base_list

    def __contains__(self, value: Any) -> bool:
        """Check if value exists in any cycle."""
        return value in self._base_list

    def __reversed__(self):
        """Iterate through all repetitions in reverse."""
        for _ in range(self.max_repetitions):
            yield from reversed(self._base_list)

    def index(self, value: Any, start: int = 0, stop: int = None) -> int:
        """
        Return first index of value.

        Args:
            value: Value to find
            start: Start index (in global indexing)
            stop: Stop index (in global indexing)

        Returns:
            First index where value appears

        Raises:
            ValueError: If value not found
        """
        if stop is None:
            stop = self._total_length

        # Normalize start and stop
        if start < 0:
            start = self._total_length + start
        if stop < 0:
            stop = self._total_length + stop

        start = max(0, min(start, self._total_length))
        stop = max(0, min(stop, self._total_length))

        for i in range(start, stop):
            if self[i] == value:
                return i

        raise ValueError(f"{value} is not in list")

    def count(self, value: Any) -> int:
        """Return number of occurrences of value."""
        if not self._base_list:
            return 0
        return self._base_list.count(value) * self.max_repetitions

    # String representations

    def __repr__(self) -> str:
        """Return string representation."""
        return f"FiniteCyclicList({self._base_list}, max_repetitions={self.max_repetitions})"

    def __str__(self) -> str:
        """Return string representation."""
        return f"FiniteCyclicList({list(self)})"