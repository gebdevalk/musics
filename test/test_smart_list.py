# test_smart_list.py

import pytest
import numpy as np
from core.domain.smart_list import SmartList
from core.domain.leafs import Part


# ------------------------------------------------------------------
# Fixtures
# ------------------------------------------------------------------

@pytest.fixture
def int_list():
    sl = SmartList([1, 2, 3, 4, 5])
    sl.finalize()
    return sl

@pytest.fixture
def float_list():
    sl = SmartList([1.0, 2.0, 3.0, 4.0, 5.0])
    sl.finalize()
    return sl

@pytest.fixture
def str_list():
    sl = SmartList(["do", "re", "mi", "fa", "sol"])
    sl.finalize()
    return sl

@pytest.fixture
def cyclic_list():
    sl = SmartList([1, 2, 3], cycles=True)
    sl.finalize()
    return sl

@pytest.fixture
def matrix():
    sl = SmartList([[1, 2, 3], [4, 5, 6], [7, 8, 9]])
    sl.finalize()
    return sl

@pytest.fixture
def mutable_list():
    return SmartList()


# ------------------------------------------------------------------
# Construction and finalization
# ------------------------------------------------------------------

class TestConstruction:

    def test_empty_construction(self):
        sl = SmartList()
        assert len(sl) == 0
        assert not sl._finalized
        assert sl.type is None

    def test_list_construction(self):
        sl = SmartList([1, 2, 3])
        assert len(sl) == 3
        assert not sl._finalized

    def test_numpy_construction_is_finalized(self):
        sl = SmartList(np.array([1, 2, 3]))
        assert sl._finalized
        assert sl.type == int

    def test_finalize_infers_int(self):
        sl = SmartList([1, 2, 3])
        sl.finalize()
        assert sl.type == int
        assert sl._finalized

    def test_finalize_infers_float(self):
        sl = SmartList([1.0, 2.0, 3.0])
        sl.finalize()
        assert sl.type == float

    def test_finalize_infers_str(self):
        sl = SmartList(["a", "b", "c"])
        sl.finalize()
        assert sl.type == str

    def test_finalize_infers_object_for_mixed(self):
        sl = SmartList([1, "a", 2.0])
        sl.finalize()
        assert sl.type == object

    def test_finalize_empty_is_object(self):
        sl = SmartList([])
        sl.finalize()
        assert sl.type == object

    def test_finalize_is_idempotent(self):
        sl = SmartList([1, 2, 3])
        sl.finalize()
        sl.finalize()
        assert sl._finalized

    def test_explicit_type_hint_preserved(self):
        sl = SmartList([1, 2, 3], type_hint=float)
        sl.finalize()
        assert sl.type == float

    def test_finalize_returns_self(self):
        sl = SmartList([1, 2, 3])
        result = sl.finalize()
        assert result is sl


# ------------------------------------------------------------------
# Mutation guards
# ------------------------------------------------------------------

class TestMutationGuards:

    def test_append_before_finalize(self, mutable_list):
        mutable_list.append(1)
        mutable_list.append(2)
        assert len(mutable_list) == 2

    def test_extend_before_finalize(self, mutable_list):
        mutable_list.extend([1, 2, 3])
        assert len(mutable_list) == 3

    def test_append_after_finalize_raises(self, int_list):
        with pytest.raises(RuntimeError, match="finalized"):
            int_list.append(6)

    def test_extend_after_finalize_raises(self, int_list):
        with pytest.raises(RuntimeError, match="finalized"):
            int_list.extend([6, 7])

    def test_array_op_before_finalize_raises(self, mutable_list):
        mutable_list.extend([1, 2, 3])
        with pytest.raises(RuntimeError, match="finalize"):
            mutable_list.rotate()


# ------------------------------------------------------------------
# Type inference with numpy subtypes
# ------------------------------------------------------------------

class TestTypeInference:

    def test_numpy_int_infers_int(self):
        sl = SmartList(np.array([1, 2, 3], dtype=np.int32))
        assert sl.type == int

    def test_numpy_float_infers_float(self):
        sl = SmartList(np.array([1.0, 2.0], dtype=np.float32))
        assert sl.type == float

    def test_none_items_skipped_in_inference(self):
        sl = SmartList([None, None, 1, 2])
        sl.finalize()
        assert sl.type == int


# ------------------------------------------------------------------
# Sequence protocol
# ------------------------------------------------------------------

class TestSequenceProtocol:

    def test_getitem(self, int_list):
        assert int_list[0] == 1
        assert int_list[4] == 5

    def test_getitem_non_integer_raises(self, int_list):
        with pytest.raises(TypeError):
            int_list["key"]

    def test_len(self, int_list):
        assert len(int_list) == 5

    def test_iter(self, int_list):
        assert list(int_list) == list(int_list.data)

    def test_cyclic_getitem_wraps(self, cyclic_list):
        assert cyclic_list[3] == cyclic_list[0]
        assert cyclic_list[4] == cyclic_list[1]
        assert cyclic_list[7] == cyclic_list[1]

    def test_non_cyclic_out_of_bounds_raises(self, int_list):
        with pytest.raises(IndexError):
            _ = int_list[99]


