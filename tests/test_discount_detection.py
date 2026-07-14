"""Tests for real-vs-fake discount detection."""

from __future__ import annotations

from deal_tracker.analytics.discount_detection import (
    classify_discount,
    compute_stats,
)
from deal_tracker.models import DiscountClass


def test_stats_basic(analytics_config, stable_then_drop):
    stats = compute_stats(stable_then_drop, config=analytics_config)
    assert stats.min_price_90d == 2900.0
    assert stats.observations == len(stable_then_drop)
    assert stats.days_of_history >= 40
    assert stats.avg_price_90d > 2900.0


def test_sudden_drop_detected(analytics_config, stable_then_drop):
    stats = compute_stats(stable_then_drop, config=analytics_config)
    assert stats.is_sudden_drop is True
    assert stats.stability_before_drop >= analytics_config.stability_min_days


def test_real_discount(analytics_config, stable_then_drop):
    stats = compute_stats(stable_then_drop, config=analytics_config)
    cls = classify_discount(2900.0, stats, config=analytics_config)
    assert cls is DiscountClass.REAL


def test_fake_discount_stale_price(analytics_config, stale_fake):
    stats = compute_stats(stale_fake, config=analytics_config)
    assert stats.is_sudden_drop is False
    cls = classify_discount(2999.0, stats, config=analytics_config)
    assert cls is DiscountClass.FAKE


def test_history_insufficient(analytics_config, short_history):
    stats = compute_stats(short_history, config=analytics_config)
    cls = classify_discount(1200.0, stats, config=analytics_config)
    assert cls is DiscountClass.INSUFFICIENT


def test_no_discount_when_price_high(analytics_config, stable_then_drop):
    # Current price well above the 90-day min and no drop event at this level.
    stats = compute_stats(stable_then_drop, config=analytics_config)
    cls = classify_discount(3500.0, stats, config=analytics_config)
    assert cls in (DiscountClass.NO_DISCOUNT, DiscountClass.FAKE)


def test_empty_history(analytics_config):
    stats = compute_stats([], config=analytics_config)
    assert stats.observations == 0
    assert classify_discount(100.0, stats, config=analytics_config) is DiscountClass.INSUFFICIENT
