# common/tools/smart_dict.py

class SmartDict(dict):
    """
    A dictionary that allows accessing keys by a unique prefix.
    If the prefix matches exactly one key, that key is used.
    If no key or multiple keys match, a KeyError is raised.
    """

    def _expand_key(self, prefix: str) -> str:
        """Return the full key that uniquely matches the given prefix."""
        matches = [key for key in self.keys() if key.startswith(prefix)]
        if not matches:
            raise KeyError(f"No key starts with prefix '{prefix}'")
        if len(matches) > 1:
            raise KeyError(f"Prefix '{prefix}' matches multiple keys: {matches}")
        return matches[0]

    def __getitem__(self, prefix):
        full_key = self._expand_key(prefix)
        return super().__getitem__(full_key)

    def __contains__(self, prefix):
        """Check if a prefix uniquely matches a key."""
        try:
            self._expand_key(prefix)
            return True
        except KeyError:
            return False

    def get(self, prefix, default=None):
        """Safe get method that expands prefix first."""
        try:
            return self[prefix]
        except KeyError:
            return default
