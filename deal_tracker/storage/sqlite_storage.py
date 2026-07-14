"""SQLite storage backend (default)."""

from __future__ import annotations

import logging
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

from ..models import PriceObservation, Product, SellerType, utcnow
from . import _schema
from .base import ScrapeRun, Storage

logger = logging.getLogger(__name__)


def _q(sql: str) -> str:
    """Rewrite ``{p}`` placeholders to SQLite's ``?`` paramstyle."""
    return sql.replace("{p}", "?")


class SqliteStorage(Storage):
    """Store snapshots in a local SQLite database file."""

    def __init__(self, path: str, *, window_days: int = 90) -> None:
        self.path = path
        self.window_days = window_days
        self._conn: sqlite3.Connection | None = None

    # --- lifecycle ----------------------------------------------------- #
    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(self.path)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
        return self._conn

    def initialize(self) -> None:
        cur = self.conn.cursor()
        for stmt in _schema.ddl(
            autoincrement_pk="INTEGER PRIMARY KEY AUTOINCREMENT",
            timestamp_type="TEXT",
        ):
            cur.execute(stmt)
        self.conn.commit()
        logger.debug("SQLite schema initialised at %s", self.path)

    def close(self) -> None:
        if self._conn is not None:
            self._conn.commit()
            self._conn.close()
            self._conn = None

    # --- scrape runs --------------------------------------------------- #
    def start_run(self, store: str) -> ScrapeRun:
        started = utcnow()
        cur = self.conn.execute(_q(_schema.START_RUN), (store, started.isoformat()))
        self.conn.commit()
        return ScrapeRun(run_id=cur.lastrowid, store=store, started_at=started)

    def finish_run(self, run: ScrapeRun) -> None:
        run.finished_at = run.finished_at or utcnow()
        self.conn.execute(
            _q(_schema.FINISH_RUN),
            (
                run.finished_at.isoformat(),
                run.products_seen,
                run.products_stored,
                run.status,
                run.note,
                run.run_id,
            ),
        )
        self.conn.commit()

    # --- products & history ------------------------------------------- #
    def upsert_product(self, product: Product) -> None:
        now = utcnow().isoformat()
        self.conn.execute(
            _q(_schema.UPSERT_PRODUCT),
            (
                product.product_id,
                product.store,
                product.title,
                product.seller_name,
                product.seller_type.value,
                product.product_image,
                product.product_url,
                product.category,
                product.currency,
                now,
                now,
            ),
        )
        self.conn.commit()

    def append_price(self, product: Product, run_id: int | None = None) -> None:
        self.conn.execute(
            _q(_schema.APPEND_PRICE),
            (
                product.product_id,
                run_id,
                product.seller_name,
                product.current_price,
                product.before_price,
                product.displayed_discount_percent,
                product.timestamp.isoformat(),
            ),
        )
        self.conn.commit()

    def last_price(self, product_id: str) -> float | None:
        row = self.conn.execute(_q(_schema.LAST_PRICE), (product_id,)).fetchone()
        return float(row[0]) if row and row[0] is not None else None

    def price_history(
        self, product_id: str, *, since: datetime | None = None
    ) -> list[PriceObservation]:
        if since is None:
            since = utcnow() - timedelta(days=self.window_days)
        rows = self.conn.execute(
            _q(_schema.PRICE_HISTORY), (product_id, since.isoformat())
        ).fetchall()
        return [self._row_to_obs(r) for r in rows]

    def all_product_ids(self) -> list[str]:
        rows = self.conn.execute(_schema.ALL_PRODUCT_IDS).fetchall()
        return [r[0] for r in rows]

    def get_product(self, product_id: str) -> Product | None:
        row = self.conn.execute(_q(_schema.GET_PRODUCT), (product_id,)).fetchone()
        if row is None:
            return None
        return Product(
            product_id=row["product_id"],
            title=row["title"],
            store=row["store"],
            seller_name=row["seller_name"],
            seller_type=SellerType(row["seller_type"]) if row["seller_type"] else SellerType.UNKNOWN,
            product_image=row["product_image"],
            product_url=row["product_url"],
            category=row["category"],
            currency=row["currency"] or "SAR",
        )

    # --- helpers ------------------------------------------------------- #
    @staticmethod
    def _row_to_obs(row: sqlite3.Row) -> PriceObservation:
        return PriceObservation(
            timestamp=datetime.fromisoformat(row["timestamp"]),
            current_price=float(row["current_price"]),
            before_price=float(row["before_price"]) if row["before_price"] is not None else None,
            displayed_discount=float(row["displayed_discount"])
            if row["displayed_discount"] is not None
            else None,
            seller=row["seller"],
        )
