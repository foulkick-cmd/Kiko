"""Tests for confidence scoring and labelling."""

from __future__ import annotations

from deal_tracker.analytics.confidence import score_confidence
from deal_tracker.analytics.discount_detection import compute_stats
from deal_tracker.models import Confidence, SellerType


def test_high_confidence_noon_genuine_drop(analytics_config, stable_then_drop):
    stats = compute_stats(stable_then_drop, config=analytics_config)
    score, label = score_confidence(stats, SellerType.NOON, config=analytics_config)
    assert label is Confidence.HIGH
    assert 0.0 <= score <= 1.0
    assert score > 0.4


def test_medium_confidence_marketplace(analytics_config, stable_then_drop):
    stats = compute_stats(stable_then_drop, config=analytics_config)
    _, label = score_confidence(stats, SellerType.MARKETPLACE, config=analytics_config)
    assert label is Confidence.MEDIUM


def test_low_confidence_insufficient(analytics_config, short_history):
    stats = compute_stats(short_history, config=analytics_config)
    score, label = score_confidence(stats, SellerType.NOON, config=analytics_config)
    assert label is Confidence.LOW
    assert score < 0.5


def test_confidence_emoji():
    assert Confidence.HIGH.emoji == "🟢"
    assert Confidence.MEDIUM.emoji == "🟡"
    assert Confidence.LOW.emoji == "🔴"