# ------------------------------------------------------------------
# Cyclic helpers
# ------------------------------------------------------------------

class TestCyclic:

    def test_cycle_next_advances(self, cyclic_list):
        assert cyclic_list.cycle_next() == 1
        assert cyclic_list.cycle_next() == 2
        assert cyclic_list.cycle_next() == 3
        assert cyclic_list.cycle_next() == 1  # wraps

    def test_cycle_reset(self, cyclic_list):
        cyclic_list.cycle_next()
        cyclic_list.cycle_next()
        cyclic_list.cycle_reset()
        assert cyclic_list.cycle_next() == 1

    def test_cycle_next_on_empty_raises(self):
        sl = SmartList([], cycles=True)
        sl.finalize()
        with pytest.raises(IndexError):
            sl.cycle_next()


# ------------------------------------------------------------------
# Array operations
# ------------------------------------------------------------------

class TestArrayOperations:

    def test_rotate_left(self, int_list):
        result = int_list.rotate(2)
        assert list(result) == [3, 4, 5, 1, 2]

    def test_rotate_right(self, int_list):
        result = int_list.rotate(-1)
        assert list(result) == [5, 1, 2, 3, 4]

    def test_reverse(self, int_list):
        result = int_list.reverse()
        assert list(result) == [5, 4, 3, 2, 1]

    def test_invert_1d(self, int_list):
        result = int_list.invert()
        assert list(result) == [5, 4, 3, 2, 1]

    def test_transpose_adds_semitones(self, int_list):
        result = int_list.transpose(12)
        assert list(result) == [13, 14, 15, 16, 17]

    def test_transpose_non_numeric_raises(self, str_list):
        with pytest.raises(TypeError, match="numeric"):
            str_list.transpose(1)

    def test_retrograde_inversion(self, int_list):
        result = int_list.retrograde_inversion()
        assert list(result) == list(int_list.reverse().invert())

    def test_flat(self, matrix):
        result = matrix.flat
        assert len(result) == 9

    def test_transpose_matrix(self, matrix):
        t = matrix.T
        assert t.data.shape == (3, 3)
        assert t.data[0, 1] == matrix.data[1, 0]

    def test_reshape(self, int_list):
        result = int_list.reshape(5, 1)
        assert result.data.shape == (5, 1)

    def test_operations_return_finalized(self, int_list):
        assert int_list.rotate()._finalized
        assert int_list.reverse()._finalized
        assert int_list.transpose(1)._finalized


# ------------------------------------------------------------------
# Aggregation
# ------------------------------------------------------------------

class TestAggregation:

    def test_sum(self, int_list):
        assert int_list.sum == 15

    def test_mean(self, int_list):
        assert int_list.mean == 3.0

    def test_min(self, int_list):
        assert int_list.min == 1

    def test_max(self, int_list):
        assert int_list.max == 5

    def test_std(self, float_list):
        assert pytest.approx(float_list.std, 0.01) == np.std([1.0, 2.0, 3.0, 4.0, 5.0])

    def test_aggregation_on_non_numeric_raises(self, str_list):
        with pytest.raises(TypeError, match="numeric"):
            _ = str_list.sum


# ------------------------------------------------------------------
# Lisp classics
# ------------------------------------------------------------------

class TestLispClassics:

    def test_car(self, int_list):
        assert int_list.car == 1

    def test_car_empty(self):
        sl = SmartList([])
        sl.finalize()
        assert sl.car is None

    def test_cdr(self, int_list):
        result = int_list.cdr
        assert list(result) == [2, 3, 4, 5]

    def test_cdr_single_element(self):
        sl = SmartList([42])
        sl.finalize()
        assert sl.cdr is None

    def test_cdr_preserves_finalized(self, int_list):
        assert int_list.cdr._finalized

    def test_cdr_mutable(self):
        sl = SmartList([1, 2, 3])
        result = sl.cdr
        assert isinstance(result, SmartList)
        assert list(result) == [2, 3]


# ------------------------------------------------------------------
# Printing
# ------------------------------------------------------------------

class TestPrinting:

    def test_repr_compact(self, int_list):
        r = repr(int_list)
        assert "int" in r
        assert "1" in r

    def test_str_pretty(self, int_list):
        s = str(int_list)
        assert "int" in s

    def test_mutable_tag_in_repr(self):
        sl = SmartList([1, 2, 3])
        assert "mutable" in repr(sl)

    def test_finalized_no_mutable_tag(self, int_list):
        assert "mutable" not in repr(int_list)

    def test_cyclic_tag_in_repr(self, cyclic_list):
        assert "cyclic" in repr(cyclic_list)

    def test_debug_print_runs(self, int_list, capsys):
        int_list.debug_print()
        out = capsys.readouterr().out
        assert "SmartList" in out
        assert "finalized" in out

    def test_prototype_runs(self, int_list):
        result = int_list.prototype()
        assert "int" in result
