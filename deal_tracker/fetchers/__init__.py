"""Fetcher abstractions.

The public surface is small on purpose::

    from deal_tracker.fetchers import Fetcher, FetchResult, RequestsFetcher
    from deal_tracker.fetchers import PlaywrightFetcher  # optional dependency

:class:`RequestsFetcher` is always available. :class:`PlaywrightFetcher` imports
lazily so the package works without Playwright installed.
"""

from __future__ import annotations

import importlib.util

from .base import BlockedError, Fetcher, FetchResult, FetchStatus
from .requests_fetcher import RequestsFetcher

__all__ = [
    "Fetcher",
    "FetchResult",
    "FetchStatus",
    "BlockedError",
    "RequestsFetcher",
    "PlaywrightFetcher",
    "playwright_available",
]


def playwright_available() -> bool:
    """Return True if the optional Playwright dependency is importable.

    Callers use this to decide whether JS-render escalation is possible and to
    degrade gracefully (rather than crashing) when Playwright is not installed.
    """
    return importlib.util.find_spec("playwright") is not None


def __getattr__(name: str):  # pragma: no cover - thin lazy import shim
    if name == "PlaywrightFetcher":
        from .playwright_fetcher import PlaywrightFetcher

        return PlaywrightFetcher
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
