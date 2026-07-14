"""Kiko — a production-quality deal tracker for Noon Saudi.

The package is organised into focused sub-packages:

* :mod:`deal_tracker.fetchers`  — HTTP / browser fetching abstractions.
* :mod:`deal_tracker.parsers`   — turn raw HTML into structured products.
* :mod:`deal_tracker.stores`    — :class:`StoreAdapter` implementations (Noon, …).
* :mod:`deal_tracker.storage`   — pluggable persistence (SQLite, PostgreSQL).
* :mod:`deal_tracker.analytics` — real-discount detection, confidence, ranking.
* :mod:`deal_tracker.charts`    — price-history chart rendering.
* :mod:`deal_tracker.alerts`    — change detection and Telegram notifications.
* :mod:`deal_tracker.scheduler` — orchestration of a single tracking run.
* :mod:`deal_tracker.exporters` — CSV and other output formats.

See :mod:`deal_tracker.main` for the command-line entry point.
"""

from __future__ import annotations

__version__ = "0.1.0"
__all__ = ["__version__"]
