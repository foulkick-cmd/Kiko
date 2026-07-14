"""End-to-end pipeline test with a stubbed store adapter (no network).

This exercises the full orchestration chain — storage, analytics, ranking,
CSV export, chart rendering and (dry-run) alerting — proving the modules wire
together correctly. The fetch step is stubbed so the test is offline and
deterministic; live fetching is verified separately with
``scripts/verify_fetcher.py``.
"""

from __future__ import annotations

from datetime import timedelta

import deal_tracker.scheduler.scheduler as sched
from deal_tracker.config import Config
from deal_tracker.models import Product, SellerType, utcnow
from deal_tracker.storage import SqliteStorage
from deal_tracker.stores.base import StoreAdapter


class _StubAdapter(StoreAdapter):
    store_key = "stub"
    display_name = "Stub"
    base_url = "https://example.test"

    def __init__(self, products):
        super().__init__()
        self._products = products

    def category_page_urls(self, config):
        return ["https://example.test/deals"]

    def iter_all_products(self, config, *, requests_fetcher, playwright_factory=None):
        yield from self._products


def _config(tmp_path) -> Config:
    cfg = Config()
    cfg.storage.sqlite_path = str(tmp_path / "kiko.db")
    cfg.output.csv_path = str(tmp_path / "deals.csv")
    cfg.output.charts_dir = str(tmp_path / "charts")
    cfg.output.log_dir = None  # type: ignore[assignment]
    cfg.alerts.state_path = str(tmp_path / "alert_state.json")
    cfg.scrape.allow_playwright = False
    return cfg


def test_full_run_detects_real_drop(tmp_path, monkeypatch):
    cfg = _config(tmp_path)

    # Seed 40 days of stable history at 3500 for the product.
    with SqliteStorage(cfg.storage.sqlite_path) as storage:
        base = utcnow() - timedelta(days=41)
        for i in range(41):
            p = Product(product_id="N53504907A", title="iPhone 15",
                        current_price=3500.0, seller_type=SellerType.NOON)
            p.timestamp = base + timedelta(days=i)
            storage.record(p)

    # The current scrape sees a genuine drop to 2900.
    current = Product(
        product_id="N53504907A", title="iPhone 15", store="stub",
        seller_name="noon", seller_type=SellerType.NOON,
        current_price=2900.0, before_price=3500.0,
        displayed_discount_percent=17.0, rating=4.6, review_count=1203,
        product_url="https://example.test/x/N53504907A/p/",
        category="mobiles",
    )

    monkeypatch.setattr(sched, "get_adapter", lambda key: _StubAdapter([current]))

    summary = sched.run_once(cfg, store_key="stub")

    assert summary.products_seen == 1
    assert summary.products_stored == 1
    assert summary.deals_ranked == 1
    # A genuine drop vs the last recorded price (3500 -> 2900) should alert.
    assert summary.alerts_sent == 1
    assert summary.csv_path is not None

    # CSV exists with our product, chart was rendered.
    import csv
    from pathlib import Path

    with open(summary.csv_path, encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert any(r["product_id"] == "N53504907A" and r["confidence"] == "High"
               for r in rows)
    assert list(Path(cfg.output.charts_dir).glob("*.png"))
