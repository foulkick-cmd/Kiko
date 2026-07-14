"""Core data models shared across the whole pipeline.

These are deliberately plain ``dataclasses`` (no ORM) so that every layer —
fetchers, parsers, storage, analytics, alerts — speaks the same vocabulary
without coupling to a particular database.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Any


def utcnow() -> datetime:
    """Return a timezone-aware UTC timestamp (used everywhere for consistency)."""
    return datetime.now(timezone.utc)


class SellerType(enum.StrEnum):
    """Who actually ships/sells the product."""

    NOON = "Noon"
    MARKETPLACE = "Marketplace"
    UNKNOWN = "Unknown"


class Confidence(enum.StrEnum):
    """Human-facing confidence label for a detected deal."""

    HIGH = "High"       # 🟢
    MEDIUM = "Medium"   # 🟡
    LOW = "Low"         # 🔴

    @property
    def emoji(self) -> str:
        return {"High": "🟢", "Medium": "🟡", "Low": "🔴"}[self.value]


class DiscountClass(enum.StrEnum):
    """Result of real-vs-fake discount analysis."""

    REAL = "real_discount"
    FAKE = "fake_discount"
    INSUFFICIENT = "history_insufficient"
    NO_DISCOUNT = "no_discount"


@dataclass(slots=True)
class Product:
    """A single product observation captured during a scrape run.

    One :class:`Product` corresponds to one product *as seen at one point in
    time*. The immutable descriptive fields (title, seller, image…) live in the
    ``products`` table; the volatile fields (prices) are additionally appended
    to ``price_history`` so history is never overwritten.
    """

    product_id: str
    title: str
    store: str = "noon_sa"
    seller_name: str | None = None
    seller_type: SellerType = SellerType.UNKNOWN
    current_price: float | None = None
    before_price: float | None = None
    displayed_discount_percent: float | None = None
    rating: float | None = None
    review_count: int | None = None
    product_image: str | None = None
    product_url: str | None = None
    category: str | None = None
    currency: str = "SAR"
    timestamp: datetime = field(default_factory=utcnow)

    def to_row(self) -> dict[str, Any]:
        """Serialise to a flat dict suitable for storage / CSV."""
        d = asdict(self)
        d["seller_type"] = self.seller_type.value
        d["timestamp"] = self.timestamp.isoformat()
        return d

    def is_complete(self) -> bool:
        """True when the fields required for meaningful tracking are present.

        Used by the fetcher layer to decide whether a Requests-only fetch is
        sufficient or whether a JS-rendered (Playwright) fetch is required.
        """
        return bool(self.product_id and self.title and self.current_price is not None)


@dataclass(slots=True)
class PriceObservation:
    """A single (timestamp, price) point pulled from ``price_history``."""

    timestamp: datetime
    current_price: float
    before_price: float | None = None
    displayed_discount: float | None = None
    seller: str | None = None


@dataclass(slots=True)
class PriceStats:
    """Rolling statistics computed over a trailing window (default 90 days)."""

    days_of_history: int = 0
    observations: int = 0
    min_price_90d: float | None = None
    avg_price_90d: float | None = None
    median_price_90d: float | None = None
    days_at_current_price: int = 0
    stability_before_drop: int = 0
    last_price_change: datetime | None = None
    price_change_percent: float | None = None
    is_sudden_drop: bool = False


@dataclass(slots=True)
class DealAnalysis:
    """The full analytics verdict for one product at one point in time."""

    product_id: str
    stats: PriceStats
    discount_class: DiscountClass
    confidence: Confidence
    confidence_score: float
    score: float
    # Sub-scores retained for transparency / debugging / CSV.
    discount_score: float = 0.0
    price_history_confidence: float = 0.0
    rating_score: float = 0.0
    seller_score: float = 0.0
