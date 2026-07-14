#!/usr/bin/env python3
"""Root convenience entry point — delegates to :mod:`deal_tracker.main`.

Run ``python main.py run`` (or install the package and use the ``kiko`` script).
"""

from __future__ import annotations

import sys

from deal_tracker.main import main

if __name__ == "__main__":
    sys.exit(main())
