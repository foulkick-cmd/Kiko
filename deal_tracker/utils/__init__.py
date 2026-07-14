"""Small shared utilities.

Kept intentionally minimal; most helpers live close to where they are used
(e.g. price parsing in :mod:`deal_tracker.parsers.base`).
"""

from __future__ import annotations

from datetime import datetime, timezone


def to_utc(dt: datetime) -> datetime:
    """Return ``dt`` as timezone-aware UTC (assumes UTC if naive)."""
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


__all__ = ["to_utc"]
