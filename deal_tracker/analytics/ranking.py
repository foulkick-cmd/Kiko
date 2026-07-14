"""Deal ranking and filtering.

Final score (weights configurable via
:class:`~deal_tracker.config.RankingConfig`)::

    score = 0.4 * discount_score
          + 0.3 * price_history_confidence
          + 0.2 * rating_score
          + 0.1 * seller_score
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from ..config import RankingConfig
from ..models import (
    Confidence,
    DealAnalysis,
    DiscountClass,
    PriceStats,
    Product,
    SellerType,
)

logger = logging.getLogger(__name__)

_CONFIDENCE_RANK = {Confidence.LOW: 0, Confidence.MEDIUM: 1, Confidence.HIGH: 2}


@dataclass(slots=True)
class DealResult:
    """A product paired with its full analysis — the unit of ranking/output."""

    product: Product
    analysis: DealAnalysis

    @property
    def score(self) -> float:
        return self.analysis.score


def _discount_score(current: float | None, stats: PriceStats, discount_class: DiscountClass) -> float:
    """0..1 reward for a genuinely low price vs the 90-day average.

    Fake / insufficient discounts are heavily penalised so they cannot
    dominate the ranking on displayed-discount alone.
    """
    if current is None or not stats.avg_price_90d:
        return 0.0
    raw = max(0.0, (stats.avg_price_90d - current) / stats.avg_price_90d)
    raw = min(1.0, raw)
    if discount_class is DiscountClass.REAL:
        return raw
    if discount_class is DiscountClass.FAKE:
        return raw * 0.25
    return raw * 0.1  # insufficient / no_discount


def _rating_score(rating: float | None) -> float:
    """Normalise a 0-5 rating to 0..1."""
    if rating is None:
        return 0.0
    return max(0.0, min(1.0, rating / 5.0))


def _seller_score(seller_type: SellerType, config: RankingConfig) -> float:
    if seller_type is SellerType.NOON:
        return config.seller_score_noon
    if seller_type is SellerType.MARKETPLACE:
        return config.seller_score_marketplace
    return (config.seller_score_noon + config.seller_score_marketplace) / 2.0


def score_deal(
    product: Product,
    stats: PriceStats,
    discount_class: DiscountClass,
    confidence: Confidence,
    confidence_score: float,
    *,
    config: RankingConfig,
) -> DealAnalysis:
    """Compute the weighted deal score and package a :class:`DealAnalysis`."""
    discount_score = _discount_score(product.current_price, stats, discount_class)
    rating_score = _rating_score(product.rating)
    seller_score = _seller_score(product.seller_type, config)

    score = round(
        config.weight_discount * discount_score
        + config.weight_history_confidence * confidence_score
        + config.weight_rating * rating_score
        + config.weight_seller * seller_score,
        4,
    )

    return DealAnalysis(
        product_id=product.product_id,
        stats=stats,
        discount_class=discount_class,
        confidence=confidence,
        confidence_score=confidence_score,
        score=score,
        discount_score=round(discount_score, 4),
        price_history_confidence=round(confidence_score, 4),
        rating_score=round(rating_score, 4),
        seller_score=round(seller_score, 4),
    )


def apply_filters(results: list[DealResult], config: RankingConfig) -> list[DealResult]:
    """Filter deals by min discount %, min rating, seller type and confidence."""
    out = results
    if config.min_discount_percent is not None:
        thr = config.min_discount_percent
        out = [
            r for r in out
            if (r.product.displayed_discount_percent or 0) >= thr
        ]
    if config.min_rating is not None:
        out = [r for r in out if (r.product.rating or 0) >= config.min_rating]
    if config.seller_type:
        want = config.seller_type.lower()
        out = [r for r in out if r.product.seller_type.value.lower() == want]
    if config.min_confidence:
        try:
            floor = _CONFIDENCE_RANK[Confidence(config.min_confidence)]
            out = [r for r in out if _CONFIDENCE_RANK[r.analysis.confidence] >= floor]
        except (KeyError, ValueError):
            logger.warning("Invalid min_confidence %r; ignoring.", config.min_confidence)
    return out


def rank_deals(results: list[DealResult]) -> list[DealResult]:
    """Return deals sorted by score (highest first), stable by product id."""
    return sorted(results, key=lambda r: (-r.score, r.product.product_id))
