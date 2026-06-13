

class LowercaseDict(dict):
    def __getitem__(self, key):
        return super().__getitem__(key.lower())
    def __setitem__(self, key, value):
        super().__setitem__(key.lower(), value)

