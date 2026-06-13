# src/common/data/articulation.py
from dataclasses import dataclass
from enum import Enum

# Staccatissimo < Stopped ≈ Staccato < Marcato < Portato < Legato ≈ Tenuto

"""
Shorthand, name,  Visual Appearance
-.	Staccato	  A small dot (shortened, detached note)
--	Tenuto	      A short horizontal line (hold note for full value)
-^	Marcato	      A vertical wedge / arrowhead (strong accent)
->	Accent	      A sideways "greater-than" sign (emphasized attack)
-+	Stopped note (Snap Pizzicato)	A plus sign
-!	Staccatissimo A small vertical wedge (very short, sharp note)
-_	Portato       (or tenuto-staccato)	A dot under a slur (detached but connected)
"""

"""
Articulation	Duration	Dynamic Addition
Staccatissimo	0.25    None
Stopped	        0.30	None
Staccato	    0.40	None
Marcato      	0.55	+10
Portato	        0.80	None
Accent	        0.90	+5
Legato	        1.00	None
Tenuto	        1.00	Emphasis (not numeric)
Sfz            	Full length	+10
"""

from enum import Enum
from typing import Optional
from dataclasses import dataclass

@dataclass(frozen=True)
class ArticulationValue:
    duration: Optional[float]
    dynamic: int

class Articulation(Enum):
    """Articulation types with duration multipliers and dynamic additions."""
    staccatissimo = ArticulationValue(0.25, 0)
    stopped = ArticulationValue(0.30, 0)
    staccato = ArticulationValue(0.40, 0)
    marcato = ArticulationValue(0.55, 10)
    portato = ArticulationValue(0.80, 0)
    accent = ArticulationValue(0.90, 5)
    legato = ArticulationValue(1.00, 0)
    tenuto = ArticulationValue(1.00, 0)
    sfz = ArticulationValue(None, 10)
    fermata = ArticulationValue(None, 0)

    @property
    def duration(self) -> Optional[float]:
        return self.value.duration

    @property
    def dynamic(self) -> int:
        return self.value.dynamic

    @classmethod
    def get(cls, key: str) -> Optional['Articulation']:
        """Get articulation member by shorthand or full name."""
        # Map of all keys (shorthand and full names) to members
        lookup_map = {
            # Shorthands
            "-!": cls.staccatissimo,
            "-.": cls.staccato,
            "-+": cls.stopped,
            "-^": cls.marcato,
            "-_": cls.portato,
            "->": cls.accent,
            "--": cls.tenuto,
            # Full names (lowercase)
            "staccatissimo": cls.staccatissimo,
            "stopped": cls.stopped,
            "staccato": cls.staccato,
            "marcato": cls.marcato,
            "portato": cls.portato,
            "accent": cls.accent,
            "legato": cls.legato,
            "tenuto": cls.tenuto,
            "sfz": cls.sfz,
            "fermata": cls.fermata,
        }
        return lookup_map.get(key.lower())

if __name__ == '__main__':
    # All of these work
    print(Articulation.get("marcato"))  # Articulation.marcato
    print(Articulation.get("MARCATO"))  # Articulation.marcato (case insensitive))
    print(Articulation.get("-^"))  # Articulation.marcato
    print(Articulation.get("staccato"))  # Articulation.staccato
    print(Articulation.get("-."))  # Articulation.staccato
    artic = Articulation.get("sfz")  # Articulation.sfz

    print(artic.duration)  # 0.55 for marcato
    print(artic.dynamic)  # 10 for marcato