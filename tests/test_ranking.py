"""Tests for deal scoring, ranking and filtering."""

from __future__ import annotations

from deal_tracker.analytics.confidence import score_confidence
from deal_tracker.analytics.discount_detection import analyze_history
from deal_tracker.analytics.ranking import (
    DealResult,
    apply_filters,
    rank_deals,
    score_deal,
)
from deal_tracker.config import RankingConfig
from deal_tracker.models import Confidence, Product, SellerType


def _build(product, history, analytics_config) -> DealResult:
    stats, dclass = analyze_history(product.current_price, history, config=analytics_config)
    cscore, conf = score_confidence(stats, product.seller_type, config=analytics_config)
    analysis = score_deal(product, stats, dclass, conf, cscore, config=RankingConfig())
    return DealResult(product=product, analysis=analysis)


def test_real_deal_outranks_fake(analytics_config, stable_then_drop, stale_fake):
    real = Product(product_id="R", title="Real", current_price=2900.0,
                   rating=4.6, seller_type=SellerType.NOON)
    fake = Product(product_id="F", title="Fake", current_price=2999.0,
                   rating=4.6, seller_type=SellerType.NOON)
    r1 = _build(real, stable_then_drop, analytics_config)
    r2 = _build(fake, stale_fake, analytics_config)
    ranked = rank_deals([r2, r1])
    assert ranked[0].product.product_id == "R"
    assert r1.score > r2.score


def test_score_weights_sum_to_range(analytics_config, stable_then_drop):
    p = Product(product_id="R", title="Real", current_price=2900.0,
                rating=5.0, seller_type=SellerType.NOON)
    result = _build(p, stable_then_drop, analytics_config)
    assert 0.0 <= result.score <= 1.0
    a = result.analysis
    assert a.discount_score >= 0
    assert a.seller_score == 1.0  # Noon


def test_filters(analytics_config, stable_then_drop):
    noon = Product(product_id="N", title="N", current_price=2900.0, rating=4.6,
                   displayed_discount_percent=20, seller_type=SellerType.NOON)
    mp = Product(product_id="M", title="M", current_price=2900.0, rating=3.0,
                 displayed_discount_percent=5, seller_type=SellerType.MARKETPLACE)
    results = [_build(noon, stable_then_drop, analytics_config),
               _build(mp, stable_then_drop, analytics_config)]

    cfg = RankingConfig(min_rating=4.0)
    assert {r.product.product_id for r in apply_filters(results, cfg)} == {"N"}

    cfg = RankingConfig(seller_type="Noon")
    assert {r.product.product_id for r in apply_filters(results, cfg)} == {"N"}

    cfg = RankingConfig(min_discount_percent=10)
    assert {r.product.product_id for r in apply_filters(results, cfg)} == {"N"}

    cfg = RankingConfig(min_confidence="High")
    ids = {r.product.product_id for r in apply_filters(results, cfg)}
    assert "N" in ids and "M" not in ids
