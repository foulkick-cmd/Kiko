"""Fetcher abstractions.

The public surface is small on purpose::

    from deal_tracker.fetchers import Fetcher, FetchResult, RequestsFetcher
    from deal_tracker.fetchers import PlaywrightFetcher  # optional dependency

:class:`RequestsFetcher` is always available. :class:`PlaywrightFetcher` imports
lazily so the package works without Playwright installed.
"""

from __future__ import annotations

from .base import BlockedError, Fetcher, FetchResult, FetchStatus
from .requests_fetcher import RequestsFetcher

__all__ = [
    "Fetcher",
    "FetchResult",
    "FetchStatus",
    "BlockedError",
    "RequestsFetcher",
    "PlaywrightFetcher",
]


def __getattr__(name: str):  # pragma: no cover - thin lazy import shim
    if name == "PlaywrightFetcher":
        from .playwright_fetcher import PlaywrightFetcher

        return PlaywrightFetcher
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
