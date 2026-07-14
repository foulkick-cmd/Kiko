"""Confidence scoring for detected deals.

Produces a numeric ``confidence_score`` in ``[0, 1]`` and a human-facing
:class:`~deal_tracker.models.Confidence` label (🟢 High / 🟡 Medium / 🔴 Low).

Signals combined:
* history length (days)
* number of observations
* scrape consistency (regularity of sampling)
* stability pattern (a clean plateau before a drop)
* sudden-drop confidence

Labelling rules (from the spec):
* 🟢 High   — 30+ days history, stable-then-genuine-drop, Noon seller.
* 🟡 Medium — partial history OR marketplace seller.
* 🔴 Low    — insufficient history OR weak evidence.
"""

from __future__ import annotations

from ..config import AnalyticsConfig
from ..models import Confidence, PriceStats, SellerType


def _history_score(stats: PriceStats, config: AnalyticsConfig) -> float:
    """0..1 based on how much history we have vs the target window."""
    target = float(config.window_days)
    return min(1.0, stats.days_of_history / target) if target else 0.0


def _observation_score(stats: PriceStats, config: AnalyticsConfig) -> float:
    """0..1 based on number of observations (roughly one per day is ideal)."""
    target = max(1, config.window_days // 2)  # ~ every other day is plenty
    return min(1.0, stats.observations / target)


def _consistency_score(stats: PriceStats, config: AnalyticsConfig) -> float:
    """0..1 measuring how regularly the product was scraped.

    Ideal cadence is ~1 observation/day, so observations/days ≈ 1. Sparse
    sampling (gaps) lowers this score.
    """
    if stats.days_of_history <= 0:
        return 0.0
    expected = stats.days_of_history + 1
    ratio = stats.observations / expected
    return max(0.0, min(1.0, ratio))


def _stability_score(stats: PriceStats, config: AnalyticsConfig) -> float:
    """0..1 rewarding a clean, sustained plateau before the drop."""
    return min(1.0, stats.stability_before_drop / float(config.stability_min_days)) \
        if config.stability_min_days else 0.0


def _drop_score(stats: PriceStats) -> float:
    """0..1 confidence that a genuine sudden drop occurred."""
    if not stats.is_sudden_drop:
        return 0.0
    magnitude = abs(stats.price_change_percent or 0.0) / 100.0
    # A 15% drop -> ~0.6, a 40%+ drop -> ~1.0
    return max(0.5, min(1.0, 0.5 + magnitude))


def score_confidence(
    stats: PriceStats,
    seller_type: SellerType,
    *,
    config: AnalyticsConfig,
) -> tuple[float, Confidence]:
    """Return ``(confidence_score, label)``."""
    h = _history_score(stats, config)
    o = _observation_score(stats, config)
    c = _consistency_score(stats, config)
    s = _stability_score(stats, config)
    d = _drop_score(stats)

    # Weighted blend of the five signals.
    numeric = round(0.25 * h + 0.15 * o + 0.15 * c + 0.20 * s + 0.25 * d, 4)

    label = _label(stats, seller_type, config)
    return numeric, label


def _label(
    stats: PriceStats, seller_type: SellerType, config: AnalyticsConfig
) -> Confidence:
    enough_history = stats.days_of_history >= config.min_history_days
    genuine = stats.is_sudden_drop and stats.stability_before_drop >= config.stability_min_days

    if not enough_history:
        return Confidence.LOW

    if enough_history and genuine and seller_type is SellerType.NOON:
        return Confidence.HIGH

    # Partial history OR marketplace seller OR genuine-but-not-Noon -> Medium.
    partial = stats.days_of_history < config.window_days
    if genuine or partial or seller_type is SellerType.MARKETPLACE:
        return Confidence.MEDIUM

    return Confidence.LOW
