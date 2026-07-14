"""Render a 90-day price-history PNG chart with matplotlib.

Uses the non-interactive ``Agg`` backend so it works headlessly on a server.
The chart is designed to be attached to alerts.
"""

from __future__ import annotations

import logging
from datetime import timedelta
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless backend — must be set before pyplot import
import matplotlib.dates as mdates  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402

from ..models import PriceObservation, PriceStats  # noqa: E402

logger = logging.getLogger(__name__)


def render_price_chart(
    product_id: str,
    title: str,
    history: list[PriceObservation],
    *,
    out_dir: str,
    window_days: int = 90,
    stats: PriceStats | None = None,
    currency: str = "SAR",
) -> str | None:
    """Render a price-history chart and return the PNG path (or ``None``).

    Args:
        product_id: Used for the output filename.
        title: Product title for the chart heading.
        history: Observations (any order); filtered to the trailing window.
        out_dir: Directory to write the PNG into (created if missing).
        window_days: Trailing window to display.
        stats: Optional stats to annotate min/avg reference lines.
    """
    if not history:
        logger.debug("No history to chart for %s", product_id)
        return None

    history = sorted(history, key=lambda o: o.timestamp)
    cutoff = history[-1].timestamp - timedelta(days=window_days)
    points = [o for o in history if o.timestamp >= cutoff]
    if len(points) < 2:
        logger.debug("Not enough points (%d) to chart %s", len(points), product_id)
        return None

    xs = [o.timestamp for o in points]
    ys = [o.current_price for o in points]

    fig, ax = plt.subplots(figsize=(8, 4.2), dpi=120)
    ax.plot(xs, ys, marker="o", markersize=3, linewidth=1.6, color="#1f77b4",
            label="Price")
    ax.fill_between(xs, ys, min(ys) * 0.98, alpha=0.08, color="#1f77b4")

    if stats is not None:
        if stats.min_price_90d is not None:
            ax.axhline(stats.min_price_90d, color="#2ca02c", linestyle="--",
                       linewidth=1, label=f"90d min {stats.min_price_90d:g}")
        if stats.avg_price_90d is not None:
            ax.axhline(stats.avg_price_90d, color="#ff7f0e", linestyle=":",
                       linewidth=1, label=f"90d avg {stats.avg_price_90d:g}")

    # Highlight the current price point.
    ax.scatter([xs[-1]], [ys[-1]], color="#d62728", zorder=5, s=40,
               label=f"Now {ys[-1]:g} {currency}")

    short = (title[:60] + "…") if len(title) > 60 else title
    ax.set_title(short, fontsize=11)
    ax.set_ylabel(f"Price ({currency})")
    ax.grid(True, alpha=0.25)
    ax.legend(fontsize=8, loc="best")
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%d %b"))
    fig.autofmt_xdate(rotation=30)
    fig.tight_layout()

    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)
    safe_id = "".join(c for c in product_id if c.isalnum() or c in "-_")
    file_path = out_path / f"price_{safe_id}.png"
    try:
        fig.savefig(file_path)
    finally:
        plt.close(fig)
    logger.debug("Wrote chart %s", file_path)
    return str(file_path)
