"""Decide whether a product warrants a fresh alert.

Rules (from the spec):

* Only alert when ``current_price < last recorded price``.
* Skip unchanged products.
* Skip duplicate alerts (same product at the same price already alerted).

Duplicate suppression is backed by a small on-disk JSON state file so it works
across scheduled runs / process restarts.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class ChangeDecision:
    should_send: bool
    reason: str
    previous_price: float | None = None


class AlertState:
    """Persisted record of the last price we alerted on, per product."""

    def __init__(self, path: str = "data/alert_state.json") -> None:
        self.path = Path(path)
        self._state: dict[str, float] = {}
        self._loaded = False

    def load(self) -> None:
        if self._loaded:
            return
        if self.path.exists():
            try:
                self._state = json.loads(self.path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError) as exc:
                logger.warning("Could not read alert state %s: %s", self.path, exc)
                self._state = {}
        self._loaded = True

    def last_alerted_price(self, product_id: str) -> float | None:
        self.load()
        return self._state.get(product_id)

    def record(self, product_id: str, price: float) -> None:
        self.load()
        self._state[product_id] = price

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self._state, indent=2), encoding="utf-8")


def should_alert(
    product_id: str,
    current_price: float | None,
    last_recorded_price: float | None,
    *,
    state: AlertState,
    min_drop_percent: float = 0.0,
) -> ChangeDecision:
    """Return whether to alert for this product right now.

    Args:
        current_price: The freshly scraped price.
        last_recorded_price: The previous price in ``price_history`` (the
            most recent *before* this run's snapshot).
        state: Duplicate-alert suppression state.
        min_drop_percent: Require at least this % drop vs the last price.
    """
    if current_price is None:
        return ChangeDecision(False, "no current price")

    if last_recorded_price is None:
        # First time we see it — no previous price to beat; do not alert.
        return ChangeDecision(False, "no prior price on record")

    if current_price >= last_recorded_price:
        return ChangeDecision(False, "price not lower than last record", last_recorded_price)

    drop_pct = (last_recorded_price - current_price) / last_recorded_price * 100
    if drop_pct < min_drop_percent:
        return ChangeDecision(False, f"drop {drop_pct:.1f}% below threshold", last_recorded_price)

    already = state.last_alerted_price(product_id)
    if already is not None and abs(already - current_price) < 1e-9:
        return ChangeDecision(False, "duplicate alert (already sent at this price)", last_recorded_price)

    return ChangeDecision(True, f"price dropped {drop_pct:.1f}%", last_recorded_price)
