from experimental.lisp.lisp_list import LispList


class T:
    """Type factory with operator overloading for natural list creation"""

    def __init__(self, name: str):
        self.type = Type(name=name)

    def __call__(self, *args):
        """Create list: Number(1,2,3)"""
        # Handle nested structures
        if len(args) == 1 and isinstance(args[0], (list, tuple, np.ndarray)):
            data = args[0]
        else:
            data = list(args)
        return LispList(self.type, data)

    def __rshift__(self, other):
        """Create singleton with cons: Number >> 42"""
        return LispList(self.type, [other])

    def __lshift__(self, other):
        """Create singleton with append: Number << 42"""
        return LispList(self.type, [other])


# Create type instances
# Number = T("Number")
# String = T("String")
# List = T("List")
# Matrix = T("Matrix")
# Tensor = T("Tensor")
