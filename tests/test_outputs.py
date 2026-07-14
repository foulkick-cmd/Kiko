"""Tests for CSV export and chart rendering."""

from __future__ import annotations

import csv

from deal_tracker.analytics.confidence import score_confidence
from deal_tracker.analytics.discount_detection import analyze_history
from deal_tracker.analytics.ranking import DealResult, score_deal
from deal_tracker.charts import render_price_chart
from deal_tracker.config import RankingConfig
from deal_tracker.exporters import CSV_COLUMNS, write_csv
from deal_tracker.models import Product, SellerType


def _result(analytics_config, history) -> DealResult:
    p = Product(product_id="P1", title="Thing", category="mobiles",
                seller_name="noon", seller_type=SellerType.NOON,
                current_price=2900.0, before_price=3499.0,
                displayed_discount_percent=17.0, rating=4.6, review_count=1203,
                product_url="https://www.noon.com/x/P1/p/")
    stats, dclass = analyze_history(p.current_price, history, config=analytics_config)
    cs, conf = score_confidence(stats, p.seller_type, config=analytics_config)
    analysis = score_deal(p, stats, dclass, conf, cs, config=RankingConfig())
    return DealResult(product=p, analysis=analysis)


def test_csv_columns_and_content(tmp_path, analytics_config, stable_then_drop):
    result = _result(analytics_config, stable_then_drop)
    path = write_csv([result], str(tmp_path / "deals.csv"))
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        assert reader.fieldnames == CSV_COLUMNS
        row = next(reader)
    assert row["product_id"] == "P1"
    assert row["seller_type"] == "Noon"
    assert row["confidence"] == "High"
    assert float(row["current_price"]) == 2900.0


def test_csv_append_no_duplicate_header(tmp_path, analytics_config, stable_then_drop):
    result = _result(analytics_config, stable_then_drop)
    path = str(tmp_path / "deals.csv")
    write_csv([result], path, append=True)
    write_csv([result], path, append=True)
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().strip().splitlines()
    assert lines[0].startswith("timestamp,")
    # header once + two data rows
    assert len(lines) == 3


def test_render_price_chart(tmp_path, analytics_config, stable_then_drop):
    stats, _ = analyze_history(2900.0, stable_then_drop, config=analytics_config)
    out = render_price_chart("P1", "Apple iPhone 15", stable_then_drop,
                             out_dir=str(tmp_path), stats=stats)
    assert out is not None
    assert out.endswith(".png")
    from pathlib import Path
    assert Path(out).exists() and Path(out).stat().st_size > 0
