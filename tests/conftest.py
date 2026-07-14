"""Shared pytest fixtures and helpers."""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest

from deal_tracker.config import AnalyticsConfig
from deal_tracker.models import PriceObservation, utcnow

FIXTURES = Path(__file__).parent / "fixtures"


def load_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def make_history(prices: list[float], *, start_days_ago: int = 0,
                 step_days: int = 1) -> list[PriceObservation]:
    """Build a price history with one observation per ``step_days``.

    The first price is oldest; the last is most recent. ``start_days_ago``
    shifts the whole series back in time (used to simulate short history).
    """
    now = utcnow()
    n = len(prices)
    total_span = (n - 1) * step_days + start_days_ago
    obs: list[PriceObservation] = []
    for i, price in enumerate(prices):
        days_ago = total_span - i * step_days
        obs.append(PriceObservation(timestamp=now - timedelta(days=days_ago),
                                     current_price=price))
    return obs


@pytest.fixture
def analytics_config() -> AnalyticsConfig:
    return AnalyticsConfig()


@pytest.fixture
def stable_then_drop() -> list[PriceObservation]:
    """41 days at 3500, then 2 days at 2900 (a genuine ~17% sudden drop)."""
    return make_history([3500.0] * 41 + [2900.0, 2900.0])


@pytest.fixture
def stale_fake() -> list[PriceObservation]:
    """60 days flat at 2999 — a permanent price dressed as a discount."""
    return make_history([2999.0] * 61)


@pytest.fixture
def short_history() -> list[PriceObservation]:
    """Only 10 days of data — insufficient to judge."""
    return make_history([1200.0] * 11)
