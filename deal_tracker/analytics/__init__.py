"""Analytics: real-discount detection, confidence scoring and ranking."""

from __future__ import annotations

from .confidence import score_confidence
from .discount_detection import analyze_history, classify_discount, compute_stats
from .ranking import DealResult, apply_filters, rank_deals, score_deal

__all__ = [
    "compute_stats",
    "analyze_history",
    "classify_discount",
    "score_confidence",
    "score_deal",
    "rank_deals",
    "apply_filters",
    "DealResult",
]
