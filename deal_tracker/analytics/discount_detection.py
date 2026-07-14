"""Real-discount detection.

The central idea: **do not trust Noon's displayed "before" price.** Instead we
reconstruct the product's own price history over a trailing window (default 90
days) and decide whether a drop is genuine.

All thresholds are configurable via :class:`~deal_tracker.config.AnalyticsConfig`.
The functions here are pure (no I/O), which makes them trivially unit-testable —
see ``tests/test_discount_detection.py``.
"""

from __future__ import annotations

import logging
import statistics
from datetime import timedelta

from ..config import AnalyticsConfig
from ..models import DiscountClass, PriceObservation, PriceStats

logger = logging.getLogger(__name__)


def compute_stats(
    history: list[PriceObservation],
    *,
    config: AnalyticsConfig,
) -> PriceStats:
    """Compute rolling price statistics from an observation history.

    Args:
        history: Observations **sorted oldest-first**. Only points within
            ``config.window_days`` of the most recent point are considered.
        config: Analytics thresholds.
    """
    stats = PriceStats()
    if not history:
        return stats

    history = sorted(history, key=lambda o: o.timestamp)
    latest_ts = history[-1].timestamp
    window_start = latest_ts - timedelta(days=config.window_days)
    window = [o for o in history if o.timestamp >= window_start]
    if not window:
        return stats

    prices = [o.current_price for o in window]
    stats.observations = len(window)
    stats.days_of_history = max(
        0, (window[-1].timestamp - window[0].timestamp).days
    )
    stats.min_price_90d = min(prices)
    stats.avg_price_90d = round(statistics.fmean(prices), 2)
    stats.median_price_90d = round(statistics.median(prices), 2)

    current_price = prices[-1]

    # --- days at the current price (walk backwards while ~unchanged) ---- #
    band = config.stability_band_pct / 100.0
    change_index = _last_change_index(prices, band)
    if change_index is None:
        # Never changed within the window.
        stats.days_at_current_price = stats.days_of_history
        stats.last_price_change = None
        stats.price_change_percent = 0.0
    else:
        stats.last_price_change = window[change_index].timestamp
        stats.days_at_current_price = max(
            0, (latest_ts - window[change_index].timestamp).days
        )
        prev_price = prices[change_index - 1]
        if prev_price:
            stats.price_change_percent = round(
                (current_price - prev_price) / prev_price * 100, 2
            )

    # --- stability window immediately before the last drop ------------- #
    stats.stability_before_drop = _stability_before(window, change_index, band)

    # --- sudden-drop detection ----------------------------------------- #
    stats.is_sudden_drop = _is_sudden_drop(window, config)
    return stats


def _last_change_index(prices: list[float], band: float) -> int | None:
    """Index at which the current stable price run begins.

    Walking backwards from the most recent price, this is the earliest index
    ``j`` such that every price in ``prices[j:]`` is within ``band`` of the
    current price. ``prices[j - 1]`` is therefore the pre-change price.

    Returns ``None`` when the price never left the band within the window
    (i.e. it was effectively constant — no change to report).
    """
    n = len(prices)
    if n < 2:
        return None
    current = prices[-1]
    if current == 0:
        return None
    j = n - 1
    while j > 0 and abs(prices[j - 1] - current) / current <= band:
        j -= 1
    return None if j == 0 else j


def _stability_before(
    window: list[PriceObservation], change_index: int | None, band: float
) -> int:
    """Number of days the price was stable in the run *before* the last change."""
    if change_index is None or change_index == 0:
        return 0
    pre_price = window[change_index - 1].current_price
    end_ts = window[change_index - 1].timestamp
    i = change_index - 1
    while i > 0:
        prev = window[i - 1].current_price
        if prev and abs(prev - pre_price) / pre_price <= band:
            i -= 1
        else:
            break
    start_ts = window[i].timestamp
    return max(0, (end_ts - start_ts).days)


def _is_sudden_drop(window: list[PriceObservation], config: AnalyticsConfig) -> bool:
    """Detect: stable (±band) for >= stability_min_days, then >= drop within N days.

    We look at the most recent price and check whether it is the result of a
    sharp fall that was preceded by a sustained stable plateau.
    """
    if len(window) < 2:
        return False

    band = config.stability_band_pct / 100.0
    current = window[-1].current_price

    # Find the drop: the most recent point where price fell >= sudden_drop_pct
    # relative to a recent higher plateau, within sudden_drop_window_days.
    drop_pct = config.sudden_drop_pct / 100.0
    drop_window = timedelta(days=config.sudden_drop_window_days)
    latest_ts = window[-1].timestamp

    # Candidate "before" price = max price in the short window just before the drop.
    recent = [o for o in window if latest_ts - o.timestamp <= drop_window]
    if len(recent) < 2:
        # Fall back to comparing against the previous distinct observation.
        recent = window[-2:]
    plateau_price = max(o.current_price for o in recent[:-1]) if len(recent) > 1 else recent[0].current_price
    if plateau_price <= 0:
        return False
    fell = (plateau_price - current) / plateau_price >= drop_pct
    if not fell:
        return False

    # Now verify a stable plateau of >= stability_min_days preceded the drop.
    drop_start_ts = recent[0].timestamp
    plateau = [
        o for o in window
        if o.timestamp < drop_start_ts
        and plateau_price and abs(o.current_price - plateau_price) / plateau_price <= band
    ]
    if not plateau:
        return False
    stable_days = (plateau[-1].timestamp - plateau[0].timestamp).days
    return stable_days >= config.stability_min_days


def classify_discount(
    current_price: float | None,
    stats: PriceStats,
    *,
    config: AnalyticsConfig,
) -> DiscountClass:
    """Classify the current price as real / fake / insufficient discount.

    * ``history_insufficient`` — fewer than ``min_history_days`` of history.
    * ``real_discount`` — at/near the 90-day min AND a genuine sudden drop.
    * ``fake_discount`` — sitting at the same "discounted" price for weeks
      (i.e. no sudden drop, price stale) — the displayed discount is based on an
      inflated before-price.
    * ``no_discount`` — none of the above (price not notably low).
    """
    if current_price is None or stats.min_price_90d is None:
        return DiscountClass.INSUFFICIENT
    if stats.days_of_history < config.min_history_days:
        return DiscountClass.INSUFFICIENT

    near_min = current_price <= stats.min_price_90d * config.real_discount_tolerance

    if near_min and stats.is_sudden_drop:
        return DiscountClass.REAL

    # Fake: the price has been static at this "deal" level for a long time
    # (weeks) — a permanent price dressed up as a discount.
    stale_for_weeks = stats.days_at_current_price >= 14 and not stats.is_sudden_drop
    if stale_for_weeks:
        return DiscountClass.FAKE

    if near_min and not stats.is_sudden_drop:
        # At the low end but no genuine drop event -> treat as fake-ish/no real deal.
        return DiscountClass.FAKE

    return DiscountClass.NO_DISCOUNT


def analyze_history(
    current_price: float | None,
    history: list[PriceObservation],
    *,
    config: AnalyticsConfig,
) -> tuple[PriceStats, DiscountClass]:
    """Convenience wrapper: compute stats and classify in one call."""
    stats = compute_stats(history, config=config)
    discount_class = classify_discount(current_price, stats, config=config)
    return stats, discount_class
