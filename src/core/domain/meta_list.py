# meta_list.py

from core.domain.meta import Meta
from core.domain.smart_list import SmartList


class MetaList(SmartList, Meta):
    def __init__(self, data=None, cycles: bool = False, parent=None):
        SmartList.__init__(self, data, cycles)
        Meta.__init__(self, parent=parent)

    def _make(self, data) -> "MetaList":
        ml = MetaList(data, cycles=self.cycles, parent=None)
        ml._finalized = True
        return ml

    def __getitem__(self, arg):
        if isinstance(arg, int):
            return SmartList.__getitem__(self, arg)
        return Meta.__getitem__(self, arg)

