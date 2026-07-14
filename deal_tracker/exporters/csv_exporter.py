"""CSV export of ranked deals.

Column order matches the spec exactly::

    timestamp, category, product_id, title, seller, seller_type,
    current_price, before_price, discount_percent, rating, review_count,
    confidence, score, product_url
"""

from __future__ import annotations

import csv
import logging
from pathlib import Path

from ..analytics.ranking import DealResult

logger = logging.getLogger(__name__)

CSV_COLUMNS = [
    "timestamp",
    "category",
    "product_id",
    "title",
    "seller",
    "seller_type",
    "current_price",
    "before_price",
    "discount_percent",
    "rating",
    "review_count",
    "confidence",
    "score",
    "product_url",
]


def _row(result: DealResult) -> dict[str, object]:
    p = result.product
    a = result.analysis
    return {
        "timestamp": p.timestamp.isoformat(),
        "category": p.category or "",
        "product_id": p.product_id,
        "title": p.title,
        "seller": p.seller_name or "",
        "seller_type": p.seller_type.value,
        "current_price": p.current_price if p.current_price is not None else "",
        "before_price": p.before_price if p.before_price is not None else "",
        "discount_percent": p.displayed_discount_percent
        if p.displayed_discount_percent is not None
        else "",
        "rating": p.rating if p.rating is not None else "",
        "review_count": p.review_count if p.review_count is not None else "",
        "confidence": a.confidence.value,
        "score": a.score,
        "product_url": p.product_url or "",
    }


def write_csv(results: list[DealResult], path: str, *, append: bool = False) -> str:
    """Write ranked deals to ``path`` as CSV. Returns the path written.

    When ``append`` is True and the file already exists, rows are appended
    without a repeated header — handy for accumulating daily snapshots.
    """
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    file_exists = out.exists()
    mode = "a" if append and file_exists else "w"
    with out.open(mode, newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=CSV_COLUMNS)
        if mode == "w" or not file_exists:
            writer.writeheader()
        for result in results:
            writer.writerow(_row(result))
    logger.info("Wrote %d deal(s) to %s", len(results), out)
    return str(out)
