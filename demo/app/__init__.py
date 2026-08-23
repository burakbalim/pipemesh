"""The demo application.

`procurement.py` lives with the example rather than here, because the example is
what a reader is meant to study and the demo is only one of the two things that
run it. Putting the example directory on the path is how this process finds it —
stated once, in the open, rather than arranged by an environment variable that
would be missing on somebody's laptop.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "examples" / "vendor-selection"))
