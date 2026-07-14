"""Tests for the SQLite storage backend."""

from __future__ import annotations

from datetime import timedelta

from deal_tracker.models import Product, SellerType, utcnow
from deal_tracker.storage import SqliteStorage


def _make_storage(tmp_path):
    return SqliteStorage(str(tmp_path / "test.db"), window_days=90)


def test_record_and_last_price(tmp_path):
    with _make_storage(tmp_path) as storage:
        p = Product(product_id="P1", title="Thing", current_price=100.0,
                    before_price=150.0, seller_name="noon",
                    seller_type=SellerType.NOON)
        storage.record(p)
        assert storage.last_price("P1") == 100.0

        p.current_price = 90.0
        p.timestamp = utcnow() + timedelta(seconds=1)
        storage.append_price(p)
        assert storage.last_price("P1") == 90.0  # newest wins


def test_history_never_overwritten(tmp_path):
    with _make_storage(tmp_path) as storage:
        base = utcnow() - timedelta(days=3)
        for i, price in enumerate([100.0, 100.0, 80.0]):
            p = Product(product_id="P2", title="T", current_price=price)
            p.timestamp = base + timedelta(days=i)
            storage.record(p)
        history = storage.price_history("P2")
        assert [o.current_price for o in history] == [100.0, 100.0, 80.0]
        # products table upserted once, history appended three times
        assert len(history) == 3


def test_get_product_and_ids(tmp_path):
    with _make_storage(tmp_path) as storage:
        storage.record(Product(product_id="P3", title="Widget",
                               current_price=10.0, seller_type=SellerType.MARKETPLACE))
        assert storage.all_product_ids() == ["P3"]
        got = storage.get_product("P3")
        assert got is not None
        assert got.title == "Widget"
        assert got.seller_type is SellerType.MARKETPLACE


def test_scrape_run_lifecycle(tmp_path):
    with _make_storage(tmp_path) as storage:
        run = storage.start_run("noon_sa")
        assert run.run_id is not None
        run.products_seen = 5
        run.products_stored = 4
        run.status = "success"
        storage.finish_run(run)  # should not raise


def test_window_filtering(tmp_path):
    with _make_storage(tmp_path) as storage:
        old = Product(product_id="P4", title="Old", current_price=50.0)
        old.timestamp = utcnow() - timedelta(days=200)
        storage.record(old)
        recent = Product(product_id="P4", title="Old", current_price=40.0)
        storage.record(recent)
        history = storage.price_history("P4")  # default 90-day window
        assert [o.current_price for o in history] == [40.0]
